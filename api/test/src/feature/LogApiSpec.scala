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
        page <- ZIO.fromEither(body.fromJson[QueryLogPage])
        logs = page.rows
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
        page <- ZIO.fromEither(body.fromJson[QueryLogPage])
        logs = page.rows
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
        page <- ZIO.fromEither(body.fromJson[QueryLogPage])
        logs = page.rows
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
        page <- ZIO.fromEither(body.fromJson[QueryLogPage])
        logs = page.rows
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
    test("GET /api/logs cursor pagination: pages cover the dataset without dups or gaps (#862)") {
      // Synthetic dataset of N rows; walk pages of K and assert the union
      // equals the dataset and pages don't overlap.
      val N        = 13
      val PageSize = 4
      for {
        _        <- cleanDb
        routerId <- seedRouter()
        connRepo <- ZIO.service[ConnectionEventRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token.value)
        _        <- connRepo.insertBatch(
          (1 to N).toList.map(i =>
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
        pager  = (cursor: Option[String]) => {
          val q =
            cursor.fold(s"/api/logs?limit=$PageSize")(c => s"/api/logs?limit=$PageSize&cursor=$c")
          getJson(routes, q, token)
            .flatMap(_.body.asString)
            .flatMap(b => ZIO.fromEither(b.fromJson[QueryLogPage]))
        }
        // Walk pages until nextCursor is None. Bounded loop (no infinite test).
        firstPage <- pager(None)
        secondPage <- firstPage.nextCursor match {
          case Some(c) => pager(Some(c))
          case None    => ZIO.succeed(QueryLogPage(Nil, None))
        }
        thirdPage  <- secondPage.nextCursor match {
          case Some(c) => pager(Some(c))
          case None    => ZIO.succeed(QueryLogPage(Nil, None))
        }
        fourthPage <- thirdPage.nextCursor match {
          case Some(c) => pager(Some(c))
          case None    => ZIO.succeed(QueryLogPage(Nil, None))
        }
        all = firstPage.rows ++ secondPage.rows ++ thirdPage.rows ++ fourthPage.rows
        ids = all.map(_.id.value)
      } yield assertTrue(firstPage.rows.length == PageSize) &&
        assertTrue(secondPage.rows.length == PageSize) &&
        assertTrue(thirdPage.rows.length == PageSize) &&
        // The remainder (N % PageSize = 1) lives in the fourth page.
        assertTrue(fourthPage.rows.length == 1) &&
        // No nextCursor on the final partial page.
        assertTrue(fourthPage.nextCursor.isEmpty) &&
        assertTrue(all.length == N) &&
        // No duplicates across pages.
        assertTrue(ids.distinct.length == N)
    },
    test("GET /api/logs?cursor=garbage returns 400 (#862)") {
      for {
        _        <- cleanDb
        _        <- seedRouter()
        connRepo <- ZIO.service[ConnectionEventRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token.value)
        routes = LogRoutes.routes(auth, connRepo, upRepo)
        resp <- getJson(routes, "/api/logs?cursor=not-base64-json", token)
      } yield assertTrue(resp.status == Status.BadRequest)
    },
    test("GET /api/logs cursor + filter composes (#862)") {
      // Page through a filtered query — pages must respect the filter AND not
      // drop rows on cursor handoff.
      for {
        _        <- cleanDb
        routerId <- seedRouter()
        connRepo <- ZIO.service[ConnectionEventRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token.value)
        _        <- connRepo.insertBatch(
          (1 to 6).toList.map(i =>
            ConnectionEventInsert(
              routerId,
              None,
              HostId.Fqdn(Hostname.unsafe(s"site$i.com")),
              None,
              i % 2 == 0, // half blocked
              if (i % 2 == 0) "allowed" else "blocked",
              Instant.now().minusSeconds(i.toLong * 10),
            ),
          ),
        )
        routes = LogRoutes.routes(auth, connRepo, upRepo)
        p1       <- getJson(routes, "/api/logs?limit=2&blocked=true", token)
          .flatMap(_.body.asString)
          .flatMap(b => ZIO.fromEither(b.fromJson[QueryLogPage]))
        p2       <- p1.nextCursor match {
          case Some(c) =>
            getJson(routes, s"/api/logs?limit=2&blocked=true&cursor=$c", token)
              .flatMap(_.body.asString)
              .flatMap(b => ZIO.fromEither(b.fromJson[QueryLogPage]))
          case None    => ZIO.succeed(QueryLogPage(Nil, None))
        }
      } yield assertTrue(p1.rows.length == 2) &&
        assertTrue(p1.rows.forall(_.blocked)) &&
        assertTrue(p2.rows.length == 1) &&
        assertTrue(p2.rows.forall(_.blocked)) &&
        assertTrue((p1.rows ++ p2.rows).map(_.id.value).distinct.length == 3)
    },
    test("GET /api/logs?until= anchors window right edge (subsumes #863)") {
      for {
        _        <- cleanDb
        routerId <- seedRouter()
        connRepo <- ZIO.service[ConnectionEventRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token.value)
        now       = Instant.now()
        oldTs     = now.minusSeconds(7200)  // 2h ago
        veryOldTs = now.minusSeconds(86400) // 1d ago
        _ <- connRepo.insertBatch(
          List(
            ConnectionEventInsert(
              routerId,
              None,
              HostId.Fqdn(Hostname.unsafe("a.com")),
              None,
              true,
              "",
              now.minusSeconds(60),
            ),
            ConnectionEventInsert(
              routerId,
              None,
              HostId.Fqdn(Hostname.unsafe("b.com")),
              None,
              true,
              "",
              oldTs,
            ),
            ConnectionEventInsert(
              routerId,
              None,
              HostId.Fqdn(Hostname.unsafe("c.com")),
              None,
              true,
              "",
              veryOldTs,
            ),
          ),
        )
        routes = LogRoutes.routes(auth, connRepo, upRepo)
        // Anchor at 3h ago with 6h lookback — should see only `c.com` (and
        // exclude `a.com` and `b.com` which are inside the past 3h).
        anchor = now.minusSeconds(3 * 3600)
        resp <- getJson(routes, s"/api/logs?until=$anchor&hours=24", token)
          .flatMap(_.body.asString)
          .flatMap(b => ZIO.fromEither(b.fromJson[QueryLogPage]))
      } yield assertTrue(resp.rows.map(_.host.value) == List("c.com"))
    },
    test("GET /api/logs?limit=999 rejects oversize page (#862)") {
      for {
        _        <- cleanDb
        _        <- seedRouter()
        connRepo <- ZIO.service[ConnectionEventRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token.value)
        routes = LogRoutes.routes(auth, connRepo, upRepo)
        resp <- getJson(routes, "/api/logs?limit=9999", token)
      } yield assertTrue(resp.status == Status.BadRequest)
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
        page <- ZIO.fromEither(body.fromJson[QueryLogPage])
        logs = page.rows
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
        page <- ZIO.fromEither(body.fromJson[QueryLogPage])
        logs = page.rows
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
        respMulti     <- getJson(
          routes,
          s"/api/logs?profileId=${kidsPid.value},${adultsPid.value}",
          token,
        )
        bodyMulti     <- respMulti.body.asString
        pageLogsmulti <- ZIO.fromEither(bodyMulti.fromJson[QueryLogPage])
        logsMulti = pageLogsmulti.rows
        // Empty list (absent param) returns everything — empty != "match nothing".
        respAll     <- getJson(routes, "/api/logs", token)
        bodyAll     <- respAll.body.asString
        pageLogsall <- ZIO.fromEither(bodyAll.fromJson[QueryLogPage])
        logsAll = pageLogsall.rows
        // Single-value param still works — backwards compatibility.
        respOne     <- getJson(routes, s"/api/logs?profileId=${kidsPid.value}", token)
        bodyOne     <- respOne.body.asString
        pageLogsone <- ZIO.fromEither(bodyOne.fromJson[QueryLogPage])
        logsOne = pageLogsone.rows
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
        resp     <- getJson(routes, "/api/logs?mac=aa:bb:cc:dd:ee:01,aa:bb:cc:dd:ee:02", token)
        body     <- resp.body.asString
        pageLogs <- ZIO.fromEither(body.fromJson[QueryLogPage])
        logs = pageLogs.rows
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
        page <- ZIO.fromEither(body.fromJson[ConnectionEventSeriesPage])
        rows = page.rows
        yt   = rows.find(_.groups.getOrElse("domain", "") == "youtube.com").get
        fb   = rows.find(_.groups.getOrElse("domain", "") == "facebook.com").get
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(yt.countSucceeded == 2) &&
        assertTrue(yt.countBlocked == 1) &&
        assertTrue(fb.countSucceeded == 1) &&
        assertTrue(fb.countBlocked == 0) &&
        // #862: ordering is (window_start DESC, group_key ASC). Within a single
        // window, alphabetical group key wins — facebook < youtube.
        assertTrue(rows.head.groups.getOrElse("domain", "") == "facebook.com")
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
        resp     <- getJson(routes, "/api/connection-events/series?bucket=1h", token)
        body     <- resp.body.asString
        pageRows <- ZIO.fromEither(body.fromJson[ConnectionEventSeriesPage])
        rows = pageRows.rows
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
        resp     <- getJson(
          routes,
          "/api/connection-events/series?bucket=1h&groupBy=device&groupBy=domain",
          token,
        )
        body     <- resp.body.asString
        pageRows <- ZIO.fromEither(body.fromJson[ConnectionEventSeriesPage])
        rows = pageRows.rows
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
        page <- ZIO.fromEither(body.fromJson[ConnectionEventSeriesPage])
        rows = page.rows
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
    // #769: groupBy=app is now accepted — rows join through app_hosts.
    test("#769: GET /api/connection-events/series buckets connection events by app") {
      for {
        _        <- cleanDb
        routerId <- seedRouter()
        connRepo <- ZIO.service[ConnectionEventRepo]
        appRepo  <- ZIO.service[AppRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token.value)
        // One app ("YouTube") owning two hosts; a third host belongs to no
        // app and should bucket under __other__.
        ytId     <- appRepo.create("YouTube", "youtube", None, Some("📺"))
        _        <- appRepo.setHosts(
          ytId,
          List(Hostname.unsafe("youtube.com"), Hostname.unsafe("ytimg.com")),
        )
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
              HostId.Fqdn(Hostname.unsafe("ytimg.com")),
              None,
              true,
              "allowed",
              recentTs,
            ),
            ConnectionEventInsert(
              routerId,
              Some(MacAddress.unsafe("aa:bb:cc:00:00:01")),
              HostId.Fqdn(Hostname.unsafe("ytimg.com")),
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
        resp <- getJson(routes, "/api/connection-events/series?bucket=1h&groupBy=app", token)
        body <- resp.body.asString
        page <- ZIO.fromEither(body.fromJson[ConnectionEventSeriesPage])
        rows = page.rows
        yt   = rows.find(_.groups.getOrElse("app", "") == "youtube").get
        ot   = rows.find(_.groups.getOrElse("app", "") == "__other__").get
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(rows.length == 2) &&
        assertTrue(yt.countSucceeded == 2) &&
        assertTrue(yt.countBlocked == 1) &&
        assertTrue(yt.appName.contains("YouTube")) &&
        assertTrue(yt.appIcon.contains("📺")) &&
        assertTrue(yt.appId.isDefined) &&
        assertTrue(ot.countSucceeded == 1) &&
        assertTrue(ot.countBlocked == 0) &&
        assertTrue(ot.appName.contains("Other")) &&
        assertTrue(ot.appId.isEmpty)
    },
    test("#769: GET /api/connection-events/series with host in 2 apps fans out") {
      for {
        _        <- cleanDb
        routerId <- seedRouter()
        connRepo <- ZIO.service[ConnectionEventRepo]
        appRepo  <- ZIO.service[AppRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token.value)
        // "youtube.com" claimed by both YouTube + Music apps.
        ytId     <- appRepo.create("YouTube", "youtube", None, None)
        muId     <- appRepo.create("Music", "music", None, None)
        _        <- appRepo.setHosts(ytId, List(Hostname.unsafe("youtube.com")))
        _        <- appRepo.setHosts(muId, List(Hostname.unsafe("youtube.com")))
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
          ),
        )
        routes = LogRoutes.routes(auth, connRepo, upRepo)
        resp <- getJson(routes, "/api/connection-events/series?bucket=1h&groupBy=app", token)
        body <- resp.body.asString
        page <- ZIO.fromEither(body.fromJson[ConnectionEventSeriesPage])
        rows  = page.rows
        slugs = rows.map(_.groups.getOrElse("app", ""))
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(slugs.toSet == Set("youtube", "music")) &&
        assertTrue(rows.forall(_.countSucceeded == 1))
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
        page <- ZIO.fromEither(body.fromJson[ConnectionEventSeriesPage])
        rows = page.rows
      } yield assertTrue(rows.length == 2) &&
        assertTrue(rows.forall(_.groups.getOrElse("domain", "") == "a.com"))
    },
    test("GET /api/connection-events/series cursor pagination covers all rows (#862)") {
      // 5 distinct domains in the same hour → 5 agg rows, paged 2 at a time.
      for {
        _        <- cleanDb
        routerId <- seedRouter()
        connRepo <- ZIO.service[ConnectionEventRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token.value)
        now = Instant.now()
        _ <- connRepo.insertBatch(
          List("alpha", "bravo", "charlie", "delta", "echo").map(d =>
            ConnectionEventInsert(
              routerId,
              None,
              HostId.Fqdn(Hostname.unsafe(s"$d.example")),
              None,
              true,
              "allowed",
              now.minusSeconds(60),
            ),
          ),
        )
        routes = LogRoutes.routes(auth, connRepo, upRepo)
        pager  = (cursor: Option[String]) => {
          val q =
            cursor.fold(s"/api/connection-events/series?bucket=1h&groupBy=domain&limit=2")(c =>
              s"/api/connection-events/series?bucket=1h&groupBy=domain&limit=2&cursor=$c",
            )
          getJson(routes, q, token)
            .flatMap(_.body.asString)
            .flatMap(b => ZIO.fromEither(b.fromJson[ConnectionEventSeriesPage]))
        }
        p1 <- pager(None)
        p2 <- p1.nextCursor match {
          case Some(c) => pager(Some(c))
          case None    => ZIO.succeed(ConnectionEventSeriesPage(Nil, None))
        }
        p3 <- p2.nextCursor match {
          case Some(c) => pager(Some(c))
          case None    => ZIO.succeed(ConnectionEventSeriesPage(Nil, None))
        }
        all = p1.rows ++ p2.rows ++ p3.rows
        domains = all.flatMap(_.groups.get("domain"))
      } yield assertTrue(p1.rows.length == 2) &&
        assertTrue(p2.rows.length == 2) &&
        assertTrue(p3.rows.length == 1) &&
        assertTrue(p3.nextCursor.isEmpty) &&
        assertTrue(domains.distinct.length == 5) &&
        assertTrue(domains == domains.sorted) // group_key ASC ordering
    },
  ) @@ TestAspect.sequential
}
