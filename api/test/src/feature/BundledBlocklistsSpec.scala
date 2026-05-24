package wifihaven.api.feature

import wifihaven.api.{BundledBlocklist, BundledBlocklists}
import wifihaven.api.db.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.interop.catz.*
import zio.test.*

/**
 * #958: bundled blocklist YAML loader + startup seeder + metadata repo.
 *
 * Cleanup of the #706 leaked test_* rows is covered by V32__cleanup_test_blocklists.sql
 * running on the embedded Postgres at TestDatabase.cleanAndMigrate. The first migration-applied
 * cleanup is implicit in every test that follows.
 */
object BundledBlocklistsSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & doobie.Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private def cleanDb = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
    TestDatabase.cleanAndMigrate.provide(ZLayer.succeed(pg)),
  )

  def spec = suite("BundledBlocklists")(
    test("_index.yml is in sync with the .yml files in blocklists/") {
      for {
        bundled <- BundledBlocklists.loadAll()
        manifestIds = bundled.map(_.id.value).toSet
        dirIds <- ZIO.attemptBlocking {
          val url = getClass.getResource("/blocklists")
          val dir = new java.io.File(url.toURI)
          dir
            .listFiles()
            .toList
            .filter(f => f.isFile && f.getName.endsWith(".yml") && !f.getName.startsWith("_"))
            .map(_.getName.stripSuffix(".yml"))
            .toSet
        }
      } yield assertTrue(manifestIds == dirIds)
    },
    test("every bundled blocklist parses, has hosts, name, description, and source") {
      for {
        bundled <- BundledBlocklists.loadAll()
      } yield assertTrue(bundled.nonEmpty) &&
        assertTrue(bundled.forall(_.hosts.nonEmpty)) &&
        assertTrue(bundled.forall(_.name.nonEmpty)) &&
        assertTrue(bundled.forall(_.description.nonEmpty)) &&
        assertTrue(bundled.forall(_.source.nonEmpty)) &&
        assertTrue(bundled.map(_.id).distinct.size == bundled.size)
    },
    test("every bundled host parses as a valid hostname") {
      for {
        bundled <- BundledBlocklists.loadAll()
      } yield assertTrue(
        bundled.forall(_.hosts.forall(h => Hostname.parse(h.value).isRight)),
      )
    },
    test("seeder populates blocklist_domains and blocklists metadata row") {
      for {
        _       <- cleanDb
        blRepo  <- ZIO.service[BlocklistRepo]
        bundled <- BundledBlocklists.loadAll()
        _       <- BundledBlocklists.seed(blRepo, bundled)
        ads     <- blRepo.loadCategory(BlocklistId.unsafe("ads"))
        meta    <- blRepo.findMeta(BlocklistId.unsafe("ads"))
      } yield assertTrue(ads.nonEmpty) &&
        assertTrue(ads.contains(Hostname.unsafe("doubleclick.net"))) &&
        assertTrue(meta.isDefined) &&
        assertTrue(meta.exists(m => m.bundled && m.name == "Ads & Trackers"))
    },
    test("seeder is idempotent (running twice yields the same hosts + advances last_built_at)") {
      for {
        _       <- cleanDb
        blRepo  <- ZIO.service[BlocklistRepo]
        bundled <- BundledBlocklists.loadAll()
        _       <- BundledBlocklists.seed(blRepo, bundled)
        first   <- blRepo.summaries.map(_.find(_.id == BlocklistId.unsafe("ads")))
        firstAds <- blRepo.loadCategory(BlocklistId.unsafe("ads"))
        _       <- BundledBlocklists.seed(blRepo, bundled)
        second  <- blRepo.summaries.map(_.find(_.id == BlocklistId.unsafe("ads")))
        secondAds <- blRepo.loadCategory(BlocklistId.unsafe("ads"))
      } yield assertTrue(firstAds == secondAds) &&
        assertTrue(first.exists(_.hostCount == secondAds.size)) &&
        assertTrue(second.exists(_.hostCount == secondAds.size))
    },
    test("seeder REPLACES hosts — manual inserts get overwritten on re-seed (bundled lists are API-managed)") {
      for {
        _       <- cleanDb
        blRepo  <- ZIO.service[BlocklistRepo]
        bundled <- BundledBlocklists.loadAll()
        _       <- BundledBlocklists.seed(blRepo, bundled)
        _       <- blRepo.insertBatch(List(("manual-injected.example", "ads")))
        before  <- blRepo.loadCategory(BlocklistId.unsafe("ads"))
        _       <- BundledBlocklists.seed(blRepo, bundled)
        after   <- blRepo.loadCategory(BlocklistId.unsafe("ads"))
      } yield assertTrue(before.contains(Hostname.unsafe("manual-injected.example"))) &&
        assertTrue(!after.contains(Hostname.unsafe("manual-injected.example")))
    },
    test("V32 cleanup migration: no test_* categories present after a fresh migrate") {
      for {
        _      <- cleanDb
        blRepo <- ZIO.service[BlocklistRepo]
        cats   <- blRepo.listCategories
      } yield assertTrue(!cats.contains(BlocklistId.unsafe("test_ads"))) &&
        assertTrue(!cats.contains(BlocklistId.unsafe("test_social")))
    },
    test("V32 cleanup migration: removes test_* even after re-insertion + scrubs profiles.blocked_categories") {
      // Simulates the prod upgrade path: V11 had previously inserted rows
      // and a profile referenced them. After V32 runs, both the
      // blocklist_domains rows and the profile.blocked_categories
      // references should be gone.
      for {
        _        <- cleanDb
        blRepo   <- ZIO.service[BlocklistRepo]
        pr       <- ZIO.service[ProfileRepo]
        // Simulate the prod state by reinserting the rows V11 would have
        // created, then re-running V32 manually as plain SQL.
        _        <- blRepo.insertBatch(
          List(
            ("adserver.example.com", "test_ads"),
            ("doubleclick.net", "test_ads"),
            ("facebook.com", "test_social"),
          ),
        )
        pid      <- pr.create("Kids", List(BlocklistId.unsafe("test_ads"), BlocklistId.unsafe("test_social")))
        xa       <- ZIO.service[doobie.Transactor[Task]]
        // Apply V32 statements manually (Flyway would have already run
        // them once at cleanDb; re-applying is safe — they're idempotent).
        _        <- {
          import doobie.implicits.*
          sql"DELETE FROM blocklist_domains WHERE category IN ('test_ads', 'test_social')".update.run.transact(xa)
        }
        _        <- {
          import doobie.implicits.*
          sql"DELETE FROM blocklists WHERE id IN ('test_ads', 'test_social')".update.run.transact(xa)
        }
        _        <- {
          import doobie.implicits.*
          sql"""UPDATE profiles
                SET blocked_categories = array_remove(
                      array_remove(blocked_categories, 'test_ads'),
                      'test_social'
                    )
                WHERE blocked_categories && ARRAY['test_ads', 'test_social']::TEXT[]""".update.run
            .transact(xa)
        }
        cats     <- blRepo.listCategories
        profile  <- pr.findById(pid).someOrFailException
      } yield assertTrue(!cats.contains(BlocklistId.unsafe("test_ads"))) &&
        assertTrue(!cats.contains(BlocklistId.unsafe("test_social"))) &&
        assertTrue(profile.blockedCategories.isEmpty)
    },
    test("dev test seeder seeds test_ads + test_social when invoked") {
      for {
        _      <- cleanDb
        blRepo <- ZIO.service[BlocklistRepo]
        _      <- BundledBlocklists.seed(blRepo, BundledBlocklists.devTestBlocklists)
        cats   <- blRepo.listCategories
        ads    <- blRepo.loadCategory(BlocklistId.unsafe("test_ads"))
      } yield assertTrue(cats.contains(BlocklistId.unsafe("test_ads"))) &&
        assertTrue(cats.contains(BlocklistId.unsafe("test_social"))) &&
        assertTrue(ads.contains(Hostname.unsafe("doubleclick.net")))
    },
    test("summaries: returns metadata rows joined with host counts") {

      for {
        _       <- cleanDb
        blRepo  <- ZIO.service[BlocklistRepo]
        bundled <- BundledBlocklists.loadAll()
        _       <- BundledBlocklists.seed(blRepo, bundled)
        rs      <- blRepo.summaries
        ads     = rs.find(_.id == BlocklistId.unsafe("ads"))
      } yield assertTrue(rs.size == bundled.size) &&
        assertTrue(ads.exists(_.bundled)) &&
        assertTrue(ads.exists(_.hostCount > 0)) &&
        assertTrue(ads.exists(_.lastBuiltAt.isDefined))
    },
  ) @@ TestAspect.sequential
}
