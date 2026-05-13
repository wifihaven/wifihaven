package familydns.api.feature

import familydns.api.db.*
import familydns.api.policy.*
import familydns.shared.*
import familydns.shared.Clock.TestClock
import familydns.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.test.*

import java.time.{LocalDate, LocalDateTime, ZoneOffset}
import java.util.UUID

/**
 * #305: the API precomputes which MACs should be currently blocked (pause / daily time limit /
 * active schedule window) so the OpenWRT agent can enforce dumbly. These tests pin the contract the
 * agent depends on: [[PolicySnapshot.blockedMacs]] contains the right MACs with the right reason,
 * with precedence pause > time_limit > schedule.
 */
object PolicySnapshotBlockedMacsSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private def cleanDb = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
    TestDatabase.cleanAndMigrate.provide(ZLayer.succeed(pg)),
  )

  private def makePsAt(dt: LocalDateTime) =
    for {
      pr     <- ZIO.service[ProfileRepo]
      sr     <- ZIO.service[ScheduleRepo]
      tlr    <- ZIO.service[TimeLimitRepo]
      stlr   <- ZIO.service[SiteTimeLimitRepo]
      dr     <- ZIO.service[DeviceRepo]
      blr    <- ZIO.service[BlocklistRepo]
      trRepo <- ZIO.service[TrafficReportRepo]
      er     <- ZIO.service[TimeExtensionRepo]
      ref    <- Ref.make(dt)
      clk = new Clock.TestClock(ref)
    } yield (new PolicyServiceLive(pr, sr, tlr, stlr, dr, blr, trRepo, er, clk)): PolicyService

  private def seedRouterRow: ZIO[RouterRepo, Throwable, UUID] =
    ZIO.serviceWithZIO[RouterRepo](_.create("gw-seed", "ENROLL_HASH"))

  private def seedTraffic(
      routerId: UUID,
      mac: String,
      hostname: String,
      date: LocalDate,
      minutes: Int,
  ): ZIO[TrafficReportRepo, Throwable, Unit] =
    ZIO.serviceWithZIO[TrafficReportRepo] { tr =>
      val buckets = minutes / 5
      val today0  = date.atStartOfDay(ZoneOffset.UTC).toInstant
      val inserts = (0 until buckets).map { i =>
        val start = today0.plusSeconds(i * 300L)
        val end   = start.plusSeconds(300)
        TrafficReportInsert(routerId, mac, None, hostname, date, start, end, 300, 0L, 0L)
      }.toList
      tr.insertBatch(inserts).unit
    }

  def spec = suite("policy snapshot — blockedMacs (#305)")(
    test("schoolDayAfternoon with no pause/time/schedule → blockedMacs is empty") {
      for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        sr   <- ZIO.service[ScheduleRepo]
        dr   <- ZIO.service[DeviceRepo]
        kid  <- TestLayers.seedKidsProfile(pr, sr)
        _    <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        ps   <- makePsAt(TestClock.schoolDayAfternoon)
        snap <- ps.snapshot
      } yield assertTrue(snap.blockedMacs.isEmpty)
    },
    test("paused profile → blockedMacs lists its devices with reason=paused") {
      for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        sr   <- ZIO.service[ScheduleRepo]
        dr   <- ZIO.service[DeviceRepo]
        kid  <- TestLayers.seedKidsProfile(pr, sr)
        _    <- pr.setPaused(kid, true)
        _    <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        _    <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:44", "kid-phone", kid)
        ps   <- makePsAt(TestClock.schoolDayAfternoon)
        snap <- ps.snapshot
      } yield assertTrue(snap.blockedMacs.size == 2) &&
        assertTrue(snap.blockedMacs.forall(_.reason == "paused")) &&
        assertTrue(
          snap.blockedMacs.map(_.mac).toSet == Set("aa:bb:cc:11:22:33", "aa:bb:cc:11:22:44"),
        )
    },
    test("active schedule window (bedtime 21:30) → reason=schedule") {
      for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        sr   <- ZIO.service[ScheduleRepo]
        dr   <- ZIO.service[DeviceRepo]
        kid  <- TestLayers.seedKidsProfile(pr, sr)
        _    <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        ps   <- makePsAt(TestClock.bedtime)
        snap <- ps.snapshot
      } yield assertTrue(snap.blockedMacs == List(BlockedMac("aa:bb:cc:11:22:33", "schedule")))
    },
    test("overnight schedule, early-morning tail (06:00) → reason=schedule (yesterday's window)") {
      for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        sr   <- ZIO.service[ScheduleRepo]
        dr   <- ZIO.service[DeviceRepo]
        kid  <- TestLayers.seedKidsProfile(pr, sr)
        _    <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        ps   <- makePsAt(TestClock.earlyMorning)
        snap <- ps.snapshot
      } yield assertTrue(snap.blockedMacs == List(BlockedMac("aa:bb:cc:11:22:33", "schedule")))
    },
    test("daily time limit exhausted → reason=time_limit") {
      for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        sr   <- ZIO.service[ScheduleRepo]
        dr   <- ZIO.service[DeviceRepo]
        tlr  <- ZIO.service[TimeLimitRepo]
        kid  <- TestLayers.seedKidsProfile(pr, sr)
        _    <- tlr.upsert(kid, 120)
        _    <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        rid  <- seedRouterRow
        _    <- seedTraffic(rid, "aa:bb:cc:11:22:33", "cnn.com", LocalDate.of(2025, 1, 6), 125)
        ps   <- makePsAt(TestClock.schoolDayAfternoon)
        snap <- ps.snapshot
      } yield assertTrue(snap.blockedMacs == List(BlockedMac("aa:bb:cc:11:22:33", "time_limit")))
    },
    test("paused beats schedule: both true → reason=paused") {
      for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        sr   <- ZIO.service[ScheduleRepo]
        dr   <- ZIO.service[DeviceRepo]
        kid  <- TestLayers.seedKidsProfile(pr, sr)
        _    <- pr.setPaused(kid, true)
        _    <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        ps   <- makePsAt(TestClock.bedtime)
        snap <- ps.snapshot
      } yield assertTrue(snap.blockedMacs == List(BlockedMac("aa:bb:cc:11:22:33", "paused")))
    },
    test("time_limit beats schedule: both true → reason=time_limit") {
      for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        sr   <- ZIO.service[ScheduleRepo]
        dr   <- ZIO.service[DeviceRepo]
        tlr  <- ZIO.service[TimeLimitRepo]
        kid  <- TestLayers.seedKidsProfile(pr, sr)
        _    <- tlr.upsert(kid, 120)
        _    <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        rid  <- seedRouterRow
        _    <- seedTraffic(rid, "aa:bb:cc:11:22:33", "cnn.com", LocalDate.of(2025, 1, 6), 125)
        ps   <- makePsAt(TestClock.bedtime)
        snap <- ps.snapshot
      } yield assertTrue(snap.blockedMacs == List(BlockedMac("aa:bb:cc:11:22:33", "time_limit")))
    },
    test("unassigned device (profileId=null) is never blocked") {
      for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        sr   <- ZIO.service[ScheduleRepo]
        dr   <- ZIO.service[DeviceRepo]
        kid  <- TestLayers.seedKidsProfile(pr, sr)
        _    <- pr.setPaused(kid, true)
        _    <- dr.upsertUnknown(
          "ff:ff:ff:aa:bb:cc",
          "mystery",
          Some("10.0.0.99"),
          java.time.Instant.now(),
        )
        ps   <- makePsAt(TestClock.bedtime)
        snap <- ps.snapshot
      } yield assertTrue(snap.blockedMacs.isEmpty)
    },
    test("etag flips when wall clock crosses a schedule edge, even with no DB change") {
      for {
        _        <- cleanDb
        pr       <- ZIO.service[ProfileRepo]
        sr       <- ZIO.service[ScheduleRepo]
        dr       <- ZIO.service[DeviceRepo]
        kid      <- TestLayers.seedKidsProfile(pr, sr)
        _        <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        psBefore <- makePsAt(TestClock.schoolDayAfternoon) // 14:00 — not blocked
        psAfter  <- makePsAt(TestClock.bedtime)            // 21:30 — schedule active
        before   <- psBefore.snapshot
        after    <- psAfter.snapshot
      } yield assertTrue(before.blockedMacs.isEmpty) &&
        assertTrue(after.blockedMacs.nonEmpty) &&
        assertTrue(before.etag != after.etag)
    },
  ) @@ TestAspect.sequential
}
