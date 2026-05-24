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

import java.time.{LocalDate, ZoneOffset}

object RecentApexesApiSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

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

  private val kidsMac   = "aa:bb:cc:dd:ee:01"
  private val adultsMac = "aa:bb:cc:dd:ee:02"

  private def createUser(
      userRepo: UserRepo,
      upRepo: UserProfileRepo,
      auth: AuthService,
      username: String,
      role: String,
      profileIds: List[ProfileId],
  ): Task[UserId] =
    for {
      hash <- auth.hashPassword("pass")
      id   <- userRepo.create(username, hash, role)
      _    <- userRepo.clearMustChangePassword(id)
      _    <- upRepo.setProfilesForUser(id, profileIds)
    } yield id

  private def seedRouter: ZIO[RouterRepo, Throwable, RouterId] =
    ZIO.serviceWithZIO[RouterRepo] { rr =>
      for {
        id <- rr.create("test-router", Sha256Hex.unsafe("t" * 64))
        _  <- rr.completeEnrollment(id, Sha256Hex.unsafe("u" * 64))
      } yield id
    }

  /** Insert one FQDN-typed bucket worth `bytes` bytes_in. */
  private def insertFqdn(
      routerId: RouterId,
      mac: String,
      hostname: String,
      date: LocalDate,
      hour: Int,
      bytes: Long,
  ): ZIO[TrafficReportRepo, Throwable, Int] =
    ZIO.serviceWithZIO[TrafficReportRepo] { tr =>
      val start = date.atStartOfDay(ZoneOffset.UTC).toInstant.plusSeconds(hour * 3600L)
      val end   = start.plusSeconds(300)
      tr.insertBatch(
        List(
          TrafficReportInsert(
            routerId,
            MacAddress.unsafe(mac),
            None,
            HostId.Fqdn(Hostname.unsafe(hostname)),
            date,
            start,
            end,
            300,
            bytes,
            0L,
          ),
        ),
      )
    }

  /** Insert one bare-IP-typed bucket (no FQDN attribution). */
  private def insertBareIp(
      routerId: RouterId,
      mac: String,
      ipLiteral: String,
      date: LocalDate,
      hour: Int,
      bytes: Long,
  ): ZIO[TrafficReportRepo, Throwable, Int] =
    ZIO.serviceWithZIO[TrafficReportRepo] { tr =>
      val start = date.atStartOfDay(ZoneOffset.UTC).toInstant.plusSeconds(hour * 3600L)
      val end   = start.plusSeconds(300)
      tr.insertBatch(
        List(
          TrafficReportInsert(
            routerId,
            MacAddress.unsafe(mac),
            None,
            HostId.IPv4(IpAddress.parse(ipLiteral).toOption.get),
            date,
            start,
            end,
            300,
            bytes,
            0L,
          ),
        ),
      )
    }

  private def buildRoutes =
    for {
      deviceRepo      <- ZIO.service[DeviceRepo]
      trafficRepo     <- ZIO.service[TrafficReportRepo]
      userProfileRepo <- ZIO.service[UserProfileRepo]
      profileRepo     <- ZIO.service[ProfileRepo]
      appRepo         <- ZIO.service[AppRepo]
      clock           <- ZIO.service[Clock]
      auth            <- makeAuth
    } yield (
      UsageRoutes.routes(
        auth,
        deviceRepo,
        trafficRepo,
        userProfileRepo,
        profileRepo,
        appRepo,
        clock,
      ),
      auth,
    )

  def spec = suite("Recent apexes API (GET /api/devices/:mac/recent-apexes)")(
    test("groups FQDNs by PSL apex, sorted by bytes desc, excludes bare IPs") {
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        schedRepo   <- ZIO.service[ScheduleRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
        _           <- TestLayers.seedDevice(deviceRepo, kidsMac, "iPad", kidsId)
        routerId    <- seedRouter
        today = TestClock.schoolDayAfternoon.toLocalDate
        // youtube subdomains collapse to youtube.com (PSL).
        _  <- insertFqdn(routerId, kidsMac, "www.youtube.com", today, 10, 800L)
        _  <- insertFqdn(routerId, kidsMac, "m.youtube.com", today, 11, 200L)
        // googlevideo as the other apex, bigger overall — sorted first.
        _  <- insertFqdn(routerId, kidsMac, "r1.googlevideo.com", today, 12, 5000L)
        _  <- insertFqdn(routerId, kidsMac, "r2.googlevideo.com", today, 13, 3000L)
        // Bare IP — must be excluded.
        _  <- insertBareIp(routerId, kidsMac, "203.0.113.42", today, 14, 9999L)
        rb <- buildRoutes
        (routes, auth) = rb
        token <- auth.login("admin", "changeme").map(_.token.value)
        req = Request
          .get(URL.decode(s"/api/devices/$kidsMac/recent-apexes").toOption.get)
          .addHeader(Header.Authorization.Bearer(token))
        resp <- routes.runZIO(req)
        body <- resp.body.asString
        out  <- ZIO.fromEither(body.fromJson[RecentApexesResponse])
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(out.windowDays == 7) &&
        assertTrue(out.items.length == 2) &&
        assertTrue(out.items.head.apex.value == "googlevideo.com") &&
        assertTrue(out.items.head.bytes == 8000L) &&
        assertTrue(out.items.head.hits == 2) &&
        assertTrue(
          out.items.head.subdomains.map(_.value).toSet ==
            Set("r1.googlevideo.com", "r2.googlevideo.com"),
        ) &&
        assertTrue(out.items(1).apex.value == "youtube.com") &&
        assertTrue(out.items(1).bytes == 1000L) &&
        assertTrue(
          out.items(1).subdomains.map(_.value).toSet ==
            Set("www.youtube.com", "m.youtube.com"),
        ) &&
        // bare IP excluded entirely
        assertTrue(out.items.forall(i => !i.apex.value.contains("203.0.113")))
    },
    test("apex hit with no subdomain leaves subdomains empty") {
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        schedRepo   <- ZIO.service[ScheduleRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
        _           <- TestLayers.seedDevice(deviceRepo, kidsMac, "iPad", kidsId)
        routerId    <- seedRouter
        today = TestClock.schoolDayAfternoon.toLocalDate
        _  <- insertFqdn(routerId, kidsMac, "youtube.com", today, 10, 500L)
        rb <- buildRoutes
        (routes, auth) = rb
        token <- auth.login("admin", "changeme").map(_.token.value)
        req = Request
          .get(URL.decode(s"/api/devices/$kidsMac/recent-apexes").toOption.get)
          .addHeader(Header.Authorization.Bearer(token))
        resp <- routes.runZIO(req)
        body <- resp.body.asString
        out  <- ZIO.fromEither(body.fromJson[RecentApexesResponse])
      } yield assertTrue(out.items.length == 1) &&
        assertTrue(out.items.head.apex.value == "youtube.com") &&
        assertTrue(out.items.head.subdomains.isEmpty)
    },
    test("respects windowDays — older traffic excluded") {
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        schedRepo   <- ZIO.service[ScheduleRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
        _           <- TestLayers.seedDevice(deviceRepo, kidsMac, "iPad", kidsId)
        routerId    <- seedRouter
        today = TestClock.schoolDayAfternoon.toLocalDate
        _  <- insertFqdn(routerId, kidsMac, "fresh.example.com", today, 10, 1000L)
        // 30 days back — outside default 7d window.
        _  <- insertFqdn(routerId, kidsMac, "stale.example.com", today.minusDays(30), 10, 1000L)
        rb <- buildRoutes
        (routes, auth) = rb
        token <- auth.login("admin", "changeme").map(_.token.value)
        req = Request
          .get(URL.decode(s"/api/devices/$kidsMac/recent-apexes").toOption.get)
          .addHeader(Header.Authorization.Bearer(token))
        resp <- routes.runZIO(req)
        body <- resp.body.asString
        out  <- ZIO.fromEither(body.fromJson[RecentApexesResponse])
      } yield assertTrue(out.items.length == 1) &&
        assertTrue(out.items.head.subdomains.map(_.value) == List("fresh.example.com"))
    },
    test("returns 404 for unknown mac") {
      for {
        _  <- cleanDb
        rb <- buildRoutes
        (routes, auth) = rb
        token <- auth.login("admin", "changeme").map(_.token.value)
        req = Request
          .get(URL.decode("/api/devices/aa:bb:cc:dd:ee:ff/recent-apexes").toOption.get)
          .addHeader(Header.Authorization.Bearer(token))
        resp <- routes.runZIO(req)
      } yield assertTrue(resp.status == Status.NotFound)
    },
    test("child user cannot see device in a profile they are not linked to") {
      for {
        _           <- cleanDb
        userRepo    <- ZIO.service[UserRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        profileRepo <- ZIO.service[ProfileRepo]
        schedRepo   <- ZIO.service[ScheduleRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
        adultsId    <- TestLayers.seedAdultsProfile(profileRepo)
        // The child is linked to Kids only; the device lives on Adults.
        _           <- TestLayers.seedDevice(deviceRepo, adultsMac, "Dad's phone", adultsId)
        rb          <- buildRoutes
        (routes, auth) = rb
        _     <- createUser(userRepo, upRepo, auth, "alice", "child", List(kidsId))
        token <- auth.login("alice", "pass").map(_.token.value)
        req = Request
          .get(URL.decode(s"/api/devices/$adultsMac/recent-apexes").toOption.get)
          .addHeader(Header.Authorization.Bearer(token))
        resp <- routes.runZIO(req)
      } yield assertTrue(resp.status == Status.Forbidden)
    },
  ) @@ TestAspect.sequential
}
