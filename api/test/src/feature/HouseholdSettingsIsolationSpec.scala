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
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.interop.catz.*
import zio.{Clock as _, *}
import zio.test.*

/**
 * #2386 (multi-tenant isolation, epic #2085/#622) — `household_settings.getForHousehold` must
 * return a household's OWN settings, never household #1's. Before this fix, no provisioning path
 * seeded a per-household settings row and `getForHousehold` fell back to the id=1 row, so a beta
 * household silently inherited household #1's `block_encrypted_dns` / `unmanaged_mac_policy` /
 * reset tz / `notify_email` — a live cross-tenant leak. Full stack, embedded Postgres, NO repo
 * mocks.
 *
 * Pins:
 *   - a household provisioned via [[HouseholdRepoLive.create]] owns its own settings row and reads
 *     ITS OWN defaults, not household #1's mutated values (positive sees-own + negative
 *     no-cross-tenant);
 *   - `getForHousehold` FAILS LOUD for a household with no settings row (a provisioning bug),
 *     rather than silently falling back to household #1;
 *   - [[HouseholdSeed.backfillMissingSettings]] gives a pre-existing rowless household its own row.
 */
object HouseholdSettingsIsolationSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  // A bare households row with NO settings row — models a household minted outside the creation
  // primitive (the shape the fix must reject / backfill).
  private def rawHousehold(xa: Transactor[Task], slug: String): Task[HouseholdId] =
    sql"INSERT INTO households(name, slug, router_cap) VALUES($slug, $slug, 1) RETURNING id"
      .query[HouseholdId]
      .unique
      .transact(xa)

  def spec = suite("HouseholdSettingsIsolationSpec")(
    test("a provisioned household reads its OWN settings, not household #1's") {
      for {
        _    <- cleanDb
        hr   <- ZIO.service[HouseholdRepo]
        hs   <- ZIO.service[HouseholdSettingsRepo]
        // Give household #1 a distinctive, non-default value.
        base <- hs.get
        _    <- hs.update(base.copy(blockEncryptedDns = true))
        // Provision a fresh household through the ONE creation primitive.
        hh   <- hr.create("Beta Fam", "beta-fam", 1)
        mine <- hs.getForHousehold(hh)
        one  <- hs.getForHousehold(HouseholdId.Default)
      } yield assertTrue(
        // household #1 sees its own mutated value (sees-own)
        one.blockEncryptedDns,
        // the new household reads its OWN default, NOT household #1's true (no cross-tenant inherit)
        !mine.blockEncryptedDns,
      )
    },
    test("getForHousehold fails loud for a household with no settings row") {
      for {
        _   <- cleanDb
        hs  <- ZIO.service[HouseholdSettingsRepo]
        xa  <- ZIO.service[Transactor[Task]]
        hid <- rawHousehold(xa, "no-settings")
        res <- hs.getForHousehold(hid).either
      } yield assertTrue(res.isLeft)
    },
    test("backfillMissingSettings gives a rowless household its own row (idempotent)") {
      for {
        _      <- cleanDb
        hs     <- ZIO.service[HouseholdSettingsRepo]
        xa     <- ZIO.service[Transactor[Task]]
        hid    <- rawHousehold(xa, "legacy-fam")
        before <- hs.getForHousehold(hid).either
        n1     <- HouseholdSeed.backfillMissingSettings.transact(xa)
        after  <- hs.getForHousehold(hid).either
        // Second run is a no-op (idempotent) — the NOT EXISTS guard skips the now-present row.
        n2     <- HouseholdSeed.backfillMissingSettings.transact(xa)
      } yield assertTrue(
        before.isLeft,
        n1 == 1,
        after.isRight,
        // the backfilled row carries defaults, not household #1's config
        after.exists(!_.blockEncryptedDns),
        n2 == 0,
      )
    },
  ) @@ TestAspect.sequential
}
