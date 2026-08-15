package wifihaven.api.feature

import wifihaven.api.db.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import doobie.*
import doobie.implicits.*
import zio.*
import zio.interop.catz.*
import zio.test.*

/**
 * #2572 gap 3 — the tenancy semantics of `block_events.router_id`, pinned at the level they
 * actually exist at.
 *
 * V87 added the column NULLABLE with no backfill and no DEFAULT, and that is the final shape: there
 * is no deferred backfill and no follow-up `SET NOT NULL`. Historical rows predate multi-tenancy in
 * practice, and `mac` stopped identifying a household when V74 dropped `devices_mac_key`, so there
 * is no correct value to backfill them with — writing one would be inventing attribution rather
 * than recovering it. Adding the column without a rewrite is also what keeps the migration
 * metadata-only on an unbounded-growth table
 * (docs/process/migrations.md#migrations-prod-data-volume).
 *
 * That leaves a decision someone has to be able to find later: what a NULL row means to a
 * household-scoped read. The answer pinned here is that an unattributable row belongs to NOBODY —
 * it is excluded from every household's view, not defaulted into one. `BlockEventRepo` has no
 * household-scoped read to assert that through (#2571 deleted the bare-MAC `listForMac`; the
 * surviving `recent` is a whole-table read with no household parameter, and per that issue's rule a
 * scoped read comes back only when a real caller needs one), so what is pinned is the data-model
 * invariant that any such reader must inherit: attribution runs through `routers.household_id`, and
 * an inner join on `router_id` drops NULL rows by construction.
 *
 * This is a guard against a specific regression, not a restatement of the schema: it fails if
 * someone later backfills the NULLs to household 1, or re-adds a `DEFAULT`. That is precisely the
 * dark-by-default shape gap 2 of this same issue existed to remove.
 *
 * TODO(#2703): retire the hand-written JOIN below when a scoped read lands. The attribution
 * predicate belongs in `BlockEventRepoLive`, and a copy of it here plus a copy there is the
 * two-sources drift shape AGENTS.md#single-source-of-truth exists to prevent. The first
 * household-scoped read must be pinned THROUGH the repo, and this spec's first test rewritten to
 * call it. It lives here only because there is no repo method to point at yet.
 *
 * Note on the `column_default` test: it pins V87 (already merged in #2582), so it passes with every
 * source change in this PR reverted. It rides here because #2582 was a schema-only PR that could
 * carry no test, and it is a real guard against a LATER migration re-adding a default — but it is
 * not coverage of the stamping change. The first test is.
 *
 * Full stack on embedded Postgres, no repo mocks.
 */
object BlockEventTenancyKeySpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Transactor[Task]] {

  override val bootstrap = TestDatabase.layer

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val macA = MacAddress.unsafe("aa:bb:cc:00:00:1a")
  private val macB = MacAddress.unsafe("aa:bb:cc:00:00:1b")

  /** The attribution predicate any future household-scoped reader must use. */
  private def hostsForHousehold(xa: Transactor[Task], hh: HouseholdId): Task[List[String]] =
    sql"""SELECT be.host_value
          FROM block_events be
          JOIN routers r ON r.id = be.router_id
          WHERE r.household_id = $hh
          ORDER BY be.host_value"""
      .query[String]
      .to[List]
      .transact(xa)

  private def insert(repo: BlockEventRepo, rid: RouterId, mac: MacAddress, host: String) =
    repo.insertBatch(
      List(
        BlockEventInsert(
          rid,
          Some(mac),
          HostId.Fqdn(Hostname.unsafe(host)),
          BlockReason.Unknown("r"),
        ),
      ),
    )

  def spec = suite("block_events.router_id is the tenancy key (#2572)")(
    test("an unattributable (NULL router_id) row is visible to NO household") {
      for {
        _    <- cleanDb
        two  <- TestLayers.seedTwoHouseholds(macA, macB)
        repo <- ZIO.service[BlockEventRepo]
        xa   <- ZIO.service[Transactor[Task]]
        _    <- insert(repo, two.routerIdA, macA, "a-blocked.example.com")
        _    <- insert(repo, two.routerIdB, macB, "b-blocked.example.com")
        // A pre-V87 row: written before the column existed, so it carries no router. Inserted raw
        // because no code path can produce one any more — `BlockEventInsert.routerId` is required.
        _    <-
          sql"""INSERT INTO block_events(router_id, mac, host_type, host_value, reason, reason_text)
                VALUES (NULL, $macA, 'fqdn', 'historical.example.com', '"unknown"'::JSONB, 'r')""".update.run
            .transact(xa)
        all  <- sql"SELECT COUNT(*) FROM block_events".query[Int].unique.transact(xa)
        inA  <- hostsForHousehold(xa, two.hhA)
        inB  <- hostsForHousehold(xa, two.hhB)
      } yield
      // All three rows are present — the historical row is retained, not deleted.
      assertTrue(all == 3) &&
        // Each household sees exactly its own stamped row, and never the other's.
        assertTrue(inA == List("a-blocked.example.com")) &&
        assertTrue(inB == List("b-blocked.example.com")) &&
        // The unattributable row surfaces for NEITHER. It is not silently attributed to household 1
        // (which is `hhA` here, so a DEFAULT-1-style backfill would show up as a failure above).
        assertTrue(!(inA ++ inB).contains("historical.example.com"))
    },
    // The NULLs are by design and must stay reachable-by-nobody rather than acquiring a default
    // later. A `DEFAULT` on this column would silently attribute every unstamped insert to one
    // household — #2572 gap 2 removed exactly that shape from `named_schedules`.
    test("the column carries no DEFAULT, so an unstamped insert cannot land in a household") {
      for {
        _    <- cleanDb
        xa   <- ZIO.service[Transactor[Task]]
        dflt <-
          sql"""SELECT column_default FROM information_schema.columns
                WHERE table_name = 'block_events' AND column_name = 'router_id'"""
            .query[Option[String]]
            .unique
            .transact(xa)
      } yield assertTrue(dflt.isEmpty)
    },
  ) @@ TestAspect.sequential
}
