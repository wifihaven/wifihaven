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
 * #1379: per-app schedules collapse, server-side, into the EXISTING `extraAllowed` / `extraBlocked`
 * snapshot fields — no wire/router change. These feature tests run the full snapshot build over an
 * embedded Postgres and pin the design's precedence table (docs/design/per-app-schedules.md §5):
 *
 *   - row 1: an `allowed_during` window carves an app's host into `extraAllowed` during profile
 *     downtime (beats the whole-MAC Schedule block, #421);
 *   - rows 9a–9c: the carve yields to the daily time limit unless the app is `exemptFromDaily` —
 *     and the gate keys off the raw cap, not the (higher-precedence) blockReason;
 *   - row 3: a `blocked_during` window drops a base-Allowed app's host while active;
 *   - the deterministic ETag flips across a window edge (pull-based freshness, §6).
 *
 * The result is carried purely in `extraAllowed` / `extraBlocked`; no `PolicySnapshot` field exists
 * for per-app schedules.
 */
object PolicySnapshotPerAppScheduleSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate
  private val UTC     = ZoneId.of("UTC")
  private val allDays = List("mon", "tue", "wed", "thu", "fri", "sat", "sun")

  private def makePsAt(dt: LocalDateTime) =
    for {
      pr     <- ZIO.service[ProfileRepo]
      sr     <- ZIO.service[ScheduleRepo]
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
      sr,
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
   * Seed a single-host app assigned to `pid` with the given base mode, then attach one per-app
   * schedule rule (a fresh named schedule with `window`, in `mode`). Returns the app host.
   */
  private def seedScheduledApp(
      ar: AppRepo,
      nsr: NamedScheduleRepo,
      pid: ProfileId,
      name: String,
      host: String,
      baseMode: AppMode,
      ruleMode: AppScheduleMode,
      window: ScheduleWindow,
      exemptFromDaily: Boolean = true,
      dailyMinutes: Option[Int] = None,
  ): Task[String] =
    for {
      appId    <- ar.create(name, name, None, None)
      _        <- ar.setHosts(appId, List(Hostname.unsafe(host)))
      assignId <- ar.upsertAssignment(appId, pid, baseMode, dailyMinutes, exemptFromDaily)
      sid      <- nsr.create(s"$name-sched", None, List(window))
      _        <- ar.setScheduleRules(assignId, List((sid, ruleMode)))
    } yield host

  private def kidsBlockReason(snap: PolicySnapshot, pid: ProfileId): Option[String] =
    snap.profiles(pid).rules.blockReason.map(MacBlockReason.asString)

  def spec = suite("PolicySnapshot — per-app schedules (#1379)")(
    test("row 1: allowed_during window carves app host into extraAllowed during bedtime downtime") {
      // Kids carries a legacy bedtime 21:00–07:00 schedule → whole-MAC Schedule block at 21:30.
      // The base-Blocked app's allowed_during overnight window is active then → host reachable.
      val bedtimeWin = ScheduleWindow(allDays, LocalTime.of(21, 0), LocalTime.of(7, 0), UTC)
      for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        sr   <- ZIO.service[ScheduleRepo]
        ar   <- ZIO.service[AppRepo]
        nsr  <- ZIO.service[NamedScheduleRepo]
        kid  <- TestLayers.seedKidsProfile(pr, sr)
        host <- seedScheduledApp(
          ar,
          nsr,
          kid,
          "Edu",
          "edu.example.com",
          AppMode.Blocked,
          AppScheduleMode.AllowedDuring,
          bedtimeWin,
        )
        svc  <- makePsAt(TestClock.bedtime)
        snap <- svc.snapshot
        rules = snap.profiles(kid).rules
        ea    = rules.extraAllowed.map(_.value).toSet
        eb    = rules.extraBlocked.map(_.value).toSet
      } yield assertTrue(rules.blocked) &&
        assertTrue(kidsBlockReason(snap, kid).contains("Schedule")) &&
        assertTrue(ea.contains(host)) &&
        assertTrue(!eb.contains(host))
    },
    test("row 1 (control): outside the window the base-Blocked app stays blocked") {
      // Same app, evaluated at a school-day afternoon — window inactive, no whole-MAC block.
      val bedtimeWin = ScheduleWindow(allDays, LocalTime.of(21, 0), LocalTime.of(7, 0), UTC)
      for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        sr   <- ZIO.service[ScheduleRepo]
        ar   <- ZIO.service[AppRepo]
        nsr  <- ZIO.service[NamedScheduleRepo]
        kid  <- TestLayers.seedKidsProfile(pr, sr)
        host <- seedScheduledApp(
          ar,
          nsr,
          kid,
          "Edu",
          "edu.example.com",
          AppMode.Blocked,
          AppScheduleMode.AllowedDuring,
          bedtimeWin,
        )
        svc  <- makePsAt(TestClock.schoolDayAfternoon)
        snap <- svc.snapshot
        rules = snap.profiles(kid).rules
        ea    = rules.extraAllowed.map(_.value).toSet
        eb    = rules.extraBlocked.map(_.value).toSet
      } yield assertTrue(eb.contains(host)) && assertTrue(!ea.contains(host))
    },
    test("9a: exempt app over the daily cap → allowed_during still carves it into extraAllowed") {
      val mac        = "aa:bb:cc:dd:e9:0a"
      val daytimeWin = ScheduleWindow(allDays, LocalTime.of(8, 0), LocalTime.of(18, 0), UTC)
      for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        sr   <- ZIO.service[ScheduleRepo]
        dr   <- ZIO.service[DeviceRepo]
        tlr  <- ZIO.service[TimeLimitRepo]
        ar   <- ZIO.service[AppRepo]
        nsr  <- ZIO.service[NamedScheduleRepo]
        kid  <- TestLayers.seedKidsProfile(pr, sr)
        _    <- tlr.upsert(kid, 30)
        _    <- TestLayers.seedDevice(dr, mac, "kid-ipad", kid)
        host <- seedScheduledApp(
          ar,
          nsr,
          kid,
          "Edu",
          "edu.example.com",
          AppMode.Blocked,
          AppScheduleMode.AllowedDuring,
          daytimeWin,
          exemptFromDaily = true,
        )
        rid  <- seedRouterRow
        _   <- seedTraffic(rid, mac, "cnn.com", LocalDate.of(2025, 1, 6), 35) // exhausts 30-min cap
        svc <- makePsAt(TestClock.schoolDayAfternoon)
        snap <- svc.snapshot
        rules = snap.profiles(kid).rules
        ea    = rules.extraAllowed.map(_.value).toSet
      } yield assertTrue(rules.blocked) &&
        assertTrue(kidsBlockReason(snap, kid).contains("TimeLimit")) &&
        assertTrue(ea.contains(host))
    },
    test(
      "9b: NON-exempt app over the daily cap → allowed_during does NOT carve (cap still bites)",
    ) {
      val mac        = "aa:bb:cc:dd:e9:0b"
      val daytimeWin = ScheduleWindow(allDays, LocalTime.of(8, 0), LocalTime.of(18, 0), UTC)
      for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        sr   <- ZIO.service[ScheduleRepo]
        dr   <- ZIO.service[DeviceRepo]
        tlr  <- ZIO.service[TimeLimitRepo]
        ar   <- ZIO.service[AppRepo]
        nsr  <- ZIO.service[NamedScheduleRepo]
        kid  <- TestLayers.seedKidsProfile(pr, sr)
        _    <- tlr.upsert(kid, 30)
        _    <- TestLayers.seedDevice(dr, mac, "kid-ipad", kid)
        host <- seedScheduledApp(
          ar,
          nsr,
          kid,
          "Game",
          "game.example.com",
          AppMode.Blocked,
          AppScheduleMode.AllowedDuring,
          daytimeWin,
          exemptFromDaily = false,
        )
        rid  <- seedRouterRow
        _    <- seedTraffic(rid, mac, "cnn.com", LocalDate.of(2025, 1, 6), 35)
        svc  <- makePsAt(TestClock.schoolDayAfternoon)
        snap <- svc.snapshot
        rules = snap.profiles(kid).rules
        ea    = rules.extraAllowed.map(_.value).toSet
        eb    = rules.extraBlocked.map(_.value).toSet
      } yield assertTrue(rules.blocked) &&
        assertTrue(kidsBlockReason(snap, kid).contains("TimeLimit")) &&
        assertTrue(!ea.contains(host)) &&
        assertTrue(!eb.contains(host)) // not carved AND not in eb — left to the whole-MAC block
    },
    test(
      "9c: coincident bedtime downtime + cap exhausted, NON-exempt → not carved though reason=Schedule",
    ) {
      // At bedtime the whole-MAC reason is Schedule (higher precedence than TimeLimit), but the
      // raw daily cap IS exhausted — the carve gate keys off the cap, not the reason, so a
      // non-exempt allowed_during app is still withheld.
      val mac        = "aa:bb:cc:dd:e9:0c"
      val bedtimeWin = ScheduleWindow(allDays, LocalTime.of(21, 0), LocalTime.of(7, 0), UTC)
      for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        sr   <- ZIO.service[ScheduleRepo]
        dr   <- ZIO.service[DeviceRepo]
        tlr  <- ZIO.service[TimeLimitRepo]
        ar   <- ZIO.service[AppRepo]
        nsr  <- ZIO.service[NamedScheduleRepo]
        kid  <- TestLayers.seedKidsProfile(pr, sr)
        _    <- tlr.upsert(kid, 30)
        _    <- TestLayers.seedDevice(dr, mac, "kid-ipad", kid)
        host <- seedScheduledApp(
          ar,
          nsr,
          kid,
          "Game",
          "game.example.com",
          AppMode.Blocked,
          AppScheduleMode.AllowedDuring,
          bedtimeWin,
          exemptFromDaily = false,
        )
        rid  <- seedRouterRow
        _    <- seedTraffic(rid, mac, "cnn.com", LocalDate.of(2025, 1, 6), 35)
        svc  <- makePsAt(TestClock.bedtime)
        snap <- svc.snapshot
        rules = snap.profiles(kid).rules
        ea    = rules.extraAllowed.map(_.value).toSet
      } yield assertTrue(rules.blocked) &&
        assertTrue(kidsBlockReason(snap, kid).contains("Schedule")) &&
        assertTrue(!ea.contains(host))
    },
    test("row 3: blocked_during window drops a base-Allowed app's host while active") {
      // No whole-MAC block (school-day afternoon); the app is base-Allowed but its blocked_during
      // window covers the afternoon → host goes to extraBlocked.
      val homeworkWin = ScheduleWindow(allDays, LocalTime.of(13, 0), LocalTime.of(16, 0), UTC)
      for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        sr   <- ZIO.service[ScheduleRepo]
        ar   <- ZIO.service[AppRepo]
        nsr  <- ZIO.service[NamedScheduleRepo]
        kid  <- TestLayers.seedKidsProfile(pr, sr)
        host <- seedScheduledApp(
          ar,
          nsr,
          kid,
          "Game",
          "game.example.com",
          AppMode.Allowed,
          AppScheduleMode.BlockedDuring,
          homeworkWin,
        )
        svc  <- makePsAt(TestClock.schoolDayAfternoon) // Monday 14:00 — inside 13:00–16:00
        snap <- svc.snapshot
        rules = snap.profiles(kid).rules
        ea    = rules.extraAllowed.map(_.value).toSet
        eb    = rules.extraBlocked.map(_.value).toSet
      } yield assertTrue(!rules.blocked) &&
        assertTrue(eb.contains(host)) &&
        assertTrue(!ea.contains(host))
    },
    test("ETag flips across a per-app window edge (15:00 exclusive end)") {
      // A base-Blocked app with an allowed_during 08:00–15:00 window. At 14:59 the host is carved
      // into extraAllowed; at 15:00 (end exclusive) it is not → the deterministic ETag must change.
      val win = ScheduleWindow(allDays, LocalTime.of(8, 0), LocalTime.of(15, 0), UTC)
      for {
        _   <- cleanDb
        pr  <- ZIO.service[ProfileRepo]
        sr  <- ZIO.service[ScheduleRepo]
        ar  <- ZIO.service[AppRepo]
        nsr <- ZIO.service[NamedScheduleRepo]
        kid <- TestLayers.seedKidsProfile(pr, sr)
        _   <- seedScheduledApp(
          ar,
          nsr,
          kid,
          "Edu",
          "edu.example.com",
          AppMode.Blocked,
          AppScheduleMode.AllowedDuring,
          win,
        )
        inWin = LocalDateTime.of(2025, 1, 6, 14, 59, 0)
        atEnd = LocalDateTime.of(2025, 1, 6, 15, 0, 0)
        svcIn   <- makePsAt(inWin)
        svcEnd  <- makePsAt(atEnd)
        snapIn  <- svcIn.snapshot
        snapEnd <- svcEnd.snapshot
      } yield assertTrue(snapIn.etag != snapEnd.etag) &&
        assertTrue(
          snapIn.profiles(kid).rules.extraAllowed.map(_.value).contains("edu.example.com"),
        ) &&
        assertTrue(
          !snapEnd.profiles(kid).rules.extraAllowed.map(_.value).contains("edu.example.com"),
        )
    },
  ) @@ TestAspect.sequential
}
