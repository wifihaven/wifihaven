package wifihaven.api

import doobie.*
import doobie.implicits.*
import wifihaven.api.auth.*
import wifihaven.api.cache.TimeStatusCache
import wifihaven.api.db.*
import wifihaven.api.metrics.{DbPoolMetrics, MetricsRuntime}
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
        _      <- Database.runMigrations(cfg.db)
        _      <- ZIO.logInfo("Database migrations complete")
        // #334: ensure household_settings has its single row, defaulting the
        // daily-reset tz to the API server's local zone on first install. No-op
        // on subsequent boots because of ON CONFLICT DO NOTHING.
        hsRepo <- ZIO.service[HouseholdSettingsRepo]
        tz = java.time.ZoneId.systemDefault()
        _ <- hsRepo.ensureDefault(tz)
        _ <- ZIO.logInfo(s"household_settings ensured (install-default tz=${tz.getId})")
        // #768: seed the starter library of app templates. Idempotent — operator
        // host edits on previously-seeded apps are preserved.
        appRepoForSeed <- ZIO.service[AppRepo]
        seedSummary    <- AppTemplates.seed(appRepoForSeed, templates)
        _              <- ZIO.logInfo(
          s"app_templates seeded (${templates.size} templates): " +
            s"created=${seedSummary.created.size} ${seedSummary.created.mkString("[", ",", "]")}, " +
            s"repopulated=${seedSummary.repopulated.size} ${seedSummary.repopulated.mkString("[", ",", "]")}, " +
            s"augmented=${seedSummary.augmented.size} " +
            seedSummary.augmented
              .map(a => s"${a.slug}+[${a.addedHosts.mkString(",")}]")
              .mkString("[", ",", "]") + ", " +
            s"preserved=${seedSummary.preserved.size}",
        )
        // #958: seed the bundled category blocklists. Inline lists pull hosts
        // straight from YAML; remote lists fetch from the declared upstream URL
        // (cached in-memory after first success — see BlocklistCache). REPLACE
        // semantics; remote-fetch failures leave existing DB rows untouched.
        blRepoForSeed  <- ZIO.service[BlocklistRepo]
        blCacheForSeed <- ZIO.service[BlocklistCache]
        blFetcher      <- ZIO.service[BlocklistFetcher]
        _              <- BundledBlocklists.seed(blRepoForSeed, blCacheForSeed, blFetcher, bundled)
        _              <- ZIO.logInfo(s"bundled blocklists seeded (${bundled.size} lists)")
        // #1248: migrations + ensureDefault + seeds are done — flip readiness so
        // /api/health returns 200 and the gated routes start serving real traffic.
        _              <- readyRef.set(true)
        _              <- ZIO.logInfo("readiness flipped → /api/health healthy, API routes ungated")
        // #809: scheduled re-aggregation of traffic_reports into the rollup
        // tables. #1230: cadence matches the tier each table is read at — hourly
        // tick re-rolls the trailing 2h every hour, daily tick re-rolls the
        // trailing 2 days once a day (reads of recent windows hit raw
        // traffic_reports, not these tables). #1247: forkScoped (not forkDaemon)
        // into the run scope so they are interrupted before the Hikari pool
        // closes on shutdown; the fork never blocks startup either way.
        rollupRepo     <- ZIO.service[wifihaven.api.db.RollupRepo]
        clockForJobs   <- ZIO.service[Clock]
        _              <- RollupJobs.hourlyLoop(rollupRepo, appRepoForSeed, clockForJobs).forkScoped
        _ <- RollupJobs.dailyLoop(rollupRepo, appRepoForSeed, clockForJobs, tz).forkScoped
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
        timeRollupRepo  <- ZIO.service[wifihaven.api.db.TimeUsedRollupRepo]
        profileRepoForJ <- ZIO.service[wifihaven.api.db.ProfileRepo]
        deviceRepoForJ  <- ZIO.service[wifihaven.api.db.DeviceRepo]
        stlRepoForJ     <- ZIO.service[wifihaven.api.db.SiteTimeLimitRepo]
        trafficRepoForJ <- ZIO.service[wifihaven.api.db.TrafficReportRepo]
        _               <- TimeUsedRollupJob
          .loop(
            timeRollupRepo,
            rollupRepo,
            profileRepoForJ,
            deviceRepoForJ,
            stlRepoForJ,
            trafficRepoForJ,
            hsRepo,
            clockForJobs,
          )
          .forkScoped
        _               <- ZIO.logInfo("rollup fibers forked (hourly + daily + time_used_daily)")
        // #1243: poll the HikariCP MXBean into the Prometheus pool gauges. forkDaemon so it lives
        // for the process and never blocks startup.
        dbPool          <- ZIO.service[Database.DbPool]
        _               <- DbPoolMetrics.loop(dbPool.dataSource, dbPool.maxSize).forkDaemon
        _               <- ZIO.logInfo("db-pool metrics fiber forked")
        _               <- ZIO
          .logWarning(
            "WIFIHAVEN_SEED_TEST_BLOCKLISTS=1 set — seeding dev test_ads/test_social. " +
              "Disable in production.",
          )
          .when(cfg.seedTestBlocklists)
        _               <- BundledBlocklists
          .seed(blRepoForSeed, blCacheForSeed, blFetcher, BundledBlocklists.devTestBlocklists)
          .when(cfg.seedTestBlocklists)
        // #811: daily retention sweep. Forks a daemon fiber that runs at 03:00 UTC.
        // Multi-instance-safe via Postgres advisory lock — losing instances skip
        // the tick rather than racing on the same DELETE.
        xaForJobs       <- ZIO.service[Transactor[Task]]
        _               <- RetentionSweepJob.start(xaForJobs)
        // #1176/#1179: backfill reason_text on connection_events / block_events rows inserted
        // between V40 and V44 (no reason_text column then). Fork-and-forget so a slow scan on a
        // cold Render PG doesn't gate the healthcheck; subsequent restarts re-run safely until
        // no NULLs remain.
        _               <- ReasonTextBackfill.run(xaForJobs).forkDaemon
        // Keep the process alive on the already-bound server fiber; exits if it dies.
        _               <- serverFiber.join
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
      auth          <- ZIO.service[AuthService]
      userRepo      <- ZIO.service[UserRepo]
      upRepo        <- ZIO.service[UserProfileRepo]
      profileRepo   <- ZIO.service[ProfileRepo]
      schedRepo     <- ZIO.service[ScheduleRepo]
      hsRepo        <- ZIO.service[HouseholdSettingsRepo]
      tlRepo        <- ZIO.service[TimeLimitRepo]
      stlRepo       <- ZIO.service[SiteTimeLimitRepo]
      deviceRepo    <- ZIO.service[DeviceRepo]
      blRepo        <- ZIO.service[BlocklistRepo]
      blCache       <- ZIO.service[BlocklistCache]
      blFetcher2    <- ZIO.service[BlocklistFetcher]
      usageRepo     <- ZIO.service[TimeUsageRepo]
      extRepo       <- ZIO.service[TimeExtensionRepo]
      routerRepo    <- ZIO.service[RouterRepo]
      trafficRepo   <- ZIO.service[TrafficReportRepo]
      rollupRepo2   <- ZIO.service[RollupRepo]
      connRepo      <- ZIO.service[ConnectionEventRepo]
      blockEvRepo   <- ZIO.service[BlockEventRepo]
      alertRepo     <- ZIO.service[AlertRepo]
      appRepo       <- ZIO.service[AppRepo]
      notifier      <- ZIO.service[Notifier]
      policy        <- ZIO.service[PolicyService]
      timeStatus    <- ZIO.service[wifihaven.api.policy.TimeStatusService]
      cfg           <- ZIO.service[AppConfig]
      clock         <- ZIO.service[Clock]
      timeCache     <- ZIO.service[TimeStatusCache]
      xa            <- ZIO.service[Transactor[Task]]
      promPublisher <- ZIO.service[PrometheusPublisher]
      routerAuth    = new RouterAuthLive(routerRepo)
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
      val ungatedRoutes: Routes[Any, Response] =
        HealthRoutes.routes(ready, dbHealthCheck) ++
          MetricsRoutes.routes(cfg.metrics, promPublisher)

      val systemRoutes: Routes[Any, Response] =
        VersionRoutes.routes(wifihaven.api.BuildInfo.fromEnv) ++
          AuthRoutes.routes(auth, userRepo, upRepo) ++
          ProfileRoutes.routes(auth, profileRepo, schedRepo, tlRepo, upRepo, userRepo) ++
          HouseholdSettingsRoutes.routes(auth, hsRepo) ++
          DeviceRoutes.routes(auth, deviceRepo, upRepo)

      val statsRoutes: Routes[Any, Response] =
        TimeRoutes.routes(
          auth,
          deviceRepo,
          tlRepo,
          stlRepo,
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
            clock,
          ) ++
          DashboardNowRoutes.routes(
            auth,
            trafficRepo,
            connRepo,
            deviceRepo,
            profileRepo,
            upRepo,
            clock,
          ) ++
          BlocklistRoutes.routes(auth, blRepo, blCache, blFetcher2, bundledBlocklists)

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
      // seeds complete), then mount the ungated observability routes alongside.
      ungatedRoutes ++
        Readiness.gate(
          systemRoutes ++ statsRoutes ++ routerAndAdminRoutes ++ spaRoutes,
          ready,
        )
    }
}
