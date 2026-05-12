package familydns.api

import doobie.*
import doobie.implicits.*
import familydns.api.auth.*
import familydns.api.db.*
import familydns.api.policy.*
import familydns.api.routes.*
import familydns.shared.Clock
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
      _      <- ZIO.logInfo(s"FamilyDNS API starting on ${cfg.http.host}:${cfg.http.port}")
      _      <- ZIO
        .logWarning(
          "FAMILYDNS_DEBUG=1 set — /api/debug/* endpoints are MOUNTED (loopback only). " +
            "Disable in production.",
        )
        .when(cfg.debugEnabled)
      _      <- Database.runMigrations(cfg.db)
      _      <- ZIO.logInfo("Database migrations complete")
      routes <- allRoutes
      _      <- Server
        .serve(routes)
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
      DeviceRoutes.routes(auth, deviceRepo, upRepo) ++
      TimeRoutes.routes(
        auth,
        deviceRepo,
        tlRepo,
        stlRepo,
        usageRepo,
        extRepo,
        profileRepo,
        upRepo,
        clock,
      ) ++
      LogRoutes.routes(auth, connRepo, upRepo) ++
      SessionRoutes.routes(auth, trafficRepo, deviceRepo, profileRepo, upRepo) ++
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
      DebugRoutes.routes(cfg.debugEnabled, deviceRepo, connRepo, usageRepo, clock) ++
      StaticRoutes.routes(cfg.http.staticDir)
}
