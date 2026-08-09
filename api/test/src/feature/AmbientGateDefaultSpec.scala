package wifihaven.api.feature

import wifihaven.api.db.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import doobie.*
import doobie.implicits.*
import java.time.ZoneId
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.interop.catz.*
import zio.{Clock as _, *}
import zio.test.*

/**
 * #2643 (operator scope extension) — the ambient/idle-traffic gate must be ON for a NEWLY created
 * household, by the same mechanism and in the same change as `blockEncryptedDns`. See
 * [[BlockEncryptedDnsDefaultSpec]] for the shared trace of where a new household's value actually
 * comes from, and `AmbientEmptyBaselineSpec` for the determination this flip was conditional on —
 * that the gate over an EMPTY learned baseline is a clean no-op, so a new household with no learned
 * ambient hosts is never mis-accounted while learning matures.
 *
 * Full stack, embedded Postgres, NO repo mocks.
 */
object AmbientGateDefaultSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private def rawHousehold(xa: Transactor[Task], slug: String): Task[HouseholdId] =
    sql"INSERT INTO households(name, slug, router_cap) VALUES($slug, $slug, 1) RETURNING id"
      .query[HouseholdId]
      .unique
      .transact(xa)

  def spec = suite("AmbientGateDefaultSpec (#2643 scope extension)")(
    test("a newly created household starts with ambientGateEnabled ON") {
      for {
        _  <- cleanDb
        hr <- ZIO.service[HouseholdRepo]
        hs <- ZIO.service[HouseholdSettingsRepo]
        hh <- hr.create("Ambient Fam", "ambient-fam-2643", 1)
        s  <- hs.getForHousehold(hh)
      } yield assertTrue(s.ambientGateEnabled)
    },
    test("a fresh install's singleton household starts with ambientGateEnabled ON") {
      for {
        _  <- cleanDb
        hs <- ZIO.service[HouseholdSettingsRepo]
        xa <- ZIO.service[Transactor[Task]]
        _  <- sql"DELETE FROM household_settings".update.run.transact(xa)
        _  <- hs.ensureDefault(ZoneId.of("UTC"))
        s  <- hs.getForHousehold(HouseholdId.Default)
      } yield assertTrue(s.ambientGateEnabled)
    },
    test("the boot backfill does NOT turn it on for a pre-existing household") {
      // Same scope decision as blockEncryptedDns: NEW households only. An existing household's
      // screen-time numbers changing under it — even in the improving direction — is a visible
      // change to figures the operator has been reading, so it stays their call.
      for {
        _   <- cleanDb
        hs  <- ZIO.service[HouseholdSettingsRepo]
        xa  <- ZIO.service[Transactor[Task]]
        hid <- rawHousehold(xa, "legacy-ambient-2643")
        n   <- HouseholdSeed.backfillMissingSettings.transact(xa)
        s   <- hs.getForHousehold(hid)
      } yield assertTrue(n == 1, !s.ambientGateEnabled)
    },
    test("ensureDefault never flips an EXISTING install's stored value") {
      for {
        _     <- cleanDb
        hs    <- ZIO.service[HouseholdSettingsRepo]
        base  <- hs.getForHousehold(HouseholdId.Default)
        _     <- hs.update(HouseholdId.Default, base.copy(ambientGateEnabled = false))
        _     <- hs.ensureDefault(ZoneId.of("UTC"))
        after <- hs.getForHousehold(HouseholdId.Default)
      } yield assertTrue(!after.ambientGateEnabled)
    },
    test("an explicit OFF on a new household survives — the default never overwrites it") {
      for {
        _     <- cleanDb
        hr    <- ZIO.service[HouseholdRepo]
        hs    <- ZIO.service[HouseholdSettingsRepo]
        hh    <- hr.create("Ambient Opt Out", "ambient-opt-out-2643", 1)
        base  <- hs.getForHousehold(hh)
        _     <- hs.update(hh, base.copy(ambientGateEnabled = false))
        after <- hs.getForHousehold(hh)
      } yield assertTrue(!after.ambientGateEnabled)
    },
    test("the V63 learning thresholds are untouched — only the master switch moved") {
      // #2643 flips the switch, NOT the thresholds behind it. If a later change wanted the gate to
      // wait for a mature baseline it would move these; pinning them makes that visible.
      for {
        _  <- cleanDb
        hr <- ZIO.service[HouseholdRepo]
        hs <- ZIO.service[HouseholdSettingsRepo]
        hh <- hr.create("Ambient Thresholds", "ambient-thresholds-2643", 1)
        s  <- hs.getForHousehold(hh)
      } yield assertTrue(
        s.ambientIsolationMaxHosts == HouseholdSettings.DefaultAmbientIsolationMaxHosts,
        s.ambientMinIsolatedDays == HouseholdSettings.DefaultAmbientMinIsolatedDays,
        s.ambientLearningWindowDays == HouseholdSettings.DefaultAmbientLearningWindowDays,
      )
    },
  ) @@ TestAspect.sequential
}
