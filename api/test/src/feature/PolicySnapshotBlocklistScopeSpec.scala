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

/**
 * #1784 — `PolicyService.snapshot.blocklists` must include only the blocklists actually referenced
 * by some profile's effective `blocklistIds` (or by the global section). The router agent fetches
 * and renders every list it sees in the snapshot, so shipping unreferenced lists is wasted work
 * (and on prod the difference is hundreds of thousands of directives vs hundreds — see #1412).
 */
object PolicySnapshotBlocklistScopeSpec
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
      clock  <- ZIO.service[Clock]
    } yield PolicyServiceLive(pr, hsr, tlr, atlr, dr, blr, trRepo, er, ar, clock): PolicyService

  // Seed three distinct bundled blocklists so the "drops the unreferenced ones" assertion has bite.
  private def seedThreeLists(blr: BlocklistRepo): Task[Unit] =
    blr
      .insertBatch(
        List(
          ("doubleclick.net", "ads"),
          ("badthings.example", "malware"),
          ("xxx.example", "adult"),
        ),
      )
      .unit

  def spec = suite("PolicySnapshot — blocklists scoped to referenced lists (#1784)")(
    test("only the blocklists referenced by some profile ship in snap.blocklists") {
      for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        blr  <- ZIO.service[BlocklistRepo]
        _    <- seedThreeLists(blr)
        // ProfileRepo.create(name, blockedCategories): one profile references "malware" only.
        _    <- pr.create("Kids", List(BlocklistId.unsafe("malware")))
        svc  <- makePs
        snap <- svc.snapshot
        keys = snap.blocklists.keySet.map(_.value)
      } yield assertTrue(keys == Set("malware"))
    },
    test("union across profiles: every referenced id appears, unreferenced ones do not") {
      for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        blr  <- ZIO.service[BlocklistRepo]
        _    <- blr.insertBatch(
          List(
            ("doubleclick.net", "ads"),
            ("badthings.example", "malware"),
            ("xxx.example", "adult"),
            ("p2p.example", "p2p"),
          ),
        )
        _    <- pr.create("Kids", List(BlocklistId.unsafe("malware")))
        _    <- pr.create("Teens", List(BlocklistId.unsafe("ads"), BlocklistId.unsafe("adult")))
        svc  <- makePs
        snap <- svc.snapshot
        keys = snap.blocklists.keySet.map(_.value)
      } yield assertTrue(keys == Set("malware", "ads", "adult"))
    },
    test("with no profile referencing any blocklist, snap.blocklists is empty") {
      for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        blr  <- ZIO.service[BlocklistRepo]
        _    <- seedThreeLists(blr)
        _    <- pr.create("Adults", List.empty)
        svc  <- makePs
        snap <- svc.snapshot
      } yield assertTrue(snap.blocklists.isEmpty)
    },
    test("a list referenced by the global sentinel profile still ships") {
      for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        blr  <- ZIO.service[BlocklistRepo]
        _    <- seedThreeLists(blr)
        _    <- pr.create("Adults", List.empty)
        g    <- pr.getGlobal.map(_.get.id)
        _    <- pr.setBlockedCategories(g, List(BlocklistId.unsafe("ads")))
        svc  <- makePs
        snap <- svc.snapshot
        keys = snap.blocklists.keySet.map(_.value)
      } yield assertTrue(keys == Set("ads"))
    },
  ) @@ TestAspect.sequential
}
