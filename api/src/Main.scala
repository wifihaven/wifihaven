package wifihaven.api

import doobie.*
import doobie.implicits.*
import wifihaven.api.auth.*
import wifihaven.api.beta.BetaService
import wifihaven.api.cache.TimeStatusCache
import wifihaven.api.db.*
import wifihaven.api.metrics.{
  DbPoolMetrics,
  HttpMetrics,
  MetricsRuntime,
  RouterMetricsService,
  RouterPresenceMetrics,
}
import wifihaven.api.notify.Notifier
import wifihaven.api.observability.LoggingMiddleware
import wifihaven.api.observability.LokiDropMetrics
import wifihaven.api.policy.*
import wifihaven.api.routes.*
import wifihaven.api.usage.PartitionMaintenanceJob
import wifihaven.api.usage.RetentionSweepJob
import wifihaven.api.usage.RollupJobs
import wifihaven.api.usage.TimeUsedRollupJob
import wifihaven.shared.Clock
import zio.*
import zio.http.*
import zio.interop.catz.*
import zio.logging.*
import zio.logging.backend.SLF4J
import zio.metrics.connectors.prometheus.PrometheusPublisher
import zio.metrics.jvm.DefaultJvmMetrics

object Main extends ZIOAppDefault {

  // #1242: enable ZIO runtime metrics (fibers, GC, etc.) so they flow into the
  // Prometheus registry exposed at /metrics, alongside SLF4J logging.
  override val bootstrap =
    (Runtime.removeDefaultLoggers >>> SLF4J.slf4j) ++ Runtime.enableRuntimeMetrics

  def run =
    // #1247: the whole startup/serve body runs inside an explicit `ZIO.scoped`
    // so the rollup fibers can be `forkScoped` into it. That scope is *inner* to
    // the layer scope opened by `.provide(serverEnv)`, so on SIGTERM it closes
    // first — interrupting (and awaiting) the rollup fibers before the
    // transactor layer's finalizer closes the Hikari pool. Without this the
    // fibers race the pool close and each in-flight tick throws "pool has been
    // closed", logging spurious ERRORs and recording bogus error rows.
    ZIO
      .scoped(for {
        cfg       <- ZIO.service[AppConfig]
        _         <- ZIO.logInfo(s"WifiHaven API starting on ${cfg.http.host}:${cfg.http.port}")
        // #2266 (no-dark-by-default rule 3): log the resolved enabled/disabled state of every
        // config-gated optional feature so a silently-off feature (unset/lost secret, config drift)
        // is visible in boot logs — "disabled because nobody set the key" vs "disabled by choice"
        // is diagnosable from a single boot. Also inspectable live at loopback GET /api/debug/config.
        _         <- StartupFeatureReport.log(cfg)
        _         <- ZIO
          .logWarning(
            "WIFIHAVEN_DEBUG=1 set — /api/debug/* endpoints are MOUNTED (loopback only). " +
              "Disable in production.",
          )
          .when(cfg.debugEnabled)
        // #1248: readiness signal. False until migrations + ensureDefault + seeds
        // complete. /api/health reports 503 status=starting and the real routes
        // are gated to 503 while false, so we can bind the port immediately
        // (below) without ever serving a request against an unmigrated schema.
        readyRef  <- Ref.make(false)
        // Static definitions load from bundled resource files, not the DB, so we
        // read them up front and build the routes before binding — no DB work
        // happens before the port is open.
        templates <- AppTemplates.loadAll()
        bundled   <- BundledBlocklists.loadAll()
        templatesById = templates.map(t => t.slug -> t).toMap
        bundledById   = bundled.map(b => b.id -> b).toMap
        routesAndRegistry <- allRoutes(templatesById, bundledById, readyRef.get)
        (routes, wsRegistry, spaWsRegistry, spaEventBus) = routesAndRegistry
        withCors = SecurityHeaders.wrap(Cors.wrap(routes, cfg.cors))
        _ <- ZIO
          .logInfo(s"CORS enabled for origins: ${cfg.cors.origins.mkString(", ")}")
          .when(cfg.cors.origins.nonEmpty)
        // #1017: zio-http 3.0.1's RequestStreaming.Disabled default cap is 100 KiB;
        // /api/router/usage bodies routinely exceed that as mac_ip_tracking fills.
        // Bump to 4 MiB — well above any realistic single-bucket payload and below
        // Render's edge 413 threshold.
        serverConfig = Server.Config.default
          .port(cfg.http.port)
          .disableRequestStreaming(4 * 1024 * 1024)
        // #1248: bind the port FIRST. Render's port scan (the ~15-min-timeout gate)
        // only needs something listening, so it passes in milliseconds even when the
        // DB is pegged. The DB-heavy init below then runs while the health check
        // (a separate, longer gate) polls /api/health and waits for readiness — a
        // slow DB delays promotion, never the port bind. forkScoped so the server
        // is part of the run scope's lifecycle (interrupted on shutdown like the
        // rollup fibers, before the Hikari pool finalizer in serverEnv runs).
        serverFiber <- Server
          .serve(withCors)
          .provide(ZLayer.succeed(serverConfig) >>> Server.live)
          .forkScoped
        _         <- ZIO.logInfo("HTTP port bound; running DB-heavy startup behind readiness gate")
        // Service handles are pure layer reads (no DB I/O); resolve them up front
        // so the retried DB-init block below is purely the idempotent DB work.
        hsRepo    <- ZIO.service[HouseholdSettingsRepo]
        // #1771: handle for the startup-time global sentinel seed; the seed itself runs inside
        // the retried DB-init block below alongside ensureDefault.
        xaForSeed <- ZIO.service[Transactor[Task]]
        appRepoForSeed <- ZIO.service[AppRepo]
        blRepoForSeed  <- ZIO.service[BlocklistRepo]
        blCacheForSeed <- ZIO.service[BlocklistCache]
        blFetcher      <- ZIO.service[BlocklistFetcher]
        tz = java.time.ZoneId.systemDefault()
        // #1255: a transient DB outage (a few seconds during a resize/failover/
        // restart) used to throw a Hikari/PSQL connection error straight to
        // `main`, exiting the JVM and crash-looping until the DB returned. Wrap
        // the DB-heavy init so connection-class failures retry with bounded
        // exponential backoff + jitter while readiness stays false (/api/health
        // 503). Genuine errors (a bad migration, a constraint violation) still
        // fail fast and loud — see Database.isTransientConnectionError. The block
        // is idempotent (Flyway tracks applied migrations; ensureDefault is
        // ON CONFLICT DO NOTHING; the seeds are REPLACE / idempotent), so a retry
        // safely re-runs it from the start.
        _             <- Database.withStartupRetry(cfg.db.resilience, "startup DB init") {
          for {
            _  <- Database.runMigrations(cfg.db)
            _  <- ZIO.logInfo("Database migrations complete")
            // #334: ensure household_settings has its single row, defaulting the
            // daily-reset tz to the API server's local zone on first install.
            _  <- hsRepo.ensureDefault(tz)
            _  <- ZIO.logInfo(s"household_settings ensured (install-default tz=${tz.getId})")
            // #1771: seed the single global sentinel profile that PolicyService unions into
            // every other profile's BlockRules. Idempotent — V59's partial unique index
            // (`is_global = TRUE`) ensures at most one row ever exists, and ON CONFLICT
            // matches the [[HouseholdSettingsRepoLive.ensureDefault]] precedent so a restart
            // is a no-op. Kept here (not in V59) so the seed lands atomically with the code
            // that hides/uses the sentinel — see #1769 step 2.
            // #2130: household_id is stamped explicitly (this seeds the single
            // backfill install's sentinel) — never left to V65's DEFAULT 1.
            // #2286: per-household sentinels for provisioned households are seeded
            // at household-create time (approveAndProvision / HouseholdRepo.create);
            // this boot seed covers only the default install (household 1). Shares
            // the ONE ProfileSeed.insertGlobalSentinel definition (SSOT) so the seed
            // shape can't drift from the provisioning path.
            _  <- ProfileSeed
              .insertGlobalSentinel(wifihaven.shared.types.HouseholdId.Default)
              .transact(xaForSeed)
            _  <- ZIO.logInfo("global profile sentinel ensured (is_global=TRUE)")
            // #2355: backfill a household_billing row for any pre-existing household minted before
            // billing was seeded on every create path (else GET /api/billing 404s NoBillingRow for
            // it). Idempotent (NOT EXISTS guard) — a no-op once every household has a row. Mirrors
            // the global-sentinel boot seed above; one-shot cleanup after deploy tracked by #2359
            // (under #1608).
            bf <- HouseholdSeed.backfillMissingBilling.transact(xaForSeed)
            _  <- ZIO.logInfo(s"household_billing backfilled for rowless households (inserted=$bf)")
            // #768: seed the starter library of app templates. Idempotent —
            // operator host edits on previously-seeded apps are preserved.
            seedSummary <- AppTemplates.seed(appRepoForSeed, templates)
            _           <- ZIO.logInfo(
              s"app_templates seeded (${templates.size} templates): " +
                s"created=${seedSummary.created.size} ${seedSummary.created.mkString("[", ",", "]")}, " +
                s"repopulated=${seedSummary.repopulated.size} ${seedSummary.repopulated
                    .mkString("[", ",", "]")}, " +
                s"augmented=${seedSummary.augmented.size} " +
                seedSummary.augmented
                  .map(a => s"${a.slug}+[${a.addedHosts.mkString(",")}]")
                  .mkString("[", ",", "]") + ", " +
                s"preserved=${seedSummary.preserved.size}",
            )
            // #958: seed the bundled category blocklists. Inline lists pull hosts
            // straight from YAML; remote lists fetch from the declared upstream
            // URL (cached in-memory after first success — see BlocklistCache).
            // REPLACE semantics; remote-fetch failures leave existing rows alone.
            _           <- BundledBlocklists.seed(blRepoForSeed, blCacheForSeed, blFetcher, bundled)
            _           <- ZIO.logInfo(s"bundled blocklists seeded (${bundled.size} lists)")
            // #1602: the boot-time ScheduleSeeder.seedAndMigrate call lived here. The
            // legacy → named_schedules migration is complete on every household, and
            // re-running it resurrected schedules the operator deleted from the SPA
            // (the #1538 marker guard inverts on legitimate delete). ScheduleSeeder.scala
            // was deleted in #1709; the legacy `schedules` table is dropped in #1485.
          } yield ()
        }
        // #1248: migrations + ensureDefault + seeds are done — flip readiness so
        // /api/health returns 200 and the gated routes start serving real traffic.
        _             <- readyRef.set(true)
        _             <- ZIO.logInfo("readiness flipped → /api/health healthy, API routes ungated")
        // #809: scheduled re-aggregation of traffic_reports into the rollup
        // tables. #1230: cadence matches the tier each table is read at — hourly
        // tick re-rolls the trailing 2h every hour, daily tick re-rolls the
        // trailing 2 days once a day (reads of recent windows hit raw
        // traffic_reports, not these tables). #1247: forkScoped (not forkDaemon)
        // into the run scope so they are interrupted before the Hikari pool
        // closes on shutdown; the fork never blocks startup either way.
        rollupRepo    <- ZIO.service[wifihaven.api.db.RollupRepo]
        clockForJobs  <- ZIO.service[Clock]
        // #1849: wire the computed-snapshot cache's push-on-change. Install the ws registry as the
        // PolicyService push sink, then fork the reconcile ticker: every `snapshotCacheRefreshSeconds`
        // it rebuilds the snapshot once and pushes it to every connected router IFF the ETag moved
        // (schedule edges, daily-limit/usage transitions). This is the single rebuild point that
        // keeps the cache warm so REST polls read it (#1512) and ws routers are pushed on change —
        // not one ~500ms recompute per poll per router. forkScoped (like the rollup loops) so it is
        // interrupted before the Hikari pool closes on shutdown. The first tick fires immediately;
        // `reevaluate` swallows+logs a transient build failure and keeps the last good cache.
        // Idle cost: the tick rebuilds unconditionally even with zero connected routers / no recent
        // poll — but for an always-polling household fleet that is ≈ the pre-cache per-poll load
        // (which also ran every ~5s), so it is not a regression; gating on "any consumer present" is
        // a possible later refinement.
        // TODO(#1936): replace this fixed-interval ticker with a boundary-scheduled refresh — compute
        // the next schedule-edge / daily-reset / limit-exhaustion instant from the data buildSnapshot
        // already loads and sleep until then, so the ~2.5s build runs at real transitions instead of
        // every tick.
        policyForPush <- ZIO.service[PolicyService]
        // #1970 (S3): the publisher is now MULTI-subscriber (design §5.2.1). Sink 1 is the router
        // registry (full snapshot → `policy` frame), unchanged and synchronous so the #1846/#1849
        // router push timing is behavior-PRESERVED. Sink 2 bridges "policy changed" into the SPA
        // change bus as a `now` recompute trigger (a reevaluate can flip who's blocked/online), so
        // the SPA `now` push stays fresh on schedule edges / cap exhaustion / unpause without a poll.
        _             <- policyForPush.setPublisher(
          wifihaven.api.policy.PolicySnapshotPublisher.broadcast(
            List(
              new wifihaven.api.policy.PolicySnapshotPublisher {
                def publish(snap: wifihaven.shared.PolicySnapshot): UIO[Unit] =
                  wsRegistry.publishPolicy(snap)
              },
              new wifihaven.api.policy.PolicySnapshotPublisher {
                def publish(snap: wifihaven.shared.PolicySnapshot): UIO[Unit] =
                  // #1974 (S6a): the #1849 reevaluate (fixed-interval ticker + every policy mutation)
                  // can change a profile's remaining-minutes / over-limit WITHOUT new usage (schedule
                  // boundary, cap exhaustion, unpause, +Time), so it is a `timeStatus`/`appUsage` push
                  // write site too (design §5.2). `now` for the dashboard, `TimeStatusChanged` for the
                  // live screen-time surface.
                  spaEventBus.publish(SpaEvent.NowChanged) *>
                    spaEventBus.publish(SpaEvent.TimeStatusChanged)
              },
            ),
          ),
        )
        _             <- policyForPush.reevaluate
          .repeat(Schedule.fixed(cfg.policy.snapshotCacheRefreshInterval))
          .forkScoped
        _             <- RollupJobs.hourlyLoop(rollupRepo, appRepoForSeed, clockForJobs).forkScoped
        _ <- RollupJobs.dailyLoop(rollupRepo, appRepoForSeed, clockForJobs, tz).forkScoped
        // #1265: connection-event rollup tier — same cadence/lookback as the
        // traffic rollups, re-rolling the trailing windows into
        // connection_events_hourly / _daily. forkScoped so they're interrupted
        // before the Hikari pool closes on shutdown.
        connRepoForJobs <- ZIO.service[ConnectionEventRepo]
        _ <- RollupJobs.connEventsHourlyLoop(connRepoForJobs, rollupRepo, clockForJobs).forkScoped
        _ <- RollupJobs
          .connEventsDailyLoop(connRepoForJobs, rollupRepo, clockForJobs, tz)
          .forkScoped
        // #1160: per-(profile, today) `used_seconds` cache. Tick aggregates today's presence into
        // a watermarked row; the read path adds a live tail of buckets after the watermark, so
        // /api/time/status/summary serves a rollup + small live aggregation instead of a full
        // per-request day scan.
        // TODO(#1221): bound the connection hold time of this rollup recompute and
        // of the per-profile UsageSeries read (#1099) so neither can monopolize the
        // shared Hikari pool for tens of seconds under load — e.g. a smaller batch,
        // a time budget, or a separate small pool for the rollup work. The pool-size
        // bump (this PR) and the dedicated connect EC are the first-order fix; this
        // is the durability follow-up tracked in #1221.
        timeRollupRepo    <- ZIO.service[wifihaven.api.db.TimeUsedRollupRepo]
        appRollupRepoForJ <- ZIO.service[wifihaven.api.db.AppUsedRollupRepo]
        profileRepoForJ   <- ZIO.service[wifihaven.api.db.ProfileRepo]
        deviceRepoForJ    <- ZIO.service[wifihaven.api.db.DeviceRepo]
        stlRepoForJ       <- ZIO.service[wifihaven.api.db.AppTimeLimitRepo]
        trafficRepoForJ   <- ZIO.service[wifihaven.api.db.TrafficReportRepo]
        ambientRepoForJ   <- ZIO.service[wifihaven.api.db.AmbientHostsRepo]
        _                 <- TimeUsedRollupJob
          .loop(
            timeRollupRepo,
            appRollupRepoForJ,
            rollupRepo,
            profileRepoForJ,
            deviceRepoForJ,
            stlRepoForJ,
            trafficRepoForJ,
            hsRepo,
            clockForJobs,
            ambientRepoForJ,
          )
          .forkScoped
        // #2077: the ambient-host learner — recomputes yesterday's isolated-span host counts
        // (idempotent upsert), prunes days that aged out of the learning window, refreshes the
        // presence_ambient_hosts gauge. Runs regardless of ambient_gate_enabled so the operator
        // can inspect the would-be ambient set before flipping the gate on.
        _                 <- wifihaven.api.usage.AmbientLearnJob
          .loop(
            ambientRepoForJ,
            rollupRepo,
            profileRepoForJ,
            deviceRepoForJ,
            stlRepoForJ,
            trafficRepoForJ,
            hsRepo,
            clockForJobs,
          )
          .forkScoped
        _                 <- ZIO.logInfo(
          "rollup fibers forked (hourly + daily + time_used_daily + conn_events_hourly + conn_events_daily)",
        )
        // #1970 (S3): fork the SPA-websocket change-source consumer. It drains the SpaEventBus and
        // fans out subscription-gated, role-filtered `now`/`connectionEvents`/`stale` frames over
        // `/api/ws` (design §5.2). forkScoped (inside `run`) so it is interrupted before the Hikari
        // pool closes on shutdown, like the rollup loops. `now` rebuilds via the shared
        // DashboardNowRoutes.computeNow builder (SSOT). #1974 (S6a): the same consumer also drives the
        // live `timeStatus`/`appUsage` pushes — wire its extra deps (canonical minutes, settings, the
        // per-child profile filter). It is additive and never blocks ingest.
        timeStatusForPush <- ZIO.service[wifihaven.api.policy.TimeStatusService]
        upRepoForPush     <- ZIO.service[UserProfileRepo]
        ambientRepoPush   <- ZIO.service[wifihaven.api.db.AmbientHostsRepo]
        _                 <- SpaPush.run(
          spaEventBus,
          spaWsRegistry,
          trafficRepoForJ,
          rollupRepo,
          connRepoForJobs,
          deviceRepoForJ,
          profileRepoForJ,
          appRepoForSeed,
          stlRepoForJ,
          clockForJobs,
          Some(SpaPush.TimeUsageDeps(timeStatusForPush, hsRepo, upRepoForPush, ambientRepoPush)),
        )
        _                 <- ZIO.logInfo("spa-ws push consumer fiber forked")
        // #1243: poll the HikariCP MXBean into the Prometheus pool gauges. forkDaemon so it lives
        // for the process and never blocks startup.
        dbPool            <- ZIO.service[Database.DbPool]
        _                 <- DbPoolMetrics.loop(dbPool.dataSource, dbPool.maxSize).forkDaemon
        _                 <- ZIO.logInfo("db-pool metrics fiber forked")
        // #1204: publish agent_connected_routers — routers seen within the window.
        // forkDaemon: a read-only periodic SELECT, never blocks startup.
        routerRepoMetric  <- ZIO.service[RouterRepo]
        _                 <- RouterPresenceMetrics.loop(routerRepoMetric).forkDaemon
        _                 <- ZIO.logInfo("router-presence metrics fiber forked")
        // #1885: poll the loki4j appender's fail-open drop counter into
        // loki_logs_dropped_total. forkDaemon; no-ops on local/test where the
        // LOKI appender isn't present (deployed-env gate lives in logback.xml).
        _                 <- LokiDropMetrics.loop().forkDaemon
        _                 <- ZIO.logInfo("loki-drop metrics fiber forked")
        _                 <- ZIO
          .logWarning(
            "WIFIHAVEN_SEED_TEST_BLOCKLISTS=1 set — seeding dev test_ads/test_social. " +
              "Disable in production.",
          )
          .when(cfg.seedTestBlocklists)
        _                 <- BundledBlocklists
          .seed(blRepoForSeed, blCacheForSeed, blFetcher, BundledBlocklists.devTestBlocklists)
          .when(cfg.seedTestBlocklists)
        // #811: daily retention sweep. Forks a daemon fiber that runs at 03:00 UTC.
        // Multi-instance-safe via Postgres advisory lock — losing instances skip
        // the tick rather than racing on the same DELETE.
        xaForJobs         <- ZIO.service[Transactor[Task]]
        _                 <- RetentionSweepJob.start(xaForJobs, clockForJobs)
        // #2137: the beta→paid flip lifecycle. Forks an hourly daemon that starts the cohort flip
        // clock once the active-beta threshold is reached, emits T-30/T-7 conversion notices, and
        // flips unconverted households to `lapsed` at window end (enforcement stops permissively —
        // never brick the network). Idempotent via DB latches + an in-memory notice dedup.
        flipServiceForJob <- ZIO.service[wifihaven.api.billing.FlipService]
        _                 <- wifihaven.api.billing.FlipService.start(flipServiceForJob)
        // #808: durable fix for the 2026-06-29 P0 (#2053). Auto-creates the next
        // cfg.partition.weeksAhead weekly partitions on traffic_reports + connection_events at
        // startup and every 24h, so the fixed runway V41/V42 seeded can never silently exhaust.
        // Emits partition_weeks_ahead{table} for the runway alert. Advisory-locked (multi-instance
        // safe), forkDaemon inside start.
        partitionRepo     <- ZIO.service[PartitionRepo]
        _                 <- PartitionMaintenanceJob.start(
          partitionRepo,
          cfg.partition.weeksAheadClamped,
          clockForJobs,
        )
        // Keep the process alive on the already-bound server fiber; exits if it dies.
        _                 <- serverFiber.join
      } yield ())
      .provide(serverEnv)

  private val serverEnv =
    AppConfig.layer >+>
      ZLayer.fromZIO(ZIO.serviceWith[AppConfig](_.db)) >+>
      Database.transactorLayer >+>
      Repos.all >+>
      ZLayer.fromZIO(ZIO.serviceWith[AppConfig](_.jwt)) >+>
      Clock.live >+>
      AuthService.layer >+>
      // #1515: per-app rollup read accessor, wired ahead of TimeStatusService so the snapshot's
      // per-app cap reads `app_used_daily` + a live tail on the rollup path.
      wifihaven.api.usage.AppUsedRollupService.layer >+>
      TimeStatusService.layer >+>
      PolicyService.layer >+>
      TimeStatusCache.live() >+>
      BlocklistCache.live >+>
      BlocklistFetcher.live >+>
      // #578: email-notification transport. The EmailConfig projection feeds the config-gated
      // EmailSender (Resend over the JDK HttpClient; a no-op when unconfigured), and Notifier.layer
      // resolves each alert's household → notify_email and sends via it. HouseholdSettingsRepo comes
      // from Repos.all above; the >+> chain keeps it in scope here.
      ZLayer.fromZIO(ZIO.serviceWith[AppConfig](_.email)) >+>
      wifihaven.api.notify.EmailSender.layer >+>
      Notifier.layer >+>
      // #2135 (multi-tenant P5-5): Stripe billing. The StripeConfig slice + the external StripeClient
      // (disabled no-op when no secret key is set — self-hosted installs never bill) + the
      // BillingService state machine (needs HouseholdBillingRepo from Repos.all + Clock).
      ZLayer.fromZIO(ZIO.serviceWith[AppConfig](_.stripe)) >+>
      wifihaven.api.billing.StripeClient.layer >+>
      wifihaven.api.billing.BillingService.layer >+>
      // #2137 (multi-tenant P5-6): the beta→paid flip lifecycle service — the cohort flip clock,
      // T-30/T-7 conversion notices, and the window-end beta→lapsed sweep (reusing the ONE billing
      // state machine + the Notifier). Reads BetaCohortRepo/HouseholdBillingRepo/HouseholdRepo (from
      // Repos.all), the Notifier, and PolicyService (to rebuild permissive snapshots at flip).
      wifihaven.api.billing.FlipService.layer >+>
      // #2199 (support intake B): Plain helpdesk integration. The SupportConfig slice + the external
      // PlainClient (disabled no-op when no Plain API key — self-hosted / unconfigured ships dark) +
      // the SupportService (household→customer mapping + server-signed widget identity; reads
      // UserRepo/HouseholdRepo/HouseholdBillingRepo from Repos.all above).
      ZLayer.fromZIO(ZIO.serviceWith[AppConfig](_.support)) >+>
      wifihaven.api.support.PlainClient.layer >+>
      wifihaven.api.support.SupportService.layer >+>
      // #2200 (support intake C): the Claude responder's external transports — the cloud-agent
      // dispatcher (Managed Agents session per authenticated-origin inbound message — UI-originated
      // or a #2307 registered-admin email) and the GitHub issue-filing client (fine-grained
      // Issues:write-only bot token). #2265: each runs iff its
      // EXPLICIT enable flag is true (validated loudly at boot); off is logged + health-visible.
      wifihaven.api.support.CloudAgentDispatcher.layer >+>
      wifihaven.api.support.GithubIssueClient.layer >+>
      // #2203 (press intake C): the public press/PR responder's cloud-agent dispatcher (a SEPARATE
      // Managed Agent persona per inbound press message). Reuses the shared ManagedAgents transport;
      // runs iff press.responderEnabled (#2265), else the logged no-op. The press reply is emailed
      // via the already-wired #578 EmailSender (retrieved below); no press-specific transport.
      ZLayer.fromZIO(ZIO.serviceWith[AppConfig](_.press)) >+>
      wifihaven.api.press.PressAgentDispatcher.layer >+>
      // #1242: Prometheus publisher + snapshot listener, and JVM metrics collectors.
      MetricsRuntime.prometheus() >+>
      DefaultJvmMetrics.live

  private def allRoutes(
      templates: Map[wifihaven.shared.types.AppTemplateId, AppTemplate],
      bundledBlocklists: Map[wifihaven.shared.types.BlocklistId, BundledBlocklist],
      ready: UIO[Boolean],
  ) =
    for {
      auth           <- ZIO.service[AuthService]
      userRepo       <- ZIO.service[UserRepo]
      entitlements   <- ZIO.service[EntitlementsRepo]
      upRepo         <- ZIO.service[UserProfileRepo]
      profileRepo    <- ZIO.service[ProfileRepo]
      namedSchedRepo <- ZIO.service[NamedScheduleRepo]
      hsRepo         <- ZIO.service[HouseholdSettingsRepo]
      tlRepo         <- ZIO.service[TimeLimitRepo]
      atlRepo        <- ZIO.service[AppTimeLimitRepo]
      deviceRepo     <- ZIO.service[DeviceRepo]
      blRepo         <- ZIO.service[BlocklistRepo]
      blCache        <- ZIO.service[BlocklistCache]
      blFetcher2     <- ZIO.service[BlocklistFetcher]
      usageRepo      <- ZIO.service[TimeUsageRepo]
      extRepo        <- ZIO.service[TimeExtensionRepo]
      routerRepo     <- ZIO.service[RouterRepo]
      trafficRepo    <- ZIO.service[TrafficReportRepo]
      rollupRepo2    <- ZIO.service[RollupRepo]
      connRepo       <- ZIO.service[ConnectionEventRepo]
      blockEvRepo    <- ZIO.service[BlockEventRepo]
      alertRepo      <- ZIO.service[AlertRepo]
      appRepo        <- ZIO.service[AppRepo]
      appRollupRepo  <- ZIO.service[wifihaven.api.db.AppUsedRollupRepo]
      // #2132 (multi-tenant P5-2): the beta request → provisioning → invite-accept pipeline repos.
      householdRepo  <- ZIO.service[HouseholdRepo]
      betaRepo       <- ZIO.service[BetaRequestRepo]
      // #2308: single-use, short-TTL password-reset tokens (V77) for the forgot-password flow.
      resetTokenRepo <- ZIO.service[PasswordResetTokenRepo]
      ambientRepoR   <- ZIO.service[wifihaven.api.db.AmbientHostsRepo]
      notifier       <- ZIO.service[Notifier]
      policy         <- ZIO.service[PolicyService]
      timeStatus     <- ZIO.service[wifihaven.api.policy.TimeStatusService]
      cfg            <- ZIO.service[AppConfig]
      clock          <- ZIO.service[Clock]
      timeCache      <- ZIO.service[TimeStatusCache]
      xa             <- ZIO.service[Transactor[Task]]
      promPublisher  <- ZIO.service[PrometheusPublisher]
      routerAuth = new RouterAuthLive(routerRepo)
      routerMetrics         <- RouterMetricsService.make
      // #2079: per-source-IP rate limit on the unauthenticated login route — 10
      // attempts / 15 min. #2081: per-source-IP rate limit on the unauthenticated
      // access-requests route (block-page kid request) — 20 / 5 min, on top of the
      // existing per-(mac,host) debounce (which a varying host bypasses).
      loginRateLimiter      <- RateLimiterLive.make(maxAttempts = 10, windowSeconds = 15 * 60)
      accessReqRateLimiter  <- RateLimiterLive.make(maxAttempts = 20, windowSeconds = 5 * 60)
      // #2132: per-source-IP rate limit on the unauthenticated beta-intake route — 5 / hour, a
      // slow cadence (a genuine prospect requests once), on top of the idempotent-email dedup.
      betaReqRateLimiter    <- RateLimiterLive.make(maxAttempts = 5, windowSeconds = 60 * 60)
      // #2308: per-source-IP rate limits on the two unauthenticated password-reset routes. Requests
      // are tight (5 / hour) to blunt email-bombing a known address; the reset consume is a touch
      // looser (10 / 15 min) so a legitimate user can retry a weak password without lockout, while
      // still bounding token brute-force.
      forgotPwRateLimiter   <- RateLimiterLive.make(maxAttempts = 5, windowSeconds = 60 * 60)
      resetPwRateLimiter    <- RateLimiterLive.make(maxAttempts = 10, windowSeconds = 15 * 60)
      // #2132: the beta pipeline service (slug derivation, invite token mint + TTL, provisioning,
      // accept). Clock-injected so the invite TTL is TestClock-driven in specs.
      // #2135: the billing state machine (Checkout/Portal/webhook + the provisioning Customer seam).
      billing               <- ZIO.service[wifihaven.api.billing.BillingService]
      // #2137: the cohort flip lifecycle service (shared with the BetaFlipJob forked in the run
      // scope). Surfaces the flip-window state to the SPA billing page.
      flipService           <- ZIO.service[wifihaven.api.billing.FlipService]
      // #2199: the support integration service (server-signed widget identity + household→Plain
      // customer mapping). Dark unless the Plain keys are set.
      support               <- ZIO.service[wifihaven.api.support.SupportService]
      // #2200: the Claude responder — the webhook→gate→dispatch pipeline plus the cloud agent's
      // token-authenticated callback endpoints. Runs iff support.responderEnabled (#2265). Issue
      // filing is rate-limited per-thread (3/h) and globally (10/h) — the #2241 volume control the
      // support_agent_action_total{op="issue"} alert watches.
      billingRepo           <- ZIO.service[wifihaven.api.db.HouseholdBillingRepo]
      plainClient           <- ZIO.service[wifihaven.api.support.PlainClient]
      agentDispatcher       <- ZIO.service[wifihaven.api.support.CloudAgentDispatcher]
      githubIssues          <- ZIO.service[wifihaven.api.support.GithubIssueClient]
      issueThreadLimiter    <- RateLimiterLive.make(maxAttempts = 3, windowSeconds = 60 * 60)
      issueGlobalLimiter    <- RateLimiterLive.make(maxAttempts = 10, windowSeconds = 60 * 60)
      // Cost guardrails (2026-07-16): each dispatched agent session bills real tokens, so dispatch
      // is hard-capped — 4/hour per thread and 50/day globally. With a worst-case ~$0.50/draft the
      // global cap bounds even sustained abuse at pocket change; normal beta volume never hits it.
      dispatchThreadLimiter <- RateLimiterLive.make(maxAttempts = 4, windowSeconds = 60 * 60)
      dispatchGlobalLimiter <- RateLimiterLive.make(maxAttempts = 50, windowSeconds = 24 * 60 * 60)
      // #2307: the static-reject cap for unregistered cold email. Cheap (a fixed string, no AI), so
      // the ceiling is generous, but bounded globally so a spammer forging many cold-email threads
      // can't turn us into a reply-amplification (backscatter) source.
      rejectLimiter         <- RateLimiterLive.make(maxAttempts = 100, windowSeconds = 60 * 60)
      supportResponder = wifihaven.api.support.SupportResponder(
        cfg.support,
        householdRepo,
        userRepo,
        billingRepo,
        deviceRepo,
        profileRepo,
        plainClient,
        githubIssues,
        agentDispatcher,
        clock,
        issueThreadLimiter,
        issueGlobalLimiter,
        dispatchThreadLimiter,
        dispatchGlobalLimiter,
        rejectLimiter,
      )
      // #2203: the PRESS responder — the public inbound webhook (from the Cloudflare Email Worker)
      // → rate-cap → dispatch pipeline, plus the press agent's reply-target-bound EMAIL callback.
      // Runs iff press.responderEnabled (#2265). NO household gate and NO data token (public
      // audience); the reply is emailed to the sender via the shared #578 EmailSender (destination
      // locked into the session token, so a hijacked agent cannot redirect it).
      pressDispatcher            <- ZIO.service[wifihaven.api.press.PressAgentDispatcher]
      pressEmailSender           <- ZIO.service[wifihaven.api.notify.EmailSender]
      // #2296: the press correspondence log (V71). Recording is fail-open inside the responder; the
      // household-1-only read route (PressRoutes) reads it back.
      pressLog                   <- ZIO.service[wifihaven.api.db.PressMessageRepo]
      // Same dispatch cost caps as the #2200 support responder (4/sender/hour, 50/day global) — each
      // dispatched session bills tokens, and the global cap is the true ceiling for this public
      // endpoint (the per-sender key is best-effort — an anonymous From is trivially rotated).
      pressDispatchSenderLimiter <- RateLimiterLive.make(maxAttempts = 4, windowSeconds = 60 * 60)
      pressDispatchGlobalLimiter <-
        RateLimiterLive.make(maxAttempts = 50, windowSeconds = 24 * 60 * 60)
      pressResponder = wifihaven.api.press.PressResponder(
        cfg.press,
        pressEmailSender,
        pressDispatcher,
        pressLog,
        clock,
        pressDispatchSenderLimiter,
        pressDispatchGlobalLimiter,
      )
      // #2233: the operator-run press-OUTREACH send capability. The media-contacts manifest + the
      // sendable release template load from bundled resources at boot (fail-fast if malformed), like
      // AppTemplates / BundledBlocklists. The send endpoints stay dark until press.outreach.enabled.
      pressContacts <- wifihaven.api.press.PressOutreach.loadContacts()
      pressRelease  <- wifihaven.api.press.PressOutreach.loadReleaseTemplate()
      betaService   = BetaService(
        betaRepo,
        householdRepo,
        userRepo,
        auth,
        notifier,
        clock,
        cfg.beta,
        billing,
      )
      // #2308: the forgot/reset-password service — mints single-use short-TTL tokens, emails the
      // reset link via the #578 Notifier, and on reset bumps token_version to invalidate prior JWTs.
      // Clock-injected so the token TTL is TestClock-driven in specs.
      passwordReset = PasswordResetServiceLive(
        userRepo,
        resetTokenRepo,
        auth,
        notifier,
        cfg.email,
        clock,
      )
      // #1970 (S3): the SPA-websocket change-source bus (design §5.2.2). The write sites publish
      // change events here; the SpaPush consumer (forked in the run scope) drains it and fans out
      // role-filtered, subscription-gated `now`/`connectionEvents`/`stale` frames.
      spaEventBus <- SpaEventBus.make
      // #1846: the transport-agnostic ingest service shared by the REST ingest routes and the new
      // websocket transport, plus the per-router ws connection registry. #1970: it also publishes
      // SPA change events (new connection-events head + `now` trigger + `stale{alerts}`) to the bus.
      routerIngest = new RouterIngestService(
        routerRepo,
        trafficRepo,
        usageRepo,
        deviceRepo,
        connRepo,
        alertRepo,
        hsRepo,
        spaEventBus,
      )
      wsRegistry    <- RouterWsRegistry.make
      // #1968: the browser-facing SPA websocket registry (S1). A FORK of the router registry pattern
      // (design §5.1), not a generalization — keyed by per-connection id + role with a subscription
      // set. Additive: REST stays the fallback throughout the rollout (no flag day).
      spaWsRegistry <- SpaWsRoutes.registry
      dbHealthCheck = sql"SELECT 1".query[Int].unique.transact(xa).unit
    } yield {
      // #1177: split the route composition into typed chunks. A flat `++` chain across
      // 18+ Routes was hitting Scala 3's type-inference recursion limit on CI (Linux
      // JDK 21 default stack), failing with "Recursion limit exceeded" at the first
      // `++` even though local macOS builds happened to fit. Explicit `Routes[Any, Response]`
      // ascriptions on the chunks ground the inference per chunk so the final fold is
      // a short, well-typed concatenation.
      // #1248: the observability endpoints are NOT readiness-gated. /api/health
      // carries its own not-ready logic (503 status=starting until migrations +
      // seeds finish) so Render's health check can observe startup progress
      // while the port is already bound; /metrics reads only the in-memory
      // Prometheus registry (no DB) and must stay scrapeable during a slow
      // startup — exactly the scenario this gate exists for. Everything else is
      // gated below.
      //
      // #1204: /metrics is deliberately left OUT of HttpMetrics.instrument below —
      // the scrape endpoint should not count itself, and counting it would just
      // add a self-referential series that climbs with Prometheus' poll rate.
      val healthRoutes: Routes[Any, Response] =
        HealthRoutes.routes(ready, dbHealthCheck)

      val metricsRoutes: Routes[Any, Response] =
        MetricsRoutes.routes(cfg.metrics, promPublisher)

      val systemRoutes: Routes[Any, Response] =
        VersionRoutes.routes(wifihaven.api.BuildInfo.fromEnv) ++
          AuthRoutes.routes(auth, userRepo, upRepo, loginRateLimiter) ++
          // #2308: forgot-password request (public) + token consume / reset (public). Both rate-
          // limited + enumeration-safe.
          PasswordResetRoutes.routes(passwordReset, forgotPwRateLimiter, resetPwRateLimiter) ++
          // #2132: beta request intake (public) + operator approval/reject + invite accept (public).
          BetaRoutes.routes(auth, betaService, betaRepo, userRepo, betaReqRateLimiter) ++
          // #2135: billing status (admin) + Checkout/Portal starts (admin) + signature-verified
          // Stripe webhook (public). Disabled installs 404 the admin surfaces and no-op the webhook.
          BillingRoutes.routes(
            auth,
            billing,
            // #2137: a read failure degrades to "no window" rather than failing the billing page.
            flipService.currentWindow.orElseSucceed(
              wifihaven.api.billing.FlipService.FlipWindow(open = false, flipDate = None),
            ),
          ) ++
          // #2199: admin-only server-signed Plain widget identity. Dark (returns {configured:false})
          // until the operator sets the Plain widget app id + identity secret.
          SupportRoutes.routes(auth, support) ++
          // #2200: the Plain new-message webhook (signature-verified, authenticated-origin-gated
          // cloud-agent dispatch — UI-originated or a #2307 registered-admin email) + the agent's
          // token-authenticated callback endpoints. Off unless
          // support.responderEnabled is set explicitly (#2265): webhook no-ops, agent endpoints 404.
          SupportAgentRoutes.routes(supportResponder) ++
          // #2203: the PRESS/PR inbox — a public, unauthenticated inbound webhook from the Cloudflare
          // Email Worker (signature-verified, NO household gate) + the press agent's
          // token-authenticated EMAIL-reply callback (destination locked into the token). Off unless
          // press.responderEnabled is set explicitly (#2265): webhook no-ops, agent endpoint 404.
          // The press agent holds no household data token (public audience).
          PressAgentRoutes.routes(pressResponder) ++
          // #2296: the household-1-only admin read of the recorded press correspondence log. Press is
          // a company-global channel (no household_id); a non-default household gets 404 (not 403),
          // so the log's existence is not disclosed across the tenant boundary.
          PressRoutes.routes(auth, pressLog) ++
          // #2233: the operator-only press-OUTREACH send surface (preview = dry-run default; send
          // requires confirm:true + resolved fill tokens + configured email). Both endpoints 404
          // unless press.outreach.enabled (dark-by-default #2265) and for any non-operator household.
          PressOutreachRoutes.routes(
            auth,
            cfg.pressOutreach,
            cfg.email.enabled,
            pressEmailSender,
            pressLog,
            pressContacts,
            pressRelease,
          ) ++
          ProfileRoutes.routes(
            auth,
            profileRepo,
            tlRepo,
            upRepo,
            userRepo,
            namedSchedRepo,
            // #1538: same cache instance TimeRoutes uses, so a schedule detach busts the
            // per-profile time-status entry instead of leaving a stale "paused for schedule".
            timeCache,
            // #1849: bust the computed-snapshot cache on a profile/schedule mutation.
            // #1970 (S3): also nudge `stale{profiles}` so an open SPA re-fetches the class-(3)
            // profiles resource (design §3.2) — composed onto the existing invalidate callback, so
            // every profile mutation publishes without touching the route's signature.
            policy.invalidate <* spaEventBus.publish(SpaEvent.Stale(StaleTopic.Profiles)),
          ) ++
          ScheduleRoutes.routes(
            auth,
            namedSchedRepo,
            policy.invalidate <* spaEventBus.publish(SpaEvent.Stale(StaleTopic.Schedules)),
          ) ++
          HouseholdSettingsRoutes.routes(auth, hsRepo, policy.invalidate) ++
          DeviceRoutes.routes(
            auth,
            deviceRepo,
            upRepo,
            profileRepo,
            policy.invalidate <* spaEventBus.publish(SpaEvent.Stale(StaleTopic.Devices)),
          )

      val statsRoutes: Routes[Any, Response] =
        TimeRoutes.routes(
          auth,
          deviceRepo,
          tlRepo,
          atlRepo,
          trafficRepo,
          extRepo,
          profileRepo,
          upRepo,
          hsRepo,
          timeStatus,
          clock,
          timeCache,
          // #1849: a +Time grant can lift a TimeLimit block, so bust the computed-snapshot cache.
          policy.invalidate,
          // #1974 (S6a): the grant changes remaining-minutes — push fresh timeStatus/appUsage live.
          spaEventBus,
          ambientRepoR,
        ) ++
          LogRoutes.routes(auth, connRepo, upRepo) ++
          UsageRoutes.routes(
            auth,
            deviceRepo,
            trafficRepo,
            upRepo,
            profileRepo,
            appRepo,
            rollupRepo2,
            hsRepo,
            atlRepo,
            appRollupRepo,
            clock,
            ambientRepoR,
          ) ++
          DashboardNowRoutes.routes(
            auth,
            trafficRepo,
            connRepo,
            deviceRepo,
            profileRepo,
            upRepo,
            atlRepo,
            clock,
          ) ++
          BlocklistRoutes.routes(auth, blRepo, blCache, blFetcher2, bundledBlocklists) ++
          // #335: kid-side block page support. Unauthenticated — the router DNATs blocked
          // traffic to the SPA's /blocked, which calls GET /api/blocked?mac=&host= to render
          // the reason + today's usage. Accidentally removed from registration in #1060;
          // re-wired here so the kid-friendly path stops falling back to the legacy
          // router-supplied `reason` query string.
          BlockedRoutes.routes(
            policy,
            deviceRepo,
            profileRepo,
            blRepo,
            timeStatus,
            hsRepo,
            clock,
          )

      val routerAndAdminRoutes: Routes[Any, Response] =
        RouterRoutes.routes(routerRepo, policy, routerAuth, blockEvRepo) ++
          AdminRouterRoutes.routes(auth, routerRepo, userRepo, entitlements) ++
          RollupAdminRoutes.routes(auth, rollupRepo2) ++
          RouterIngestRoutes.routes(routerAuth, routerIngest) ++
          // #1846: additive websocket transport. REST ingest/poll/metrics above stay fully live.
          RouterWsRoutes.routes(
            routerAuth,
            wsRegistry,
            routerIngest,
            routerMetrics,
            routerRepo,
            policy,
          ) ++
          RouterMetricsRoutes.routes(routerAuth, routerMetrics) ++
          // #1968/#1969: additive browser-facing websocket endpoint (`GET /api/ws`). S2 (#1969)
          // authorizes the upgrade via the `wh_ws` cookie (existing AuthService.verify) + the §8
          // Origin allowlist (cfg.ws); push sources are S3/S4. The SPA has no ws client yet (S5), so
          // this reads idle in prod until then.
          SpaWsRoutes.routes(auth, spaWsRegistry, clock, cfg.ws) ++
          AlertRoutes.routes(
            auth,
            alertRepo,
            deviceRepo,
            profileRepo,
            extRepo,
            appRepo,
            hsRepo,
            notifier,
            clock,
            accessReqRateLimiter,
          ) ++
          AppRoutes.routes(auth, appRepo, profileRepo, upRepo, blRepo, templates) ++
          AdminDebugRoutes.routes(auth, policy) ++
          DebugRoutes.routes(
            cfg.debugEnabled,
            cfg,
            deviceRepo,
            profileRepo,
            connRepo,
            usageRepo,
            trafficRepo,
            clock,
            timeCache,
          )

      val spaRoutes: Routes[Any, Response] =
        if (cfg.http.serveSpa) StaticRoutes.routes(cfg.http.staticDir) else Routes.empty

      // #638: OpenAPI spec auto-generated from the live route table + Swagger UI.
      // Unauthenticated by design (discovery surface); the spec itself advertises
      // which routes require bearer auth. Constructed AFTER the real route chunks
      // so the spec enumerates them all; mounted alongside the SPA fallback so
      // /api/openapi.json + /api/docs resolve before the SPA catch-all.
      val openApiRoutes: Routes[Any, Response] =
        OpenApiRoutes.routes(
          wifihaven.api.BuildInfo.fromEnv.sha,
          systemRoutes,
          statsRoutes,
          routerAndAdminRoutes,
          healthRoutes,
        )

      // #1248: gate all real routes behind readiness (503 until migrations +
      // seeds complete). #1204: wrap health + the gated real routes in the HTTP
      // metrics middleware (templated route labels), then mount the uninstrumented
      // /metrics scrape endpoint alongside.
      // #1570: ErrorBoundary.observe wraps every served route family so any error response (4xx
      // WARN / 5xx ERROR) is logged + metered (api_errors_total{route,status}) in one place —
      // matching the coverage of HttpMetrics.instrument (which already counts all these in
      // http_requests_total). This includes the SPA catch-all (StaticRoutes): an unmatched
      // `/api/*` path 404s there ("no such API route") and a malformed URI 400s — both are real
      // API errors worth surfacing, and the SPA fallback serves index.html (200) for client routes
      // so there is no asset-404 flood. The `route` label is the route's bounded template (the
      // trailing catch-all collapses every path to one series), so no per-path leak.
      //
      // It sits *inside* the readiness gate so the gate's startup 503s don't spam the log. Only
      // /api/health (its own 503-while-starting semantics, polled constantly) and /metrics (the
      // scrape endpoint) are left out — mirroring HttpMetrics' own exclusion of /metrics.
      // #602: LoggingMiddleware.annotate wraps every served route family so every
      // ZIO.log* emitted inside a handler (or anything it calls) inherits the
      // templated `route` and `method` as MDC keys via FiberRef — handlers no
      // longer wrap individual log lines for that context. ErrorBoundary still
      // adds `status` because it sees the response. The middleware sits *inside*
      // HttpMetrics (so the same routes it counts also get logged) and *outside*
      // the readiness gate so even a gated 503 carries its route/method context.
      val assembled = HttpMetrics.instrument(
        healthRoutes ++
          LoggingMiddleware.annotate(
            Readiness.gate(
              ErrorBoundary.observe(
                openApiRoutes ++ systemRoutes ++ statsRoutes ++ routerAndAdminRoutes ++ spaRoutes,
              ),
              ready,
            ),
          ),
      ) ++ metricsRoutes
      // #1849: hand the ws registry back so the run scope can wire it as PolicyService's push sink
      // and fork the reconcile ticker (both need the run scope, which `allRoutes` is not).
      // #1970 (S3): also hand back the SPA registry + change-source bus so the run scope can fork the
      // SpaPush consumer and add the SPA `now`-trigger sink to the (now multi-subscriber) publisher.
      (assembled, wsRegistry, spaWsRegistry, spaEventBus)
    }
}
