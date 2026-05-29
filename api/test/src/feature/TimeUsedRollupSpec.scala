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

import java.time.{LocalDate, LocalDateTime, LocalTime, ZoneOffset}

/**
 * #1160: rollup is a cache of `TimeStatusService.dayStateAll`. Tests assert the source-of-truth
 * invariant (rollup == live) and the two non-trivial invalidation triggers (household-settings tz
 * change, heartbeat-filter change).
 */
object TimeUsedRollupSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres] {

  override val bootstrap = TestDatabase.layer

  private def cleanDb = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
    TestDatabase.cleanAndMigrate.provide(ZLayer.succeed(pg)),
  )

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

  private def setTz(hsr: HouseholdSettingsRepo, tz: String): Task[HouseholdSettings] =
    for {
      cur <- hsr.get
      upd = cur.copy(dailyResetTz = java.time.ZoneId.of(tz))
      _ <- hsr.update(upd)
    } yield upd

  def spec = suite("TimeUsedRollupSpec (#1160)")(
    test("invariant: rollup(date) usedMinutes == TimeStatusService.dayState(date).usedMinutes") {
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        pr  <- ZIO.service[ProfileRepo]
        sr  <- ZIO.service[ScheduleRepo]
        dr  <- ZIO.service[DeviceRepo]
        tlr <- ZIO.service[TimeLimitRepo]
        ru  <- ZIO.service[TimeUsedRollupRepo]
        s   <- setTz(hsr, "UTC")
        kid <- TestLayers.seedKidsProfile(pr, sr)
        _   <- tlr.upsert(kid, 120)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:dd:ee:10", "kid-a", kid)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:dd:ee:11", "kid-b", kid)
        rid <- seedRouterRow
        date = LocalDate.of(2025, 1, 6)
        _   <- seedTraffic(rid, "aa:bb:cc:dd:ee:10", "youtube.com", date, 25)
        _   <- seedTraffic(rid, "aa:bb:cc:dd:ee:11", "tiktok.com", date, 15)
        // Compute live via a fresh service (before the rollup is written).
        svc <- makeService
        now = LocalDateTime.of(2025, 1, 7, 12, 0).toInstant(ZoneOffset.UTC)
        live   <- svc.dayStateAllLive(now, date, s)
        // Run the job once — it should populate the rollup.
        _      <- TimeUsedRollupJob.oneTickForTest(ru, svc, hsr, now, date)
        rolled <- ru.getDayMap(date)
      } yield assertTrue(
        rolled.get(kid).contains(live(kid).usedMinutes),
      ) && assertTrue(live(kid).usedMinutes > 0)
    },
    test("dayState reads rollup for past days, returning rolled used_minutes") {
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        pr  <- ZIO.service[ProfileRepo]
        sr  <- ZIO.service[ScheduleRepo]
        dr  <- ZIO.service[DeviceRepo]
        ru  <- ZIO.service[TimeUsedRollupRepo]
        s   <- setTz(hsr, "UTC")
        kid <- TestLayers.seedKidsProfile(pr, sr)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:dd:ee:20", "kid", kid)
        date = LocalDate.of(2025, 1, 6)
        // Pre-populate the rollup directly with a sentinel value distinct from any raw aggregation.
        _   <- ru.upsertDay(kid, date, 99)
        svc <- makeService
        now = LocalDateTime.of(2025, 1, 7, 12, 0).toInstant(ZoneOffset.UTC) // tomorrow
        st <- svc.dayState(now, date, s, kid)
      } yield assertTrue(st.exists(_.usedMinutes == 99))
    },
    test("heartbeat-filter change deletes the rollup so the next refill reflects the new filter") {
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        pr  <- ZIO.service[ProfileRepo]
        sr  <- ZIO.service[ScheduleRepo]
        dr  <- ZIO.service[DeviceRepo]
        ru  <- ZIO.service[TimeUsedRollupRepo]
        s   <- setTz(hsr, "UTC")
        kid <- TestLayers.seedKidsProfile(pr, sr)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:dd:ee:30", "kid", kid)
        date = LocalDate.of(2025, 1, 6)
        _    <- ru.upsertDay(kid, date, 50)
        pre  <- ru.getDayMap(date)
        // Mutating the filter via the repo must invalidate the rollup.
        cur  <- hsr.get
        _    <- hsr.update(
          cur.copy(heartbeatFilter = HeartbeatFilter(enabled = true, 1000, List("ntp.org"))),
        )
        post <- ru.getDayMap(date)
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
        date = LocalDate.of(2025, 1, 6)
        _    <- ru.upsertDay(kid, date, 42)
        pre  <- ru.getDayMap(date)
        _    <- setTz(hsr, "America/Denver")
        post <- ru.getDayMap(date)
      } yield assertTrue(pre.contains(kid)) && assertTrue(!post.contains(kid))
    },
  )
}
