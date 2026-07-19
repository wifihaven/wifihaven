package wifihaven.api.feature

import wifihaven.api.db.*
import wifihaven.api.policy.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.test.*

import java.time.{LocalDate, LocalDateTime, LocalTime, ZoneOffset}

/**
 * #1104: canonical test surface for `TimeStatusService`. Every other consumer of cap/block state
 * (snapshot, the 7 `/api/time/status/...` endpoints) asserts agreement with the outputs measured
 * here.
 */
object TimeStatusServiceSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres] {

  override val bootstrap = TestDatabase.layer

  private val cleanDb = TestDatabase.cleanAndMigrate

  private def makeService: ZIO[
    ProfileRepo & TimeLimitRepo & AppTimeLimitRepo & DeviceRepo & TrafficReportRepo &
      TimeExtensionRepo,
    Nothing,
    TimeStatusService,
  ] =
    for {
      pr   <- ZIO.service[ProfileRepo]
      tlr  <- ZIO.service[TimeLimitRepo]
      atlr <- ZIO.service[AppTimeLimitRepo]
      dr   <- ZIO.service[DeviceRepo]
      trr  <- ZIO.service[TrafficReportRepo]
      er   <- ZIO.service[TimeExtensionRepo]
    } yield new TimeStatusServiceLive(pr, tlr, atlr, dr, trr, er)

  private def seedRouterRow: ZIO[RouterRepo, Throwable, RouterId] =
    ZIO.serviceWithZIO[RouterRepo](_.create("gw-tss", Sha256Hex.unsafe("t" * 64)))

  /** Seed `minutes` consecutive 5-minute buckets starting at midnight UTC on `date`. */
  private def seedTraffic(
      routerId: RouterId,
      mac: String,
      hostname: String,
      date: LocalDate,
      minutes: Int,
  ): ZIO[TrafficReportRepo, Throwable, Unit] =
    ZIO.serviceWithZIO[TrafficReportRepo] { tr =>
      val buckets = minutes / 5
      val day0    = date.atStartOfDay(ZoneOffset.UTC).toInstant
      val inserts = (0 until buckets).map { i =>
        val start = day0.plusSeconds(i * 300L)
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

  /**
   * #1504: seed `windows` contiguous 60 s reporting windows where each only sampled the ~10 s
   * activeSeconds floor — the request-driven app shape. Engaged wall-clock time is `windows`
   * minutes but `Σ max(activeSeconds)` is only ~`windows/6` minutes, so the legacy bucket-max
   * per-site count undercounts ~6×. Starts at midnight UTC on `date`.
   */
  private def seedSparseTraffic(
      routerId: RouterId,
      mac: String,
      hostname: String,
      date: LocalDate,
      windows: Int,
  ): ZIO[TrafficReportRepo, Throwable, Unit] =
    ZIO.serviceWithZIO[TrafficReportRepo] { tr =>
      val day0    = date.atStartOfDay(ZoneOffset.UTC).toInstant
      val inserts = (0 until windows).map { i =>
        val start = day0.plusSeconds(i * 60L)
        TrafficReportInsert(
          routerId,
          MacAddress.unsafe(mac),
          None,
          HostId.Fqdn(Hostname.unsafe(hostname)),
          date,
          start,
          start.plusSeconds(60),
          10,
          500_000L,
          500_000L,
        )
      }.toList
      tr.insertBatch(inserts).unit
    }

  /**
   * #2025: seed `count` consecutive WIDE flush windows of `periodSec` each (the conntrack-stall
   * shape, sibling #2024) for one device/host, where each window carries only `activeSec` of REAL
   * activity at its leading edge via the self-describing `active_start`/`active_end` columns. This
   * is the prod #2016 shape: a ~20 s burst inside a ~500 s flush window. With the activity envelope
   * the server credits only the real activity; without it (the `withEnvelope = false` arm) it
   * credits the whole ballooned flush window. Starts at midnight UTC on `date`.
   */
  private def seedStallTraffic(
      routerId: RouterId,
      mac: String,
      hostname: String,
      date: LocalDate,
      count: Int,
      periodSec: Int,
      activeSec: Int,
      withEnvelope: Boolean = true,
  ): ZIO[TrafficReportRepo, Throwable, Unit] =
    ZIO.serviceWithZIO[TrafficReportRepo] { tr =>
      val day0    = date.atStartOfDay(ZoneOffset.UTC).toInstant
      val inserts = (0 until count).map { i =>
        val start = day0.plusSeconds(i.toLong * periodSec.toLong)
        TrafficReportInsert(
          routerId,
          MacAddress.unsafe(mac),
          None,
          HostId.Fqdn(Hostname.unsafe(hostname)),
          date,
          start,
          start.plusSeconds(periodSec.toLong),
          math.min(activeSec, periodSec),
          500_000L,
          500_000L,
          None,
          if (withEnvelope) Some(start) else None,
          if (withEnvelope) Some(start.plusSeconds(activeSec.toLong)) else None,
        )
      }.toList
      tr.insertBatch(inserts).unit
    }

  private def settingsWithTz(
      hsr: HouseholdSettingsRepo,
      tz: String,
      resetAt: LocalTime = LocalTime.of(0, 0),
  ): Task[HouseholdSettings] =
    for {
      cur <- hsr.get
      upd = cur.copy(
        dailyResetTz = java.time.ZoneId.of(tz),
        dailyResetTime = resetAt,
      )
      _ <- hsr.update(upd)
    } yield upd

  def spec = suite("TimeStatusService (#1104)")(
    test("west of UTC: 22:46Z under America/Denver buckets to 2026-05-26") {
      // The exact repro from the bug: late evening UTC, household tz MDT (UTC-6).
      // householdLocalDate must say 2026-05-26 even though UTC is already that wall-clock day,
      // and the snapshot for 'today' uses the same date.
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        s   <- settingsWithTz(hsr, "America/Denver")
        kid <- TestLayers.seedKidsProfile(pr)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:dd:ee:01", "kid-ipad", kid)
        svc <- makeService
        now = LocalDateTime.of(2026, 5, 26, 22, 46, 0).toInstant(ZoneOffset.UTC)
        stOpt <- svc.todaysState(HouseholdId.Default, now, s, kid)
      } yield assertTrue(
        stOpt.exists(_.date == LocalDate.of(2026, 5, 26)),
      )
    },
    test("east of UTC: 13:00Z under Asia/Tokyo buckets to 2026-05-26") {
      // Catches lazy single-direction fixes that special-case west-of-UTC.
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        pr  <- ZIO.service[ProfileRepo]
        s   <- settingsWithTz(hsr, "Asia/Tokyo")
        kid <- TestLayers.seedKidsProfile(pr)
        svc <- makeService
        now = LocalDateTime.of(2026, 5, 26, 13, 0, 0).toInstant(ZoneOffset.UTC)
        stOpt <- svc.todaysState(HouseholdId.Default, now, s, kid)
      } yield assertTrue(stOpt.exists(_.date == LocalDate.of(2026, 5, 26)))
    },
    test("after UTC midnight but before MDT midnight, today is still the prior MDT day") {
      // The actual prod incident: 00:30 UTC = 18:30 MDT the day before.
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        pr  <- ZIO.service[ProfileRepo]
        s   <- settingsWithTz(hsr, "America/Denver")
        kid <- TestLayers.seedKidsProfile(pr)
        svc <- makeService
        now = LocalDateTime.of(2026, 5, 27, 0, 30, 0).toInstant(ZoneOffset.UTC)
        stOpt <- svc.todaysState(HouseholdId.Default, now, s, kid)
      } yield assertTrue(stOpt.exists(_.date == LocalDate.of(2026, 5, 26)))
    },
    test(
      "DST spring-forward: 2025-03-09 08:00Z under America/Denver buckets to local 02:00=2025-03-09",
    ) {
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        pr  <- ZIO.service[ProfileRepo]
        s   <- settingsWithTz(hsr, "America/Denver")
        kid <- TestLayers.seedKidsProfile(pr)
        svc <- makeService
        now = LocalDateTime.of(2025, 3, 9, 8, 0, 0).toInstant(ZoneOffset.UTC)
        stOpt <- svc.todaysState(HouseholdId.Default, now, s, kid)
      } yield assertTrue(stOpt.exists(_.date == LocalDate.of(2025, 3, 9)))
    },
    test("cap exhausted exactly at minute N → blocked=true with reason=TimeLimit") {
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        tlr <- ZIO.service[TimeLimitRepo]
        s   <- settingsWithTz(hsr, "UTC")
        kid <- TestLayers.seedKidsProfile(pr)
        _   <- tlr.upsert(kid, 30)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:dd:ee:02", "kid-ipad", kid)
        rid <- seedRouterRow
        _   <- seedTraffic(rid, "aa:bb:cc:dd:ee:02", "cnn.com", LocalDate.of(2025, 1, 6), 30)
        svc <- makeService
        // 14:00 Monday — well after exhausting; no schedule conflict at this hour either
        now = Clock.TestClock.schoolDayAfternoon.toInstant(ZoneOffset.UTC)
        stOpt <- svc.todaysState(HouseholdId.Default, now, s, kid)
      } yield assertTrue(
        stOpt.exists(st => st.blocked && st.blockReason.contains(MacBlockReason.TimeLimit)),
      ) && assertTrue(stOpt.exists(_.usedMinutes >= 30))
    },
    test("extension grant lifts cap-exhausted block") {
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        tlr <- ZIO.service[TimeLimitRepo]
        er  <- ZIO.service[TimeExtensionRepo]
        s   <- settingsWithTz(hsr, "UTC")
        kid <- TestLayers.seedKidsProfile(pr)
        _   <- tlr.upsert(kid, 30)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:dd:ee:03", "kid-ipad", kid)
        rid <- seedRouterRow
        _   <- seedTraffic(rid, "aa:bb:cc:dd:ee:03", "cnn.com", LocalDate.of(2025, 1, 6), 30)
        _   <- er.grantForProfile(kid, LocalDate.of(2025, 1, 6), 15, "admin", None)
        svc <- makeService
        now = Clock.TestClock.schoolDayAfternoon.toInstant(ZoneOffset.UTC)
        stOpt <- svc.todaysState(HouseholdId.Default, now, s, kid)
      } yield assertTrue(stOpt.exists(st => !st.blocked)) &&
        assertTrue(stOpt.exists(_.extensionMinutes == 15)) &&
        assertTrue(stOpt.exists(_.remainingMinutes.contains(15)))
    },
    test("Sum vs Dedup overlap modes produce different usedMinutes for overlapping buckets") {
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        s   <- settingsWithTz(hsr, "UTC")
        kid <- TestLayers.seedKidsProfile(pr)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:dd:ee:04", "ipadA", kid)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:dd:ee:05", "ipadB", kid)
        rid <- seedRouterRow
        // Both devices active in the SAME 30 minutes
        _   <- seedTraffic(rid, "aa:bb:cc:dd:ee:04", "cnn.com", LocalDate.of(2025, 1, 6), 30)
        _   <- seedTraffic(rid, "aa:bb:cc:dd:ee:05", "cnn.com", LocalDate.of(2025, 1, 6), 30)
        svc <- makeService
        now = Clock.TestClock.schoolDayAfternoon.toInstant(ZoneOffset.UTC)
        // Default seedKidsProfile creates profile with overlap=Sum (verify shape)
        sumState   <- svc.todaysState(HouseholdId.Default, now, s, kid)
        profile    <- pr.findById(kid).map(_.get)
        _          <- pr.update(profile.copy(crossDeviceOverlapMode = CrossDeviceOverlapMode.Dedup))
        dedupState <- svc.todaysState(HouseholdId.Default, now, s, kid)
      } yield assertTrue(sumState.exists(_.usedMinutes == 60)) &&
        assertTrue(dedupState.exists(_.usedMinutes == 30))
    },
    test("per-site limit exempt from daily subtracts from total used") {
      // #1105 regression guard: a fully-exempt site limit means its buckets never count
      // toward the headline `usedMinutes`. Add a regular and an exempt site limit; only
      // the regular site's mins should land in usedMinutes.
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        ar  <- ZIO.service[AppRepo]
        s   <- settingsWithTz(hsr, "UTC")
        kid <- TestLayers.seedKidsProfile(pr)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:dd:ee:06", "kid-ipad", kid)
        _   <- TestLayers.seedAppAssignment(
          ar,
          kid,
          "khan.org",
          AppMode.TimeLimited,
          dailyMinutes = Some(60),
          exemptFromDaily = true,
        )
        rid <- seedRouterRow
        _   <- seedTraffic(rid, "aa:bb:cc:dd:ee:06", "khan.org", LocalDate.of(2025, 1, 6), 25)
        _   <- seedTraffic(rid, "aa:bb:cc:dd:ee:06", "cnn.com", LocalDate.of(2025, 1, 6), 10)
        svc <- makeService
        now = Clock.TestClock.schoolDayAfternoon.toInstant(ZoneOffset.UTC)
        stOpt <- svc.todaysState(HouseholdId.Default, now, s, kid)
      } yield assertTrue(stOpt.exists(_.usedMinutes == 10)) &&
        assertTrue(
          stOpt.exists(_.perApp.exists(p => p.domainPattern == "khan.org" && p.exemptFromDaily)),
        )
    },
    test("#1504: per-site usage counts engaged minutes, blocking at the true cap not bucket-max") {
      // 30 contiguous minutes of www.mathacademy.com sampled at the 10 s activeSeconds floor.
      // Bucket-max credits ~5 min — well under the 30 min site limit, so enforcement would let
      // the kid run on. Session-stitch credits the real 30 engaged minutes, so the per-site cap
      // is reached and the domain is blocked.
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        ar  <- ZIO.service[AppRepo]
        s   <- settingsWithTz(hsr, "UTC")
        kid <- TestLayers.seedKidsProfile(pr)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:dd:ee:07", "kid-ipad", kid)
        _   <- TestLayers.seedAppAssignment(
          ar,
          kid,
          "mathacademy.com",
          AppMode.TimeLimited,
          dailyMinutes = Some(30),
          exemptFromDaily = true,
        )
        rid <- seedRouterRow
        _   <- seedSparseTraffic(
          rid,
          "aa:bb:cc:dd:ee:07",
          "www.mathacademy.com",
          LocalDate.of(2025, 1, 6),
          30,
        )
        svc <- makeService
        now = Clock.TestClock.schoolDayAfternoon.toInstant(ZoneOffset.UTC)
        stOpt <- svc.todaysState(HouseholdId.Default, now, s, kid)
      } yield assertTrue(
        stOpt.exists(
          _.perApp.exists(p => p.domainPattern == "mathacademy.com" && p.usedMinutes == 30),
        ),
      ) &&
        assertTrue(
          stOpt.exists(
            _.perApp.exists(p =>
              p.domainPattern == "mathacademy.com" && p.dailyLimitMinutes.exists(
                p.usedMinutes >= _,
              ),
            ),
          ),
        )
    },
    test("#2025 STALL: wide flush windows with tight activity envelopes give bounded usedMinutes") {
      // The prod #2016 shape: 5 contiguous 500 s flush windows (the conntrack-gated loop stalling
      // on a quiet LAN, sibling #2024) each carrying only 20 s of REAL activity. Pre-#2025 the
      // server credited the whole 2500 s (~41 min) as continuous presence; with self-describing
      // buckets it credits only the ~100 s activity envelope (<2 min). The withEnvelope=false arm
      // reproduces the un-defended over-count, pinning that the envelope path is strictly tighter.
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        s   <- settingsWithTz(hsr, "UTC")
        kid <- TestLayers.seedKidsProfile(pr)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:dd:ee:30", "kid-ipad", kid)
        rid <- seedRouterRow
        today = LocalDate.of(2025, 1, 6)
        _   <- seedStallTraffic(rid, "aa:bb:cc:dd:ee:30", "gimkit.com", today, 5, 500, 20)
        svc <- makeService
        now = LocalDateTime.of(2025, 1, 6, 12, 0).toInstant(ZoneOffset.UTC)
        bounded <- svc.dayStateLive(HouseholdId.Default, now, today, s, kid)
        // Re-seed the SAME shape without the envelope (an old agent) to show the over-count it
        // structurally prevents.
        _       <- cleanDb
        s2      <- settingsWithTz(hsr, "UTC")
        kid2    <- TestLayers.seedKidsProfile(pr)
        _       <- TestLayers.seedDevice(dr, "aa:bb:cc:dd:ee:31", "kid-ipad", kid2)
        rid2    <- seedRouterRow
        _       <- seedStallTraffic(
          rid2,
          "aa:bb:cc:dd:ee:31",
          "gimkit.com",
          today,
          5,
          500,
          20,
          withEnvelope = false,
        )
        svc2    <- makeService
        balloon <- svc2.dayStateLive(HouseholdId.Default, now, today, s2, kid2)
      } yield assertTrue(
        bounded.exists(_.usedMinutes <= 2),
        balloon.exists(_.usedMinutes >= 30),
        bounded.map(_.usedMinutes).getOrElse(0) < balloon.map(_.usedMinutes).getOrElse(0),
      )
    },
    test("#2025 invariant: per-app usedMinutes ⊆ profile usedMinutes for a non-exempt app") {
      // A NON-exempt app's engaged minutes can never exceed the profile's total — the per-app
      // surface and the daily total derive from the same #1464 session-stitch primitive, and a
      // non-exempt app's buckets are a subset of the counted set. (Exempt apps are excluded from
      // the total and may exceed it — that's the documented carve-out, not tested here.) Pinned
      // under the stall shape so a refactor that re-inflates one surface but not the other fails.
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        ar  <- ZIO.service[AppRepo]
        s   <- settingsWithTz(hsr, "UTC")
        kid <- TestLayers.seedKidsProfile(pr)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:dd:ee:32", "kid-ipad", kid)
        _   <- TestLayers.seedAppAssignment(
          ar,
          kid,
          "gimkit.com",
          AppMode.TimeLimited,
          dailyMinutes = Some(120),
          exemptFromDaily = false,
        )
        rid <- seedRouterRow
        today = LocalDate.of(2025, 1, 6)
        _   <- seedStallTraffic(rid, "aa:bb:cc:dd:ee:32", "www.gimkit.com", today, 5, 500, 20)
        svc <- makeService
        now = LocalDateTime.of(2025, 1, 6, 12, 0).toInstant(ZoneOffset.UTC)
        stOpt <- svc.dayStateLive(HouseholdId.Default, now, today, s, kid)
      } yield assertTrue(
        stOpt.exists { st =>
          val appMins = st.perApp.find(_.domainPattern == "gimkit.com").map(_.usedMinutes)
          appMins.forall(_ <= st.usedMinutes)
        },
      )
    },
    test("#2025 invariant: Dedup-mode profile usedMinutes ≤ elapsed wall-clock and ≤ 1440") {
      // A single profile's presence can never exceed real elapsed wall-clock under Dedup (one human
      // can't be present longer than the day so far). Two devices on the SAME stalled windows: Sum
      // would double them, but Dedup unions them — and with the activity envelope the union is the
      // ~100 s of real activity, comfortably under both the elapsed-since-midnight bound and 1440.
      for {
        _    <- cleanDb
        hsr  <- ZIO.service[HouseholdSettingsRepo]
        pr   <- ZIO.service[ProfileRepo]
        dr   <- ZIO.service[DeviceRepo]
        s    <- settingsWithTz(hsr, "UTC")
        kid  <- TestLayers.seedKidsProfile(pr)
        prof <- pr.findById(kid).map(_.get)
        _    <- pr.update(prof.copy(crossDeviceOverlapMode = CrossDeviceOverlapMode.Dedup))
        _    <- TestLayers.seedDevice(dr, "aa:bb:cc:dd:ee:33", "kid-a", kid)
        _    <- TestLayers.seedDevice(dr, "aa:bb:cc:dd:ee:34", "kid-b", kid)
        rid  <- seedRouterRow
        today = LocalDate.of(2025, 1, 6)
        _   <- seedStallTraffic(rid, "aa:bb:cc:dd:ee:33", "gimkit.com", today, 5, 500, 20)
        _   <- seedStallTraffic(rid, "aa:bb:cc:dd:ee:34", "gimkit.com", today, 5, 500, 20)
        svc <- makeService
        // now = 00:45 — elapsed wall-clock since midnight is 45 min.
        now = LocalDateTime.of(2025, 1, 6, 0, 45).toInstant(ZoneOffset.UTC)
        stOpt <- svc.dayStateLive(HouseholdId.Default, now, today, s, kid)
      } yield assertTrue(
        stOpt.exists(st => st.usedMinutes <= 45 && st.usedMinutes <= 1440),
      )
    },
  ) @@ TestAspect.sequential
}
