package wifihaven.api.feature

import wifihaven.api.{AppTemplate, AppTemplates, JwtConfig}
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

/**
 * #768: tests for the starter library YAML templates, the idempotent startup seeder, and the
 * reset-to-template endpoint.
 */
object AppTemplatesSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg = JwtConfig(secret = "test-secret-at-least-32-chars!!", expiryHours = 1)

  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)

  private def cleanDb = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
    TestDatabase.cleanAndMigrate.provide(ZLayer.succeed(pg)),
  )

  private def url(p: String) = URL.decode(p).toOption.get

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
      auth        <- makeAuth
    } yield AppRoutes.routes(auth, appRepo, profileRepo, upRepo, templates)

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
        )
        val slugs    = templates.map(_.slug.value).toSet
        assertTrue(slugs == expected) &&
        assertTrue(templates.forall(_.hosts.nonEmpty)) &&
        assertTrue(templates.forall(_.name.nonEmpty)) &&
        assertTrue(templates.map(_.slug).distinct.size == templates.size)
      }
    },
    test("templates carry icon_type, seeded apps round-trip it through the DB") {
      for {
        _         <- cleanDb
        appRepo   <- ZIO.service[AppRepo]
        templates <- AppTemplates.loadAll()
        _         <- AppTemplates.seed(appRepo, templates)
        yt        <- appRepo
          .findByTemplateId(AppTemplateId.unsafe("youtube"))
          .someOrFailException
      } yield assertTrue(templates.forall(_.iconType == IconType.Emoji)) &&
        assertTrue(yt.iconType == IconType.Emoji)
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
    test("operator host edits survive subsequent seeds") {
      for {
        _         <- cleanDb
        appRepo   <- ZIO.service[AppRepo]
        templates <- AppTemplates.loadAll()
        _         <- AppTemplates.seed(appRepo, templates)
        yt        <- appRepo
          .findByTemplateId(AppTemplateId.unsafe("youtube"))
          .someOrFailException
        customHosts = List(Hostname.unsafe("operator-only.example.com"))
        _     <- appRepo.setHosts(yt.id, customHosts)
        _     <- AppTemplates.seed(appRepo, templates)
        after <- appRepo.getHosts(yt.id)
      } yield assertTrue(after == customHosts)
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
    test("POST /reset-to-template rejects non-admin (writer)") {
      for {
        _         <- cleanDb
        userRepo  <- ZIO.service[UserRepo]
        upRepo    <- ZIO.service[UserProfileRepo]
        auth      <- makeAuth
        _         <- createUser(userRepo, upRepo, auth, "mom", "adult")
        token     <- auth.login("mom", "pass").map(_.token.value)
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
