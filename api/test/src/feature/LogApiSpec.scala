package familydns.api.feature

import familydns.api.JwtConfig
import familydns.api.auth.*
import familydns.api.db.*
import familydns.api.routes.*
import familydns.shared.*
import familydns.shared.Clock.TestClock
import familydns.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*
import zio.test.Assertion.*

object LogApiSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg   = JwtConfig(secret = "test-secret-at-least-32-chars!!", expiryHours = 1)
  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)
  private def cleanDb  = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
    TestDatabase.cleanAndMigrate.provide(ZLayer.succeed(pg)),
  )

  private def insertLogs(logRepo: QueryLogRepo, logs: List[QueryLogInsert]): Task[Unit] =
    logRepo.insertBatch(logs)

  def spec = suite("Query Log API")(
    test("GET /api/logs returns inserted logs") {
      for {
        _               <- cleanDb
        logRepo         <- ZIO.service[QueryLogRepo]
        auth            <- makeAuth
        token           <- auth.login("admin", "changeme").map(_.token)
        _               <- insertLogs(
          logRepo,
          List(
            QueryLogInsert(
              Some("aa:bb:cc:dd:ee:ff"),
              Some("iPad"),
              None,
              Some("Kids"),
              "youtube.com",
              1,
              blocked = false,
              "allowed",
              Some("home"),
            ),
            QueryLogInsert(
              Some("aa:bb:cc:dd:ee:ff"),
              Some("iPad"),
              None,
              Some("Kids"),
              "pornhub.com",
              1,
              blocked = true,
              "category:adult",
              Some("home"),
            ),
            QueryLogInsert(
              Some("11:22:33:44:55:66"),
              Some("Dad's Phone"),
              None,
              Some("Adults"),
              "facebook.com",
              1,
              blocked = false,
              "allowed",
              Some("home"),
            ),
          ),
        )
        userProfileRepo <- ZIO.service[UserProfileRepo]
        routes = LogRoutes.routes(auth, logRepo, userProfileRepo)
        req    = Request
          .get(URL.decode("/api/logs").toOption.get)
          .addHeader(Header.Authorization.Bearer(token))
        resp <- routes.runZIO(req)
        body <- resp.body.asString
        logs <- ZIO.fromEither(body.fromJson[List[QueryLog]])
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(logs.length == 3)
    },
    test("GET /api/logs?blocked=true filters to blocked only") {
      for {
        _               <- cleanDb
        logRepo         <- ZIO.service[QueryLogRepo]
        auth            <- makeAuth
        token           <- auth.login("admin", "changeme").map(_.token)
        _               <- insertLogs(
          logRepo,
          List(
            QueryLogInsert(
              Some("aa:bb:cc:dd:ee:ff"),
              Some("iPad"),
              None,
              Some("Kids"),
              "youtube.com",
              1,
              blocked = false,
              "allowed",
              Some("home"),
            ),
            QueryLogInsert(
              Some("aa:bb:cc:dd:ee:ff"),
              Some("iPad"),
              None,
              Some("Kids"),
              "badsite.com",
              1,
              blocked = true,
              "category:adult",
              Some("home"),
            ),
          ),
        )
        userProfileRepo <- ZIO.service[UserProfileRepo]
        routes = LogRoutes.routes(auth, logRepo, userProfileRepo)
        req    = Request
          .get(URL.decode("/api/logs?blocked=true").toOption.get)
          .addHeader(Header.Authorization.Bearer(token))
        resp <- routes.runZIO(req)
        body <- resp.body.asString
        logs <- ZIO.fromEither(body.fromJson[List[QueryLog]])
      } yield assertTrue(logs.length == 1) &&
        assertTrue(logs.head.domain == "badsite.com") &&
        assertTrue(logs.head.blocked)
    },
    test("GET /api/stats returns correct counts") {
      for {
        _               <- cleanDb
        logRepo         <- ZIO.service[QueryLogRepo]
        auth            <- makeAuth
        token           <- auth.login("admin", "changeme").map(_.token)
        _               <- insertLogs(
          logRepo,
          List(
            QueryLogInsert(
              Some("aa:bb:cc:dd:ee:ff"),
              Some("iPad"),
              None,
              Some("Kids"),
              "google.com",
              1,
              blocked = false,
              "allowed",
              Some("home"),
            ),
            QueryLogInsert(
              Some("aa:bb:cc:dd:ee:ff"),
              Some("iPad"),
              None,
              Some("Kids"),
              "badsite.com",
              1,
              blocked = true,
              "category:adult",
              Some("home"),
            ),
            QueryLogInsert(
              Some("aa:bb:cc:dd:ee:ff"),
              Some("iPad"),
              None,
              Some("Kids"),
              "badsite.com",
              1,
              blocked = true,
              "category:adult",
              Some("home"),
            ),
          ),
        )
        userProfileRepo <- ZIO.service[UserProfileRepo]
        routes = LogRoutes.routes(auth, logRepo, userProfileRepo)
        req    = Request
          .get(URL.decode("/api/stats").toOption.get)
          .addHeader(Header.Authorization.Bearer(token))
        resp  <- routes.runZIO(req)
        body  <- resp.body.asString
        stats <- ZIO.fromEither(body.fromJson[DashboardStats])
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(stats.totalToday == 3) &&
        assertTrue(stats.blockedToday == 2) &&
        assertTrue(stats.topBlocked.exists(_.domain == "badsite.com")) &&
        assertTrue(stats.topBlocked.find(_.domain == "badsite.com").exists(_.count == 2))
    },
    test("GET /api/logs?mac=... filters to one device") {
      for {
        _               <- cleanDb
        logRepo         <- ZIO.service[QueryLogRepo]
        auth            <- makeAuth
        token           <- auth.login("admin", "changeme").map(_.token)
        _               <- insertLogs(
          logRepo,
          List(
            QueryLogInsert(
              Some("aa:bb:cc:dd:ee:01"),
              Some("iPad"),
              None,
              Some("Kids"),
              "google.com",
              1,
              blocked = false,
              "allowed",
              Some("home"),
            ),
            QueryLogInsert(
              Some("aa:bb:cc:dd:ee:02"),
              Some("Laptop"),
              None,
              Some("Adults"),
              "nytimes.com",
              1,
              blocked = false,
              "allowed",
              Some("home"),
            ),
          ),
        )
        userProfileRepo <- ZIO.service[UserProfileRepo]
        routes = LogRoutes.routes(auth, logRepo, userProfileRepo)
        req    = Request
          .get(
            URL.decode("/api/logs?mac=aa:bb:cc:dd:ee:01").toOption.get,
          )
          .addHeader(Header.Authorization.Bearer(token))
        resp <- routes.runZIO(req)
        body <- resp.body.asString
        logs <- ZIO.fromEither(body.fromJson[List[QueryLog]])
      } yield assertTrue(logs.length == 1) &&
        assertTrue(logs.head.mac.contains("aa:bb:cc:dd:ee:01"))
    },
  ) @@ TestAspect.sequential
}
