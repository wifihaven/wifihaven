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

/**
 * #1318 / #1308: PolicyService assembles `snapshot.global` (a fleet-wide `BlockRules`) from the V48
 * global-policy tables, and collapses a per-profile `default_deny` flag into block-all +
 * `DefaultDeny`. This spec covers the global-section assembly, default-deny evaluation, the
 * precedence of `DefaultDeny` vs. stronger reasons, default-deny + `blockIpOnly`, and that a change
 * to the global section moves the ETag.
 *
 * The router-side composition (`global.extraAllowed` actually carving out a block) is a separate
 * issue (#1319); here we pin only that the snapshot the API emits is correct.
 */
object PolicySnapshotGlobalSectionSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private def makePs =
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
      gpr    <- ZIO.service[GlobalPolicyRepo]
      clock  <- ZIO.service[Clock]
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
      clock,
      Nil, // no ui hosts — isolate the DB-backed global section
      gpr,
    ): PolicyService

  def spec = suite("policy snapshot — global section + default-deny (#1318)")(
    test(
      "global section is assembled from global_allow / global_blocks / global_blocklists + flags",
    ) {
      for {
        _    <- cleanDb
        blr  <- ZIO.service[BlocklistRepo]
        gpr  <- ZIO.service[GlobalPolicyRepo]
        // a blocklist must exist before it can be associated globally (FK)
        _    <- blr.upsertMeta(
          BlocklistId.unsafe("ads"),
          "Ads",
          None,
          bundled = true,
          None,
          java.time.Instant.parse("2026-01-01T00:00:00Z"),
        )
        _    <- gpr.addAllow(Hostname.unsafe("infra.example"), Some("pki"), None)
        _    <- gpr.addBlock(Hostname.unsafe("evil.example"), Some("operator"), None)
        _    <- gpr.addBlocklist(BlocklistId.unsafe("ads"), None)
        _    <- gpr.setFlags(blocked = false, None, blockIpOnly = true)
        ps   <- makePs
        snap <- ps.snapshot
      } yield {
        val g     = snap.global
        val ea    = g.extraAllowed.map(_.value).toSet
        // #1321: the curated infra allowlist is unioned into global.extraAllowed
        // alongside the DB-backed global_allow rows, so assert membership rather
        // than an exact list (no ui hosts are configured in this spec).
        val infra = PolicyService.infraAllowHosts.map(_.value).toSet
        assertTrue(
          ea.contains("infra.example"),
          infra.subsetOf(ea),
          ea == infra + "infra.example",
          g.extraBlocked.map(_.value) == List("evil.example"),
          g.blocklistIds.map(_.value) == List("ads"),
          g.blockIpOnly,
          !g.blocked,
          g.blockReason.isEmpty,
        )
      }
    },
    test("soft-deleted global_allow rows are excluded from global.extraAllowed") {
      for {
        _    <- cleanDb
        gpr  <- ZIO.service[GlobalPolicyRepo]
        _    <- gpr.addAllow(Hostname.unsafe("kept.example"), None, None)
        _    <- gpr.addAllow(Hostname.unsafe("removed.example"), None, None)
        _    <- gpr.removeAllow(Hostname.unsafe("removed.example"), None)
        ps   <- makePs
        snap <- ps.snapshot
      } yield {
        val ea = snap.global.extraAllowed.map(_.value).toSet
        // #1321: kept DB row + the curated infra constant; removed row excluded.
        assertTrue(
          ea.contains("kept.example"),
          !ea.contains("removed.example"),
          PolicyService.infraAllowHosts.map(_.value).toSet.subsetOf(ea),
        )
      }
    },
    test("global lockdown flag → global.blocked + reason from household_settings") {
      for {
        _    <- cleanDb
        gpr  <- ZIO.service[GlobalPolicyRepo]
        _    <- gpr.setFlags(blocked = true, Some(MacBlockReason.Manual), blockIpOnly = false)
        ps   <- makePs
        snap <- ps.snapshot
      } yield assertTrue(
        snap.global.blocked,
        snap.global.blockReason.contains(MacBlockReason.Manual),
      )
    },
    test(
      "default-deny profile collapses to blocked=true + DefaultDeny, only extraAllowed reachable",
    ) {
      for {
        _   <- cleanDb
        pr  <- ZIO.service[ProfileRepo]
        ar  <- ZIO.service[AppRepo]
        ps0 <- pr.listAll
        kids = ps0.find(_.name == "Kids").get
        // give it an explicit allow + flip default-deny on
        _    <- TestLayers.seedAppAssignment(ar, kids.id, "pbskids.org", AppMode.Allowed)
        _    <- pr.update(kids.copy(defaultDeny = true))
        svc  <- makePs
        snap <- svc.snapshot
      } yield {
        val rules = snap.profiles(kids.id).rules
        assertTrue(
          rules.blocked,
          rules.blockReason.contains(MacBlockReason.DefaultDeny),
          // only the explicit per-profile allow is reachable here; the curated
          // infra set carves out separately via global.extraAllowed (#1321)
          rules.extraAllowed.map(_.value).contains("pbskids.org"),
          // extraBlocked / blocklistIds are omitted under block-all even though the
          // profile carries blocked_categories
          rules.extraBlocked.isEmpty,
          rules.blocklistIds.isEmpty,
        )
      }
    },
    test("a NON default-deny profile still emits its blocklistIds (control)") {
      for {
        _   <- cleanDb
        pr  <- ZIO.service[ProfileRepo]
        ps0 <- pr.listAll
        kids = ps0.find(_.name == "Kids").get
        svc  <- makePs
        snap <- svc.snapshot
      } yield assertTrue(
        snap.profiles(kids.id).rules.blocklistIds.nonEmpty,
        snap.profiles(kids.id).rules.blockReason.forall(_ != MacBlockReason.DefaultDeny),
      )
    },
    test("default-deny + a stronger reason (paused) reports the stronger reason") {
      for {
        _   <- cleanDb
        pr  <- ZIO.service[ProfileRepo]
        ps0 <- pr.listAll
        kids = ps0.find(_.name == "Kids").get
        _    <- pr.update(kids.copy(defaultDeny = true, paused = true))
        svc  <- makePs
        snap <- svc.snapshot
      } yield {
        val rules = snap.profiles(kids.id).rules
        assertTrue(
          rules.blocked,
          // Paused outranks DefaultDeny in the block-page precedence ladder
          rules.blockReason.contains(MacBlockReason.Paused),
        )
      }
    },
    test("default-deny honors the profile's blockIpOnly (strictest combination)") {
      for {
        _   <- cleanDb
        pr  <- ZIO.service[ProfileRepo]
        ps0 <- pr.listAll
        kids = ps0.find(_.name == "Kids").get
        _    <- pr.update(kids.copy(defaultDeny = true, blockIpOnly = true))
        svc  <- makePs
        snap <- svc.snapshot
      } yield {
        val rules = snap.profiles(kids.id).rules
        assertTrue(rules.blocked, rules.blockIpOnly)
      }
    },
    test("a change to the global section moves the snapshot ETag") {
      for {
        _   <- cleanDb
        gpr <- ZIO.service[GlobalPolicyRepo]
        svc <- makePs
        e0  <- svc.snapshot.map(_.etag)
        _   <- gpr.addAllow(Hostname.unsafe("newinfra.example"), None, None)
        e1  <- svc.snapshot.map(_.etag)
      } yield assertTrue(e0 != e1)
    },
  ) @@ TestAspect.sequential
}
