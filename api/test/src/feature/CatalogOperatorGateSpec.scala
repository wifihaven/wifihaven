package wifihaven.api.feature

import wifihaven.api.{AppTemplate, JwtConfig}
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import doobie.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.test.*

/**
 * #2535 / #2567 — the install-wide catalog mutators are OPERATOR verbs.
 *
 * `apps` / `app_hosts` / `blocklist_domains` are single install-wide catalogs: app definitions are
 * template-authored in code (#1798) and blocklist categories are the bundled set. Neither table
 * carries `household_id`, and per-household copies are not the answer — they are genuinely shared.
 * What was wrong is the GATE: six mutating routes sat behind `requireWriter` / `requireAdmin`, so
 * an adult or admin in ANY household could rewrite state every other household's enforcement reads.
 * This suite pins them behind `requireOperator` (admin AND household 1) — the same narrow,
 * documented cross-household exception the beta-approval queue and the `free_forever` grant use
 * (Routes.scala, `requireOperator`).
 *
 * Every case is two-sided and asserts the DATA, not just the status:
 *   - a household-B admin gets 403 AND the catalog is provably byte-identical afterwards, so a 403
 *     that arrived after a partial write would still fail;
 *   - the operator (household 1's admin) still succeeds and the mutation is observable, so a gate
 *     that simply broke the route cannot masquerade as coverage.
 *
 * The two READ routes get an explicit verdict too (#2535 asks for one): `GET /api/blocklists` and
 * `GET /api/blocklists/:id/hosts` STAY `requireWriter`. The catalog they serve is bundled public
 * data, and both are live SPA surfaces (`BlocklistsPage`, the per-profile category matrix on
 * `ProfilesPage`, the app/blocklist overlap warning on `AppsPage`) — demoting them would break the
 * product for no confidentiality gain. Pinned below so the verdict is recorded, not assumed.
 *
 * Full stack on embedded Postgres, no repo mocks.
 */
object CatalogOperatorGateSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate
  private val jwtCfg  = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)

  private val macA = MacAddress.unsafe("aa:bb:cc:00:00:1a")
  private val macB = MacAddress.unsafe("aa:bb:cc:00:00:1b")

  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      // #2140: the DB-backed HouseholdRepo so login can resolve household B's slug.
      hr    <- ZIO.service[HouseholdRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock, hr): AuthService

  /** (operator token = household 1's admin, household-B admin token). */
  private def tokens(two: TestLayers.TwoHouseholds) =
    for {
      auth  <- makeAuth
      opTok <- auth.login(two.adminA, two.password).map(_.token.value)
      bTok  <- auth.login(s"${two.slugB}/${two.adminB}", two.password).map(_.token.value)
    } yield (opTok, bTok)

  private def statusOf(
      routes: Routes[Any, Response],
      method: Method,
      path: String,
      token: String,
  ): Task[Status] =
    routes
      .runZIO(
        Request(method = method, url = URL.decode(path).toOption.get)
          .addHeader(Header.Authorization.Bearer(token)),
      )
      .map(_.status)

  private def bodyOf(
      routes: Routes[Any, Response],
      path: String,
      token: String,
  ): Task[(Status, String)] =
    for {
      r <- routes.runZIO(
        Request.get(URL.decode(path).toOption.get).addHeader(Header.Authorization.Bearer(token)),
      )
      b <- r.body.asString
    } yield (r.status, b)

  // ── route wiring ─────────────────────────────────────────────────────────

  /**
   * The refresh route resolves its hosts from the `bundled` map. `Inline` content means no fetcher
   * is ever needed, so the stub below is one that fails loudly if it is reached — a silent stub
   * could hide the route quietly taking a different path.
   */
  private object NeverFetcher extends wifihaven.api.BlocklistFetcher {
    def fetch(url: String, format: wifihaven.api.BlocklistFormat): Task[List[Hostname]] =
      ZIO.fail(new RuntimeException(s"fetcher must not be reached (url=$url)"))
  }

  private val refreshCat  = BlocklistId.unsafe("ads")
  private val freshHost   = Hostname.unsafe("fresh.example.com")
  private val staleHost   = Hostname.unsafe("stale.example.com")
  private val bundledList = wifihaven.api.BundledBlocklist(
    refreshCat,
    "Ads",
    "test bundled list",
    "inline://test",
    wifihaven.api.BundledBlocklistContent.Inline(List(freshHost)),
  )

  private def blocklistRoutes(
      bundled: Map[BlocklistId, wifihaven.api.BundledBlocklist] = Map.empty,
  ) =
    for {
      blRepo <- ZIO.service[BlocklistRepo]
      auth   <- makeAuth
      cache  <- Ref
        .make(Map.empty[BlocklistId, wifihaven.api.BlocklistCache.Entry])
        .map(new wifihaven.api.BlocklistCache.Live(_))
    } yield BlocklistRoutes.routes(auth, blRepo, cache, NeverFetcher, bundled)

  private val youtubeSlug     = AppTemplateId.unsafe("youtube")
  private val youtubeTemplate = AppTemplate(
    slug = youtubeSlug,
    name = "YouTube",
    icon = None,
    iconType = IconType.Emoji,
    hosts = List(Hostname.unsafe("youtube.com"), Hostname.unsafe("ytimg.com")),
  )

  private def appRoutes(templates: Map[AppTemplateId, AppTemplate] = Map.empty) =
    for {
      appRepo <- ZIO.service[AppRepo]
      pr      <- ZIO.service[ProfileRepo]
      up      <- ZIO.service[UserProfileRepo]
      blRepo  <- ZIO.service[BlocklistRepo]
      auth    <- makeAuth
    } yield AppRoutes.routes(auth, appRepo, pr, up, blRepo, templates)

  // ── the six mutators ─────────────────────────────────────────────────────

  private val mutators = suite("the six install-wide catalog mutators are operator-only")(
    test("POST /api/blocklists/:category/clear") {
      for {
        _   <- cleanDb
        two <- TestLayers.seedTwoHouseholds(macA, macB)
        tk  <- tokens(two)
        (opTok, bTok) = tk
        blRepo   <- ZIO.service[BlocklistRepo]
        rts      <- blocklistRoutes()
        _        <- blRepo.insertBatch(List((staleHost.value, refreshCat.value)))
        refused  <- statusOf(rts, Method.POST, s"/api/blocklists/${refreshCat.value}/clear", bTok)
        // The catalog is untouched by the refused call — assert the ROWS, not just the status.
        survived <- blRepo.loadCategory(refreshCat)
        allowed  <- statusOf(rts, Method.POST, s"/api/blocklists/${refreshCat.value}/clear", opTok)
        cleared  <- blRepo.loadCategory(refreshCat)
      } yield assertTrue(refused == Status.Forbidden) &&
        assertTrue(survived == Set(staleHost)) &&
        assertTrue(allowed == Status.Ok) &&
        assertTrue(cleared.isEmpty)
    },
    test("POST /api/blocklists/:id/refresh") {
      for {
        _   <- cleanDb
        two <- TestLayers.seedTwoHouseholds(macA, macB)
        tk  <- tokens(two)
        (opTok, bTok) = tk
        blRepo   <- ZIO.service[BlocklistRepo]
        rts      <- blocklistRoutes(Map(refreshCat -> bundledList))
        _        <- blRepo.insertBatch(List((staleHost.value, refreshCat.value)))
        refused  <- statusOf(rts, Method.POST, s"/api/blocklists/${refreshCat.value}/refresh", bTok)
        survived <- blRepo.loadCategory(refreshCat)
        allowed <- statusOf(rts, Method.POST, s"/api/blocklists/${refreshCat.value}/refresh", opTok)
        reseeded <- blRepo.loadCategory(refreshCat)
      } yield assertTrue(refused == Status.Forbidden) &&
        // Still the stale host: the re-seed (which clears the category first) never ran.
        assertTrue(survived == Set(staleHost)) &&
        assertTrue(allowed == Status.Ok) &&
        assertTrue(reseeded == Set(freshHost))
    },
    test("DELETE /api/apps/:id") {
      for {
        _   <- cleanDb
        two <- TestLayers.seedTwoHouseholds(macA, macB)
        tk  <- tokens(two)
        (opTok, bTok) = tk
        appRepo <- ZIO.service[AppRepo]
        rts     <- appRoutes()
        aid     <- appRepo.create("Doomed App", "doomed-app", None, None)
        refused <- statusOf(rts, Method.DELETE, s"/api/apps/${aid.value}", bTok)
        still   <- appRepo.findById(aid)
        allowed <- statusOf(rts, Method.DELETE, s"/api/apps/${aid.value}", opTok)
        gone    <- appRepo.findById(aid)
      } yield assertTrue(refused == Status.Forbidden) &&
        assertTrue(still.isDefined) &&
        assertTrue(allowed == Status.Ok) &&
        assertTrue(gone.isEmpty)
    },
    test("POST /api/apps/seed-from-templates") {
      for {
        _   <- cleanDb
        two <- TestLayers.seedTwoHouseholds(macA, macB)
        tk  <- tokens(two)
        (opTok, bTok) = tk
        appRepo <- ZIO.service[AppRepo]
        rts     <- appRoutes(Map(youtubeSlug -> youtubeTemplate))
        before  <- appRepo.listAll
        refused <- statusOf(rts, Method.POST, "/api/apps/seed-from-templates", bTok)
        after   <- appRepo.listAll
        allowed <- statusOf(rts, Method.POST, "/api/apps/seed-from-templates", opTok)
        seeded  <- appRepo.findByTemplateId(youtubeSlug)
      } yield assertTrue(refused == Status.Forbidden) &&
        // The `apps` catalog is byte-identical across the refused call.
        assertTrue(after.map(_.slug) == before.map(_.slug)) &&
        assertTrue(allowed == Status.Ok) &&
        assertTrue(seeded.isDefined)
    },
    test("POST /api/apps/:id/reset-to-template") {
      val diverged = List(Hostname.unsafe("household-a-only.example.com"))
      for {
        _   <- cleanDb
        two <- TestLayers.seedTwoHouseholds(macA, macB)
        tk  <- tokens(two)
        (opTok, bTok) = tk
        appRepo <- ZIO.service[AppRepo]
        rts     <- appRoutes(Map(youtubeSlug -> youtubeTemplate))
        aid     <- appRepo.create("YouTube", "youtube", Some(youtubeSlug), None)
        _       <- appRepo.setHosts(aid, diverged)
        refused <- statusOf(rts, Method.POST, s"/api/apps/${aid.value}/reset-to-template", bTok)
        // The host-set every household's enforcement reads is untouched.
        kept    <- appRepo.getHosts(aid)
        allowed <- statusOf(rts, Method.POST, s"/api/apps/${aid.value}/reset-to-template", opTok)
        reset   <- appRepo.getHosts(aid)
      } yield assertTrue(refused == Status.Forbidden) &&
        assertTrue(kept == diverged) &&
        assertTrue(allowed == Status.Ok) &&
        assertTrue(reset.toSet == youtubeTemplate.hosts.toSet)
    },
    test("POST /api/admin/apps/reconcile-templates") {
      // The worst of the six: `mergeAppInto` DELETEs an app row and repoints every household's
      // `app_policy_assignments` / rollup rows onto the survivor. Seed the messy pair the
      // reconciler collapses (canonical `youtube` + seeded `youtube-template`) and assert a
      // household-B admin cannot trigger the collapse.
      for {
        _   <- cleanDb
        two <- TestLayers.seedTwoHouseholds(macA, macB)
        tk  <- tokens(two)
        (opTok, bTok) = tk
        appRepo   <- ZIO.service[AppRepo]
        rts       <- appRoutes(Map(youtubeSlug -> youtubeTemplate))
        canonical <- appRepo.create("My YouTube", "youtube", None, Some("📺"))
        _         <- appRepo.setHosts(canonical, List(Hostname.unsafe("youtube.com")))
        seeded    <- appRepo.create("YouTube", "youtube-template", Some(youtubeSlug), None)
        _         <- appRepo.setHosts(seeded, List(Hostname.unsafe("ytimg.com")))
        refused   <- statusOf(rts, Method.POST, "/api/admin/apps/reconcile-templates", bTok)
        // Both rows survive: no merge, no cascade across households.
        stillTwo  <- appRepo.listAll
        allowed   <- statusOf(rts, Method.POST, "/api/admin/apps/reconcile-templates", opTok)
        merged    <- appRepo.listAll
      } yield assertTrue(refused == Status.Forbidden) &&
        assertTrue(stillTwo.map(_.id).toSet.contains(canonical)) &&
        assertTrue(stillTwo.map(_.id).toSet.contains(seeded)) &&
        assertTrue(allowed == Status.Ok) &&
        // The `-template` row is gone; exactly one `youtube` row remains.
        assertTrue(merged.count(_.slug.startsWith("youtube")) == 1)
    },
  )

  // ── the two reads: verdict is KEEP requireWriter ─────────────────────────

  private val reads = suite("the blocklist READ routes stay household-user surfaces")(
    test("GET /api/blocklists and .../hosts serve the shared catalog to any household's writer") {
      // #2535's explicit verdict: bundled public data + live SPA surfaces → `requireWriter` stays.
      // Both households see the SAME catalog, which is the correct behaviour for a single shared
      // install-wide list, not a leak.
      for {
        _   <- cleanDb
        two <- TestLayers.seedTwoHouseholds(macA, macB)
        tk  <- tokens(two)
        (opTok, bTok) = tk
        blRepo            <- ZIO.service[BlocklistRepo]
        rts               <- blocklistRoutes()
        _                 <- blRepo.insertBatch(List((staleHost.value, refreshCat.value)))
        _                 <- blRepo.upsertMeta(
          refreshCat,
          "Ads",
          None,
          bundled = true,
          None,
          java.time.Instant.parse("2026-01-01T00:00:00Z"),
        )
        (sListOp, listOp) <- bodyOf(rts, "/api/blocklists", opTok)
        (sListB, listB)   <- bodyOf(rts, "/api/blocklists", bTok)
        (sHostsOp, hOp)   <- bodyOf(rts, s"/api/blocklists/${refreshCat.value}/hosts", opTok)
        (sHostsB, hB)     <- bodyOf(rts, s"/api/blocklists/${refreshCat.value}/hosts", bTok)
      } yield assertTrue(sListOp == Status.Ok, sListB == Status.Ok) &&
        assertTrue(sHostsOp == Status.Ok, sHostsB == Status.Ok) &&
        assertTrue(listOp == listB, hOp == hB) &&
        assertTrue(hB.contains(staleHost.value))
    },
  )

  def spec = suite("#2535/#2567 — install-wide catalog gates")(mutators, reads) @@
    TestAspect.sequential
}
