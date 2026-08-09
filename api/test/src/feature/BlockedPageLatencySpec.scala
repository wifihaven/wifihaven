package wifihaven.api.feature

import wifihaven.api.auth.RateLimiter
import wifihaven.api.db.*
import wifihaven.api.policy.*
import wifihaven.api.routes.*
import wifihaven.api.usage.AppUsedRollupServiceLive
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.test.*

import java.time.{Instant, LocalDate, LocalDateTime, ZoneOffset}

/**
 * #2652: `GET /api/blocked` took 12-16s in prod — the child-facing block page. Measured on prod
 * 2026-08-08 against three profiles in the same household, on the same day's data:
 *
 * {{{
 *   profile        traffic_reports rows (today)   GET /api/blocked
 *   Octavius                                  0            0.35 s
 *   Kids                                    371       0.6 - 0.9 s
 *   Family                              136 199         8 - 10 s
 *   Sameer                              191 188        14 - 17 s
 * }}}
 *
 * The latency is a linear function of the profile's whole-day presence-row count, which is the
 * signature of a full-day `traffic_reports` scan on the request path — twice. This spec pins the
 * two structural facts behind that, so neither can come back:
 *
 *   1. '''One''' day-state computation per request. `BlockedRoutes.resolve` used to call
 *      `policy.decide` (which resolves the device, loads the profile, reads the household settings
 *      and computes the profile's `ProfileDayState` internally) and then re-read all four of those
 *      itself — so every block-page load paid for the expensive part twice. The route now consumes
 *      what `decide` already computed ([[PolicyService.decideDetailed]]), and no longer holds the
 *      repos it would need to re-read them. 2. A profile with '''no app assignments''' performs
 *      '''no''' presence read in [[AppUsedRollupServiceLive]]. That service fetches a whole day of
 *      `traffic_reports` to aggregate per-app engaged seconds, and falls back to the WHOLE DAY
 *      (rather than the tail past the rollup watermark) whenever `app_used_daily` has no rows for
 *      the profile — which is exactly the case for a profile with no app assignments, where the
 *      aggregation is provably empty. Prod profiles 2/3/4 (the three slow ones above) have zero
 *      rows in `app_policy_assignments`; they were each scanning 191k / 136k / 8k rows per call to
 *      build an empty map.
 */
object BlockedPageLatencySpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  /**
   * Counting decorator over the REAL repo (never a mock — testing.md): increments `dayLoads` on
   * every whole-day presence load (the `listPresenceRows(household, macs, date)` variant — the
   * expensive scan this issue is made of) and delegates everything else verbatim. Same shape as
   * `SpaWsS6aSpec.CountingTrafficRepo` (#2167).
   */
  private final class CountingTrafficRepo(underlying: TrafficReportRepo, dayLoads: Ref[Int])
      extends TrafficReportRepo {
    def insertBatch(reports: List[TrafficReportInsert]) = underlying.insertBatch(reports)
    def listForDevice(household: HouseholdId, mac: MacAddress, date: LocalDate) =
      underlying.listForDevice(household, mac, date)
    def listForRouter(routerId: RouterId, limit: Int) = underlying.listForRouter(routerId, limit)
    def listTrafficRollupRows(household: HouseholdId, f: TrafficRollupFilter)             =
      underlying.listTrafficRollupRows(household, f)
    def listPresenceRows(household: HouseholdId, macs: List[MacAddress], date: LocalDate) =
      dayLoads.update(_ + 1) *> underlying.listPresenceRows(household, macs, date)
    def listPresenceRows(
        household: HouseholdId,
        macs: List[MacAddress],
        from: LocalDate,
        to: LocalDate,
    ) = underlying.listPresenceRows(household, macs, from, to)
    def listPresenceRowsSince(
        household: HouseholdId,
        macs: List[MacAddress],
        date: LocalDate,
        since: Instant,
    ) = underlying.listPresenceRowsSince(household, macs, date, since)
    def listPresenceRowsInWindow(
        household: HouseholdId,
        macs: List[MacAddress],
        fromInstant: Instant,
        toInstant: Instant,
    ) = underlying.listPresenceRowsInWindow(household, macs, fromInstant, toInstant)
    def listRawInRange(
        household: HouseholdId,
        macs: List[MacAddress],
        fromInstant: Instant,
        toInstant: Instant,
        cursor: Option[wifihaven.api.usage.RawTrafficCursorKey],
        limit: Option[Int],
    ) = underlying.listRawInRange(household, macs, fromInstant, toInstant, cursor, limit)
    def listRawAggregatedInRange(
        household: HouseholdId,
        macs: List[MacAddress],
        fromInstant: Instant,
        toInstant: Instant,
        stepSeconds: Long,
    ) = underlying.listRawAggregatedInRange(household, macs, fromInstant, toInstant, stepSeconds)
    def earliestPeriodStart = underlying.earliestPeriodStart
    def listFqdnHostAggregatesForDevice(
        household: HouseholdId,
        mac: MacAddress,
        fromInstant: Instant,
        toInstant: Instant,
    ) = underlying.listFqdnHostAggregatesForDevice(household, mac, fromInstant, toInstant)
  }

  private def seedRouter: ZIO[RouterRepo, Throwable, RouterId] =
    ZIO.serviceWithZIO[RouterRepo] { rr =>
      for {
        id <- rr.create("test-router", Sha256Hex.unsafe("t" * 64))
        _  <- rr.completeEnrollment(id, Sha256Hex.unsafe("u" * 64))
      } yield id
    }

  private def seedTraffic(
      tr: TrafficReportRepo,
      routerId: RouterId,
      mac: String,
      hostname: String,
      date: LocalDate,
      minutes: Int,
  ): Task[Unit] = {
    val buckets = minutes / 5
    val today0  = date.atStartOfDay(ZoneOffset.UTC).toInstant
    val inserts = (0 until buckets).map { i =>
      val start = today0.plusSeconds(i * 300L)
      TrafficReportInsert(
        routerId,
        MacAddress.unsafe(mac),
        None,
        HostId.Fqdn(Hostname.unsafe(hostname)),
        date,
        start,
        start.plusSeconds(300),
        300,
        500_000L,
        500_000L,
      )
    }.toList
    tr.insertBatch(inserts).unit
  }

  private def makePsAt(
      dt: LocalDateTime,
      trafficRepo: TrafficReportRepo,
  ): ZIO[TestDatabase.AllRepos, Throwable, (PolicyService, Clock)] =
    for {
      pr   <- ZIO.service[ProfileRepo]
      hsr  <- ZIO.service[HouseholdSettingsRepo]
      tlr  <- ZIO.service[TimeLimitRepo]
      atlr <- ZIO.service[AppTimeLimitRepo]
      dr   <- ZIO.service[DeviceRepo]
      blr  <- ZIO.service[BlocklistRepo]
      er   <- ZIO.service[TimeExtensionRepo]
      ar   <- ZIO.service[AppRepo]
      nsr  <- ZIO.service[NamedScheduleRepo]
      ref  <- Ref.make(dt)
      clk = new Clock.TestClock(ref)
    } yield (
      PolicyServiceLive(
        pr,
        hsr,
        tlr,
        atlr,
        dr,
        blr,
        trafficRepo,
        er,
        ar,
        clk,
        namedScheduleRepo = nsr,
      ): PolicyService,
      clk: Clock,
    )

  def spec = suite("#2652 block-page latency")(
    test("GET /api/blocked performs exactly ONE whole-day presence load") {
      for {
        _        <- cleanDb
        pr       <- ZIO.service[ProfileRepo]
        dr       <- ZIO.service[DeviceRepo]
        blr      <- ZIO.service[BlocklistRepo]
        realTr   <- ZIO.service[TrafficReportRepo]
        dayLoads <- Ref.make(0)
        countingTr = new CountingTrafficRepo(realTr, dayLoads)
        routerId <- seedRouter
        kid      <- TestLayers.seedKidsProfile(pr)
        _        <- pr.setPaused(kid, true)
        _        <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        _        <- seedTraffic(
          countingTr,
          routerId,
          "aa:bb:cc:11:22:33",
          "example.com",
          TestClock.schoolDayAfternoon.toLocalDate,
          60,
        )
        psClk    <- makePsAt(TestClock.schoolDayAfternoon, countingTr)
        (ps, clk) = psClk
        tlr  <- ZIO.service[TimeLimitRepo]
        atlr <- ZIO.service[AppTimeLimitRepo]
        er   <- ZIO.service[TimeExtensionRepo]
        hsr  <- ZIO.service[HouseholdSettingsRepo]
        tss    = new TimeStatusServiceLive(pr, tlr, atlr, dr, countingTr, er): TimeStatusService
        routes = BlockedRoutes.routes(
          ps,
          dr,
          pr,
          blr,
          tss,
          hsr,
          clk,
          BlockPageHousehold.defaultOnly,
          RateLimiter.allowAll,
          RateLimiter.allowAll,
        )
        _     <- dayLoads.set(0)
        _     <- routes.runZIO(
          Request.get(
            URL.decode("/api/blocked?mac=aa:bb:cc:11:22:33&host=example.com").toOption.get,
          ),
        )
        loads <- dayLoads.get
      } yield assertTrue(loads == 1)
    },
    test("a profile with no app assignments performs NO presence load for per-app minutes") {
      for {
        _        <- cleanDb
        pr       <- ZIO.service[ProfileRepo]
        dr       <- ZIO.service[DeviceRepo]
        atlr     <- ZIO.service[AppTimeLimitRepo]
        realTr   <- ZIO.service[TrafficReportRepo]
        aur      <- ZIO.service[AppUsedRollupRepo]
        hsr      <- ZIO.service[HouseholdSettingsRepo]
        dayLoads <- Ref.make(0)
        countingTr = new CountingTrafficRepo(realTr, dayLoads)
        routerId <- seedRouter
        kid      <- TestLayers.seedKidsProfile(pr)
        _        <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        today = TestClock.schoolDayAfternoon.toLocalDate
        _        <- seedTraffic(countingTr, routerId, "aa:bb:cc:11:22:33", "example.com", today, 60)
        settings <- hsr.getForHousehold(HouseholdId.Default)
        svc = new AppUsedRollupServiceLive(pr, dr, atlr, countingTr, aur)
        _     <- dayLoads.set(0)
        out   <- svc.appCapMinutesByAppId(
          HouseholdId.Default,
          TestClock.schoolDayAfternoon.toInstant(ZoneOffset.UTC),
          today,
          settings,
          kid,
        )
        loads <- dayLoads.get
      } yield assertTrue(out.isEmpty) && assertTrue(loads == 0)
    },
  ) @@ TestAspect.sequential
}
