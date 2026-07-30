package wifihaven.api.feature

import wifihaven.api.{AppTemplate, AppTemplateSeedSummary, AppTemplates, JwtConfig}
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.IconType
import wifihaven.shared.types.*
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*

import scala.jdk.CollectionConverters.*

/**
 * #768: tests for the starter library YAML templates, the idempotent startup seeder, and the
 * reset-to-template endpoint.
 */
object AppTemplatesSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)

  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private def url(p: String) = URL.decode(p).toOption.get

  /** Build a raw YAML-shaped root map for exercising [[AppTemplates.parseTemplate]] directly. */
  private def rootMap(
      slug: String,
      hosts: List[String],
      sharedHosts: Option[List[String]],
  ): java.util.Map[String, AnyRef] = {
    val m = new java.util.HashMap[String, AnyRef]()
    m.put("slug", slug)
    m.put("name", slug)
    m.put("hosts", hosts.asJava)
    sharedHosts.foreach(s => m.put("shared_hosts", s.asJava))
    m
  }

  private def tmpl(slug: String, hosts: List[String], sharedHosts: List[String]): AppTemplate =
    AppTemplate(
      AppTemplateId.unsafe(slug),
      slug,
      None,
      IconType.Emoji,
      hosts.map(Hostname.unsafe),
      sharedHosts.map(Hostname.unsafe),
    )

  private def adminToken =
    for {
      auth  <- makeAuth
      token <- auth.login("admin", "changeme").map(_.token.value)
    } yield token

  private def makeRoutes(templates: Map[AppTemplateId, AppTemplate]) =
    for {
      appRepo     <- ZIO.service[AppRepo]
      profileRepo <- ZIO.service[ProfileRepo]
      upRepo      <- ZIO.service[UserProfileRepo]
      blRepo      <- ZIO.service[BlocklistRepo]
      auth        <- makeAuth
    } yield AppRoutes.routes(auth, appRepo, profileRepo, upRepo, blRepo, templates)

  private def createUser(
      userRepo: UserRepo,
      upRepo: UserProfileRepo,
      auth: AuthService,
      username: String,
      role: String,
  ): Task[UserId] =
    for {
      hash <- auth.hashPassword("pass")
      id   <- userRepo.create(username, hash, role)
      _    <- userRepo.clearMustChangePassword(id)
      _    <- upRepo.setProfilesForUser(id, Nil)
    } yield id

  def spec = suite("AppTemplates")(
    test("_index.yml is in sync with the .yml files in app_templates/") {
      // Cross-check the manifest against the actual files on disk so a new
      // template added without updating _index.yml fails loudly.
      for {
        templates <- AppTemplates.loadAll()
        manifestSlugs = templates.map(_.slug.value).toSet
        dirSlugs <- ZIO.attemptBlocking {
          val url = getClass.getResource("/app_templates")
          val dir = new java.io.File(url.toURI)
          dir
            .listFiles()
            .toList
            .filter(f => f.isFile && f.getName.endsWith(".yml") && !f.getName.startsWith("_"))
            .map(_.getName.stripSuffix(".yml"))
            .toSet
        }
      } yield assertTrue(manifestSlugs == dirSlugs)
    },
    test("manifest + all starter templates parse and have unique slugs") {
      for {
        templates <- AppTemplates.loadAll()
      } yield {
        val expected = Set(
          "youtube",
          "tiktok",
          "roblox",
          "discord",
          "minecraft",
          "netflix",
          "instagram",
          "snapchat",
          "whatsapp",
          "twitch",
          "gimkit",
          "khan-academy",
          "math-academy",
          "lexia",
          "imessage",
          // #1705: kid-traffic-driven additions
          "tinkercad",
          "duolingo",
          "brave",
          "plex",
          "crazygames",
          "poki",
          "thingiverse",
          // #1705: ported in from operator-created prod apps
          "1password",
          "eaglercraft",
          "giphy",
          "google-play",
          "moc-pilot",
          "wifihaven",
          "x",
          // #1815: traffic-driven catalog pass
          "lego",
          // #1889: Feeling Great CBT app
          "feeling-great",
          // #1922: traffic-driven catalog pass
          "math-playground",
          "dance-mat-typing",
          // operator request: A-Z Animals reference site
          "a-z-animals",
          // operator request: Prodigy kids' educational math game
          "prodigy",
          // #2331: traffic-driven catalog pass
          "serato",
        )
        val slugs    = templates.map(_.slug.value).toSet
        assertTrue(slugs == expected) &&
        assertTrue(templates.forall(_.hosts.nonEmpty)) &&
        assertTrue(templates.forall(_.name.nonEmpty)) &&
        assertTrue(templates.map(_.slug).distinct.size == templates.size)
      }
    },
    test("#1896 shared_hosts defaults to empty and parses parallel to hosts") {
      val base       = AppTemplates.parseTemplate(
        rootMap("youtube", List("youtube.com"), None),
        "/app_templates/youtube.yml",
      )
      val withShared = AppTemplates.parseTemplate(
        rootMap("youtube", List("youtube.com"), Some(List("elevenlabs.io", "launchdarkly.com"))),
        "/app_templates/youtube.yml",
      )
      assertTrue(base.map(_.sharedHosts) == Right(Nil)) &&
      assertTrue(
        withShared.map(_.sharedHosts) ==
          Right(List(Hostname.unsafe("elevenlabs.io"), Hostname.unsafe("launchdarkly.com"))),
      )
    },
    test("#1896 parseTemplate rejects a host listed in both hosts and shared_hosts") {
      val r = AppTemplates.parseTemplate(
        rootMap("youtube", List("youtube.com"), Some(List("youtube.com"))),
        "/app_templates/youtube.yml",
      )
      assertTrue(r.isLeft)
    },
    test("#1896 catalog satisfies the shared-host invariants") {
      for {
        templates <- AppTemplates.loadAll()
      } yield assertTrue(AppTemplates.sharedHostViolations(templates).isEmpty)
    },
    test("#1896 sharedHostViolations flags an all-shared template (no distinctive host)") {
      assertTrue(
        AppTemplates.sharedHostViolations(List(tmpl("x", Nil, List("elevenlabs.io")))).nonEmpty,
      )
    },
    test("#1896 sharedHostViolations flags a host distinctive on one app but shared on another") {
      val a = tmpl("a", List("elevenlabs.io"), Nil)
      val b = tmpl("b", List("b.com"), List("elevenlabs.io"))
      assertTrue(AppTemplates.sharedHostViolations(List(a, b)).nonEmpty)
    },
    test("#1896 sharedHostViolations passes a globally consistent catalog") {
      val a = tmpl("a", List("a.com"), List("elevenlabs.io"))
      val b = tmpl("b", List("b.com"), List("elevenlabs.io"))
      assertTrue(AppTemplates.sharedHostViolations(List(a, b)).isEmpty)
    },
    test("#1966 Feeling Great + Math Academy wire the known shared backends") {
      // Pins the #1888 rollout: the four shared backends are listed under
      // `shared_hosts:` (not distinctive `hosts:`) so S2 excludes them from
      // engaged-minutes, S3 routes them through co-presence, and S4 keeps them
      // out of every drop set while carving them when the app is Allowed.
      for {
        templates <- AppTemplates.loadAll()
        bySlug = templates.map(t => t.slug.value -> t).toMap
        fg     = bySlug("feeling-great")
        ma     = bySlug("math-academy")
      } yield assertTrue(
        // distinctive host-sets stay branded / app-specific
        fg.hosts == List(Hostname.unsafe("app.feelinggreat.com")),
        ma.hosts == List(Hostname.unsafe("mathacademy.com")),
        // shared backends are tagged shared, never distinctive
        fg.sharedHosts.toSet ==
          Set(Hostname.unsafe("elevenlabs.io"), Hostname.unsafe("launchdarkly.com")),
        ma.sharedHosts.toSet ==
          Set(Hostname.unsafe("d3js.org"), Hostname.unsafe("jsdelivr.net")),
        // the whole catalog still satisfies the global shared/distinctive
        // consistency invariant after wiring these in
        AppTemplates.sharedHostViolations(templates).isEmpty,
      )
    },
    test("#1896 seeder persists the shared flag per host") {
      for {
        _       <- cleanDb
        appRepo <- ZIO.service[AppRepo]
        t = tmpl("youtube", List("youtube.com"), List("elevenlabs.io"))
        _       <- AppTemplates.seed(appRepo, List(t))
        app     <- appRepo.findByTemplateId(t.slug)
        entries <- appRepo.getHostEntries(app.get.id)
      } yield assertTrue(
        entries.toSet == Set(
          AppHostEntry(Hostname.unsafe("youtube.com"), false),
          AppHostEntry(Hostname.unsafe("elevenlabs.io"), true),
        ),
      )
    },
    test("every starter template has icon_type=url with a non-empty URL (#1041)") {
      for {
        templates <- AppTemplates.loadAll()
      } yield assertTrue(templates.forall(_.iconType == IconType.Url)) &&
        assertTrue(templates.forall(_.icon.exists(_.startsWith("http")))) &&
        assertTrue(templates.forall(_.icon.exists(_.nonEmpty)))
    },
    test("#1705 new templates use favicon URLs, never emoji") {
      // The 7 templates added by #1705 must all be icon_type=url with a
      // ddg ip3 favicon — the operator explicitly asked for favicons on new
      // templates so the new-app rollout looks consistent in the SPA.
      val newSlugs = Set(
        "tinkercad",
        "duolingo",
        "brave",
        "plex",
        "crazygames",
        "poki",
        "thingiverse",
        "1password",
        "eaglercraft",
        "giphy",
        "google-play",
        "moc-pilot",
        "wifihaven",
        "x",
      )
      for {
        templates <- AppTemplates.loadAll()
      } yield {
        val newTemplates = templates.filter(t => newSlugs.contains(t.slug.value))
        assertTrue(newTemplates.size == newSlugs.size) &&
        assertTrue(newTemplates.forall(_.iconType == IconType.Url)) &&
        assertTrue(newTemplates.forall(_.icon.exists(_.startsWith("https://"))))
      }
    },
    test("seeded apps round-trip icon_type=url through the DB (#1041)") {
      for {
        _         <- cleanDb
        appRepo   <- ZIO.service[AppRepo]
        templates <- AppTemplates.loadAll()
        _         <- AppTemplates.seed(appRepo, templates)
        yt        <- appRepo
          .findByTemplateId(AppTemplateId.unsafe("youtube"))
          .someOrFailException
      } yield assertTrue(yt.iconType == IconType.Url) &&
        assertTrue(yt.icon.exists(_.startsWith("http")))
    },
    test("backfill seeds favicon icons when no apps exist for the slug (#1041)") {
      for {
        _         <- cleanDb
        appRepo   <- ZIO.service[AppRepo]
        templates <- AppTemplates.loadAll()
        _         <- AppTemplates.seed(appRepo, templates)
        seeded    <- appRepo.listAll
      } yield assertTrue(seeded.nonEmpty) &&
        assertTrue(seeded.forall(_.iconType == IconType.Url)) &&
        assertTrue(seeded.forall(_.icon.exists(_.startsWith("http"))))
    },
    test("backfill preserves operator's existing emoji icon (#1041)") {
      for {
        _         <- cleanDb
        appRepo   <- ZIO.service[AppRepo]
        templates <- AppTemplates.loadAll()
        // Operator has already created an app for the youtube slug with an emoji icon.
        ytTmpl = templates.find(_.slug == AppTemplateId.unsafe("youtube")).get
        existingId <- appRepo.create(
          "Operator YouTube",
          "operator-youtube",
          Some(ytTmpl.slug),
          Some("📺"),
          IconType.Emoji,
        )
        _          <- appRepo.setHosts(existingId, List(Hostname.unsafe("youtube.com")))
        _          <- AppTemplates.seed(appRepo, templates)
        after      <- appRepo.findById(existingId).someOrFailException
      } yield assertTrue(after.iconType == IconType.Emoji) &&
        assertTrue(after.icon.contains("📺"))
    },
    test("gimkit template covers both marketing site and the school game endpoint (#1005)") {
      for {
        templates <- AppTemplates.loadAll()
      } yield {
        val gimkit = templates.find(_.slug == AppTemplateId.unsafe("gimkit")).get
        val hosts  = gimkit.hosts.map(_.value).toSet
        assertTrue(hosts.contains("gimkit.com")) &&
        assertTrue(hosts.contains("gimkitconnect.com"))
      }
    },
    test("imessage template targets the iMessage edge and excludes shared APNs hosts (#1529)") {
      // iMessage rides shared Apple infra, so a clean block isn't possible via
      // host-set alone (APNs multiplexing + 17.0.0.0/8 IP traffic). The default
      // set is the iMessage-specific service edge `ess.apple.com` (suffix-matched
      // by dnsmasq's nftset=, covering the observed query/identity/kt-prod
      // subdomains). It must NOT contain push.apple.com / courier hosts —
      // blocking those breaks ALL Apple push notifications system-wide.
      for {
        templates <- AppTemplates.loadAll()
      } yield {
        val imessage = templates.find(_.slug == AppTemplateId.unsafe("imessage")).get
        val hosts    = imessage.hosts.map(_.value).toSet
        assertTrue(imessage.name == "iMessage") &&
        assertTrue(hosts.contains("ess.apple.com")) &&
        assertTrue(hosts.forall(h => !h.contains("push.apple.com"))) &&
        assertTrue(hosts.forall(h => !h.contains("courier")))
      }
    },
    test("youtube template keeps googlevideo but excludes the shared-pool youtubei host (#1636)") {
      // youtubei.googleapis.com rides Google's shared *.googleapis.com GFE
      // anycast pool — the same front-end IPs that serve OAuth / Drive / Docs.
      // Because the block enforces at the IP layer, including it collateral-drops
      // Google sign-in / Drive on the device (#1636). googlevideo.com is the
      // dedicated video-bytes pool, so keeping it preserves the real block.
      for {
        templates <- AppTemplates.loadAll()
      } yield {
        val youtube = templates.find(_.slug == AppTemplateId.unsafe("youtube")).get
        val hosts   = youtube.hosts.map(_.value).toSet
        assertTrue(youtube.name == "YouTube") &&
        assertTrue(hosts.contains("googlevideo.com")) &&
        assertTrue(hosts.contains("youtube.com")) &&
        assertTrue(hosts.forall(h => !h.contains("googleapis.com")))
      }
    },
    test(
      "#1705 new templates attribute real prod-observed subdomains and exclude shared platforms",
    ) {
      // Phase-3 host-set evidence from `evidence/classification.md`. Each tuple
      // is (slug, real-subdomain-seen-in-kid-traffic, shared-platform-apex-the-set-must-NOT-match).
      // The new templates were authored from 14d of prod traffic on the kid
      // MACs (see `scripts/analysis/fetch_prod_data.sh` for the device list);
      // pinning these here guards against an author accidentally pulling in a
      // shared-platform apex (#1661) or shrinking the set so real attribution
      // breaks.
      val cases = List(
        ("tinkercad", "editor.tinkercad.com", "googleapis.com"),
        ("duolingo", "simg-ssl.duolingo.com", "fastly.net"),
        ("brave", "redirector.brave.com", "cloudfront.net"),
        ("plex", "pubsub.plex.tv", "fastly.net"),
        ("crazygames", "cza.crazygames.com", "cloudflare.com"),
        ("poki", "t.poki.io", "akamaiedge.net"),
        ("thingiverse", "img.thingiverse.com", "amazonaws.com"),
      )
      for {
        templates <- AppTemplates.loadAll()
      } yield {
        val bySlug  = templates.map(t => t.slug.value -> t).toMap
        val results = cases.map { case (slug, sub, sharedApex) =>
          val t             = bySlug.getOrElse(slug, sys.error(s"missing template: $slug"))
          val hosts         = t.hosts.map(_.value)
          val attributes    =
            hosts.exists(h => wifihaven.shared.types.HostMatch.matchesApex(sub, h))
          val pullsInShared =
            hosts.exists(h =>
              h == sharedApex || wifihaven.shared.types.HostMatch.matchesApex(h, sharedApex),
            )
          (slug, sub, sharedApex, attributes, pullsInShared)
        }
        assertTrue(results.forall { case (_, _, _, attributes, _) => attributes }) &&
        assertTrue(results.forall { case (_, _, _, _, pullsInShared) => !pullsInShared })
      }
    },
    test("#1705 plex template covers both apex domains observed in prod") {
      // plex.tv carries control/auth traffic, plex.direct carries the
      // direct-stream media bytes. Both apexes had real kid traffic on the
      // 14d evidence window (see evidence/classification.md).
      for {
        templates <- AppTemplates.loadAll()
      } yield {
        val plex  = templates.find(_.slug == AppTemplateId.unsafe("plex")).get
        val hosts = plex.hosts.map(_.value).toSet
        assertTrue(hosts.contains("plex.tv")) &&
        assertTrue(hosts.contains("plex.direct"))
      }
    },
    test("each template's hosts parse as apex hostnames") {
      for {
        templates <- AppTemplates.loadAll()
      } yield assertTrue(
        templates.forall(_.hosts.forall(h => Hostname.parse(h.value).isRight)),
      )
    },
    test("seeder is idempotent: second run does not create duplicates") {
      for {
        _         <- cleanDb
        appRepo   <- ZIO.service[AppRepo]
        templates <- AppTemplates.loadAll()
        _         <- AppTemplates.seed(appRepo, templates)
        firstAll  <- appRepo.listAll
        _         <- AppTemplates.seed(appRepo, templates)
        secondAll <- appRepo.listAll
      } yield assertTrue(firstAll.size == templates.size) &&
        assertTrue(secondAll.size == templates.size) &&
        assertTrue(firstAll.map(_.id).toSet == secondAll.map(_.id).toSet)
    },
    test("seeder populates host list on first seed") {
      for {
        _         <- cleanDb
        appRepo   <- ZIO.service[AppRepo]
        templates <- AppTemplates.loadAll()
        _         <- AppTemplates.seed(appRepo, templates)
        yt        <- appRepo.findByTemplateId(AppTemplateId.unsafe("youtube"))
        ytHosts   <- ZIO
          .fromOption(yt)
          .orElseFail(new RuntimeException("youtube not seeded"))
          .flatMap(a => appRepo.getHosts(a.id))
      } yield assertTrue(ytHosts.nonEmpty) &&
        assertTrue(ytHosts.contains(Hostname.unsafe("youtube.com")))
    },
    test(
      "operator-added hosts survive subsequent seeds (template hosts are additively re-added per #1087)",
    ) {
      // Pre-#1087 contract was "operator host list wins outright"; post-#1087 the seeder
      // additively merges missing template hosts back in so drift is recovered. Operator
      // *additions* are still preserved — only operator *deletions* of template hosts are
      // reversed, which is the intended trade-off (drift recovery > removal stickiness).
      for {
        _         <- cleanDb
        appRepo   <- ZIO.service[AppRepo]
        templates <- AppTemplates.loadAll()
        _         <- AppTemplates.seed(appRepo, templates)
        yt        <- appRepo
          .findByTemplateId(AppTemplateId.unsafe("youtube"))
          .someOrFailException
        ytTmpl       = templates.find(_.slug == AppTemplateId.unsafe("youtube")).get
        operatorHost = Hostname.unsafe("operator-only.example.com")
        _     <- appRepo.setHosts(yt.id, ytTmpl.hosts :+ operatorHost)
        _     <- AppTemplates.seed(appRepo, templates)
        after <- appRepo.getHosts(yt.id)
      } yield assertTrue(after.contains(operatorHost)) &&
        assertTrue(ytTmpl.hosts.forall(after.contains))
    },
    test("POST /reset-to-template restores template hosts (admin)") {
      for {
        _         <- cleanDb
        token     <- adminToken
        appRepo   <- ZIO.service[AppRepo]
        templates <- AppTemplates.loadAll()
        _         <- AppTemplates.seed(appRepo, templates)
        rs        <- makeRoutes(templates.map(t => t.slug -> t).toMap)
        yt        <- appRepo
          .findByTemplateId(AppTemplateId.unsafe("youtube"))
          .someOrFailException
        ytTmpl = templates.find(_.slug == AppTemplateId.unsafe("youtube")).get
        _     <- appRepo.setHosts(yt.id, List(Hostname.unsafe("custom.example.com")))
        resp  <- rs.runZIO(
          Request
            .post(url(s"/api/apps/${yt.id.value}/reset-to-template"), Body.empty)
            .addHeader(Header.Authorization.Bearer(token)),
        )
        after <- appRepo.getHosts(yt.id)
        body  <- resp.body.asString
        _     <- ZIO.fromEither(body.fromJson[AppDetail])
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(after.toSet == ytTmpl.hosts.toSet)
    },
    test("POST /reset-to-template returns 400 when app has no template_id") {
      for {
        _         <- cleanDb
        token     <- adminToken
        appRepo   <- ZIO.service[AppRepo]
        templates <- AppTemplates.loadAll()
        rs        <- makeRoutes(templates.map(t => t.slug -> t).toMap)
        id        <- appRepo.create("Custom", "custom", None, None)
        resp      <- rs.runZIO(
          Request
            .post(url(s"/api/apps/${id.value}/reset-to-template"), Body.empty)
            .addHeader(Header.Authorization.Bearer(token)),
        )
      } yield assertTrue(resp.status == Status.BadRequest)
    },
    test("POST /reset-to-template returns 404 when template_id is unknown") {
      for {
        _         <- cleanDb
        token     <- adminToken
        appRepo   <- ZIO.service[AppRepo]
        templates <- AppTemplates.loadAll()
        rs        <- makeRoutes(templates.map(t => t.slug -> t).toMap)
        id   <- appRepo.create("Ghost", "ghost", Some(AppTemplateId.unsafe("not-a-template")), None)
        resp <- rs.runZIO(
          Request
            .post(url(s"/api/apps/${id.value}/reset-to-template"), Body.empty)
            .addHeader(Header.Authorization.Bearer(token)),
        )
      } yield assertTrue(resp.status == Status.NotFound)
    },
    test(
      "seed additively merges hosts that were added to the template after initial seed (#1087)",
    ) {
      // Simulates the Gimkit drift: row was seeded back when the template had only one host;
      // template later grew a second host (gimkitconnect.com). Subsequent seeds must add the
      // missing host without disturbing what was already there.
      for {
        _       <- cleanDb
        appRepo <- ZIO.service[AppRepo]
        gimkitSlug = AppTemplateId.unsafe("gimkit")
        seedOnly   = AppTemplate(
          gimkitSlug,
          "Gimkit",
          None,
          IconType.Emoji,
          List(Hostname.unsafe("gimkit.com")),
        )
        full       = AppTemplate(
          gimkitSlug,
          "Gimkit",
          None,
          IconType.Emoji,
          List(Hostname.unsafe("gimkit.com"), Hostname.unsafe("gimkitconnect.com")),
        )
        _       <- AppTemplates.seed(appRepo, List(seedOnly))
        summary <- AppTemplates.seed(appRepo, List(full))
        gimkit  <- appRepo.findByTemplateId(gimkitSlug).someOrFailException
        after   <- appRepo.getHosts(gimkit.id)
      } yield assertTrue(after.toSet == full.hosts.toSet) &&
        assertTrue(after.contains(Hostname.unsafe("gimkit.com"))) &&
        assertTrue(after.contains(Hostname.unsafe("gimkitconnect.com"))) &&
        assertTrue(summary.created.isEmpty) &&
        assertTrue(summary.preserved.isEmpty) &&
        assertTrue(summary.repopulated.isEmpty) &&
        assertTrue(summary.augmented.map(_.slug) == List("gimkit")) &&
        assertTrue(summary.augmented.head.addedHosts == List("gimkitconnect.com"))
    },
    test(
      "seed augment preserves operator-added hosts and still adds missing template hosts (#1087)",
    ) {
      for {
        _       <- cleanDb
        appRepo <- ZIO.service[AppRepo]
        gimkitSlug = AppTemplateId.unsafe("gimkit")
        seedOnly   = AppTemplate(
          gimkitSlug,
          "Gimkit",
          None,
          IconType.Emoji,
          List(Hostname.unsafe("gimkit.com")),
        )
        full       = AppTemplate(
          gimkitSlug,
          "Gimkit",
          None,
          IconType.Emoji,
          List(Hostname.unsafe("gimkit.com"), Hostname.unsafe("gimkitconnect.com")),
        )
        _      <- AppTemplates.seed(appRepo, List(seedOnly))
        gimkit <- appRepo.findByTemplateId(gimkitSlug).someOrFailException
        operatorHost = Hostname.unsafe("myhouseholdspecific.example")
        _       <- appRepo.setHosts(gimkit.id, List(Hostname.unsafe("gimkit.com"), operatorHost))
        summary <- AppTemplates.seed(appRepo, List(full))
        after   <- appRepo.getHosts(gimkit.id)
      } yield assertTrue(after.contains(operatorHost)) &&
        assertTrue(after.contains(Hostname.unsafe("gimkitconnect.com"))) &&
        assertTrue(after.contains(Hostname.unsafe("gimkit.com"))) &&
        assertTrue(summary.augmented.map(_.slug) == List("gimkit")) &&
        assertTrue(summary.augmented.head.addedHosts == List("gimkitconnect.com"))
    },
    test("seed augment is idempotent: second pass produces preserved, not augmented (#1087)") {
      for {
        _       <- cleanDb
        appRepo <- ZIO.service[AppRepo]
        gimkitSlug = AppTemplateId.unsafe("gimkit")
        seedOnly   = AppTemplate(
          gimkitSlug,
          "Gimkit",
          None,
          IconType.Emoji,
          List(Hostname.unsafe("gimkit.com")),
        )
        full       = AppTemplate(
          gimkitSlug,
          "Gimkit",
          None,
          IconType.Emoji,
          List(Hostname.unsafe("gimkit.com"), Hostname.unsafe("gimkitconnect.com")),
        )
        _      <- AppTemplates.seed(appRepo, List(seedOnly))
        first  <- AppTemplates.seed(appRepo, List(full))
        second <- AppTemplates.seed(appRepo, List(full))
      } yield assertTrue(first.augmented.size == 1) &&
        assertTrue(second.augmented.isEmpty) &&
        assertTrue(second.preserved == List("gimkit"))
    },
    test("seed returns summary: first run created=all; second run preserved=all (#1024)") {
      for {
        _         <- cleanDb
        appRepo   <- ZIO.service[AppRepo]
        templates <- AppTemplates.loadAll()
        first     <- AppTemplates.seed(appRepo, templates)
        second    <- AppTemplates.seed(appRepo, templates)
      } yield assertTrue(first.created.size == templates.size) &&
        assertTrue(first.repopulated.isEmpty) &&
        assertTrue(first.preserved.isEmpty) &&
        assertTrue(second.created.isEmpty) &&
        assertTrue(second.repopulated.isEmpty) &&
        assertTrue(second.preserved.size == templates.size)
    },
    test("seed summary: row exists with empty hosts is repopulated (#1024)") {
      for {
        _         <- cleanDb
        appRepo   <- ZIO.service[AppRepo]
        templates <- AppTemplates.loadAll()
        _         <- AppTemplates.seed(appRepo, templates)
        yt        <- appRepo
          .findByTemplateId(AppTemplateId.unsafe("youtube"))
          .someOrFailException
        _         <- appRepo.setHosts(yt.id, Nil)
        summary   <- AppTemplates.seed(appRepo, templates)
      } yield assertTrue(summary.repopulated == List("youtube")) &&
        assertTrue(summary.preserved.size == templates.size - 1) &&
        assertTrue(summary.created.isEmpty)
    },
    test("POST /api/apps/seed-from-templates (admin) backfills and returns summary (#1024)") {
      for {
        _         <- cleanDb
        token     <- adminToken
        appRepo   <- ZIO.service[AppRepo]
        templates <- AppTemplates.loadAll()
        rs        <- makeRoutes(templates.map(t => t.slug -> t).toMap)
        resp      <- rs.runZIO(
          Request
            .post(url("/api/apps/seed-from-templates"), Body.empty)
            .addHeader(Header.Authorization.Bearer(token)),
        )
        body      <- resp.body.asString
        summary   <- ZIO.fromEither(body.fromJson[AppTemplateSeedSummary])
        after     <- appRepo.listAll
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(summary.created.size == templates.size) &&
        assertTrue(after.size == templates.size)
    },
    test("POST /api/apps/seed-from-templates is idempotent across calls (#1024)") {
      for {
        _         <- cleanDb
        token     <- adminToken
        appRepo   <- ZIO.service[AppRepo]
        templates <- AppTemplates.loadAll()
        rs        <- makeRoutes(templates.map(t => t.slug -> t).toMap)
        req = Request
          .post(url("/api/apps/seed-from-templates"), Body.empty)
          .addHeader(Header.Authorization.Bearer(token))
        _     <- rs.runZIO(req)
        resp2 <- rs.runZIO(req)
        body2 <- resp2.body.asString
        sum2  <- ZIO.fromEither(body2.fromJson[AppTemplateSeedSummary])
        all   <- appRepo.listAll
      } yield assertTrue(resp2.status == Status.Ok) &&
        assertTrue(sum2.created.isEmpty) &&
        assertTrue(sum2.preserved.size == templates.size) &&
        assertTrue(all.size == templates.size)
    },
    // #2522: the seeder moved from `requireAdmin` to `requireWriter` — an adult does the parenting,
    // and re-seeding the starter app set is parenting. A CHILD is still refused, which is what this
    // pin now guards; the adult's positive half lives in AdultEditBoundarySpec.
    test("POST /api/apps/seed-from-templates rejects a child (#1024, #2522)") {
      for {
        _         <- cleanDb
        userRepo  <- ZIO.service[UserRepo]
        upRepo    <- ZIO.service[UserProfileRepo]
        auth      <- makeAuth
        _         <- createUser(userRepo, upRepo, auth, "kiddo", "child")
        token     <- auth.login("kiddo", "pass").map(_.token.value)
        templates <- AppTemplates.loadAll()
        rs        <- makeRoutes(templates.map(t => t.slug -> t).toMap)
        resp      <- rs.runZIO(
          Request
            .post(url("/api/apps/seed-from-templates"), Body.empty)
            .addHeader(Header.Authorization.Bearer(token)),
        )
      } yield assertTrue(resp.status == Status.Forbidden)
    },
    test("packaged jar contains every template YAML named in _index.yml (#1024)") {
      // Tripwire for cause #2 in the issue: if the build ever stops shipping
      // `api/resources/app_templates/*.yml` into the assembly, this fails loudly
      // at test time instead of leaving prod with an empty apps list.
      for {
        templates <- AppTemplates.loadAll()
        missing   <- ZIO.attemptBlocking {
          templates.flatMap { t =>
            Option(getClass.getResourceAsStream(s"/app_templates/${t.slug.value}.yml")) match {
              case Some(in) => in.close(); None
              case None     => Some(t.slug.value)
            }
          }
        }
      } yield assertTrue(missing.isEmpty)
    },
    // #2522: same flip as above — adult-or-admin, so the refusal pin moves to `child`.
    test("POST /reset-to-template rejects a child (#2522)") {
      for {
        _         <- cleanDb
        userRepo  <- ZIO.service[UserRepo]
        upRepo    <- ZIO.service[UserProfileRepo]
        auth      <- makeAuth
        _         <- createUser(userRepo, upRepo, auth, "kiddo", "child")
        token     <- auth.login("kiddo", "pass").map(_.token.value)
        appRepo   <- ZIO.service[AppRepo]
        templates <- AppTemplates.loadAll()
        _         <- AppTemplates.seed(appRepo, templates)
        rs        <- makeRoutes(templates.map(t => t.slug -> t).toMap)
        yt        <- appRepo
          .findByTemplateId(AppTemplateId.unsafe("youtube"))
          .someOrFailException
        resp      <- rs.runZIO(
          Request
            .post(url(s"/api/apps/${yt.id.value}/reset-to-template"), Body.empty)
            .addHeader(Header.Authorization.Bearer(token)),
        )
      } yield assertTrue(resp.status == Status.Forbidden)
    },
  ) @@ TestAspect.sequential
}
