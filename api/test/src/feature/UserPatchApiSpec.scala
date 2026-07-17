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

// #997: PATCH /api/users/:id — partial update of username/role/profileIds.
// Password changes stay on the dedicated change-password endpoint.
object UserPatchApiSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg   = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)
  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)
  private val cleanDb  = TestDatabase.cleanAndMigrate

  private def url(p: String) = URL.decode(p).toOption.get

  private def setupUser(role: String = "adult", profileIds: List[ProfileId] = Nil) =
    for {
      _        <- cleanDb
      userRepo <- ZIO.service[UserRepo]
      upRepo   <- ZIO.service[UserProfileRepo]
      auth     <- makeAuth
      hash     <- auth.hashPassword("secret")
      id       <- userRepo.create("alice", hash, role)
      _        <- userRepo.clearMustChangePassword(id)
      _        <- upRepo.setProfilesForUser(id, profileIds)
    } yield id

  private def routesAndToken =
    for {
      auth     <- makeAuth
      userRepo <- ZIO.service[UserRepo]
      upRepo   <- ZIO.service[UserProfileRepo]
      token    <- auth.login("admin", "changeme").map(_.token.value)
    } yield (AuthRoutes.routes(auth, userRepo, upRepo, RateLimiter.allowAll), token)

  private def patch(routes: Routes[Any, Response], token: String, id: UserId, body: String) =
    routes.runZIO(
      Request
        .patch(url(s"/api/users/${id.value}"), Body.fromString(body))
        .addHeader(Header.Authorization.Bearer(token))
        .addHeader(Header.ContentType(MediaType.application.json)),
    )

  def spec = suite("PATCH /api/users/:id (#997)")(
    test("single-field patch updates username only") {
      for {
        id           <- setupUser()
        (routes, tk) <- routesAndToken
        resp         <- patch(routes, tk, id, """{"username":"alice2"}""")
        userRepo     <- ZIO.service[UserRepo]
        after        <- userRepo.findById(id)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(after.exists(_.username == "alice2")) &&
        assertTrue(after.exists(_.role == UserRole.Adult))
    },
    test("multi-field patch updates username + role + profileIds together") {
      for {
        id          <- setupUser(role = "adult")
        profileRepo <- ZIO.service[ProfileRepo]
        profiles    <- profileRepo.listAllForHousehold(HouseholdId.Default)
        kidsId = profiles.find(_.name == "Kids").get.id
        (routes, tk) <- routesAndToken
        resp         <- patch(
          routes,
          tk,
          id,
          s"""{"username":"junior","role":"child","profileIds":[${kidsId.value}]}""",
        )
        userRepo     <- ZIO.service[UserRepo]
        upRepo       <- ZIO.service[UserProfileRepo]
        after        <- userRepo.findById(id)
        pids         <- upRepo.listProfilesForUser(id)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(after.exists(_.username == "junior")) &&
        assertTrue(after.exists(_.role == UserRole.Child)) &&
        assertTrue(pids == List(kidsId))
    },
    test("absent field preserves existing value") {
      for {
        _           <- cleanDb
        userRepo    <- ZIO.service[UserRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        profileRepo <- ZIO.service[ProfileRepo]
        auth        <- makeAuth
        hash        <- auth.hashPassword("secret")
        id          <- userRepo.create("alice", hash, "adult")
        _           <- userRepo.clearMustChangePassword(id)
        profiles    <- profileRepo.listAllForHousehold(HouseholdId.Default)
        kidsId = profiles.find(_.name == "Kids").get.id
        _     <- upRepo.setProfilesForUser(id, List(kidsId))
        token <- auth.login("admin", "changeme").map(_.token.value)
        routes = AuthRoutes.routes(auth, userRepo, upRepo, RateLimiter.allowAll)
        // Empty body — change nothing.
        resp  <- patch(routes, token, id, "{}")
        after <- userRepo.findById(id)
        pids  <- upRepo.listProfilesForUser(id)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(after.exists(_.username == "alice")) &&
        assertTrue(after.exists(_.role == UserRole.Adult)) &&
        assertTrue(pids == List(kidsId))
    },
    test("profileIds=[] unassigns all profiles") {
      for {
        _            <- ZIO.unit
        profileRepo  <- ZIO.service[ProfileRepo]
        _            <- cleanDb
        userRepo     <- ZIO.service[UserRepo]
        upRepo       <- ZIO.service[UserProfileRepo]
        auth         <- makeAuth
        hash         <- auth.hashPassword("secret")
        id           <- userRepo.create("alice", hash, "adult")
        _            <- userRepo.clearMustChangePassword(id)
        profileRepo2 <- ZIO.service[ProfileRepo]
        profiles     <- profileRepo2.listAllForHousehold(HouseholdId.Default)
        kidsId = profiles.find(_.name == "Kids").get.id
        _     <- upRepo.setProfilesForUser(id, List(kidsId))
        token <- auth.login("admin", "changeme").map(_.token.value)
        routes = AuthRoutes.routes(auth, userRepo, upRepo, RateLimiter.allowAll)
        resp <- patch(routes, token, id, """{"profileIds":[]}""")
        pids <- upRepo.listProfilesForUser(id)
      } yield assertTrue(resp.status == Status.Ok) && assertTrue(pids.isEmpty)
    },
    test("null on non-nullable username returns 400") {
      for {
        id           <- setupUser()
        (routes, tk) <- routesAndToken
        resp         <- patch(routes, tk, id, """{"username":null}""")
      } yield assertTrue(resp.status == Status.BadRequest)
    },
    test("invalid role value returns 400") {
      for {
        id           <- setupUser()
        (routes, tk) <- routesAndToken
        resp         <- patch(routes, tk, id, """{"role":"superuser"}""")
      } yield assertTrue(resp.status == Status.BadRequest)
    },
    test("404 when user is missing") {
      for {
        _            <- cleanDb
        (routes, tk) <- routesAndToken
        resp         <- patch(routes, tk, UserId(999999L), """{"username":"x"}""")
      } yield assertTrue(resp.status == Status.NotFound)
    },
    test("403 when non-admin (adult) tries to PATCH") {
      for {
        id       <- setupUser()
        userRepo <- ZIO.service[UserRepo]
        upRepo   <- ZIO.service[UserProfileRepo]
        auth     <- makeAuth
        // alice is the seeded adult; she shouldn't be able to PATCH herself.
        token    <- auth.login("alice", "secret").map(_.token.value)
        routes = AuthRoutes.routes(auth, userRepo, upRepo, RateLimiter.allowAll)
        resp <- patch(routes, token, id, """{"username":"alice2"}""")
      } yield assertTrue(resp.status == Status.Forbidden)
    },
  ) @@ TestAspect.sequential
}
