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

  private def cleanDb = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
    TestDatabase.cleanAndMigrate.provide(ZLayer.succeed(pg)),
  )

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
    test("POST creates an app, GET list+detail returns it with hosts") {
      for {
        _     <- cleanDb
        token <- adminToken
        rs    <- makeRoutes
        body = CreateAppRequest(
          name = "YouTube",
          slug = Some("youtube"),
          icon = Some("📺"),
          templateId = Some(AppTemplateId.unsafe("youtube")),
          hosts = List("youtube.com", "*.ytimg.com"),
        ).toJson
        post     <- rs.runZIO(
          Request
            .post(url("/api/apps"), Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
        postBody <- post.body.asString
        created  <- ZIO.fromEither(postBody.fromJson[AppDetail])
        list     <- rs.runZIO(
          Request.get(url("/api/apps")).addHeader(Header.Authorization.Bearer(token)),
        )
        listBody <- list.body.asString
        details  <- ZIO.fromEither(listBody.fromJson[List[AppDetail]])
        one      <- rs.runZIO(
          Request
            .get(url(s"/api/apps/${created.app.id.value}"))
            .addHeader(Header.Authorization.Bearer(token)),
        )
        oneBody  <- one.body.asString
        detail   <- ZIO.fromEither(oneBody.fromJson[AppDetail])
      } yield assertTrue(post.status == Status.Ok) &&
        assertTrue(created.app.name == "YouTube") &&
        assertTrue(created.app.slug == "youtube") &&
        assertTrue(
          created.hosts.toSet == Set(Hostname.unsafe("youtube.com"), Hostname.unsafe("ytimg.com")),
        ) &&
        assertTrue(details.length == 1 && details.head.app.id == created.app.id) &&
        assertTrue(detail.hosts.toSet == created.hosts.toSet)
    },
    test("POST without slug derives one from the name") {
      for {
        _     <- cleanDb
        token <- adminToken
        rs    <- makeRoutes
        body = CreateAppRequest(name = "My Cool App").toJson
        resp <- rs.runZIO(
          Request
            .post(url("/api/apps"), Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
        rb   <- resp.body.asString
        d    <- ZIO.fromEither(rb.fromJson[AppDetail])
      } yield assertTrue(resp.status == Status.Ok) && assertTrue(d.app.slug == "my-cool-app")
    },
    test("POST returns 409 on duplicate slug") {
      for {
        _     <- cleanDb
        token <- adminToken
        rs    <- makeRoutes
        body = CreateAppRequest(name = "YouTube", slug = Some("youtube")).toJson
        _    <- rs.runZIO(
          Request
            .post(url("/api/apps"), Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
        dupe <- rs.runZIO(
          Request
            .post(url("/api/apps"), Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
      } yield assertTrue(dupe.status == Status.Conflict)
    },
    test("POST rejects malformed host strings with 400") {
      for {
        _     <- cleanDb
        token <- adminToken
        rs    <- makeRoutes
        body = """{"name":"X","slug":"x","hosts":["not a hostname!"]}"""
        resp <- rs.runZIO(
          Request
            .post(url("/api/apps"), Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
      } yield assertTrue(resp.status == Status.BadRequest)
    },
    test("GET /api/apps without token returns 401") {
      for {
        rs   <- makeRoutes
        resp <- rs.runZIO(Request.get(url("/api/apps")))
      } yield assertTrue(resp.status == Status.Unauthorized)
    },
    test("PUT updates name + icon") {
      for {
        _       <- cleanDb
        token   <- adminToken
        rs      <- makeRoutes
        appRepo <- ZIO.service[AppRepo]
        id      <- appRepo.create("Old", "old", None, None)
        body = UpdateAppRequest(name = "New", icon = Some("✨")).toJson
        resp <- rs.runZIO(
          Request
            .put(url(s"/api/apps/${id.value}"), Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
        row  <- appRepo.findById(id)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(row.exists(_.name == "New")) &&
        assertTrue(row.exists(_.icon.contains("✨")))
    },
    test("PUT /hosts replaces the host set and canonicalizes *.foo") {
      for {
        _       <- cleanDb
        token   <- adminToken
        rs      <- makeRoutes
        appRepo <- ZIO.service[AppRepo]
        id      <- appRepo.create("X", "x", None, None)
        _       <- appRepo.setHosts(id, List(Hostname.unsafe("a.com"), Hostname.unsafe("b.com")))
        body = SetAppHostsRequest(hosts = List("*.youtube.com", "ytimg.com")).toJson
        resp  <- rs.runZIO(
          Request
            .put(url(s"/api/apps/${id.value}/hosts"), Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
        hosts <- appRepo.getHosts(id)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(hosts.toSet == Set(Hostname.unsafe("youtube.com"), Hostname.unsafe("ytimg.com")))
    },
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
    test("non-admin (adult) cannot create apps") {
      for {
        _        <- cleanDb
        userRepo <- ZIO.service[UserRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        auth     <- makeAuth
        _        <- createUser(userRepo, upRepo, auth, "mom", "adult", Nil)
        token    <- auth.login("mom", "pass").map(_.token.value)
        rs       <- makeRoutes
        body = CreateAppRequest(name = "X", slug = Some("x")).toJson
        resp <- rs.runZIO(
          Request
            .post(url("/api/apps"), Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
      } yield assertTrue(resp.status == Status.Forbidden)
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
