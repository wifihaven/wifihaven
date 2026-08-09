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
 * #2643 — "Block encrypted DNS & relays" must be ON for a NEWLY created household.
 *
 * Why: a device that tunnels around the LAN resolver (iCloud Private Relay, public DoH/DoT)
 * bypasses ALL WifiHaven filtering and ALL hostname attribution, and the failure is silent in the
 * worst direction — the dashboard renders, it just shows raw IPs, and configured blocks do not
 * bite. Off-by-default meant every household started inert until an operator happened to find the
 * toggle. Hit live on a fresh prod household during #2527.
 *
 * Where the default lives (the #2643 first-job trace): `HouseholdSeed.insertHousehold` used to
 * INSERT into `household_settings` naming ONLY `household_id`, so the value a new household got
 * came from V61's `block_encrypted_dns BOOLEAN NOT NULL DEFAULT FALSE` — the SQL column default was
 * authoritative and both Scala defaults in `Models.scala` were inert for creation (they are
 * JSON-decoding defaults). The fix makes the creation paths name the column explicitly from the ONE
 * constant [[HouseholdSettings.DefaultBlockEncryptedDns]], which is what lets the NEW-household
 * default move without dragging the backfill (pre-existing households) along with it.
 *
 * Pins, full stack on embedded Postgres, NO repo mocks:
 *   - a household minted through the creation primitive starts ON;
 *   - a fresh install's singleton household (`ensureDefault`) starts ON;
 *   - a PRE-EXISTING household is never flipped — neither the boot backfill nor anything else turns
 *     it on behind the operator's back (#2643 scope decision 1: flipping a live network's DNS
 *     behaviour is the operator's call, per household);
 *   - an explicit OFF survives — the new default never overwrites a stored value.
 */
object BlockEncryptedDnsDefaultSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  // A bare households row with NO settings row — models a household minted before the settings row
  // was seeded on every create path (#2386), i.e. the shape the boot backfill exists to repair.
  private def rawHousehold(xa: Transactor[Task], slug: String): Task[HouseholdId] =
    sql"INSERT INTO households(name, slug, router_cap) VALUES($slug, $slug, 1) RETURNING id"
      .query[HouseholdId]
      .unique
      .transact(xa)

  def spec = suite("BlockEncryptedDnsDefaultSpec (#2643)")(
    test("a newly created household starts with blockEncryptedDns ON") {
      for {
        _  <- cleanDb
        hr <- ZIO.service[HouseholdRepo]
        hs <- ZIO.service[HouseholdSettingsRepo]
        hh <- hr.create("New Fam", "new-fam-2643", 1)
        s  <- hs.getForHousehold(hh)
      } yield assertTrue(s.blockEncryptedDns)
    },
    test("a fresh install's singleton household starts with blockEncryptedDns ON") {
      // `ensureDefault` is the boot seed for household #1 — THE household on a self-hosted install.
      // The template DB already carries a seeded row, so drop it first to model a fresh install;
      // `ON CONFLICT (id) DO NOTHING` is what keeps an EXISTING install untouched on upgrade, and
      // that is pinned separately below.
      for {
        _  <- cleanDb
        hs <- ZIO.service[HouseholdSettingsRepo]
        xa <- ZIO.service[Transactor[Task]]
        _  <- sql"DELETE FROM household_settings".update.run.transact(xa)
        _  <- hs.ensureDefault(ZoneId.of("UTC"))
        s  <- hs.getForHousehold(HouseholdId.Default)
      } yield assertTrue(s.blockEncryptedDns)
    },
    test("ensureDefault never flips an EXISTING install's stored value") {
      for {
        _     <- cleanDb
        hs    <- ZIO.service[HouseholdSettingsRepo]
        base  <- hs.getForHousehold(HouseholdId.Default)
        _     <- hs.update(HouseholdId.Default, base.copy(blockEncryptedDns = false))
        // A restart re-runs the boot seed; ON CONFLICT DO NOTHING must leave the row alone.
        _     <- hs.ensureDefault(ZoneId.of("UTC"))
        after <- hs.getForHousehold(HouseholdId.Default)
      } yield assertTrue(!after.blockEncryptedDns)
    },
    test("the boot backfill does NOT turn it on for a pre-existing household") {
      // #2643 scope decision 1: NEW households only. A household that predates the per-household
      // settings row (#2386) is an EXISTING network — turning relay/DoH blocking on for it can
      // break devices that depend on DoH, so the backfilled row keeps the old OFF value and the
      // operator decides.
      for {
        _   <- cleanDb
        hs  <- ZIO.service[HouseholdSettingsRepo]
        xa  <- ZIO.service[Transactor[Task]]
        hid <- rawHousehold(xa, "legacy-fam-2643")
        n   <- HouseholdSeed.backfillMissingSettings.transact(xa)
        s   <- hs.getForHousehold(hid)
      } yield assertTrue(n == 1, !s.blockEncryptedDns)
    },
    test("an explicit OFF on a new household survives — the default never overwrites it") {
      for {
        _     <- cleanDb
        hr    <- ZIO.service[HouseholdRepo]
        hs    <- ZIO.service[HouseholdSettingsRepo]
        hh    <- hr.create("Opt Out", "opt-out-2643", 1)
        base  <- hs.getForHousehold(hh)
        _     <- hs.update(hh, base.copy(blockEncryptedDns = false))
        after <- hs.getForHousehold(hh)
      } yield assertTrue(!after.blockEncryptedDns)
    },
  ) @@ TestAspect.sequential
}
