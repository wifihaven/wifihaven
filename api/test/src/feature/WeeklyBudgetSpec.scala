package wifihaven.api.feature

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import cats.implicits.*
import wifihaven.api.JwtConfig
import wifihaven.api.auth.AuthServiceLive
import wifihaven.api.routes.{LatePresenceMeter, RouterAuth, RouterIngestService, TimeRoutes}
import wifihaven.api.db.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.api.policy.*
import wifihaven.api.usage.{AppUsedRollupServiceLive, TimeUsedRollupJob}
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.interop.catz.*
import zio.test.*
import zio.http.*
import zio.json.*

import java.time.{Instant, LocalDate, LocalDateTime, LocalTime, ZoneId, ZoneOffset}

/**
 * #2932 (epic #2930, design `docs/design/weekly-limits-and-app-groups.md` §6, §12): week-to-date
 * usage per budget (an app's own, or an attached group's), counted but not enforced.
 *
 * Fixture week: Monday 2025-01-06 .. Sunday 2025-01-12 in a UTC household that resets at midnight
 * and starts its week on Monday. "Now" is Thursday 2025-01-09 16:00 unless a test says otherwise,
 * so Monday to Wednesday are settled past days and Thursday is today.
 *
 * Every test that asserts an absence (a profile, a day or a host that must count zero) also asserts
 * a non-zero figure from the same rig, so a rig with no traffic cannot pass.
 */
object WeeklyBudgetSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Transactor[Task] & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(LocalDateTime.of(2025, 1, 9, 16, 0))

  private val hh  = HouseholdId.Default
  private val utc = ZoneId.of("UTC")

  private val mon = LocalDate.of(2025, 1, 6)
  private val tue = mon.plusDays(1)
  private val wed = mon.plusDays(2)
  private val thu = mon.plusDays(3)
  private val fri = mon.plusDays(4)

  private def at(d: LocalDate, h: Int, m: Int = 0): Instant =
    LocalDateTime.of(d, LocalTime.of(h, m)).toInstant(ZoneOffset.UTC)

  private val nowThu = at(thu, 16)

  private val macA  = MacAddress.unsafe("aa:bb:cc:29:32:01")
  private val macB  = MacAddress.unsafe("aa:bb:cc:29:32:02")
  private val macC  = MacAddress.unsafe("aa:bb:cc:29:32:03")
  private val macSh = MacAddress.unsafe("aa:bb:cc:29:32:04")

  // ── rig ────────────────────────────────────────────────────────────────────────────────────

  final case class Rig(settings: HouseholdSettings, router: RouterId)

  private def rig(
      tz: ZoneId = utc,
      reset: LocalTime = LocalTime.MIDNIGHT,
      weekStartDay: Int = 1,
  ) =
    for {
      _   <- TestDatabase.cleanAndMigrate
      hsr <- ZIO.service[HouseholdSettingsRepo]
      cur <- hsr.getForHousehold(hh)
      settings = cur.copy(dailyResetTz = tz, dailyResetTime = reset, weekStartDay = weekStartDay)
      _   <- hsr.update(hh, settings)
      rid <- ZIO.serviceWithZIO[RouterRepo](_.create("gw-2932", Sha256Hex.unsafe("w" * 64)))
    } yield Rig(settings, rid)

  // `minutes` of consecutive, fully active 5-minute report periods on `host` starting at `start`,
  // each dated by the household-local day its period starts in (as ingest dates them).
  private def traffic(
      r: Rig,
      mac: MacAddress,
      host: String,
      start: Instant,
      minutes: Int,
  ): ZIO[TrafficReportRepo, Throwable, Unit] =
    ZIO.serviceWithZIO[TrafficReportRepo] { tr =>
      tr.insertBatch((0 until minutes / 5).map { i =>
        val s = start.plusSeconds(i * 300L)
        TrafficReportInsert(
          r.router,
          mac,
          None,
          HostId.Fqdn(Hostname.unsafe(host)),
          PolicyService.householdLocalDate(s, r.settings),
          s,
          s.plusSeconds(300),
          300,
          500_000L,
          500_000L,
        )
      }.toList)
        .unit
    }

  private def app(slug: String, hosts: (String, Boolean)*): ZIO[AppRepo, Throwable, AppId] =
    ZIO.serviceWithZIO[AppRepo] { ar =>
      for {
        id <- ar.create(slug, slug, None, None)
        _  <- ar.setHostEntries(id, hosts.toList.map((h, s) => AppHostEntry(Hostname.unsafe(h), s)))
      } yield id
    }

  private def app1(host: String): ZIO[AppRepo, Throwable, AppId] = app(host, host -> false)

  // The AppRepo the layer provides dates its settled-usage invalidation by the fixture clock
  // (`TestDatabase.FixtureAssignmentTime`, year 2000); writes that must invalidate THIS week go
  // through one dated at `nowThu`.
  private def appRepoAt(now: Instant): RIO[Transactor[Task], AppRepo] =
    for {
      xa <- ZIO.service[Transactor[Task]]
      c  <- Clock.TestClock.makeWithControl(LocalDateTime.ofInstant(now, ZoneOffset.UTC))
    } yield new AppRepoLive(xa, c._1)

  private def assign(
      a: AppId,
      p: ProfileId,
      mode: AppMode = AppMode.TimeLimited,
      daily: Option[Int] = None,
      exempt: Boolean = false,
  ): RIO[Transactor[Task], Unit] =
    appRepoAt(nowThu).flatMap(_.upsertAssignment(a, p, mode, daily, exempt).unit)

  private def setWeekly(a: AppId, p: ProfileId, weekly: Int): RIO[Transactor[Task], Unit] =
    ZIO.serviceWithZIO[Transactor[Task]](xa =>
      sql"UPDATE app_policy_assignments SET weekly_minutes = $weekly WHERE app_id = $a AND profile_id = $p".update.run
        .transact(xa)
        .unit,
    )

  private def device(mac: MacAddress, p: ProfileId): ZIO[DeviceRepo, Throwable, DeviceId] =
    ZIO.serviceWithZIO[DeviceRepo](TestLayers.seedDevice(_, mac.value, mac.value, p))

  // The group / attachment CRUD routes are #2934. These helpers write the V92 rows directly and make
  // the SettledUsage invalidation call those routes make, in the same transaction.
  private def group(name: String, members: AppId*): RIO[Transactor[Task], AppGroupId] =
    ZIO.serviceWithZIO[Transactor[Task]] { xa =>
      (for {
        gid <- sql"INSERT INTO app_groups (household_id, name) VALUES ($hh, $name) RETURNING id"
          .query[AppGroupId]
          .unique
        _   <- members.toList.traverse_(a =>
          sql"INSERT INTO app_group_members (group_id, household_id, app_id) VALUES ($gid, $hh, $a)".update.run,
        )
      } yield gid).transact(xa)
    }

  private def attach(
      p: ProfileId,
      g: AppGroupId,
      daily: Option[Int] = None,
      weekly: Option[Int] = Some(600),
      exempt: Boolean = false,
      now: Instant = nowThu,
  ): RIO[Transactor[Task], Unit] =
    ZIO.serviceWithZIO[Transactor[Task]](xa =>
      (sql"""INSERT INTO profile_app_groups
               (household_id, profile_id, group_id, daily_minutes, weekly_minutes, exempt_from_daily)
             VALUES ($hh, $p, $g, $daily, $weekly, $exempt)""".update.run *>
        SettledUsage.invalidateCurrentWeek(List(p), SettledUsage.Kinds.All, now))
        .transact(xa)
        .unit,
    )

  private def detach(p: ProfileId, g: AppGroupId, now: Instant): RIO[Transactor[Task], Unit] =
    ZIO.serviceWithZIO[Transactor[Task]](xa =>
      (sql"DELETE FROM profile_app_groups WHERE profile_id = $p AND group_id = $g".update.run *>
        SettledUsage.invalidateCurrentWeek(List(p), SettledUsage.Kinds.All, now))
        .transact(xa)
        .unit,
    )

  private def addMember(g: AppGroupId, a: AppId, now: Instant): RIO[Transactor[Task], Unit] =
    ZIO.serviceWithZIO[Transactor[Task]](xa =>
      (sql"INSERT INTO app_group_members (group_id, household_id, app_id) VALUES ($g, $hh, $a)".update.run *>
        SettledUsage.invalidateForGroup(g, now))
        .transact(xa)
        .unit,
    )

  // The REAL service: the real per-app rollup reader and the real settled-usage repo.
  private val svc = for {
    pr  <- ZIO.service[ProfileRepo]
    tlr <- ZIO.service[TimeLimitRepo]
    atl <- ZIO.service[AppTimeLimitRepo]
    dr  <- ZIO.service[DeviceRepo]
    trr <- ZIO.service[TrafficReportRepo]
    er  <- ZIO.service[TimeExtensionRepo]
    ru  <- ZIO.service[TimeUsedRollupRepo]
    nsr <- ZIO.service[NamedScheduleRepo]
    aru <- ZIO.service[AppUsedRollupRepo]
    sur <- ZIO.service[SettledUsageRepo]
  } yield new TimeStatusServiceLive(
    pr,
    tlr,
    atl,
    dr,
    trr,
    er,
    ru,
    nsr,
    new AppUsedRollupServiceLive(pr, dr, atl, trr, aru),
    NoopAmbientHostsRepo,
    sur,
  )

  private def states(r: Rig, now: Instant) =
    svc.flatMap(
      _.dayStateAll(hh, now, PolicyService.householdLocalDate(now, r.settings), r.settings),
    )

  private def appWeek(s: ProfileDayState, a: AppId): Option[Int] =
    s.perApp.find(_.appId == a).flatMap(_.week).map(_.usedMinutes)

  private def appToday(s: ProfileDayState, a: AppId): Option[Int] =
    s.perApp.find(_.appId == a).map(_.usedMinutes)

  private def groupWeek(s: ProfileDayState, g: AppGroupId): Option[Int] =
    s.perGroup.find(_.groupId == g).flatMap(_.week).map(_.usedMinutes)

  private def tick(now: Instant) =
    for {
      ru  <- ZIO.service[TimeUsedRollupRepo]
      aru <- ZIO.service[AppUsedRollupRepo]
      pr  <- ZIO.service[ProfileRepo]
      dr  <- ZIO.service[DeviceRepo]
      atl <- ZIO.service[AppTimeLimitRepo]
      trr <- ZIO.service[TrafficReportRepo]
      hsr <- ZIO.service[HouseholdSettingsRepo]
      s   <- svc
      n   <- TimeUsedRollupJob.oneTickForTest(
        ru,
        aru,
        pr,
        dr,
        atl,
        trr,
        hsr,
        now,
        NoopAmbientHostsRepo,
        settleWeek = s.settleWeek,
      )
    } yield n

  private def settleCount(outcome: String): UIO[Double] =
    zio.metrics.Metric
      .counter("budget_usage_settle_total")
      .tagged("outcome", outcome)
      .value
      .map(_.count)

  private val lateRowCount: UIO[Double] =
    zio.metrics.Metric.counter("budget_late_presence_rows_total").value.map(_.count)

  private def rowCount(table: String, p: ProfileId): RIO[Transactor[Task], Int] =
    ZIO.serviceWithZIO[Transactor[Task]](xa =>
      (fr"SELECT COUNT(*) FROM" ++ Fragment.const(table) ++ fr"WHERE profile_id = $p")
        .query[Int]
        .unique
        .transact(xa),
    )

  // GET `path` through the real TimeRoutes as the default admin, with the routes' clock at `now`.
  private def getJson[A: JsonDecoder](path: String, now: Instant) =
    for {
      clock <- Clock.TestClock
        .makeWithControl(LocalDateTime.ofInstant(now, ZoneOffset.UTC))
        .map(_._1)
      auth  <- ZIO.serviceWith[UserRepo](ur =>
        AuthServiceLive(ur, JwtConfig("test-secret-at-least-32-chars!!x", 1), clock),
      )
      s     <- svc
      dr    <- ZIO.service[DeviceRepo]
      trr   <- ZIO.service[TrafficReportRepo]
      tlr   <- ZIO.service[TimeLimitRepo]
      atl   <- ZIO.service[AppTimeLimitRepo]
      er    <- ZIO.service[TimeExtensionRepo]
      pr    <- ZIO.service[ProfileRepo]
      upr   <- ZIO.service[UserProfileRepo]
      hsr   <- ZIO.service[HouseholdSettingsRepo]
      routes = TimeRoutes.routes(auth, dr, tlr, atl, trr, er, pr, upr, hsr, s, clock)
      token <- auth.login("admin", "changeme").mapError(e => new RuntimeException(s"login: $e"))
      resp  <- routes.runZIO(
        Request
          .get(URL.decode(path).toOption.get)
          .addHeader(Header.Authorization.Bearer(token.token.value)),
      )
      body  <- resp.body.asString
      out   <- ZIO
        .fromEither(body.fromJson[A])
        .mapError(e => new RuntimeException(s"$path -> ${resp.status}: $e: $body"))
    } yield out

  // ── tests ──────────────────────────────────────────────────────────────────────────────────

  def spec = suite("WeeklyBudgetSpec (#2932)")(
    suite("week boundaries (design §5.1)")(
      test("householdWeekStart moves back to the household's week-start day") {
        val s = HouseholdSettings(LocalTime.of(4, 0), utc, HeartbeatFilter.Off)
        assertTrue(
          // Thursday 16:00 -> Monday.
          PolicyService.householdWeekStart(nowThu, s) == mon,
          // Monday 03:59 belongs to Sunday's household day, so to the PREVIOUS week.
          PolicyService.householdWeekStart(at(mon, 3, 59), s) == mon.minusDays(7),
          PolicyService.householdWeekStart(at(mon, 4, 0), s) == mon,
          // A Sunday start: Thursday -> the Sunday before.
          PolicyService.householdWeekStart(nowThu, s.copy(weekStartDay = 7)) == mon.minusDays(1),
          // A Thursday start on a Thursday is that day.
          PolicyService.householdWeekStart(nowThu, s.copy(weekStartDay = 4)) == thu,
        )
      },
      test("14: the week begins at dailyResetTime on the start day, one minute either side") {
        for {
          r     <- rig(reset = LocalTime.of(4, 0))
          pr    <- ZIO.service[ProfileRepo]
          a     <- pr.create("A", Nil)
          y     <- app1("youtube.com")
          _     <- assign(y, a)
          _     <- device(macA, a)
          // Ends at 03:59 Monday: Sunday's household day, the previous week.
          _     <- traffic(r, macA, "youtube.com", at(mon, 3, 44), 15)
          // Starts at 04:01 Monday: this week.
          _     <- traffic(r, macA, "youtube.com", at(mon, 4, 1), 10)
          // Ends at 03:59 next Monday: still this week (its Sunday). Starts 04:01: next week.
          _     <- traffic(r, macA, "youtube.com", at(mon.plusDays(7), 3, 44), 15)
          _     <- traffic(r, macA, "youtube.com", at(mon.plusDays(7), 4, 1), 10)
          thuS  <- states(r, nowThu)
          lastS <- states(r, at(mon.plusDays(7), 3, 59))
          nextS <- states(r, at(mon.plusDays(7), 4, 30))
        } yield assertTrue(
          appWeek(thuS(a), y).contains(10),
          thuS(a).week.map(_.start).contains(mon),
          thuS(a).week.map(_.resetsAt).contains(at(mon.plusDays(7), 4)),
          appWeek(lastS(a), y).contains(25),
          appWeek(nextS(a), y).contains(10),
          nextS(a).week.map(_.start).contains(mon.plusDays(7)),
        )
      },
      test("15: DST weeks are 167 / 169 hours and count a session across the transition night") {
        // (zone, a Monday, the Saturday before that week's transition Sunday, hours in the week)
        val cases = List(
          (ZoneId.of("America/New_York"), LocalDate.of(2025, 3, 3), 167L),
          (ZoneId.of("America/New_York"), LocalDate.of(2025, 10, 27), 169L),
          (ZoneId.of("Europe/Berlin"), LocalDate.of(2025, 3, 24), 167L),
          (ZoneId.of("Europe/Berlin"), LocalDate.of(2025, 10, 20), 169L),
        )
        ZIO
          .foreach(cases) { case (zone, weekMon, hours) =>
            val sat                                     = weekMon.plusDays(5)
            val sun                                     = weekMon.plusDays(6)
            def local(d: LocalDate, h: Int, m: Int = 0) =
              LocalDateTime.of(d, LocalTime.of(h, m)).atZone(zone).toInstant
            for {
              r  <- rig(tz = zone)
              pr <- ZIO.service[ProfileRepo]
              a  <- pr.create("A", Nil)
              y  <- app1("youtube.com")
              _  <- assign(y, a)
              _  <- device(macA, a)
              // 180 real minutes from Saturday 23:30 local, across midnight and the DST change.
              _  <- traffic(r, macA, "youtube.com", local(sat, 23, 30), 180)
              // Monday 00:30 of the next week: not in this week.
              _  <- traffic(r, macA, "youtube.com", local(weekMon.plusDays(7), 0, 30), 20)
              (from, to) = AttributionScope.rangeWindow(weekMon, sun, r.settings)
              // Outside `assertTrue`: its macro cannot render a Java static call.
              weekHours  = java.time.temporal.ChronoUnit.HOURS.between(from, to)
              s  <- states(r, local(sun, 20))
              nx <- states(r, local(weekMon.plusDays(7), 12))
            } yield assertTrue(
              weekHours == hours,
              s(a).week.map(_.start).contains(weekMon),
              s(a).week.map(_.resetsAt).contains(to),
              appWeek(s(a), y).contains(180),
              s(a).week.map(_.usedMinutes).contains(180),
              appWeek(nx(a), y).contains(20),
            )
          }
          .map(_.reduce(_ && _))
      },
      test("16: changing week_start_day mid-week recomputes the current week") {
        for {
          r        <- rig()
          pr       <- ZIO.service[ProfileRepo]
          hsr      <- ZIO.service[HouseholdSettingsRepo]
          a        <- pr.create("A", Nil)
          y        <- app1("youtube.com")
          _        <- assign(y, a)
          _        <- device(macA, a)
          _        <- traffic(r, macA, "youtube.com", at(mon.minusDays(1), 10), 20) // Sunday
          _        <- traffic(r, macA, "youtube.com", at(tue, 10), 15)
          _        <- traffic(r, macA, "youtube.com", at(thu, 10), 10)
          monStart <- states(r, nowThu)
          _        <- hsr.update(hh, r.settings.copy(weekStartDay = 7))
          sunStart <- states(r.copy(settings = r.settings.copy(weekStartDay = 7)), nowThu)
        } yield assertTrue(
          appWeek(monStart(a), y).contains(25),
          appWeek(sunStart(a), y).contains(45),
          sunStart(a).week.map(_.start).contains(mon.minusDays(1)),
        )
      },
    ),
    suite("attribution (design §6.3, tests 1-3)")(
      test("1: a shared device checked in by A on Tuesday and by B on Thursday") {
        for {
          r   <- rig()
          pr  <- ZIO.service[ProfileRepo]
          dar <- ZIO.service[DeviceAssignmentRepo]
          xa  <- ZIO.service[Transactor[Task]]
          a   <- pr.create("A", Nil)
          b   <- pr.create("B", Nil)
          rb  <- app1("roblox.com")
          _   <- assign(rb, a)
          _   <- assign(rb, b)
          dev <- device(macSh, a)
          // Make it a checked-out shared device from Monday on.
          _   <- dar.assign(
            hh,
            dev,
            None,
            at(mon, 0),
            AssignmentKind.Assigned,
            None,
            AssignmentEndCause.Unassigned,
          )
          _   <- sql"UPDATE devices SET shared = true WHERE id = $dev".update.run.transact(xa)
          _   <- dar.assign(
            hh,
            dev,
            Some(a),
            at(tue, 10),
            AssignmentKind.CheckIn,
            None,
            AssignmentEndCause.CheckOut,
          )
          _   <- dar.assign(
            hh,
            dev,
            None,
            at(tue, 11),
            AssignmentKind.CheckIn,
            None,
            AssignmentEndCause.CheckOut,
          )
          _   <- dar.assign(
            hh,
            dev,
            Some(b),
            at(thu, 12),
            AssignmentKind.CheckIn,
            None,
            AssignmentEndCause.CheckOut,
          )
          _   <- dar.assign(
            hh,
            dev,
            None,
            at(thu, 13),
            AssignmentKind.CheckIn,
            None,
            AssignmentEndCause.CheckOut,
          )
          _   <- traffic(r, macSh, "roblox.com", at(tue, 10), 30)
          // Checked out: nobody's.
          _   <- traffic(r, macSh, "roblox.com", at(tue, 11, 30), 15)
          _   <- traffic(r, macSh, "roblox.com", at(thu, 12), 20)
          s   <- states(r, nowThu)
        } yield assertTrue(appWeek(s(a), rb).contains(30), appWeek(s(b), rb).contains(20))
      },
      test("2 + 3: a device moved A -> B mid-week, and a row straddling the move") {
        for {
          r   <- rig()
          pr  <- ZIO.service[ProfileRepo]
          dar <- ZIO.service[DeviceAssignmentRepo]
          a   <- pr.create("A", Nil)
          b   <- pr.create("B", Nil)
          rb  <- app1("roblox.com")
          _   <- assign(rb, a)
          _   <- assign(rb, b)
          dev <- device(macA, a)
          _   <- traffic(r, macA, "roblox.com", at(mon, 9), 15)
          _   <- traffic(r, macA, "roblox.com", at(wed, 11), 20)
          // 11:58-12:03: starts before the 12:00 move, so it is A's (credited by period_start).
          _   <- traffic(r, macA, "roblox.com", at(wed, 11, 58), 5)
          _   <- dar.assign(
            hh,
            dev,
            Some(b),
            at(wed, 12),
            AssignmentKind.Assigned,
            None,
            AssignmentEndCause.Reassigned,
          )
          _   <- traffic(r, macA, "roblox.com", at(wed, 13), 10)
          _   <- traffic(r, macA, "roblox.com", at(thu, 9), 25)
          s   <- states(r, nowThu)
        } yield assertTrue(
          // A: Monday 15 + Wednesday 20 + the 5-minute straddling row. B: Wednesday 10 + Thursday 25.
          // Crediting the straddling row by its end would give 35 / 40.
          appWeek(s(a), rb).contains(40),
          appWeek(s(b), rb).contains(35),
        )
      },
    ),
    suite("budget semantics (design §6.1-§6.2)")(
      test("6: a host on two members counts once for the group; a shared host counts zero") {
        for {
          r  <- rig()
          pr <- ZIO.service[ProfileRepo]
          a  <- pr.create("A", Nil)
          rb <- app("roblox.com", "roblox.com" -> false, "playfab.com" -> false)
          mc <- app(
            "minecraft.net",
            "minecraft.net" -> false,
            "playfab.com"   -> false,
            "akamai.net"    -> true,
          )
          g  <- group("Games", rb, mc)
          _  <- attach(a, g)
          _  <- device(macA, a)
          _  <- traffic(r, macA, "playfab.com", at(tue, 10), 20)
          _  <- traffic(r, macA, "akamai.net", at(tue, 14), 15)
          s  <- states(r, nowThu)
        } yield assertTrue(
          groupWeek(s(a), g).contains(20),
          appWeek(s(a), rb).contains(20),
          appWeek(s(a), mc).contains(20),
        )
      },
      test("7: attaching a group over assigned members changes no app's minutes") {
        for {
          r      <- rig()
          pr     <- ZIO.service[ProfileRepo]
          a      <- pr.create("A", Nil)
          rb     <- app1("roblox.com")
          mc     <- app1("minecraft.net")
          y      <- app1("youtube.com")
          _      <- ZIO.foreachDiscard(List(rb, mc, y))(assign(_, a))
          _      <- device(macA, a)
          _      <- traffic(r, macA, "roblox.com", at(tue, 10), 20)
          _      <- traffic(r, macA, "minecraft.net", at(tue, 10, 10), 25)
          _      <- traffic(r, macA, "youtube.com", at(wed, 9), 15)
          _      <- traffic(r, macA, "roblox.com", at(thu, 9), 10)
          before <- states(r, nowThu)
          g      <- group("Games", rb, mc)
          _      <- attach(a, g)
          after  <- states(r, nowThu)
          ids = List(rb, mc, y)
        } yield assertTrue(
          ids.map(appWeek(before(a), _)) == ids.map(appWeek(after(a), _)),
          ids.map(appToday(before(a), _)) == ids.map(appToday(after(a), _)),
          before(a).usedMinutes == after(a).usedMinutes,
          before(a).week == after(a).week,
          // Roblox 10:00-10:20 and Minecraft 10:10-10:35 overlap: one 35-minute session, plus 10.
          groupWeek(after(a), g).contains(45),
          appWeek(after(a), y).contains(15),
        )
      },
      test("7: attaching a group with new members equals assigning them individually") {
        for {
          r  <- rig()
          pr <- ZIO.service[ProfileRepo]
          b  <- pr.create("B", Nil)
          c  <- pr.create("C", Nil)
          rb <- app1("roblox.com")
          mc <- app1("minecraft.net")
          y  <- app1("youtube.com")
          _  <- assign(y, b)
          _  <- ZIO.foreachDiscard(List(rb, mc, y))(assign(_, c))
          g  <- group("Games", rb, mc)
          _  <- attach(b, g)
          _  <- device(macB, b)
          _  <- device(macC, c)
          _  <- ZIO.foreachDiscard(List(macB, macC)) { m =>
            traffic(r, m, "roblox.com", at(tue, 10), 20) *>
              traffic(r, m, "minecraft.net", at(wed, 10), 25) *>
              traffic(r, m, "youtube.com", at(wed, 11), 15) *>
              traffic(r, m, "roblox.com", at(thu, 9), 10)
          }
          s  <- states(r, nowThu)
          ids = List(rb, mc, y)
        } yield assertTrue(
          ids.map(appWeek(s(b), _)) == ids.map(appWeek(s(c), _)),
          ids.map(appToday(s(b), _)) == ids.map(appToday(s(c), _)),
          s(b).usedMinutes == s(c).usedMinutes,
          s(b).week == s(c).week,
          appWeek(s(b), rb).contains(30),
          groupWeek(s(b), g).contains(55),
        )
      },
      test("8: the attachment's counts-toward-daily flag overrides the members' own") {
        for {
          r  <- rig()
          pr <- ZIO.service[ProfileRepo]
          a  <- pr.create("A", Nil)
          b  <- pr.create("B", Nil)
          rb <- app1("roblox.com")
          y  <- app1("youtube.com")
          // A's own row says exempt; its attachment says counts. B's the reverse.
          _  <- assign(rb, a, exempt = true)
          _  <- assign(rb, b, exempt = false)
          _  <- assign(y, a)
          _  <- assign(y, b)
          ga <- group("Games", rb)
          _  <- attach(a, ga, exempt = false)
          _  <- attach(b, ga, exempt = true)
          _  <- device(macA, a)
          _  <- device(macB, b)
          _  <- ZIO.foreachDiscard(List(macA, macB)) { m =>
            traffic(r, m, "roblox.com", at(thu, 9), 20) *>
              traffic(r, m, "youtube.com", at(thu, 11), 15)
          }
          s  <- states(r, nowThu)
        } yield assertTrue(s(a).usedMinutes == 35, s(b).usedMinutes == 15)
      },
      test("10: one group on two profiles counts each profile's own usage at its own limits") {
        for {
          r  <- rig()
          pr <- ZIO.service[ProfileRepo]
          a  <- pr.create("A", Nil)
          b  <- pr.create("B", Nil)
          rb <- app1("roblox.com")
          g  <- group("Games", rb)
          _  <- attach(a, g, daily = Some(30), weekly = Some(100))
          _  <- attach(b, g, daily = Some(60), weekly = Some(200))
          _  <- device(macA, a)
          _  <- device(macB, b)
          _  <- traffic(r, macA, "roblox.com", at(tue, 10), 40)
          _  <- traffic(r, macA, "roblox.com", at(thu, 10), 35)
          _  <- traffic(r, macB, "roblox.com", at(thu, 10), 10)
          s  <- states(r, nowThu)
          ga = s(a).perGroup.find(_.groupId == g)
          gb = s(b).perGroup.find(_.groupId == g)
        } yield assertTrue(
          ga.map(x => (x.dailyLimitMinutes, x.weeklyLimitMinutes, x.usedMinutes)) ==
            Some((Some(30), Some(100), 35)),
          ga.flatMap(_.week).map(_.usedMinutes).contains(75),
          gb.map(x => (x.dailyLimitMinutes, x.weeklyLimitMinutes, x.usedMinutes)) ==
            Some((Some(60), Some(200), 10)),
          gb.flatMap(_.week).map(_.usedMinutes).contains(10),
          // A covered app with no assignment is counted but listed only through its group (D6).
          s(a).perApp.find(_.appId == rb).flatMap(_.groupId).contains(g),
        )
      },
      test("an assignment's weekly limit is carried with its week-to-date minutes") {
        for {
          r  <- rig()
          pr <- ZIO.service[ProfileRepo]
          a  <- pr.create("A", Nil)
          y  <- app1("youtube.com")
          _  <- assign(y, a, daily = Some(60))
          _  <- setWeekly(y, a, 120)
          _  <- device(macA, a)
          _  <- traffic(r, macA, "youtube.com", at(mon, 10), 50)
          _  <- traffic(r, macA, "youtube.com", at(thu, 10), 20)
          s  <- states(r, nowThu)
          st = s(a).perApp.find(_.appId == y)
        } yield assertTrue(
          st.map(x => (x.dailyLimitMinutes, x.weeklyLimitMinutes, x.usedMinutes)) ==
            Some((Some(60), Some(120), 20)),
          appWeek(s(a), y).contains(70),
        )
      },
    ),
    suite("display reads the enforced figure (design §6.5, test 4)")(
      test("4: /api/time/status shows the week minutes the snapshot's day state carries") {
        for {
          r     <- rig()
          pr    <- ZIO.service[ProfileRepo]
          a     <- pr.create("A", Nil)
          rb    <- app1("roblox.com")
          y     <- app1("youtube.com")
          _     <- assign(y, a, daily = Some(60))
          _     <- setWeekly(y, a, 300)
          g     <- group("Games", rb)
          _     <- attach(a, g, weekly = Some(240))
          _     <- device(macA, a)
          _     <- traffic(r, macA, "youtube.com", at(mon, 10), 40)
          _     <- traffic(r, macA, "roblox.com", at(wed, 10), 30)
          // A rollup tick, then more traffic past its watermark: today is rolled + tail.
          _     <- traffic(r, macA, "youtube.com", at(thu, 9), 20)
          _     <- tick(at(thu, 12))
          _     <- traffic(r, macA, "roblox.com", at(thu, 13), 15)
          s     <- states(r, nowThu)
          live  <- svc.flatMap(_.dayStateAllLive(hh, nowThu, thu, r.settings))
          one   <- svc.flatMap(_.dayState(hh, nowThu, thu, r.settings, a)).someOrFailException
          shown <- getJson[List[ProfileTimeStatus]](
            s"/api/time/status?profileId=${a.value}",
            nowThu,
          )
          ya = shown.head.appUsage.find(_.label == "app:youtube.com")
          ga = shown.head.groupUsage.find(_.groupId == g)
        } yield assertTrue(
          appWeek(s(a), y).contains(60),
          groupWeek(s(a), g).contains(45),
          // Every read path agrees.
          appWeek(live(a), y) == appWeek(s(a), y),
          groupWeek(live(a), g) == groupWeek(s(a), g),
          appWeek(one, y) == appWeek(s(a), y),
          groupWeek(one, g) == groupWeek(s(a), g),
          // Displayed = the state enforcement reads.
          ya.flatMap(_.weekUsedMins) == appWeek(s(a), y),
          ya.flatMap(_.weeklyLimitMins).contains(300),
          ya.flatMap(_.weekStart).contains(mon.toString),
          ga.flatMap(_.weekUsedMins) == groupWeek(s(a), g),
          ga.flatMap(_.weeklyLimitMins).contains(240),
          ga.map(_.usedMins).contains(15),
        )
      },
    ),
    suite("settling (design §6.4, tests 17-19)")(
      test("17: a cold week refills each past day once; the next read is all hits") {
        for {
          r         <- rig()
          pr        <- ZIO.service[ProfileRepo]
          a         <- pr.create("A", Nil)
          y         <- app1("youtube.com")
          rb        <- app1("roblox.com")
          _         <- assign(y, a)
          g         <- group("Games", rb)
          _         <- attach(a, g)
          _         <- device(macA, a)
          _         <- traffic(r, macA, "youtube.com", at(mon, 10), 20)
          _         <- traffic(r, macA, "roblox.com", at(tue, 10), 25)
          refill0   <- settleCount("refill")
          hit0      <- settleCount("hit")
          cold      <- states(r, nowThu)
          refill1   <- settleCount("refill")
          hit1      <- settleCount("hit")
          warm      <- states(r, nowThu)
          refill2   <- settleCount("refill")
          hit2      <- settleCount("hit")
          xa        <- ZIO.service[Transactor[Task]]
          settled   <- sql"""SELECT date, rolled_through FROM time_used_daily
                             WHERE profile_id = $a AND date < $thu ORDER BY date"""
            .query[(LocalDate, Instant)]
            .to[List]
            .transact(xa)
          groupRows <- rowCount("app_group_used_daily", a)
        } yield assertTrue(
          appWeek(cold(a), y).contains(20),
          groupWeek(cold(a), g).contains(25),
          cold(a) == warm(a),
          // Monday, Tuesday, Wednesday: refilled once each, then read back as hits.
          refill1 - refill0 == 3.0,
          hit1 - hit0 == 0.0,
          refill2 - refill1 == 0.0,
          hit2 - hit1 == 3.0,
          settled == List(mon, tue, wed).map(d => d -> at(d.plusDays(1), 0)),
          // Zero-usage days are settled too, so they are recognisable as settled.
          groupRows == 3,
        )
      },
      test("the rollup tick settles yesterday on its first tick after SettleDelay") {
        for {
          r  <- rig()
          pr <- ZIO.service[ProfileRepo]
          a  <- pr.create("A", Nil)
          y  <- app1("youtube.com")
          _  <- assign(y, a)
          _  <- device(macA, a)
          _  <- traffic(r, macA, "youtube.com", at(thu, 22), 30)
          xa <- ZIO.service[Transactor[Task]]
          q = sql"SELECT rolled_through FROM time_used_daily WHERE profile_id = $a AND date = $thu"
            .query[Instant]
            .option
            .transact(xa)
          _     <- tick(at(thu, 23))    // Thursday's own last tick
          _     <- tick(at(fri, 0, 15)) // inside the delay: Thursday is not settled
          early <- q
          _     <- tick(at(fri, 0, 30)) // the delay has passed
          late  <- q
        } yield assertTrue(
          early.contains(at(thu, 23)),
          late.contains(at(fri, 0)),
        )
      },
      test("18: a settings save deletes the settled rows, and the refill gives the same totals") {
        for {
          r     <- rig()
          pr    <- ZIO.service[ProfileRepo]
          hsr   <- ZIO.service[HouseholdSettingsRepo]
          a     <- pr.create("A", Nil)
          rb    <- app1("roblox.com")
          g     <- group("Games", rb)
          _     <- attach(a, g)
          _     <- device(macA, a)
          _     <- traffic(r, macA, "roblox.com", at(tue, 10), 25)
          warm  <- states(r, nowThu)
          rows0 <- rowCount("app_group_used_daily", a)
          _     <- hsr.update(hh, r.settings)
          rows1 <- rowCount("app_group_used_daily", a)
          tot1  <- rowCount("time_used_daily", a)
          again <- states(r, nowThu)
        } yield assertTrue(
          groupWeek(warm(a), g).contains(25),
          rows0 == 3,
          rows1 == 0,
          tot1 == 0,
          again(a) == warm(a),
        )
      },
      test("18a: a report inside SettleDelay is counted; one after settling is metered late") {
        for {
          r   <- rig()
          pr  <- ZIO.service[ProfileRepo]
          rr  <- ZIO.service[RouterRepo]
          trr <- ZIO.service[TrafficReportRepo]
          tur <- ZIO.service[TimeUsageRepo]
          dr  <- ZIO.service[DeviceRepo]
          cer <- ZIO.service[ConnectionEventRepo]
          alr <- ZIO.service[AlertRepo]
          hsr <- ZIO.service[HouseholdSettingsRepo]
          sur <- ZIO.service[SettledUsageRepo]
          a   <- pr.create("A", Nil)
          y   <- app1("youtube.com")
          _   <- assign(y, a)
          _   <- dr.upsert(macA, "kid", Some(a), "192.168.1.10")
          _ <- rr.completeEnrollment(r.router, Sha256Hex.unsafe(RouterAuth.sha256Hex("TOKEN-2932")))
          router      <- rr.findById(r.router).someOrFailException
          _           <- traffic(r, macA, "youtube.com", at(thu, 22), 30)
          _           <- tick(at(thu, 23, 50))
          (clock, tc) <- Clock.TestClock.makeWithControl(LocalDateTime.of(fri, LocalTime.of(0, 10)))
          ingest = new RouterIngestService(
            rr,
            trr,
            tur,
            dr,
            cer,
            alr,
            hsr,
            lateRows = LatePresenceMeter(sur, clock),
          )
          report = (ps: Instant) =>
            ingest
              .ingestUsage(
                router,
                UsageReport(
                  router.id,
                  ps.toString,
                  ps.plusSeconds(300).toString,
                  List(
                    UsageRecord(
                      macA,
                      Some(IpAddress.unsafe("192.168.1.10")),
                      HostId.Fqdn(Hostname.unsafe("youtube.com")),
                      300L,
                      500_000L,
                      500_000L,
                    ),
                  ),
                ).toJson,
              )
              .mapError(e => new RuntimeException(s"ingest: $e"))
          late0  <- lateRowCount
          // Arrives at 00:10 Friday, inside the delay: Thursday is read rolled + tail, so it counts.
          _      <- report(at(thu, 23, 50))
          late1  <- lateRowCount
          during <- states(r, at(fri, 0, 10))
          // The settle pass after the delay settles Thursday with that report in it.
          _      <- tick(at(fri, 0, 45))
          _      <- tc.setTo(LocalDateTime.of(fri, LocalTime.of(1, 0)))
          // Arrives after Thursday settled: not counted, metered.
          _      <- report(at(thu, 23, 55))
          late2  <- lateRowCount
          after  <- states(r, at(fri, 1, 0))
        } yield assertTrue(
          late1 - late0 == 0.0,
          appWeek(during(a), y).contains(35),
          late2 - late1 == 1.0,
          appWeek(after(a), y).contains(35),
        )
      },
      test("18b: an assignment mode change deletes the profile's current-week settled rows") {
        for {
          r      <- rig()
          pr     <- ZIO.service[ProfileRepo]
          a      <- pr.create("A", Nil)
          ctl    <- pr.create("Control", Nil)
          y      <- app1("youtube.com")
          rb     <- app1("roblox.com")
          _      <- assign(y, a)
          _      <- assign(rb, a, mode = AppMode.Allowed)
          _      <- assign(y, ctl)
          _      <- device(macA, a)
          _      <- device(macB, ctl)
          _      <- traffic(r, macA, "roblox.com", at(tue, 10), 25)
          _      <- traffic(r, macA, "youtube.com", at(tue, 11), 10)
          _      <- traffic(r, macB, "youtube.com", at(tue, 11), 10)
          _      <- states(r, nowThu)
          aRows0 <- rowCount("app_used_daily", a)
          _      <- assign(rb, a, mode = AppMode.TimeLimited)
          aRows1 <- rowCount("time_used_daily", a)
          cRows1 <- rowCount("time_used_daily", ctl)
          s      <- states(r, nowThu)
          // A cold read: nothing settled for A.
          xa     <- ZIO.service[Transactor[Task]]
          _      <- ZIO.foreachDiscard(
            List("time_used_daily", "app_used_daily", "app_group_used_daily"),
          )(t =>
            (fr"DELETE FROM" ++ Fragment.const(t) ++ fr"WHERE profile_id = $a").update.run
              .transact(xa),
          )
          cold   <- states(r, nowThu)
        } yield assertTrue(
          aRows0 > 0,
          aRows1 == 0,
          cRows1 == 3,
          appWeek(s(a), rb).contains(25),
          s(a) == cold(a),
        )
      },
      test("18b: attaching and detaching a group delete the profile's current-week settled rows") {
        for {
          r     <- rig()
          pr    <- ZIO.service[ProfileRepo]
          a     <- pr.create("A", Nil)
          rb    <- app1("roblox.com")
          y     <- app1("youtube.com")
          _     <- assign(y, a)
          _     <- device(macA, a)
          _     <- traffic(r, macA, "roblox.com", at(tue, 10), 25)
          _     <- traffic(r, macA, "youtube.com", at(wed, 10), 10)
          _     <- states(r, nowThu)
          rows0 <- rowCount("time_used_daily", a)
          g     <- group("Games", rb)
          _     <- attach(a, g)
          rows1 <- rowCount("time_used_daily", a)
          on    <- states(r, nowThu)
          _     <- detach(a, g, nowThu)
          rows2 <- rowCount("app_used_daily", a)
          off   <- states(r, nowThu)
        } yield assertTrue(
          rows0 == 3,
          rows1 == 0,
          groupWeek(on(a), g).contains(25),
          rows2 == 0,
          off(a).perGroup.isEmpty,
          appWeek(off(a), y).contains(10),
        )
      },
      test("18b: an exempt-flag edit deletes only the daily-total rows; the weekly view matches") {
        for {
          r        <- rig()
          pr       <- ZIO.service[ProfileRepo]
          a        <- pr.create("A", Nil)
          rb       <- app1("roblox.com")
          y        <- app1("youtube.com")
          _        <- assign(rb, a, daily = Some(60), exempt = true)
          _        <- assign(y, a)
          _        <- device(macA, a)
          _        <- traffic(r, macA, "roblox.com", at(tue, 10), 25)
          _        <- traffic(r, macA, "youtube.com", at(wed, 10), 10)
          before   <- getJson[List[ProfileTimeStatusWeek]](
            s"/api/time/status/week?profileId=${a.value}",
            nowThu,
          )
          appRows0 <- rowCount("app_used_daily", a)
          // Same mode and limit; only "counts toward daily" changes.
          _        <- assign(rb, a, daily = Some(60), exempt = false)
          appRows1 <- rowCount("app_used_daily", a)
          totRows1 <- rowCount("time_used_daily", a)
          after    <- getJson[List[ProfileTimeStatusWeek]](
            s"/api/time/status/week?profileId=${a.value}&bucketOffsetMin=15",
            nowThu,
          )
          s        <- states(r, nowThu)
        } yield assertTrue(
          before.head.totalMins == 10,
          // Roblox and YouTube on Monday to Wednesday.
          appRows0 == 6,
          appRows1 == 6,
          totRows1 == 0,
          after.head.totalMins == 35,
          s(a).week.map(_.usedMinutes).contains(35),
          appWeek(s(a), rb).contains(25),
        )
      },
      test("19: a membership edit recomputes the week for every profile the group is attached to") {
        for {
          r      <- rig()
          pr     <- ZIO.service[ProfileRepo]
          a      <- pr.create("A", Nil)
          b      <- pr.create("B", Nil)
          rb     <- app1("roblox.com")
          mc     <- app1("minecraft.net")
          g      <- group("Games", rb)
          _      <- attach(a, g)
          _      <- attach(b, g)
          _      <- device(macA, a)
          _      <- device(macB, b)
          _      <- ZIO.foreachDiscard(List(macA, macB)) { m =>
            traffic(r, m, "roblox.com", at(mon, 10), 20) *>
              traffic(r, m, "minecraft.net", at(tue, 10), 15)
          }
          before <- states(r, nowThu)
          _      <- addMember(g, mc, nowThu)
          after  <- states(r, nowThu)
        } yield assertTrue(
          groupWeek(before(a), g).contains(20),
          groupWeek(before(b), g).contains(20),
          groupWeek(after(a), g).contains(35),
          groupWeek(after(b), g).contains(35),
        )
      },
    ),
    suite("weekly views on the calendar week (design §6.5, D10)")(
      test("/api/time/status/week and /summary/week: calendar week, exempt-filtered total") {
        for {
          r    <- rig()
          pr   <- ZIO.service[ProfileRepo]
          tlr  <- ZIO.service[TimeLimitRepo]
          a    <- pr.create("A", Nil)
          _    <- tlr.upsert(a, 120)
          ex   <- app1("khanacademy.org")
          y    <- app1("youtube.com")
          _    <- assign(ex, a, mode = AppMode.Allowed, exempt = true)
          _    <- assign(y, a)
          _    <- device(macA, a)
          // The Sunday before: the previous week.
          _    <- traffic(r, macA, "youtube.com", at(mon.minusDays(1), 10), 30)
          _    <- traffic(r, macA, "youtube.com", at(tue, 10), 20)
          _    <- traffic(r, macA, "khanacademy.org", at(wed, 10), 40)
          _    <- traffic(r, macA, "youtube.com", at(thu, 10), 15)
          week <- getJson[List[ProfileTimeStatusWeek]](
            s"/api/time/status/week?profileId=${a.value}",
            nowThu,
          )
          sum  <- getJson[List[ProfileTimeSummaryWeek]]("/api/time/status/summary/week", nowThu)
          past <- getJson[List[ProfileTimeStatusWeek]](
            s"/api/time/status/week?profileId=${a.value}&to=${mon.minusDays(2)}",
            nowThu,
          )
          w  = week.head
          sa = sum.find(_.profileId == a).get
        } yield assertTrue(
          w.from == mon.toString,
          w.to == mon.plusDays(6).toString,
          w.totalMins == 35,
          w.devices.map(_.usedMins) == List(35),
          w.perBucket.map(_.usedMins).sum == 35,
          sa.from == mon.toString,
          sa.to == mon.plusDays(6).toString,
          sa.totalMins == 35,
          past.head.from == mon.minusDays(7).toString,
          past.head.totalMins == 30,
        )
      },
    ),
    suite("AppRepo.mergeAppInto keeps group membership (#2938 review)")(
      test("a merged-away app's membership moves to the survivor") {
        for {
          _       <- rig()
          ar      <- ZIO.service[AppRepo]
          xa      <- ZIO.service[Transactor[Task]]
          dup     <- app1("roblox-dup.com")
          keep    <- app1("roblox.com")
          g       <- group("Games", dup)
          _       <- ar.mergeAppInto(dup, keep)
          members <- sql"SELECT app_id FROM app_group_members WHERE group_id = $g"
            .query[AppId]
            .to[List]
            .transact(xa)
        } yield assertTrue(members == List(keep))
      },
      test("the survivor's own membership wins over the merged-away app's") {
        for {
          _     <- rig()
          ar    <- ZIO.service[AppRepo]
          xa    <- ZIO.service[Transactor[Task]]
          dup   <- app1("roblox-dup.com")
          keep  <- app1("roblox.com")
          gDup  <- group("Old", dup)
          gKeep <- group("Games", keep)
          _     <- ar.mergeAppInto(dup, keep)
          rows  <- sql"SELECT group_id, app_id FROM app_group_members ORDER BY group_id"
            .query[(AppGroupId, AppId)]
            .to[List]
            .transact(xa)
        } yield assertTrue(rows == List(gKeep -> keep))
      },
    ),
  ) @@ TestAspect.sequential
}
