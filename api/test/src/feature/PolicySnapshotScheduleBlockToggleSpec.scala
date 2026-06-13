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

import java.time.LocalDateTime

/**
 * #1679: `allowedDuringScheduleBlock` toggle on `app_policy_assignments`.
 *
 * An Allowed-mode app normally carves its hosts into `extraAllowed`, beating the whole-MAC schedule
 * block (#421). When `allowedDuringScheduleBlock = false`, that carve-out is suppressed during a
 * Schedule-reason block — the app's hosts are NOT in `extraAllowed`, so the bedtime drop applies.
 * The default is `true`, preserving existing behavior for all existing assignments.
 *
 * Scope: only Schedule-reason blocks. Paused / TimeLimit / Manual are unchanged.
 */
object PolicySnapshotScheduleBlockToggleSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

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

  def spec = suite("PolicySnapshot — allowedDuringScheduleBlock toggle (#1679)")(
    test(
      "app with allowedDuringScheduleBlock=true stays in extraAllowed during schedule block",
    ) {
      // Kids profile has a 21:00–07:00 bedtime block. An Allowed-mode app with
      // allowedDuringScheduleBlock=true (the default) must remain in extraAllowed at 21:30.
      for {
        _     <- cleanDb
        pr    <- ZIO.service[ProfileRepo]
        sr    <- ZIO.service[ScheduleRepo]
        ar    <- ZIO.service[AppRepo]
        kid   <- TestLayers.seedKidsProfile(pr, sr)
        appId <- ar.create("AlwaysAllowed", "always-allowed", None, None)
        _     <- ar.setHosts(appId, List(Hostname.unsafe("always.example.com")))
        // allowedDuringScheduleBlock=true (default) — carve-out survives schedule block
        _     <- ar.upsertAssignment(
          appId,
          kid,
          AppMode.Allowed,
          None,
          true,
          allowedDuringScheduleBlock = true,
        )
        svc   <- makePsAt(TestClock.bedtime)
        snap  <- svc.snapshot
        rules = snap.profiles(kid).rules
        ea    = rules.extraAllowed.map(_.value).toSet
      } yield assertTrue(rules.blocked) &&
        assertTrue(rules.blockReason.map(MacBlockReason.asString).contains("Schedule")) &&
        assertTrue(ea.contains("always.example.com"))
    },
    test(
      "app with allowedDuringScheduleBlock=false is removed from extraAllowed during schedule block",
    ) {
      // Same Kids bedtime block. An Allowed-mode app with allowedDuringScheduleBlock=false
      // must NOT appear in extraAllowed at 21:30 — the router's drop applies.
      for {
        _     <- cleanDb
        pr    <- ZIO.service[ProfileRepo]
        sr    <- ZIO.service[ScheduleRepo]
        ar    <- ZIO.service[AppRepo]
        kid   <- TestLayers.seedKidsProfile(pr, sr)
        appId <- ar.create("ScheduleRespect", "schedule-respect", None, None)
        _     <- ar.setHosts(appId, List(Hostname.unsafe("schedule-respect.example.com")))
        // allowedDuringScheduleBlock=false — carve-out suppressed during schedule block
        _     <- ar.upsertAssignment(
          appId,
          kid,
          AppMode.Allowed,
          None,
          true,
          allowedDuringScheduleBlock = false,
        )
        svc   <- makePsAt(TestClock.bedtime)
        snap  <- svc.snapshot
        rules = snap.profiles(kid).rules
        ea    = rules.extraAllowed.map(_.value).toSet
      } yield assertTrue(rules.blocked) &&
        assertTrue(rules.blockReason.map(MacBlockReason.asString).contains("Schedule")) &&
        assertTrue(!ea.contains("schedule-respect.example.com"))
    },
    test(
      "two apps: allowedDuringScheduleBlock=true keeps its host; false removes its host",
    ) {
      // Both apps in the same profile. At bedtime the true-app's host is in extraAllowed,
      // the false-app's host is NOT.
      for {
        _      <- cleanDb
        pr     <- ZIO.service[ProfileRepo]
        sr     <- ZIO.service[ScheduleRepo]
        ar     <- ZIO.service[AppRepo]
        kid    <- TestLayers.seedKidsProfile(pr, sr)
        keepId <- ar.create("Keep", "keep-app", None, None)
        _      <- ar.setHosts(keepId, List(Hostname.unsafe("keep.example.com")))
        _      <- ar.upsertAssignment(
          keepId,
          kid,
          AppMode.Allowed,
          None,
          true,
          allowedDuringScheduleBlock = true,
        )
        dropId <- ar.create("Drop", "drop-app", None, None)
        _      <- ar.setHosts(dropId, List(Hostname.unsafe("drop.example.com")))
        _      <- ar.upsertAssignment(
          dropId,
          kid,
          AppMode.Allowed,
          None,
          true,
          allowedDuringScheduleBlock = false,
        )
        svc    <- makePsAt(TestClock.bedtime)
        snap   <- svc.snapshot
        rules = snap.profiles(kid).rules
        ea    = rules.extraAllowed.map(_.value).toSet
      } yield assertTrue(rules.blocked) &&
        assertTrue(rules.blockReason.map(MacBlockReason.asString).contains("Schedule")) &&
        assertTrue(ea.contains("keep.example.com")) &&
        assertTrue(!ea.contains("drop.example.com"))
    },
    test(
      "allowedDuringScheduleBlock=false does NOT remove host outside schedule block (school afternoon)",
    ) {
      // Outside bedtime the profile is not blocked. The false-app's host is still in extraAllowed
      // (no schedule block active — the toggle only fires during Schedule reason).
      for {
        _     <- cleanDb
        pr    <- ZIO.service[ProfileRepo]
        sr    <- ZIO.service[ScheduleRepo]
        ar    <- ZIO.service[AppRepo]
        kid   <- TestLayers.seedKidsProfile(pr, sr)
        appId <- ar.create("ScheduleRespect2", "schedule-respect2", None, None)
        _     <- ar.setHosts(appId, List(Hostname.unsafe("sr2.example.com")))
        _     <- ar.upsertAssignment(
          appId,
          kid,
          AppMode.Allowed,
          None,
          true,
          allowedDuringScheduleBlock = false,
        )
        svc   <- makePsAt(TestClock.schoolDayAfternoon)
        snap  <- svc.snapshot
        rules = snap.profiles(kid).rules
        ea    = rules.extraAllowed.map(_.value).toSet
      } yield assertTrue(!rules.blocked) &&
        assertTrue(ea.contains("sr2.example.com"))
    },
    test(
      "allowedDuringScheduleBlock=false does NOT suppress hosts during Paused block (toggle is schedule-only)",
    ) {
      // When the profile is paused (not schedule), the false-app is still in extraAllowed
      // (for soft pause). The toggle only applies to Schedule-reason blocks.
      for {
        _     <- cleanDb
        pr    <- ZIO.service[ProfileRepo]
        sr    <- ZIO.service[ScheduleRepo]
        ar    <- ZIO.service[AppRepo]
        kid   <- TestLayers.seedKidsProfile(pr, sr)
        // Pause the profile
        _     <- pr.setPaused(kid, paused = true)
        appId <- ar.create("PauseIgnored", "pause-ignored", None, None)
        _     <- ar.setHosts(appId, List(Hostname.unsafe("pause-ignored.example.com")))
        // allowedDuringScheduleBlock=false — but Paused, not Schedule, so toggle is irrelevant
        _     <- ar.upsertAssignment(
          appId,
          kid,
          AppMode.Allowed,
          None,
          true,
          allowedDuringScheduleBlock = false,
        )
        // school afternoon: no bedtime schedule active, only paused
        svc   <- makePsAt(TestClock.schoolDayAfternoon)
        snap  <- svc.snapshot
        rules = snap.profiles(kid).rules
        ea    = rules.extraAllowed.map(_.value).toSet
      } yield assertTrue(rules.blocked) &&
        assertTrue(rules.blockReason.map(MacBlockReason.asString).contains("Paused")) &&
        assertTrue(ea.contains("pause-ignored.example.com"))
    },
  )
}
