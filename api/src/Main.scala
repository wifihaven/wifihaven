package wifihaven.api

import doobie.*
import doobie.implicits.*
import wifihaven.api.auth.*
import wifihaven.api.cache.TimeStatusCache
import wifihaven.api.db.*
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

object Main extends ZIOAppDefault {

  override val bootstrap =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  def run =
    (for {
      cfg       <- ZIO.service[AppConfig]
      _         <- ZIO.logInfo(s"WifiHaven API starting on ${cfg.http.host}:${cfg.http.port}")
      _         <- ZIO
        .logWarning(
          "WIFIHAVEN_DEBUG=1 set — /api/debug/* endpoints are MOUNTED (loopback only). " +
            "Disable in production.",
        )
        .when(cfg.debugEnabled)
      // YAML loads are file-system only (no DB) and feed route construction, so they
      // run on the port-bind critical path. The DB-touching warm-up below forks.
      templates <- AppTemplates.loadAll()
      bundled   <- BundledBlocklists.loadAll()
      templatesById = templates.map(t => t.slug -> t).toMap
      bundledById   = bundled.map(b => b.id -> b).toMap
      routes <- allRoutes(templatesById, bundledById)
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
      tz           = java.time.ZoneId.systemDefault()
      warmup       = warmupChain(cfg, tz, templates, bundled)
      serve        = Server
        .serve(withCors)
        .provide(ZLayer.succeed(serverConfig) >>> Server.live)
      // #1197: bind :8080 immediately and run all DB warm-up in a forked daemon.
      // Previously the entire warm-up chain (Flyway + seeds + remote blocklist
      // fetches) ran on the critical path, so a cold Render Postgres at deploy
      // time exceeded the 15-min port-scan window and the deploy failed.
      _ <- Bootstrap.boot(warmup, serve)
    } yield ()).provide(serverEnv)

  /**
   * The full DB warm-up chain. Runs in a forked daemon (see Bootstrap.boot). Order matters:
   * migrations must precede any seed; rollup/retention jobs are themselves forkDaemon and never
   * block the warm-up either.
   *
   * If any step fails, Bootstrap logs the cause and lets the server keep running. Subsequent
   * restarts retry. Operator alerting on errored deploys catches genuine breakage.
   */
  private def warmupChain(
      cfg: AppConfig,
      tz: java.time.ZoneId,
      templates: List[AppTemplate],
      bundled: List[BundledBlocklist],
  ): ZIO[
    HouseholdSettingsRepo & AppRepo & BlocklistRepo & BlocklistCache & BlocklistFetcher &
      wifihaven.api.db.RollupRepo & Clock & wifihaven.api.db.TimeUsedRollupRepo &
      wifihaven.api.db.ProfileRepo & wifihaven.api.db.DeviceRepo &
      wifihaven.api.db.SiteTimeLimitRepo & wifihaven.api.db.TrafficReportRepo & Transactor[Task],
    Throwable,
    Unit,
  ] =
    for {
      _               <- Database.runMigrations(cfg.db)
      _               <- ZIO.logInfo("Database migrations complete")
      // #334: ensure household_settings has its single row, defaulting the
      // daily-reset tz to the API server's local zone on first install. No-op
      // on subsequent boots because of ON CONFLICT DO NOTHING.
      hsRepo          <- ZIO.service[HouseholdSettingsRepo]
      _               <- hsRepo.ensureDefault(tz)
      _               <- ZIO.logInfo(s"household_settings ensured (install-default tz=${tz.getId})")
      // #768: seed the starter library of app templates. Idempotent — operator
      // host edits on previously-seeded apps are preserved.
      appRepoForSeed  <- ZIO.service[AppRepo]
      seedSummary     <- AppTemplates.seed(appRepoForSeed, templates)
      _               <- ZIO.logInfo(
        s"app_templates seeded (${templates.size} templates): " +
          s"created=${seedSummary.created.size} ${seedSummary.created.mkString("[", ",", "]")}, " +
          s"repopulated=${seedSummary.repopulated.size} ${seedSummary.repopulated.mkString("[", ",", "]")}, " +
          s"augmented=${seedSummary.augmented.size} " +
          seedSummary.augmented
            .map(a => s"${a.slug}+[${a.addedHosts.mkString(",")}]")
            .mkString("[", ",", "]") + ", " +
          s"preserved=${seedSummary.preserved.size}",
      )
      // #958: seed the bundled category blocklists.
      blRepoForSeed   <- ZIO.service[BlocklistRepo]
      blCacheForSeed  <- ZIO.service[BlocklistCache]
      blFetcher       <- ZIO.service[BlocklistFetcher]
      _               <- BundledBlocklists.seed(blRepoForSeed, blCacheForSeed, blFetcher, bundled)
      _               <- ZIO.logInfo(s"bundled blocklists seeded (${bundled.size} lists)")
      // #809: rollup fibers — fork inside the warm-up daemon; they live for the
      // lifetime of the process.
      rollupRepo      <- ZIO.service[wifihaven.api.db.RollupRepo]
      clockForJobs    <- ZIO.service[Clock]
      _               <- RollupJobs.hourlyLoop(rollupRepo, clockForJobs).forkDaemon
      _               <- RollupJobs.dailyLoop(rollupRepo, clockForJobs, tz).forkDaemon
      // #1160: per-(profile, today) used_seconds cache.
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
        .forkDaemon
      _               <- ZIO.logInfo("rollup fibers forked (hourly + daily + time_used_daily)")
      _               <- ZIO
        .logWarning(
          "WIFIHAVEN_SEED_TEST_BLOCKLISTS=1 set — seeding dev test_ads/test_social. " +
            "Disable in production.",
        )
        .when(cfg.seedTestBlocklists)
      _               <- BundledBlocklists
        .seed(blRepoForSeed, blCacheForSeed, blFetcher, BundledBlocklists.devTestBlocklists)
        .when(cfg.seedTestBlocklists)
      // #811: daily retention sweep.
      xaForJobs       <- ZIO.service[Transactor[Task]]
      _               <- RetentionSweepJob.start(xaForJobs)
      // #1176/#1179: backfill reason_text on rows inserted between V40 and V44.
      _               <- ReasonTextBackfill.run(xaForJobs).forkDaemon
    } yield ()

  private val serverEnv =
    AppConfig.layer >+>
      ZLayer.fromZIO(ZIO.serviceWith[AppConfig](_.db)) >+>
      ZLayer.fromZIO(ZIO.serviceWith[AppConfig](c => Database.makeTransactor(c.db))) >+>
      Repos.all >+>
      ZLayer.fromZIO(ZIO.serviceWith[AppConfig](_.jwt)) >+>
      Clock.live >+>
      AuthService.layer >+>
      TimeStatusService.layer >+>
      PolicyService.layer >+>
      TimeStatusCache.live() >+>
      BlocklistCache.live >+>
      BlocklistFetcher.live >+>
      Notifier.live

  private def allRoutes(
      templates: Map[wifihaven.shared.types.AppTemplateId, AppTemplate],
      bundledBlocklists: Map[wifihaven.shared.types.BlocklistId, BundledBlocklist],
  ) =
    for {
      auth        <- ZIO.service[AuthService]
      userRepo    <- ZIO.service[UserRepo]
      upRepo      <- ZIO.service[UserProfileRepo]
      profileRepo <- ZIO.service[ProfileRepo]
      schedRepo   <- ZIO.service[ScheduleRepo]
      hsRepo      <- ZIO.service[HouseholdSettingsRepo]
      tlRepo      <- ZIO.service[TimeLimitRepo]
      stlRepo     <- ZIO.service[SiteTimeLimitRepo]
      deviceRepo  <- ZIO.service[DeviceRepo]
      blRepo      <- ZIO.service[BlocklistRepo]
      blCache     <- ZIO.service[BlocklistCache]
      blFetcher2  <- ZIO.service[BlocklistFetcher]
      usageRepo   <- ZIO.service[TimeUsageRepo]
      extRepo     <- ZIO.service[TimeExtensionRepo]
      routerRepo  <- ZIO.service[RouterRepo]
      trafficRepo <- ZIO.service[TrafficReportRepo]
      rollupRepo2 <- ZIO.service[RollupRepo]
      connRepo    <- ZIO.service[ConnectionEventRepo]
      blockEvRepo <- ZIO.service[BlockEventRepo]
      alertRepo   <- ZIO.service[AlertRepo]
      appRepo     <- ZIO.service[AppRepo]
      notifier    <- ZIO.service[Notifier]
      policy      <- ZIO.service[PolicyService]
      timeStatus  <- ZIO.service[wifihaven.api.policy.TimeStatusService]
      cfg         <- ZIO.service[AppConfig]
      clock       <- ZIO.service[Clock]
      timeCache   <- ZIO.service[TimeStatusCache]
      xa          <- ZIO.service[Transactor[Task]]
      routerAuth    = new RouterAuthLive(routerRepo)
      dbHealthCheck = sql"SELECT 1".query[Int].unique.transact(xa).unit
    } yield {
      // #1177: split the route composition into typed chunks. A flat `++` chain across
      // 18+ Routes was hitting Scala 3's type-inference recursion limit on CI (Linux
      // JDK 21 default stack), failing with "Recursion limit exceeded" at the first
      // `++` even though local macOS builds happened to fit. Explicit `Routes[Any, Response]`
      // ascriptions on the chunks ground the inference per chunk so the final fold is
      // a short, well-typed concatenation.
      val systemRoutes: Routes[Any, Response] =
        HealthRoutes.routes(dbHealthCheck) ++
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

      systemRoutes ++ statsRoutes ++ routerAndAdminRoutes ++ spaRoutes
    }
}
