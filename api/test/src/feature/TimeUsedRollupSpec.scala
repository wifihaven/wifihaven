package wifihaven.api.feature

import wifihaven.api.db.*
import wifihaven.api.policy.*
import wifihaven.api.usage.TimeUsedRollupJob
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.test.*

import java.time.{Instant, LocalDate, LocalDateTime, ZoneOffset}

/**
 * #1160: rollup caches today's `used_seconds` per profile with a `rolled_through` watermark. The
 * read path is `rollup + live tail`. These tests assert:
 *   - source-of-truth invariant: rollup(today) + tail == live(today)
 *   - cache miss falls back to all-live
 *   - past dates ignore the rollup entirely
 *   - heartbeat-filter and tz changes invalidate the cache
 */
object TimeUsedRollupSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres] {

  override val bootstrap = TestDatabase.layer

  private val cleanDb = TestDatabase.cleanAndMigrate

  private def makeService: ZIO[
    ProfileRepo & ScheduleRepo & TimeLimitRepo & SiteTimeLimitRepo & DeviceRepo &
      TrafficReportRepo & TimeExtensionRepo & TimeUsedRollupRepo & HouseholdSettingsRepo,
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
      ru   <- ZIO.service[TimeUsedRollupRepo]
    } yield new TimeStatusServiceLive(pr, sr, tlr, stlr, dr, trr, er, ru)

  private def seedRouterRow: ZIO[RouterRepo, Throwable, RouterId] =
    ZIO.serviceWithZIO[RouterRepo](_.create("gw-tur", Sha256Hex.unsafe("t" * 64)))

  // Seed `minutes` consecutive 5-min buckets starting at `startOffsetMin` minutes after midnight
  // UTC on `date`. Each bucket is fully active (300 active_seconds).
  private def seedTraffic(
      routerId: RouterId,
      mac: String,
      hostname: String,
      date: LocalDate,
      minutes: Int,
      startOffsetMin: Int = 0,
  ): ZIO[TrafficReportRepo, Throwable, Unit] =
    ZIO.serviceWithZIO[TrafficReportRepo] { tr =>
      val buckets = minutes / 5
      val day0    = date.atStartOfDay(ZoneOffset.UTC).toInstant.plusSeconds(startOffsetMin * 60L)
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

  private def setTz(hsr: HouseholdSettingsRepo, tz: String): Task[HouseholdSettings] =
    for {
      cur <- hsr.get
      upd = cur.copy(dailyResetTz = java.time.ZoneId.of(tz))
      _ <- hsr.update(upd)
    } yield upd

  def spec = suite("TimeUsedRollupSpec (#1160)")(
    test("invariant: rollup(today) + live-tail == live(today) for a multi-device fixture") {
      // Seed two devices on the kids profile. The fiber writes rolled_through somewhere in the
      // middle of the day; the read combines the rolled prefix + the live tail and must equal what
      // a full live aggregation over the whole day would have produced.
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        pr  <- ZIO.service[ProfileRepo]
        sr  <- ZIO.service[ScheduleRepo]
        dr  <- ZIO.service[DeviceRepo]
        tlr <- ZIO.service[TimeLimitRepo]
        ru  <- ZIO.service[TimeUsedRollupRepo]
        trr <- ZIO.service[TrafficReportRepo]
        s   <- setTz(hsr, "UTC")
        kid <- TestLayers.seedKidsProfile(pr, sr)
        _   <- tlr.upsert(kid, 240)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:dd:ee:10", "kid-a", kid)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:dd:ee:11", "kid-b", kid)
        rid <- seedRouterRow
        today = LocalDate.of(2025, 1, 6)
        // 25 min on device A starting at 08:00, then 30 more min on device B starting at 10:00.
        // (Non-overlapping macs/hours so Sum mode yields a clean total of 55 min.)
        _ <- seedTraffic(rid, "aa:bb:cc:dd:ee:10", "youtube.com", today, 25, 8 * 60)
        _ <- seedTraffic(rid, "aa:bb:cc:dd:ee:11", "tiktok.com", today, 30, 10 * 60)
        // now = 12:00 today
        now       = LocalDateTime.of(2025, 1, 6, 12, 0).toInstant(ZoneOffset.UTC)
        // Roll up everything up to 09:00 — captures only device A's 25 min; the read tail covers
        // 09:00 onward (device B's 30 min).
        watermark = LocalDateTime.of(2025, 1, 6, 9, 0).toInstant(ZoneOffset.UTC)
        // Compute live value first (rollup is empty so this goes through dayStateAllLive).
        svc      <- makeService
        live     <- svc.dayStateAllLive(now, today, s)
        // Manually plant a rolled row at the chosen watermark; the read path should compose it
        // with the live tail and recover the live total exactly.
        rolledA  <- {
          // Construct the rolled `usedSeconds` for device A in [00:00, 09:00) by aggregating live.
          // We do this by calling usedSecondsForProfile on the bucketed prefix presence.
          val prefix = LocalDateTime.of(2025, 1, 6, 9, 0).toInstant(ZoneOffset.UTC)
          for {
            profile <- ZIO.serviceWithZIO[ProfileRepo](_.findById(kid)).someOrFailException
            devices <- ZIO
              .serviceWithZIO[DeviceRepo](_.listAll)
              .map(_.filter(_.profileId.contains(kid)))
            stls    <- ZIO.serviceWithZIO[SiteTimeLimitRepo](_.listForProfile(kid))
            allPres <- trr.listPresenceRows(devices.map(_.mac), today)
            prefixP = allPres.filter(_.periodStart.isBefore(prefix))
            secs    = TimeStatusService.usedSecondsForProfile(profile, devices, stls, prefixP, s)
          } yield secs
        }
        _        <- ru.upsertDay(kid, today, RolledDay(rolledA, watermark))
        // Now read via the rollup+tail path.
        composed <- svc.dayStateAll(now, today, s)
      } yield assertTrue(composed(kid).usedMinutes == live(kid).usedMinutes) &&
        assertTrue(live(kid).usedMinutes > 0)
    },
    test("fiber tick + immediate read reproduces the live total exactly") {
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        pr  <- ZIO.service[ProfileRepo]
        sr  <- ZIO.service[ScheduleRepo]
        dr  <- ZIO.service[DeviceRepo]
        ru  <- ZIO.service[TimeUsedRollupRepo]
        trr <- ZIO.service[TrafficReportRepo]
        stl <- ZIO.service[SiteTimeLimitRepo]
        s   <- setTz(hsr, "UTC")
        kid <- TestLayers.seedKidsProfile(pr, sr)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:dd:ee:20", "kid-a", kid)
        rid <- seedRouterRow
        today = LocalDate.of(2025, 1, 6)
        _ <- seedTraffic(rid, "aa:bb:cc:dd:ee:20", "youtube.com", today, 40, 9 * 60)
        now = LocalDateTime.of(2025, 1, 6, 12, 0).toInstant(ZoneOffset.UTC)
        svc    <- makeService
        live   <- svc.dayStateAllLive(now, today, s)
        _      <- TimeUsedRollupJob.oneTickForTest(ru, pr, dr, stl, trr, hsr, now)
        cached <- svc.dayStateAll(now, today, s)
      } yield assertTrue(cached(kid).usedMinutes == live(kid).usedMinutes) &&
        assertTrue(live(kid).usedMinutes == 40)
    },
    test("cache miss falls back to all-live") {
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        pr  <- ZIO.service[ProfileRepo]
        sr  <- ZIO.service[ScheduleRepo]
        dr  <- ZIO.service[DeviceRepo]
        s   <- setTz(hsr, "UTC")
        kid <- TestLayers.seedKidsProfile(pr, sr)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:dd:ee:25", "kid-a", kid)
        rid <- seedRouterRow
        today = LocalDate.of(2025, 1, 6)
        _   <- seedTraffic(rid, "aa:bb:cc:dd:ee:25", "youtube.com", today, 15, 9 * 60)
        svc <- makeService
        now = LocalDateTime.of(2025, 1, 6, 12, 0).toInstant(ZoneOffset.UTC)
        st <- svc.dayState(now, today, s, kid)
      } yield assertTrue(st.exists(_.usedMinutes == 15))
    },
    test("past dates ignore the rollup entirely") {
      // Plant a misleading rollup row on a *past* date — the read path must NOT use it, so the
      // returned usedMinutes reflects the (absent) live presence rows instead.
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        pr  <- ZIO.service[ProfileRepo]
        sr  <- ZIO.service[ScheduleRepo]
        dr  <- ZIO.service[DeviceRepo]
        ru  <- ZIO.service[TimeUsedRollupRepo]
        s   <- setTz(hsr, "UTC")
        kid <- TestLayers.seedKidsProfile(pr, sr)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:dd:ee:30", "kid-a", kid)
        pastDate = LocalDate.of(2025, 1, 5)
        bogus    = Instant.parse("2025-01-05T23:59:59Z")
        _   <- ru.upsertDay(kid, pastDate, RolledDay(usedSeconds = 9_999L, rolledThrough = bogus))
        svc <- makeService
        now = LocalDateTime.of(2025, 1, 6, 12, 0).toInstant(ZoneOffset.UTC) // tomorrow
        st <- svc.dayState(now, pastDate, s, kid)
      } yield assertTrue(st.exists(_.usedMinutes == 0))
    },
    test("heartbeat-filter change deletes the rollup so the next refill reflects the new filter") {
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        pr  <- ZIO.service[ProfileRepo]
        sr  <- ZIO.service[ScheduleRepo]
        dr  <- ZIO.service[DeviceRepo]
        ru  <- ZIO.service[TimeUsedRollupRepo]
        _   <- setTz(hsr, "UTC")
        kid <- TestLayers.seedKidsProfile(pr, sr)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:dd:ee:40", "kid", kid)
        today = LocalDate.of(2025, 1, 6)
        _   <- ru.upsertDay(kid, today, RolledDay(50L * 60L, Instant.parse("2025-01-06T09:00:00Z")))
        pre <- ru.getDayMap(today)
        cur <- hsr.get
        _   <- hsr.update(
          cur.copy(heartbeatFilter = HeartbeatFilter(enabled = true, 1000, List("ntp.org"))),
        )
        post <- ru.getDayMap(today)
      } yield assertTrue(pre.contains(kid)) && assertTrue(!post.contains(kid))
    },
    test("daily-reset-tz change invalidates the rollup") {
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        pr  <- ZIO.service[ProfileRepo]
        sr  <- ZIO.service[ScheduleRepo]
        ru  <- ZIO.service[TimeUsedRollupRepo]
        _   <- setTz(hsr, "UTC")
        kid <- TestLayers.seedKidsProfile(pr, sr)
        today = LocalDate.of(2025, 1, 6)
        _   <- ru.upsertDay(kid, today, RolledDay(42L * 60L, Instant.parse("2025-01-06T09:00:00Z")))
        pre <- ru.getDayMap(today)
        _   <- setTz(hsr, "America/Denver")
        post <- ru.getDayMap(today)
      } yield assertTrue(pre.contains(kid)) && assertTrue(!post.contains(kid))
    },
  ) @@ TestAspect.sequential
}
