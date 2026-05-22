package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant

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
  private def seedRouter(name: String = "home"): ZIO[RouterRepo, Throwable, RouterId] =
    ZIO.serviceWithZIO[RouterRepo] { rRepo =>
      for {
        id <- rRepo.create(name, Sha256Hex.unsafe("e" * 64))
        _  <- rRepo.completeEnrollment(id, Sha256Hex.unsafe("f" * 64))
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
        token    <- auth.login("admin", "changeme").map(_.token.value)
        _        <- connRepo.insertBatch(
          List(
            ConnectionEventInsert(
              routerId,
              Some(MacAddress.unsafe("aa:bb:cc:dd:ee:ff")),
              HostId.Fqdn(Hostname.unsafe("youtube.com")),
              None,
              true,
              "allowed",
              recentTs,
            ),
            ConnectionEventInsert(
              routerId,
              Some(MacAddress.unsafe("aa:bb:cc:dd:ee:ff")),
              HostId.Fqdn(Hostname.unsafe("pornhub.com")),
              None,
              false,
              "category:adult",
              recentTs,
            ),
            ConnectionEventInsert(
              routerId,
              Some(MacAddress.unsafe("11:22:33:44:55:66")),
              HostId.Fqdn(Hostname.unsafe("facebook.com")),
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
        assertTrue(logs.exists(_.host.value == "youtube.com")) &&
        assertTrue(logs.exists(l => l.host.value == "pornhub.com" && l.blocked)) &&
        assertTrue(logs.exists(l => l.host.value == "youtube.com" && !l.blocked)) &&
        assertTrue(logs.forall(_.location.contains("home")))
    },
    test("GET /api/logs?blocked=true returns only blocked (allowed=false) events") {
      for {
        _        <- cleanDb
        routerId <- seedRouter()
        connRepo <- ZIO.service[ConnectionEventRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token.value)
        _        <- connRepo.insertBatch(
          List(
            ConnectionEventInsert(
              routerId,
              Some(MacAddress.unsafe("aa:bb:cc:dd:ee:ff")),
              HostId.Fqdn(Hostname.unsafe("ok.com")),
              None,
              true,
              "allowed",
              recentTs,
            ),
            ConnectionEventInsert(
              routerId,
              Some(MacAddress.unsafe("aa:bb:cc:dd:ee:ff")),
              HostId.Fqdn(Hostname.unsafe("blocked.com")),
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
        assertTrue(logs.head.host.value == "blocked.com") &&
        assertTrue(logs.head.blocked)
    },
    test("GET /api/logs?location= filters by router name") {
      for {
        _          <- cleanDb
        homeId     <- seedRouter("home")
        vacationId <- ZIO.serviceWithZIO[RouterRepo] { rRepo =>
          rRepo.create("vacation", Sha256Hex.unsafe("g" * 64)).flatMap { id =>
            rRepo.completeEnrollment(id, Sha256Hex.unsafe("h" * 64)).as(id)
          }
        }
        connRepo   <- ZIO.service[ConnectionEventRepo]
        upRepo     <- ZIO.service[UserProfileRepo]
        auth       <- makeAuth
        token      <- auth.login("admin", "changeme").map(_.token.value)
        _          <- connRepo.insertBatch(
          List(
            ConnectionEventInsert(
              homeId,
              None,
              HostId.Fqdn(Hostname.unsafe("home-site.com")),
              None,
              true,
              "allowed",
              recentTs,
            ),
            ConnectionEventInsert(
              vacationId,
              None,
              HostId.Fqdn(Hostname.unsafe("vacation-site.com")),
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
        assertTrue(logs.head.host.value == "home-site.com")
    },
    test("GET /api/logs?mac=... filters to one device") {
      for {
        _        <- cleanDb
        routerId <- seedRouter()
        connRepo <- ZIO.service[ConnectionEventRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token.value)
        _        <- connRepo.insertBatch(
          List(
            ConnectionEventInsert(
              routerId,
              Some(MacAddress.unsafe("aa:bb:cc:dd:ee:01")),
              HostId.Fqdn(Hostname.unsafe("site1.com")),
              None,
              true,
              "allowed",
              recentTs,
            ),
            ConnectionEventInsert(
              routerId,
              Some(MacAddress.unsafe("aa:bb:cc:dd:ee:02")),
              HostId.Fqdn(Hostname.unsafe("site2.com")),
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
        assertTrue(logs.head.mac.contains(MacAddress.unsafe("aa:bb:cc:dd:ee:01")))
    },
    test("GET /api/stats returns correct total and blocked counts") {
      for {
        _        <- cleanDb
        routerId <- seedRouter()
        connRepo <- ZIO.service[ConnectionEventRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token.value)
        _        <- connRepo.insertBatch(
          List(
            ConnectionEventInsert(
              routerId,
              Some(MacAddress.unsafe("aa:bb:cc:00:00:01")),
              HostId.Fqdn(Hostname.unsafe("google.com")),
              None,
              true,
              "allowed",
              recentTs,
            ),
            ConnectionEventInsert(
              routerId,
              Some(MacAddress.unsafe("aa:bb:cc:00:00:01")),
              HostId.Fqdn(Hostname.unsafe("badsite.com")),
              None,
              false,
              "category:adult",
              recentTs,
            ),
            ConnectionEventInsert(
              routerId,
              Some(MacAddress.unsafe("aa:bb:cc:00:00:01")),
              HostId.Fqdn(Hostname.unsafe("badsite.com")),
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
        assertTrue(stats.topBlocked.exists(_.host.value == "badsite.com")) &&
        assertTrue(stats.topBlocked.find(_.host.value == "badsite.com").exists(_.count == 2))
    },
    test("GET /api/stats topBlocked is sorted by frequency descending") {
      for {
        _        <- cleanDb
        routerId <- seedRouter()
        connRepo <- ZIO.service[ConnectionEventRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token.value)
        _        <- connRepo.insertBatch(
          List(
            ConnectionEventInsert(
              routerId,
              None,
              HostId.Fqdn(Hostname.unsafe("rare.com")),
              None,
              false,
              "blocked",
              recentTs,
            ),
            ConnectionEventInsert(
              routerId,
              None,
              HostId.Fqdn(Hostname.unsafe("frequent.com")),
              None,
              false,
              "blocked",
              recentTs,
            ),
            ConnectionEventInsert(
              routerId,
              None,
              HostId.Fqdn(Hostname.unsafe("frequent.com")),
              None,
              false,
              "blocked",
              recentTs,
            ),
            ConnectionEventInsert(
              routerId,
              None,
              HostId.Fqdn(Hostname.unsafe("frequent.com")),
              None,
              false,
              "blocked",
              recentTs,
            ),
          ),
        )
        routes = LogRoutes.routes(auth, connRepo, upRepo)
        resp  <- getJson(routes, "/api/stats", token)
        body  <- resp.body.asString
        stats <- ZIO.fromEither(body.fromJson[DashboardStats])
      } yield assertTrue(stats.topBlocked.nonEmpty) &&
        assertTrue(stats.topBlocked.head.host.value == "frequent.com") &&
        assertTrue(stats.topBlocked.head.count == 3) &&
        assertTrue(stats.topBlocked.exists(d => d.host.value == "rare.com" && d.count == 1))
    },
    test("GET /api/logs pagination respects limit and offset") {
      for {
        _        <- cleanDb
        routerId <- seedRouter()
        connRepo <- ZIO.service[ConnectionEventRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token.value)
        _        <- connRepo.insertBatch(
          (1 to 5).toList.map(i =>
            ConnectionEventInsert(
              routerId,
              None,
              HostId.Fqdn(Hostname.unsafe(s"site$i.com")),
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
        assertTrue(page1.map(_.host.value) != page2.map(_.host.value))
    },
    test("GET /api/logs?deviceId= filters to events whose mac belongs to that device (#342)") {
      for {
        _           <- cleanDb
        routerId    <- seedRouter()
        profileRepo <- ZIO.service[ProfileRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        connRepo    <- ZIO.service[ConnectionEventRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        auth        <- makeAuth
        token       <- auth.login("admin", "changeme").map(_.token.value)
        pid         <- profileRepo.create("Kids", List.empty)
        ipadId      <- deviceRepo.upsert(
          MacAddress.unsafe("aa:bb:cc:dd:ee:01"),
          "Kid's iPad",
          Some(pid),
          "10.0.0.1",
        )
        _           <- deviceRepo.upsert(
          MacAddress.unsafe("aa:bb:cc:dd:ee:02"),
          "Phone",
          Some(pid),
          "10.0.0.2",
        )
        _           <- connRepo.insertBatch(
          List(
            ConnectionEventInsert(
              routerId,
              Some(MacAddress.unsafe("aa:bb:cc:dd:ee:01")),
              HostId.Fqdn(Hostname.unsafe("ipad-site.com")),
              None,
              true,
              "allowed",
              recentTs,
            ),
            ConnectionEventInsert(
              routerId,
              Some(MacAddress.unsafe("aa:bb:cc:dd:ee:02")),
              HostId.Fqdn(Hostname.unsafe("phone-site.com")),
              None,
              true,
              "allowed",
              recentTs,
            ),
          ),
        )
        routes = LogRoutes.routes(auth, connRepo, upRepo)
        resp <- getJson(routes, s"/api/logs?deviceId=${ipadId.value}", token)
        body <- resp.body.asString
        logs <- ZIO.fromEither(body.fromJson[List[QueryLog]])
      } yield assertTrue(logs.length == 1) &&
        assertTrue(logs.head.host.value == "ipad-site.com")
    },
    test("GET /api/logs?profileId= filters to events whose device belongs to that profile (#342)") {
      for {
        _           <- cleanDb
        routerId    <- seedRouter()
        profileRepo <- ZIO.service[ProfileRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        connRepo    <- ZIO.service[ConnectionEventRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        auth        <- makeAuth
        token       <- auth.login("admin", "changeme").map(_.token.value)
        kidsPid     <- profileRepo.create("Kids", List.empty)
        adultsPid   <- profileRepo.create("Adults", List.empty)
        _           <- deviceRepo.upsert(
          MacAddress.unsafe("aa:bb:cc:dd:ee:01"),
          "Kid's iPad",
          Some(kidsPid),
          "10.0.0.1",
        )
        _           <- deviceRepo.upsert(
          MacAddress.unsafe("aa:bb:cc:dd:ee:02"),
          "Adult Phone",
          Some(adultsPid),
          "10.0.0.2",
        )
        _           <- connRepo.insertBatch(
          List(
            ConnectionEventInsert(
              routerId,
              Some(MacAddress.unsafe("aa:bb:cc:dd:ee:01")),
              HostId.Fqdn(Hostname.unsafe("kids-site.com")),
              None,
              true,
              "allowed",
              recentTs,
            ),
            ConnectionEventInsert(
              routerId,
              Some(MacAddress.unsafe("aa:bb:cc:dd:ee:02")),
              HostId.Fqdn(Hostname.unsafe("adults-site.com")),
              None,
              true,
              "allowed",
              recentTs,
            ),
          ),
        )
        routes = LogRoutes.routes(auth, connRepo, upRepo)
        resp <- getJson(routes, s"/api/logs?profileId=${kidsPid.value}", token)
        body <- resp.body.asString
        logs <- ZIO.fromEither(body.fromJson[List[QueryLog]])
      } yield assertTrue(logs.length == 1) &&
        assertTrue(logs.head.host.value == "kids-site.com")
    },
  ) @@ TestAspect.sequential
}
