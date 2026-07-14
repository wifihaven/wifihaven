package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.shared.*
import wifihaven.shared.Clock.TestClock
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import pdi.jwt.*
import pdi.jwt.algorithms.JwtHmacAlgorithm
import zio.{Clock as _, *}
import zio.test.*

/**
 * #2218 (multi-tenant security): a JWT that carries no explicit `hh` (household) claim — a
 * pre-#2105 token, minted before the household claim existed — MUST be rejected by `verify`, not
 * silently assigned `HouseholdId.Default`. Defaulting a missing household is a cross-tenant hazard
 * the moment a second household exists (the token would be granted the default tenant's data). The
 * rejection mirrors the #2080 `token_version` path: `TokenRevoked` → 401 → the client re-logs in
 * and mints a token that carries a real `hh`.
 */
object JwtHouseholdRequiredSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)
  private val algo: JwtHmacAlgorithm = JwtAlgorithm.HS256
  private def makeAuth               =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)
  private val cleanDb                = TestDatabase.cleanAndMigrate

  /**
   * Sign a JWT whose `content` claim is the raw JSON given — used to reconstruct exactly what a
   * pre-#2105 token looked like on the wire (a `content` payload with NO `hh` field). Signed with
   * the same secret/algorithm the service uses, so the HMAC verifies and the decode is reached.
   */
  private def signToken(contentJson: String): ZIO[Clock, Nothing, String] =
    ZIO.serviceWithZIO[Clock](_.instant).map { i =>
      val now   = i.getEpochSecond
      val claim = JwtClaim(
        content = contentJson,
        subject = Some("admin"),
        issuedAt = Some(now),
        expiration = Some(now + jwtCfg.expiryHours * 3600L),
      )
      JwtZIOJson.encode(claim, jwtCfg.secret, algo)
    }

  def spec = suite("JWT requires an explicit household claim")(
    test("a token whose payload has NO hh field is rejected (not defaulted to household 1)") {
      for {
        _      <- cleanDb
        auth   <- makeAuth
        // Exactly a pre-#2105 payload: role + tv, but NO hh field.
        token  <- signToken("""{"role":"admin","tv":0}""")
        result <- auth.verify(token).either
      } yield assertTrue(
        result == Left(AuthError.TokenRevoked),
        // Belt-and-suspenders: it must NOT have resolved to the default household.
        result.toOption.map(_.hh) != Some(HouseholdId.Default),
        result.isLeft,
      )
    },
    test("a token WITH an explicit hh claim verifies and resolves to that household") {
      for {
        _      <- cleanDb
        auth   <- makeAuth
        // Explicit hh present → passes the household gate. (Seeded admin is in household 1, so the
        // token_version lookup succeeds against a tv of 0.)
        token  <- signToken("""{"role":"admin","tv":0,"hh":1}""")
        claims <- auth.verify(token)
      } yield assertTrue(claims.hh == HouseholdId.Default, claims.sub == "admin")
    },
    test("a freshly minted login token (always carries hh) verifies fine") {
      for {
        _      <- cleanDb
        auth   <- makeAuth
        token  <- auth.login("admin", "changeme").map(_.token.value)
        claims <- auth.verify(token)
      } yield assertTrue(claims.hh == HouseholdId.Default)
    },
    test("token_version rejection still fires alongside the household gate") {
      for {
        _        <- cleanDb
        auth     <- makeAuth
        // A login token carries hh, so it clears the household gate; changing the password bumps
        // token_version and the old token is then rejected TokenRevoked by the #2080 path.
        oldToken <- auth.login("admin", "changeme").map(_.token.value)
        _        <- auth.changePassword("admin", "changeme", "brandnewpassword1")
        result   <- auth.verify(oldToken).either
      } yield assertTrue(result == Left(AuthError.TokenRevoked))
    },
  ) @@ TestAspect.sequential
}
