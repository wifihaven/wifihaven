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
    test("GET /api/logs?profileId=A,B accepts comma-separated multi-value (#865)") {
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
        guestsPid   <- profileRepo.create("Guests", List.empty)
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
        _           <- deviceRepo.upsert(
          MacAddress.unsafe("aa:bb:cc:dd:ee:03"),
          "Guest Laptop",
          Some(guestsPid),
          "10.0.0.3",
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
            ConnectionEventInsert(
              routerId,
              Some(MacAddress.unsafe("aa:bb:cc:dd:ee:03")),
              HostId.Fqdn(Hostname.unsafe("guest-site.com")),
              None,
              true,
              "allowed",
              recentTs,
            ),
          ),
        )
        routes = LogRoutes.routes(auth, connRepo, upRepo)
        // Two profiles selected — should include only kids + adults rows.
        respMulti <- getJson(
          routes,
          s"/api/logs?profileId=${kidsPid.value},${adultsPid.value}",
          token,
        )
        bodyMulti <- respMulti.body.asString
        logsMulti <- ZIO.fromEither(bodyMulti.fromJson[List[QueryLog]])
        // Empty list (absent param) returns everything — empty != "match nothing".
        respAll   <- getJson(routes, "/api/logs", token)
        bodyAll   <- respAll.body.asString
        logsAll   <- ZIO.fromEither(bodyAll.fromJson[List[QueryLog]])
        // Single-value param still works — backwards compatibility.
        respOne   <- getJson(routes, s"/api/logs?profileId=${kidsPid.value}", token)
        bodyOne   <- respOne.body.asString
        logsOne   <- ZIO.fromEither(bodyOne.fromJson[List[QueryLog]])
      } yield assertTrue(
        logsMulti.map(_.host.value).sorted == List("adults-site.com", "kids-site.com"),
      ) &&
        assertTrue(logsAll.length == 3) &&
        assertTrue(logsOne.length == 1 && logsOne.head.host.value == "kids-site.com")
    },
    test("GET /api/logs?mac=A,B accepts comma-separated multi-value (#865)") {
      for {
        _          <- cleanDb
        routerId   <- seedRouter()
        deviceRepo <- ZIO.service[DeviceRepo]
        connRepo   <- ZIO.service[ConnectionEventRepo]
        upRepo     <- ZIO.service[UserProfileRepo]
        auth       <- makeAuth
        token      <- auth.login("admin", "changeme").map(_.token.value)
        _ <- deviceRepo.upsert(MacAddress.unsafe("aa:bb:cc:dd:ee:01"), "A", None, "10.0.0.1")
        _ <- deviceRepo.upsert(MacAddress.unsafe("aa:bb:cc:dd:ee:02"), "B", None, "10.0.0.2")
        _ <- deviceRepo.upsert(MacAddress.unsafe("aa:bb:cc:dd:ee:03"), "C", None, "10.0.0.3")
        _ <- connRepo.insertBatch(
          List(
            ConnectionEventInsert(
              routerId,
              Some(MacAddress.unsafe("aa:bb:cc:dd:ee:01")),
              HostId.Fqdn(Hostname.unsafe("a.example.com")),
              None,
              true,
              "allowed",
              recentTs,
            ),
            ConnectionEventInsert(
              routerId,
              Some(MacAddress.unsafe("aa:bb:cc:dd:ee:02")),
              HostId.Fqdn(Hostname.unsafe("b.example.com")),
              None,
              true,
              "allowed",
              recentTs,
            ),
            ConnectionEventInsert(
              routerId,
              Some(MacAddress.unsafe("aa:bb:cc:dd:ee:03")),
              HostId.Fqdn(Hostname.unsafe("c.example.com")),
              None,
              true,
              "allowed",
              recentTs,
            ),
          ),
        )
        routes = LogRoutes.routes(auth, connRepo, upRepo)
        resp <- getJson(routes, "/api/logs?mac=aa:bb:cc:dd:ee:01,aa:bb:cc:dd:ee:02", token)
        body <- resp.body.asString
        logs <- ZIO.fromEither(body.fromJson[List[QueryLog]])
      } yield assertTrue(logs.map(_.host.value).sorted == List("a.example.com", "b.example.com"))
    },
    test("GET /api/connection-events/series buckets domain counts (1h, groupBy=domain)") {
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
              HostId.Fqdn(Hostname.unsafe("youtube.com")),
              None,
              true,
              "allowed",
              recentTs,
            ),
            ConnectionEventInsert(
              routerId,
              Some(MacAddress.unsafe("aa:bb:cc:00:00:01")),
              HostId.Fqdn(Hostname.unsafe("youtube.com")),
              None,
              false,
              "blocked",
              recentTs,
            ),
            ConnectionEventInsert(
              routerId,
              Some(MacAddress.unsafe("aa:bb:cc:00:00:02")),
              HostId.Fqdn(Hostname.unsafe("youtube.com")),
              None,
              true,
              "allowed",
              recentTs,
            ),
            ConnectionEventInsert(
              routerId,
              Some(MacAddress.unsafe("aa:bb:cc:00:00:01")),
              HostId.Fqdn(Hostname.unsafe("facebook.com")),
              None,
              true,
              "allowed",
              recentTs,
            ),
          ),
        )
        routes = LogRoutes.routes(auth, connRepo, upRepo)
        resp <- getJson(routes, "/api/connection-events/series?bucket=1h&groupBy=domain", token)
        body <- resp.body.asString
        rows <- ZIO.fromEither(body.fromJson[List[ConnectionEventAggRow]])
        yt = rows.find(_.groups.getOrElse("domain", "") == "youtube.com").get
        fb = rows.find(_.groups.getOrElse("domain", "") == "facebook.com").get
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(yt.countSucceeded == 2) &&
        assertTrue(yt.countBlocked == 1) &&
        assertTrue(fb.countSucceeded == 1) &&
        assertTrue(fb.countBlocked == 0) &&
        // youtube has 3 events vs facebook 1 — total-count desc ordering
        assertTrue(rows.head.groups.getOrElse("domain", "") == "youtube.com")
    },
    // #917: strictly additive aggregation. Empty groupBy = one row per window.
    test("#917: GET /api/connection-events/series with no groupBy returns one row per window") {
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
              HostId.Fqdn(Hostname.unsafe("youtube.com")),
              None,
              true,
              "allowed",
              recentTs,
            ),
            ConnectionEventInsert(
              routerId,
              Some(MacAddress.unsafe("aa:bb:cc:00:00:01")),
              HostId.Fqdn(Hostname.unsafe("youtube.com")),
              None,
              false,
              "blocked",
              recentTs,
            ),
            ConnectionEventInsert(
              routerId,
              Some(MacAddress.unsafe("aa:bb:cc:00:00:02")),
              HostId.Fqdn(Hostname.unsafe("facebook.com")),
              None,
              true,
              "allowed",
              recentTs,
            ),
          ),
        )
        routes = LogRoutes.routes(auth, connRepo, upRepo)
        resp <- getJson(routes, "/api/connection-events/series?bucket=1h", token)
        body <- resp.body.asString
        rows <- ZIO.fromEither(body.fromJson[List[ConnectionEventAggRow]])
      } yield assertTrue(resp.status == Status.Ok) &&
        // 3 events all in the same 1h window → one fully-aggregated row.
        assertTrue(rows.length == 1) &&
        assertTrue(rows.head.groups.isEmpty) &&
        assertTrue(rows.head.countSucceeded == 2) &&
        assertTrue(rows.head.countBlocked == 1) &&
        assertTrue(rows.head.distinctDomains == 2) &&
        assertTrue(rows.head.distinctDevices == 2)
    },
    test("#917: GET /api/connection-events/series accepts repeated groupBy params") {
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
              HostId.Fqdn(Hostname.unsafe("youtube.com")),
              None,
              true,
              "allowed",
              recentTs,
            ),
            ConnectionEventInsert(
              routerId,
              Some(MacAddress.unsafe("aa:bb:cc:00:00:02")),
              HostId.Fqdn(Hostname.unsafe("youtube.com")),
              None,
              true,
              "allowed",
              recentTs,
            ),
            ConnectionEventInsert(
              routerId,
              Some(MacAddress.unsafe("aa:bb:cc:00:00:01")),
              HostId.Fqdn(Hostname.unsafe("facebook.com")),
              None,
              true,
              "allowed",
              recentTs,
            ),
          ),
        )
        routes = LogRoutes.routes(auth, connRepo, upRepo)
        resp <- getJson(
          routes,
          "/api/connection-events/series?bucket=1h&groupBy=device&groupBy=domain",
          token,
        )
        body <- resp.body.asString
        rows <- ZIO.fromEither(body.fromJson[List[ConnectionEventAggRow]])
      } yield assertTrue(resp.status == Status.Ok) &&
        // Three distinct (device,domain) tuples in the single window.
        assertTrue(rows.length == 3) &&
        assertTrue(rows.forall(r => r.groups.contains("device") && r.groups.contains("domain")))
    },
    test("#917: GET /api/connection-events/series rejects unknown groupBy with 400") {
      for {
        _        <- cleanDb
        connRepo <- ZIO.service[ConnectionEventRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token.value)
        routes = LogRoutes.routes(auth, connRepo, upRepo)
        resp <- getJson(routes, "/api/connection-events/series?bucket=1h&groupBy=bogus", token)
        body <- resp.body.asString
      } yield assertTrue(resp.status == Status.BadRequest) &&
        assertTrue(body.contains("unknown groupBy"))
    },
    test("GET /api/connection-events/series passes through blocked filter") {
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
              HostId.Fqdn(Hostname.unsafe("ok.com")),
              None,
              true,
              "allowed",
              recentTs,
            ),
            ConnectionEventInsert(
              routerId,
              None,
              HostId.Fqdn(Hostname.unsafe("bad.com")),
              None,
              false,
              "blocked",
              recentTs,
            ),
          ),
        )
        routes = LogRoutes.routes(auth, connRepo, upRepo)
        resp <- getJson(
          routes,
          "/api/connection-events/series?bucket=1d&groupBy=domain&blocked=true",
          token,
        )
        body <- resp.body.asString
        rows <- ZIO.fromEither(body.fromJson[List[ConnectionEventAggRow]])
      } yield assertTrue(rows.length == 1) &&
        assertTrue(rows.head.groups.getOrElse("domain", "") == "bad.com") &&
        assertTrue(rows.head.countBlocked == 1)
    },
    test("GET /api/connection-events/series rejects groupBy=apex with 400 (#849)") {
      for {
        _        <- cleanDb
        connRepo <- ZIO.service[ConnectionEventRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token.value)
        routes = LogRoutes.routes(auth, connRepo, upRepo)
        resp <- getJson(routes, "/api/connection-events/series?bucket=1h&groupBy=apex", token)
      } yield assertTrue(resp.status == Status.BadRequest)
    },
    test("GET /api/connection-events/series rejects groupBy=app with 400") {
      for {
        _        <- cleanDb
        connRepo <- ZIO.service[ConnectionEventRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token.value)
        routes = LogRoutes.routes(auth, connRepo, upRepo)
        resp <- getJson(routes, "/api/connection-events/series?bucket=1h&groupBy=app", token)
      } yield assertTrue(resp.status == Status.BadRequest)
    },
    test("GET /api/connection-events/series rejects bucket=off with 400") {
      for {
        _        <- cleanDb
        connRepo <- ZIO.service[ConnectionEventRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token.value)
        routes = LogRoutes.routes(auth, connRepo, upRepo)
        resp <- getJson(routes, "/api/connection-events/series?bucket=off&groupBy=domain", token)
      } yield assertTrue(resp.status == Status.BadRequest)
    },
    test("GET /api/connection-events/series rejects unknown bucket with 400") {
      for {
        _        <- cleanDb
        connRepo <- ZIO.service[ConnectionEventRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token.value)
        routes = LogRoutes.routes(auth, connRepo, upRepo)
        resp <- getJson(routes, "/api/connection-events/series?bucket=5m&groupBy=domain", token)
      } yield assertTrue(resp.status == Status.BadRequest)
    },
    test("GET /api/connection-events/series buckets across multiple windows (10m)") {
      for {
        _        <- cleanDb
        routerId <- seedRouter()
        connRepo <- ZIO.service[ConnectionEventRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token.value)
        now = Instant.now()
        // Two events 25 min apart → land in different 10-minute buckets
        _ <- connRepo.insertBatch(
          List(
            ConnectionEventInsert(
              routerId,
              None,
              HostId.Fqdn(Hostname.unsafe("a.com")),
              None,
              true,
              "allowed",
              now.minusSeconds(60),
            ),
            ConnectionEventInsert(
              routerId,
              None,
              HostId.Fqdn(Hostname.unsafe("a.com")),
              None,
              true,
              "allowed",
              now.minusSeconds(60 * 25),
            ),
          ),
        )
        routes = LogRoutes.routes(auth, connRepo, upRepo)
        resp <- getJson(routes, "/api/connection-events/series?bucket=10m&groupBy=domain", token)
        body <- resp.body.asString
        rows <- ZIO.fromEither(body.fromJson[List[ConnectionEventAggRow]])
      } yield assertTrue(rows.length == 2) &&
        assertTrue(rows.forall(_.groups.getOrElse("domain", "") == "a.com"))
    },
  ) @@ TestAspect.sequential
}
