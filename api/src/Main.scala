package wifihaven.api

import doobie.*
import doobie.implicits.*
import wifihaven.api.auth.*
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
import wifihaven.api.policy.*
import wifihaven.api.routes.*
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
        routes <- allRoutes(templatesById, bundledById, readyRef.get)
        withCors = Cors.wrap(routes, cfg.cors)
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
        _      <- ZIO.logInfo("HTTP port bound; running DB-heavy startup behind readiness gate")
        // Service handles are pure layer reads (no DB I/O); resolve them up front
        // so the retried DB-init block below is purely the idempotent DB work.
        hsRepo <- ZIO.service[HouseholdSettingsRepo]
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
        _            <- Database.withStartupRetry(cfg.db.resilience, "startup DB init") {
          for {
            _ <- Database.runMigrations(cfg.db)
            _ <- ZIO.logInfo("Database migrations complete")
            // #334: ensure household_settings has its single row, defaulting the
            // daily-reset tz to the API server's local zone on first install.
            _ <- hsRepo.ensureDefault(tz)
            _ <- ZIO.logInfo(s"household_settings ensured (install-default tz=${tz.getId})")
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
        _            <- readyRef.set(true)
        _            <- ZIO.logInfo("readiness flipped → /api/health healthy, API routes ungated")
        // #809: scheduled re-aggregation of traffic_reports into the rollup
        // tables. #1230: cadence matches the tier each table is read at — hourly
        // tick re-rolls the trailing 2h every hour, daily tick re-rolls the
        // trailing 2 days once a day (reads of recent windows hit raw
        // traffic_reports, not these tables). #1247: forkScoped (not forkDaemon)
        // into the run scope so they are interrupted before the Hikari pool
        // closes on shutdown; the fork never blocks startup either way.
        rollupRepo   <- ZIO.service[wifihaven.api.db.RollupRepo]
        clockForJobs <- ZIO.service[Clock]
        _            <- RollupJobs.hourlyLoop(rollupRepo, appRepoForSeed, clockForJobs).forkScoped
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
          )
          .forkScoped
        _                 <- ZIO.logInfo(
          "rollup fibers forked (hourly + daily + time_used_daily + conn_events_hourly + conn_events_daily)",
        )
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
        _                 <- RetentionSweepJob.start(xaForJobs)
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
      Notifier.live >+>
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
      upRepo         <- ZIO.service[UserProfileRepo]
      profileRepo    <- ZIO.service[ProfileRepo]
      namedSchedRepo <- ZIO.service[NamedScheduleRepo]
      hsRepo         <- ZIO.service[HouseholdSettingsRepo]
      globalRepo     <- ZIO.service[GlobalPolicyRepo]
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
      notifier       <- ZIO.service[Notifier]
      policy         <- ZIO.service[PolicyService]
      timeStatus     <- ZIO.service[wifihaven.api.policy.TimeStatusService]
      cfg            <- ZIO.service[AppConfig]
      clock          <- ZIO.service[Clock]
      timeCache      <- ZIO.service[TimeStatusCache]
      xa             <- ZIO.service[Transactor[Task]]
      promPublisher  <- ZIO.service[PrometheusPublisher]
      routerAuth = new RouterAuthLive(routerRepo)
      routerMetrics <- RouterMetricsService.make
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
          AuthRoutes.routes(auth, userRepo, upRepo) ++
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
          ) ++
          ScheduleRoutes.routes(auth, namedSchedRepo) ++
          HouseholdSettingsRoutes.routes(auth, hsRepo) ++
          GlobalPolicyRoutes.routes(auth, globalRepo, userRepo) ++
          DeviceRoutes.routes(auth, deviceRepo, upRepo)

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
          AdminRouterRoutes.routes(auth, routerRepo) ++
          RollupAdminRoutes.routes(auth, rollupRepo2) ++
          RouterIngestRoutes.routes(
            routerAuth,
            routerRepo,
            trafficRepo,
            usageRepo,
            deviceRepo,
            connRepo,
            alertRepo,
            hsRepo,
          ) ++
          RouterMetricsRoutes.routes(routerAuth, routerMetrics) ++
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
          ) ++
          AppRoutes.routes(auth, appRepo, profileRepo, upRepo, templates) ++
          AdminDebugRoutes.routes(auth, policy) ++
          DebugRoutes.routes(
            cfg.debugEnabled,
            deviceRepo,
            connRepo,
            usageRepo,
            trafficRepo,
            clock,
            timeCache,
          )

      val spaRoutes: Routes[Any, Response] =
        if (cfg.http.serveSpa) StaticRoutes.routes(cfg.http.staticDir) else Routes.empty

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
      HttpMetrics.instrument(
        healthRoutes ++
          Readiness.gate(
            ErrorBoundary.observe(
              systemRoutes ++ statsRoutes ++ routerAndAdminRoutes ++ spaRoutes,
            ),
            ready,
          ),
      ) ++ metricsRoutes
    }
}
