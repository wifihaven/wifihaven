package wifihaven.api.feature

import doobie.*
import doobie.implicits.*
import cats.implicits.*
import wifihaven.api.db.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.api.policy.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.interop.catz.*
import zio.test.*

import java.time.{Instant, LocalDate, LocalDateTime, LocalTime, ZoneId, ZoneOffset}

/**
 * #3004 (epic #2930, design `docs/design/weekly-limits-and-app-groups.md` §18): a profile's group
 * attachment takes the same named-schedule rules an app assignment takes (V51), and they fold
 * through the same per-app evaluation path. Every §18.4 row is a feature test through the real
 * snapshot builder AND the per-host `decideDetailed`, which must agree (#1532). Each blocked
 * assertion sits beside a reachable host from the same rig and each reachable one beside a blocked
 * one, so a rig that blocks everything, or nothing, cannot pass.
 *
 * Rules are written straight into `profile_app_group_schedule_rules` for the enforcement rows, so
 * those rows test the read and the fold apart from the write path, which the API suite covers.
 *
 * Fixture week as in `GroupModesSpec`: Thursday 2025-01-09 16:00 UTC is "now".
 */
object GroupScheduleRulesSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Transactor[Task] & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(LocalDateTime.of(2025, 1, 9, 16, 0))

  private val hh = HouseholdId.Default

  private val mon = LocalDate.of(2025, 1, 6)
  private val thu = mon.plusDays(3)

  private def at(d: LocalDate, h: Int, m: Int = 0): Instant =
    LocalDateTime.of(d, LocalTime.of(h, m)).toInstant(ZoneOffset.UTC)

  private val nowThu = at(thu, 16)

  private val macA = MacAddress.unsafe("aa:bb:cc:30:04:01")
  private val macB = MacAddress.unsafe("aa:bb:cc:30:04:02")

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
      rid <- ZIO.serviceWithZIO[RouterRepo](_.create("gw-3004", Sha256Hex.unsafe("v" * 64)))
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

  private def app(name: String, hosts: String*): ZIO[AppRepo, Throwable, AppId] =
    ZIO.serviceWithZIO[AppRepo] { ar =>
      for {
        id <- ar.create(name, name.toLowerCase.replace(' ', '-'), None, None)
        _  <- ar.setHostEntries(id, hosts.toList.map(h => AppHostEntry(Hostname.unsafe(h), false)))
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

  private def appGroupRepoAt(now: Instant): RIO[Transactor[Task], AppGroupRepo] =
    for {
      xa <- ZIO.service[Transactor[Task]]
      c  <- Clock.TestClock.makeWithControl(LocalDateTime.ofInstant(now, ZoneOffset.UTC))
    } yield new AppGroupRepoLive(xa, c._1)

  /** Attaches through the repo, so its write of the two V93 columns is under test too. */
  private def attach(
      p: ProfileId,
      g: AppGroupId,
      mode: AppMode,
      exempt: Boolean = false,
      allowedDuringScheduleBlock: Boolean = true,
      daily: Option[Int] = None,
      weekly: Option[Int] = None,
  ): RIO[Transactor[Task], ProfileAppGroup] =
    appGroupRepoAt(nowThu)
      .flatMap(
        _.upsertAttachment(hh, p, g, daily, weekly, exempt, mode, allowedDuringScheduleBlock),
      )
      .flatMap(ZIO.fromEither(_))
      .mapError(e => new RuntimeException(s"attach failed: $e"))
      .orDie

  /** A profile with one device. */
  private def kid(name: String, mac: MacAddress, categories: List[BlocklistId] = Nil) =
    for {
      pr <- ZIO.service[ProfileRepo]
      p  <- pr.create(name, categories)
      _  <- ZIO.serviceWithZIO[DeviceRepo](TestLayers.seedDevice(_, mac.value, mac.value, p))
    } yield p

  // A window covering Thursday 16:00 (UTC), and one that does not.
  private val activeWindow   =
    ScheduleWindow(
      List("mon", "tue", "wed", "thu", "fri", "sat", "sun"),
      LocalTime.of(15, 0),
      LocalTime.of(17, 0),
      ZoneId.of("UTC"),
    )
  private val inactiveWindow =
    activeWindow.copy(startLocal = LocalTime.of(6, 0), endLocal = LocalTime.of(7, 0))

  private def bedtime(p: ProfileId) =
    for {
      nsr <- ZIO.service[NamedScheduleRepo]
      sid <- nsr.create(s"Bed#${p.value}", None, List(activeWindow))
      _   <- nsr.setProfileBlockSchedules(p, List(sid))
    } yield ()

  /** Adds a rule to an attachment directly in the V96 table. */
  private def groupRule(
      a: ProfileAppGroup,
      p: ProfileId,
      mode: AppScheduleMode,
      w: ScheduleWindow,
  ) =
    for {
      nsr <- ZIO.service[NamedScheduleRepo]
      sid <- nsr.create(
        s"G${a.groupId.value}-P${p.value}-${AppScheduleMode.asString(mode)}-${w.startLocal}",
        None,
        List(w),
      )
      _   <- ZIO.serviceWithZIO[Transactor[Task]] { xa =>
        val m = AppScheduleMode.asString(mode)
        sql"""INSERT INTO profile_app_group_schedule_rules (household_id, attachment_id, schedule_id, mode)
              SELECT household_id, id, $sid, $m FROM profile_app_groups
               WHERE profile_id = $p AND group_id = ${a.groupId}""".update.run.transact(xa)
      }
    } yield sid

  // An `allowed_during` window, active now, on an app's own assignment.
  private def ownAllowedDuring(assignment: AppPolicyAssignmentId, name: String) =
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

  private type Verdicts = Map[String, (Boolean, Boolean)]

  /** The router's predicate: extraAllowed beats `blocked`, extraBlocked and every blocklist. */
  private def snapReachable(
      snap: PolicySnapshot,
      p: ProfileId,
      host: String,
      blocklisted: Set[String],
  ): Boolean = {
    val r = snap.profiles(p).rules
    r.extraAllowed.exists(h => HostMatch.matchesPattern(host, h.value)) ||
    !(r.blocked || r.extraBlocked.exists(h => HostMatch.matchesPattern(host, h.value)) ||
      blocklisted.contains(host))
  }

  private def verdicts(
      svc: PolicyService,
      p: ProfileId,
      mac: MacAddress,
      hosts: List[String],
      blocklisted: Set[String] = Set.empty,
  ): Task[Verdicts] =
    for {
      snap <- svc.snapshot
      ds   <- ZIO.foreach(hosts)(h =>
        svc
          .decideDetailed(hh, mac.value, h)
          .map(d => h -> (snapReachable(snap, p, h, blocklisted), d)),
      )
    } yield ds.map { case (h, (s, d)) =>
      h -> (s, d.response.decision == ConnectionDecision.Allow)
    }.toMap

  private def reachable(v: Verdicts, h: String): Boolean = v(h) == ((true, true))
  private def blocked(v: Verdicts, h: String): Boolean   = v(h) == ((false, false))

  private val AD = AppScheduleMode.AllowedDuring
  private val BD = AppScheduleMode.BlockedDuring

  // ── tests ──────────────────────────────────────────────────────────────────────────────────

  def spec = suite("GroupScheduleRulesSpec (#3004)")(
    suite("Blocked group with allowed_during (rows 26-30)")(
      test("26 + 27: reachable inside the window, blocked outside it; unrelated host unaffected") {
        for {
          _   <- rig
          a   <- kid("A", macA)
          b   <- kid("B", macB)
          rb  <- app("Roblox", "roblox.com", "rbxcdn.com")
          g   <- group("Games", rb)
          ga  <- attach(a, g, AppMode.Blocked)
          gb  <- attach(b, g, AppMode.Blocked)
          _   <- groupRule(ga, a, AD, activeWindow)
          _   <- groupRule(gb, b, AD, inactiveWindow)
          svc <- policyAt(nowThu)
          hosts = List("roblox.com", "rbxcdn.com", "unrelated.com")
          va   <- verdicts(svc, a, macA, hosts)
          vb   <- verdicts(svc, b, macB, hosts)
          snap <- svc.snapshot
        } yield assertTrue(
          reachable(va, "roblox.com"),
          reachable(va, "rbxcdn.com"),
          reachable(va, "unrelated.com"),
          blocked(vb, "roblox.com"),
          blocked(vb, "rbxcdn.com"),
          reachable(vb, "unrelated.com"),
          // The window carves through extraAllowed; it never sets a whole-MAC flag.
          snap.profiles(a).rules.extraAllowed.exists(_.value == "roblox.com"),
          !snap.profiles(a).rules.blocked,
        )
      },
      test("28: the window lifts the group's block, not a member's own Blocked row (D24)") {
        for {
          _   <- rig
          a   <- kid("A", macA)
          rb  <- app("Roblox", "roblox.com")
          mc  <- app("Minecraft", "minecraft.net")
          _   <- assign(mc, a, mode = AppMode.Blocked)
          g   <- group("Games", rb, mc)
          ga  <- attach(a, g, AppMode.Blocked)
          _   <- groupRule(ga, a, AD, activeWindow)
          svc <- policyAt(nowThu)
          v   <- verdicts(svc, a, macA, List("roblox.com", "minecraft.net"))
        } yield assertTrue(reachable(v, "roblox.com"), blocked(v, "minecraft.net"))
      },
      test("29: reachable during bedtime, which still blocks everything else") {
        for {
          _   <- rig
          a   <- kid("A", macA)
          rb  <- app("Roblox", "roblox.com")
          g   <- group("Games", rb)
          ga  <- attach(a, g, AppMode.Blocked)
          _   <- groupRule(ga, a, AD, activeWindow)
          _   <- bedtime(a)
          svc <- policyAt(nowThu)
          v   <- verdicts(svc, a, macA, List("roblox.com", "unrelated.com"))
        } yield assertTrue(reachable(v, "roblox.com"), blocked(v, "unrelated.com"))
      },
      test(
        "29 (#1679): a stored toggle left off from an earlier Allowed mode does not suppress it",
      ) {
        for {
          _   <- rig
          a   <- kid("A", macA)
          rb  <- app("Roblox", "roblox.com")
          g   <- group("Games", rb)
          _   <- attach(a, g, AppMode.Allowed, allowedDuringScheduleBlock = false)
          ga  <- attach(a, g, AppMode.Blocked, allowedDuringScheduleBlock = false)
          _   <- groupRule(ga, a, AD, activeWindow)
          _   <- bedtime(a)
          svc <- policyAt(nowThu)
          v   <- verdicts(svc, a, macA, List("roblox.com", "unrelated.com"))
        } yield assertTrue(reachable(v, "roblox.com"), blocked(v, "unrelated.com"))
      },
      test(
        "30: once the daily total is spent, a counted group's window yields; an exempt one's does not",
      ) {
        for {
          r   <- rig
          a   <- kid("A", macA)
          b   <- kid("B", macB)
          tlr <- ZIO.service[TimeLimitRepo]
          rb  <- app("Roblox", "roblox.com")
          z   <- app("Counted", "counted.com")
          g   <- group("Games", rb)
          ga  <- attach(a, g, AppMode.Blocked, exempt = false)
          gb  <- attach(b, g, AppMode.Blocked, exempt = true)
          _   <- groupRule(ga, a, AD, activeWindow) *> groupRule(gb, b, AD, activeWindow)
          _   <- tlr.upsert(a, 30) *> tlr.upsert(b, 30) *> assign(z, a) *> assign(z, b)
          _   <- traffic(r, macA, "counted.com", at(thu, 9), 40)
          _   <- traffic(r, macB, "counted.com", at(thu, 9), 40)
          svc <- policyAt(nowThu)
          va  <- verdicts(svc, a, macA, List("roblox.com", "unrelated.com"))
          vb  <- verdicts(svc, b, macB, List("roblox.com", "unrelated.com"))
        } yield assertTrue(
          blocked(va, "roblox.com"),
          blocked(va, "unrelated.com"),
          reachable(vb, "roblox.com"),
          blocked(vb, "unrelated.com"),
        )
      },
      test("both rules active on one attachment: allowed_during wins, as for an app") {
        for {
          _   <- rig
          a   <- kid("A", macA)
          rb  <- app("Roblox", "roblox.com")
          g   <- group("Games", rb)
          ga  <- attach(a, g, AppMode.Blocked)
          _   <- groupRule(ga, a, BD, activeWindow) *> groupRule(ga, a, AD, activeWindow)
          svc <- policyAt(nowThu)
          v   <- verdicts(svc, a, macA, List("roblox.com"))
          // Control on the same rig: the blocked_during rule alone still blocks.
          _   <- ZIO.serviceWithZIO[Transactor[Task]](xa =>
            sql"DELETE FROM profile_app_group_schedule_rules WHERE mode = 'allowed_during'".update.run
              .transact(xa),
          )
          v2  <- verdicts(svc, a, macA, List("roblox.com"))
        } yield assertTrue(reachable(v, "roblox.com"), blocked(v2, "roblox.com"))
      },
    ),
    suite("Allowed group with blocked_during (rows 31, 32)")(
      test("31: blocked inside the window, reachable outside; unrelated host unaffected") {
        for {
          _   <- rig
          a   <- kid("A", macA)
          b   <- kid("B", macB)
          rb  <- app("Roblox", "roblox.com")
          g   <- group("Games", rb)
          ga  <- attach(a, g, AppMode.Allowed)
          gb  <- attach(b, g, AppMode.Allowed)
          _   <- groupRule(ga, a, BD, activeWindow)
          _   <- groupRule(gb, b, BD, inactiveWindow)
          svc <- policyAt(nowThu)
          va  <- verdicts(svc, a, macA, List("roblox.com", "unrelated.com"))
          vb  <- verdicts(svc, b, macB, List("roblox.com", "unrelated.com"))
        } yield assertTrue(
          blocked(va, "roblox.com"),
          reachable(va, "unrelated.com"),
          reachable(vb, "roblox.com"),
          reachable(vb, "unrelated.com"),
        )
      },
      test("32: a member's own allowed_during does not lift the group's blocked_during (D20)") {
        for {
          _   <- rig
          a   <- kid("A", macA)
          rb  <- app("Roblox", "roblox.com")
          ed  <- app("Edu", "edu.org")
          rba <- assign(rb, a, mode = AppMode.Allowed)
          eda <- assign(ed, a, mode = AppMode.Blocked)
          _   <- ownAllowedDuring(rba, "RB own") *> ownAllowedDuring(eda, "Edu own")
          g   <- group("Games", rb)
          ga  <- attach(a, g, AppMode.Allowed)
          _   <- groupRule(ga, a, BD, activeWindow)
          svc <- policyAt(nowThu)
          v   <- verdicts(svc, a, macA, List("roblox.com", "edu.org"))
        } yield assertTrue(blocked(v, "roblox.com"), reachable(v, "edu.org"))
      },
    ),
    suite("TimeLimited group (row 33, D25)")(
      test("33: a window carves at bedtime under budget, and is withheld once the group is over") {
        // Counted (not exempt), so nothing but the window can carve the group at bedtime (D16).
        for {
          r   <- rig
          a   <- kid("A", macA)
          b   <- kid("B", macB)
          rb  <- app("Roblox", "roblox.com")
          g   <- group("Games", rb)
          ga  <- attach(a, g, AppMode.TimeLimited, exempt = false, daily = Some(30))
          gb  <- attach(b, g, AppMode.TimeLimited, exempt = false, daily = Some(30))
          _   <- groupRule(ga, a, AD, activeWindow) *> groupRule(gb, b, AD, activeWindow)
          _   <- bedtime(a) *> bedtime(b)
          _   <- traffic(r, macA, "roblox.com", at(thu, 9), 40)
          _   <- traffic(r, macB, "roblox.com", at(thu, 9), 10)
          svc <- policyAt(nowThu)
          va  <- verdicts(svc, a, macA, List("roblox.com", "unrelated.com"))
          vb  <- verdicts(svc, b, macB, List("roblox.com", "unrelated.com"))
        } yield assertTrue(
          blocked(va, "roblox.com"),
          blocked(va, "unrelated.com"),
          reachable(vb, "roblox.com"),
          blocked(vb, "unrelated.com"),
        )
      },
    ),
    suite("rule lifecycle and the gauge")(
      test("rules survive a mode change and go with the attachment on detach") {
        def count =
          ZIO.serviceWithZIO[Transactor[Task]](xa =>
            sql"SELECT count(*) FROM profile_app_group_schedule_rules"
              .query[Int]
              .unique
              .transact(xa),
          )
        for {
          _     <- rig
          a     <- kid("A", macA)
          rb    <- app("Roblox", "roblox.com")
          g     <- group("Games", rb)
          ga    <- attach(a, g, AppMode.Blocked)
          _     <- groupRule(ga, a, AD, activeWindow)
          _     <- attach(a, g, AppMode.Allowed)
          kept  <- count
          read  <- appGroupRepoAt(nowThu).flatMap(_.listAttachments(hh, a))
          _     <- appGroupRepoAt(nowThu).flatMap(_.deleteAttachment(hh, a, g))
          after <- count
        } yield assertTrue(
          kept == 1,
          read.flatMap(_.scheduleRules.map(_.mode)) == List(AD),
          after == 0,
        )
      },
      test("the repo replaces an attachment's rules when given a list, and keeps them when not") {
        for {
          _    <- rig
          a    <- kid("A", macA)
          rb   <- app("Roblox", "roblox.com")
          g    <- group("Games", rb)
          nsr  <- ZIO.service[NamedScheduleRepo]
          s1   <- nsr.create("Homework", None, List(activeWindow))
          s2   <- nsr.create("Weekend", None, List(inactiveWindow))
          repo <- appGroupRepoAt(nowThu)
          w1   <- repo
            .upsertAttachment(
              hh,
              a,
              g,
              None,
              None,
              false,
              AppMode.Blocked,
              true,
              Some(List((s1, AD))),
            )
            .flatMap(ZIO.fromEither(_))
            .orDieWith(e => new RuntimeException(e.toString))
          // Absent: unchanged.
          w2   <- repo
            .upsertAttachment(hh, a, g, None, None, false, AppMode.Blocked, true, None)
            .flatMap(ZIO.fromEither(_))
            .orDieWith(e => new RuntimeException(e.toString))
          // A list replaces.
          w3   <- repo
            .upsertAttachment(
              hh,
              a,
              g,
              None,
              None,
              false,
              AppMode.Blocked,
              true,
              Some(List((s2, BD))),
            )
            .flatMap(ZIO.fromEither(_))
            .orDieWith(e => new RuntimeException(e.toString))
        } yield assertTrue(
          w1.scheduleRules.map(r => (r.scheduleId, r.mode)) == List((s1, AD)),
          w2.scheduleRules.map(r => (r.scheduleId, r.mode)) == List((s1, AD)),
          w3.scheduleRules.map(r => (r.scheduleId, r.mode)) == List((s2, BD)),
        )
      },
      test("one gauge series per mode, counting attachments with a rule of that mode") {
        def gauge(mode: String) =
          zio.metrics.Metric
            .gauge("app_group_rule_attachments")
            .tagged("mode", mode)
            .value
            .map(_.value)
        for {
          _   <- rig
          a   <- kid("A", macA)
          b   <- kid("B", macB)
          rb  <- app("Roblox", "roblox.com")
          g   <- group("Games", rb)
          ga  <- attach(a, g, AppMode.Blocked)
          gb  <- attach(b, g, AppMode.Allowed)
          // Two allowed_during rules on one attachment still count it once.
          _   <- groupRule(ga, a, AD, activeWindow) *> groupRule(ga, a, AD, inactiveWindow)
          _   <- groupRule(gb, b, AD, activeWindow)
          svc <- policyAt(nowThu, cacheEnabled = true)
          _   <- svc.reevaluate
          ad  <- gauge("allowed_during")
          bd  <- gauge("blocked_during")
        } yield assertTrue(ad == 2.0, bd == 0.0)
      },
    ),
  ) @@ TestAspect.sequential
}
