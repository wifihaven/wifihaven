package wifihaven.api.feature

import wifihaven.api.db.*
import wifihaven.api.policy.*
import wifihaven.shared.*
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.json.*
import zio.test.*

/**
 * #385: PolicySnapshot must carry the per-profile failureMode so the agent can pick the right
 * failover behaviour after 5 minutes of API unreachability. Three modes: BlockAll → drop all
 * forwarded traffic; AllowAll → pass everything; LastKnownGood → keep enforcing the cached snapshot
 * exactly as-is.
 */
object PolicySnapshotFailureModeSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  def spec = suite("PolicySnapshot — failureMode (#385)")(
    test("snapshot carries each profile's failureMode value") {
      for {
        _      <- cleanDb
        pr     <- ZIO.service[ProfileRepo]
        hsr    <- ZIO.service[HouseholdSettingsRepo]
        tlr    <- ZIO.service[TimeLimitRepo]
        atlr   <- ZIO.service[AppTimeLimitRepo]
        dr     <- ZIO.service[DeviceRepo]
        blr    <- ZIO.service[BlocklistRepo]
        trRepo <- ZIO.service[TrafficReportRepo]
        er     <- ZIO.service[TimeExtensionRepo]
        ar     <- ZIO.service[AppRepo]
        clock  <- ZIO.service[Clock]
        svc = PolicyServiceLive(pr, hsr, tlr, atlr, dr, blr, trRepo, er, ar, clock)
        profiles0 <- pr.listAllAcrossHouseholds
        kidsId   = profiles0.find(_.name == "Kids").get.id
        adultsId = profiles0.find(_.name == "Adults").get.id
        // Force the two seeded profiles into known modes.
        _    <- pr.update(
          profiles0.find(_.name == "Kids").get.copy(failureMode = FailureMode.BlockAll),
        )
        _    <- pr.update(
          profiles0.find(_.name == "Adults").get.copy(failureMode = FailureMode.LastKnownGood),
        )
        snap <- svc.snapshot
        kids   = snap.profiles(kidsId)
        adults = snap.profiles(adultsId)
      } yield assertTrue(kids.failureMode == FailureMode.BlockAll) &&
        assertTrue(adults.failureMode == FailureMode.LastKnownGood)
    },
    test("failureMode serializes as lower-kebab on the wire (#385)") {
      // The lua agent reads snapshot.failureMode as a plain string; pin the
      // exact wire spelling so render.lua's comparisons don't drift.
      for {
        _      <- cleanDb
        pr     <- ZIO.service[ProfileRepo]
        hsr    <- ZIO.service[HouseholdSettingsRepo]
        tlr    <- ZIO.service[TimeLimitRepo]
        atlr   <- ZIO.service[AppTimeLimitRepo]
        dr     <- ZIO.service[DeviceRepo]
        blr    <- ZIO.service[BlocklistRepo]
        trRepo <- ZIO.service[TrafficReportRepo]
        er     <- ZIO.service[TimeExtensionRepo]
        ar     <- ZIO.service[AppRepo]
        clock  <- ZIO.service[Clock]
        svc = PolicyServiceLive(pr, hsr, tlr, atlr, dr, blr, trRepo, er, ar, clock)
        profiles0 <- pr.listAllAcrossHouseholds
        _         <- pr.update(
          profiles0.find(_.name == "Kids").get.copy(failureMode = FailureMode.BlockAll),
        )
        _         <- pr.update(
          profiles0.find(_.name == "Adults").get.copy(failureMode = FailureMode.LastKnownGood),
        )
        snap      <- svc.snapshot
        json = snap.toJson
      } yield assertTrue(json.contains("\"failureMode\":\"block-all\"")) &&
        assertTrue(json.contains("\"failureMode\":\"last-known-good\""))
    },
    test("ETag flips when a profile's failureMode changes") {
      for {
        _      <- cleanDb
        pr     <- ZIO.service[ProfileRepo]
        hsr    <- ZIO.service[HouseholdSettingsRepo]
        tlr    <- ZIO.service[TimeLimitRepo]
        atlr   <- ZIO.service[AppTimeLimitRepo]
        dr     <- ZIO.service[DeviceRepo]
        blr    <- ZIO.service[BlocklistRepo]
        trRepo <- ZIO.service[TrafficReportRepo]
        er     <- ZIO.service[TimeExtensionRepo]
        ar     <- ZIO.service[AppRepo]
        clock  <- ZIO.service[Clock]
        svc = PolicyServiceLive(pr, hsr, tlr, atlr, dr, blr, trRepo, er, ar, clock)
        profiles0 <- pr.listAllAcrossHouseholds
        _         <- pr.update(
          profiles0.find(_.name == "Kids").get.copy(failureMode = FailureMode.BlockAll),
        )
        snap1     <- svc.snapshot
        _         <- pr.update(
          profiles0.find(_.name == "Kids").get.copy(failureMode = FailureMode.LastKnownGood),
        )
        snap2     <- svc.snapshot
      } yield assertTrue(snap1.etag != snap2.etag)
    },
  ) @@ TestAspect.sequential
}
