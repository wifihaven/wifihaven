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
import zio.json.*
import zio.test.*

/**
 * Feature tests for the apps CRUD HTTP endpoints (#762). Each test boots a fresh embedded Postgres
 * via [[TestDatabase]] and exercises the route stack end-to-end (no mocks).
 */
object AppApiSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg = JwtConfig(secret = "test-secret-at-least-32-chars!!", expiryHours = 1)

  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private def makeRoutes =
    for {
      appRepo     <- ZIO.service[AppRepo]
      profileRepo <- ZIO.service[ProfileRepo]
      upRepo      <- ZIO.service[UserProfileRepo]
      auth        <- makeAuth
    } yield AppRoutes.routes(auth, appRepo, profileRepo, upRepo)

  private def adminToken =
    for {
      auth  <- makeAuth
      token <- auth.login("admin", "changeme").map(_.token.value)
    } yield token

  private def createUser(
      userRepo: UserRepo,
      upRepo: UserProfileRepo,
      auth: AuthService,
      username: String,
      role: String,
      profileIds: List[ProfileId],
  ): Task[UserId] =
    for {
      hash <- auth.hashPassword("pass")
      id   <- userRepo.create(username, hash, role)
      _    <- userRepo.clearMustChangePassword(id)
      _    <- upRepo.setProfilesForUser(id, profileIds)
    } yield id

  private def url(p: String) = URL.decode(p).toOption.get

  def spec = suite("Apps API")(
    // #1798: app *definition* mutators were retired — definitions are authored
    // only via the built-in `AppTemplates`. These routes no longer exist, so
    // they fall through to the unrouted 404.
    test("POST /api/apps is gone (definition create removed)") {
      for {
        _     <- cleanDb
        token <- adminToken
        rs    <- makeRoutes
        resp  <- rs.runZIO(
          Request
            .post(url("/api/apps"), Body.fromString("""{"name":"X","slug":"x"}"""))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
      } yield assertTrue(resp.status == Status.NotFound)
    },
    test("PUT /api/apps/:id is gone (definition update removed)") {
      for {
        _       <- cleanDb
        token   <- adminToken
        rs      <- makeRoutes
        appRepo <- ZIO.service[AppRepo]
        id      <- appRepo.create("Old", "old", None, None)
        resp    <- rs.runZIO(
          Request
            .put(url(s"/api/apps/${id.value}"), Body.fromString("""{"name":"New"}"""))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
      } yield assertTrue(resp.status == Status.NotFound)
    },
    test("PUT /api/apps/:id/hosts is gone (host editing removed)") {
      for {
        _       <- cleanDb
        token   <- adminToken
        rs      <- makeRoutes
        appRepo <- ZIO.service[AppRepo]
        id      <- appRepo.create("X", "x", None, None)
        resp    <- rs.runZIO(
          Request
            .put(url(s"/api/apps/${id.value}/hosts"), Body.fromString("""{"hosts":["a.com"]}"""))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
      } yield assertTrue(resp.status == Status.NotFound)
    },
    test("PATCH /api/apps/:id is gone (definition patch removed)") {
      for {
        _       <- cleanDb
        token   <- adminToken
        rs      <- makeRoutes
        appRepo <- ZIO.service[AppRepo]
        id      <- appRepo.create("X", "x", None, None)
        resp    <- rs.runZIO(
          Request
            .patch(url(s"/api/apps/${id.value}"), Body.fromString("""{"name":"New"}"""))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
      } yield assertTrue(resp.status == Status.NotFound)
    },
    test("GET list+detail returns apps with hosts") {
      for {
        _        <- cleanDb
        token    <- adminToken
        rs       <- makeRoutes
        appRepo  <- ZIO.service[AppRepo]
        id       <- appRepo.create(
          "YouTube",
          "youtube",
          Some(AppTemplateId.unsafe("youtube")),
          Some("📺"),
        )
        _        <- appRepo.setHosts(
          id,
          List(Hostname.unsafe("youtube.com"), Hostname.unsafe("ytimg.com")),
        )
        list     <- rs.runZIO(
          Request.get(url("/api/apps")).addHeader(Header.Authorization.Bearer(token)),
        )
        listBody <- list.body.asString
        details  <- ZIO.fromEither(listBody.fromJson[List[AppDetail]])
        one      <- rs.runZIO(
          Request
            .get(url(s"/api/apps/${id.value}"))
            .addHeader(Header.Authorization.Bearer(token)),
        )
        oneBody  <- one.body.asString
        detail   <- ZIO.fromEither(oneBody.fromJson[AppDetail])
      } yield assertTrue(list.status == Status.Ok) &&
        assertTrue(details.length == 1 && details.head.app.id == id) &&
        assertTrue(details.head.app.name == "YouTube" && details.head.app.slug == "youtube") &&
        assertTrue(
          detail.hosts.toSet == Set(Hostname.unsafe("youtube.com"), Hostname.unsafe("ytimg.com")),
        )
    },
    test("GET /api/apps without token returns 401") {
      for {
        rs   <- makeRoutes
        resp <- rs.runZIO(Request.get(url("/api/apps")))
      } yield assertTrue(resp.status == Status.Unauthorized)
    },
    // #1798: DELETE is kept as an admin-only path (stray-row cleanup) even
    // though the SPA no longer surfaces it.
    test("DELETE cascades hosts and assignments") {
      for {
        _        <- cleanDb
        token    <- adminToken
        rs       <- makeRoutes
        appRepo  <- ZIO.service[AppRepo]
        profileR <- ZIO.service[ProfileRepo]
        profiles <- profileR.listAll
        pid = profiles.head.id
        id    <- appRepo.create("X", "x", None, None)
        _     <- appRepo.setHosts(id, List(Hostname.unsafe("a.com")))
        _     <- appRepo.upsertAssignment(id, pid, AppMode.Blocked, None, true)
        resp  <- rs.runZIO(
          Request
            .delete(url(s"/api/apps/${id.value}"))
            .addHeader(Header.Authorization.Bearer(token)),
        )
        gone  <- appRepo.findById(id)
        hosts <- appRepo.getHosts(id)
        asgn  <- appRepo.listAssignmentsForApp(id)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(gone.isEmpty && hosts.isEmpty && asgn.isEmpty)
    },
    test("PUT /policy/:profileId upserts an assignment (admin)") {
      for {
        _        <- cleanDb
        token    <- adminToken
        rs       <- makeRoutes
        appRepo  <- ZIO.service[AppRepo]
        profileR <- ZIO.service[ProfileRepo]
        profiles <- profileR.listAll
        pid = profiles.head.id
        id <- appRepo.create("X", "x", None, None)
        body = UpsertAppAssignmentRequest(
          mode = AppMode.TimeLimited,
          dailyMinutes = Some(30),
          exemptFromDaily = Some(false),
        ).toJson
        resp <- rs.runZIO(
          Request
            .put(url(s"/api/apps/${id.value}/policy/${pid.value}"), Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
        rows <- appRepo.listAssignmentsForApp(id)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(rows.length == 1) &&
        assertTrue(rows.head.mode == AppMode.TimeLimited) &&
        assertTrue(rows.head.dailyMinutes.contains(30)) &&
        assertTrue(!rows.head.exemptFromDaily)
    },
    test("PUT /policy rejects time_limited without dailyMinutes") {
      for {
        _        <- cleanDb
        token    <- adminToken
        rs       <- makeRoutes
        appRepo  <- ZIO.service[AppRepo]
        profileR <- ZIO.service[ProfileRepo]
        profiles <- profileR.listAll
        pid = profiles.head.id
        id <- appRepo.create("X", "x", None, None)
        body = """{"mode":"time_limited"}"""
        resp <- rs.runZIO(
          Request
            .put(url(s"/api/apps/${id.value}/policy/${pid.value}"), Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
      } yield assertTrue(resp.status == Status.BadRequest)
    },
    test("PUT /policy rejects unknown mode values") {
      for {
        _        <- cleanDb
        token    <- adminToken
        rs       <- makeRoutes
        appRepo  <- ZIO.service[AppRepo]
        profileR <- ZIO.service[ProfileRepo]
        profiles <- profileR.listAll
        pid = profiles.head.id
        id <- appRepo.create("X", "x", None, None)
        body = """{"mode":"bogus"}"""
        resp <- rs.runZIO(
          Request
            .put(url(s"/api/apps/${id.value}/policy/${pid.value}"), Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
      } yield assertTrue(resp.status == Status.BadRequest)
    },
    test("DELETE /policy clears the assignment") {
      for {
        _        <- cleanDb
        token    <- adminToken
        rs       <- makeRoutes
        appRepo  <- ZIO.service[AppRepo]
        profileR <- ZIO.service[ProfileRepo]
        profiles <- profileR.listAll
        pid = profiles.head.id
        id   <- appRepo.create("X", "x", None, None)
        _    <- appRepo.upsertAssignment(id, pid, AppMode.Blocked, None, true)
        resp <- rs.runZIO(
          Request
            .delete(url(s"/api/apps/${id.value}/policy/${pid.value}"))
            .addHeader(Header.Authorization.Bearer(token)),
        )
        rows <- appRepo.listAssignmentsForApp(id)
      } yield assertTrue(resp.status == Status.Ok) && assertTrue(rows.isEmpty)
    },
    test("non-admin (adult) cannot DELETE apps (admin-only)") {
      for {
        _        <- cleanDb
        userRepo <- ZIO.service[UserRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        appRepo  <- ZIO.service[AppRepo]
        auth     <- makeAuth
        _        <- createUser(userRepo, upRepo, auth, "mom", "adult", Nil)
        token    <- auth.login("mom", "pass").map(_.token.value)
        rs       <- makeRoutes
        id       <- appRepo.create("X", "x", None, None)
        resp     <- rs.runZIO(
          Request
            .delete(url(s"/api/apps/${id.value}"))
            .addHeader(Header.Authorization.Bearer(token)),
        )
        still    <- appRepo.findById(id)
      } yield assertTrue(resp.status == Status.Forbidden) && assertTrue(still.isDefined)
    },
    test("adult writer can upsert assignment only for a profile they're linked to") {
      for {
        _        <- cleanDb
        userRepo <- ZIO.service[UserRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        profileR <- ZIO.service[ProfileRepo]
        appRepo  <- ZIO.service[AppRepo]
        auth     <- makeAuth
        profiles <- profileR.listAll
        kidsId   = profiles.find(_.name == "Kids").get.id
        adultsId = profiles.find(_.name == "Adults").get.id
        _     <- createUser(userRepo, upRepo, auth, "mom", "adult", List(kidsId))
        token <- auth.login("mom", "pass").map(_.token.value)
        rs    <- makeRoutes
        id    <- appRepo.create("X", "x", None, None)
        body = UpsertAppAssignmentRequest(mode = AppMode.Blocked).toJson
        okResp <- rs.runZIO(
          Request
            .put(url(s"/api/apps/${id.value}/policy/${kidsId.value}"), Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
        forbid <- rs.runZIO(
          Request
            .put(url(s"/api/apps/${id.value}/policy/${adultsId.value}"), Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
      } yield assertTrue(okResp.status == Status.Ok) &&
        assertTrue(forbid.status == Status.Forbidden)
    },
    test("child role cannot upsert policy assignments (writer required)") {
      for {
        _        <- cleanDb
        userRepo <- ZIO.service[UserRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        profileR <- ZIO.service[ProfileRepo]
        appRepo  <- ZIO.service[AppRepo]
        auth     <- makeAuth
        profiles <- profileR.listAll
        kidsId = profiles.find(_.name == "Kids").get.id
        _     <- createUser(userRepo, upRepo, auth, "alice", "child", List(kidsId))
        token <- auth.login("alice", "pass").map(_.token.value)
        rs    <- makeRoutes
        id    <- appRepo.create("X", "x", None, None)
        body = UpsertAppAssignmentRequest(mode = AppMode.Blocked).toJson
        resp <- rs.runZIO(
          Request
            .put(url(s"/api/apps/${id.value}/policy/${kidsId.value}"), Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
      } yield assertTrue(resp.status == Status.Forbidden)
    },
    test("GET 404 for unknown app id") {
      for {
        _     <- cleanDb
        token <- adminToken
        rs    <- makeRoutes
        resp  <- rs.runZIO(
          Request.get(url("/api/apps/9999")).addHeader(Header.Authorization.Bearer(token)),
        )
      } yield assertTrue(resp.status == Status.NotFound)
    },
  ) @@ TestAspect.sequential
}
