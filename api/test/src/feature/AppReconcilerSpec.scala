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
        profiles <- profileR.listAllForHousehold(HouseholdId.Default)
        kidsId   = profiles.find(_.name == "Kids").get.id
        adultsId = profiles.find(_.name == "Adults").get.id
        // 1. Operator-added canonical 'youtube' (no template_id). Operator added an extra host.
        operatorId <- appRepo.create("My YouTube", "youtube", None, Some("📺"), IconType.Emoji)
        operatorHost = Hostname.unsafe("operator-only.example.com")
        _          <- appRepo.setHosts(
          operatorId,
          List(Hostname.unsafe("youtube.com"), operatorHost),
        )
        _          <- appRepo.upsertAssignment(operatorId, kidsId, AppMode.Blocked, None, true)
        // 2. Seeded '-template'-suffixed row carrying template_id, distinct assignment + hosts.
        seededId   <- appRepo.create(
          "YouTube",
          "youtube-template",
          Some(youtubeSlug),
          Some("https://example/yt.png"),
          IconType.Url,
        )
        _          <- appRepo.setHosts(
          seededId,
          List(Hostname.unsafe("youtube.com"), Hostname.unsafe("googlevideo.com")),
        )
        _          <- appRepo.upsertAssignment(
          seededId,
          adultsId,
          AppMode.TimeLimited,
          Some(30),
          true,
        )
        // 2b. Seed app_used_daily rows on both sides — overlapping (profile_id, date) on the
        // Kids row to exercise the SUM-on-conflict path; a non-overlapping (Adults) row to
        // exercise the plain reattach path. Verifies the rollup-table FK merge SQL.
        rollupRepo <- ZIO.service[AppUsedRollupRepo]
        usageDate     = java.time.LocalDate.parse("2026-06-15")
        rolledThrough = java.time.Instant.parse("2026-06-15T23:59:00Z")
        _              <- rollupRepo
          .upsertDay(kidsId, operatorId, usageDate, RolledAppDay(120L, rolledThrough))
        _              <- rollupRepo
          .upsertDay(kidsId, seededId, usageDate, RolledAppDay(300L, rolledThrough))
        _              <- rollupRepo
          .upsertDay(adultsId, seededId, usageDate, RolledAppDay(600L, rolledThrough))
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
        // (profile, date) overlap collapses onto canonical with engaged_seconds summed.
        kidsUsage      <- rollupRepo.getDayForProfile(kidsId, usageDate)
        // Non-overlapping (Adults) row reattached.
        adultsUsage    <- rollupRepo.getDayForProfile(adultsId, usageDate)
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
        // engaged_seconds is the SUM of the conflicting (canonical=120) + (suffixed=300) row.
        assertTrue(kidsUsage.get(canonical.id).map(_.engagedSeconds).contains(420L)) &&
        // Adults-only suffixed row reattached to canonical id.
        assertTrue(adultsUsage.get(canonical.id).map(_.engagedSeconds).contains(600L)) &&
        // No stray rows still keyed by the deleted suffixed app id.
        assertTrue(!kidsUsage.contains(seededId)) &&
        assertTrue(!adultsUsage.contains(seededId)) &&
        assertTrue(summary.mergedSlugs == List("youtube")) &&
        assertTrue(summary.renamedSlugs.isEmpty)
    },
    test("reconcileTemplates renames -template row when no canonical co-exists") {
      for {
        _        <- cleanDb
        appRepo  <- ZIO.service[AppRepo]
        profileR <- ZIO.service[ProfileRepo]
        profiles <- profileR.listAllForHousehold(HouseholdId.Default)
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
    // #2820: retiring a template file leaves its seeded `apps` row behind — still
    // carrying its template_id, hosts, per-profile assignments and usage history,
    // managed by no template. These pin that the surviving template absorbs it.
    test("#2820 reconcileTemplates merges a retired template's orphan row into the survivor") {
      for {
        _          <- cleanDb
        appRepo    <- ZIO.service[AppRepo]
        profileR   <- ZIO.service[ProfileRepo]
        rollupRepo <- ZIO.service[AppUsedRollupRepo]
        profiles   <- profileR.listAllForHousehold(HouseholdId.Default)
        kidsId   = profiles.find(_.name == "Kids").get.id
        adultsId = profiles.find(_.name == "Adults").get.id
        templates  <- wifihaven.api.AppTemplates.loadAll()
        // Pre-#2820 state: both rows seeded from the two templates that used to exist.
        survivorId <- appRepo.create(
          "icanhazip",
          "icanhazip",
          Some(AppTemplateId.unsafe("icanhazip")),
          None,
          IconType.Url,
        )
        _          <- appRepo.setHosts(survivorId, List(Hostname.unsafe("icanhazip.com")))
        retiredId  <- appRepo.create(
          "ipify",
          "ipify",
          Some(AppTemplateId.unsafe("ipify")),
          None,
          IconType.Url,
        )
        _          <- appRepo.setHosts(
          retiredId,
          List(Hostname.unsafe("api.ipify.org"), Hostname.unsafe("api64.ipify.org")),
        )
        // The retired row carries a per-profile assignment and usage history.
        _          <- appRepo.upsertAssignment(retiredId, kidsId, AppMode.Allowed, None, true)
        usageDate     = java.time.LocalDate.parse("2026-09-15")
        rolledThrough = java.time.Instant.parse("2026-09-15T23:59:00Z")
        _ <- rollupRepo.upsertDay(kidsId, retiredId, usageDate, RolledAppDay(300L, rolledThrough))
        _ <- rollupRepo.upsertDay(adultsId, retiredId, usageDate, RolledAppDay(60L, rolledThrough))
        // Seed + reconcile with the post-#2820 catalog.
        _ <- wifihaven.api.AppTemplates.seed(appRepo, templates)
        _ <- AppReconciler.reconcileTemplates(appRepo, templates)
        after       <- appRepo.listAll
        survivor    <- appRepo.findBySlug("icanhazip").someOrFailException
        hosts       <- appRepo.getHosts(survivor.id)
        asgn        <- appRepo.listAssignmentsForApp(survivor.id)
        kidsUsage   <- rollupRepo.getDayForProfile(kidsId, usageDate)
        adultsUsage <- rollupRepo.getDayForProfile(adultsId, usageDate)
      } yield assertTrue(
        // no orphan row left behind, under either the slug or the template id
        after.count(_.slug == "ipify") == 0,
        after.count(_.templateId.contains(AppTemplateId.unsafe("ipify"))) == 0,
        survivor.id == survivorId,
        // the survivor carries the union of both host sets
        hosts.toSet == Set(
          Hostname.unsafe("icanhazip.com"),
          Hostname.unsafe("api.ipify.org"),
          Hostname.unsafe("api64.ipify.org"),
        ),
        // the retired row's assignment moved rather than being silently dropped
        asgn.map(_.profileId) == List(kidsId),
        asgn.head.mode == AppMode.Allowed,
        // ...as did its usage history, now attributed to the survivor
        kidsUsage.get(survivor.id).map(_.engagedSeconds).contains(300L),
        adultsUsage.get(survivor.id).map(_.engagedSeconds).contains(60L),
        !kidsUsage.contains(retiredId),
      )
    },
    test("#2820 retirement is idempotent — a second reconcile changes nothing") {
      for {
        _           <- cleanDb
        appRepo     <- ZIO.service[AppRepo]
        templates   <- wifihaven.api.AppTemplates.loadAll()
        retiredId   <- appRepo.create(
          "ipify",
          "ipify",
          Some(AppTemplateId.unsafe("ipify")),
          None,
          IconType.Url,
        )
        _           <- appRepo.setHosts(retiredId, List(Hostname.unsafe("api.ipify.org")))
        _           <- wifihaven.api.AppTemplates.seed(appRepo, templates)
        _           <- AppReconciler.reconcileTemplates(appRepo, templates)
        first       <- appRepo.listAll
        firstHosts  <- ZIO.foreach(first)(a => appRepo.getHosts(a.id).map(a.slug -> _.toSet))
        _           <- AppReconciler.reconcileTemplates(appRepo, templates)
        second      <- appRepo.listAll
        secondHosts <- ZIO.foreach(second)(a => appRepo.getHosts(a.id).map(a.slug -> _.toSet))
      } yield assertTrue(
        first.map(_.id).toSet == second.map(_.id).toSet,
        firstHosts.toSet == secondHosts.toSet,
        second.count(_.slug == "ipify") == 0,
      )
    },
    test("#2820 retirement leaves an unrelated app squatting the retired slug alone") {
      // Only a row carrying the retired TEMPLATE id is absorbed. An operator row that
      // merely happens to use the slug is not ours to delete.
      for {
        _         <- cleanDb
        appRepo   <- ZIO.service[AppRepo]
        templates <- wifihaven.api.AppTemplates.loadAll()
        squatId   <- appRepo.create("Operator ipify", "ipify", None, Some("📶"), IconType.Emoji)
        _         <- appRepo.setHosts(squatId, List(Hostname.unsafe("example.com")))
        _         <- wifihaven.api.AppTemplates.seed(appRepo, templates)
        _         <- AppReconciler.reconcileTemplates(appRepo, templates)
        still     <- appRepo.findById(squatId)
        hosts     <- appRepo.getHosts(squatId)
      } yield assertTrue(
        still.isDefined,
        still.get.slug == "ipify",
        hosts == List(Hostname.unsafe("example.com")),
      )
    },
    test("#2820 the boot sequence (seed then retire) leaves no orphan row behind") {
      // Pins the wiring Main uses: AppTemplates.seed walks only templates that
      // exist, so the retirement pass is what prunes the deleted template's row.
      for {
        _          <- cleanDb
        appRepo    <- ZIO.service[AppRepo]
        templates  <- wifihaven.api.AppTemplates.loadAll()
        retiredId  <- appRepo.create(
          "ipify",
          "ipify",
          Some(AppTemplateId.unsafe("ipify")),
          None,
          IconType.Url,
        )
        _          <- appRepo.setHosts(retiredId, List(Hostname.unsafe("api.ipify.org")))
        _          <- wifihaven.api.AppTemplates.seed(appRepo, templates)
        retiredNow <- AppReconciler.retireSupersededRows(appRepo, templates)
        again      <- AppReconciler.retireSupersededRows(appRepo, templates)
        after      <- appRepo.listAll
        survivor   <- appRepo.findBySlug("icanhazip").someOrFailException
        hosts      <- appRepo.getHosts(survivor.id)
      } yield assertTrue(
        retiredNow == List("ipify"),
        again.isEmpty,
        after.count(_.templateId.contains(AppTemplateId.unsafe("ipify"))) == 0,
        hosts.toSet == Set(
          Hostname.unsafe("icanhazip.com"),
          Hostname.unsafe("api.ipify.org"),
          Hostname.unsafe("api64.ipify.org"),
        ),
      )
    },
    test("#2820 retirement merges into the TEMPLATE's row, not an operator app on its slug") {
      // AppTemplates.findFreeSlug parks the seeded row at `<slug>-template` when an
      // operator app already owns the canonical slug. At boot no reconcileOne pass has
      // collapsed that yet, so resolving the survivor by slug would hand the retired
      // row's hosts, assignments and history to the operator's app.
      for {
        _        <- cleanDb
        appRepo  <- ZIO.service[AppRepo]
        profileR <- ZIO.service[ProfileRepo]
        profiles <- profileR.listAllForHousehold(HouseholdId.Default)
        kidsId = profiles.find(_.name == "Kids").get.id
        templates  <- wifihaven.api.AppTemplates.loadAll()
        // Operator app squatting the SURVIVOR's canonical slug.
        squatId    <- appRepo.create("My IP thing", "icanhazip", None, Some("📶"), IconType.Emoji)
        _          <- appRepo.setHosts(squatId, List(Hostname.unsafe("squatter.example.com")))
        // The retired row, with an assignment and usage to be carried.
        retiredId  <- appRepo.create(
          "ipify",
          "ipify",
          Some(AppTemplateId.unsafe("ipify")),
          None,
          IconType.Url,
        )
        _          <- appRepo.setHosts(retiredId, List(Hostname.unsafe("api.ipify.org")))
        _          <- appRepo.upsertAssignment(retiredId, kidsId, AppMode.Allowed, None, true)
        // Boot order: seed (parks the template row at icanhazip-template), then retire.
        _          <- wifihaven.api.AppTemplates.seed(appRepo, templates)
        _          <- AppReconciler.retireSupersededRows(appRepo, templates)
        squat      <- appRepo.findById(squatId).someOrFailException
        squatHosts <- appRepo.getHosts(squatId)
        squatAsgn  <- appRepo.listAssignmentsForApp(squatId)
        template   <- appRepo
          .findByTemplateId(AppTemplateId.unsafe("icanhazip"))
          .someOrFailException
        tmplHosts  <- appRepo.getHosts(template.id)
        tmplAsgn   <- appRepo.listAssignmentsForApp(template.id)
        after      <- appRepo.listAll
      } yield assertTrue(
        // the operator's app is untouched — no hosts, assignment or template_id grafted on
        squatHosts == List(Hostname.unsafe("squatter.example.com")),
        squatAsgn.isEmpty,
        squat.templateId.isEmpty,
        squat.name == "My IP thing",
        // the template's own row absorbed the retirement
        template.id != squatId,
        tmplHosts.toSet == Set(
          Hostname.unsafe("icanhazip.com"),
          Hostname.unsafe("api.ipify.org"),
          Hostname.unsafe("api64.ipify.org"),
        ),
        tmplAsgn.map(_.profileId) == List(kidsId),
        after.count(_.templateId.contains(AppTemplateId.unsafe("ipify"))) == 0,
      )
    },
    test("#2820 the survivor's assignment wins where both rows cover the same profile") {
      for {
        _        <- cleanDb
        appRepo  <- ZIO.service[AppRepo]
        profileR <- ZIO.service[ProfileRepo]
        profiles <- profileR.listAllForHousehold(HouseholdId.Default)
        kidsId = profiles.find(_.name == "Kids").get.id
        templates <- wifihaven.api.AppTemplates.loadAll()
        retiredId <- appRepo.create(
          "ipify",
          "ipify",
          Some(AppTemplateId.unsafe("ipify")),
          None,
          IconType.Url,
        )
        _         <- appRepo.setHosts(retiredId, List(Hostname.unsafe("api.ipify.org")))
        _         <- appRepo.upsertAssignment(retiredId, kidsId, AppMode.Blocked, None, true)
        _         <- wifihaven.api.AppTemplates.seed(appRepo, templates)
        survivor  <- appRepo
          .findByTemplateId(AppTemplateId.unsafe("icanhazip"))
          .someOrFailException
        _         <- appRepo.upsertAssignment(survivor.id, kidsId, AppMode.Allowed, None, true)
        _         <- AppReconciler.retireSupersededRows(appRepo, templates)
        asgn      <- appRepo.listAssignmentsForApp(survivor.id)
      } yield assertTrue(
        asgn.map(_.profileId) == List(kidsId),
        // documented "survivor wins" conflict policy, logged as a WARN before the merge
        asgn.head.mode == AppMode.Allowed,
      )
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
