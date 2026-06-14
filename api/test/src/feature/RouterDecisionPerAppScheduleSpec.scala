package wifihaven.api.feature

import wifihaven.api.db.*
import wifihaven.api.policy.*
import wifihaven.shared.*
import wifihaven.shared.Clock.TestClock
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.test.*

import java.time.{LocalDate, LocalDateTime, LocalTime, ZoneId, ZoneOffset}

/**
 * #1379 (§4.2): the per-host `/decision` fallback must mirror the snapshot's per-app
 * effective-disposition + cap gate, so a router that consults the endpoint agrees with what
 * nftables enforces. An active `allowed_during` window makes the app's host `extra_allowed` (beats
 * Paused/Schedule), unless the app is non-exempt and the daily cap is exhausted — then it yields to
 * the `time_limit` block. A `blocked_during` window drops a base-Allowed host.
 */
object RouterDecisionPerAppScheduleSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate
  private val UTC     = ZoneId.of("UTC")
  private val allDays = List("mon", "tue", "wed", "thu", "fri", "sat", "sun")
  private val mac     = "aa:bb:cc:de:c1:de"

  private def makePsAt(dt: LocalDateTime) =
    for {
      pr     <- ZIO.service[ProfileRepo]
      hsr    <- ZIO.service[HouseholdSettingsRepo]
      tlr    <- ZIO.service[TimeLimitRepo]
      atlr   <- ZIO.service[AppTimeLimitRepo]
      dr     <- ZIO.service[DeviceRepo]
      blr    <- ZIO.service[BlocklistRepo]
      trRepo <- ZIO.service[TrafficReportRepo]
      er     <- ZIO.service[TimeExtensionRepo]
      ar     <- ZIO.service[AppRepo]
      nsr    <- ZIO.service[NamedScheduleRepo]
      ref    <- Ref.make(dt)
      clk = new Clock.TestClock(ref)
    } yield PolicyServiceLive(
      pr,
      hsr,
      tlr,
      atlr,
      dr,
      blr,
      trRepo,
      er,
      ar,
      clk,
      namedScheduleRepo = nsr,
    ): PolicyService

  private def seedRouterRow: ZIO[RouterRepo, Throwable, RouterId] =
    ZIO.serviceWithZIO[RouterRepo](_.create("gw-seed", Sha256Hex.unsafe("d" * 64)))

  private def seedTraffic(
      routerId: RouterId,
      hostname: String,
      date: LocalDate,
      minutes: Int,
  ): ZIO[TrafficReportRepo, Throwable, Unit] =
    ZIO.serviceWithZIO[TrafficReportRepo] { tr =>
      val today0  = date.atStartOfDay(ZoneOffset.UTC).toInstant
      val inserts = (0 until minutes / 5).map { i =>
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

  private def seedScheduledApp(
      ar: AppRepo,
      nsr: NamedScheduleRepo,
      pid: ProfileId,
      host: String,
      baseMode: AppMode,
      ruleMode: AppScheduleMode,
      window: ScheduleWindow,
      exemptFromDaily: Boolean,
  ): Task[Unit] =
    for {
      appId    <- ar.create("App-" + host, host, None, None)
      _        <- ar.setHosts(appId, List(Hostname.unsafe(host)))
      assignId <- ar.upsertAssignment(appId, pid, baseMode, None, exemptFromDaily)
      sid      <- nsr.create("sched-" + host, None, List(window))
      _        <- ar.setScheduleRules(assignId, List((sid, ruleMode)))
    } yield ()

  def spec = suite("decide — per-app schedules (#1379 §4.2)")(
    test("allowed_during active during bedtime → Allow extra_allowed (beats Schedule block)") {
      val bedtimeWin = ScheduleWindow(allDays, LocalTime.of(21, 0), LocalTime.of(7, 0), UTC)
      for {
        _   <- cleanDb
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        ar  <- ZIO.service[AppRepo]
        nsr <- ZIO.service[NamedScheduleRepo]
        kid <- TestLayers.seedKidsProfile(pr)
        _   <- TestLayers.seedDevice(dr, mac, "kid-ipad", kid)
        _   <- seedScheduledApp(
          ar,
          nsr,
          kid,
          "edu.example.com",
          AppMode.Blocked,
          AppScheduleMode.AllowedDuring,
          bedtimeWin,
          exemptFromDaily = true,
        )
        ps  <- makePsAt(TestClock.bedtime)
        res <- ps.decide(mac, "edu.example.com")
      } yield assertTrue(res.decision == ConnectionDecision.Allow) &&
        assertTrue(res.reason == "extra_allowed")
    },
    test("blocked_during active over base-Allowed → Block extra_blocked") {
      val homeworkWin = ScheduleWindow(allDays, LocalTime.of(13, 0), LocalTime.of(16, 0), UTC)
      for {
        _   <- cleanDb
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        ar  <- ZIO.service[AppRepo]
        nsr <- ZIO.service[NamedScheduleRepo]
        kid <- TestLayers.seedKidsProfile(pr)
        _   <- TestLayers.seedDevice(dr, mac, "kid-ipad", kid)
        _   <- seedScheduledApp(
          ar,
          nsr,
          kid,
          "game.example.com",
          AppMode.Allowed,
          AppScheduleMode.BlockedDuring,
          homeworkWin,
          exemptFromDaily = true,
        )
        ps  <- makePsAt(TestClock.schoolDayAfternoon) // 14:00, inside 13:00–16:00
        res <- ps.decide(mac, "game.example.com")
      } yield assertTrue(res.decision == ConnectionDecision.Block) &&
        assertTrue(res.reason == "extra_blocked")
    },
    test("NON-exempt allowed_during + cap exhausted → yields to time_limit (Block)") {
      val daytimeWin = ScheduleWindow(allDays, LocalTime.of(8, 0), LocalTime.of(18, 0), UTC)
      for {
        _   <- cleanDb
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        tlr <- ZIO.service[TimeLimitRepo]
        ar  <- ZIO.service[AppRepo]
        nsr <- ZIO.service[NamedScheduleRepo]
        kid <- TestLayers.seedKidsProfile(pr)
        _   <- tlr.upsert(kid, 30)
        _   <- TestLayers.seedDevice(dr, mac, "kid-ipad", kid)
        _   <- seedScheduledApp(
          ar,
          nsr,
          kid,
          "game.example.com",
          AppMode.Blocked,
          AppScheduleMode.AllowedDuring,
          daytimeWin,
          exemptFromDaily = false,
        )
        rid <- seedRouterRow
        _   <- seedTraffic(rid, "cnn.com", LocalDate.of(2025, 1, 6), 35)
        ps  <- makePsAt(TestClock.schoolDayAfternoon)
        res <- ps.decide(mac, "game.example.com")
      } yield assertTrue(res.decision == ConnectionDecision.Block) &&
        assertTrue(res.reason == "time_limit")
    },
    test("EXEMPT allowed_during + cap exhausted → still Allow extra_allowed") {
      val daytimeWin = ScheduleWindow(allDays, LocalTime.of(8, 0), LocalTime.of(18, 0), UTC)
      for {
        _   <- cleanDb
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        tlr <- ZIO.service[TimeLimitRepo]
        ar  <- ZIO.service[AppRepo]
        nsr <- ZIO.service[NamedScheduleRepo]
        kid <- TestLayers.seedKidsProfile(pr)
        _   <- tlr.upsert(kid, 30)
        _   <- TestLayers.seedDevice(dr, mac, "kid-ipad", kid)
        _   <- seedScheduledApp(
          ar,
          nsr,
          kid,
          "edu.example.com",
          AppMode.Blocked,
          AppScheduleMode.AllowedDuring,
          daytimeWin,
          exemptFromDaily = true,
        )
        rid <- seedRouterRow
        _   <- seedTraffic(rid, "cnn.com", LocalDate.of(2025, 1, 6), 35)
        ps  <- makePsAt(TestClock.schoolDayAfternoon)
        res <- ps.decide(mac, "edu.example.com")
      } yield assertTrue(res.decision == ConnectionDecision.Allow) &&
        assertTrue(res.reason == "extra_allowed")
    },
  ) @@ TestAspect.sequential
}
