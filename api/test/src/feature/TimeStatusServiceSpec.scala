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

  private def cleanDb = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
    TestDatabase.cleanAndMigrate.provide(ZLayer.succeed(pg)),
  )

  private def makeService: ZIO[
    ProfileRepo & ScheduleRepo & TimeLimitRepo & SiteTimeLimitRepo & DeviceRepo &
      TrafficReportRepo & TimeExtensionRepo,
    Nothing,
    TimeStatusService,
  ] =
    for {
      pr   <- ZIO.service[ProfileRepo]
      sr   <- ZIO.service[ScheduleRepo]
      tlr  <- ZIO.service[TimeLimitRepo]
      stlr <- ZIO.service[SiteTimeLimitRepo]
      dr   <- ZIO.service[DeviceRepo]
      trr  <- ZIO.service[TrafficReportRepo]
      er   <- ZIO.service[TimeExtensionRepo]
    } yield new TimeStatusServiceLive(pr, sr, tlr, stlr, dr, trr, er)

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
        sr  <- ZIO.service[ScheduleRepo]
        dr  <- ZIO.service[DeviceRepo]
        s   <- settingsWithTz(hsr, "America/Denver")
        kid <- TestLayers.seedKidsProfile(pr, sr)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:dd:ee:01", "kid-ipad", kid)
        svc <- makeService
        now = LocalDateTime.of(2026, 5, 26, 22, 46, 0).toInstant(ZoneOffset.UTC)
        stOpt <- svc.todaysState(now, s, kid)
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
        sr  <- ZIO.service[ScheduleRepo]
        s   <- settingsWithTz(hsr, "Asia/Tokyo")
        kid <- TestLayers.seedKidsProfile(pr, sr)
        svc <- makeService
        now = LocalDateTime.of(2026, 5, 26, 13, 0, 0).toInstant(ZoneOffset.UTC)
        stOpt <- svc.todaysState(now, s, kid)
      } yield assertTrue(stOpt.exists(_.date == LocalDate.of(2026, 5, 26)))
    },
    test("after UTC midnight but before MDT midnight, today is still the prior MDT day") {
      // The actual prod incident: 00:30 UTC = 18:30 MDT the day before.
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        pr  <- ZIO.service[ProfileRepo]
        sr  <- ZIO.service[ScheduleRepo]
        s   <- settingsWithTz(hsr, "America/Denver")
        kid <- TestLayers.seedKidsProfile(pr, sr)
        svc <- makeService
        now = LocalDateTime.of(2026, 5, 27, 0, 30, 0).toInstant(ZoneOffset.UTC)
        stOpt <- svc.todaysState(now, s, kid)
      } yield assertTrue(stOpt.exists(_.date == LocalDate.of(2026, 5, 26)))
    },
    test(
      "DST spring-forward: 2025-03-09 08:00Z under America/Denver buckets to local 02:00=2025-03-09",
    ) {
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        pr  <- ZIO.service[ProfileRepo]
        sr  <- ZIO.service[ScheduleRepo]
        s   <- settingsWithTz(hsr, "America/Denver")
        kid <- TestLayers.seedKidsProfile(pr, sr)
        svc <- makeService
        now = LocalDateTime.of(2025, 3, 9, 8, 0, 0).toInstant(ZoneOffset.UTC)
        stOpt <- svc.todaysState(now, s, kid)
      } yield assertTrue(stOpt.exists(_.date == LocalDate.of(2025, 3, 9)))
    },
    test("cap exhausted exactly at minute N → blocked=true with reason=TimeLimit") {
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        pr  <- ZIO.service[ProfileRepo]
        sr  <- ZIO.service[ScheduleRepo]
        dr  <- ZIO.service[DeviceRepo]
        tlr <- ZIO.service[TimeLimitRepo]
        s   <- settingsWithTz(hsr, "UTC")
        kid <- TestLayers.seedKidsProfile(pr, sr)
        _   <- tlr.upsert(kid, 30)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:dd:ee:02", "kid-ipad", kid)
        rid <- seedRouterRow
        _   <- seedTraffic(rid, "aa:bb:cc:dd:ee:02", "cnn.com", LocalDate.of(2025, 1, 6), 30)
        svc <- makeService
        // 14:00 Monday — well after exhausting; no schedule conflict at this hour either
        now = Clock.TestClock.schoolDayAfternoon.toInstant(ZoneOffset.UTC)
        stOpt <- svc.todaysState(now, s, kid)
      } yield assertTrue(
        stOpt.exists(st => st.blocked && st.blockReason.contains(MacBlockReason.TimeLimit)),
      ) && assertTrue(stOpt.exists(_.usedMinutes >= 30))
    },
    test("extension grant lifts cap-exhausted block") {
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        pr  <- ZIO.service[ProfileRepo]
        sr  <- ZIO.service[ScheduleRepo]
        dr  <- ZIO.service[DeviceRepo]
        tlr <- ZIO.service[TimeLimitRepo]
        er  <- ZIO.service[TimeExtensionRepo]
        s   <- settingsWithTz(hsr, "UTC")
        kid <- TestLayers.seedKidsProfile(pr, sr)
        _   <- tlr.upsert(kid, 30)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:dd:ee:03", "kid-ipad", kid)
        rid <- seedRouterRow
        _   <- seedTraffic(rid, "aa:bb:cc:dd:ee:03", "cnn.com", LocalDate.of(2025, 1, 6), 30)
        _   <- er.grantForProfile(kid, LocalDate.of(2025, 1, 6), 15, "admin", None)
        svc <- makeService
        now = Clock.TestClock.schoolDayAfternoon.toInstant(ZoneOffset.UTC)
        stOpt <- svc.todaysState(now, s, kid)
      } yield assertTrue(stOpt.exists(st => !st.blocked)) &&
        assertTrue(stOpt.exists(_.extensionMinutes == 15)) &&
        assertTrue(stOpt.exists(_.remainingMinutes.contains(15)))
    },
    test("Sum vs Dedup overlap modes produce different usedMinutes for overlapping buckets") {
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        pr  <- ZIO.service[ProfileRepo]
        sr  <- ZIO.service[ScheduleRepo]
        dr  <- ZIO.service[DeviceRepo]
        s   <- settingsWithTz(hsr, "UTC")
        kid <- TestLayers.seedKidsProfile(pr, sr)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:dd:ee:04", "ipadA", kid)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:dd:ee:05", "ipadB", kid)
        rid <- seedRouterRow
        // Both devices active in the SAME 30 minutes
        _   <- seedTraffic(rid, "aa:bb:cc:dd:ee:04", "cnn.com", LocalDate.of(2025, 1, 6), 30)
        _   <- seedTraffic(rid, "aa:bb:cc:dd:ee:05", "cnn.com", LocalDate.of(2025, 1, 6), 30)
        svc <- makeService
        now = Clock.TestClock.schoolDayAfternoon.toInstant(ZoneOffset.UTC)
        // Default seedKidsProfile creates profile with overlap=Sum (verify shape)
        sumState   <- svc.todaysState(now, s, kid)
        profile    <- pr.findById(kid).map(_.get)
        _          <- pr.update(profile.copy(crossDeviceOverlapMode = CrossDeviceOverlapMode.Dedup))
        dedupState <- svc.todaysState(now, s, kid)
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
        sr  <- ZIO.service[ScheduleRepo]
        dr  <- ZIO.service[DeviceRepo]
        ar  <- ZIO.service[AppRepo]
        s   <- settingsWithTz(hsr, "UTC")
        kid <- TestLayers.seedKidsProfile(pr, sr)
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
        stOpt <- svc.todaysState(now, s, kid)
      } yield assertTrue(stOpt.exists(_.usedMinutes == 10)) &&
        assertTrue(
          stOpt.exists(_.perSite.exists(p => p.domainPattern == "khan.org" && p.exemptFromDaily)),
        )
    },
  ) @@ TestAspect.sequential
}
