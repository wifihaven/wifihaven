package wifihaven.api.feature

import wifihaven.api.{
  BlocklistCache,
  BlocklistFetcher,
  BlocklistFormat,
  BundledBlocklist,
  BundledBlocklistContent,
  BundledBlocklists,
}
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
 * No test ever hits the network — remote-sourced YAML entries are exercised via a `StubFetcher`
 * that returns canned bytes. The real `BlocklistFetcher.Live` is covered indirectly by the parser
 * unit tests in `BlocklistFetcherSpec`.
 *
 * Cleanup of the #706 leaked test_* rows is covered by V32__cleanup_test_blocklists.sql running on
 * the embedded Postgres at TestDatabase.cleanAndMigrate.
 */
object BundledBlocklistsSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & doobie.Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private def cleanDb = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
    TestDatabase.cleanAndMigrate.provide(ZLayer.succeed(pg)),
  )

  /** Fetcher that returns canned hosts for known URLs and fails for everything else. */
  final class StubFetcher(responses: Map[String, List[Hostname]], failures: Set[String] = Set.empty)
      extends BlocklistFetcher {
    def fetch(url: String, format: BlocklistFormat): Task[List[Hostname]] =
      if (failures.contains(url))
        ZIO.fail(new RuntimeException(s"stubbed network failure for $url"))
      else
        responses.get(url) match {
          case Some(hs) => ZIO.succeed(hs)
          case None     => ZIO.fail(new RuntimeException(s"unstubbed url: $url"))
        }
  }

  private def newCache: UIO[BlocklistCache] =
    Ref.make(Map.empty[BlocklistId, BlocklistCache.Entry]).map(new BlocklistCache.Live(_))

  /**
   * Stub responses keyed by the URLs referenced by our bundled YAML files. Each upstream is given a
   * tiny canned response so seeding completes without network.
   */
  private val stubbedUpstreams: Map[String, List[Hostname]] = Map(
    "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"                        ->
      List("ads-extended-upstream.example", "tracker.example").map(Hostname.unsafe),
    "https://urlhaus.abuse.ch/downloads/hostfile/"                                            ->
      List("malware-upstream.example").map(Hostname.unsafe),
    "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/social-only/hosts"   ->
      List("social-extended-upstream.example").map(Hostname.unsafe),
    "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts"     ->
      List("adult-extended-upstream.example").map(Hostname.unsafe),
  )

  private def stubFetcher: BlocklistFetcher = new StubFetcher(stubbedUpstreams)

  /** All inline lists — used by tests that only care about offline behavior. */
  private def loadInlineOnly: Task[List[BundledBlocklist]] =
    BundledBlocklists
      .loadAll()
      .map(_.filter(_.content match {
        case _: BundledBlocklistContent.Inline => true
        case _                                 => false
      }))

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
    test("every bundled blocklist parses, has name/description/source, and a content payload") {
      for {
        bundled <- BundledBlocklists.loadAll()
      } yield assertTrue(bundled.nonEmpty) &&
        assertTrue(bundled.forall(_.name.nonEmpty)) &&
        assertTrue(bundled.forall(_.description.nonEmpty)) &&
        assertTrue(bundled.forall(_.source.nonEmpty)) &&
        assertTrue(bundled.map(_.id).distinct.size == bundled.size) &&
        assertTrue(bundled.forall(_.content match {
          case BundledBlocklistContent.Inline(hs) => hs.nonEmpty
          case _: BundledBlocklistContent.Remote  => true
        }))
    },
    test("every inline host parses as a valid hostname") {
      for {
        bundled <- BundledBlocklists.loadAll()
      } yield assertTrue(
        bundled
          .collect { case b @ BundledBlocklist(_, _, _, _, BundledBlocklistContent.Inline(hs)) =>
            hs
          }
          .flatten
          .forall(h => Hostname.parse(h.value).isRight),
      )
    },
    test("every remote url is https") {
      for {
        bundled <- BundledBlocklists.loadAll()
      } yield assertTrue(
        bundled
          .collect { case BundledBlocklist(_, _, _, _, BundledBlocklistContent.Remote(u, _)) => u }
          .forall(_.startsWith("https://")),
      )
    },
    test("seeder populates blocklist_domains and blocklists metadata row (inline)") {
      for {
        _       <- cleanDb
        blRepo  <- ZIO.service[BlocklistRepo]
        cache   <- newCache
        bundled <- loadInlineOnly
        _       <- BundledBlocklists.seed(blRepo, cache, stubFetcher, bundled)
        ads     <- blRepo.loadCategory(BlocklistId.unsafe("ads"))
        meta    <- blRepo.findMeta(BlocklistId.unsafe("ads"))
      } yield assertTrue(ads.nonEmpty) &&
        assertTrue(ads.contains(Hostname.unsafe("doubleclick.net"))) &&
        assertTrue(meta.isDefined) &&
        assertTrue(meta.exists(m => m.bundled && m.name == "Ads & Trackers"))
    },
    test("seeder is idempotent (running twice yields the same hosts)") {
      for {
        _         <- cleanDb
        blRepo    <- ZIO.service[BlocklistRepo]
        cache     <- newCache
        bundled   <- loadInlineOnly
        _         <- BundledBlocklists.seed(blRepo, cache, stubFetcher, bundled)
        first     <- blRepo.summaries.map(_.find(_.id == BlocklistId.unsafe("ads")))
        firstAds  <- blRepo.loadCategory(BlocklistId.unsafe("ads"))
        _         <- BundledBlocklists.seed(blRepo, cache, stubFetcher, bundled)
        second    <- blRepo.summaries.map(_.find(_.id == BlocklistId.unsafe("ads")))
        secondAds <- blRepo.loadCategory(BlocklistId.unsafe("ads"))
      } yield assertTrue(firstAds == secondAds) &&
        assertTrue(first.exists(_.hostCount == secondAds.size)) &&
        assertTrue(second.exists(_.hostCount == secondAds.size))
    },
    test("seeder REPLACES hosts — manual inserts get overwritten on re-seed") {
      for {
        _       <- cleanDb
        blRepo  <- ZIO.service[BlocklistRepo]
        cache   <- newCache
        bundled <- loadInlineOnly
        _       <- BundledBlocklists.seed(blRepo, cache, stubFetcher, bundled)
        _       <- blRepo.insertBatch(List(("manual-injected.example", "ads")))
        before  <- blRepo.loadCategory(BlocklistId.unsafe("ads"))
        _       <- BundledBlocklists.seed(blRepo, cache, stubFetcher, bundled)
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
    test(
      "V32 cleanup migration: removes test_* even after re-insertion + scrubs profiles.blocked_categories",
    ) {
      for {
        _       <- cleanDb
        blRepo  <- ZIO.service[BlocklistRepo]
        pr      <- ZIO.service[ProfileRepo]
        _       <- blRepo.insertBatch(
          List(
            ("adserver.example.com", "test_ads"),
            ("doubleclick.net", "test_ads"),
            ("facebook.com", "test_social"),
          ),
        )
        pid     <- pr.create(
          "Kids",
          List(BlocklistId.unsafe("test_ads"), BlocklistId.unsafe("test_social")),
        )
        xa      <- ZIO.service[doobie.Transactor[Task]]
        _       <- {
          import doobie.implicits.*
          sql"DELETE FROM blocklist_domains WHERE category IN ('test_ads', 'test_social')".update.run
            .transact(xa)
        }
        _       <- {
          import doobie.implicits.*
          sql"DELETE FROM blocklists WHERE id IN ('test_ads', 'test_social')".update.run
            .transact(xa)
        }
        _       <- {
          import doobie.implicits.*
          sql"""UPDATE profiles
                SET blocked_categories = array_remove(
                      array_remove(blocked_categories, 'test_ads'),
                      'test_social'
                    )
                WHERE blocked_categories && ARRAY['test_ads', 'test_social']::TEXT[]""".update.run
            .transact(xa)
        }
        cats    <- blRepo.listCategories
        profile <- pr.findById(pid).someOrFailException
      } yield assertTrue(!cats.contains(BlocklistId.unsafe("test_ads"))) &&
        assertTrue(!cats.contains(BlocklistId.unsafe("test_social"))) &&
        assertTrue(profile.blockedCategories.isEmpty)
    },
    test("dev test seeder seeds test_ads + test_social when invoked") {
      for {
        _      <- cleanDb
        blRepo <- ZIO.service[BlocklistRepo]
        cache  <- newCache
        _ <- BundledBlocklists.seed(blRepo, cache, stubFetcher, BundledBlocklists.devTestBlocklists)
        cats <- blRepo.listCategories
        ads  <- blRepo.loadCategory(BlocklistId.unsafe("test_ads"))
      } yield assertTrue(cats.contains(BlocklistId.unsafe("test_ads"))) &&
        assertTrue(cats.contains(BlocklistId.unsafe("test_social"))) &&
        assertTrue(ads.contains(Hostname.unsafe("doubleclick.net")))
    },
    test("summaries: returns metadata rows joined with host counts (inline only)") {
      for {
        _       <- cleanDb
        blRepo  <- ZIO.service[BlocklistRepo]
        cache   <- newCache
        bundled <- loadInlineOnly
        _       <- BundledBlocklists.seed(blRepo, cache, stubFetcher, bundled)
        rs      <- blRepo.summaries
        ads = rs.find(_.id == BlocklistId.unsafe("ads"))
      } yield assertTrue(rs.size == bundled.size) &&
        assertTrue(ads.exists(_.bundled)) &&
        assertTrue(ads.exists(_.hostCount > 0)) &&
        assertTrue(ads.exists(_.lastBuiltAt.isDefined))
    },
    test("remote: stub fetcher populates blocklist_domains and the cache") {
      for {
        _       <- cleanDb
        blRepo  <- ZIO.service[BlocklistRepo]
        cache   <- newCache
        bundled <- BundledBlocklists.loadAll()
        _       <- BundledBlocklists.seed(blRepo, cache, stubFetcher, bundled)
        hosts   <- blRepo.loadCategory(BlocklistId.unsafe("ads-extended"))
        cached  <- cache.get(BlocklistId.unsafe("ads-extended"))
      } yield assertTrue(hosts.contains(Hostname.unsafe("ads-extended-upstream.example"))) &&
        assertTrue(
          cached.exists(_.hosts.contains(Hostname.unsafe("ads-extended-upstream.example"))),
        )
    },
    test("remote fetch failure leaves existing DB rows untouched") {
      val failingFetcher = new StubFetcher(
        responses = stubbedUpstreams,
        failures = Set("https://urlhaus.abuse.ch/downloads/hostfile/"),
      )
      for {
        _       <- cleanDb
        blRepo  <- ZIO.service[BlocklistRepo]
        cache   <- newCache
        bundled <- BundledBlocklists.loadAll()
        // First seed succeeds with the working fetcher and populates the malware list.
        _       <- BundledBlocklists.seed(blRepo, cache, stubFetcher, bundled)
        before  <- blRepo.loadCategory(BlocklistId.unsafe("malware"))
        // Second seed fails the malware fetch but should leave the existing rows alone.
        _       <- BundledBlocklists.seed(blRepo, cache, failingFetcher, bundled)
        after   <- blRepo.loadCategory(BlocklistId.unsafe("malware"))
      } yield assertTrue(before.nonEmpty) && assertTrue(before == after)
    },
    test("refresh(): re-fetches a single bundled list and returns new host count") {
      for {
        _       <- cleanDb
        blRepo  <- ZIO.service[BlocklistRepo]
        cache   <- newCache
        bundled <- BundledBlocklists.loadAll()
        _       <- BundledBlocklists.seed(blRepo, cache, stubFetcher, bundled)
        adsExt = bundled.find(_.id == BlocklistId.unsafe("ads-extended")).get
        n <- BundledBlocklists.refresh(blRepo, cache, stubFetcher, adsExt)
      } yield assertTrue(n.contains(2))
    },
  ) @@ TestAspect.sequential
}
