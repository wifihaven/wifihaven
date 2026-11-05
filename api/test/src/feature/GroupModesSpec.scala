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
 * #2980 (epic #2930, design `docs/design/weekly-limits-and-app-groups.md` §17): a profile's
 * attachment of a group has a mode. Blocked blocks every member, Allowed allows every member as an
 * Allowed app is allowed, and TimeLimited is the group budget `AppsOverBudgetSpec` covers. Where a
 * member also has its own row on the profile, the stricter of the two wins (D20).
 *
 * Every §17.4 row is a feature test through the real snapshot builder AND the per-host
 * `decideDetailed`, which must agree (#1532). Each blocked assertion sits beside a reachable
 * unrelated host from the same rig, so a rig that blocks everything cannot pass, and each reachable
 * assertion beside a blocked one, so a rig that blocks nothing cannot either.
 *
 * Fixture week as in `AppsOverBudgetSpec`: Monday 2025-01-06, UTC, midnight reset, "now" is
 * Thursday 2025-01-09 16:00.
 */
object GroupModesSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Transactor[Task] & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(LocalDateTime.of(2025, 1, 9, 16, 0))

  private val hh = HouseholdId.Default

  private val mon = LocalDate.of(2025, 1, 6)
  private val thu = mon.plusDays(3)

  private def at(d: LocalDate, h: Int, m: Int = 0): Instant =
    LocalDateTime.of(d, LocalTime.of(h, m)).toInstant(ZoneOffset.UTC)

  private val nowThu = at(thu, 16)

  private val macA = MacAddress.unsafe("aa:bb:cc:29:80:01")
  private val macB = MacAddress.unsafe("aa:bb:cc:29:80:02")

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
      rid <- ZIO.serviceWithZIO[RouterRepo](_.create("gw-2980", Sha256Hex.unsafe("v" * 64)))
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

  // ── tests ──────────────────────────────────────────────────────────────────────────────────

  def spec = suite("GroupModesSpec (#2980)")(
    suite("Blocked group (rows 16, 17, 25)")(
      test("16 + 17: every member is blocked, an own Allowed row included; others reachable") {
        for {
          _   <- rig
          a   <- kid("A", macA)
          rb  <- app("Roblox", "roblox.com", "rbxcdn.com")
          mc  <- app("Minecraft", "minecraft.net")
          kh  <- app("Khan", "khan.org")
          // Minecraft is also Allowed on its own: the stricter Blocked group wins (D20).
          _   <- assign(mc, a, mode = AppMode.Allowed)
          _   <- assign(kh, a, mode = AppMode.Allowed)
          g   <- group("Games", rb, mc)
          _   <- attach(a, g, AppMode.Blocked)
          svc <- policyAt(nowThu)
          hosts = List("roblox.com", "rbxcdn.com", "minecraft.net", "khan.org", "unrelated.com")
          v    <- verdicts(svc, a, macA, hosts)
          snap <- svc.snapshot
          rules = snap.profiles(a).rules
        } yield assertTrue(
          blocked(v, "roblox.com"),
          blocked(v, "rbxcdn.com"),
          blocked(v, "minecraft.net"),
          reachable(v, "khan.org"),
          reachable(v, "unrelated.com"),
          // A per-host drop, never a whole-MAC one.
          !rules.blocked,
          !rules.extraAllowed.exists(_.value == "minecraft.net"),
        )
      },
      test("25: a host also on an unrelated Allowed app stays reachable (allow beats block)") {
        for {
          _   <- rig
          a   <- kid("A", macA)
          rb  <- app("Roblox", "roblox.com", "sharedish.com")
          ed  <- app("Edu", "edu.org", "sharedish.com")
          _   <- assign(ed, a, mode = AppMode.Allowed)
          g   <- group("Games", rb)
          _   <- attach(a, g, AppMode.Blocked)
          svc <- policyAt(nowThu)
          v   <- verdicts(svc, a, macA, List("roblox.com", "sharedish.com", "edu.org"))
        } yield assertTrue(
          blocked(v, "roblox.com"),
          reachable(v, "sharedish.com"),
          reachable(v, "edu.org"),
        )
      },
    ),
    suite("Allowed group with no whole-MAC block (rows 18-20)")(
      test("18: every member is carved through a category blocklist; others are not") {
        for {
          _   <- rig
          a   <- kid("A", macA, List(BlocklistId.unsafe("games")))
          blr <- ZIO.service[BlocklistRepo]
          _   <- blr.insertBatch(
            List(
              ("roblox.com", "games"),
              ("minecraft.net", "games"),
              ("fortnite.com", "games"),
            ),
          )
          rb  <- app("Roblox", "roblox.com")
          mc  <- app("Minecraft", "minecraft.net")
          g   <- group("Games", rb, mc)
          _   <- attach(a, g, AppMode.Allowed)
          svc <- policyAt(nowThu)
          bl = Set("roblox.com", "minecraft.net", "fortnite.com")
          v    <- verdicts(svc, a, macA, bl.toList :+ "unrelated.com", bl)
          snap <- svc.snapshot
        } yield assertTrue(
          snap.profiles(a).rules.blocklistIds.map(_.value).contains("games"),
          reachable(v, "roblox.com"),
          reachable(v, "minecraft.net"),
          // A blocklisted host in no member app is still dropped.
          blocked(v, "fortnite.com"),
          reachable(v, "unrelated.com"),
        )
      },
      test("19 + 20: an own Blocked row, or an own exhausted limit, beats the Allowed group") {
        for {
          r   <- rig
          a   <- kid("A", macA)
          rb  <- app("Roblox", "roblox.com")
          mc  <- app("Minecraft", "minecraft.net")
          ft  <- app("Fortnite", "fortnite.com")
          _   <- assign(mc, a, mode = AppMode.Blocked)
          _   <- assign(ft, a, daily = Some(30))
          g   <- group("Games", rb, mc, ft)
          _   <- attach(a, g, AppMode.Allowed)
          _   <- traffic(r, macA, "fortnite.com", at(thu, 10), 40)
          svc <- policyAt(nowThu)
          v   <- verdicts(svc, a, macA, List("roblox.com", "minecraft.net", "fortnite.com"))
        } yield assertTrue(
          reachable(v, "roblox.com"),
          blocked(v, "minecraft.net"),
          blocked(v, "fortnite.com"),
        )
      },
      test("an own TimeLimited row under its limit stays reachable inside the Allowed group") {
        for {
          r   <- rig
          a   <- kid("A", macA)
          ft  <- app("Fortnite", "fortnite.com")
          _   <- assign(ft, a, daily = Some(60))
          g   <- group("Games", ft)
          _   <- attach(a, g, AppMode.Allowed)
          _   <- traffic(r, macA, "fortnite.com", at(thu, 10), 20)
          svc <- policyAt(nowThu)
          v   <- verdicts(svc, a, macA, List("fortnite.com"))
        } yield assertTrue(reachable(v, "fortnite.com"))
      },
    ),
    suite("Allowed group under a whole-MAC block (rows 21-24)")(
      test("21 + 22: bedtime spares an Allowed group whose toggle is on, not one whose is off") {
        for {
          _   <- rig
          a   <- kid("A", macA)
          b   <- kid("B", macB)
          rb  <- app("Roblox", "roblox.com")
          g   <- group("Games", rb)
          _   <- attach(a, g, AppMode.Allowed, allowedDuringScheduleBlock = true)
          _   <- attach(b, g, AppMode.Allowed, allowedDuringScheduleBlock = false)
          _   <- bedtime(a) *> bedtime(b)
          svc <- policyAt(nowThu)
          va  <- verdicts(svc, a, macA, List("roblox.com", "unrelated.com"))
          vb  <- verdicts(svc, b, macB, List("roblox.com", "unrelated.com"))
        } yield assertTrue(
          reachable(va, "roblox.com"),
          blocked(va, "unrelated.com"),
          blocked(vb, "roblox.com"),
          blocked(vb, "unrelated.com"),
        )
      },
      test("21 (overlap): an own Allowed row with its toggle off turns the group's off too") {
        for {
          _   <- rig
          a   <- kid("A", macA)
          rb  <- app("Roblox", "roblox.com")
          mc  <- app("Minecraft", "minecraft.net")
          _   <- assign(mc, a, mode = AppMode.Allowed)
          _   <- ZIO.serviceWithZIO[Transactor[Task]](xa =>
            sql"""UPDATE app_policy_assignments SET allowed_during_schedule_block = FALSE
                   WHERE app_id = $mc AND profile_id = $a""".update.run.transact(xa),
          )
          g   <- group("Games", rb, mc)
          _   <- attach(a, g, AppMode.Allowed, allowedDuringScheduleBlock = true)
          _   <- bedtime(a)
          svc <- policyAt(nowThu)
          v   <- verdicts(svc, a, macA, List("roblox.com", "minecraft.net"))
        } yield assertTrue(reachable(v, "roblox.com"), blocked(v, "minecraft.net"))
      },
      test("23 + 24: daily total and soft pause spare an Allowed group; a hard pause does not") {
        for {
          r   <- rig
          a   <- kid("A", macA)
          b   <- kid("B", macB)
          pr  <- ZIO.service[ProfileRepo]
          tlr <- ZIO.service[TimeLimitRepo]
          rb  <- app("Roblox", "roblox.com")
          z   <- app("Counted", "counted.com")
          g   <- group("Games", rb)
          _   <- attach(a, g, AppMode.Allowed)
          _   <- attach(b, g, AppMode.Allowed)
          // A: the profile daily total is spent on a counted app.
          _   <- tlr.upsert(a, 30) *> assign(z, a)
          _   <- traffic(r, macA, "counted.com", at(thu, 9), 40)
          // B: soft pause, then hard.
          _   <- pr.setPaused(b, true)
          svc <- policyAt(nowThu)
          va  <- verdicts(svc, a, macA, List("roblox.com", "counted.com"))
          vs  <- verdicts(svc, b, macB, List("roblox.com", "unrelated.com"))
          _   <- pr.setPauseMode(b, PauseMode.Hard)
          vh  <- verdicts(svc, b, macB, List("roblox.com", "unrelated.com"))
        } yield assertTrue(
          reachable(va, "roblox.com"),
          blocked(va, "counted.com"),
          reachable(vs, "roblox.com"),
          blocked(vs, "unrelated.com"),
          blocked(vh, "roblox.com"),
          blocked(vh, "unrelated.com"),
        )
      },
    ),
    suite("the exempt flag by mode (D22) and budgets (§17.3)")(
      test("an Allowed group's minutes count toward the daily total only when not exempt") {
        for {
          r   <- rig
          a   <- kid("A", macA)
          b   <- kid("B", macB)
          tlr <- ZIO.service[TimeLimitRepo]
          rb  <- app("Roblox", "roblox.com")
          g   <- group("Games", rb)
          _   <- attach(a, g, AppMode.Allowed, exempt = true)
          _   <- attach(b, g, AppMode.Allowed, exempt = false)
          _   <- tlr.upsert(a, 30) *> tlr.upsert(b, 30)
          _   <- traffic(r, macA, "roblox.com", at(thu, 9), 40)
          _   <- traffic(r, macB, "roblox.com", at(thu, 9), 40)
          svc <- policyAt(nowThu)
          va  <- verdicts(svc, a, macA, List("roblox.com", "unrelated.com"))
          vb  <- verdicts(svc, b, macB, List("roblox.com", "unrelated.com"))
        } yield assertTrue(
          // Exempt: 40 minutes on Roblox spend none of A's 30-minute total.
          reachable(va, "unrelated.com"),
          // Counted: B's total is spent, but the Allowed carve outlives it (row 23, #2747).
          blocked(vb, "unrelated.com"),
          reachable(va, "roblox.com"),
          reachable(vb, "roblox.com"),
        )
      },
      test("only a TimeLimited attachment makes a group budget") {
        for {
          _  <- rig
          a  <- kid("A", macA)
          b  <- kid("B", macB)
          c  <- kid("C", MacAddress.unsafe("aa:bb:cc:29:80:03"))
          rb <- app("Roblox", "roblox.com")
          g  <- group("Games", rb)
          _  <- attach(a, g, AppMode.Allowed)
          _  <- attach(b, g, AppMode.Blocked)
          _  <- attach(c, g, AppMode.TimeLimited, weekly = Some(600))
          da <- ZIO.serviceWithZIO[AppTimeLimitRepo](_.listForProfile(a))
          db <- ZIO.serviceWithZIO[AppTimeLimitRepo](_.listForProfile(b))
          dc <- ZIO.serviceWithZIO[AppTimeLimitRepo](_.listForProfile(c))
          pa = ProfileAppDispositions.from(da)
          pb = ProfileAppDispositions.from(db)
          pc = ProfileAppDispositions.from(dc)
        } yield assertTrue(
          pa.groupBudgets.isEmpty,
          pb.groupBudgets.isEmpty,
          pc.groupBudgets.map(_.key) == List(BudgetKey.Group(g)),
          pa.perApp.map(_.mode) == List(AppMode.Allowed),
          pb.perApp.map(_.mode) == List(AppMode.Blocked),
          pc.perApp.map(_.mode) == List(AppMode.TimeLimited),
        )
      },
    ),
    suite("a limitless TimeLimited row is Allowed (D23)")(
      test("as #2973's image writes it: Allowed in the API read and in enforcement") {
        for {
          r      <- rig
          a      <- kid("A", macA)
          tlr    <- ZIO.service[TimeLimitRepo]
          kh     <- app("Khan", "khan.org")
          z      <- app("Counted", "counted.com")
          g      <- group("Homework", kh)
          // #2973's upsert writes no `mode`, so the row stores 'time_limited' with no limit.
          _      <- ZIO.serviceWithZIO[Transactor[Task]](xa => sql"""INSERT INTO profile_app_groups
                    (household_id, profile_id, group_id, daily_minutes, weekly_minutes,
                     exempt_from_daily)
                  VALUES ($hh, $a, $g, NULL, NULL, FALSE)""".update.run.transact(xa).unit)
          stored <- ZIO.serviceWithZIO[Transactor[Task]](xa =>
            sql"SELECT mode FROM profile_app_groups WHERE profile_id = $a"
              .query[String]
              .unique
              .transact(xa),
          )
          ls     <- appGroupRepoAt(nowThu).flatMap(_.listAttachments(hh, a))
          // Counted, and the daily total is spent: an Allowed member outlives it (#2747).
          _      <- tlr.upsert(a, 30) *> assign(z, a)
          _      <- traffic(r, macA, "khan.org", at(thu, 9), 40)
          svc    <- policyAt(nowThu)
          v      <- verdicts(svc, a, macA, List("khan.org", "counted.com"))
        } yield assertTrue(
          stored == "time_limited",
          ls.map(_.mode) == List(AppMode.Allowed),
          reachable(v, "khan.org"),
          blocked(v, "counted.com"),
        )
      },
    ),
    suite("repo round-trip and the app_group_attachments gauge")(
      test("mode and the toggle round-trip; changing mode keeps the row valid") {
        for {
          _  <- rig
          a  <- kid("A", macA)
          rb <- app("Roblox", "roblox.com")
          g  <- group("Games", rb)
          t  <- attach(a, g, AppMode.TimeLimited, daily = Some(30))
          al <- attach(a, g, AppMode.Allowed, allowedDuringScheduleBlock = false)
          bl <- attach(a, g, AppMode.Blocked)
          ls <- appGroupRepoAt(nowThu).flatMap(_.listAttachments(hh, a))
        } yield assertTrue(
          (t.mode, t.dailyMinutes) == ((AppMode.TimeLimited, Some(30))),
          (al.mode, al.allowedDuringScheduleBlock, al.dailyMinutes) ==
            ((AppMode.Allowed, false, None)),
          bl.mode == AppMode.Blocked,
          ls.map(_.mode) == List(AppMode.Blocked),
        )
      },
      test("one gauge series per mode, set on the reevaluate tick, zero when none") {
        def gauge(mode: String) =
          zio.metrics.Metric
            .gauge("app_group_attachments")
            .tagged("mode", mode)
            .value
            .map(_.value)
        for {
          _   <- rig
          a   <- kid("A", macA)
          b   <- kid("B", macB)
          rb  <- app("Roblox", "roblox.com")
          mc  <- app("Minecraft", "minecraft.net")
          g   <- group("Games", rb, mc)
          _   <- attach(a, g, AppMode.Blocked)
          _   <- attach(b, g, AppMode.Allowed)
          svc <- policyAt(nowThu, cacheEnabled = true)
          _   <- svc.reevaluate
          gb  <- gauge("blocked")
          ga  <- gauge("allowed")
          gt  <- gauge("time_limited")
        } yield assertTrue(gb == 1.0, ga == 1.0, gt == 0.0)
      },
    ),
  ) @@ TestAspect.sequential
}
