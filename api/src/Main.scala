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
      cfg    <- ZIO.service[AppConfig]
      _      <- ZIO.logInfo(s"WifiHaven API starting on ${cfg.http.host}:${cfg.http.port}")
      _      <- ZIO
        .logWarning(
          "WIFIHAVEN_DEBUG=1 set — /api/debug/* endpoints are MOUNTED (loopback only). " +
            "Disable in production.",
        )
        .when(cfg.debugEnabled)
      _      <- Database.runMigrations(cfg.db)
      _      <- ZIO.logInfo("Database migrations complete")
      // #334: ensure household_settings has its single row, defaulting the
      // daily-reset tz to the API server's local zone on first install. No-op
      // on subsequent boots because of ON CONFLICT DO NOTHING.
      hsRepo <- ZIO.service[HouseholdSettingsRepo]
      tz = java.time.ZoneId.systemDefault()
      _              <- hsRepo.ensureDefault(tz)
      _              <- ZIO.logInfo(s"household_settings ensured (install-default tz=${tz.getId})")
      // #768: seed the starter library of app templates. Idempotent — operator
      // host edits on previously-seeded apps are preserved.
      appRepoForSeed <- ZIO.service[AppRepo]
      templates      <- AppTemplates.loadAll()
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
      bundled        <- BundledBlocklists.loadAll()
      _              <- BundledBlocklists.seed(blRepoForSeed, blCacheForSeed, blFetcher, bundled)
      _              <- ZIO.logInfo(s"bundled blocklists seeded (${bundled.size} lists)")
      // #809: scheduled re-aggregation of traffic_reports into the rollup
      // tables. Hourly tick re-rolls the trailing 2h every 5 min; daily tick
      // re-rolls yesterday + today every hour. Both are forkDaemon so they
      // run for the lifetime of the process and never block startup.
      rollupRepo     <- ZIO.service[wifihaven.api.db.RollupRepo]
      clockForJobs   <- ZIO.service[Clock]
      _              <- RollupJobs.hourlyLoop(rollupRepo, clockForJobs).forkDaemon
      _              <- RollupJobs.dailyLoop(rollupRepo, clockForJobs, tz).forkDaemon
      _              <- ZIO.logInfo("rollup fibers forked (hourly + daily)")
      _              <- ZIO
        .logWarning(
          "WIFIHAVEN_SEED_TEST_BLOCKLISTS=1 set — seeding dev test_ads/test_social. " +
            "Disable in production.",
        )
        .when(cfg.seedTestBlocklists)
      _              <- BundledBlocklists
        .seed(blRepoForSeed, blCacheForSeed, blFetcher, BundledBlocklists.devTestBlocklists)
        .when(cfg.seedTestBlocklists)
      // #811: daily retention sweep. Forks a daemon fiber that runs at 03:00 UTC.
      // Multi-instance-safe via Postgres advisory lock — losing instances skip
      // the tick rather than racing on the same DELETE.
      xaForJobs      <- ZIO.service[Transactor[Task]]
      _              <- RetentionSweepJob.start(xaForJobs)
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
      _ <- Server
        .serve(withCors)
        .provide(ZLayer.succeed(serverConfig) >>> Server.live)
    } yield ()).provide(serverEnv)

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
    } yield HealthRoutes.routes(dbHealthCheck) ++
      VersionRoutes.routes(wifihaven.api.BuildInfo.fromEnv) ++
      AuthRoutes.routes(auth, userRepo, upRepo) ++
      ProfileRoutes.routes(auth, profileRepo, schedRepo, tlRepo, upRepo, userRepo) ++
      HouseholdSettingsRoutes.routes(auth, hsRepo) ++
      DeviceRoutes.routes(auth, deviceRepo, upRepo) ++
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
      BlocklistRoutes.routes(auth, blRepo, blCache, blFetcher2, bundledBlocklists) ++
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
      ) ++
      (if (cfg.http.serveSpa) StaticRoutes.routes(cfg.http.staticDir) else Routes.empty)
}
