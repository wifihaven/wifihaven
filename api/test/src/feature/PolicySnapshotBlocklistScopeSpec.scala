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

  // V1 seeds default Kids and Adults profiles with hard-coded blockedCategories. Clear them so each
  // test starts from a known empty reference set and only the lists this test references show up.
  private def clearDefaultProfileCategories: ZIO[ProfileRepo, Throwable, Unit] =
    for {
      pr  <- ZIO.service[ProfileRepo]
      all <- pr.listAllIncludingGlobal
      _   <- ZIO.foreachDiscard(all)(p => pr.setBlockedCategories(p.id, List.empty))
    } yield ()

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

  private def kidsId: ZIO[ProfileRepo, Throwable, ProfileId] =
    ZIO.serviceWithZIO[ProfileRepo](_.listAll.map(_.find(_.name == "Kids").get.id))

  private def adultsId: ZIO[ProfileRepo, Throwable, ProfileId] =
    ZIO.serviceWithZIO[ProfileRepo](_.listAll.map(_.find(_.name == "Adults").get.id))

  def spec = suite("PolicySnapshot — blocklists scoped to referenced lists (#1784)")(
    test("only the blocklists referenced by some profile ship in snap.blocklists") {
      for {
        _    <- cleanDb
        _    <- clearDefaultProfileCategories
        pr   <- ZIO.service[ProfileRepo]
        blr  <- ZIO.service[BlocklistRepo]
        _    <- seedThreeLists(blr)
        kid  <- kidsId
        _    <- pr.setBlockedCategories(kid, List(BlocklistId.unsafe("malware")))
        svc  <- makePs
        snap <- svc.snapshot
        keys = snap.blocklists.keySet.map(_.value)
      } yield assertTrue(keys == Set("malware"))
    },
    test("union across profiles: every referenced id appears, unreferenced ones do not") {
      for {
        _      <- cleanDb
        _      <- clearDefaultProfileCategories
        pr     <- ZIO.service[ProfileRepo]
        blr    <- ZIO.service[BlocklistRepo]
        _      <- blr.insertBatch(
          List(
            ("doubleclick.net", "ads"),
            ("badthings.example", "malware"),
            ("xxx.example", "adult"),
            ("p2p.example", "p2p"),
          ),
        )
        kid    <- kidsId
        adults <- adultsId
        _      <- pr.setBlockedCategories(kid, List(BlocklistId.unsafe("malware")))
        _      <- pr.setBlockedCategories(
          adults,
          List(BlocklistId.unsafe("ads"), BlocklistId.unsafe("adult")),
        )
        svc    <- makePs
        snap   <- svc.snapshot
        keys = snap.blocklists.keySet.map(_.value)
      } yield assertTrue(keys == Set("malware", "ads", "adult"))
    },
    test("with no profile referencing any blocklist, snap.blocklists is empty") {
      for {
        _    <- cleanDb
        _    <- clearDefaultProfileCategories
        blr  <- ZIO.service[BlocklistRepo]
        _    <- seedThreeLists(blr)
        svc  <- makePs
        snap <- svc.snapshot
      } yield assertTrue(snap.blocklists.isEmpty)
    },
    test("a list referenced only by the global sentinel profile still ships") {
      for {
        _    <- cleanDb
        _    <- clearDefaultProfileCategories
        pr   <- ZIO.service[ProfileRepo]
        blr  <- ZIO.service[BlocklistRepo]
        _    <- seedThreeLists(blr)
        g    <- pr.getGlobal.map(_.get.id)
        _    <- pr.setBlockedCategories(g, List(BlocklistId.unsafe("ads")))
        svc  <- makePs
        snap <- svc.snapshot
        keys = snap.blocklists.keySet.map(_.value)
      } yield assertTrue(keys == Set("ads"))
    },
  ) @@ TestAspect.sequential
}
