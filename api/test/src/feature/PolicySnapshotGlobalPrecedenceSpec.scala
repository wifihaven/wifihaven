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
 * #1322 (test coverage for the #1308 global-policy layer + per-profile default-deny): pins the
 * snapshot-side half of the precedence table in docs/design/global-policy-layer.md §5.3, filling
 * the gaps left by PolicySnapshotGlobalSectionSpec / PolicySnapshotGlobalAllowSpec (#1318):
 *
 *   1. `DefaultDeny` is the LOWEST-precedence reason — a concurrent Schedule or TimeLimit wins the
 *      block-page copy (the #1318 spec only pinned Paused > DefaultDeny). 2. Default-deny composes
 *      orthogonally with a schedule: both collapse into `blocked = true`, the stronger reason is
 *      reported, and `extraAllowed` still carves out (consistent with #421). 3. The fleet-wide
 *      `global` section is assembled ONCE and is independent of why any per-MAC is blocked: a
 *      configured `global.extraAllowed` host is present regardless of the per-MAC block reason
 *      (paused / schedule / time-limit / default-deny / global lockdown). This is the
 *      snapshot-shape guarantee behind the router's "global.extraAllowed carves out every drop"
 *      enforcement (which render_spec.lua pins directly).
 *
 * The actual carve-out / precedence ENFORCEMENT lives at the router and is covered by
 * openwrt/test/render_spec.lua; here we only assert the API emits the correct wire shape.
 */
object PolicySnapshotGlobalPrecedenceSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  // Real GlobalPolicyRepo wired (so the global section is assembled from the V48 tables), plus a
  // caller-controlled clock so schedule / time-limit windows can be exercised. uiAllowedHosts = Nil
  // to isolate the DB-backed global section from the per-deployment UI hosts.
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
      gpr    <- ZIO.service[GlobalPolicyRepo]
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
      Nil,
      gpr,
    ): PolicyService

  private def seedRouterRow: ZIO[RouterRepo, Throwable, RouterId] =
    ZIO.serviceWithZIO[RouterRepo](_.create("gw-seed", Sha256Hex.unsafe("o" * 64)))

  // Mirror of PolicySnapshotBlockedMacsSpec.seedTraffic: insert enough 5-minute presence buckets to
  // accumulate `minutes` of used time for (mac, host) on `date`, above the heartbeat byte floor.
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
          500_000L,
          500_000L,
        )
      }.toList
      tr.insertBatch(inserts).unit
    }

  // 2025-01-06 is the Monday that schoolDayAfternoon / bedtime fall on (matches BlockedMacsSpec).
  private val day = LocalDate.of(2025, 1, 6)

  private def kidsRules(snap: PolicySnapshot, kid: ProfileId): BlockRules =
    snap.profiles(kid).rules

  def spec = suite("policy snapshot — global precedence + default-deny compositions (#1322)")(
    // ── DefaultDeny is the lowest-precedence reason (§3.3) ──────────────────
    test(
      "default-deny + active schedule (bedtime) → blocked, reason=Schedule (Schedule > DefaultDeny)",
    ) {
      for {
        _   <- cleanDb
        pr  <- ZIO.service[ProfileRepo]
        sr  <- ZIO.service[ScheduleRepo]
        ps0 <- pr.listAll
        kids = ps0.find(_.name == "Kids").get
        _    <- pr.update(kids.copy(defaultDeny = true))
        svc  <- makePsAt(TestClock.bedtime)
        snap <- svc.snapshot
      } yield {
        val rules = kidsRules(snap, kids.id)
        assertTrue(
          rules.blocked,
          rules.blockReason.contains(MacBlockReason.Schedule),
        )
      }
    },
    test(
      "default-deny + exhausted daily limit → blocked, reason=TimeLimit (TimeLimit > DefaultDeny)",
    ) {
      for {
        _   <- cleanDb
        pr  <- ZIO.service[ProfileRepo]
        tlr <- ZIO.service[TimeLimitRepo]
        dr  <- ZIO.service[DeviceRepo]
        ps0 <- pr.listAll
        kids = ps0.find(_.name == "Kids").get
        _    <- pr.update(kids.copy(defaultDeny = true))
        _    <- tlr.upsert(kids.id, 120)
        _    <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kids.id)
        rid  <- seedRouterRow
        _    <- seedTraffic(rid, "aa:bb:cc:11:22:33", "cnn.com", day, 125)
        svc  <- makePsAt(TestClock.schoolDayAfternoon)
        snap <- svc.snapshot
      } yield {
        val rules = kidsRules(snap, kids.id)
        assertTrue(
          rules.blocked,
          rules.blockReason.contains(MacBlockReason.TimeLimit),
        )
      }
    },
    // ── Default-deny composes orthogonally with a schedule, extraAllowed survives ──
    test("default-deny + schedule still carries the profile allow list (extraAllowed carves out)") {
      for {
        _   <- cleanDb
        pr  <- ZIO.service[ProfileRepo]
        ar  <- ZIO.service[AppRepo]
        ps0 <- pr.listAll
        kids = ps0.find(_.name == "Kids").get
        _    <- TestLayers.seedAppAssignment(ar, kids.id, "pbskids.org", AppMode.Allowed)
        _    <- pr.update(kids.copy(defaultDeny = true))
        svc  <- makePsAt(TestClock.bedtime) // inside the bedtime schedule window
        snap <- svc.snapshot
      } yield {
        val rules = kidsRules(snap, kids.id)
        assertTrue(
          rules.blocked,
          // schedule wins the reported reason even though default-deny is also active
          rules.blockReason.contains(MacBlockReason.Schedule),
          // the explicit allow is still reachable — the block (whatever its reason) is carved out
          rules.extraAllowed.map(_.value).contains("pbskids.org"),
          // extraBlocked / blocklistIds stay omitted under block-all (default-deny slimming)
          rules.extraBlocked.isEmpty,
          rules.blocklistIds.isEmpty,
        )
      }
    },
    // ── The global section is assembled once, independent of the per-MAC block reason ──
    test("global.extraAllowed is present regardless of why a MAC is blocked (paused)") {
      assertGlobalAllowIndependentOfBlock { (pr, kids) => pr.update(kids.copy(paused = true)) }(
        TestClock.schoolDayAfternoon,
        MacBlockReason.Paused,
      )
    },
    test("global.extraAllowed is present regardless of why a MAC is blocked (schedule)") {
      assertGlobalAllowIndependentOfBlock { (_, _) =>
        ZIO.unit // the seeded Kids profile already has a bedtime schedule
      }(TestClock.bedtime, MacBlockReason.Schedule)
    },
    test("global.extraAllowed is present regardless of why a MAC is blocked (default-deny)") {
      assertGlobalAllowIndependentOfBlock { (pr, kids) =>
        pr.update(kids.copy(defaultDeny = true))
      }(TestClock.schoolDayAfternoon, MacBlockReason.DefaultDeny)
    },
    test("global.extraAllowed is present even under a whole-network global lockdown") {
      for {
        _    <- cleanDb
        gpr  <- ZIO.service[GlobalPolicyRepo]
        _    <- gpr.addAllow(Hostname.unsafe("always.example"), Some("pki"), None)
        _    <- gpr.setFlags(blocked = true, Some(MacBlockReason.Manual), blockIpOnly = false)
        svc  <- makePsAt(TestClock.schoolDayAfternoon)
        snap <- svc.snapshot
      } yield assertTrue(
        snap.global.blocked,
        snap.global.blockReason.contains(MacBlockReason.Manual),
        snap.global.extraAllowed.map(_.value).contains("always.example"),
      )
    },
    // ── A global block and a per-MAC allow coexist in the snapshot (router resolves precedence) ──
    test(
      "a global block and a per-MAC extraAllowed both ship; a host may be in BOTH global lists",
    ) {
      for {
        _   <- cleanDb
        pr  <- ZIO.service[ProfileRepo]
        ar  <- ZIO.service[AppRepo]
        gpr <- ZIO.service[GlobalPolicyRepo]
        ps0 <- pr.listAll
        kids = ps0.find(_.name == "Kids").get
        // a per-MAC (profile) allow…
        _    <- TestLayers.seedAppAssignment(ar, kids.id, "school.example", AppMode.Allowed)
        // …a global block that a per-MAC allow may NOT loosen…
        _    <- gpr.addBlock(Hostname.unsafe("evil.example"), Some("operator"), None)
        // …and the same host on BOTH global lists — global.extraAllowed must win at the router.
        _    <- gpr.addAllow(Hostname.unsafe("evil.example"), Some("override"), None)
        svc  <- makePsAt(TestClock.schoolDayAfternoon)
        snap <- svc.snapshot
      } yield {
        val g = snap.global
        assertTrue(
          // global block ships
          g.extraBlocked.map(_.value).contains("evil.example"),
          // global allow ships the same host (top of the ladder — router carves it out)
          g.extraAllowed.map(_.value).contains("evil.example"),
          // the per-MAC allow ships independently on the profile
          kidsRules(snap, kids.id).extraAllowed.map(_.value).contains("school.example"),
          // and it is NOT promoted into the global block-suppressing layer
          !g.extraAllowed.map(_.value).contains("school.example"),
        )
      }
    },
  ) @@ TestAspect.sequential

  /**
   * Helper for the "global section is independent of the per-MAC block reason" cases: configure a
   * `global.extraAllowed` host, apply `block` (which forces the Kids profile into `blocked = true`
   * via some reason), and assert both that the MAC is blocked with `expectedReason` AND that the
   * global allow host is present in `snap.global.extraAllowed` (i.e. the global section did not
   * change because of the per-MAC block).
   */
  private def assertGlobalAllowIndependentOfBlock(
      block: (ProfileRepo, Profile) => Task[Unit],
  )(at: LocalDateTime, expectedReason: MacBlockReason) =
    for {
      _   <- cleanDb
      pr  <- ZIO.service[ProfileRepo]
      dr  <- ZIO.service[DeviceRepo]
      gpr <- ZIO.service[GlobalPolicyRepo]
      _   <- gpr.addAllow(Hostname.unsafe("always.example"), Some("pki"), None)
      ps0 <- pr.listAll
      kids = ps0.find(_.name == "Kids").get
      _    <- block(pr, kids)
      _    <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kids.id)
      svc  <- makePsAt(at)
      snap <- svc.snapshot
    } yield {
      val rules = kidsRules(snap, kids.id)
      assertTrue(
        rules.blocked,
        rules.blockReason.contains(expectedReason),
        // the global allow host is carried once, unaffected by the per-MAC block
        snap.global.extraAllowed.map(_.value).contains("always.example"),
        // and it is NOT copied into the blocked profile's own extraAllowed
        !rules.extraAllowed.map(_.value).contains("always.example"),
      )
    }
}
