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

import java.time.{Instant, ZoneOffset}
import java.util.UUID

object DashboardNowApiSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

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

  private def seedRouter(name: String = "home"): ZIO[RouterRepo, Throwable, UUID] =
    ZIO.serviceWithZIO[RouterRepo] { rRepo =>
      for {
        id <- rRepo.create(name, "ENROLL_HASH")
        _  <- rRepo.completeEnrollment(id, "TOKEN_HASH")
      } yield id
    }

  private def insertReport(
      routerId: UUID,
      mac: String,
      host: String,
      start: Instant,
      activeSeconds: Int = 300,
      periodLen: Long = 300,
  ): ZIO[TrafficReportRepo, Throwable, Unit] =
    ZIO.serviceWithZIO[TrafficReportRepo] { tr =>
      val end  = start.plusSeconds(periodLen)
      val date = start.atZone(ZoneOffset.UTC).toLocalDate
      tr.insertBatch(
        List(
          TrafficReportInsert(routerId, mac, None, host, date, start, end, activeSeconds, 1, 1),
        ),
      ).unit
    }

  private def insertConn(
      routerId: UUID,
      mac: String,
      ts: Instant,
  ): ZIO[ConnectionEventRepo, Throwable, Unit] =
    ZIO.serviceWithZIO[ConnectionEventRepo] { cr =>
      cr.insertBatch(
        List(
          ConnectionEventInsert(routerId, Some(mac), "example.com", None, true, "allow", ts),
        ),
      ).unit
    }

  private def getJson(routes: Routes[Any, Response], path: String, token: String) =
    routes.runZIO(
      Request
        .get(URL.decode(path).toOption.get)
        .addHeader(Header.Authorization.Bearer(token)),
    )

  private def buildRoutes(auth: AuthService) =
    for {
      tr     <- ZIO.service[TrafficReportRepo]
      cr     <- ZIO.service[ConnectionEventRepo]
      dr     <- ZIO.service[DeviceRepo]
      pr     <- ZIO.service[ProfileRepo]
      upRepo <- ZIO.service[UserProfileRepo]
      clock  <- ZIO.service[Clock]
    } yield DashboardNowRoutes.routes(auth, tr, cr, dr, pr, upRepo, clock)

  /**
   * V1__init seeds two profiles ("Kids" and "Adults"). Tests that need a clean slate clear them so
   * profile-id assertions are stable.
   */
  private def clearSeededProfiles: ZIO[ProfileRepo, Throwable, Unit] =
    ZIO.serviceWithZIO[ProfileRepo](pr =>
      pr.listAll.flatMap(ps => ZIO.foreachDiscard(ps)(p => pr.delete(p.id))),
    )

  private val mac1 = "aa:bb:cc:dd:ee:01"
  private val mac2 = "aa:bb:cc:dd:ee:02"

  def spec = suite("Dashboard Now API")(
    test("empty DB → 200 with idle seeded profiles, no active devices") {
      for {
        _      <- cleanDb
        auth   <- makeAuth
        token  <- auth.login("admin", "changeme").map(_.token)
        routes <- buildRoutes(auth)
        resp   <- getJson(routes, "/api/dashboard/now", token)
        body   <- resp.body.asString
        parsed <- ZIO.fromEither(body.fromJson[DashboardNow])
      } yield assertTrue(
        resp.status == Status.Ok,
        parsed.profiles.forall(_.activeDevices.isEmpty),
      )
    },
    test("active device with current session: top host + currentSession populated") {
      for {
        _        <- cleanDb
        _        <- clearSeededProfiles
        routerId <- seedRouter()
        dr       <- ZIO.service[DeviceRepo]
        pr       <- ZIO.service[ProfileRepo]
        kid      <- pr.create("Kids", Nil)
        _        <- TestLayers.seedDevice(dr, mac1, "iPad", kid)
        now0     <- Clock.instant
        // 6 contiguous 5-min periods ending 1 min ago.
        t0 = now0.minusSeconds(31 * 60)
        _      <- ZIO.foreachDiscard(0 until 6) { i =>
          insertReport(routerId, mac1, "youtube.com", t0.plusSeconds(i.toLong * 300))
        }
        _      <- insertConn(routerId, mac1, now0.minusSeconds(5))
        auth   <- makeAuth
        token  <- auth.login("admin", "changeme").map(_.token)
        routes <- buildRoutes(auth)
        resp   <- getJson(routes, "/api/dashboard/now", token)
        body   <- resp.body.asString
        parsed <- ZIO.fromEither(body.fromJson[DashboardNow])
        prof = parsed.profiles.find(_.id == kid).get
        dev  = prof.activeDevices.head
      } yield assertTrue(
        parsed.profiles.length == 1,
        prof.activeDevices.length == 1,
        dev.mac == mac1,
        dev.name == "iPad",
        dev.lastSeenSeconds <= 60L,
        dev.topHosts.headOption.exists(_.hostname == "youtube.com"),
        dev.topHosts.head.activeSeconds == 1800L,
        dev.currentSession.exists(_.hostname == "youtube.com"),
        dev.currentSession.get.durationSeconds == 1800L,
      )
    },
    test("stale device (no conn + no recent traffic) is excluded") {
      for {
        _        <- cleanDb
        _        <- clearSeededProfiles
        routerId <- seedRouter()
        dr       <- ZIO.service[DeviceRepo]
        pr       <- ZIO.service[ProfileRepo]
        kid      <- pr.create("Kids", Nil)
        _        <- TestLayers.seedDevice(dr, mac1, "iPad", kid)
        now0     <- Clock.instant
        _        <- insertReport(routerId, mac1, "youtube.com", now0.minusSeconds(20 * 60))
        _        <- insertConn(routerId, mac1, now0.minusSeconds(10 * 60))
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token)
        routes   <- buildRoutes(auth)
        resp     <- getJson(routes, "/api/dashboard/now", token)
        body     <- resp.body.asString
        parsed   <- ZIO.fromEither(body.fromJson[DashboardNow])
      } yield assertTrue(parsed.profiles.find(_.id == kid).get.activeDevices.isEmpty)
    },
    test("traffic_report inside 5min keeps device active even if connection events are stale") {
      for {
        _        <- cleanDb
        _        <- clearSeededProfiles
        routerId <- seedRouter()
        dr       <- ZIO.service[DeviceRepo]
        pr       <- ZIO.service[ProfileRepo]
        kid      <- pr.create("Kids", Nil)
        _        <- TestLayers.seedDevice(dr, mac1, "iPad", kid)
        now0     <- Clock.instant
        _        <- insertConn(routerId, mac1, now0.minusSeconds(6 * 60))
        _        <- insertReport(routerId, mac1, "youtube.com", now0.minusSeconds(6 * 60))
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token)
        routes   <- buildRoutes(auth)
        resp     <- getJson(routes, "/api/dashboard/now", token)
        body     <- resp.body.asString
        parsed   <- ZIO.fromEither(body.fromJson[DashboardNow])
        prof = parsed.profiles.find(_.id == kid).get
        dev  = prof.activeDevices.head
      } yield assertTrue(
        prof.activeDevices.length == 1,
        dev.mac == mac1,
        dev.lastSeenSeconds <= 90L,
      )
    },
    test("currentSession is null when the latest session ended > 10 min ago") {
      for {
        _        <- cleanDb
        _        <- clearSeededProfiles
        routerId <- seedRouter()
        dr       <- ZIO.service[DeviceRepo]
        pr       <- ZIO.service[ProfileRepo]
        kid      <- pr.create("Kids", Nil)
        _        <- TestLayers.seedDevice(dr, mac1, "iPad", kid)
        now0     <- Clock.instant
        _        <- insertReport(routerId, mac1, "youtube.com", now0.minusSeconds(17 * 60))
        _        <- insertConn(routerId, mac1, now0.minusSeconds(30))
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token)
        routes   <- buildRoutes(auth)
        resp     <- getJson(routes, "/api/dashboard/now", token)
        body     <- resp.body.asString
        parsed   <- ZIO.fromEither(body.fromJson[DashboardNow])
        prof = parsed.profiles.find(_.id == kid).get
        dev  = prof.activeDevices.head
      } yield assertTrue(prof.activeDevices.length == 1, dev.currentSession.isEmpty)
    },
    test("topHosts: sums active_seconds, sorts desc, caps at 3") {
      for {
        _        <- cleanDb
        _        <- clearSeededProfiles
        routerId <- seedRouter()
        dr       <- ZIO.service[DeviceRepo]
        pr       <- ZIO.service[ProfileRepo]
        kid      <- pr.create("Kids", Nil)
        _        <- TestLayers.seedDevice(dr, mac1, "iPad", kid)
        now0     <- Clock.instant
        t0 = now0.minusSeconds(10 * 60)
        _    <- insertReport(routerId, mac1, "youtube.com", t0, activeSeconds = 300)
        _    <- insertReport(routerId, mac1, "youtube.com", t0.plusSeconds(300), activeSeconds = 60)
        _    <- insertReport(routerId, mac1, "tiktok.com", t0, activeSeconds = 200)
        _    <- insertReport(routerId, mac1, "reddit.com", t0, activeSeconds = 100)
        _    <- insertReport(routerId, mac1, "news.com", t0, activeSeconds = 50)
        _    <- insertReport(routerId, mac1, "wiki.com", t0, activeSeconds = 20)
        _    <- insertConn(routerId, mac1, now0.minusSeconds(15))
        auth <- makeAuth
        token  <- auth.login("admin", "changeme").map(_.token)
        routes <- buildRoutes(auth)
        resp   <- getJson(routes, "/api/dashboard/now", token)
        body   <- resp.body.asString
        parsed <- ZIO.fromEither(body.fromJson[DashboardNow])
        dev = parsed.profiles.find(_.id == kid).get.activeDevices.head
      } yield assertTrue(
        dev.topHosts.length == 3,
        dev.topHosts.map(_.hostname) == List("youtube.com", "tiktok.com", "reddit.com"),
        dev.topHosts.head.activeSeconds == 360L,
      )
    },
    test("multiple profiles, idle ones retained in id-asc order") {
      for {
        _        <- cleanDb
        _        <- clearSeededProfiles
        routerId <- seedRouter()
        dr       <- ZIO.service[DeviceRepo]
        pr       <- ZIO.service[ProfileRepo]
        kids     <- pr.create("Kids", Nil)
        adults   <- pr.create("Adults", Nil)
        _        <- TestLayers.seedDevice(dr, mac1, "iPad", kids)
        _        <- TestLayers.seedDevice(dr, mac2, "Laptop", adults)
        now0     <- Clock.instant
        _        <- insertConn(routerId, mac1, now0.minusSeconds(10))
        _        <- insertReport(routerId, mac1, "youtube.com", now0.minusSeconds(4 * 60))
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token)
        routes   <- buildRoutes(auth)
        resp     <- getJson(routes, "/api/dashboard/now", token)
        body     <- resp.body.asString
        parsed   <- ZIO.fromEither(body.fromJson[DashboardNow])
      } yield assertTrue(
        parsed.profiles.map(_.id) == List(kids, adults),
        parsed.profiles.find(_.id == kids).get.activeDevices.length == 1,
        parsed.profiles.find(_.id == adults).get.activeDevices.isEmpty,
      )
    },
    test("paused profile is flagged paused: true with active devices still listed") {
      for {
        _        <- cleanDb
        _        <- clearSeededProfiles
        routerId <- seedRouter()
        dr       <- ZIO.service[DeviceRepo]
        pr       <- ZIO.service[ProfileRepo]
        kid      <- pr.create("Kids", Nil)
        _        <- pr.setPaused(kid, true)
        _        <- TestLayers.seedDevice(dr, mac1, "iPad", kid)
        now0     <- Clock.instant
        _        <- insertConn(routerId, mac1, now0.minusSeconds(10))
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token)
        routes   <- buildRoutes(auth)
        resp     <- getJson(routes, "/api/dashboard/now", token)
        body     <- resp.body.asString
        parsed   <- ZIO.fromEither(body.fromJson[DashboardNow])
        prof = parsed.profiles.find(_.id == kid).get
      } yield assertTrue(prof.paused, prof.activeDevices.length == 1)
    },
    test("child token sees only profiles linked to their user") {
      for {
        _        <- cleanDb
        _        <- clearSeededProfiles
        routerId <- seedRouter()
        dr       <- ZIO.service[DeviceRepo]
        pr       <- ZIO.service[ProfileRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        userRepo <- ZIO.service[UserRepo]
        kids     <- pr.create("Kids", Nil)
        adults   <- pr.create("Adults", Nil)
        _        <- TestLayers.seedDevice(dr, mac1, "iPad", kids)
        _        <- TestLayers.seedDevice(dr, mac2, "Laptop", adults)
        now0     <- Clock.instant
        _        <- insertConn(routerId, mac1, now0.minusSeconds(10))
        _        <- insertConn(routerId, mac2, now0.minusSeconds(10))
        auth     <- makeAuth
        hash     <- auth.hashPassword("pass")
        childId  <- userRepo.create("alice", hash, "child")
        _        <- upRepo.setProfilesForUser(childId, List(kids))
        token    <- auth.login("alice", "pass").map(_.token)
        routes   <- buildRoutes(auth)
        resp     <- getJson(routes, "/api/dashboard/now", token)
        body     <- resp.body.asString
        parsed   <- ZIO.fromEither(body.fromJson[DashboardNow])
      } yield assertTrue(
        parsed.profiles.length == 1,
        parsed.profiles.head.id == kids,
        parsed.profiles.head.activeDevices.length == 1,
        parsed.profiles.head.activeDevices.head.mac == mac1,
      )
    },
    test("unauthenticated → 401") {
      for {
        _      <- cleanDb
        auth   <- makeAuth
        routes <- buildRoutes(auth)
        resp   <- routes.runZIO(Request.get(URL.decode("/api/dashboard/now").toOption.get))
      } yield assertTrue(resp.status == Status.Unauthorized)
    },
    test("perf smoke: 20 devices × 6 buckets responds within 1s") {
      for {
        _        <- cleanDb
        _        <- clearSeededProfiles
        routerId <- seedRouter()
        dr       <- ZIO.service[DeviceRepo]
        pr       <- ZIO.service[ProfileRepo]
        kid      <- pr.create("Kids", Nil)
        now0     <- Clock.instant
        _        <- ZIO.foreachDiscard(0 until 20) { i =>
          val mac = f"aa:bb:cc:dd:ee:$i%02x"
          TestLayers.seedDevice(dr, mac, s"dev$i", kid) *>
            insertConn(routerId, mac, now0.minusSeconds(10)) *>
            ZIO.foreachDiscard(0 until 6) { j =>
              insertReport(
                routerId,
                mac,
                s"host${j % 4}.com",
                now0.minusSeconds(30L * 60 - j * 300L),
              )
            }
        }
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token)
        routes   <- buildRoutes(auth)
        t0       <- zio.Clock.nanoTime
        resp     <- getJson(routes, "/api/dashboard/now", token)
        t1       <- zio.Clock.nanoTime
        elapsedMs = (t1 - t0) / 1_000_000L
      } yield assertTrue(resp.status == Status.Ok, elapsedMs < 1000L)
    },
  ) @@ TestAspect.sequential
}
