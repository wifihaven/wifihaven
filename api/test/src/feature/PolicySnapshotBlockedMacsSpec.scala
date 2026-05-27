package wifihaven.api.feature

import wifihaven.api.db.*
import wifihaven.api.policy.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.test.*

import java.time.{LocalDate, LocalDateTime, ZoneOffset}

/**
 * #305 (re-shaped by #354): the API precomputes which MACs should be currently blocked (pause /
 * daily time limit / active schedule window) so the OpenWRT agent can enforce dumbly. Post-#354 the
 * blocked state lives on each device's effective BlockRules (resolved from the profile or a
 * per-device override). These tests pin the agent-visible contract: the right MACs come out blocked
 * with the right reason, with precedence Paused > TimeLimit > Schedule.
 */
object PolicySnapshotBlockedMacsSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  /**
   * Test helper: collect (mac, reasonString) pairs for every device whose effective
   * BlockRules.blocked == true. Sorted by mac for stable assertion.
   */
  private def blockedMacs(snap: PolicySnapshot): List[(String, String)] =
    snap.devices.toList.sortBy(_._1).flatMap { case (mac, dev) =>
      val rules = dev.rules.orElse(dev.profileId.flatMap(snap.profiles.get).map(_.rules))
      rules.filter(_.blocked).map { r =>
        mac.value -> r.blockReason.map(MacBlockReason.asString).getOrElse("")
      }
    }

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private def cleanDb = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
    TestDatabase.cleanAndMigrate.provide(ZLayer.succeed(pg)),
  )

  private def makePsAt(dt: LocalDateTime) =
    for {
      pr     <- ZIO.service[ProfileRepo]
      sr     <- ZIO.service[ScheduleRepo]
      hsr    <- ZIO.service[HouseholdSettingsRepo]
      tlr    <- ZIO.service[TimeLimitRepo]
      stlr   <- ZIO.service[SiteTimeLimitRepo]
      dr     <- ZIO.service[DeviceRepo]
      blr    <- ZIO.service[BlocklistRepo]
      trRepo <- ZIO.service[TrafficReportRepo]
      er     <- ZIO.service[TimeExtensionRepo]
      ar     <- ZIO.service[AppRepo]
      ref    <- Ref.make(dt)
      clk = new Clock.TestClock(ref)
    } yield PolicyServiceLive(
      pr,
      sr,
      hsr,
      tlr,
      stlr,
      dr,
      blr,
      trRepo,
      er,
      ar,
      clk,
    ): PolicyService

  private def seedRouterRow: ZIO[RouterRepo, Throwable, RouterId] =
    ZIO.serviceWithZIO[RouterRepo](_.create("gw-seed", Sha256Hex.unsafe("o" * 64)))

  private def seedTraffic(
      routerId: RouterId,
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
        TrafficReportInsert(
          routerId,
          MacAddress.unsafe(mac),
          None,
          HostId.Fqdn(Hostname.unsafe(hostname)),
          date,
          start,
          end,
          300,
          // #789: above the default heartbeat-filter byte floor (10 KB) so rows aren't dropped.
          500_000L,
          500_000L,
        )
      }.toList
      tr.insertBatch(inserts).unit
    }

  def spec = suite("policy snapshot — blocked MACs (#305 / #354)")(
    test("schoolDayAfternoon with no pause/time/schedule → no devices blocked") {
      for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        sr   <- ZIO.service[ScheduleRepo]
        dr   <- ZIO.service[DeviceRepo]
        kid  <- TestLayers.seedKidsProfile(pr, sr)
        _    <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        ps   <- makePsAt(TestClock.schoolDayAfternoon)
        snap <- ps.snapshot
      } yield assertTrue(blockedMacs(snap).isEmpty)
    },
    test("paused profile → both its devices blocked with reason=Paused") {
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
      } yield assertTrue(
        blockedMacs(snap) == List(
          "aa:bb:cc:11:22:33" -> "Paused",
          "aa:bb:cc:11:22:44" -> "Paused",
        ),
      )
    },
    test("active schedule window (bedtime 21:30) → reason=Schedule") {
      for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        sr   <- ZIO.service[ScheduleRepo]
        dr   <- ZIO.service[DeviceRepo]
        kid  <- TestLayers.seedKidsProfile(pr, sr)
        _    <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        ps   <- makePsAt(TestClock.bedtime)
        snap <- ps.snapshot
      } yield assertTrue(blockedMacs(snap) == List("aa:bb:cc:11:22:33" -> "Schedule"))
    },
    test("overnight schedule, early-morning tail (06:00) → reason=Schedule (yesterday's window)") {
      for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        sr   <- ZIO.service[ScheduleRepo]
        dr   <- ZIO.service[DeviceRepo]
        kid  <- TestLayers.seedKidsProfile(pr, sr)
        _    <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        ps   <- makePsAt(TestClock.earlyMorning)
        snap <- ps.snapshot
      } yield assertTrue(blockedMacs(snap) == List("aa:bb:cc:11:22:33" -> "Schedule"))
    },
    test("daily time limit exhausted → reason=TimeLimit") {
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
      } yield assertTrue(blockedMacs(snap) == List("aa:bb:cc:11:22:33" -> "TimeLimit"))
    },
    test("paused beats schedule: both true → reason=Paused") {
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
      } yield assertTrue(blockedMacs(snap) == List("aa:bb:cc:11:22:33" -> "Paused"))
    },
    test("schedule beats time_limit: both true → reason=Schedule (#354 precedence)") {
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
      } yield assertTrue(blockedMacs(snap) == List("aa:bb:cc:11:22:33" -> "Schedule"))
    },
    test("#961 unassigned device not blocked under default unmanagedMacPolicy=allow") {
      for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        sr   <- ZIO.service[ScheduleRepo]
        dr   <- ZIO.service[DeviceRepo]
        kid  <- TestLayers.seedKidsProfile(pr, sr)
        _    <- pr.setPaused(kid, true)
        _    <- dr.upsertUnknown(
          MacAddress.unsafe("ff:ff:ff:aa:bb:cc"),
          "mystery",
          Some(IpAddress.unsafe("10.0.0.99")),
          java.time.Instant.now(),
        )
        ps   <- makePsAt(TestClock.bedtime)
        snap <- ps.snapshot
      } yield assertTrue(!blockedMacs(snap).exists(_._1 == "ff:ff:ff:aa:bb:cc"))
    },
    test("#961 unassigned device is Manual-blocked when unmanagedMacPolicy=block") {
      for {
        _        <- cleanDb
        pr       <- ZIO.service[ProfileRepo]
        sr       <- ZIO.service[ScheduleRepo]
        dr       <- ZIO.service[DeviceRepo]
        hsr      <- ZIO.service[HouseholdSettingsRepo]
        kid      <- TestLayers.seedKidsProfile(pr, sr)
        _        <- pr.setPaused(kid, true)
        _        <- dr.upsertUnknown(
          MacAddress.unsafe("ff:ff:ff:aa:bb:cc"),
          "mystery",
          Some(IpAddress.unsafe("10.0.0.99")),
          java.time.Instant.now(),
        )
        existing <- hsr.get
        _        <- hsr.update(
          existing.copy(unmanagedMacPolicy = UnmanagedMacPolicy(policy = "block", blockPage = true)),
        )
        ps       <- makePsAt(TestClock.bedtime)
        snap     <- ps.snapshot
      } yield assertTrue(blockedMacs(snap).contains("ff:ff:ff:aa:bb:cc" -> "Manual"))
    },
    test("etag flips when wall clock crosses a schedule edge, even with no DB change") {
      for {
        _        <- cleanDb
        pr       <- ZIO.service[ProfileRepo]
        sr       <- ZIO.service[ScheduleRepo]
        dr       <- ZIO.service[DeviceRepo]
        kid      <- TestLayers.seedKidsProfile(pr, sr)
        _        <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        psBefore <- makePsAt(TestClock.schoolDayAfternoon)
        psAfter  <- makePsAt(TestClock.bedtime)
        before   <- psBefore.snapshot
        after    <- psAfter.snapshot
      } yield assertTrue(blockedMacs(before).isEmpty) &&
        assertTrue(blockedMacs(after).nonEmpty) &&
        assertTrue(before.etag != after.etag)
    },
  ) @@ TestAspect.sequential
}
