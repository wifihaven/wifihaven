package wifihaven.api.feature

import wifihaven.api.{AppReconciler, AppTemplate, AppTemplates}
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

  // #1794: the exact prod duplicate shape — operator-added canonical `1password` (template_id
  // NULL, carrying assignments) co-existing with a seeded `1password-template` row. Pinned so the
  // boot-time seed→reconcile pipeline that #1794 wires into `Main` provably collapses it.
  private val onePasswordSlug     = AppTemplateId.unsafe("1password")
  private val onePasswordTemplate = AppTemplate(
    slug = onePasswordSlug,
    name = "1Password",
    icon = None,
    iconType = IconType.Emoji,
    hosts = List(Hostname.unsafe("1password.com"), Hostname.unsafe("1passwordusercontent.com")),
  )

  def spec = suite("AppReconciler")(
    test(
      "#1794: seed-then-reconcile (the boot pipeline) collapses the prod operator+`-template` " +
        "shape into one canonical row, preserving assignments",
    ) {
      for {
        _        <- cleanDb
        appRepo  <- ZIO.service[AppRepo]
        profileR <- ZIO.service[ProfileRepo]
        profiles <- profileR.listAll
        kidsId   = profiles.find(_.name == "Kids").get.id
        adultsId = profiles.find(_.name == "Adults").get.id
        // Operator created `1password` BEFORE the template seeder ran (template_id NULL), and
        // attached policy assignments to it — exactly prod app id17.
        operatorId <- appRepo.create("1Password", "1password", None, Some("🔑"), IconType.Emoji)
        _          <- appRepo.setHosts(operatorId, List(Hostname.unsafe("1password.com")))
        _          <- appRepo.upsertAssignment(operatorId, kidsId, AppMode.Blocked, None, true)
        _          <- appRepo.upsertAssignment(operatorId, adultsId, AppMode.Allowed, None, true)
        // Boot step 1 — seed. `findByTemplateId(1password)` is None and the `1password` slug is
        // taken, so `findFreeSlug` falls back to `1password-template`: this is how prod accreted
        // the dup (the seeder can never un-create it because that row holds the template_id).
        _          <- AppTemplates.seed(appRepo, List(onePasswordTemplate))
        afterSeed  <- appRepo.listAll
        // Boot step 2 — reconcile (what #1794 adds). Collapses the pair.
        summary    <- AppReconciler.reconcileTemplates(appRepo, List(onePasswordTemplate))
        afterRecon <- appRepo.listAll
        canonical  <- appRepo.findBySlug("1password").someOrFailException
        canonHosts <- appRepo.getHosts(canonical.id)
        canonAsgn  <- appRepo.listAssignmentsForApp(canonical.id)
        suffixGone <- appRepo.findBySlug("1password-template")
        // Boot step 2 is idempotent on the next reboot.
        second     <- AppReconciler.reconcileTemplates(appRepo, List(onePasswordTemplate))
        afterAgain <- appRepo.listAll
      } yield
      // The seed reproduces the dup the operator saw: two rows for one logical app.
      assertTrue(afterSeed.count(_.name == "1Password") == 2) &&
        assertTrue(afterSeed.count(_.slug == "1password-template") == 1) &&
        // Reconcile collapses to a single canonical row carrying the template_id…
        assertTrue(afterRecon.count(_.name == "1Password") == 1) &&
        assertTrue(canonical.id == operatorId) &&
        assertTrue(canonical.templateId.contains(onePasswordSlug)) &&
        assertTrue(suffixGone.isEmpty) &&
        // …with the operator's assignments preserved on it…
        assertTrue(canonAsgn.map(_.profileId).toSet == Set(kidsId, adultsId)) &&
        // …and the template hosts unioned in.
        assertTrue(canonHosts.toSet == onePasswordTemplate.hosts.toSet) &&
        assertTrue(summary.mergedSlugs == List("1password")) &&
        // The next reboot's reconcile is a clean no-op — no whack-a-mole.
        assertTrue(second.mergedSlugs.isEmpty && second.renamedSlugs.isEmpty) &&
        assertTrue(afterAgain.count(_.slug == "1password") == 1) &&
        assertTrue(afterAgain.count(_.slug == "1password-template") == 0)
    },
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
