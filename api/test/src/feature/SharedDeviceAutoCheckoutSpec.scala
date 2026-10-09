package wifihaven.api.feature

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import wifihaven.api.db.*
import wifihaven.api.policy.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.interop.catz.*
import zio.test.*

import java.time.{Instant, LocalDateTime, LocalTime, ZoneOffset}

/**
 * #2849 (epic #2841, design `docs/design/shared-devices.md` §7.2 rows 3–7, §7.3, §15 Q3/Q3a): the
 * auto-checkout job on the per-household reevaluate tick, driven through the real
 * `PolicyServiceLive.reevaluate` (cache on, the job wired the way `PolicyService.layer` wires it)
 * on embedded Postgres, with an injected clock. No fiber is waited on: each test calls the tick.
 *
 * Every release asserts three things: the history row's `end_cause` and exact `ended_at`, the
 * snapshot the same tick built (`CheckedOut` again), and that presence after the release is no
 * longer credited to the holder. Each trigger test also holds a second check-in whose holder
 * matches no trigger, which must stay open: the liveness anchor that proves the tick ran and did
 * not simply release everything.
 */
object SharedDeviceAutoCheckoutSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val SharedMac = MacAddress.unsafe("aa:bb:cc:00:49:01")
  private val OtherMac  = MacAddress.unsafe("aa:bb:cc:00:49:02")
  private val PlainMac  = MacAddress.unsafe("aa:bb:cc:00:49:03")
  private val Monday    = TestClock.schoolDayAfternoon.toLocalDate

  private def at(h: Int, m: Int, s: Int = 0): LocalDateTime = Monday.atTime(h, m, s)
  private def utc(dt: LocalDateTime): Instant               = dt.toInstant(ZoneOffset.UTC)

  final case class Fx(
      ps: PolicyService,
      clock: TestClock,
      kid1: ProfileId,
      kid2: ProfileId,
      router: RouterId,
      released: Ref[List[HouseholdId]],
  )

  private def markShared(mac: MacAddress) =
    ZIO.serviceWithZIO[Transactor[Task]] { xa =>
      sql"UPDATE devices SET shared = true WHERE mac = ${mac.value}".update.run.transact(xa).unit
    }

  /** Two shared, checked-out devices and two profiles, with the clock at `start`. */
  private def setup(start: LocalDateTime) =
    for {
      _              <- cleanDb
      pr             <- ZIO.service[ProfileRepo]
      hsr            <- ZIO.service[HouseholdSettingsRepo]
      tlr            <- ZIO.service[TimeLimitRepo]
      atlr           <- ZIO.service[AppTimeLimitRepo]
      dr             <- ZIO.service[DeviceRepo]
      blr            <- ZIO.service[BlocklistRepo]
      trr            <- ZIO.service[TrafficReportRepo]
      er             <- ZIO.service[TimeExtensionRepo]
      ar             <- ZIO.service[AppRepo]
      nsr            <- ZIO.service[NamedScheduleRepo]
      sdr            <- ZIO.service[SharedDeviceRepo]
      (clk, control) <- TestClock.makeWithControl(start)
      tss            <- TestLayers.timeStatusService
      kid1           <- pr.create("Kid1", Nil)
      kid2           <- pr.create("Kid2", Nil)
      _              <- dr.upsertUnknown(SharedMac, "family-mac", None, utc(start))
      _              <- dr.upsertUnknown(OtherMac, "family-ipad", None, utc(start))
      _              <- markShared(SharedMac) *> markShared(OtherMac)
      router   <- ZIO.serviceWithZIO[RouterRepo](_.create("gw-2849", Sha256Hex.unsafe("d" * 64)))
      released <- Ref.make(List.empty[HouseholdId])
      job = new SharedDeviceCheckoutJob(sdr, hsr, tss, clk)
      _ <- job.setOnReleased(hh => released.update(hh :: _))
      ps = PolicyServiceLive(
        pr,
        hsr,
        tlr,
        atlr,
        dr,
        blr,
        trr,
        er,
        ar,
        clk,
        namedScheduleRepo = nsr,
        cacheEnabled = true,
        autoCheckout = job.run,
      )
    } yield Fx(ps, control, kid1, kid2, router, released)

  private def deviceId(mac: MacAddress) =
    ZIO.serviceWithZIO[DeviceRepo](_.findByMac(mac)).someOrFailException.map(_.id)

  private def checkIn(fx: Fx, mac: MacAddress, pid: ProfileId) =
    for {
      id  <- deviceId(mac)
      now <- fx.clock.instant
      out <- ZIO.serviceWithZIO[SharedDeviceRepo](
        _.checkIn(HouseholdId.Default, id, pid, now, "admin"),
      )
      _   <- ZIO.when(out != CheckInOutcome.CheckedIn)(ZIO.fail(new RuntimeException(s"$out")))
    } yield ()

  /** The tick under test: one reevaluate sweep, exactly what Main's ticker runs. */
  private def tick(fx: Fx) = fx.ps.reevaluate

  /** `(kind, profile_id, end_cause, ended_at)` of every history row for `mac`, oldest first. */
  private def history(mac: MacAddress) =
    ZIO.serviceWithZIO[Transactor[Task]] { xa =>
      sql"""SELECT h.kind, h.profile_id, h.end_cause, h.ended_at
              FROM device_profile_assignments h JOIN devices d ON d.id = h.device_id
             WHERE d.mac = ${mac.value} ORDER BY h.id"""
        .query[(String, Long, Option[String], Option[Instant])]
        .to[List]
        .transact(xa)
    }

  private def snapshotDevice(fx: Fx, mac: MacAddress) = fx.ps.snapshot.map(_.devices.get(mac))

  private def isCheckedOut(dp: Option[DevicePolicy]): Boolean =
    dp.exists(d =>
      d.profileId.isEmpty &&
        d.rules.exists(r => r.blocked && r.blockReason.contains(MacBlockReason.CheckedOut)),
    )

  private def heldBy(dp: Option[DevicePolicy], pid: ProfileId): Boolean =
    dp.exists(d => d.profileId.contains(pid) && d.rules.isEmpty)

  /**
   * `minutes` of active traffic on `mac` from `from`, in 5-minute reports. `bytes` above the
   * heartbeat floor counts as screen time; `host` defaults to an ordinary site.
   */
  private def usage(
      fx: Fx,
      mac: MacAddress,
      from: LocalDateTime,
      minutes: Int,
      host: String = "example.com",
      bytes: Long = 500_000L,
  ) =
    for {
      settings <- ZIO.serviceWithZIO[HouseholdSettingsRepo](_.getForHousehold(HouseholdId.Default))
      _        <- ZIO.serviceWithZIO[TrafficReportRepo](
        _.insertBatch(
          (0 until minutes / 5).toList.map { i =>
            val start = utc(from).plusSeconds(i * 300L)
            TrafficReportInsert(
              fx.router,
              mac,
              None,
              HostId.Fqdn(Hostname.unsafe(host)),
              PolicyService.householdLocalDate(start, settings),
              start,
              start.plusSeconds(300),
              300,
              bytes,
              bytes,
            )
          },
        ),
      )
    } yield ()

  /** Minutes credited to `pid` on the household-local day containing the clock's now. */
  private def usedMinutes(fx: Fx, pid: ProfileId) =
    for {
      now      <- fx.clock.instant
      settings <- ZIO.serviceWithZIO[HouseholdSettingsRepo](_.getForHousehold(HouseholdId.Default))
      tss      <- TestLayers.timeStatusService
      st       <- tss.todaysState(HouseholdId.Default, now, settings, pid)
    } yield st.map(_.usedMinutes).getOrElse(-1)

  private def setIdleMinutes(m: Int) =
    ZIO.serviceWithZIO[HouseholdSettingsRepo] { r =>
      r.getForHousehold(HouseholdId.Default)
        .flatMap(s => r.update(HouseholdId.Default, s.copy(sharedDeviceIdleMinutes = m)))
    }

  // 13:00–15:00 every day covers the fixture's Monday afternoon.
  private def homeworkSchedule(pid: ProfileId) =
    ZIO.serviceWithZIO[NamedScheduleRepo] { nsr =>
      nsr
        .create(
          "Homework",
          None,
          List(
            ScheduleWindow(
              List("mon", "tue", "wed", "thu", "fri", "sat", "sun"),
              LocalTime.of(13, 0),
              LocalTime.of(15, 0),
              java.time.ZoneId.of("UTC"),
            ),
          ),
        )
        .flatMap(sid => nsr.setProfileBlockSchedules(pid, List(sid)))
    }

  /** The check-in on `OtherMac` by kid2, who matches no trigger, is still open and still held. */
  private def anchorHeld(fx: Fx) =
    for {
      hist <- history(OtherMac)
      snap <- snapshotDevice(fx, OtherMac)
    } yield hist == List(("check_in", fx.kid2.value, None, None)) && heldBy(snap, fx.kid2)

  def spec = suite("Shared-device auto-checkout (#2849)")(
    test("time_limit: the holder's daily limit is used up on the shared device itself") {
      for {
        fx       <- setup(at(13, 0))
        _        <- ZIO.serviceWithZIO[TimeLimitRepo](_.upsert(fx.kid1, 30))
        _        <- checkIn(fx, SharedMac, fx.kid1)
        _        <- checkIn(fx, OtherMac, fx.kid2)
        // 40 minutes on the shared device, then the tick at 13:45: within the idle threshold.
        _        <- usage(fx, SharedMac, at(13, 0), 40)
        _        <- usage(fx, OtherMac, at(13, 0), 45)
        _        <- fx.clock.setTo(at(13, 45))
        before   <- usedMinutes(fx, fx.kid1)
        _        <- tick(fx)
        hist     <- history(SharedMac)
        snap     <- snapshotDevice(fx, SharedMac)
        anchor   <- anchorHeld(fx)
        released <- fx.released.get
        // Presence on the device after the release belongs to nobody.
        _        <- usage(fx, SharedMac, at(13, 45), 10)
        _        <- fx.clock.setTo(at(13, 59))
        after    <- usedMinutes(fx, fx.kid1)
      } yield assertTrue(
        before == 40,
        hist == List(("check_in", fx.kid1.value, Some("time_limit"), Some(utc(at(13, 45))))),
        isCheckedOut(snap),
        anchor,
        released == List(HouseholdId.Default),
        after == 40,
      )
    },
    test("schedule: the holder enters a schedule block") {
      for {
        fx     <- setup(at(12, 50))
        _      <- checkIn(fx, SharedMac, fx.kid1)
        _      <- checkIn(fx, OtherMac, fx.kid2)
        _      <- usage(fx, SharedMac, at(12, 50), 10)
        _      <- usage(fx, OtherMac, at(12, 50), 10)
        // Before the window opens the holder is not blocked: nothing is released.
        _      <- fx.clock.setTo(at(12, 59))
        _      <- tick(fx)
        open   <- history(SharedMac)
        _      <- homeworkSchedule(fx.kid1)
        _      <- fx.clock.setTo(at(13, 0, 5))
        _      <- tick(fx)
        hist   <- history(SharedMac)
        snap   <- snapshotDevice(fx, SharedMac)
        anchor <- anchorHeld(fx)
      } yield assertTrue(
        open == List(("check_in", fx.kid1.value, None, None)),
        hist == List(("check_in", fx.kid1.value, Some("schedule"), Some(utc(at(13, 0, 5))))),
        isCheckedOut(snap),
        anchor,
      )
    },
    test("paused: the holder's profile is paused") {
      for {
        fx     <- setup(at(14, 0))
        _      <- checkIn(fx, SharedMac, fx.kid1)
        _      <- checkIn(fx, OtherMac, fx.kid2)
        _      <- ZIO.serviceWithZIO[ProfileRepo](_.setPaused(fx.kid1, true))
        _      <- fx.clock.setTo(at(14, 1))
        _      <- tick(fx)
        hist   <- history(SharedMac)
        snap   <- snapshotDevice(fx, SharedMac)
        anchor <- anchorHeld(fx)
        _      <- usage(fx, SharedMac, at(14, 1), 10)
        _      <- fx.clock.setTo(at(14, 20))
        after  <- usedMinutes(fx, fx.kid1)
      } yield assertTrue(
        hist == List(("check_in", fx.kid1.value, Some("paused"), Some(utc(at(14, 1))))),
        isCheckedOut(snap),
        anchor,
        after == 0,
      )
    },
    test("idle: no engaged presence for the threshold; heartbeats do not keep it alive") {
      for {
        fx     <- setup(at(14, 0))
        _      <- setIdleMinutes(10)
        _      <- checkIn(fx, SharedMac, fx.kid1)
        _      <- checkIn(fx, OtherMac, fx.kid2)
        // Engaged until 14:10, then only a captive-portal probe (a heartbeat by host) at 14:15.
        _      <- usage(fx, SharedMac, at(14, 0), 10)
        _      <- usage(fx, SharedMac, at(14, 15), 5, host = "captive.apple.com")
        _      <- usage(fx, OtherMac, at(14, 0), 25)
        // 9m59s after the last engaged presence: still held.
        _      <- fx.clock.setTo(at(14, 19, 59))
        _      <- tick(fx)
        held   <- history(SharedMac)
        _      <- fx.clock.setTo(at(14, 20))
        _      <- tick(fx)
        hist   <- history(SharedMac)
        snap   <- snapshotDevice(fx, SharedMac)
        anchor <- anchorHeld(fx)
      } yield assertTrue(
        held == List(("check_in", fx.kid1.value, None, None)),
        hist == List(("check_in", fx.kid1.value, Some("idle"), Some(utc(at(14, 20))))),
        isCheckedOut(snap),
        anchor,
      )
    },
    test("idle: a check-in with no presence at all is idle from its start") {
      for {
        fx     <- setup(at(14, 0))
        _      <- checkIn(fx, SharedMac, fx.kid1)
        _      <- checkIn(fx, OtherMac, fx.kid2)
        _      <- usage(fx, OtherMac, at(14, 0), 15)
        _      <- fx.clock.setTo(at(14, 15))
        _      <- tick(fx)
        hist   <- history(SharedMac)
        anchor <- anchorHeld(fx)
      } yield assertTrue(
        hist == List(("check_in", fx.kid1.value, Some("idle"), Some(utc(at(14, 15))))),
        anchor,
      )
    },
    test("day_reset: closed at the reset instant, not the tick; post-reset usage is nobody's") {
      for {
        fx       <- setup(at(22, 0))
        hsr      <- ZIO.service[HouseholdSettingsRepo]
        // A 03:00 reset, so the reset instant is not a calendar midnight.
        settings <- hsr.getForHousehold(HouseholdId.Default)
        _ <- hsr.update(HouseholdId.Default, settings.copy(dailyResetTime = LocalTime.of(3, 0)))
        _ <- checkIn(fx, SharedMac, fx.kid1)
        _ <- usage(fx, SharedMac, at(22, 0), 60)
        tuesday = Monday.plusDays(1)
        // The device is still in use across the reset, and the tick lands 40 s after it.
        _       <- usage(fx, SharedMac, tuesday.atTime(2, 50), 20)
        _       <- fx.clock.setTo(tuesday.atTime(3, 0, 40))
        // kid2 checked in after the reset and matches nothing.
        _       <- checkIn(fx, OtherMac, fx.kid2)
        _       <- tick(fx)
        hist    <- history(SharedMac)
        snap    <- snapshotDevice(fx, SharedMac)
        anchor  <- anchorHeld(fx)
        _       <- fx.clock.setTo(tuesday.atTime(4, 0))
        tueMins <- usedMinutes(fx, fx.kid1)
      } yield assertTrue(
        hist == List(
          ("check_in", fx.kid1.value, Some("day_reset"), Some(utc(tuesday.atTime(3, 0)))),
        ),
        isCheckedOut(snap),
        anchor,
        // The 03:00–03:10 reports fall after the reset: credited to nobody.
        tueMins == 0,
      )
    },
    test("precedence: day_reset over paused over schedule over time_limit over idle") {
      for {
        fx <- setup(at(14, 0))
        _  <- ZIO.serviceWithZIO[TimeLimitRepo](_.upsert(fx.kid1, 30))
        // Kid1's own phone used an hour this morning.
        _  <- ZIO.serviceWithZIO[DeviceRepo](_.upsert(PlainMac, "kid1-phone", Some(fx.kid1), ""))
        _  <- usage(fx, PlainMac, at(10, 0), 60)
        _  <- homeworkSchedule(fx.kid1)
        _  <- ZIO.serviceWithZIO[ProfileRepo](_.setPaused(fx.kid1, true))
        _  <- checkIn(fx, SharedMac, fx.kid1)
        // Paused, in a schedule block, out of time and idle all at once.
        _  <- fx.clock.setTo(at(14, 30))
        _  <- tick(fx)
        p  <- history(SharedMac)
        // Unpaused: schedule, time limit and idle remain.
        _  <- ZIO.serviceWithZIO[ProfileRepo](_.setPaused(fx.kid1, false))
        _  <- checkIn(fx, SharedMac, fx.kid1)
        _  <- fx.clock.setTo(at(14, 50))
        _  <- tick(fx)
        s  <- history(SharedMac)
        // After the window: time limit and idle remain.
        _  <- checkIn(fx, SharedMac, fx.kid1)
        _  <- fx.clock.setTo(at(15, 30))
        _  <- tick(fx)
        t  <- history(SharedMac)
        // Checked in at 23:00 Monday, ticked after midnight: day_reset beats idle.
        _  <- fx.clock.setTo(at(23, 0))
        _  <- checkIn(fx, SharedMac, fx.kid1)
        _  <- fx.clock.setTo(Monday.plusDays(1).atTime(0, 0, 5))
        _  <- tick(fx)
        d  <- history(SharedMac)
      } yield assertTrue(
        p.map(_._3) == List(Some("paused")),
        s.map(_._3) == List(Some("paused"), Some("schedule")),
        t.map(_._3) == List(Some("paused"), Some("schedule"), Some("time_limit")),
        d.map(_._3) == List(
          Some("paused"),
          Some("schedule"),
          Some("time_limit"),
          Some("day_reset"),
        ),
        d.lastOption.flatMap(_._4).contains(utc(Monday.plusDays(1).atTime(0, 0))),
      )
    },
    test("an unrestricted, active holder stays checked in and nothing is notified") {
      for {
        fx       <- setup(at(14, 0))
        _        <- checkIn(fx, OtherMac, fx.kid2)
        _        <- usage(fx, OtherMac, at(14, 0), 30)
        _        <- fx.clock.setTo(at(14, 30))
        _        <- tick(fx)
        anchor   <- anchorHeld(fx)
        released <- fx.released.get
      } yield assertTrue(anchor, released.isEmpty)
    },
  ) @@ TestAspect.sequential
}
