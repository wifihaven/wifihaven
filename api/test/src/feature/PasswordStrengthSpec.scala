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
 * #2084: POST /api/users and POST /api/auth/change-password reject passwords shorter than
 * [[AuthService.MinPasswordLength]] with 400 — previously neither route validated strength at all.
 */
object PasswordStrengthSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg   = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)
  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)
  private val cleanDb  = TestDatabase.cleanAndMigrate

  def spec = suite("Password strength validation")(
    test("POST /api/users rejects a password shorter than the minimum") {
      for {
        _          <- cleanDb
        userRepo   <- ZIO.service[UserRepo]
        upRepo     <- ZIO.service[UserProfileRepo]
        auth       <- makeAuth
        adminToken <- auth.login("admin", "changeme").map(_.token.value)
        authRoutes = AuthRoutes.routes(auth, userRepo, upRepo, RateLimiter.allowAll)
        shortPw    = "a" * (AuthService.MinPasswordLength - 1)
        createBody = CreateUserRequest("shortpw", shortPw, UserRole.Adult, Nil).toJson
        createReq  = Request
          .post(URL.decode("/api/users").toOption.get, Body.fromString(createBody))
          .addHeader(Header.Authorization.Bearer(adminToken))
          .addHeader(Header.ContentType(MediaType.application.json))
        createResp <- authRoutes.runZIO(createReq)
        found      <- userRepo.findByUsername(HouseholdId.Default, "shortpw")
      } yield assertTrue(createResp.status == Status.BadRequest) &&
        assertTrue(found.isEmpty)
    },
    test("POST /api/users accepts a password at exactly the minimum length") {
      for {
        _          <- cleanDb
        userRepo   <- ZIO.service[UserRepo]
        upRepo     <- ZIO.service[UserProfileRepo]
        auth       <- makeAuth
        adminToken <- auth.login("admin", "changeme").map(_.token.value)
        authRoutes = AuthRoutes.routes(auth, userRepo, upRepo, RateLimiter.allowAll)
        exactPw    = "a" * AuthService.MinPasswordLength
        createBody = CreateUserRequest("exactpw", exactPw, UserRole.Adult, Nil).toJson
        createReq  = Request
          .post(URL.decode("/api/users").toOption.get, Body.fromString(createBody))
          .addHeader(Header.Authorization.Bearer(adminToken))
          .addHeader(Header.ContentType(MediaType.application.json))
        createResp <- authRoutes.runZIO(createReq)
      } yield assertTrue(createResp.status == Status.Ok)
    },
    test("POST /api/auth/change-password rejects a new password shorter than the minimum") {
      for {
        _          <- cleanDb
        userRepo   <- ZIO.service[UserRepo]
        upRepo     <- ZIO.service[UserProfileRepo]
        auth       <- makeAuth
        adminToken <- auth.login("admin", "changeme").map(_.token.value)
        authRoutes = AuthRoutes.routes(auth, userRepo, upRepo, RateLimiter.allowAll)
        shortPw    = "b" * (AuthService.MinPasswordLength - 1)
        cpBody     = ChangePasswordRequest("changeme", shortPw).toJson
        cpReq      = Request
          .post(URL.decode("/api/auth/change-password").toOption.get, Body.fromString(cpBody))
          .addHeader(Header.Authorization.Bearer(adminToken))
          .addHeader(Header.ContentType(MediaType.application.json))
        cpResp      <- authRoutes.runZIO(cpReq)
        // Old password must still work — the rejected change must not have applied.
        stillLogsIn <- auth.login("admin", "changeme").either
      } yield assertTrue(cpResp.status == Status.BadRequest) &&
        assertTrue(stillLogsIn.isRight)
    },
  ) @@ TestAspect.sequential
}
