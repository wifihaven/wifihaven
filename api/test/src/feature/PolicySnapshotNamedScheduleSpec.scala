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

import java.time.{LocalDate, LocalDateTime, LocalTime, ZoneId, ZoneOffset}

/**
 * #1069: the policy snapshot must fold a profile's *named* schedule (referenced via
 * profiles.schedule_id) into the per-MAC `blocked` flag, exactly like the legacy per-profile
 * schedules — so editing the household schedule reflects on the next snapshot rebuild, and the
 * router never learns schedules exist. These tests pin that the named-schedule path drives
 * blocked/reason=Schedule on its own (the profile has NO legacy schedules).
 */
object PolicySnapshotNamedScheduleSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private def blockedMacs(snap: PolicySnapshot): List[(String, String)] =
    snap.devices.toList.sortBy(_._1).flatMap { case (mac, dev) =>
      val rules = dev.rules.orElse(dev.profileId.flatMap(snap.profiles.get).map(_.rules))
      rules.filter(_.blocked).map { r =>
        mac.value -> r.blockReason.map(MacBlockReason.asString).getOrElse("")
      }
    }

  // Build the full PolicyService wiring with the REAL NamedScheduleRepo threaded through both
  // TimeStatusService (snapshot blocked-flag) and PolicyServiceLive (per-host /decision).
  private def makePsAt(dt: LocalDateTime) =
    for {
      pr     <- ZIO.service[ProfileRepo]
      nsr    <- ZIO.service[NamedScheduleRepo]
      hsr    <- ZIO.service[HouseholdSettingsRepo]
      tlr    <- ZIO.service[TimeLimitRepo]
      atlr   <- ZIO.service[AppTimeLimitRepo]
      dr     <- ZIO.service[DeviceRepo]
      blr    <- ZIO.service[BlocklistRepo]
      trRepo <- ZIO.service[TrafficReportRepo]
      er     <- ZIO.service[TimeExtensionRepo]
      ar     <- ZIO.service[AppRepo]
      ref    <- Ref.make(dt)
      clk = new Clock.TestClock(ref)
      tss = new TimeStatusServiceLive(
        pr,
        tlr,
        atlr,
        dr,
        trRepo,
        er,
        NoopTimeUsedRollupRepo,
        nsr,
      )
    } yield new PolicyServiceLive(
      pr,
      hsr,
      tlr,
      atlr,
      dr,
      blr,
      trRepo,
      er,
      ar,
      tss,
      clk,
      namedScheduleRepo = nsr,
    ): PolicyService

  // A profile with NO legacy schedules, linked to a named schedule with a bedtime window.
  private def seedBedtimeNamedSchedule =
    for {
      pr  <- ZIO.service[ProfileRepo]
      nsr <- ZIO.service[NamedScheduleRepo]
      dr  <- ZIO.service[DeviceRepo]
      pid <- pr.create("Kids", Nil)
      sid <- nsr.create(
        "Bedtime",
        Some("overnight"),
        List(
          ScheduleWindow(
            List("mon", "tue", "wed", "thu", "fri", "sat", "sun"),
            LocalTime.of(21, 0),
            LocalTime.of(7, 0),
            ZoneId.of("UTC"),
          ),
        ),
      )
      _   <- nsr.setProfileBlockSchedules(pid, List(sid))
      _   <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", pid)
    } yield (pid, sid)

  // Set a 1-minute daily limit on the profile and accrue well past it, so the
  // TimeLimit step (#3) would block on its own — used to prove Schedule (#2)
  // wins. seedTraffic mirrors RouterDecisionSpec: 5-min traffic_reports buckets
  // for 2025-01-06 (the TestClock fixture date).
  private def seedDailyLimitExceeded(pid: ProfileId) =
    for {
      tlr <- ZIO.service[TimeLimitRepo]
      rr  <- ZIO.service[RouterRepo]
      tr  <- ZIO.service[TrafficReportRepo]
      _   <- tlr.upsert(pid, 1)
      rid <- rr.create("gw-seed", Sha256Hex.unsafe("n" * 64))
      date    = LocalDate.of(2025, 1, 6)
      day0    = date.atStartOfDay(ZoneOffset.UTC).toInstant
      inserts = (0 until 4).toList.map { i =>
        val start = day0.plusSeconds(i * 300L)
        TrafficReportInsert(
          rid,
          MacAddress.unsafe("aa:bb:cc:11:22:33"),
          None,
          HostId.Fqdn(Hostname.unsafe("youtube.com")),
          date,
          start,
          start.plusSeconds(300),
          300,
          500_000L,
          500_000L,
        )
      }
      _ <- tr.insertBatch(inserts)
    } yield ()

  def spec = suite("policy snapshot — named schedules (#1069)")(
    test("active named-schedule window (bedtime 21:30) → device blocked, reason=Schedule") {
      for {
        _    <- cleanDb
        _    <- seedBedtimeNamedSchedule
        ps   <- makePsAt(TestClock.bedtime)
        snap <- ps.snapshot
      } yield assertTrue(blockedMacs(snap) == List("aa:bb:cc:11:22:33" -> "Schedule"))
    },
    test("outside the window (school-day afternoon) → not blocked") {
      for {
        _    <- cleanDb
        _    <- seedBedtimeNamedSchedule
        ps   <- makePsAt(TestClock.schoolDayAfternoon)
        snap <- ps.snapshot
      } yield assertTrue(blockedMacs(snap).isEmpty)
    },
    test("editing the schedule's window reflects on the next snapshot (no DB profile change)") {
      for {
        _      <- cleanDb
        nsr    <- ZIO.service[NamedScheduleRepo]
        seeded <- seedBedtimeNamedSchedule
        (_, sid) = seeded
        // Narrow the window to mornings only → bedtime 21:30 no longer active.
        _    <- nsr.update(
          sid,
          "Bedtime",
          Some("mornings only now"),
          List(
            ScheduleWindow(List("mon"), LocalTime.of(6, 0), LocalTime.of(7, 0), ZoneId.of("UTC")),
          ),
        )
        ps   <- makePsAt(TestClock.bedtime)
        snap <- ps.snapshot
      } yield assertTrue(blockedMacs(snap).isEmpty)
    },
    // #1494: an attached named schedule must win over an also-active daily
    // TimeLimit — PolicyService precedence is Schedule (#2) > TimeLimit (#3).
    // This is the snapshot-level counterpart to e2e §7, which writes the
    // schedule through the same attach path (profile_schedule_rules).
    test("active named schedule + exceeded daily limit → Schedule wins over TimeLimit") {
      for {
        _    <- cleanDb
        pid  <- seedBedtimeNamedSchedule.map(_._1)
        _    <- seedDailyLimitExceeded(pid)
        ps   <- makePsAt(TestClock.bedtime)
        snap <- ps.snapshot
      } yield assertTrue(blockedMacs(snap) == List("aa:bb:cc:11:22:33" -> "Schedule"))
    },
    test("control: same exceeded limit, no active schedule → TimeLimit blocks") {
      // Outside the bedtime window the schedule is inactive, so the same usage
      // falls through to TimeLimit — confirming the limit really is hot and the
      // test above isn't just measuring an absent TimeLimit.
      for {
        _    <- cleanDb
        pid  <- seedBedtimeNamedSchedule.map(_._1)
        _    <- seedDailyLimitExceeded(pid)
        ps   <- makePsAt(TestClock.schoolDayAfternoon)
        snap <- ps.snapshot
      } yield assertTrue(blockedMacs(snap) == List("aa:bb:cc:11:22:33" -> "TimeLimit"))
    },
    test("no schedule reference → never blocked by schedule") {
      for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        dr   <- ZIO.service[DeviceRepo]
        pid  <- pr.create("Adults", Nil)
        _    <- TestLayers.seedDevice(dr, "aa:bb:cc:44:55:66", "adult-phone", pid)
        ps   <- makePsAt(TestClock.bedtime)
        snap <- ps.snapshot
      } yield assertTrue(blockedMacs(snap).isEmpty)
    },
    // #1539 invariant guard: two profiles attaching the SAME named schedules
    // must enforce identically. The prod symptom was divergent enforcement
    // across profiles sharing 'Bedtime'+'Breakfast'; the evaluation path is
    // profile-uniform, so this pins that identical attachments → identical
    // `blocked`/reason for every profile. (Root cause was a display-only chip
    // reading the dead legacy `schedules` table; this keeps the *server* side
    // honest in case a future per-profile branch ever creeps in.)
    test("two profiles sharing the same two named schedules block identically") {
      for {
        _   <- cleanDb
        pr  <- ZIO.service[ProfileRepo]
        nsr <- ZIO.service[NamedScheduleRepo]
        dr  <- ZIO.service[DeviceRepo]
        allDays = List("mon", "tue", "wed", "thu", "fri", "sat", "sun")
        // 'Bedtime' is active at 21:30; 'Breakfast' is inactive then. Both are
        // attached to BOTH profiles, exactly like the prod setup.
        bedtime   <- nsr.create(
          "Bedtime",
          Some("overnight"),
          List(ScheduleWindow(allDays, LocalTime.of(21, 0), LocalTime.of(7, 0), ZoneId.of("UTC"))),
        )
        breakfast <- nsr.create(
          "Breakfast",
          Some("morning"),
          List(ScheduleWindow(allDays, LocalTime.of(7, 0), LocalTime.of(8, 0), ZoneId.of("UTC"))),
        )
        pidA      <- pr.create("Kids", Nil)
        pidB      <- pr.create("Teens", Nil)
        _         <- nsr.setProfileBlockSchedules(pidA, List(bedtime, breakfast))
        _         <- nsr.setProfileBlockSchedules(pidB, List(bedtime, breakfast))
        _         <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", pidA)
        _         <- TestLayers.seedDevice(dr, "aa:bb:cc:44:55:66", "teen-phone", pidB)
        ps        <- makePsAt(TestClock.bedtime)
        snap      <- ps.snapshot
      } yield assertTrue(
        blockedMacs(snap) == List(
          "aa:bb:cc:11:22:33" -> "Schedule",
          "aa:bb:cc:44:55:66" -> "Schedule",
        ),
      )
    },
  ) @@ TestAspect.sequential
}
