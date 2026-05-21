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

object UsageApiSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

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

  private val testMac = "aa:bb:cc:dd:ee:01"

  private def seedRouter: ZIO[RouterRepo, Throwable, RouterId] =
    ZIO.serviceWithZIO[RouterRepo] { rr =>
      for {
        id <- rr.create("test-router", Sha256Hex.unsafe("t" * 64))
        _  <- rr.completeEnrollment(id, Sha256Hex.unsafe("u" * 64))
      } yield id
    }

  /**
   * Insert one row into traffic_reports at (date, hour:minute UTC) for (mac, hostname). The full
   * bucket is `active_seconds = 300`, matching what the router emits in the wild.
   */
  private def insertRow(
      routerId: RouterId,
      mac: String,
      hostname: String,
      date: LocalDate,
      hour: Int,
      minute: Int,
  ): ZIO[TrafficReportRepo, Throwable, Int] =
    ZIO.serviceWithZIO[TrafficReportRepo] { tr =>
      val start = date.atStartOfDay(ZoneOffset.UTC).toInstant
        .plusSeconds(hour * 3600L + minute * 60L)
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
            0L,
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
      clock           <- ZIO.service[Clock]
      auth            <- makeAuth
    } yield (UsageRoutes.routes(auth, deviceRepo, trafficRepo, userProfileRepo, clock), auth)

  def spec = suite("Usage API")(
    suite("GET /api/usage/series")(
      test("requires mac param") {
        for {
          _              <- cleanDb
          rb             <- buildRoutes
          (routes, auth)  = rb
          token          <- auth.login("admin", "changeme").map(_.token.value)
          req             = Request
            .get(URL.decode("/api/usage/series").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp           <- routes.runZIO(req)
        } yield assertTrue(resp.status == Status.BadRequest)
      },
      test("returns 24 hourly buckets, top-N hosts, and empty long tail for empty day") {
        for {
          _              <- cleanDb
          profileRepo    <- ZIO.service[ProfileRepo]
          schedRepo      <- ZIO.service[ScheduleRepo]
          deviceRepo     <- ZIO.service[DeviceRepo]
          kidsId         <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _              <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          rb             <- buildRoutes
          (routes, auth)  = rb
          token          <- auth.login("admin", "changeme").map(_.token.value)
          today           = TestClock.schoolDayAfternoon.toLocalDate
          req             = Request
            .get(URL.decode(s"/api/usage/series?mac=$testMac&date=$today").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp           <- routes.runZIO(req)
          body           <- resp.body.asString
          out            <- ZIO.fromEither(body.fromJson[UsageSeriesResponse])
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(out.buckets.length == 24) &&
          assertTrue(out.buckets.forall(_.totalMins == 0)) &&
          assertTrue(out.topHosts.isEmpty)
      },
      test("buckets activity by UTC hour and proportionally allocates per host") {
        for {
          _              <- cleanDb
          profileRepo    <- ZIO.service[ProfileRepo]
          schedRepo      <- ZIO.service[ScheduleRepo]
          deviceRepo     <- ZIO.service[DeviceRepo]
          trafficRepo    <- ZIO.service[TrafficReportRepo]
          kidsId         <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _              <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId       <- seedRouter
          today           = TestClock.schoolDayAfternoon.toLocalDate
          // 14:00–14:05 UTC, two hosts in same 5-min bucket → 5 wall-clock mins
          // split 2.5min/2.5min between hosts (floored to 2m each + 1m other).
          _              <- insertRow(routerId, testMac, "youtube.com", today, 14, 0)
          _              <- insertRow(routerId, testMac, "google.com", today, 14, 0)
          // 14:05–14:10 UTC, youtube.com alone → 5m to youtube
          _              <- insertRow(routerId, testMac, "youtube.com", today, 14, 5)
          // 03:00 UTC, drop.com alone → 5m to drop in hour 3
          _              <- insertRow(routerId, testMac, "drop.com", today, 3, 0)
          _              <- ZIO.service[TrafficReportRepo].as(trafficRepo)
          rb             <- buildRoutes
          (routes, auth)  = rb
          token          <- auth.login("admin", "changeme").map(_.token.value)
          req             = Request
            .get(URL.decode(s"/api/usage/series?mac=$testMac&date=$today").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp           <- routes.runZIO(req)
          body           <- resp.body.asString
          out            <- ZIO.fromEither(body.fromJson[UsageSeriesResponse])
          h14             = out.buckets(14)
          h3              = out.buckets(3)
          h0              = out.buckets(0)
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(out.buckets.length == 24) &&
          assertTrue(h14.totalMins == 10) && // two 5-min buckets in hour 14
          assertTrue(h3.totalMins == 5) &&
          assertTrue(h0.totalMins == 0) &&
          // youtube wins hour 14: 2.5min from shared bucket + 5min solo = 7.5
          assertTrue(h14.perHost.headOption.exists(_.host.value == "youtube.com")) &&
          assertTrue(h14.perHost.exists(p => p.host.value == "google.com"))
      },
      test("topN collapses long-tail into otherMins") {
        for {
          _              <- cleanDb
          profileRepo    <- ZIO.service[ProfileRepo]
          schedRepo      <- ZIO.service[ScheduleRepo]
          deviceRepo     <- ZIO.service[DeviceRepo]
          kidsId         <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _              <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId       <- seedRouter
          today           = TestClock.schoolDayAfternoon.toLocalDate
          // 7 distinct hosts each in their own 5-min bucket. topN=2 must
          // keep two named, fold the other 5 into otherMins.
          _              <- ZIO.foreachDiscard(0 until 7) { i =>
            insertRow(routerId, testMac, s"host$i.com", today, 10, i * 5)
          }
          rb             <- buildRoutes
          (routes, auth)  = rb
          token          <- auth.login("admin", "changeme").map(_.token.value)
          req             = Request
            .get(URL.decode(s"/api/usage/series?mac=$testMac&date=$today&topN=2").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp           <- routes.runZIO(req)
          body           <- resp.body.asString
          out            <- ZIO.fromEither(body.fromJson[UsageSeriesResponse])
          h10             = out.buckets(10)
        } yield assertTrue(out.topHosts.length == 2) &&
          assertTrue(h10.totalMins == 35) &&
          assertTrue(h10.perHost.length == 2) &&
          assertTrue(h10.otherMins == 25) // 5 hosts × 5 min
      },
      test("tz parameter buckets by local-hour") {
        for {
          _              <- cleanDb
          profileRepo    <- ZIO.service[ProfileRepo]
          schedRepo      <- ZIO.service[ScheduleRepo]
          deviceRepo     <- ZIO.service[DeviceRepo]
          kidsId         <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _              <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId       <- seedRouter
          today           = TestClock.schoolDayAfternoon.toLocalDate
          // 06:00 UTC on a Jan day == 22:00 previous day in PST (UTC-8). When
          // requested with tz=America/Los_Angeles for `today`, this row
          // belongs to the *previous* local day and should not appear.
          _              <- insertRow(routerId, testMac, "youtube.com", today, 6, 0)
          // 20:00 UTC == 12:00 PST on `today` → hour 12 in PST bucket layout.
          _              <- insertRow(routerId, testMac, "google.com", today, 20, 0)
          rb             <- buildRoutes
          (routes, auth)  = rb
          token          <- auth.login("admin", "changeme").map(_.token.value)
          req             = Request
            .get(
              URL
                .decode(
                  s"/api/usage/series?mac=$testMac&date=$today&tz=America/Los_Angeles",
                )
                .toOption
                .get,
            )
            .addHeader(Header.Authorization.Bearer(token))
          resp           <- routes.runZIO(req)
          body           <- resp.body.asString
          out            <- ZIO.fromEither(body.fromJson[UsageSeriesResponse])
        } yield assertTrue(out.tz == "America/Los_Angeles") &&
          // The 06:00 UTC sample falls into the previous local day → excluded.
          assertTrue(out.buckets.iterator.map(_.totalMins).sum == 5) &&
          assertTrue(out.buckets(12).totalMins == 5)
      },
      test("rejects unknown mac with 404") {
        for {
          _              <- cleanDb
          rb             <- buildRoutes
          (routes, auth)  = rb
          token          <- auth.login("admin", "changeme").map(_.token.value)
          req             = Request
            .get(URL.decode("/api/usage/series?mac=aa:bb:cc:dd:ee:ff").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp           <- routes.runZIO(req)
        } yield assertTrue(resp.status == Status.NotFound)
      },
    ) @@ TestAspect.sequential,
  ) @@ TestAspect.sequential
}
