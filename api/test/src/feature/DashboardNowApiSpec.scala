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

import java.time.{Instant, ZoneOffset}

object DashboardNowApiSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg   = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)
  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)
  private val cleanDb  = TestDatabase.cleanAndMigrate

  private def seedRouter(name: String = "home"): ZIO[RouterRepo, Throwable, RouterId] =
    ZIO.serviceWithZIO[RouterRepo] { rRepo =>
      for {
        id <- rRepo.create(name, Sha256Hex.unsafe("a" * 64))
        _  <- rRepo.completeEnrollment(id, Sha256Hex.unsafe("b" * 64))
      } yield id
    }

  private def insertReport(
      routerId: RouterId,
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
          TrafficReportInsert(
            routerId,
            MacAddress.unsafe(mac),
            None,
            HostId.Fqdn(Hostname.unsafe(host)),
            date,
            start,
            end,
            activeSeconds,
            1,
            1,
          ),
        ),
      ).unit
    }

  private def insertConn(
      routerId: RouterId,
      mac: String,
      ts: Instant,
  ): ZIO[ConnectionEventRepo, Throwable, Unit] =
    ZIO.serviceWithZIO[ConnectionEventRepo] { cr =>
      cr.insertBatch(
        List(
          ConnectionEventInsert(
            routerId,
            Some(MacAddress.unsafe(mac)),
            HostId.Fqdn(Hostname.unsafe("example.com")),
            None,
            true,
            BlockReason.fromWire("allow"),
            ts,
          ),
        ),
      ).unit
    }

  private def getJson(routes: Routes[Any, Response], path: String, token: JwtToken) =
    routes.runZIO(
      Request
        .get(URL.decode(path).toOption.get)
        .addHeader(Header.Authorization.Bearer(token.value)),
    )

  private def buildRoutes(auth: AuthService) =
    for {
      tr     <- ZIO.service[TrafficReportRepo]
      cr     <- ZIO.service[ConnectionEventRepo]
      dr     <- ZIO.service[DeviceRepo]
      pr     <- ZIO.service[ProfileRepo]
      upRepo <- ZIO.service[UserProfileRepo]
      atl    <- ZIO.service[AppTimeLimitRepo]
      clock  <- ZIO.service[Clock]
    } yield DashboardNowRoutes.routes(auth, tr, cr, dr, pr, upRepo, atl, clock)

  /**
   * V1__init seeds two profiles ("Kids" and "Adults"). Tests that need a clean slate clear them so
   * profile-id assertions are stable.
   */
  private def clearSeededProfiles: ZIO[ProfileRepo, Throwable, Unit] =
    ZIO.serviceWithZIO[ProfileRepo](pr =>
      pr.listAll.flatMap(ps => ZIO.foreachDiscard(ps)(p => pr.delete(p.id))),
    )

  private val mac1  = "aa:bb:cc:dd:ee:01"
  private val mac2  = "aa:bb:cc:dd:ee:02"
  private val mac1T = MacAddress.unsafe(mac1)

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
    test("active device: top host populated") {
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
        dev.mac == mac1T,
        dev.name == "iPad",
        dev.lastSeenSeconds <= 60L,
        dev.topHosts.headOption.exists(_.host == HostId.Fqdn(Hostname.unsafe("youtube.com"))),
        dev.topHosts.head.activeSeconds == 1800L,
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
        dev.mac == mac1T,
        dev.lastSeenSeconds <= 90L,
      )
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
        dev.topHosts.map(_.host) == List(
          HostId.Fqdn(Hostname.unsafe("youtube.com")),
          HostId.Fqdn(Hostname.unsafe("tiktok.com")),
          HostId.Fqdn(Hostname.unsafe("reddit.com")),
        ),
        dev.topHosts.head.activeSeconds == 360L,
      )
    },
    test("#1503 background infra hosts are excluded from the now-widget's top hosts") {
      // The "watching X right now" widget must not surface device-level OS/telemetry infra as the
      // active host. It routes the host ranking through Presence.isHeartbeat, which (post-#1503)
      // drops the unified InfraHosts set on identity — so the dominant gvt2/ls.apple/OCSP chatter
      // is excluded and only the genuine app remains, even though infra has more active_seconds.
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
        _      <- insertReport(routerId, mac1, "beacons3.gvt2.com", t0, activeSeconds = 300)
        _      <- insertReport(routerId, mac1, "gsp-ssl.ls.apple.com", t0, activeSeconds = 300)
        _      <- insertReport(routerId, mac1, "ocsp.digicert.com", t0, activeSeconds = 300)
        _      <- insertReport(routerId, mac1, "youtube.com", t0, activeSeconds = 120)
        _      <- insertConn(routerId, mac1, now0.minusSeconds(15))
        auth   <- makeAuth
        token  <- auth.login("admin", "changeme").map(_.token)
        routes <- buildRoutes(auth)
        resp   <- getJson(routes, "/api/dashboard/now", token)
        body   <- resp.body.asString
        parsed <- ZIO.fromEither(body.fromJson[DashboardNow])
        dev = parsed.profiles.find(_.id == kid).get.activeDevices.head
      } yield assertTrue(
        dev.topHosts.map(_.host) == List(HostId.Fqdn(Hostname.unsafe("youtube.com"))),
        dev.nowActivity.exists(_.topHost == HostId.Fqdn(Hostname.unsafe("youtube.com"))),
      )
    },
    test(
      "#1559 a background-pattern host attributed to an active app is kept in the now-widget ranking",
    ) {
      // Attribution beats suppression on the ranking path (#1559), the same way #1506 made it
      // beat suppression on the counting paths. An off-domain asset/CDN host an app genuinely
      // depends on that also happens to match the unified InfraHosts device-infra list must stay
      // in topHosts / nowActivity instead of being silently dropped. A device-infra host with no
      // app behind it (ocsp.digicert.com here) stays dropped.
      //
      // The app's host-set is built via app_policy_assignments + app_hosts, the same
      // attribution data `Presence.appHostPatterns` reads — i.e. routed through the single
      // canonical predicate, not a parallel one (#1532 / #1560).
      for {
        _        <- cleanDb
        _        <- clearSeededProfiles
        routerId <- seedRouter()
        dr       <- ZIO.service[DeviceRepo]
        pr       <- ZIO.service[ProfileRepo]
        ar       <- ZIO.service[AppRepo]
        kid      <- pr.create("Kids", Nil)
        _        <- TestLayers.seedDevice(dr, mac1, "iPad", kid)
        // Synthetic time-limited app whose host-set claims `gvt2.com` — the apex sits on
        // InfraHosts.canonical, so any subdomain (`beacons3.gvt2.com`) would otherwise be
        // dropped as background. With the #1559 app-aware predicate it is attributed to the
        // app and kept.
        _        <- TestLayers.seedAppAssignment(
          ar,
          kid,
          host = "gvt2.com",
          mode = AppMode.TimeLimited,
          dailyMinutes = Some(60),
          exemptFromDaily = false,
        )
        now0     <- Clock.instant
        t0 = now0.minusSeconds(10 * 60)
        // App-attributed background host — stays in ranking after the fix.
        _      <- insertReport(routerId, mac1, "beacons3.gvt2.com", t0, activeSeconds = 300)
        // Device infra with no app behind it — still dropped.
        _      <- insertReport(routerId, mac1, "ocsp.digicert.com", t0, activeSeconds = 300)
        _      <- insertConn(routerId, mac1, now0.minusSeconds(15))
        auth   <- makeAuth
        token  <- auth.login("admin", "changeme").map(_.token)
        routes <- buildRoutes(auth)
        resp   <- getJson(routes, "/api/dashboard/now", token)
        body   <- resp.body.asString
        parsed <- ZIO.fromEither(body.fromJson[DashboardNow])
        dev = parsed.profiles.find(_.id == kid).get.activeDevices.head
      } yield assertTrue(
        dev.topHosts.map(_.host) == List(HostId.Fqdn(Hostname.unsafe("beacons3.gvt2.com"))),
        dev.nowActivity.exists(_.topHost == HostId.Fqdn(Hostname.unsafe("beacons3.gvt2.com"))),
      )
    },
    test("nowActivity: consistent top host across 3 buckets → topHost + accurate minutes") {
      for {
        _        <- cleanDb
        _        <- clearSeededProfiles
        routerId <- seedRouter()
        dr       <- ZIO.service[DeviceRepo]
        pr       <- ZIO.service[ProfileRepo]
        kid      <- pr.create("Kids", Nil)
        _        <- TestLayers.seedDevice(dr, mac1, "iPad", kid)
        now0     <- Clock.instant
        // Three 5-min buckets ending at: now-1m, now-6m, now-11m, all top youtube.com.
        _        <- insertReport(
          routerId,
          mac1,
          "youtube.com",
          now0.minusSeconds(16 * 60),
          activeSeconds = 250,
        )
        _        <- insertReport(
          routerId,
          mac1,
          "youtube.com",
          now0.minusSeconds(11 * 60),
          activeSeconds = 280,
        )
        _        <- insertReport(
          routerId,
          mac1,
          "youtube.com",
          now0.minusSeconds(6 * 60),
          activeSeconds = 290,
        )
        _        <- insertConn(routerId, mac1, now0.minusSeconds(5))
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token)
        routes   <- buildRoutes(auth)
        resp     <- getJson(routes, "/api/dashboard/now", token)
        body     <- resp.body.asString
        parsed   <- ZIO.fromEither(body.fromJson[DashboardNow])
        dev = parsed.profiles.find(_.id == kid).get.activeDevices.head
      } yield assertTrue(
        dev.nowActivity.exists(_.topHost == HostId.Fqdn(Hostname.unsafe("youtube.com"))),
        dev.nowActivity.flatMap(_.minutes).contains(15),
      )
    },
    test("nowActivity: top host varies bucket-to-bucket → latest bucket's top, no minutes") {
      for {
        _        <- cleanDb
        _        <- clearSeededProfiles
        routerId <- seedRouter()
        dr       <- ZIO.service[DeviceRepo]
        pr       <- ZIO.service[ProfileRepo]
        kid      <- pr.create("Kids", Nil)
        _        <- TestLayers.seedDevice(dr, mac1, "iPad", kid)
        now0     <- Clock.instant
        // Latest bucket dominated by netflix; the earlier one by tiktok — should not extend the run.
        _        <- insertReport(
          routerId,
          mac1,
          "tiktok.com",
          now0.minusSeconds(11 * 60),
          activeSeconds = 290,
        )
        _        <- insertReport(
          routerId,
          mac1,
          "netflix.com",
          now0.minusSeconds(6 * 60),
          activeSeconds = 280,
        )
        _        <- insertReport(
          routerId,
          mac1,
          "tiktok.com",
          now0.minusSeconds(6 * 60),
          activeSeconds = 10,
        )
        _        <- insertConn(routerId, mac1, now0.minusSeconds(5))
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token)
        routes   <- buildRoutes(auth)
        resp     <- getJson(routes, "/api/dashboard/now", token)
        body     <- resp.body.asString
        parsed   <- ZIO.fromEither(body.fromJson[DashboardNow])
        dev = parsed.profiles.find(_.id == kid).get.activeDevices.head
      } yield assertTrue(
        dev.nowActivity.exists(_.topHost == HostId.Fqdn(Hostname.unsafe("netflix.com"))),
        dev.nowActivity.flatMap(_.minutes).isEmpty,
      )
    },
    test("nowActivity: idle device (no rows) → None") {
      for {
        _        <- cleanDb
        _        <- clearSeededProfiles
        routerId <- seedRouter()
        dr       <- ZIO.service[DeviceRepo]
        pr       <- ZIO.service[ProfileRepo]
        kid      <- pr.create("Kids", Nil)
        _        <- TestLayers.seedDevice(dr, mac1, "iPad", kid)
        now0     <- Clock.instant
        // Active via connection_events only — no traffic rows, so no nowActivity.
        _        <- insertConn(routerId, mac1, now0.minusSeconds(5))
        auth     <- makeAuth
        token    <- auth.login("admin", "changeme").map(_.token)
        routes   <- buildRoutes(auth)
        resp     <- getJson(routes, "/api/dashboard/now", token)
        body     <- resp.body.asString
        parsed   <- ZIO.fromEither(body.fromJson[DashboardNow])
        dev = parsed.profiles.find(_.id == kid).get.activeDevices.head
      } yield assertTrue(dev.nowActivity.isEmpty)
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
        _        <- userRepo.clearMustChangePassword(childId)
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
        parsed.profiles.head.activeDevices.head.mac == mac1T,
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
