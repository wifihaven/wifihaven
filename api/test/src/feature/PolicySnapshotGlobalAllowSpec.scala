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
 * #944 → #1318: deployment-configured UI hosts (wifihaven.policy.uiAllowedHosts) are fleet-wide
 * always-reachable hosts. As of #1318 they are emitted ONCE in `snapshot.global.extraAllowed`
 * (which carves out every block at the router) rather than copied into every profile's
 * `extraAllowed`. This spec pins the relocated shape: the UI hosts appear in the global section and
 * NOT in per-profile `extraAllowed`, while the curated infra-allow hosts (#1307, retired in #1321)
 * stay copied per-profile for now.
 */
object PolicySnapshotGlobalAllowSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val uiHosts: List[Hostname] =
    List("wifihaven.net", "api.wifihaven.net").map(Hostname.unsafe)

  // makePs wires the real GlobalPolicyRepo so the global section is assembled from the DB +
  // uiAllowedHosts, exactly as production does.
  private def makePs =
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
      clock  <- ZIO.service[Clock]
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
      clock,
      uiHosts,
      gpr,
    ): PolicyService

  private val expected: Set[String] = uiHosts.map(_.value).toSet

  def spec = suite("policy snapshot — global allow hosts (#944 / #1318)")(
    test(
      "empty uiAllowedHosts → per-profile extraAllowed is the app-derived list plus infra hosts",
    ) {
      for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        sr   <- ZIO.service[ScheduleRepo]
        hsr  <- ZIO.service[HouseholdSettingsRepo]
        tlr  <- ZIO.service[TimeLimitRepo]
        stlr <- ZIO.service[SiteTimeLimitRepo]
        dr   <- ZIO.service[DeviceRepo]
        blr  <- ZIO.service[BlocklistRepo]
        trr  <- ZIO.service[TrafficReportRepo]
        er   <- ZIO.service[TimeExtensionRepo]
        ar   <- ZIO.service[AppRepo]
        gpr  <- ZIO.service[GlobalPolicyRepo]
        clk  <- ZIO.service[Clock]
        ps0  <- pr.listAll
        kids = ps0.find(_.name == "Kids").get
        _ <- TestLayers.seedAppAssignment(ar, kids.id, "user.example", AppMode.Allowed)
        svc = PolicyServiceLive(
          pr,
          sr,
          hsr,
          tlr,
          stlr,
          dr,
          blr,
          trr,
          er,
          ar,
          clk,
          Nil, // empty uiAllowedHosts
          gpr,
        )
        snap <- svc.snapshot
      } yield assertTrue(
        snap.profiles(kids.id).rules.extraAllowed.map(_.value).toSet ==
          (Set("user.example") ++ PolicyService.infraAllowHosts.map(_.value).toSet),
        // global is inert (no DB global_allow rows, no ui hosts configured)
        snap.global.extraAllowed.isEmpty,
      )
    },
    test("configured ui hosts land in global.extraAllowed once, NOT per-profile") {
      for {
        _         <- cleanDb
        pr        <- ZIO.service[ProfileRepo]
        ar        <- ZIO.service[AppRepo]
        profiles0 <- pr.listAll
        kids = profiles0.find(_.name == "Kids").get
        _    <- TestLayers.seedAppAssignment(ar, kids.id, "already-in.example", AppMode.Allowed)
        ps   <- makePs
        snap <- ps.snapshot
      } yield {
        val globalAllowed = snap.global.extraAllowed.map(_.value).toSet
        // ui hosts present in the global section exactly once
        val globalCovers  = expected.subsetOf(globalAllowed)
        val noGlobalDupes = snap.global.extraAllowed.distinct == snap.global.extraAllowed
        // ui hosts are NOT copied into any profile's extraAllowed anymore
        val noProfileCopy = snap.profiles.values.forall(p =>
          expected.intersect(p.rules.extraAllowed.map(_.value).toSet).isEmpty,
        )
        // the profile's own app allow still flows per-profile
        val kidsAllowed   =
          snap.profiles(kids.id).rules.extraAllowed.map(_.value)
        assertTrue(
          globalCovers,
          noGlobalDupes,
          noProfileCopy,
          kidsAllowed.contains("already-in.example"),
        )
      }
    },
    test(
      "global section is independent of any profile (carries ui hosts even with zero app allows)",
    ) {
      for {
        _    <- cleanDb
        ps   <- makePs
        snap <- ps.snapshot
      } yield assertTrue(
        snap.profiles.nonEmpty,
        expected.subsetOf(snap.global.extraAllowed.map(_.value).toSet),
      )
    },
    test("paused profile blocks but ui hosts stay reachable via global.extraAllowed") {
      for {
        _   <- cleanDb
        pr  <- ZIO.service[ProfileRepo]
        ps0 <- pr.listAll
        kids = ps0.find(_.name == "Kids").get
        _    <- pr.setPaused(kids.id, true)
        svc  <- makePs
        snap <- svc.snapshot
      } yield {
        val rules = snap.profiles(kids.id).rules
        assertTrue(
          rules.blocked,
          // ui hosts are NOT in the paused profile's own extraAllowed — they carve out via global
          expected.intersect(rules.extraAllowed.map(_.value).toSet).isEmpty,
          expected.subsetOf(snap.global.extraAllowed.map(_.value).toSet),
        )
      }
    },
    test("every profile's extraAllowed includes the #1337 Apple connectivity-test host") {
      // #1337: Apple's network-connectivity-test CDN is a device-level infra dep that
      // was dropped under a whole-MAC block. It lives in the curated infraAllowHosts
      // constant, still copied per-profile (the #1307 copy is retired separately in #1321).
      val newHosts = Set("netcts.cdn-apple.com")
      for {
        _    <- cleanDb
        svc  <- makePs
        snap <- svc.snapshot
      } yield assertTrue(
        snap.profiles.nonEmpty,
        snap.profiles.values.forall(p => newHosts.subsetOf(p.rules.extraAllowed.map(_.value).toSet)),
      )
    },
    test("every profile's extraAllowed includes the #1411 gvt2.com Google infra domain") {
      // #1411: gvt2.com is Google's connectivity-check / Play / download infra
      // domain, a device-level transitive dep dropped under a whole-MAC block. It
      // lives in the curated infraAllowHosts constant, so it must surface in every
      // profile's extraAllowed (copied per-profile, allow beats the @blocked_macs
      // drop via #421 ea_ enforcement). Added as the apex `gvt2.com` so the
      // router's trailing-suffix match carves out every *.gvt2.com subdomain,
      // including the rotating download shards.
      val newHosts = Set("gvt2.com")
      for {
        _    <- cleanDb
        svc  <- makePs
        snap <- svc.snapshot
      } yield assertTrue(
        snap.profiles.nonEmpty,
        snap.profiles.values.forall(p => newHosts.subsetOf(p.rules.extraAllowed.map(_.value).toSet)),
      )
    },
    test(
      "ui host blocked as an app appears in per-profile extraBlocked; global.extraAllowed still saves it",
    ) {
      for {
        _   <- cleanDb
        pr  <- ZIO.service[ProfileRepo]
        ar  <- ZIO.service[AppRepo]
        ps0 <- pr.listAll
        kids = ps0.find(_.name == "Kids").get
        _    <- TestLayers.seedAppAssignment(ar, kids.id, "wifihaven.net", AppMode.Blocked)
        svc  <- makePs
        snap <- svc.snapshot
      } yield {
        val rules         = snap.profiles(kids.id).rules
        val blocked       = rules.extraBlocked.map(_.value).toSet
        val globalAllowed = snap.global.extraAllowed.map(_.value).toSet
        assertTrue(
          blocked.contains("wifihaven.net"),
          // the ui host is allow-listed at the global layer, which beats the per-MAC block at the
          // router (allow-beats-block, top of the precedence ladder)
          globalAllowed.contains("wifihaven.net"),
        )
      }
    },
  ) @@ TestAspect.sequential
}
