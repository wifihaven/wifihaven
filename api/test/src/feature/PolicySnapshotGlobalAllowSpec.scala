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
 * #944: deployment-configured UI hosts (wifihaven.policy.uiAllowedHosts) must appear in every
 * profile's snapshot `extraAllowed`, regardless of paused state or whether the profile lists one of
 * them in `extraBlocked`. Router enforces allow-beats-block; this spec pins that the snapshot emits
 * the union. The list is per-deployment so prod doesn't let staging hosts through and vice versa.
 */
object PolicySnapshotGlobalAllowSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val uiHosts: List[Hostname] =
    List("wifihaven.net", "api.wifihaven.net").map(Hostname.unsafe)

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
    ): PolicyService

  private val expected: Set[String] = uiHosts.map(_.value).toSet

  def spec = suite("policy snapshot — global allow hosts (#944)")(
    test("empty uiAllowedHosts → snapshot extraAllowed is just the app-derived list") {
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
        ) // uiAllowedHosts defaults to Nil
        snap <- svc.snapshot
      } yield assertTrue(
        snap.profiles(kids.id).rules.extraAllowed.map(_.value) == List("user.example"),
      )
    },
    test("every profile's emitted extraAllowed includes all configured ui hosts (union)") {
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
        val perProfileAllowed = snap.profiles.values.map(_.rules.extraAllowed.map(_.value).toSet)
        val allCover          = perProfileAllowed.forall(expected.subsetOf)
        val kidsAllowed       = snap.profiles
          .find(_._2.name == "Kids")
          .get
          ._2
          .rules
          .extraAllowed
          .map(_.value)
        val noDupes           = kidsAllowed.distinct == kidsAllowed
        val carriesExisting   = kidsAllowed.contains("already-in.example")
        assertTrue(allCover) && assertTrue(noDupes) && assertTrue(carriesExisting)
      }
    },
    test("paused profile still emits global hosts in extraAllowed") {
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
        assertTrue(rules.blocked) &&
        assertTrue(expected.subsetOf(rules.extraAllowed.map(_.value).toSet))
      }
    },
    test(
      "global host in extraBlocked still appears in extraAllowed (allow-beats-block at router)",
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
        val rules   = snap.profiles(kids.id).rules
        val blocked = rules.extraBlocked.map(_.value).toSet
        val allowed = rules.extraAllowed.map(_.value).toSet
        assertTrue(blocked.contains("wifihaven.net")) &&
        assertTrue(allowed.contains("wifihaven.net")) &&
        assertTrue(expected.subsetOf(allowed))
      }
    },
  ) @@ TestAspect.sequential
}
