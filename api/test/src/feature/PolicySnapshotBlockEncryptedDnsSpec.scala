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
 * #1912 / #1909: the network-wide "block encrypted DNS & relays" toggle lives on `household_settings`
 * and surfaces on the wire as the additive top-level `PolicySnapshot.blockEncryptedDns` boolean. The
 * router agent (#1911) bakes the curated relay/DoH host + resolver-IP lists itself; the snapshot only
 * carries the boolean. This spec pins that the snapshot reflects the household setting and that
 * toggling it moves the ETag (so the fleet re-polls).
 */
object PolicySnapshotBlockEncryptedDnsSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private def makeSvc =
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
      clock  <- ZIO.service[Clock]
    } yield PolicyServiceLive(pr, hsr, tlr, atlr, dr, blr, trRepo, er, ar, clock)

  private def setBlockEncryptedDns(v: Boolean) =
    for {
      hsr      <- ZIO.service[HouseholdSettingsRepo]
      existing <- hsr.get
      _        <- hsr.update(existing.copy(blockEncryptedDns = v))
    } yield ()

  def spec = suite("PolicySnapshot — blockEncryptedDns (#1912)")(
    test("default household setting → snapshot.blockEncryptedDns is false") {
      for {
        _    <- cleanDb
        svc  <- makeSvc
        snap <- svc.snapshot
      } yield assertTrue(!snap.blockEncryptedDns)
    },
    test("household setting true → snapshot.blockEncryptedDns is true") {
      for {
        _    <- cleanDb
        _    <- setBlockEncryptedDns(true)
        svc  <- makeSvc
        snap <- svc.snapshot
      } yield assertTrue(snap.blockEncryptedDns)
    },
    test("blockEncryptedDns serializes on the wire (the lua agent reads it as a plain bool)") {
      for {
        _    <- cleanDb
        _    <- setBlockEncryptedDns(true)
        svc  <- makeSvc
        snap <- svc.snapshot
        json = snap.toJson
      } yield assertTrue(json.contains("\"blockEncryptedDns\":true"))
    },
    test("ETag flips when the household toggle changes") {
      for {
        _     <- cleanDb
        svc   <- makeSvc
        snap1 <- svc.snapshot
        _     <- setBlockEncryptedDns(true)
        snap2 <- svc.snapshot
      } yield assertTrue(snap1.etag != snap2.etag) && assertTrue(!snap1.blockEncryptedDns) &&
        assertTrue(snap2.blockEncryptedDns)
    },
  ) @@ TestAspect.sequential
}
