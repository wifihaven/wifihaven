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
import zio.json.*
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
 * PIN ONE — '''one''' day-state computation per request. `BlockedRoutes.resolve` used to call
 * `policy.decide` (which resolves the device, loads the profile, reads the household settings and
 * computes the profile's `ProfileDayState` internally) and then re-read all four of those itself,
 * so every block-page load paid for the expensive part twice. The route now consumes what `decide`
 * already computed ([[PolicyService.decideDetailed]]) and no longer holds the repos it would need
 * to re-read them.
 *
 * PIN TWO — a profile with '''no app assignments''' performs '''no''' presence read in
 * [[AppUsedRollupServiceLive]]. That service fetches a whole day of `traffic_reports` to aggregate
 * per-app engaged seconds, and falls back to the WHOLE DAY (rather than the tail past the rollup
 * watermark) whenever `app_used_daily` has no rows for the profile — which is exactly the case for
 * a profile with no app assignments, where the aggregation is provably empty. Prod profiles 2/3/4
 * (the three slow ones above) have zero rows in `app_policy_assignments`; they were each scanning
 * 191k / 136k / 8k rows per call to build an empty map.
 *
 * The last two tests exist because the first two, alone, would each pass for a weaker reason than
 * they claim. PIN TWO's `out.isEmpty` is trivially true over an empty rollup, so the third test
 * gives it a leftover rolled row to prove the skip drops the READ and not the RESULT. And PIN ONE
 * runs over `NoopTimeUsedRollupRepo` (`PolicyServiceLive.apply` wires it), which is the all-live
 * day-state path — but `appCapMinutesByAppId` is reached ONLY from `dayStateFromRollupAndTail`, so
 * the fourth test wires the real rollup repos and seeds a rolled day to exercise the shape prod is
 * actually in.
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
        (ps, _) = psClk
        routes  = BlockedRoutes.routes(
          ps,
          blr,
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
    // The branch the skip's own comment claims is safe: `rolled` is NOT empty (an assignment was
    // removed after the last rollup tick, leaving its app_used_daily row behind) while the profile
    // now has no assignments. Skipping the READ must not skip the RESULT — the rolled seconds still
    // have to be projected to minutes exactly as the full path would. Without this case the
    // `out.isEmpty` assertion above passes trivially, since an empty rollup can only ever project
    // to an empty map.
    test("no app assignments but a leftover rolled row → rolled minutes still projected, 0 loads") {
      for {
        _        <- cleanDb
        pr       <- ZIO.service[ProfileRepo]
        dr       <- ZIO.service[DeviceRepo]
        atlr     <- ZIO.service[AppTimeLimitRepo]
        ar       <- ZIO.service[AppRepo]
        realTr   <- ZIO.service[TrafficReportRepo]
        aur      <- ZIO.service[AppUsedRollupRepo]
        hsr      <- ZIO.service[HouseholdSettingsRepo]
        dayLoads <- Ref.make(0)
        countingTr = new CountingTrafficRepo(realTr, dayLoads)
        routerId <- seedRouter
        kid      <- TestLayers.seedKidsProfile(pr)
        _        <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        today = TestClock.schoolDayAfternoon.toLocalDate
        now   = TestClock.schoolDayAfternoon.toInstant(ZoneOffset.UTC)
        _        <- seedTraffic(countingTr, routerId, "aa:bb:cc:11:22:33", "example.com", today, 60)
        // An app row with NO assignment to this profile — so `listForProfile` is empty (the skip
        // fires) but its rolled row is present.
        appId    <- ar.create("orphaned", "orphaned", None, None)
        _        <- aur.upsertDay(kid, appId, today, RolledAppDay(25L * 60L, now))
        settings <- hsr.getForHousehold(HouseholdId.Default)
        svc = new AppUsedRollupServiceLive(pr, dr, atlr, countingTr, aur)
        _     <- dayLoads.set(0)
        out   <- svc.appCapMinutesByAppId(HouseholdId.Default, now, today, settings, kid)
        loads <- dayLoads.get
      } yield assertTrue(out == Map(appId -> 25)) && assertTrue(loads == 0)
    },
    // PIN ONE covers the all-live day-state path, because `PolicyServiceLive.apply` wires
    // `NoopTimeUsedRollupRepo`. Prod is the OTHER path: `time_used_daily` has a row for every
    // profile on every tick, so `TimeStatusServiceLive.dayState` takes the rollup+tail branch —
    // and `dayStateFromRollupAndTail` is the ONLY caller of `appCapMinutesByAppId`, i.e. the only
    // way the whole-day scan this issue is about is reached at all. So this wires the real
    // `TimeUsedRollupRepo` + `AppUsedRollupServiceLive` and seeds a rolled row, reproducing the
    // prod shape: the tail read is cheap, and after #2652 there is no whole-day read left.
    test("with the day rolled (the prod shape), /api/blocked performs NO whole-day presence load") {
      for {
        _        <- cleanDb
        pr       <- ZIO.service[ProfileRepo]
        hsr      <- ZIO.service[HouseholdSettingsRepo]
        tlr      <- ZIO.service[TimeLimitRepo]
        atlr     <- ZIO.service[AppTimeLimitRepo]
        dr       <- ZIO.service[DeviceRepo]
        blr      <- ZIO.service[BlocklistRepo]
        er       <- ZIO.service[TimeExtensionRepo]
        ar       <- ZIO.service[AppRepo]
        nsr      <- ZIO.service[NamedScheduleRepo]
        tur      <- ZIO.service[TimeUsedRollupRepo]
        aur      <- ZIO.service[AppUsedRollupRepo]
        realTr   <- ZIO.service[TrafficReportRepo]
        dayLoads <- Ref.make(0)
        countingTr = new CountingTrafficRepo(realTr, dayLoads)
        routerId <- seedRouter
        kid      <- TestLayers.seedKidsProfile(pr)
        _        <- pr.setPaused(kid, true)
        _        <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        today = TestClock.schoolDayAfternoon.toLocalDate
        now   = TestClock.schoolDayAfternoon.toInstant(ZoneOffset.UTC)
        _   <- seedTraffic(countingTr, routerId, "aa:bb:cc:11:22:33", "example.com", today, 60)
        _   <- tur.upsertDay(kid, today, RolledDay(30L * 60L, now))
        ref <- Ref.make(TestClock.schoolDayAfternoon)
        clk               = new Clock.TestClock(ref)
        appUsed           = new AppUsedRollupServiceLive(pr, dr, atlr, countingTr, aur)
        tss               = new TimeStatusServiceLive(
          pr,
          tlr,
          atlr,
          dr,
          countingTr,
          er,
          tur,
          nsr,
          appUsed,
        )
        ps: PolicyService = new PolicyServiceLive(
          pr,
          hsr,
          tlr,
          atlr,
          dr,
          blr,
          countingTr,
          er,
          ar,
          tss,
          clk,
          namedScheduleRepo = nsr,
        )
        routes            = BlockedRoutes.routes(
          ps,
          blr,
          BlockPageHousehold.defaultOnly,
          RateLimiter.allowAll,
          RateLimiter.allowAll,
        )
        _    <- dayLoads.set(0)
        resp <- routes.runZIO(
          Request.get(
            URL.decode("/api/blocked?mac=aa:bb:cc:11:22:33&host=example.com").toOption.get,
          ),
        )
        body <- resp.body.asString
        info <- ZIO.fromEither(body.fromJson[BlockedInfoResponse]).mapError(new RuntimeException(_))
        loads <- dayLoads.get
      } yield assertTrue(loads == 0) &&
        // The answer is still correct, and still carries the profile's screen time — the rolled 30
        // minutes, not a zero from a skipped read.
        assertTrue(info.blocked) &&
        assertTrue(info.reasonClass.contains("paused")) &&
        assertTrue(info.profileName.contains("Kids")) &&
        assertTrue(info.usedMinutes.contains(30))
    },
  ) @@ TestAspect.sequential
}
