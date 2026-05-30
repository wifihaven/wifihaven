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
 * #1174: a profile that's blocked by an active Schedule must still emit the deployment's
 * `uiAllowedHosts` in its snapshot `extraAllowed`. Without this carve-out, the kid can't reach the
 * SPA block-page / request-extension flow during bedtime — they see a connection error.
 *
 * `PolicySnapshotGlobalAllowSpec` already pins the paused-profile case; this spec pins the
 * schedule-blocked case independently, because Schedule blocking flows through a separate code path
 * in `TimeStatusService` (clock + window evaluation) than Pause (a static profile column).
 */
object PolicyScheduleGlobalAllowSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  // Bedtime: Monday 21:30 — inside the seedKidsProfile schedule (21:00–07:00 UTC).
  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.bedtime)

  private def cleanDb = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
    TestDatabase.cleanAndMigrate.provide(ZLayer.succeed(pg)),
  )

  private val uiHosts: List[Hostname] =
    List("wifihaven.net", "www.wifihaven.net", "api.wifihaven.net").map(Hostname.unsafe)

  def spec = suite("policy snapshot — schedule-blocked profile still emits uiAllowedHosts (#1174)")(
    test("schedule-active profile carries uiAllowedHosts in extraAllowed and blocked=Schedule") {
      for {
        _      <- cleanDb
        pr     <- ZIO.service[ProfileRepo]
        sr     <- ZIO.service[ScheduleRepo]
        hsr    <- ZIO.service[HouseholdSettingsRepo]
        tlr    <- ZIO.service[TimeLimitRepo]
        stlr   <- ZIO.service[SiteTimeLimitRepo]
        dr     <- ZIO.service[DeviceRepo]
        blr    <- ZIO.service[BlocklistRepo]
        trr    <- ZIO.service[TrafficReportRepo]
        er     <- ZIO.service[TimeExtensionRepo]
        ar     <- ZIO.service[AppRepo]
        clk    <- ZIO.service[Clock]
        kidsId <- TestLayers.seedKidsProfile(pr, sr)
        svc = PolicyServiceLive(pr, sr, hsr, tlr, stlr, dr, blr, trr, er, ar, clk, uiHosts)
        snap <- svc.snapshot
      } yield {
        val rules   = snap.profiles(kidsId).rules
        val allowed = rules.extraAllowed.map(_.value).toSet
        assertTrue(rules.blocked) &&
        assertTrue(rules.blockReason.contains(MacBlockReason.Schedule)) &&
        assertTrue(uiHosts.map(_.value).toSet.subsetOf(allowed))
      }
    },
  ) @@ TestAspect.sequential
}
