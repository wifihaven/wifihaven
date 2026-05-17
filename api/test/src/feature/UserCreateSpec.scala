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
 * Tests that admin-created users are forced through the change-password flow (#599, follow-up to
 * #586).
 *
 * On creation via POST /api/users (or direct userRepo.create), must_change_password must be true.
 * The user can still log in and call POST /api/auth/change-password, but every other authenticated
 * route returns 403 {"error":"password_change_required"} until the flag is cleared.
 */
object UserCreateSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg   = JwtConfig(secret = "test-secret-at-least-32-chars!!", expiryHours = 1)
  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)
  private def cleanDb  = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
    TestDatabase.cleanAndMigrate.provide(ZLayer.succeed(pg)),
  )

  def spec = suite("User creation sets must_change_password=true")(
    test("userRepo.create stores must_change_password=true") {
      for {
        _        <- cleanDb
        userRepo <- ZIO.service[UserRepo]
        auth     <- makeAuth
        hash     <- auth.hashPassword("secret")
        id       <- userRepo.create("alice", hash, "adult")
        user     <- userRepo.findById(id)
      } yield assertTrue(user.exists(_.mustChangePassword))
    },
    test("admin-created user cannot access authenticated routes until password changed") {
      for {
        _               <- cleanDb
        userRepo        <- ZIO.service[UserRepo]
        upRepo          <- ZIO.service[UserProfileRepo]
        auth            <- makeAuth
        // Admin creates a new user via the route handler (exercises the full HTTP path).
        adminToken      <- auth.login("admin", "changeme").map(_.token.value)
        authRoutes       = AuthRoutes.routes(auth, userRepo, upRepo)
        createBody       = CreateUserRequest("newbie", "initial123", UserRole.Adult, Nil).toJson
        createReq        = Request
          .post(URL.decode("/api/users").toOption.get, Body.fromString(createBody))
          .addHeader(Header.Authorization.Bearer(adminToken))
          .addHeader(Header.ContentType(MediaType.application.json))
        createResp      <- authRoutes.runZIO(createReq)
        // Login with the newly created user.
        newbieLoginResp <- auth.login("newbie", "initial123")
        newbieToken      = newbieLoginResp.token.value
        // Any non-change-password authenticated route should return 403.
        meReq            = Request
          .get(URL.decode("/api/me").toOption.get)
          .addHeader(Header.Authorization.Bearer(newbieToken))
        meResp          <- authRoutes.runZIO(meReq)
      } yield assertTrue(createResp.status == Status.Ok) &&
        assertTrue(newbieLoginResp.mustChangePassword) &&
        assertTrue(meResp.status == Status.Forbidden)
    },
    test("change-password route is not blocked by must_change_password flag") {
      for {
        _               <- cleanDb
        userRepo        <- ZIO.service[UserRepo]
        upRepo          <- ZIO.service[UserProfileRepo]
        auth            <- makeAuth
        adminToken      <- auth.login("admin", "changeme").map(_.token.value)
        authRoutes       = AuthRoutes.routes(auth, userRepo, upRepo)
        createBody       = CreateUserRequest("charlie", "initial456", UserRole.Adult, Nil).toJson
        createReq        = Request
          .post(URL.decode("/api/users").toOption.get, Body.fromString(createBody))
          .addHeader(Header.Authorization.Bearer(adminToken))
          .addHeader(Header.ContentType(MediaType.application.json))
        _               <- authRoutes.runZIO(createReq)
        charlieToken    <- auth.login("charlie", "initial456").map(_.token.value)
        // POST /api/auth/change-password must succeed even with must_change_password=true.
        cpBody           = ChangePasswordRequest("initial456", "mynewpassword99").toJson
        cpReq            = Request
          .post(URL.decode("/api/auth/change-password").toOption.get, Body.fromString(cpBody))
          .addHeader(Header.Authorization.Bearer(charlieToken))
          .addHeader(Header.ContentType(MediaType.application.json))
        cpResp          <- authRoutes.runZIO(cpReq)
        // After changing the password the flag is cleared and /api/me now works.
        newToken        <- auth.login("charlie", "mynewpassword99").map(_.token.value)
        meReq            = Request
          .get(URL.decode("/api/me").toOption.get)
          .addHeader(Header.Authorization.Bearer(newToken))
        meResp          <- authRoutes.runZIO(meReq)
      } yield assertTrue(cpResp.status == Status.Ok) &&
        assertTrue(meResp.status == Status.Ok)
    },
  ) @@ TestAspect.sequential
}
