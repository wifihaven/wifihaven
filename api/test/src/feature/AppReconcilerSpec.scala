package wifihaven.api.feature

import wifihaven.api.{AppReconciler, AppTemplate}
import wifihaven.api.db.*
import wifihaven.shared.*
import wifihaven.shared.IconType
import wifihaven.shared.types.*
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.test.*

/**
 * #1777: tests for `AppReconciler.reconcileTemplates`. Seeds the messy state — an operator-added
 * canonical row co-existing with a `-template`-suffixed seeded row, with FK refs on both — and
 * asserts the reconciliation converges onto a single canonical row, FKs reattached and host-set
 * unioned. Also tests the rename-only path and idempotency.
 */
object AppReconcilerSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val youtubeSlug     = AppTemplateId.unsafe("youtube")
  private val youtubeTemplate = AppTemplate(
    slug = youtubeSlug,
    name = "YouTube",
    icon = Some("https://example/yt.png"),
    iconType = IconType.Url,
    hosts = List(Hostname.unsafe("youtube.com"), Hostname.unsafe("ytimg.com")),
  )

  def spec = suite("AppReconciler")(
    test(
      "reconcileTemplates merges -template row INTO canonical, reattaches FK refs, unions hosts",
    ) {
      for {
        _        <- cleanDb
        appRepo  <- ZIO.service[AppRepo]
        profileR <- ZIO.service[ProfileRepo]
        profiles <- profileR.listAll
        kidsId   = profiles.find(_.name == "Kids").get.id
        adultsId = profiles.find(_.name == "Adults").get.id
        // 1. Operator-added canonical 'youtube' (no template_id). Operator added an extra host.
        operatorId <- appRepo.create("My YouTube", "youtube", None, Some("📺"), IconType.Emoji)
        operatorHost = Hostname.unsafe("operator-only.example.com")
        _              <- appRepo.setHosts(
          operatorId,
          List(Hostname.unsafe("youtube.com"), operatorHost),
        )
        _              <- appRepo.upsertAssignment(operatorId, kidsId, AppMode.Blocked, None, true)
        // 2. Seeded '-template'-suffixed row carrying template_id, distinct assignment + hosts.
        seededId       <- appRepo.create(
          "YouTube",
          "youtube-template",
          Some(youtubeSlug),
          Some("https://example/yt.png"),
          IconType.Url,
        )
        _              <- appRepo.setHosts(
          seededId,
          List(Hostname.unsafe("youtube.com"), Hostname.unsafe("googlevideo.com")),
        )
        _              <- appRepo.upsertAssignment(
          seededId,
          adultsId,
          AppMode.TimeLimited,
          Some(30),
          true,
        )
        // 3. Reconcile.
        summary        <- AppReconciler.reconcileTemplates(appRepo, List(youtubeTemplate))
        // 4. Assertions.
        after          <- appRepo.listAll
        canonicalOpt   <- appRepo.findBySlug("youtube")
        canonical      <- ZIO
          .fromOption(canonicalOpt)
          .orElseFail(new RuntimeException("missing canonical"))
        canonicalHosts <- appRepo.getHosts(canonical.id)
        canonicalAsgn  <- appRepo.listAssignmentsForApp(canonical.id)
        suffixedGone   <- appRepo.findBySlug("youtube-template")
      } yield assertTrue(after.count(_.slug == "youtube") == 1) &&
        assertTrue(after.count(_.slug == "youtube-template") == 0) &&
        assertTrue(suffixedGone.isEmpty) &&
        assertTrue(canonical.templateId.contains(youtubeSlug)) &&
        // Host-set is the UNION: template hosts + operator's extra host + seeded's googlevideo.com.
        assertTrue(
          canonicalHosts.toSet == Set(
            Hostname.unsafe("youtube.com"),
            Hostname.unsafe("ytimg.com"),
            Hostname.unsafe("googlevideo.com"),
            operatorHost,
          ),
        ) &&
        // Both assignments reattached to canonical.
        assertTrue(canonicalAsgn.size == 2) &&
        assertTrue(canonicalAsgn.map(_.profileId).toSet == Set(kidsId, adultsId)) &&
        assertTrue(summary.mergedSlugs == List("youtube")) &&
        assertTrue(summary.renamedSlugs.isEmpty)
    },
    test("reconcileTemplates renames -template row when no canonical co-exists") {
      for {
        _        <- cleanDb
        appRepo  <- ZIO.service[AppRepo]
        profileR <- ZIO.service[ProfileRepo]
        profiles <- profileR.listAll
        kidsId = profiles.find(_.name == "Kids").get.id
        id       <- appRepo.create(
          "YouTube",
          "youtube-template",
          Some(youtubeSlug),
          Some("https://example/yt.png"),
          IconType.Url,
        )
        _        <- appRepo.setHosts(id, List(Hostname.unsafe("youtube.com")))
        _        <- appRepo.upsertAssignment(id, kidsId, AppMode.Blocked, None, true)
        summary  <- AppReconciler.reconcileTemplates(appRepo, List(youtubeTemplate))
        renamed  <- appRepo.findById(id).someOrFailException
        hosts    <- appRepo.getHosts(id)
        suffixed <- appRepo.findBySlug("youtube-template")
        asgn     <- appRepo.listAssignmentsForApp(id)
      } yield assertTrue(renamed.slug == "youtube") &&
        assertTrue(renamed.templateId.contains(youtubeSlug)) &&
        assertTrue(suffixed.isEmpty) &&
        assertTrue(hosts.toSet == youtubeTemplate.hosts.toSet) &&
        assertTrue(asgn.size == 1) &&
        assertTrue(summary.renamedSlugs == List("youtube")) &&
        assertTrue(summary.mergedSlugs.isEmpty)
    },
    test("reconcileTemplates is a no-op on already-clean state") {
      for {
        _       <- cleanDb
        appRepo <- ZIO.service[AppRepo]
        id      <- appRepo.create(
          "YouTube",
          "youtube",
          Some(youtubeSlug),
          Some("https://example/yt.png"),
          IconType.Url,
        )
        _       <- appRepo.setHosts(id, youtubeTemplate.hosts)
        before  <- appRepo.listAll
        summary <- AppReconciler.reconcileTemplates(appRepo, List(youtubeTemplate))
        after   <- appRepo.listAll
      } yield assertTrue(before.map(_.id).toSet == after.map(_.id).toSet) &&
        assertTrue(summary.mergedSlugs.isEmpty) &&
        assertTrue(summary.renamedSlugs.isEmpty)
    },
    test("reconcileTemplates is idempotent — second run does nothing") {
      for {
        _        <- cleanDb
        appRepo  <- ZIO.service[AppRepo]
        _        <- appRepo.create("My YouTube", "youtube", None, Some("📺"), IconType.Emoji)
        sId      <- appRepo.create(
          "YouTube",
          "youtube-template",
          Some(youtubeSlug),
          Some("https://example/yt.png"),
          IconType.Url,
        )
        _        <- appRepo.setHosts(sId, youtubeTemplate.hosts)
        first    <- AppReconciler.reconcileTemplates(appRepo, List(youtubeTemplate))
        second   <- AppReconciler.reconcileTemplates(appRepo, List(youtubeTemplate))
        afterAll <- appRepo.listAll
      } yield assertTrue(first.mergedSlugs == List("youtube")) &&
        assertTrue(second.mergedSlugs.isEmpty) &&
        assertTrue(second.renamedSlugs.isEmpty) &&
        assertTrue(afterAll.count(_.slug == "youtube") == 1) &&
        assertTrue(afterAll.count(_.slug == "youtube-template") == 0)
    },
  ) @@ TestAspect.sequential
}
