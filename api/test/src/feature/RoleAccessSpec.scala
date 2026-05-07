package familydns.api.feature

import familydns.api.JwtConfig
import familydns.api.auth.*
import familydns.api.db.*
import familydns.api.routes.*
import familydns.shared.*
import familydns.shared.Clock.TestClock
import familydns.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*
import zio.test.Assertion.*

/**
 * Role-based access tests covering the admin / adult / child split:
 *   - admin: full access
 *   - adult: write access on profiles linked to them, read all? (no — only linked)
 *   - child: read-only on profiles linked to them
 */
object RoleAccessSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg   = JwtConfig(secret = "test-secret-at-least-32-chars!!", expiryHours = 1)
  private def makeAuth =
    for
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    yield AuthServiceLive(ur, jwtCfg, clock)
  private def cleanDb  = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
    TestDatabase.cleanAndMigrate.provide(ZLayer.succeed(pg)),
  )

  /** Create a user with a given role and link them to the listed profile ids. */
  private def createUser(
      userRepo: UserRepo,
      upRepo: UserProfileRepo,
      auth: AuthService,
      username: String,
      role: String,
      profileIds: List[Long],
  ): Task[Long] =
    for
      hash <- auth.hashPassword("pass")
      id   <- userRepo.create(username, hash, role)
      _    <- upRepo.setProfilesForUser(id, profileIds)
    yield id

  def spec = suite("Role-based access")(
    test("child sees only profiles linked to them") {
      for
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        schedRepo   <- ZIO.service[ScheduleRepo]
        tlRepo      <- ZIO.service[TimeLimitRepo]
        stlRepo     <- ZIO.service[SiteTimeLimitRepo]
        userRepo    <- ZIO.service[UserRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        auth        <- makeAuth
        profiles    <- profileRepo.listAll
        kidsId = profiles.find(_.name == "Kids").get.id
        _     <- createUser(userRepo, upRepo, auth, "alice", "child", List(kidsId))
        token <- auth.login("alice", "pass").map(_.token)
        routes = ProfileRoutes.routes(auth, profileRepo, schedRepo, tlRepo, stlRepo, upRepo)
        req    = Request
          .get(URL.decode("/api/profiles").toOption.get)
          .addHeader(Header.Authorization.Bearer(token))
        resp    <- routes.runZIO(req)
        body    <- resp.body.asString
        details <- ZIO.fromEither(body.fromJson[List[ProfileDetail]])
      yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(details.length == 1) &&
        assertTrue(details.head.profile.name == "Kids")
    },
    test("child cannot read sibling's profile") {
      for
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        schedRepo   <- ZIO.service[ScheduleRepo]
        tlRepo      <- ZIO.service[TimeLimitRepo]
        stlRepo     <- ZIO.service[SiteTimeLimitRepo]
        userRepo    <- ZIO.service[UserRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        auth        <- makeAuth
        profiles    <- profileRepo.listAll
        kidsId   = profiles.find(_.name == "Kids").get.id
        adultsId = profiles.find(_.name == "Adults").get.id
        _     <- createUser(userRepo, upRepo, auth, "alice", "child", List(kidsId))
        token <- auth.login("alice", "pass").map(_.token)
        routes = ProfileRoutes.routes(auth, profileRepo, schedRepo, tlRepo, stlRepo, upRepo)
        req    = Request
          .get(URL.decode(s"/api/profiles/$adultsId").toOption.get)
          .addHeader(Header.Authorization.Bearer(token))
        resp <- routes.runZIO(req)
      yield assertTrue(resp.status == Status.Forbidden)
    },
    test("child cannot edit even their own profile") {
      for
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        schedRepo   <- ZIO.service[ScheduleRepo]
        tlRepo      <- ZIO.service[TimeLimitRepo]
        stlRepo     <- ZIO.service[SiteTimeLimitRepo]
        userRepo    <- ZIO.service[UserRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        auth        <- makeAuth
        profiles    <- profileRepo.listAll
        kidsId = profiles.find(_.name == "Kids").get.id
        _     <- createUser(userRepo, upRepo, auth, "alice", "child", List(kidsId))
        token <- auth.login("alice", "pass").map(_.token)
        routes = ProfileRoutes.routes(auth, profileRepo, schedRepo, tlRepo, stlRepo, upRepo)
        body   = UpsertProfileRequest("Hacked", Nil, Nil, Nil, false, Nil, None, Nil).toJson
        req    = Request
          .put(URL.decode(s"/api/profiles/$kidsId").toOption.get, Body.fromString(body))
          .addHeader(Header.Authorization.Bearer(token))
          .addHeader(Header.ContentType(MediaType.application.json))
        resp <- routes.runZIO(req)
      yield assertTrue(resp.status == Status.Forbidden)
    },
    test("adult can edit profiles they're linked to") {
      for
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        schedRepo   <- ZIO.service[ScheduleRepo]
        tlRepo      <- ZIO.service[TimeLimitRepo]
        stlRepo     <- ZIO.service[SiteTimeLimitRepo]
        userRepo    <- ZIO.service[UserRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        auth        <- makeAuth
        profiles    <- profileRepo.listAll
        kidsId   = profiles.find(_.name == "Kids").get.id
        adultsId = profiles.find(_.name == "Adults").get.id
        _     <- createUser(
          userRepo,
          upRepo,
          auth,
          "mom",
          "adult",
          List(kidsId, adultsId),
        )
        token <- auth.login("mom", "pass").map(_.token)
        routes = ProfileRoutes.routes(auth, profileRepo, schedRepo, tlRepo, stlRepo, upRepo)
        body   = UpsertProfileRequest(
          "Kids Renamed",
          List("adult"),
          Nil,
          Nil,
          false,
          Nil,
          None,
          Nil,
        ).toJson
        req    = Request
          .put(URL.decode(s"/api/profiles/$kidsId").toOption.get, Body.fromString(body))
          .addHeader(Header.Authorization.Bearer(token))
          .addHeader(Header.ContentType(MediaType.application.json))
        resp    <- routes.runZIO(req)
        updated <- profileRepo.findById(kidsId)
      yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(updated.exists(_.name == "Kids Renamed"))
    },
    test("adult cannot edit a profile they're not linked to") {
      for
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        schedRepo   <- ZIO.service[ScheduleRepo]
        tlRepo      <- ZIO.service[TimeLimitRepo]
        stlRepo     <- ZIO.service[SiteTimeLimitRepo]
        userRepo    <- ZIO.service[UserRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        auth        <- makeAuth
        otherId     <- profileRepo.create("Strangers", List("gambling"))
        profiles    <- profileRepo.listAll
        kidsId = profiles.find(_.name == "Kids").get.id
        _     <- createUser(userRepo, upRepo, auth, "mom", "adult", List(kidsId))
        token <- auth.login("mom", "pass").map(_.token)
        routes = ProfileRoutes.routes(auth, profileRepo, schedRepo, tlRepo, stlRepo, upRepo)
        body   = UpsertProfileRequest(
          "Pwned",
          Nil,
          Nil,
          Nil,
          false,
          Nil,
          None,
          Nil,
        ).toJson
        req    = Request
          .put(URL.decode(s"/api/profiles/$otherId").toOption.get, Body.fromString(body))
          .addHeader(Header.Authorization.Bearer(token))
          .addHeader(Header.ContentType(MediaType.application.json))
        resp <- routes.runZIO(req)
      yield assertTrue(resp.status == Status.Forbidden)
    },
    test("adult cannot create new profiles (admin only)") {
      for
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        schedRepo   <- ZIO.service[ScheduleRepo]
        tlRepo      <- ZIO.service[TimeLimitRepo]
        stlRepo     <- ZIO.service[SiteTimeLimitRepo]
        userRepo    <- ZIO.service[UserRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        auth        <- makeAuth
        _           <- createUser(userRepo, upRepo, auth, "mom", "adult", Nil)
        token       <- auth.login("mom", "pass").map(_.token)
        routes = ProfileRoutes.routes(auth, profileRepo, schedRepo, tlRepo, stlRepo, upRepo)
        body   = UpsertProfileRequest("New", Nil, Nil, Nil, false, Nil, None, Nil).toJson
        req    = Request
          .post(URL.decode("/api/profiles").toOption.get, Body.fromString(body))
          .addHeader(Header.Authorization.Bearer(token))
          .addHeader(Header.ContentType(MediaType.application.json))
        resp <- routes.runZIO(req)
      yield assertTrue(resp.status == Status.Forbidden)
    },
    test("adult cannot manage users (admin only)") {
      for
        _        <- cleanDb
        userRepo <- ZIO.service[UserRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        auth     <- makeAuth
        _        <- createUser(userRepo, upRepo, auth, "mom", "adult", Nil)
        token    <- auth.login("mom", "pass").map(_.token)
        routes = AuthRoutes.routes(auth, userRepo, upRepo)
        req    = Request
          .get(URL.decode("/api/users").toOption.get)
          .addHeader(Header.Authorization.Bearer(token))
        resp <- routes.runZIO(req)
      yield assertTrue(resp.status == Status.Forbidden)
    },
    test("admin can set user-profile links and they take effect") {
      for
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        schedRepo   <- ZIO.service[ScheduleRepo]
        tlRepo      <- ZIO.service[TimeLimitRepo]
        stlRepo     <- ZIO.service[SiteTimeLimitRepo]
        userRepo    <- ZIO.service[UserRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        auth        <- makeAuth
        profiles    <- profileRepo.listAll
        kidsId = profiles.find(_.name == "Kids").get.id
        // create a child user with no links yet
        childId    <- createUser(userRepo, upRepo, auth, "alice", "child", Nil)
        adminToken <- auth.login("admin", "changeme").map(_.token)
        authRoutes = AuthRoutes.routes(auth, userRepo, upRepo)
        setBody    = SetUserProfilesRequest(List(kidsId)).toJson
        setReq     = Request
          .put(
            URL.decode(s"/api/users/$childId/profiles").toOption.get,
            Body.fromString(setBody),
          )
          .addHeader(Header.Authorization.Bearer(adminToken))
          .addHeader(Header.ContentType(MediaType.application.json))
        setResp    <- authRoutes.runZIO(setReq)
        // alice should now see the Kids profile
        aliceToken <- auth.login("alice", "pass").map(_.token)
        profRoutes = ProfileRoutes.routes(
          auth,
          profileRepo,
          schedRepo,
          tlRepo,
          stlRepo,
          upRepo,
        )
        listReq    = Request
          .get(URL.decode("/api/profiles").toOption.get)
          .addHeader(Header.Authorization.Bearer(aliceToken))
        listResp <- profRoutes.runZIO(listReq)
        body     <- listResp.body.asString
        details  <- ZIO.fromEither(body.fromJson[List[ProfileDetail]])
      yield assertTrue(setResp.status == Status.Ok) &&
        assertTrue(details.length == 1) &&
        assertTrue(details.head.profile.id == kidsId)
    },
    test("GET /api/me returns username, role, and linked profile ids") {
      for
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        userRepo    <- ZIO.service[UserRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        auth        <- makeAuth
        profiles    <- profileRepo.listAll
        kidsId = profiles.find(_.name == "Kids").get.id
        _     <- createUser(userRepo, upRepo, auth, "alice", "child", List(kidsId))
        token <- auth.login("alice", "pass").map(_.token)
        routes = AuthRoutes.routes(auth, userRepo, upRepo)
        req    = Request
          .get(URL.decode("/api/me").toOption.get)
          .addHeader(Header.Authorization.Bearer(token))
        resp <- routes.runZIO(req)
        body <- resp.body.asString
        me   <- ZIO.fromEither(body.fromJson[MeResponse])
      yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(me.username == "alice") &&
        assertTrue(me.role == "child") &&
        assertTrue(me.profileIds == List(kidsId))
    },
    test("device list scoped to user's profiles for non-admin") {
      for
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        userRepo    <- ZIO.service[UserRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        auth        <- makeAuth
        profiles    <- profileRepo.listAll
        kidsId   = profiles.find(_.name == "Kids").get.id
        adultsId = profiles.find(_.name == "Adults").get.id
        _     <- deviceRepo.upsert("aa:bb:cc:00:00:01", "kid-tablet", kidsId, "")
        _     <- deviceRepo.upsert("aa:bb:cc:00:00:02", "dad-laptop", adultsId, "")
        _     <- createUser(userRepo, upRepo, auth, "alice", "child", List(kidsId))
        token <- auth.login("alice", "pass").map(_.token)
        routes = DeviceRoutes.routes(auth, deviceRepo, upRepo)
        req    = Request
          .get(URL.decode("/api/devices").toOption.get)
          .addHeader(Header.Authorization.Bearer(token))
        resp    <- routes.runZIO(req)
        body    <- resp.body.asString
        devices <- ZIO.fromEither(body.fromJson[List[Device]])
      yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(devices.length == 1) &&
        assertTrue(devices.head.name == "kid-tablet")
    },
  ) @@ TestAspect.sequential
}
