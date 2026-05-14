package familydns.api.feature

import familydns.api.JwtConfig
import familydns.api.auth.*
import familydns.api.db.*
import familydns.api.routes.*
import familydns.shared.*
import familydns.shared.types.*
import familydns.shared.Clock.TestClock
import familydns.testinfra.*
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
  private def cleanDb  = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
    TestDatabase.cleanAndMigrate.provide(ZLayer.succeed(pg)),
  )

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
        _        <- userRepo.create("child1", hash, "child")
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
        _        <- userRepo.create("viewer", hash, "child")
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
