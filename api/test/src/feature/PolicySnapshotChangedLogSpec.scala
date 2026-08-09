package wifihaven.api.feature

import wifihaven.api.db.*
import wifihaven.api.observability.LogContext
import wifihaven.api.policy.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import doobie.Transactor
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.test.*

/**
 * #2653 (regression from #2633): `logSnapshotChanged` deduped against a SINGLE GLOBAL last-etag
 * slot. #2633 made `reevaluate` rebuild PER HOUSEHOLD and correctly keyed `lastPublishedEtag` by
 * household for exactly this reason — `lastSnapshotEtag` was left behind. With two households
 * alternating through one slot each evicts the other, so every rebuild looks like a change and the
 * #1641 guarantee ("one line per ETag transition, not per poll") is false: prod measured 120
 * `snapshot_changed` lines in 20 minutes across only TWO distinct etags, each carrying the full
 * snapshot JSON — every household's device names and MAC addresses, forever.
 *
 * The pin is deliberately TWO households alternating. A single-household spec passes against the
 * buggy global slot and cannot catch this.
 *
 * Lines are attributed by their `etag` annotation rather than by household: two households with
 * distinct policy have distinct ETags, and the ETag is what the dedupe is actually keyed on.
 */
object PolicySnapshotChangedLogSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val macA = MacAddress.unsafe("aa:bb:cc:00:00:0a")
  private val macB = MacAddress.unsafe("aa:bb:cc:00:00:0b")

  // Cache disabled (the constructor default) so every `snapshot` call is a real rebuild — this
  // spec pins the LOG dedupe, not the cache.
  private def makePolicyService =
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

  private def changedEtags(logs: Chunk[ZTestLogger.LogEntry]): Chunk[String] =
    logs
      .filter(_.annotations.get(LogContext.Op).contains("snapshot_changed"))
      .flatMap(_.annotations.get(LogContext.Etag))

  def spec = suite("#2653 snapshot_changed log dedupe is per household")(
    test("two households rebuilt alternately with UNCHANGED policy log one line EACH") {
      (for {
        _     <- cleanDb
        two   <- TestLayers.seedTwoHouseholds(macA, macB)
        ps    <- makePolicyService
        // Alternate A, B, A, B, A, B. Policy never changes, so each household's ETag is stable and
        // #1641's contract is exactly one line per household. Against the global slot this emits
        // six — each household's ETag evicting the other's on every pass.
        etagA <- ps.snapshot(two.hhA).map(_.etag.value)
        etagB <- ps.snapshot(two.hhB).map(_.etag.value)
        _     <- ZIO.foreachDiscard(1 to 2)(_ => ps.snapshot(two.hhA) *> ps.snapshot(two.hhB))
        logs  <- ZTestLogger.logOutput
      } yield {
        val etags = changedEtags(logs)
        assertTrue(etagA != etagB) &&
        assertTrue(etags.count(_ == etagA) == 1) &&
        assertTrue(etags.count(_ == etagB) == 1) &&
        assertTrue(etags.size == 2)
      })
        .provideSome[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]](
          ZTestLogger.default,
        )
    },
    test(
      "a household whose policy actually changes logs again — dedupe suppresses repeats, not changes",
    ) {
      (for {
        _      <- cleanDb
        two    <- TestLayers.seedTwoHouseholds(macA, macB)
        pr     <- ZIO.service[ProfileRepo]
        ps     <- makePolicyService
        etagA  <- ps.snapshot(two.hhA).map(_.etag.value)
        etagB  <- ps.snapshot(two.hhB).map(_.etag.value)
        // Mutate household A only: its ETag moves, B's does not.
        _      <- pr.setPaused(two.profileA, true)
        etagA2 <- ps.snapshot(two.hhA).map(_.etag.value)
        etagB2 <- ps.snapshot(two.hhB).map(_.etag.value)
        logs   <- ZTestLogger.logOutput
      } yield {
        val etags = changedEtags(logs)
        assertTrue(etagA2 != etagA) &&
        assertTrue(etagB2 == etagB) &&
        assertTrue(etags.count(_ == etagA) == 1) &&
        assertTrue(etags.count(_ == etagA2) == 1) &&
        assertTrue(etags.count(_ == etagB) == 1) &&
        assertTrue(etags.size == 3)
      })
        .provideSome[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]](
          ZTestLogger.default,
        )
    },
  ) @@ TestAspect.sequential
}
