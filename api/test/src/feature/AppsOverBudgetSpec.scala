package wifihaven.api.feature

import doobie.*
import doobie.implicits.*
import cats.implicits.*
import wifihaven.api.db.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.api.policy.*
import wifihaven.api.routes.{BlockPageHousehold, BlockedRoutes}
import wifihaven.api.auth.RateLimiter
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
 * #2933 (epic #2930, design `docs/design/weekly-limits-and-app-groups.md` §7, §12): an app is
 * blocked when ANY budget containing it is exhausted, and no carve may put it back on the allow
 * list. Every §7.3 row is a feature test through the real snapshot builder AND the per-host
 * `decideDetailed`, which must agree (#1532): the helpers below assert both verdicts together.
 *
 * Rows 3, 7, 9c and 14 are the cases the carves exist for: each blocks only because the carves read
 * `appsOverBudget` (the group, or the weekly limit) rather than the app's own daily cap.
 *
 * Fixture week as in `WeeklyBudgetSpec`: Monday 2025-01-06 .. Sunday, UTC, midnight reset, "now" is
 * Thursday 2025-01-09 16:00. Every blocked-ness assertion sits beside a reachable control from the
 * same rig, so a rig with no traffic or no enforcement cannot pass.
 */
object AppsOverBudgetSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Transactor[Task] & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(LocalDateTime.of(2025, 1, 9, 16, 0))

  private val hh = HouseholdId.Default

  private val mon = LocalDate.of(2025, 1, 6)
  private val tue = mon.plusDays(1)
  private val thu = mon.plusDays(3)

  private def at(d: LocalDate, h: Int, m: Int = 0): Instant =
    LocalDateTime.of(d, LocalTime.of(h, m)).toInstant(ZoneOffset.UTC)

  private val nowThu = at(thu, 16)

  private val macA = MacAddress.unsafe("aa:bb:cc:29:33:01")
  private val macB = MacAddress.unsafe("aa:bb:cc:29:33:02")

  // ── rig ────────────────────────────────────────────────────────────────────────────────────

  final case class Rig(settings: HouseholdSettings, router: RouterId)

  private val rig =
    for {
      _   <- TestDatabase.cleanAndMigrate
      hsr <- ZIO.service[HouseholdSettingsRepo]
      cur <- hsr.getForHousehold(hh)
      settings = cur.copy(
        dailyResetTz = ZoneId.of("UTC"),
        dailyResetTime = LocalTime.MIDNIGHT,
        weekStartDay = 1,
      )
      _   <- hsr.update(hh, settings)
      rid <- ZIO.serviceWithZIO[RouterRepo](_.create("gw-2933", Sha256Hex.unsafe("v" * 64)))
    } yield Rig(settings, rid)

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

  private def app(name: String, host: String): ZIO[AppRepo, Throwable, AppId] =
    ZIO.serviceWithZIO[AppRepo] { ar =>
      for {
        id <- ar.create(name, name.toLowerCase.replace(' ', '-'), None, None)
        _  <- ar.setHostEntries(id, List(AppHostEntry(Hostname.unsafe(host), false)))
      } yield id
    }

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
  ): RIO[Transactor[Task], AppPolicyAssignmentId] =
    appRepoAt(nowThu).flatMap(_.upsertAssignment(a, p, mode, daily, exempt))

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
  ): RIO[Transactor[Task], Unit] =
    ZIO.serviceWithZIO[Transactor[Task]](xa =>
      (sql"""INSERT INTO profile_app_groups
               (household_id, profile_id, group_id, daily_minutes, weekly_minutes, exempt_from_daily)
             VALUES ($hh, $p, $g, $daily, $weekly, $exempt)""".update.run *>
        SettledUsage.invalidateCurrentWeek(List(p), SettledUsage.Kinds.All, nowThu))
        .transact(xa)
        .unit,
    )

  private def setWeekly(a: AppId, p: ProfileId, weekly: Int): RIO[Transactor[Task], Unit] =
    ZIO.serviceWithZIO[Transactor[Task]](xa =>
      sql"UPDATE app_policy_assignments SET weekly_minutes = $weekly WHERE app_id = $a AND profile_id = $p".update.run
        .transact(xa)
        .unit,
    )

  /** A profile with one device. */
  private def kid(name: String, mac: MacAddress) =
    for {
      pr <- ZIO.service[ProfileRepo]
      p  <- pr.create(name, Nil)
      _  <- ZIO.serviceWithZIO[DeviceRepo](TestLayers.seedDevice(_, mac.value, mac.value, p))
    } yield p

  // A window covering Thursday 16:00 (UTC), as a profile's bedtime Schedule.
  private val activeWindow =
    ScheduleWindow(
      List("mon", "tue", "wed", "thu", "fri", "sat", "sun"),
      LocalTime.of(15, 0),
      LocalTime.of(17, 0),
      ZoneId.of("UTC"),
    )

  private def bedtime(p: ProfileId) =
    for {
      nsr <- ZIO.service[NamedScheduleRepo]
      sid <- nsr.create(s"Bed#${p.value}", None, List(activeWindow))
      _   <- nsr.setProfileBlockSchedules(p, List(sid))
    } yield ()

  // An `allowed_during` window, active now, on an assignment.
  private def allowedDuring(assignment: AppPolicyAssignmentId, name: String) =
    for {
      nsr <- ZIO.service[NamedScheduleRepo]
      ar  <- ZIO.service[AppRepo]
      sid <- nsr.create(name, None, List(activeWindow))
      _   <- ar.setScheduleRules(assignment, List((sid, AppScheduleMode.AllowedDuring)))
    } yield ()

  private def policyAt(now: Instant, cacheEnabled: Boolean = false) =
    for {
      pr  <- ZIO.service[ProfileRepo]
      hsr <- ZIO.service[HouseholdSettingsRepo]
      tlr <- ZIO.service[TimeLimitRepo]
      atl <- ZIO.service[AppTimeLimitRepo]
      dr  <- ZIO.service[DeviceRepo]
      blr <- ZIO.service[BlocklistRepo]
      trr <- ZIO.service[TrafficReportRepo]
      er  <- ZIO.service[TimeExtensionRepo]
      ar  <- ZIO.service[AppRepo]
      nsr <- ZIO.service[NamedScheduleRepo]
      c   <- Clock.TestClock.makeWithControl(LocalDateTime.ofInstant(now, ZoneOffset.UTC))
    } yield PolicyServiceLive(
      pr,
      hsr,
      tlr,
      atl,
      dr,
      blr,
      trr,
      er,
      ar,
      c._1,
      namedScheduleRepo = nsr,
      cacheEnabled = cacheEnabled,
    ): PolicyService

  // ── verdicts: snapshot and decideDetailed, asserted together ───────────────────────────────

  /** (snapshot reachable, per-host decision allowed) for each host. */
  private type Verdicts = Map[String, (Boolean, Boolean)]

  private def snapReachable(snap: PolicySnapshot, p: ProfileId, host: String): Boolean = {
    val r = snap.profiles(p).rules
    r.extraAllowed.exists(h => HostMatch.matchesPattern(host, h.value)) ||
    !(r.blocked || r.extraBlocked.exists(h => HostMatch.matchesPattern(host, h.value)))
  }

  private def verdicts(
      svc: PolicyService,
      p: ProfileId,
      mac: MacAddress,
      hosts: String*,
  ): Task[Verdicts] =
    for {
      snap <- svc.snapshot
      ds   <- ZIO.foreach(hosts.toList)(h =>
        svc.decideDetailed(hh, mac.value, h).map(d => h -> (snapReachable(snap, p, h), d)),
      )
    } yield ds.map { case (h, (s, d)) =>
      h -> (s, d.response.decision == ConnectionDecision.Allow)
    }.toMap

  private def reachable(v: Verdicts, h: String): Boolean = v(h) == ((true, true))
  private def blocked(v: Verdicts, h: String): Boolean   = v(h) == ((false, false))

  private def groupReason(g: AppGroupId): String =
    BlockReason.asWire(BlockReason.AppTimeLimit(s"group:${g.value}"))

  // ── tests ──────────────────────────────────────────────────────────────────────────────────

  def spec = suite("AppsOverBudgetSpec (#2933)")(
    suite("whole-MAC block: none (rows 1-5, 11, 13-15)")(
      test("1 + 11: nothing over: covered and uncovered apps are reachable") {
        for {
          r   <- rig
          a   <- kid("A", macA)
          rb  <- app("Roblox", "roblox.com")
          mc  <- app("Minecraft", "minecraft.net")
          kh  <- app("Khan", "khan.org")
          g   <- group("Games", rb, mc)
          _   <- attach(a, g, weekly = Some(600))
          _   <- assign(kh, a, mode = AppMode.Allowed)
          _   <- traffic(r, macA, "roblox.com", at(thu, 10), 20)
          svc <- policyAt(nowThu)
          v   <- verdicts(svc, a, macA, "roblox.com", "minecraft.net", "khan.org")
        } yield assertTrue(
          reachable(v, "roblox.com"),
          reachable(v, "minecraft.net"),
          reachable(v, "khan.org"),
        )
      },
      test("2: an app over its own weekly limit is blocked; a control app is reachable") {
        for {
          r   <- rig
          a   <- kid("A", macA)
          y   <- app("YouTube", "youtube.com")
          c   <- app("Control", "control.com")
          _   <- assign(y, a)
          _   <- setWeekly(y, a, 60)
          _   <- assign(c, a)
          _   <- traffic(r, macA, "youtube.com", at(mon, 10), 50)
          _   <- traffic(r, macA, "youtube.com", at(thu, 10), 20)
          _   <- traffic(r, macA, "control.com", at(thu, 12), 20)
          svc <- policyAt(nowThu)
          v   <- verdicts(svc, a, macA, "youtube.com", "control.com")
          d   <- svc.decideDetailed(hh, macA.value, "youtube.com")
        } yield assertTrue(
          blocked(v, "youtube.com"),
          reachable(v, "control.com"),
          d.response.reason == BlockReason.asWire(BlockReason.AppTimeLimit("app:youtube")),
          // The weekly window resets at the next week start.
          d.response.expiresAt.contains("2025-01-13T00:00:00Z"),
          d.budget.map(b => (b.kind, b.name, b.window)) ==
            Some((BudgetKind.App, "YouTube", BudgetWindow.Week)),
        )
      },
      test("3 (+11): group over weekly while the member's own cap is under: blocked, not carved") {
        for {
          r    <- rig
          a    <- kid("A", macA)
          rb   <- app("Roblox", "roblox.com")
          mc   <- app("Minecraft", "minecraft.net")
          y    <- app("YouTube", "youtube.com")
          kh   <- app("Khan", "khan.org")
          // Minecraft also has its own TimeLimited row, well under its cap.
          _    <- assign(mc, a, daily = Some(120))
          _    <- assign(y, a)
          _    <- assign(kh, a, mode = AppMode.Allowed)
          g    <- group("Games", rb, mc)
          _    <- attach(a, g, weekly = Some(100))
          _    <- traffic(r, macA, "roblox.com", at(tue, 10), 60)
          _    <- traffic(r, macA, "roblox.com", at(thu, 10), 50)
          _    <- traffic(r, macA, "youtube.com", at(thu, 12), 20)
          svc  <- policyAt(nowThu)
          v    <- verdicts(svc, a, macA, "roblox.com", "minecraft.net", "youtube.com", "khan.org")
          snap <- svc.snapshot
          allowed = snap.profiles(a).rules.extraAllowed.map(_.value).toSet
          d  <- svc.decideDetailed(hh, macA.value, "roblox.com")
          dm <- svc.decideDetailed(hh, macA.value, "minecraft.net")
        } yield assertTrue(
          blocked(v, "roblox.com"),
          blocked(v, "minecraft.net"),
          reachable(v, "youtube.com"),
          reachable(v, "khan.org"),
          !allowed.contains("roblox.com"),
          !allowed.contains("minecraft.net"),
          d.response.reason == groupReason(g),
          dm.response.reason == groupReason(g),
          d.response.expiresAt.contains("2025-01-13T00:00:00Z"),
          d.budget.map(b => (b.kind, b.name, b.window)) ==
            Some((BudgetKind.Group, "Games", BudgetWindow.Week)),
        )
      },
      test("4: an app over its own daily cap is blocked until the next daily reset") {
        for {
          r   <- rig
          a   <- kid("A", macA)
          y   <- app("YouTube", "youtube.com")
          c   <- app("Control", "control.com")
          _   <- assign(y, a, daily = Some(30))
          _   <- assign(c, a, daily = Some(30))
          _   <- traffic(r, macA, "youtube.com", at(thu, 10), 35)
          _   <- traffic(r, macA, "control.com", at(thu, 12), 10)
          svc <- policyAt(nowThu)
          v   <- verdicts(svc, a, macA, "youtube.com", "control.com")
          d   <- svc.decideDetailed(hh, macA.value, "youtube.com")
        } yield assertTrue(
          blocked(v, "youtube.com"),
          reachable(v, "control.com"),
          d.response.expiresAt.contains("2025-01-10T00:00:00Z"),
          d.budget.map(b => (b.kind, b.window)) == Some((BudgetKind.App, BudgetWindow.Day)),
        )
      },
      test("5: group over its daily limit: every member blocked, labelled group:<id>") {
        for {
          r   <- rig
          a   <- kid("A", macA)
          rb  <- app("Roblox", "roblox.com")
          mc  <- app("Minecraft", "minecraft.net")
          y   <- app("YouTube", "youtube.com")
          _   <- assign(y, a)
          g   <- group("Games", rb, mc)
          _   <- attach(a, g, daily = Some(30), weekly = Some(600))
          _   <- traffic(r, macA, "roblox.com", at(thu, 10), 35)
          _   <- traffic(r, macA, "youtube.com", at(thu, 12), 20)
          svc <- policyAt(nowThu)
          v   <- verdicts(svc, a, macA, "roblox.com", "minecraft.net", "youtube.com")
          d   <- svc.decideDetailed(hh, macA.value, "minecraft.net")
        } yield assertTrue(
          blocked(v, "roblox.com"),
          blocked(v, "minecraft.net"),
          reachable(v, "youtube.com"),
          d.response.reason == groupReason(g),
          d.response.expiresAt.contains("2025-01-10T00:00:00Z"),
          d.budget.map(b => (b.kind, b.name, b.window)) ==
            Some((BudgetKind.Group, "Games", BudgetWindow.Day)),
        )
      },
      test(
        "13: overlap, own row Blocked, group under: blocked; a covered-only control is reachable",
      ) {
        for {
          r   <- rig
          a   <- kid("A", macA)
          bl  <- app("Blocked", "blk.com")
          ct  <- app("Control", "ctl.com")
          _   <- assign(bl, a, mode = AppMode.Blocked)
          g   <- group("Games", bl, ct)
          _   <- attach(a, g, weekly = Some(600))
          _   <- traffic(r, macA, "ctl.com", at(thu, 10), 20)
          svc <- policyAt(nowThu)
          v   <- verdicts(svc, a, macA, "blk.com", "ctl.com")
        } yield assertTrue(blocked(v, "blk.com"), reachable(v, "ctl.com"))
      },
      test(
        "14: overlap, own row Allowed, group over: blocked (the Allowed row's carve is withheld)",
      ) {
        for {
          r   <- rig
          a   <- kid("A", macA)
          al  <- app("Allowed", "alw.com")
          ct  <- app("Control", "ctl.com")
          y   <- app("YouTube", "youtube.com")
          _   <- assign(al, a, mode = AppMode.Allowed)
          _   <- assign(y, a, mode = AppMode.Allowed)
          g   <- group("Games", al, ct)
          _   <- attach(a, g, weekly = Some(100))
          _   <- traffic(r, macA, "ctl.com", at(tue, 10), 60)
          _   <- traffic(r, macA, "ctl.com", at(thu, 10), 50)
          svc <- policyAt(nowThu)
          v   <- verdicts(svc, a, macA, "alw.com", "ctl.com", "youtube.com")
        } yield assertTrue(
          blocked(v, "alw.com"),
          blocked(v, "ctl.com"),
          reachable(v, "youtube.com"),
        )
      },
      test(
        "14 control + 15: overlap, group under: an Allowed row is reachable; own daily over blocks",
      ) {
        for {
          r   <- rig
          a   <- kid("A", macA)
          al  <- app("Allowed", "alw.com")
          ow  <- app("Own", "own.com")
          ct  <- app("Control", "ctl.com")
          _   <- assign(al, a, mode = AppMode.Allowed)
          _   <- assign(ow, a, daily = Some(30))
          g   <- group("Games", al, ow, ct)
          _   <- attach(a, g, weekly = Some(600))
          _   <- traffic(r, macA, "own.com", at(thu, 10), 35)
          _   <- traffic(r, macA, "ctl.com", at(thu, 12), 10)
          svc <- policyAt(nowThu)
          v   <- verdicts(svc, a, macA, "alw.com", "own.com", "ctl.com")
        } yield assertTrue(
          reachable(v, "alw.com"),
          blocked(v, "own.com"),
          reachable(v, "ctl.com"),
        )
      },
    ),
    suite("whole-MAC block: profile daily limit (rows 6-8)")(
      test("6 + 7 + 8: an exempt member outlives the daily total, until its group is over") {
        for {
          r   <- rig
          a   <- kid("A", macA)
          b   <- kid("B", macB)
          tlr <- ZIO.service[TimeLimitRepo]
          rb  <- app("Roblox", "roblox.com")
          z   <- app("Counted", "counted.com")
          g   <- group("Games", rb)
          // Exempt attachments (D5): the group's time is not in the daily total.
          _   <- attach(a, g, weekly = Some(600), exempt = true)
          _   <- attach(b, g, weekly = Some(50), exempt = true)
          _   <- ZIO.foreachDiscard(List(a, b))(p => tlr.upsert(p, 30) *> assign(z, p))
          _   <- traffic(r, macA, "counted.com", at(thu, 9), 40)
          _   <- traffic(r, macB, "counted.com", at(thu, 9), 40)
          _   <- traffic(r, macA, "roblox.com", at(thu, 11), 20)
          _   <- traffic(r, macB, "roblox.com", at(tue, 11), 60)
          svc <- policyAt(nowThu)
          va  <- verdicts(svc, a, macA, "roblox.com", "counted.com")
          vb  <- verdicts(svc, b, macB, "roblox.com", "counted.com")
        } yield assertTrue(
          // 6: exempt, under every budget: reachable through the daily total block.
          reachable(va, "roblox.com"),
          // 8: a counted app is blocked by the whole-MAC drop.
          blocked(va, "counted.com"),
          // 7: the group is over its weekly limit: the exempt carve is withheld.
          blocked(vb, "roblox.com"),
          blocked(vb, "counted.com"),
        )
      },
      test("9: an extension raises the profile total only; it does not lift a budget") {
        for {
          r   <- rig
          a   <- kid("A", macA)
          tlr <- ZIO.service[TimeLimitRepo]
          ext <- ZIO.service[TimeExtensionRepo]
          rb  <- app("Roblox", "roblox.com")
          y   <- app("YouTube", "youtube.com")
          g   <- group("Games", rb)
          _   <- attach(a, g, daily = Some(30), weekly = Some(600))
          _   <- assign(y, a)
          _   <- tlr.upsert(a, 30)
          _   <- traffic(r, macA, "roblox.com", at(thu, 10), 40)
          svc <- policyAt(nowThu)
          // 40 counted minutes against a 30-minute total: blocked until the extension is granted.
          v0  <- verdicts(svc, a, macA, "roblox.com", "youtube.com")
          _   <- ext.grantForProfile(a, thu, 30, "test", None)
          v1  <- verdicts(svc, a, macA, "roblox.com", "youtube.com")
        } yield assertTrue(
          blocked(v0, "roblox.com"),
          blocked(v0, "youtube.com"),
          // The extension lifts the profile total, so the uncovered app is reachable again ...
          reachable(v1, "youtube.com"),
          // ... but the group is still over its own daily limit.
          blocked(v1, "roblox.com"),
        )
      },
    ),
    suite("whole-MAC block: Schedule and Pause (rows 9a-9f, 10)")(
      test("9a + 9b + 9d: under every budget, an exempt member outlives Schedule; others do not") {
        for {
          r   <- rig
          a   <- kid("A", macA)
          rb  <- app("Roblox", "roblox.com")
          ex  <- app("Exempt", "exempt.com")
          nx  <- app("NotExempt", "notexempt.com")
          wn  <- app("Windowed", "windowed.com")
          g   <- group("Games", rb)
          _   <- attach(a, g, weekly = Some(600), exempt = true)
          _   <- assign(ex, a, exempt = true)
          _   <- assign(nx, a, exempt = false)
          wa  <- assign(wn, a, daily = Some(120), exempt = false)
          _   <- allowedDuring(wa, "win-9d")
          _   <- bedtime(a)
          _   <- traffic(r, macA, "roblox.com", at(thu, 10), 20)
          svc <- policyAt(nowThu)
          v   <- verdicts(svc, a, macA, "roblox.com", "exempt.com", "notexempt.com", "windowed.com")
          snap <- svc.snapshot
        } yield assertTrue(
          snap.profiles(a).rules.blocked,
          reachable(v, "roblox.com"),
          reachable(v, "exempt.com"),
          blocked(v, "notexempt.com"),
          reachable(v, "windowed.com"),
        )
      },
      test("9c: over any budget, an exempt or windowed app is blocked during Schedule") {
        for {
          r   <- rig
          a   <- kid("A", macA)
          rb  <- app("Roblox", "roblox.com")
          ex  <- app("Exempt", "exempt.com")
          wn  <- app("Windowed", "windowed.com")
          wk  <- app("WeeklyOver", "weekly.com")
          ok  <- app("Under", "under.com")
          g   <- group("Games", rb)
          _   <- attach(a, g, weekly = Some(50), exempt = true)
          _   <- assign(ex, a, daily = Some(30), exempt = true)
          wa  <- assign(wn, a, daily = Some(30), exempt = false)
          _   <- allowedDuring(wa, "win-9c")
          _   <- assign(wk, a, exempt = true)
          _   <- setWeekly(wk, a, 60)
          _   <- assign(ok, a, exempt = true)
          _   <- bedtime(a)
          _   <- traffic(r, macA, "roblox.com", at(tue, 10), 60)
          _   <- traffic(r, macA, "exempt.com", at(thu, 9), 35)
          _   <- traffic(r, macA, "windowed.com", at(thu, 10), 35)
          _   <- traffic(r, macA, "weekly.com", at(tue, 13), 65)
          _   <- traffic(r, macA, "under.com", at(thu, 12), 10)
          svc <- policyAt(nowThu)
          v   <- verdicts(
            svc,
            a,
            macA,
            "roblox.com",
            "exempt.com",
            "windowed.com",
            "weekly.com",
            "under.com",
          )
        } yield assertTrue(
          // The group, the app's own daily cap, an allowed_during window past its daily cap (D17)
          // and the app's own weekly limit each block.
          blocked(v, "roblox.com"),
          blocked(v, "exempt.com"),
          blocked(v, "windowed.com"),
          blocked(v, "weekly.com"),
          // The control under every budget is carved, as an exempt app is today.
          reachable(v, "under.com"),
        )
      },
      test(
        "9e + 9f + 10: soft pause spares an exempt member under budget; hard pause spares none",
      ) {
        for {
          r   <- rig
          a   <- kid("A", macA)
          b   <- kid("B", macB)
          pr  <- ZIO.service[ProfileRepo]
          rb  <- app("Roblox", "roblox.com")
          ex  <- app("Exempt", "exempt.com")
          g   <- group("Games", rb)
          _   <- attach(a, g, weekly = Some(600), exempt = true)
          _   <- attach(b, g, weekly = Some(50), exempt = true)
          _   <- ZIO.foreachDiscard(List(a, b))(p => assign(ex, p, exempt = true))
          _   <- traffic(r, macA, "roblox.com", at(thu, 10), 20)
          _   <- traffic(r, macB, "roblox.com", at(tue, 10), 60)
          _   <- traffic(r, macA, "exempt.com", at(thu, 12), 10)
          _   <- traffic(r, macB, "exempt.com", at(thu, 12), 10)
          _   <- pr.setPaused(a, true)
          _   <- pr.setPaused(b, true)
          svc <- policyAt(nowThu)
          // Soft pause (the default).
          va  <- verdicts(svc, a, macA, "roblox.com", "exempt.com")
          vb  <- verdicts(svc, b, macB, "roblox.com", "exempt.com")
          // Hard pause on A: no carve survives.
          _   <- pr.setPauseMode(a, PauseMode.Hard)
          vh  <- verdicts(svc, a, macA, "roblox.com", "exempt.com")
        } yield assertTrue(
          reachable(va, "roblox.com"),
          reachable(va, "exempt.com"),
          blocked(vb, "roblox.com"),
          reachable(vb, "exempt.com"),
          blocked(vh, "roblox.com"),
          blocked(vh, "exempt.com"),
        )
      },
    ),
    suite("default-deny (row 12)")(
      test("12: reachable within budget, blocked by the baseline once over") {
        for {
          r    <- rig
          a    <- kid("A", macA)
          b    <- kid("B", macB)
          pr   <- ZIO.service[ProfileRepo]
          rb   <- app("Roblox", "roblox.com")
          g    <- group("Games", rb)
          _    <- attach(a, g, weekly = Some(600))
          _    <- attach(b, g, weekly = Some(50))
          _    <- traffic(r, macA, "roblox.com", at(thu, 10), 20)
          _    <- traffic(r, macB, "roblox.com", at(tue, 10), 60)
          pa   <- pr.findById(a).map(_.get)
          pb   <- pr.findById(b).map(_.get)
          _    <- pr.update(pa.copy(defaultDeny = true))
          _    <- pr.update(pb.copy(defaultDeny = true))
          svc  <- policyAt(nowThu)
          snap <- svc.snapshot
        } yield assertTrue(
          snap.profiles(a).rules.blocked,
          snapReachable(snap, a, "roblox.com"),
          snap.profiles(b).rules.blocked,
          !snapReachable(snap, b, "roblox.com"),
        )
      },
    ),
    suite("one group on two profiles (test 10)")(
      test("10: each profile is blocked at its own limit on its own usage") {
        for {
          r   <- rig
          a   <- kid("A", macA)
          b   <- kid("B", macB)
          rb  <- app("Roblox", "roblox.com")
          g   <- group("Games", rb)
          _   <- attach(a, g, daily = Some(30), weekly = Some(600))
          _   <- attach(b, g, daily = Some(60), weekly = Some(600))
          _   <- traffic(r, macA, "roblox.com", at(thu, 10), 35)
          _   <- traffic(r, macB, "roblox.com", at(thu, 10), 35)
          svc <- policyAt(nowThu)
          va  <- verdicts(svc, a, macA, "roblox.com")
          vb  <- verdicts(svc, b, macB, "roblox.com")
        } yield assertTrue(blocked(va, "roblox.com"), reachable(vb, "roblox.com"))
      },
    ),
    suite("covered app with no assignment (test 11)")(
      test("11: enforced by the group alone") {
        for {
          r   <- rig
          a   <- kid("A", macA)
          rb  <- app("Roblox", "roblox.com")
          mc  <- app("Minecraft", "minecraft.net")
          g   <- group("Games", rb, mc)
          _   <- attach(a, g, daily = Some(30), weekly = None)
          _   <- traffic(r, macA, "minecraft.net", at(thu, 10), 35)
          svc <- policyAt(nowThu)
          v   <- verdicts(svc, a, macA, "roblox.com", "minecraft.net")
          al  <- ZIO.serviceWithZIO[AppTimeLimitRepo](_.listForProfile(a))
        } yield assertTrue(
          blocked(v, "roblox.com"),
          blocked(v, "minecraft.net"),
          al.forall(_.coveredOnly),
        )
      },
    ),
    suite("app_budget_exhausted gauge (design §11)")(
      test("one gauge per kind and window, set once per reevaluate tick, back to 0 when cleared") {
        def gauge(kind: String, window: String) =
          zio.metrics.Metric
            .gauge("app_budget_exhausted")
            .tagged("kind", kind)
            .tagged("window", window)
            .value
            .map(_.value)
        for {
          r   <- rig
          a   <- kid("A", macA)
          b   <- kid("B", macB)
          rb  <- app("Roblox", "roblox.com")
          y   <- app("YouTube", "youtube.com")
          g   <- group("Games", rb)
          _   <- attach(a, g, weekly = Some(100))
          _   <- assign(y, b, daily = Some(30))
          _   <- traffic(r, macA, "roblox.com", at(tue, 10), 60)
          _   <- traffic(r, macA, "roblox.com", at(thu, 10), 50)
          _   <- traffic(r, macB, "youtube.com", at(thu, 10), 35)
          svc <- policyAt(nowThu, cacheEnabled = true)
          _   <- svc.reevaluate
          gw  <- gauge("group", "week")
          gd  <- gauge("group", "day")
          ad  <- gauge("app", "day")
          aw  <- gauge("app", "week")
          // Raise the group's limit: the next tick publishes zero, not the stale one.
          _   <- ZIO.serviceWithZIO[Transactor[Task]](xa =>
            sql"UPDATE profile_app_groups SET weekly_minutes = 600 WHERE profile_id = $a".update.run
              .transact(xa),
          )
          _   <- svc.invalidate(hh)
          _   <- svc.reevaluate
          gw2 <- gauge("group", "week")
          ad2 <- gauge("app", "day")
        } yield assertTrue(gw == 1.0, gd == 0.0, ad == 1.0, aw == 0.0, gw2 == 0.0, ad2 == 1.0)
      },
    ),
    suite("GET /api/blocked names the budget (design §8)")(
      test("a group block and an app block carry budget {kind, name, window}") {
        for {
          r   <- rig
          a   <- kid("A", macA)
          b   <- kid("B", macB)
          blr <- ZIO.service[BlocklistRepo]
          rb  <- app("Roblox", "roblox.com")
          y   <- app("YouTube", "youtube.com")
          g   <- group("Games", rb)
          _   <- attach(a, g, weekly = Some(100))
          _   <- assign(y, b, daily = Some(30))
          _   <- traffic(r, macA, "roblox.com", at(tue, 10), 60)
          _   <- traffic(r, macA, "roblox.com", at(thu, 10), 50)
          _   <- traffic(r, macB, "youtube.com", at(thu, 10), 35)
          svc <- policyAt(nowThu)
          routes = BlockedRoutes.routes(
            svc,
            blr,
            BlockPageHousehold.defaultOnly,
            RateLimiter.allowAll,
            RateLimiter.allowAll,
          )
          call   = (mac: MacAddress, host: String) =>
            routes
              .runZIO(
                Request.get(URL.decode(s"/api/blocked?mac=${mac.value}&host=$host").toOption.get),
              )
              .flatMap(_.body.asString)
              .flatMap(s =>
                ZIO.fromEither(s.fromJson[BlockedInfoResponse]).mapError(new RuntimeException(_)),
              )
          ig <- call(macA, "roblox.com")
          ia <- call(macB, "youtube.com")
        } yield assertTrue(
          ig.reasonClass.contains("app_time_limit"),
          ig.budget.contains(BudgetBlockInfo("group", "Games", "week")),
          ia.reasonClass.contains("app_time_limit"),
          ia.budget.contains(BudgetBlockInfo("app", "YouTube", "day")),
        )
      },
    ),
  ) @@ TestAspect.sequential
}
