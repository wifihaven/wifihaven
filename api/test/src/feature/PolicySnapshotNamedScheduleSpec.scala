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

import java.time.{LocalDateTime, LocalTime, ZoneId}

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
      sr     <- ZIO.service[ScheduleRepo]
      nsr    <- ZIO.service[NamedScheduleRepo]
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
      tss = new TimeStatusServiceLive(
        pr,
        sr,
        tlr,
        stlr,
        dr,
        trRepo,
        er,
        NoopTimeUsedRollupRepo,
        nsr,
      )
    } yield new PolicyServiceLive(
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
  ) @@ TestAspect.sequential
}
