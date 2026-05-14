package familydns.api.feature

import familydns.api.db.*
import familydns.api.policy.*
import familydns.shared.*
import familydns.shared.Clock.TestClock
import familydns.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.json.*
import zio.test.*

/**
 * #311: PolicySnapshot must carry the per-profile failureMode so the agent can pick the right
 * failover behaviour after 5 minutes of API unreachability. Closed → drop all forwarded traffic for
 * the profile's devices; Open → keep enforcing the cached snapshot exactly as-is. Without this
 * field on the wire, "fail closed for children, fail open for adults" can't be honoured.
 */
object PolicySnapshotFailureModeSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private def cleanDb = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
    TestDatabase.cleanAndMigrate.provide(ZLayer.succeed(pg)),
  )

  def spec = suite("PolicySnapshot — failureMode (#311)")(
    test("snapshot carries each profile's failureMode value") {
      for {
        _      <- cleanDb
        pr     <- ZIO.service[ProfileRepo]
        sr     <- ZIO.service[ScheduleRepo]
        tlr    <- ZIO.service[TimeLimitRepo]
        stlr   <- ZIO.service[SiteTimeLimitRepo]
        dr     <- ZIO.service[DeviceRepo]
        blr    <- ZIO.service[BlocklistRepo]
        trRepo <- ZIO.service[TrafficReportRepo]
        er     <- ZIO.service[TimeExtensionRepo]
        clock  <- ZIO.service[Clock]
        svc = PolicyServiceLive(pr, sr, tlr, stlr, dr, blr, trRepo, er, clock)
        profiles0 <- pr.listAll
        kidsId   = profiles0.find(_.name == "Kids").get.id
        adultsId = profiles0.find(_.name == "Adults").get.id
        // Force the two seeded profiles into known modes.
        _ <- pr.update(profiles0.find(_.name == "Kids").get.copy(failureMode = FailureMode.Closed))
        _ <- pr.update(profiles0.find(_.name == "Adults").get.copy(failureMode = FailureMode.Open))
        snap <- svc.snapshot
        kids   = snap.profiles(kidsId)
        adults = snap.profiles(adultsId)
      } yield assertTrue(kids.failureMode == FailureMode.Closed) &&
        assertTrue(adults.failureMode == FailureMode.Open)
    },
    test("failureMode serializes as lowercase \"open\" / \"closed\" on the wire") {
      // The lua agent reads snapshot.failureMode as a plain string; pin the
      // exact wire spelling so render.lua's comparisons don't drift.
      for {
        _      <- cleanDb
        pr     <- ZIO.service[ProfileRepo]
        sr     <- ZIO.service[ScheduleRepo]
        tlr    <- ZIO.service[TimeLimitRepo]
        stlr   <- ZIO.service[SiteTimeLimitRepo]
        dr     <- ZIO.service[DeviceRepo]
        blr    <- ZIO.service[BlocklistRepo]
        trRepo <- ZIO.service[TrafficReportRepo]
        er     <- ZIO.service[TimeExtensionRepo]
        clock  <- ZIO.service[Clock]
        svc = PolicyServiceLive(pr, sr, tlr, stlr, dr, blr, trRepo, er, clock)
        profiles0 <- pr.listAll
        _ <- pr.update(profiles0.find(_.name == "Kids").get.copy(failureMode = FailureMode.Closed))
        _ <- pr.update(profiles0.find(_.name == "Adults").get.copy(failureMode = FailureMode.Open))
        snap <- svc.snapshot
        json = snap.toJson
      } yield assertTrue(json.contains("\"failureMode\":\"closed\"")) &&
        assertTrue(json.contains("\"failureMode\":\"open\""))
    },
    test("ETag flips when a profile's failureMode changes") {
      for {
        _      <- cleanDb
        pr     <- ZIO.service[ProfileRepo]
        sr     <- ZIO.service[ScheduleRepo]
        tlr    <- ZIO.service[TimeLimitRepo]
        stlr   <- ZIO.service[SiteTimeLimitRepo]
        dr     <- ZIO.service[DeviceRepo]
        blr    <- ZIO.service[BlocklistRepo]
        trRepo <- ZIO.service[TrafficReportRepo]
        er     <- ZIO.service[TimeExtensionRepo]
        clock  <- ZIO.service[Clock]
        svc = PolicyServiceLive(pr, sr, tlr, stlr, dr, blr, trRepo, er, clock)
        profiles0 <- pr.listAll
        _ <- pr.update(profiles0.find(_.name == "Kids").get.copy(failureMode = FailureMode.Closed))
        snap1 <- svc.snapshot
        _ <- pr.update(profiles0.find(_.name == "Kids").get.copy(failureMode = FailureMode.Open))
        snap2 <- svc.snapshot
      } yield assertTrue(snap1.etag != snap2.etag)
    },
  ) @@ TestAspect.sequential
}
