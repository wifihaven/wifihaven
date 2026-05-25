package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.test.*

// #999: PATCH /api/apps/:id — partial update of mutable App fields (name,
// icon, iconType, templateId, hosts). slug is immutable post-create.
object AppPatchApiSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

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

  private def setupApp(
      name: String = "Original",
      icon: Option[String] = Some("📺"),
      templateId: Option[AppTemplateId] = Some(AppTemplateId.unsafe("youtube")),
  ) =
    for {
      _       <- cleanDb
      appRepo <- ZIO.service[AppRepo]
      id      <- appRepo.create(name, "original", templateId, icon)
      _       <- appRepo.setHosts(id, List(Hostname.unsafe("youtube.com")))
    } yield id

  private def routesAndToken =
    for {
      auth        <- makeAuth
      appRepo     <- ZIO.service[AppRepo]
      profileRepo <- ZIO.service[ProfileRepo]
      upRepo      <- ZIO.service[UserProfileRepo]
      tk          <- auth.login("admin", "changeme").map(_.token.value)
    } yield (AppRoutes.routes(auth, appRepo, profileRepo, upRepo), tk)

  private def patch(routes: Routes[Any, Response], token: String, id: AppId, body: String) =
    routes.runZIO(
      Request
        .patch(url(s"/api/apps/${id.value}"), Body.fromString(body))
        .addHeader(Header.Authorization.Bearer(token))
        .addHeader(Header.ContentType(MediaType.application.json)),
    )

  def spec = suite("PATCH /api/apps/:id (#999)")(
    test("single-field patch updates name only") {
      for {
        id           <- setupApp()
        (routes, tk) <- routesAndToken
        resp         <- patch(routes, tk, id, """{"name":"Renamed"}""")
        appRepo      <- ZIO.service[AppRepo]
        after        <- appRepo.findById(id)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(after.exists(_.name == "Renamed")) &&
        assertTrue(after.exists(_.icon.contains("📺"))) &&
        assertTrue(after.exists(_.templateId.contains(AppTemplateId.unsafe("youtube"))))
    },
    test("multi-field patch updates name + iconType together") {
      for {
        id           <- setupApp()
        (routes, tk) <- routesAndToken
        resp         <- patch(routes, tk, id, """{"name":"X","iconType":"url"}""")
        appRepo      <- ZIO.service[AppRepo]
        after        <- appRepo.findById(id)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(after.exists(_.name == "X")) &&
        assertTrue(after.exists(_.iconType == IconType.Url))
    },
    test("hosts replaces the host set with canonicalized values") {
      for {
        id           <- setupApp()
        (routes, tk) <- routesAndToken
        resp         <- patch(routes, tk, id, """{"hosts":["*.youtube.com","ytimg.com"]}""")
        appRepo      <- ZIO.service[AppRepo]
        hosts        <- appRepo.getHosts(id)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(hosts.toSet == Set(Hostname.unsafe("youtube.com"), Hostname.unsafe("ytimg.com")))
    },
    test("hosts=[] empties the host set") {
      for {
        id           <- setupApp()
        (routes, tk) <- routesAndToken
        resp         <- patch(routes, tk, id, """{"hosts":[]}""")
        appRepo      <- ZIO.service[AppRepo]
        hosts        <- appRepo.getHosts(id)
      } yield assertTrue(resp.status == Status.Ok) && assertTrue(hosts.isEmpty)
    },
    test("absent fields preserve existing values + hosts") {
      for {
        id           <- setupApp()
        (routes, tk) <- routesAndToken
        resp         <- patch(routes, tk, id, "{}")
        appRepo      <- ZIO.service[AppRepo]
        after        <- appRepo.findById(id)
        hosts        <- appRepo.getHosts(id)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(after.exists(_.name == "Original")) &&
        assertTrue(after.exists(_.icon.contains("📺"))) &&
        assertTrue(after.exists(_.templateId.contains(AppTemplateId.unsafe("youtube")))) &&
        assertTrue(hosts == List(Hostname.unsafe("youtube.com")))
    },
    test("null on icon clears the icon (nullable)") {
      for {
        id           <- setupApp()
        (routes, tk) <- routesAndToken
        resp         <- patch(routes, tk, id, """{"icon":null}""")
        appRepo      <- ZIO.service[AppRepo]
        after        <- appRepo.findById(id)
      } yield assertTrue(resp.status == Status.Ok) && assertTrue(after.exists(_.icon.isEmpty))
    },
    test("null on templateId clears it (nullable)") {
      for {
        id           <- setupApp()
        (routes, tk) <- routesAndToken
        resp         <- patch(routes, tk, id, """{"templateId":null}""")
        appRepo      <- ZIO.service[AppRepo]
        after        <- appRepo.findById(id)
      } yield assertTrue(resp.status == Status.Ok) && assertTrue(after.exists(_.templateId.isEmpty))
    },
    test("null on non-nullable name returns 400") {
      for {
        id           <- setupApp()
        (routes, tk) <- routesAndToken
        resp         <- patch(routes, tk, id, """{"name":null}""")
      } yield assertTrue(resp.status == Status.BadRequest)
    },
    test("404 when app is missing") {
      for {
        _            <- cleanDb
        (routes, tk) <- routesAndToken
        resp         <- patch(routes, tk, AppId(999999L), """{"name":"X"}""")
      } yield assertTrue(resp.status == Status.NotFound)
    },
    test("403 when non-admin (adult) tries to PATCH") {
      for {
        id          <- setupApp()
        userRepo    <- ZIO.service[UserRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        appRepo     <- ZIO.service[AppRepo]
        profileRepo <- ZIO.service[ProfileRepo]
        auth        <- makeAuth
        hash        <- auth.hashPassword("pass")
        uid         <- userRepo.create("mom", hash, "adult")
        _           <- userRepo.clearMustChangePassword(uid)
        token       <- auth.login("mom", "pass").map(_.token.value)
        routes      = AppRoutes.routes(auth, appRepo, profileRepo, upRepo)
        resp        <- patch(routes, token, id, """{"name":"X"}""")
      } yield assertTrue(resp.status == Status.Forbidden)
    },
  ) @@ TestAspect.sequential
}
