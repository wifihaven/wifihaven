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

  private val jwtCfg   = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)
  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)
  private val cleanDb  = TestDatabase.cleanAndMigrate

  /**
   * Create a user with a given role and link them to the listed profile ids.
   *
   * Clears must_change_password so that these test users are fully operational immediately; the
   * flag behaviour itself is tested in UserCreateSpec (#599).
   */
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

  def spec = suite("Role-based access")(
    test("child sees only profiles linked to them") {
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        tlRepo      <- ZIO.service[TimeLimitRepo]
        userRepo    <- ZIO.service[UserRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        auth        <- makeAuth
        profiles    <- profileRepo.listAllForHousehold(HouseholdId.Default)
        kidsId = profiles.find(_.name == "Kids").get.id
        _     <- createUser(userRepo, upRepo, auth, "alice", "child", List(kidsId))
        token <- auth.login("alice", "pass").map(_.token.value)
        routes = ProfileRoutes.routes(
          auth,
          profileRepo,
          tlRepo,
          upRepo,
          userRepo,
        )
        req    = Request
          .get(URL.decode("/api/profiles").toOption.get)
          .addHeader(Header.Authorization.Bearer(token))
        resp    <- routes.runZIO(req)
        body    <- resp.body.asString
        details <- ZIO.fromEither(body.fromJson[List[ProfileDetail]])
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(details.length == 1) &&
        assertTrue(details.head.profile.name == "Kids")
    },
    test("child cannot read sibling's profile") {
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        tlRepo      <- ZIO.service[TimeLimitRepo]
        userRepo    <- ZIO.service[UserRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        auth        <- makeAuth
        profiles    <- profileRepo.listAllForHousehold(HouseholdId.Default)
        kidsId   = profiles.find(_.name == "Kids").get.id
        adultsId = profiles.find(_.name == "Adults").get.id
        _     <- createUser(userRepo, upRepo, auth, "alice", "child", List(kidsId))
        token <- auth.login("alice", "pass").map(_.token.value)
        routes = ProfileRoutes.routes(
          auth,
          profileRepo,
          tlRepo,
          upRepo,
          userRepo,
        )
        req    = Request
          .get(URL.decode(s"/api/profiles/$adultsId").toOption.get)
          .addHeader(Header.Authorization.Bearer(token))
        resp <- routes.runZIO(req)
      } yield assertTrue(resp.status == Status.Forbidden)
    },
    test("child cannot edit even their own profile") {
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        tlRepo      <- ZIO.service[TimeLimitRepo]
        userRepo    <- ZIO.service[UserRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        auth        <- makeAuth
        profiles    <- profileRepo.listAllForHousehold(HouseholdId.Default)
        kidsId = profiles.find(_.name == "Kids").get.id
        _     <- createUser(userRepo, upRepo, auth, "alice", "child", List(kidsId))
        token <- auth.login("alice", "pass").map(_.token.value)
        routes = ProfileRoutes.routes(
          auth,
          profileRepo,
          tlRepo,
          upRepo,
          userRepo,
        )
        body   = UpsertProfileRequest("Hacked", Nil, false, None).toJson
        req    = Request
          .put(URL.decode(s"/api/profiles/$kidsId").toOption.get, Body.fromString(body))
          .addHeader(Header.Authorization.Bearer(token))
          .addHeader(Header.ContentType(MediaType.application.json))
        resp <- routes.runZIO(req)
      } yield assertTrue(resp.status == Status.Forbidden)
    },
    test("adult can edit profiles they're linked to") {
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        tlRepo      <- ZIO.service[TimeLimitRepo]
        userRepo    <- ZIO.service[UserRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        auth        <- makeAuth
        profiles    <- profileRepo.listAllForHousehold(HouseholdId.Default)
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
        token <- auth.login("mom", "pass").map(_.token.value)
        routes = ProfileRoutes.routes(
          auth,
          profileRepo,
          tlRepo,
          upRepo,
          userRepo,
        )
        body   = UpsertProfileRequest(
          "Kids Renamed",
          List(BlocklistId.unsafe("adult")),
          false,
          None,
        ).toJson
        req    = Request
          .put(URL.decode(s"/api/profiles/$kidsId").toOption.get, Body.fromString(body))
          .addHeader(Header.Authorization.Bearer(token))
          .addHeader(Header.ContentType(MediaType.application.json))
        resp    <- routes.runZIO(req)
        updated <- profileRepo.findById(kidsId)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(updated.exists(_.name == "Kids Renamed"))
    },
    test("adult cannot edit a profile they're not linked to") {
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        tlRepo      <- ZIO.service[TimeLimitRepo]
        userRepo    <- ZIO.service[UserRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        auth        <- makeAuth
        otherId     <- profileRepo.create("Strangers", List(BlocklistId.unsafe("gambling")))
        profiles    <- profileRepo.listAllForHousehold(HouseholdId.Default)
        kidsId = profiles.find(_.name == "Kids").get.id
        _     <- createUser(userRepo, upRepo, auth, "mom", "adult", List(kidsId))
        token <- auth.login("mom", "pass").map(_.token.value)
        routes = ProfileRoutes.routes(
          auth,
          profileRepo,
          tlRepo,
          upRepo,
          userRepo,
        )
        body   = UpsertProfileRequest(
          "Pwned",
          Nil,
          false,
          None,
        ).toJson
        req    = Request
          .put(URL.decode(s"/api/profiles/$otherId").toOption.get, Body.fromString(body))
          .addHeader(Header.Authorization.Bearer(token))
          .addHeader(Header.ContentType(MediaType.application.json))
        resp <- routes.runZIO(req)
      } yield assertTrue(resp.status == Status.Forbidden)
    },
    test("adult cannot create new profiles (admin only)") {
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        tlRepo      <- ZIO.service[TimeLimitRepo]
        userRepo    <- ZIO.service[UserRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        auth        <- makeAuth
        _           <- createUser(userRepo, upRepo, auth, "mom", "adult", Nil)
        token       <- auth.login("mom", "pass").map(_.token.value)
        routes = ProfileRoutes.routes(
          auth,
          profileRepo,
          tlRepo,
          upRepo,
          userRepo,
        )
        body   = UpsertProfileRequest("New", Nil, false, None).toJson
        req    = Request
          .post(URL.decode("/api/profiles").toOption.get, Body.fromString(body))
          .addHeader(Header.Authorization.Bearer(token))
          .addHeader(Header.ContentType(MediaType.application.json))
        resp <- routes.runZIO(req)
      } yield assertTrue(resp.status == Status.Forbidden)
    },
    test("adult cannot manage users (admin only)") {
      for {
        _        <- cleanDb
        userRepo <- ZIO.service[UserRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        auth     <- makeAuth
        _        <- createUser(userRepo, upRepo, auth, "mom", "adult", Nil)
        token    <- auth.login("mom", "pass").map(_.token.value)
        routes = AuthRoutes.routes(auth, userRepo, upRepo, RateLimiter.allowAll)
        req    = Request
          .get(URL.decode("/api/users").toOption.get)
          .addHeader(Header.Authorization.Bearer(token))
        resp <- routes.runZIO(req)
      } yield assertTrue(resp.status == Status.Forbidden)
    },
    test("admin can set user-profile links and they take effect") {
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        tlRepo      <- ZIO.service[TimeLimitRepo]
        userRepo    <- ZIO.service[UserRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        auth        <- makeAuth
        profiles    <- profileRepo.listAllForHousehold(HouseholdId.Default)
        kidsId = profiles.find(_.name == "Kids").get.id
        // create a child user with no links yet
        childId    <- createUser(userRepo, upRepo, auth, "alice", "child", Nil)
        adminToken <- auth.login("admin", "changeme").map(_.token.value)
        authRoutes = AuthRoutes.routes(auth, userRepo, upRepo, RateLimiter.allowAll)
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
        aliceToken <- auth.login("alice", "pass").map(_.token.value)
        profRoutes = ProfileRoutes.routes(
          auth,
          profileRepo,
          tlRepo,
          upRepo,
          userRepo,
        )
        listReq    = Request
          .get(URL.decode("/api/profiles").toOption.get)
          .addHeader(Header.Authorization.Bearer(aliceToken))
        listResp <- profRoutes.runZIO(listReq)
        body     <- listResp.body.asString
        details  <- ZIO.fromEither(body.fromJson[List[ProfileDetail]])
      } yield assertTrue(setResp.status == Status.Ok) &&
        assertTrue(details.length == 1) &&
        assertTrue(details.head.profile.id == kidsId)
    },
    test("GET /api/me returns username, role, and linked profile ids") {
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        userRepo    <- ZIO.service[UserRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        auth        <- makeAuth
        profiles    <- profileRepo.listAllForHousehold(HouseholdId.Default)
        kidsId = profiles.find(_.name == "Kids").get.id
        _     <- createUser(userRepo, upRepo, auth, "alice", "child", List(kidsId))
        token <- auth.login("alice", "pass").map(_.token.value)
        routes = AuthRoutes.routes(auth, userRepo, upRepo, RateLimiter.allowAll)
        req    = Request
          .get(URL.decode("/api/me").toOption.get)
          .addHeader(Header.Authorization.Bearer(token))
        resp <- routes.runZIO(req)
        body <- resp.body.asString
        me   <- ZIO.fromEither(body.fromJson[MeResponse])
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(me.username == "alice") &&
        assertTrue(me.role == UserRole.Child) &&
        assertTrue(me.profileIds == List(kidsId)) &&
        // #2133: a child is never an operator (design §3.2 — admin AND household 1).
        assertTrue(!me.isOperator)
    },
    test("GET /api/me reports isOperator for a household-1 admin (#2133)") {
      for {
        _        <- cleanDb
        userRepo <- ZIO.service[UserRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        auth     <- makeAuth
        // The V1 seed admin lives in household 1 (the default operator household).
        token    <- auth.login("admin", "changeme").map(_.token.value)
        routes = AuthRoutes.routes(auth, userRepo, upRepo, RateLimiter.allowAll)
        req    = Request
          .get(URL.decode("/api/me").toOption.get)
          .addHeader(Header.Authorization.Bearer(token))
        resp <- routes.runZIO(req)
        body <- resp.body.asString
        me   <- ZIO.fromEither(body.fromJson[MeResponse])
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(me.role == UserRole.Admin) &&
        assertTrue(me.isOperator)
    },
    test("device list scoped to user's profiles for non-admin") {
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        userRepo    <- ZIO.service[UserRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        auth        <- makeAuth
        profiles    <- profileRepo.listAllForHousehold(HouseholdId.Default)
        kidsId   = profiles.find(_.name == "Kids").get.id
        adultsId = profiles.find(_.name == "Adults").get.id
        _     <- deviceRepo.upsert(
          MacAddress.unsafe("aa:bb:cc:00:00:01"),
          "kid-tablet",
          Some(kidsId),
          "",
        )
        _     <- deviceRepo.upsert(
          MacAddress.unsafe("aa:bb:cc:00:00:02"),
          "dad-laptop",
          Some(adultsId),
          "",
        )
        _     <- createUser(userRepo, upRepo, auth, "alice", "child", List(kidsId))
        token <- auth.login("alice", "pass").map(_.token.value)
        routes = DeviceRoutes.routes(auth, deviceRepo, upRepo, profileRepo)
        req    = Request
          .get(URL.decode("/api/devices").toOption.get)
          .addHeader(Header.Authorization.Bearer(token))
        resp    <- routes.runZIO(req)
        body    <- resp.body.asString
        devices <- ZIO.fromEither(body.fromJson[List[Device]])
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(devices.length == 1) &&
        assertTrue(devices.head.name == "kid-tablet")
    },
    test("adult sees ALL profiles (not just linked ones)") {
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        tlRepo      <- ZIO.service[TimeLimitRepo]
        userRepo    <- ZIO.service[UserRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        auth        <- makeAuth
        profiles    <- profileRepo.listAllForHousehold(HouseholdId.Default)
        kidsId = profiles.find(_.name == "Kids").get.id
        _     <- createUser(userRepo, upRepo, auth, "mom", "adult", List(kidsId))
        token <- auth.login("mom", "pass").map(_.token.value)
        routes = ProfileRoutes.routes(
          auth,
          profileRepo,
          tlRepo,
          upRepo,
          userRepo,
        )
        req    = Request
          .get(URL.decode("/api/profiles").toOption.get)
          .addHeader(Header.Authorization.Bearer(token))
        resp    <- routes.runZIO(req)
        body    <- resp.body.asString
        details <- ZIO.fromEither(body.fromJson[List[ProfileDetail]])
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(details.length == 2)
    },
    test("adult can GET any profile by id even if not linked") {
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        tlRepo      <- ZIO.service[TimeLimitRepo]
        userRepo    <- ZIO.service[UserRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        auth        <- makeAuth
        profiles    <- profileRepo.listAllForHousehold(HouseholdId.Default)
        kidsId   = profiles.find(_.name == "Kids").get.id
        adultsId = profiles.find(_.name == "Adults").get.id
        _     <- createUser(userRepo, upRepo, auth, "mom", "adult", List(kidsId))
        token <- auth.login("mom", "pass").map(_.token.value)
        routes = ProfileRoutes.routes(
          auth,
          profileRepo,
          tlRepo,
          upRepo,
          userRepo,
        )
        req    = Request
          .get(URL.decode(s"/api/profiles/$adultsId").toOption.get)
          .addHeader(Header.Authorization.Bearer(token))
        resp <- routes.runZIO(req)
      } yield assertTrue(resp.status == Status.Ok)
    },
    test("adult sees ALL devices (not just linked ones)") {
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        userRepo    <- ZIO.service[UserRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        auth        <- makeAuth
        profiles    <- profileRepo.listAllForHousehold(HouseholdId.Default)
        kidsId   = profiles.find(_.name == "Kids").get.id
        adultsId = profiles.find(_.name == "Adults").get.id
        _     <- deviceRepo.upsert(
          MacAddress.unsafe("aa:bb:cc:00:00:01"),
          "kid-tablet",
          Some(kidsId),
          "",
        )
        _     <- deviceRepo.upsert(
          MacAddress.unsafe("aa:bb:cc:00:00:02"),
          "dad-laptop",
          Some(adultsId),
          "",
        )
        _     <- createUser(userRepo, upRepo, auth, "mom", "adult", List(kidsId))
        token <- auth.login("mom", "pass").map(_.token.value)
        routes = DeviceRoutes.routes(auth, deviceRepo, upRepo, profileRepo)
        req    = Request
          .get(URL.decode("/api/devices").toOption.get)
          .addHeader(Header.Authorization.Bearer(token))
        resp    <- routes.runZIO(req)
        body    <- resp.body.asString
        devices <- ZIO.fromEither(body.fromJson[List[Device]])
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(devices.length == 2)
    },
    test("adult sees ALL logs (not just linked profiles)") {
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        connRepo    <- ZIO.service[ConnectionEventRepo]
        routerRepo  <- ZIO.service[RouterRepo]
        userRepo    <- ZIO.service[UserRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        auth        <- makeAuth
        profiles    <- profileRepo.listAllForHousehold(HouseholdId.Default)
        kidsId = profiles.find(_.name == "Kids").get.id
        routerId <- routerRepo.create("home", Sha256Hex.unsafe("p" * 64))
        _        <- routerRepo.completeEnrollment(routerId, Sha256Hex.unsafe("q" * 64))
        _        <- connRepo.insertBatch(
          List(
            ConnectionEventInsert(
              routerId,
              Some(MacAddress.unsafe("aa:bb:cc:00:00:01")),
              HostId.Fqdn(Hostname.unsafe("youtube.com")),
              None,
              true,
              BlockReason.fromWire("allowed"),
              java.time.Instant.now().minusSeconds(300),
            ),
            ConnectionEventInsert(
              routerId,
              Some(MacAddress.unsafe("aa:bb:cc:00:00:02")),
              HostId.Fqdn(Hostname.unsafe("nytimes.com")),
              None,
              true,
              BlockReason.fromWire("allowed"),
              java.time.Instant.now().minusSeconds(300),
            ),
          ),
        )
        _        <- createUser(userRepo, upRepo, auth, "mom", "adult", List(kidsId))
        token    <- auth.login("mom", "pass").map(_.token.value)
        routes = LogRoutes.routes(auth, connRepo, upRepo)
        req    = Request
          .get(URL.decode("/api/logs").toOption.get)
          .addHeader(Header.Authorization.Bearer(token))
        resp <- routes.runZIO(req)
        body <- resp.body.asString
        page <- ZIO.fromEither(body.fromJson[QueryLogPage])
        logs = page.rows
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(logs.length == 2)
    },
  ) @@ TestAspect.sequential
}
