package wifihaven.api

import doobie.*
import doobie.implicits.*
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.policy.*
import wifihaven.api.routes.*
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
      _      <- hsRepo.ensureDefault(tz)
      _      <- ZIO.logInfo(s"household_settings ensured (install-default tz=${tz.getId})")
      routes <- allRoutes
      withCors = Cors.wrap(routes, cfg.cors)
      _ <- ZIO
        .logInfo(s"CORS enabled for origins: ${cfg.cors.origins.mkString(", ")}")
        .when(cfg.cors.origins.nonEmpty)
      _ <- Server
        .serve(withCors)
        .provide(Server.defaultWithPort(cfg.http.port))
    } yield ()).provide(serverEnv)

  private val serverEnv =
    AppConfig.layer >+>
      ZLayer.fromZIO(ZIO.serviceWith[AppConfig](_.db)) >+>
      ZLayer.fromZIO(ZIO.serviceWith[AppConfig](c => Database.makeTransactor(c.db))) >+>
      Repos.all >+>
      ZLayer.fromZIO(ZIO.serviceWith[AppConfig](_.jwt)) >+>
      Clock.live >+>
      AuthService.layer >+>
      PolicyService.layer

  private def allRoutes =
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
      usageRepo   <- ZIO.service[TimeUsageRepo]
      extRepo     <- ZIO.service[TimeExtensionRepo]
      routerRepo  <- ZIO.service[RouterRepo]
      trafficRepo <- ZIO.service[TrafficReportRepo]
      connRepo    <- ZIO.service[ConnectionEventRepo]
      blockEvRepo <- ZIO.service[BlockEventRepo]
      policy      <- ZIO.service[PolicyService]
      cfg         <- ZIO.service[AppConfig]
      clock       <- ZIO.service[Clock]
      xa          <- ZIO.service[Transactor[Task]]
      routerAuth    = new RouterAuthLive(routerRepo)
      dbHealthCheck = sql"SELECT 1".query[Int].unique.transact(xa).unit
    } yield HealthRoutes.routes(dbHealthCheck) ++
      AuthRoutes.routes(auth, userRepo, upRepo) ++
      ProfileRoutes.routes(auth, profileRepo, schedRepo, tlRepo, stlRepo, upRepo, userRepo) ++
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
        clock,
      ) ++
      LogRoutes.routes(auth, connRepo, upRepo) ++
      SessionRoutes.routes(auth, trafficRepo, deviceRepo, profileRepo, upRepo) ++
      DashboardNowRoutes.routes(
        auth,
        trafficRepo,
        connRepo,
        deviceRepo,
        profileRepo,
        upRepo,
        clock,
      ) ++
      BlocklistRoutes.routes(auth, blRepo) ++
      RouterRoutes.routes(routerRepo, policy, routerAuth, blockEvRepo) ++
      AdminRouterRoutes.routes(auth, routerRepo) ++
      RouterIngestRoutes.routes(
        routerAuth,
        routerRepo,
        trafficRepo,
        usageRepo,
        deviceRepo,
        connRepo,
      ) ++
      DebugRoutes.routes(cfg.debugEnabled, deviceRepo, connRepo, usageRepo, trafficRepo, clock) ++
      (if (cfg.http.serveSpa) StaticRoutes.routes(cfg.http.staticDir) else Routes.empty)
}
