package wifihaven.api.feature

import doobie.*
import doobie.implicits.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.routes.UsageRoutes
import wifihaven.shared.*
import wifihaven.shared.Clock.TestClock
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*

import java.time.{Duration, LocalDate, ZoneId, ZoneOffset}

/**
 * #1099 — performance regression coverage for the per-profile usage reads that power the /profiles
 * page.
 *
 * (a) The partition-pruned `listPresenceRowsInWindow` returns exactly the rows the old three-call /
 * zone-filter path returned (no accuracy regression). (b) The batched `/api/usage/series/batch`
 * endpoint returns, for each profile, exactly what the single-profile
 * `/api/usage/series?profileId=` endpoint returns. (c) A query that blows its statement-timeout
 * surfaces a typed [[QueryTimeoutException]] rather than hanging and holding a connection.
 */
object UsageSeriesPerfSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val jwtCfg   = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)
  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)

  private def seedRouter: ZIO[RouterRepo, Throwable, RouterId] =
    ZIO.serviceWithZIO[RouterRepo] { rr =>
      for {
        id <- rr.create("test-router", Sha256Hex.unsafe("t" * 64))
        _  <- rr.completeEnrollment(id, Sha256Hex.unsafe("u" * 64))
      } yield id
    }

  // Insert one 5-min traffic_reports row at the given UTC instant. The stored
  // `date` column is the row's UTC calendar date, exactly as the ingest path
  // writes it — so the window query (period_start) and the legacy day query
  // (date) must agree.
  private def insertAt(
      routerId: RouterId,
      mac: String,
      hostname: String,
      startUtc: java.time.Instant,
  ): ZIO[TrafficReportRepo, Throwable, Int] =
    ZIO.serviceWithZIO[TrafficReportRepo] { tr =>
      tr.insertBatch(
        List(
          TrafficReportInsert(
            routerId,
            MacAddress.unsafe(mac),
            None,
            HostId.Fqdn(Hostname.unsafe(hostname)),
            startUtc.atZone(ZoneOffset.UTC).toLocalDate,
            startUtc,
            startUtc.plusSeconds(300),
            300,
            // #1492: above the 10 KB heartbeat threshold so the presence-based series counts
            // these as real activity (the default filter drops sub-10 KB buckets).
            25_000L,
            25_000L,
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
      rollupRepo      <- ZIO.service[RollupRepo]
      hsRepo          <- ZIO.service[wifihaven.api.db.HouseholdSettingsRepo]
      atlRepo         <- ZIO.service[wifihaven.api.db.AppTimeLimitRepo]
      aruRepo         <- ZIO.service[wifihaven.api.db.AppUsedRollupRepo]
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
        rollupRepo,
        hsRepo,
        atlRepo,
        aruRepo,
        clock,
      ),
      auth,
    )

  def spec = suite("UsageSeriesPerf (#1099)")(
    test("(a) listPresenceRowsInWindow returns the same rows as the legacy day/zone path") {
      // America/New_York is UTC-4 in May, so the local day [00:00, 24:00) maps
      // to UTC [04:00 today, 04:00 tomorrow). Seed rows on both sides of every
      // boundary so the equivalence is meaningful, not vacuous.
      val zone = ZoneId.of("America/New_York")
      val mac  = "aa:bb:cc:dd:ee:0a"
      val date = LocalDate.of(2025, 5, 12)
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        trafficRepo <- ZIO.service[TrafficReportRepo]
        kidsId      <- TestLayers.seedKidsProfile(profileRepo)
        _           <- TestLayers.seedDevice(deviceRepo, mac, "iPad", kidsId)
        routerId    <- seedRouter
        localMid = date.atStartOfDay(zone).toInstant
        nextMid  = date.plusDays(1).atStartOfDay(zone).toInstant
        // Inside the local day (one just after local midnight, one near the end).
        _    <- insertAt(routerId, mac, "youtube.com", localMid.plusSeconds(60))
        _    <- insertAt(routerId, mac, "google.com", nextMid.minusSeconds(600))
        // Outside the local day on both ends (must be excluded by both paths).
        _    <- insertAt(routerId, mac, "before.com", localMid.minusSeconds(600))
        _    <- insertAt(routerId, mac, "after.com", nextMid.plusSeconds(60))
        // Legacy three-call + zone filter (what fetchPresenceDayWindow used to do).
        prev <- trafficRepo.listPresenceRows(List(MacAddress.unsafe(mac)), date.minusDays(1))
        cur  <- trafficRepo.listPresenceRows(List(MacAddress.unsafe(mac)), date)
        nxt  <- trafficRepo.listPresenceRows(List(MacAddress.unsafe(mac)), date.plusDays(1))
        legacy = (prev ++ cur ++ nxt).filter(_.periodStart.atZone(zone).toLocalDate == date)
        windowed <- trafficRepo.listPresenceRowsInWindow(
          List(MacAddress.unsafe(mac)),
          localMid,
          nextMid,
        )
      } yield assertTrue(
        windowed.toSet == legacy.toSet,
        windowed.map(_.host.value).toSet == Set("youtube.com", "google.com"),
        windowed.size == 2,
      )
    },
    test("(b) batched /usage/series/batch matches the single-profile endpoint per profile") {
      val macA  = "aa:bb:cc:dd:ee:0a"
      val macB  = "aa:bb:cc:dd:ee:0b"
      val today = TestClock.schoolDayAfternoon.toLocalDate
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        kidsId      <- TestLayers.seedKidsProfile(profileRepo)
        adultsId    <- TestLayers.seedAdultsProfile(profileRepo)
        _           <- TestLayers.seedDevice(deviceRepo, macA, "iPad-A", kidsId)
        _           <- TestLayers.seedDevice(deviceRepo, macB, "iPad-B", adultsId)
        routerId    <- seedRouter
        base = today.atStartOfDay(ZoneOffset.UTC).toInstant.plusSeconds(14 * 3600L)
        _  <- insertAt(routerId, macA, "youtube.com", base)
        _  <- insertAt(routerId, macA, "youtube.com", base.plusSeconds(300))
        _  <- insertAt(routerId, macB, "google.com", base.plusSeconds(600))
        rb <- buildRoutes
        (routes, auth) = rb
        token <- auth.login("admin", "changeme").map(_.token.value)
        get = (url: String) =>
          routes
            .runZIO(
              Request
                .get(URL.decode(url).toOption.get)
                .addHeader(Header.Authorization.Bearer(token)),
            )
            .flatMap(_.body.asString)
        singleA   <- get(s"/api/usage/series?profileId=${kidsId.value}&date=$today")
          .flatMap(b => ZIO.fromEither(b.fromJson[UsageSeriesResponse]))
        singleB   <- get(s"/api/usage/series?profileId=${adultsId.value}&date=$today")
          .flatMap(b => ZIO.fromEither(b.fromJson[UsageSeriesResponse]))
        batchBody <- get(
          s"/api/usage/series/batch?profileId=${kidsId.value},${adultsId.value}&date=$today",
        )
        batch     <- ZIO.fromEither(batchBody.fromJson[UsageSeriesBatchResponse])
        byPid = batch.series.flatMap(s => s.profileId.map(_ -> s)).toMap
      } yield assertTrue(
        batch.series.size == 2,
        byPid.get(kidsId).contains(singleA),
        byPid.get(adultsId).contains(singleB),
        singleA.topHosts.map(_.host.value).contains("youtube.com"),
      )
    },
    test("(c) a query past its statement_timeout surfaces a typed QueryTimeoutException") {
      for {
        xa  <- ZIO.service[Transactor[Task]]
        res <- QueryTimeout
          .bounded(xa, Duration.ofMillis(1))(sql"SELECT 1 FROM pg_sleep(1)".query[Int].unique)
          .exit
      } yield assert(res)(
        Assertion.fails(Assertion.isSubtype[QueryTimeoutException](Assertion.anything)),
      )
    },
  ) @@ TestAspect.sequential
}
