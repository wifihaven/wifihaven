package wifihaven.api.feature

import doobie.*
import doobie.implicits.*
import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.shared.*
import wifihaven.shared.Clock.TestClock
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.test.*

/**
 * #2105 (multi-tenant sub-issue B): the user's household rides in the JWT as an additive `hh`
 * claim, minted at login from `users.household_id` and read back in `verify`. Like the #2080
 * `token_version` claim, `hh` lives inside the HMAC-signed payload — a client cannot forge it any
 * more than `role`.
 */
object JwtHouseholdClaimSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg   = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)
  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)
  private val cleanDb  = TestDatabase.cleanAndMigrate

  /** Insert a second household and a user in it, returning that household's id. */
  private def seedUserInSecondHousehold(
      username: String,
      passwordHash: String,
  ): ZIO[Transactor[Task], Throwable, Long] =
    for {
      xa <- ZIO.service[Transactor[Task]]
      hh <- sql"INSERT INTO households(name) VALUES ('Second household') RETURNING id"
        .query[Long]
        .unique
        .transact(xa)
      _  <- sql"""INSERT INTO users(username,password_hash,role,household_id)
                  VALUES ($username,$passwordHash,'admin',$hh)""".update.run.transact(xa)
    } yield hh

  def spec = suite("JWT household claim (hh)")(
    test("a token minted at login carries the user's household as hh") {
      for {
        _      <- cleanDb
        auth   <- makeAuth
        token  <- auth.login("admin", "changeme").map(_.token.value)
        claims <- auth.verify(token)
      } yield assertTrue(
        claims.hh == HouseholdId(1L),
      ) // seeded admin backfilled to household 1 (V65)
    },
    test("hh survives a mint -> verify round-trip for a non-default household") {
      for {
        _      <- cleanDb
        hash   <- makeAuth.flatMap(_.hashPassword("changeme"))
        hh     <- seedUserInSecondHousehold("beta", hash)
        auth   <- makeAuth
        token  <- auth.login("beta", "changeme").map(_.token.value)
        claims <- auth.verify(token)
      } yield assertTrue(claims.hh == HouseholdId(hh))
    },
    test("hh is inside the signed payload: a tampered token fails verification") {
      for {
        _     <- cleanDb
        auth  <- makeAuth
        token <- auth.login("admin", "changeme").map(_.token.value)
        // Flip a character in the JWT payload segment (base64url middle segment). Any change to the
        // signed content — including hh — invalidates the HMAC signature.
        parts    = token.split('.')
        tampered = parts(0) + "." + mutatePayload(parts(1)) + "." + parts(2)
        result <- auth.verify(tampered).either
      } yield assertTrue(result == Left(AuthError.InvalidToken))
    },
  ) @@ TestAspect.sequential

  /** Flip one character in a base64url segment to corrupt the signed payload. */
  private def mutatePayload(seg: String): String = {
    val c       = seg.charAt(0)
    val flipped = if c == 'A' then 'B' else 'A'
    flipped.toString + seg.substring(1)
  }
}
