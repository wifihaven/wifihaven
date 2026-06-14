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

object AuthApiSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg   = JwtConfig(secret = "test-secret-at-least-32-chars!!", expiryHours = 1)
  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)
  private val cleanDb  = TestDatabase.cleanAndMigrate

  def spec = suite("Auth API")(
    test("admin can login with seeded credentials") {
      for {
        _      <- cleanDb
        auth   <- makeAuth
        result <- auth.login("admin", "changeme")
      } yield assertTrue(result.token.value.nonEmpty) &&
        assertTrue(result.role == UserRole.Admin) &&
        assertTrue(result.username == "admin")
    },
    test("wrong password returns InvalidCredentials") {
      for {
        _      <- cleanDb
        auth   <- makeAuth
        result <- auth.login("admin", "wrongpassword").exit
      } yield assertTrue(result.isFailure)
    },
    test("unknown user returns InvalidCredentials") {
      for {
        _      <- cleanDb
        auth   <- makeAuth
        result <- auth.login("nobody", "anything").exit
      } yield assertTrue(result.isFailure)
    },
    test("issued token is verifiable") {
      for {
        _      <- cleanDb
        auth   <- makeAuth
        resp   <- auth.login("admin", "changeme")
        claims <- auth.verify(resp.token.value)
      } yield assertTrue(claims.sub == "admin") &&
        assertTrue(claims.role == "admin")
    },
    test("admin can create child user who can then login") {
      for {
        _        <- cleanDb
        userRepo <- ZIO.service[UserRepo]
        auth     <- makeAuth
        hash     <- auth.hashPassword("childpass")
        id       <- userRepo.create("child1", hash, "child")
        // Clear the must_change_password flag so this test exercises login/verify,
        // not the forced-rotation flow (that is tested in UserCreateSpec, #599).
        _        <- userRepo.clearMustChangePassword(id)
        resp     <- auth.login("child1", "childpass")
        claims   <- auth.verify(resp.token.value)
      } yield assertTrue(resp.role == UserRole.Child) &&
        assertTrue(claims.role == "child")
    },
    test("child token fails requireAdmin check") {
      for {
        _        <- cleanDb
        userRepo <- ZIO.service[UserRepo]
        auth     <- makeAuth
        hash     <- auth.hashPassword("pass")
        id       <- userRepo.create("viewer", hash, "child")
        // Clear the must_change_password flag; this test checks role enforcement,
        // not the forced-rotation flow (#599).
        _        <- userRepo.clearMustChangePassword(id)
        resp     <- auth.login("viewer", "pass")
        result   <- auth.requireAdmin(resp.token.value).exit
      } yield assertTrue(result.isFailure)
    },
    test("change password works and old password no longer valid") {
      for {
        _    <- cleanDb
        auth <- makeAuth
        _    <- auth.changePassword("admin", "changeme", "newpassword123")
        bad  <- auth.login("admin", "changeme").exit
        good <- auth.login("admin", "newpassword123").exit
      } yield assertTrue(bad.isFailure) &&
        assertTrue(good.isSuccess)
    },
    test("POST /api/auth/change-password returns JSON body with mustChangePassword=false (#623)") {
      for {
        _               <- cleanDb
        userRepo        <- ZIO.service[UserRepo]
        auth            <- makeAuth
        userProfileRepo <- ZIO.service[UserProfileRepo]
        routes = AuthRoutes.routes(auth, userRepo, userProfileRepo)
        token <- auth.login("admin", "changeme").map(_.token.value)
        cpBody = ChangePasswordRequest("changeme", "newpassword123").toJson
        cpReq  = Request
          .post(URL.decode("/api/auth/change-password").toOption.get, Body.fromString(cpBody))
          .addHeader(Header.Authorization.Bearer(token))
          .addHeader(Header.ContentType(MediaType.application.json))
        resp     <- routes.runZIO(cpReq)
        respBody <- resp.body.asString
        cpResp   <- ZIO.fromEither(respBody.fromJson[ChangePasswordResponse])
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(respBody.nonEmpty) &&
        assertTrue(cpResp.mustChangePassword == false)
    },
    test("POST /api/auth/login via HTTP handler") {
      for {
        _               <- cleanDb
        userRepo        <- ZIO.service[UserRepo]
        auth            <- makeAuth
        userProfileRepo <- ZIO.service[UserProfileRepo]
        routes = AuthRoutes.routes(auth, userRepo, userProfileRepo)
        body   = LoginRequest("admin", "changeme").toJson
        req    = Request
          .post(URL.decode("/api/auth/login").toOption.get, Body.fromString(body))
          .addHeader(Header.ContentType(MediaType.application.json))
        resp     <- routes.runZIO(req)
        respBody <- resp.body.asString
        lr       <- ZIO.fromEither(respBody.fromJson[LoginResponse])
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(lr.token.value.nonEmpty) &&
        assertTrue(lr.role == UserRole.Admin)
    },
  ) @@ TestAspect.sequential
}
