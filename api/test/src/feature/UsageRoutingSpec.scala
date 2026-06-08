package wifihaven.api.feature

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.policy.PolicyService
import wifihaven.api.routes.UsageRoutes
import wifihaven.shared.*
import wifihaven.shared.Clock.TestClock
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*

import java.time.ZoneOffset

// #813/#1262: BUCKET-driven source-tier routing. The aggregated path of
// /api/usage/traffic picks the table from the requested bucket, never from the
// window — bucket and range are independent client choices:
//   raw / 1m / 10m → traffic_reports (5-min raw)
//   1h / 12h       → traffic_hourly
//   1d / 1w        → traffic_daily
//
// #1262: a wide window must NOT coarsen the caller's bucket. A fine bucket over
// a 30-day window is served from raw (bounded by keyset pagination), at the
// requested width, with no `X-Bucket-Promoted-From` promotion.
//
// These tests pre-populate the rollup tables (via the same `rerollHourly` /
// `rerollDaily` repo calls the scheduled fibers use) and then verify the
// endpoint reads from the tier the bucket selects.
object UsageRoutingSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val testMac = "aa:bb:cc:dd:ee:01"

  private val jwtCfg    = JwtConfig(secret = "test-secret-at-least-32-chars!!", expiryHours = 1)
  private def buildAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)

  private def buildRoutes =
    for {
      deviceRepo      <- ZIO.service[DeviceRepo]
      trafficRepo     <- ZIO.service[TrafficReportRepo]
      userProfileRepo <- ZIO.service[UserProfileRepo]
      profileRepo     <- ZIO.service[ProfileRepo]
      appRepo         <- ZIO.service[AppRepo]
      rollupRepo      <- ZIO.service[RollupRepo]
      hsRepo          <- ZIO.service[wifihaven.api.db.HouseholdSettingsRepo]
      atlRepo         <- ZIO.service[wifihaven.api.db.AppTimeLimitRepo]
      clock           <- ZIO.service[Clock]
      auth            <- buildAuth
    } yield (
      UsageRoutes.routes(
        auth,
        deviceRepo,
        trafficRepo,
        userProfileRepo,
        profileRepo,
        appRepo,
        rollupRepo,
        hsRepo,
        atlRepo,
        clock,
      ),
      auth,
    )

  private def seedRouter: RIO[RouterRepo, RouterId] =
    ZIO.serviceWithZIO[RouterRepo](_.create("test", PolicyService.hashToken("et_x")))

  // Seed `n` 5-min traffic_reports rows for the testMac starting at startUtc.
  private def seedRaw(
      routerId: RouterId,
      startUtc: java.time.Instant,
      n: Int,
  ): RIO[TrafficReportRepo, Unit] =
    ZIO.serviceWithZIO[TrafficReportRepo] { tr =>
      val inserts = (0 until n).map { i =>
        val s = startUtc.plusSeconds(i.toLong * 300L)
        val e = s.plusSeconds(300L)
        TrafficReportInsert(
          routerId,
          MacAddress.unsafe(testMac),
          None,
          HostId.Fqdn(Hostname.unsafe("example.com")),
          s.atZone(ZoneOffset.UTC).toLocalDate,
          s,
          e,
          300,
          100L,
          100L,
        )
      }.toList
      tr.insertBatch(inserts).unit
    }

  private def loginAdmin(auth: AuthService): Task[String] =
    auth
      .login("admin", "changeme")
      .mapError(e => new RuntimeException(e.toString))
      .map(_.token.value)

  def spec = suite("/api/usage/traffic bucket-driven source-tier routing (#813/#1262)")(
    test("bucket=1h reads traffic_hourly (rollup seeded → results > 0)") {
      val today = TestClock.schoolDayAfternoon.toLocalDate
      val from  = today.minusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant
      val to    = today.atStartOfDay(ZoneOffset.UTC).toInstant
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        schedRepo   <- ZIO.service[ScheduleRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
        _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
        routerId    <- seedRouter
        // Seed 6 hours of raw rows on day -3, then trigger the hourly roll.
        seedStart = today.minusDays(3).atStartOfDay(ZoneOffset.UTC).toInstant
        _      <- seedRaw(routerId, seedStart, 72)
        rollup <- ZIO.service[RollupRepo]
        _      <- rollup.rerollHourly(seedStart.minusSeconds(3600))
        rb     <- buildRoutes
        (routes, auth) = rb
        token <- loginAdmin(auth)
        // bucket=1h → tier=Hourly (independent of the 7-day window).
        url = URL
          .decode(
            s"/api/usage/traffic?mac=$testMac&from=$from&to=$to&bucket=1h&groupBy=domain",
          )
          .toOption
          .get
        resp <- routes.runZIO(Request.get(url).addHeader(Header.Authorization.Bearer(token)))
        body <- resp.body.asString
        out  <- ZIO.fromEither(body.fromJson[TrafficUsageResponse])
      } yield assertTrue(
        resp.status == Status.Ok,
        out.bucket == "1h",
        out.aggregateRows.nonEmpty,
        resp.headers.get("X-Bucket-Promoted-From").isEmpty,
      )
    },
    test("bucket=1d reads traffic_daily") {
      val today = TestClock.schoolDayAfternoon.toLocalDate
      val from  = today.minusDays(30).atStartOfDay(ZoneOffset.UTC).toInstant
      val to    = today.atStartOfDay(ZoneOffset.UTC).toInstant
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        schedRepo   <- ZIO.service[ScheduleRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
        _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
        routerId    <- seedRouter
        seedStart = today.minusDays(20).atStartOfDay(ZoneOffset.UTC).toInstant
        _      <- seedRaw(routerId, seedStart, 12)
        rollup <- ZIO.service[RollupRepo]
        _      <- rollup.rerollDaily(today.minusDays(40))
        rb     <- buildRoutes
        (routes, auth) = rb
        token <- loginAdmin(auth)
        url = URL
          .decode(
            s"/api/usage/traffic?mac=$testMac&from=$from&to=$to&bucket=1d",
          )
          .toOption
          .get
        resp <- routes.runZIO(Request.get(url).addHeader(Header.Authorization.Bearer(token)))
        body <- resp.body.asString
        out  <- ZIO.fromEither(body.fromJson[TrafficUsageResponse])
      } yield assertTrue(
        resp.status == Status.Ok,
        out.bucket == "1d",
        out.aggregateRows.nonEmpty,
      )
    },
    // #1262 regression: bucket and range are independent. A 10m bucket over a
    // 30-day window must stay 10m and be served from raw — NOT promoted to the
    // daily tier's 1d the way the old window-driven routing did. Seeds an hour
    // of raw rows inside the window so the served grain is observable: 10-min
    // buckets ⇒ several sub-hour rows. If the bucket were coarsened to 1d this
    // collapses to a single daily row read from the (empty) daily rollup.
    test("bucket=10m over a 30-day window stays 10m and reads raw (no promotion)") {
      val today = TestClock.schoolDayAfternoon.toLocalDate
      val from  = today.minusDays(30).atStartOfDay(ZoneOffset.UTC).toInstant
      val to    = today.atStartOfDay(ZoneOffset.UTC).toInstant
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        schedRepo   <- ZIO.service[ScheduleRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
        _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
        routerId    <- seedRouter
        // 1h of 5-min raw rows on day -5, inside the 30-day window.
        seedStart = today.minusDays(5).atStartOfDay(ZoneOffset.UTC).toInstant
        _  <- seedRaw(routerId, seedStart, 12)
        rb <- buildRoutes
        (routes, auth) = rb
        token <- loginAdmin(auth)
        url = URL
          .decode(
            s"/api/usage/traffic?mac=$testMac&from=$from&to=$to&bucket=10m&groupBy=domain",
          )
          .toOption
          .get
        resp <- routes.runZIO(Request.get(url).addHeader(Header.Authorization.Bearer(token)))
        body <- resp.body.asString
        out  <- ZIO.fromEither(body.fromJson[TrafficUsageResponse])
      } yield assertTrue(
        resp.status == Status.Ok,
        // Requested width is honored, never coarsened by the range.
        out.bucket == "10m",
        // 1h of data at 10m grain ⇒ multiple sub-hour windows (came from raw,
        // not a single daily-rollup row).
        out.aggregateRows.size >= 2,
        // No silent promotion.
        resp.headers.get("X-Bucket-Promoted-From").isEmpty,
      )
    },
  ) @@ TestAspect.sequential
}
