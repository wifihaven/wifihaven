package familydns.api.feature

import familydns.api.JwtConfig
import familydns.api.auth.*
import familydns.api.db.*
import familydns.api.routes.*
import familydns.shared.*
import familydns.shared.types.*
import familydns.shared.Clock.TestClock
import familydns.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*

import java.time.{Instant, ZoneOffset}

object SessionApiSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

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

  private def seedRouter(name: String = "home"): ZIO[RouterRepo, Throwable, RouterId] =
    ZIO.serviceWithZIO[RouterRepo] { rRepo =>
      for {
        id <- rRepo.create(name, Sha256Hex.unsafe("r" * 64))
        _  <- rRepo.completeEnrollment(id, Sha256Hex.unsafe("s" * 64))
      } yield id
    }

  private def getJson(routes: Routes[Any, Response], path: String, token: String) =
    routes.runZIO(
      Request
        .get(URL.decode(path).toOption.get)
        .addHeader(Header.Authorization.Bearer(token)),
    )

  // Recent base time so default `hours=24` filter includes seeded rows.
  private def baseInstant = Instant.now().minusSeconds(2 * 3600)

  private def insertReport(
      routerId: RouterId,
      mac: String,
      host: String,
      start: Instant,
      activeSeconds: Int = 300,
      periodLen: Long = 300,
      bytesIn: Long = 1000,
      bytesOut: Long = 200,
  ): ZIO[TrafficReportRepo, Throwable, Unit] =
    ZIO.serviceWithZIO[TrafficReportRepo] { tr =>
      val end  = start.plusSeconds(periodLen)
      val date = start.atZone(ZoneOffset.UTC).toLocalDate
      tr.insertBatch(
        List(
          TrafficReportInsert(
            routerId,
            MacAddress.unsafe(mac),
            None,
            Hostname.unsafe(host),
            date,
            start,
            end,
            activeSeconds,
            bytesIn,
            bytesOut,
          ),
        ),
      ).unit
    }

  private val mac1 = "aa:bb:cc:dd:ee:01"
  private val mac2 = "aa:bb:cc:dd:ee:02"

  def spec = suite("Sessions API (traffic_reports stitched)")(
    test("GET /api/sessions stitches contiguous periods into one session") {
      for {
        _        <- cleanDb
        routerId <- seedRouter()
        dr       <- ZIO.service[DeviceRepo]
        pr       <- ZIO.service[ProfileRepo]
        tr       <- ZIO.service[TrafficReportRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        kid      <- pr.create("Kids", Nil)
        _        <- TestLayers.seedDevice(dr, mac1, "iPad", kid)
        t0 = baseInstant
        _ <- insertReport(routerId, mac1, "youtube.com", t0, activeSeconds = 300)
        _ <- insertReport(routerId, mac1, "youtube.com", t0.plusSeconds(300), activeSeconds = 240)
        auth  <- makeAuth
        token <- auth.login("admin", "changeme").map(_.token.value)
        routes = SessionRoutes.routes(auth, tr, dr, pr, upRepo)
        resp <- getJson(routes, "/api/sessions", token)
        body <- resp.body.asString
        page <- ZIO.fromEither(body.fromJson[SessionPage])
      } yield assertTrue(
        resp.status == Status.Ok,
        page.sessions.length == 1,
        page.sessions.head.durationSeconds == 540L,
        page.sessions.head.periodCount == 2,
        page.sessions.head.hostname == Hostname.unsafe("youtube.com"),
        page.sessions.head.deviceName.contains("iPad"),
        page.sessions.head.profileName.contains("Kids"),
        page.sessions.head.profileId.contains(kid),
      )
    },
    test("GET /api/sessions splits non-contiguous periods into separate sessions") {
      for {
        _        <- cleanDb
        routerId <- seedRouter()
        dr       <- ZIO.service[DeviceRepo]
        pr       <- ZIO.service[ProfileRepo]
        tr       <- ZIO.service[TrafficReportRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        kid      <- pr.create("Kids", Nil)
        _        <- TestLayers.seedDevice(dr, mac1, "iPad", kid)
        t0 = baseInstant
        _ <- insertReport(routerId, mac1, "youtube.com", t0, activeSeconds = 300)
        // one empty period (t0+300..t0+600), then a fresh session
        _ <- insertReport(routerId, mac1, "youtube.com", t0.plusSeconds(600), activeSeconds = 300)
        auth  <- makeAuth
        token <- auth.login("admin", "changeme").map(_.token.value)
        routes = SessionRoutes.routes(auth, tr, dr, pr, upRepo)
        resp <- getJson(routes, "/api/sessions", token)
        body <- resp.body.asString
        page <- ZIO.fromEither(body.fromJson[SessionPage])
      } yield assertTrue(
        page.sessions.length == 2,
        page.sessions.map(_.durationSeconds).sum == 600L,
      )
    },
    test("GET /api/sessions?mac=… filters to one device") {
      for {
        _        <- cleanDb
        routerId <- seedRouter()
        dr       <- ZIO.service[DeviceRepo]
        pr       <- ZIO.service[ProfileRepo]
        tr       <- ZIO.service[TrafficReportRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        kid      <- pr.create("Kids", Nil)
        _        <- TestLayers.seedDevice(dr, mac1, "iPad", kid)
        _        <- TestLayers.seedDevice(dr, mac2, "Phone", kid)
        _        <- insertReport(routerId, mac1, "youtube.com", baseInstant)
        _        <- insertReport(routerId, mac2, "tiktok.com", baseInstant)
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token.value)
        routes = SessionRoutes.routes(auth, tr, dr, pr, upRepo)
        resp <- getJson(routes, s"/api/sessions?mac=$mac1", token)
        body <- resp.body.asString
        page <- ZIO.fromEither(body.fromJson[SessionPage])
      } yield assertTrue(
        page.sessions.length == 1,
        page.sessions.head.mac == MacAddress.unsafe(mac1),
        page.sessions.head.hostname == Hostname.unsafe("youtube.com"),
      )
    },
    test("GET /api/sessions?host=… case-insensitive substring") {
      for {
        _        <- cleanDb
        routerId <- seedRouter()
        dr       <- ZIO.service[DeviceRepo]
        pr       <- ZIO.service[ProfileRepo]
        tr       <- ZIO.service[TrafficReportRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        kid      <- pr.create("Kids", Nil)
        _        <- TestLayers.seedDevice(dr, mac1, "iPad", kid)
        _        <- insertReport(routerId, mac1, "youtube.com", baseInstant)
        _        <- insertReport(routerId, mac1, "tiktok.com", baseInstant)
        _        <- insertReport(routerId, mac1, "www.YOUTUBE.com", baseInstant.plusSeconds(3600))
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token.value)
        routes = SessionRoutes.routes(auth, tr, dr, pr, upRepo)
        resp <- getJson(routes, "/api/sessions?host=youtube", token)
        body <- resp.body.asString
        page <- ZIO.fromEither(body.fromJson[SessionPage])
      } yield assertTrue(
        page.sessions.length == 2,
        page.sessions.forall(_.hostname.value.toLowerCase.contains("youtube")),
      )
    },
    test("GET /api/sessions?profileId=… filters via devices.profile_id") {
      for {
        _        <- cleanDb
        routerId <- seedRouter()
        dr       <- ZIO.service[DeviceRepo]
        pr       <- ZIO.service[ProfileRepo]
        tr       <- ZIO.service[TrafficReportRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        kids     <- pr.create("Kids", Nil)
        adults   <- pr.create("Adults", Nil)
        _        <- TestLayers.seedDevice(dr, mac1, "iPad", kids)
        _        <- TestLayers.seedDevice(dr, mac2, "Phone", adults)
        _        <- insertReport(routerId, mac1, "youtube.com", baseInstant)
        _        <- insertReport(routerId, mac2, "news.com", baseInstant)
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token.value)
        routes = SessionRoutes.routes(auth, tr, dr, pr, upRepo)
        resp <- getJson(routes, s"/api/sessions?profileId=$kids", token)
        body <- resp.body.asString
        page <- ZIO.fromEither(body.fromJson[SessionPage])
      } yield assertTrue(
        page.sessions.length == 1,
        page.sessions.head.profileId.contains(kids),
      )
    },
    test("GET /api/sessions limit pagination via cursor") {
      for {
        _        <- cleanDb
        routerId <- seedRouter()
        dr       <- ZIO.service[DeviceRepo]
        pr       <- ZIO.service[ProfileRepo]
        tr       <- ZIO.service[TrafficReportRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        kid      <- pr.create("Kids", Nil)
        _        <- TestLayers.seedDevice(dr, mac1, "iPad", kid)
        _        <- ZIO.foreachDiscard(0 until 5)(i =>
          // Five distinct, non-contiguous periods → five sessions.
          insertReport(routerId, mac1, s"host$i.com", baseInstant.plusSeconds(i.toLong * 3600)),
        )
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token.value)
        routes = SessionRoutes.routes(auth, tr, dr, pr, upRepo)
        r1 <- getJson(routes, "/api/sessions?limit=2", token)
        b1 <- r1.body.asString
        p1 <- ZIO.fromEither(b1.fromJson[SessionPage])
        cursor    = p1.nextCursor.getOrElse("")
        cursorEnc = java.net.URLEncoder.encode(cursor, java.nio.charset.StandardCharsets.UTF_8)
        r2 <- getJson(routes, s"/api/sessions?limit=2&cursor=$cursorEnc", token)
        b2 <- r2.body.asString
        p2 <- ZIO.fromEither(b2.fromJson[SessionPage])
      } yield assertTrue(
        p1.sessions.length == 2,
        p1.nextCursor.isDefined,
        p2.sessions.length == 2,
        p1.sessions.map(_.startedAt).toSet.intersect(p2.sessions.map(_.startedAt).toSet).isEmpty,
      )
    },
    test("GET /api/sessions limit is clamped at 500") {
      for {
        _      <- cleanDb
        _      <- seedRouter()
        dr     <- ZIO.service[DeviceRepo]
        pr     <- ZIO.service[ProfileRepo]
        tr     <- ZIO.service[TrafficReportRepo]
        upRepo <- ZIO.service[UserProfileRepo]
        auth   <- makeAuth
        token  <- auth.login("admin", "changeme").map(_.token.value)
        routes = SessionRoutes.routes(auth, tr, dr, pr, upRepo)
        resp <- getJson(routes, "/api/sessions?limit=9999", token)
      } yield assertTrue(resp.status == Status.Ok)
    },
    test("GET /api/sessions without token → 401") {
      for {
        _      <- cleanDb
        _      <- seedRouter()
        dr     <- ZIO.service[DeviceRepo]
        pr     <- ZIO.service[ProfileRepo]
        tr     <- ZIO.service[TrafficReportRepo]
        upRepo <- ZIO.service[UserProfileRepo]
        auth   <- makeAuth
        routes = SessionRoutes.routes(auth, tr, dr, pr, upRepo)
        resp <- routes.runZIO(Request.get(URL.decode("/api/sessions").toOption.get))
      } yield assertTrue(resp.status == Status.Unauthorized)
    },
    test("GET /api/sessions filters sessions to visible profiles for child token") {
      // Child user linked to Kids profile sees only sessions for devices in Kids.
      for {
        _        <- cleanDb
        routerId <- seedRouter()
        dr       <- ZIO.service[DeviceRepo]
        pr       <- ZIO.service[ProfileRepo]
        tr       <- ZIO.service[TrafficReportRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        userRepo <- ZIO.service[UserRepo]
        kids     <- pr.create("Kids", Nil)
        adults   <- pr.create("Adults", Nil)
        _        <- TestLayers.seedDevice(dr, mac1, "iPad", kids)
        _        <- TestLayers.seedDevice(dr, mac2, "Phone", adults)
        _        <- insertReport(routerId, mac1, "youtube.com", baseInstant)
        _        <- insertReport(routerId, mac2, "news.com", baseInstant)
        auth     <- makeAuth
        hash     <- auth.hashPassword("pass")
        childId  <- userRepo.create("alice", hash, "child")
        _        <- upRepo.setProfilesForUser(childId, List(kids))
        token    <- auth.login("alice", "pass").map(_.token.value)
        routes = SessionRoutes.routes(auth, tr, dr, pr, upRepo)
        resp <- getJson(routes, "/api/sessions", token)
        body <- resp.body.asString
        page <- ZIO.fromEither(body.fromJson[SessionPage])
      } yield assertTrue(
        page.sessions.length == 1,
        page.sessions.head.profileId.contains(kids),
      )
    },
  ) @@ TestAspect.sequential
}
