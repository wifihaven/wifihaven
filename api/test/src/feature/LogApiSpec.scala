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

import java.time.Instant
import java.util.UUID

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

  // Router name doubles as location until routers.location column lands (#136).
  private def seedRouter(name: String = "home"): ZIO[RouterRepo, Throwable, UUID] =
    ZIO.serviceWithZIO[RouterRepo] { rRepo =>
      for {
        id <- rRepo.create(name, "ENROLL_HASH")
        _  <- rRepo.completeEnrollment(id, "TOKEN_HASH")
      } yield id
    }

  private def getJson(routes: Routes[Any, Response], path: String, token: String) =
    routes.runZIO(
      Request
        .get(URL.decode(path).toOption.get)
        .addHeader(Header.Authorization.Bearer(token)),
    )

  private def recentTs = Instant.now().minusSeconds(300)

  def spec = suite("Log API (connection_events)")(
    test("GET /api/logs returns events mapped to QueryLog shape with router name as location") {
      for {
        _        <- cleanDb
        routerId <- seedRouter("home")
        connRepo <- ZIO.service[ConnectionEventRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token)
        _        <- connRepo.insertBatch(
          List(
            ConnectionEventInsert(
              routerId,
              Some("aa:bb:cc:dd:ee:ff"),
              "youtube.com",
              None,
              true,
              "allowed",
              recentTs,
            ),
            ConnectionEventInsert(
              routerId,
              Some("aa:bb:cc:dd:ee:ff"),
              "pornhub.com",
              None,
              false,
              "category:adult",
              recentTs,
            ),
            ConnectionEventInsert(
              routerId,
              Some("11:22:33:44:55:66"),
              "facebook.com",
              None,
              true,
              "allowed",
              recentTs,
            ),
          ),
        )
        routes = LogRoutes.routes(auth, connRepo, upRepo)
        resp <- getJson(routes, "/api/logs", token)
        body <- resp.body.asString
        logs <- ZIO.fromEither(body.fromJson[List[QueryLog]])
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(logs.length == 3) &&
        assertTrue(logs.exists(_.domain == "youtube.com")) &&
        assertTrue(logs.exists(l => l.domain == "pornhub.com" && l.blocked)) &&
        assertTrue(logs.exists(l => l.domain == "youtube.com" && !l.blocked)) &&
        assertTrue(logs.forall(_.location.contains("home"))) &&
        assertTrue(logs.exists(l => l.domain == "pornhub.com" && l.`type` == "dns_block")) &&
        assertTrue(logs.exists(l => l.domain == "youtube.com" && l.`type` == "dns_allow"))
    },
    test("GET /api/logs?blocked=true returns only blocked (allowed=false) events") {
      for {
        _        <- cleanDb
        routerId <- seedRouter()
        connRepo <- ZIO.service[ConnectionEventRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token)
        _        <- connRepo.insertBatch(
          List(
            ConnectionEventInsert(
              routerId,
              Some("aa:bb:cc:dd:ee:ff"),
              "ok.com",
              None,
              true,
              "allowed",
              recentTs,
            ),
            ConnectionEventInsert(
              routerId,
              Some("aa:bb:cc:dd:ee:ff"),
              "blocked.com",
              None,
              false,
              "category:adult",
              recentTs,
            ),
          ),
        )
        routes = LogRoutes.routes(auth, connRepo, upRepo)
        resp <- getJson(routes, "/api/logs?blocked=true", token)
        body <- resp.body.asString
        logs <- ZIO.fromEither(body.fromJson[List[QueryLog]])
      } yield assertTrue(logs.length == 1) &&
        assertTrue(logs.head.domain == "blocked.com") &&
        assertTrue(logs.head.blocked)
    },
    test("GET /api/logs?location= filters by router name") {
      for {
        _          <- cleanDb
        homeId     <- seedRouter("home")
        vacationId <- ZIO.serviceWithZIO[RouterRepo] { rRepo =>
          rRepo.create("vacation", "ENROLL_HASH_2").flatMap { id =>
            rRepo.completeEnrollment(id, "TOKEN_HASH_2").as(id)
          }
        }
        connRepo   <- ZIO.service[ConnectionEventRepo]
        upRepo     <- ZIO.service[UserProfileRepo]
        auth       <- makeAuth
        token      <- auth.login("admin", "changeme").map(_.token)
        _          <- connRepo.insertBatch(
          List(
            ConnectionEventInsert(homeId, None, "home-site.com", None, true, "allowed", recentTs),
            ConnectionEventInsert(
              vacationId,
              None,
              "vacation-site.com",
              None,
              true,
              "allowed",
              recentTs,
            ),
          ),
        )
        routes = LogRoutes.routes(auth, connRepo, upRepo)
        resp <- getJson(routes, "/api/logs?location=home", token)
        body <- resp.body.asString
        logs <- ZIO.fromEither(body.fromJson[List[QueryLog]])
      } yield assertTrue(logs.length == 1) &&
        assertTrue(logs.head.domain == "home-site.com")
    },
    test("GET /api/logs?mac=... filters to one device") {
      for {
        _        <- cleanDb
        routerId <- seedRouter()
        connRepo <- ZIO.service[ConnectionEventRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token)
        _        <- connRepo.insertBatch(
          List(
            ConnectionEventInsert(
              routerId,
              Some("aa:bb:cc:dd:ee:01"),
              "site1.com",
              None,
              true,
              "allowed",
              recentTs,
            ),
            ConnectionEventInsert(
              routerId,
              Some("aa:bb:cc:dd:ee:02"),
              "site2.com",
              None,
              true,
              "allowed",
              recentTs,
            ),
          ),
        )
        routes = LogRoutes.routes(auth, connRepo, upRepo)
        resp <- getJson(routes, "/api/logs?mac=aa:bb:cc:dd:ee:01", token)
        body <- resp.body.asString
        logs <- ZIO.fromEither(body.fromJson[List[QueryLog]])
      } yield assertTrue(logs.length == 1) &&
        assertTrue(logs.head.mac.contains("aa:bb:cc:dd:ee:01"))
    },
    test("GET /api/stats returns correct total and blocked counts") {
      for {
        _        <- cleanDb
        routerId <- seedRouter()
        connRepo <- ZIO.service[ConnectionEventRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token)
        _        <- connRepo.insertBatch(
          List(
            ConnectionEventInsert(
              routerId,
              Some("aa:bb:cc:00:00:01"),
              "google.com",
              None,
              true,
              "allowed",
              recentTs,
            ),
            ConnectionEventInsert(
              routerId,
              Some("aa:bb:cc:00:00:01"),
              "badsite.com",
              None,
              false,
              "category:adult",
              recentTs,
            ),
            ConnectionEventInsert(
              routerId,
              Some("aa:bb:cc:00:00:01"),
              "badsite.com",
              None,
              false,
              "category:adult",
              recentTs,
            ),
          ),
        )
        routes = LogRoutes.routes(auth, connRepo, upRepo)
        resp  <- getJson(routes, "/api/stats", token)
        body  <- resp.body.asString
        stats <- ZIO.fromEither(body.fromJson[DashboardStats])
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(stats.totalToday == 3) &&
        assertTrue(stats.blockedToday == 2) &&
        assertTrue(stats.topBlocked.exists(_.domain == "badsite.com")) &&
        assertTrue(stats.topBlocked.find(_.domain == "badsite.com").exists(_.count == 2))
    },
    test("GET /api/stats topBlocked is sorted by frequency descending") {
      for {
        _        <- cleanDb
        routerId <- seedRouter()
        connRepo <- ZIO.service[ConnectionEventRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token)
        _        <- connRepo.insertBatch(
          List(
            ConnectionEventInsert(routerId, None, "rare.com", None, false, "blocked", recentTs),
            ConnectionEventInsert(routerId, None, "frequent.com", None, false, "blocked", recentTs),
            ConnectionEventInsert(routerId, None, "frequent.com", None, false, "blocked", recentTs),
            ConnectionEventInsert(routerId, None, "frequent.com", None, false, "blocked", recentTs),
          ),
        )
        routes = LogRoutes.routes(auth, connRepo, upRepo)
        resp  <- getJson(routes, "/api/stats", token)
        body  <- resp.body.asString
        stats <- ZIO.fromEither(body.fromJson[DashboardStats])
      } yield assertTrue(stats.topBlocked.nonEmpty) &&
        assertTrue(stats.topBlocked.head.domain == "frequent.com") &&
        assertTrue(stats.topBlocked.head.count == 3) &&
        assertTrue(stats.topBlocked.exists(d => d.domain == "rare.com" && d.count == 1))
    },
    test("GET /api/logs pagination respects limit and offset") {
      for {
        _        <- cleanDb
        routerId <- seedRouter()
        connRepo <- ZIO.service[ConnectionEventRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token)
        _        <- connRepo.insertBatch(
          (1 to 5).toList.map(i =>
            ConnectionEventInsert(
              routerId,
              None,
              s"site$i.com",
              None,
              true,
              "allowed",
              Instant.now().minusSeconds(i.toLong * 10),
            ),
          ),
        )
        routes = LogRoutes.routes(auth, connRepo, upRepo)
        page1 <- getJson(routes, "/api/logs?limit=2&offset=0", token)
          .flatMap(_.body.asString)
          .flatMap(b => ZIO.fromEither(b.fromJson[List[QueryLog]]))
        page2 <- getJson(routes, "/api/logs?limit=2&offset=2", token)
          .flatMap(_.body.asString)
          .flatMap(b => ZIO.fromEither(b.fromJson[List[QueryLog]]))
        page3 <- getJson(routes, "/api/logs?limit=2&offset=4", token)
          .flatMap(_.body.asString)
          .flatMap(b => ZIO.fromEither(b.fromJson[List[QueryLog]]))
      } yield assertTrue(page1.length == 2) &&
        assertTrue(page2.length == 2) &&
        assertTrue(page3.length == 1) &&
        assertTrue(page1.map(_.domain) != page2.map(_.domain))
    },
  ) @@ TestAspect.sequential
}
