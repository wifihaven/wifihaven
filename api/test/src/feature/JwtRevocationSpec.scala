package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.shared.*
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.test.*
import zio.test.Assertion.*

/**
 * #2080: a JWT issued before a password change stops working once the password changes — previously
 * a token was valid for its full expiry (up to 30 days) regardless of password rotation, and logout
 * was client-side only with no server-side revocation at all.
 */
object JwtRevocationSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg   = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)
  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)
  private val cleanDb  = TestDatabase.cleanAndMigrate

  def spec = suite("JWT revocation on password change")(
    test("a token minted before a password change is rejected after the change") {
      for {
        _         <- cleanDb
        auth      <- makeAuth
        oldToken  <- auth.login("admin", "changeme").map(_.token.value)
        preClaims <- auth.verify(oldToken)
        _         <- auth.changePassword("admin", "changeme", "brandnewpassword1")
        result    <- auth.verify(oldToken).either
      } yield assertTrue(preClaims.sub == "admin") &&
        assertTrue(result == Left(AuthError.TokenRevoked))
    },
    test("a token minted AFTER the password change keeps working") {
      for {
        _        <- cleanDb
        auth     <- makeAuth
        _        <- auth.changePassword("admin", "changeme", "brandnewpassword1")
        newToken <- auth.login("admin", "brandnewpassword1").map(_.token.value)
        claims   <- auth.verify(newToken)
      } yield assertTrue(claims.sub == "admin")
    },
    test("changing the password a second time revokes the first-rotation token too") {
      for {
        _          <- cleanDb
        auth       <- makeAuth
        _          <- auth.changePassword("admin", "changeme", "firstrotation1")
        firstToken <- auth.login("admin", "firstrotation1").map(_.token.value)
        _          <- auth.changePassword("admin", "firstrotation1", "secondrotation1")
        result     <- auth.verify(firstToken).either
      } yield assertTrue(result == Left(AuthError.TokenRevoked))
    },
  ) @@ TestAspect.sequential
}
