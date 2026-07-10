package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import doobie.*
import doobie.implicits.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.interop.catz.*
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*

/**
 * #2140 (multi-tenant P5-8, epic #622) — household-aware login.
 *
 * V65 relaxed `users.username` to `UNIQUE(household_id, username)`, so the SAME username can exist
 * in two households. Login therefore cannot resolve the household from the username alone: the
 * request carries an optional household **slug** (`households.slug`, V66/#2131). This spec pins the
 * operator's requirement (2026-07-09): the same username works in different households, each keyed
 * by its slug, and a right-username+password in the WRONG household fails identically to a bad
 * password (no cross-household enumeration). Absent slug resolves to the default household so
 * self-hosted single-household deploys log in exactly as before (back-compat).
 *
 * Full-stack, embedded Postgres, no repo mocks, Clock injected.
 */
object HouseholdAwareLoginSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate
  private val jwtCfg  = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)

  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      hr    <- ZIO.service[HouseholdRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock, hr): AuthService

  // Seed a household with `slug`, containing a single user `shared` whose password is `pw`.
  private def seedHousehold(name: String, slug: String, pwHash: String): RIO[Transactor[Task], HouseholdId] =
    for {
      xa <- ZIO.service[Transactor[Task]]
      hh <- sql"INSERT INTO households(name, slug) VALUES ($name, $slug) RETURNING id"
        .query[HouseholdId]
        .unique
        .transact(xa)
      _  <-
        sql"""INSERT INTO users(username, password_hash, role, must_change_password, household_id)
              VALUES ('shared', $pwHash, 'admin', false, $hh)""".update.run.transact(xa)
    } yield hh

  def spec = suite("Household-aware login (#2140)")(
    test("same username in two households: each slug authenticates into its own household") {
      for {
        _        <- cleanDb
        auth     <- makeAuth
        hashX    <- auth.hashPassword("passX")
        hashY    <- auth.hashPassword("passY")
        hhX      <- seedHousehold("House X", "house-x", hashX)
        hhY      <- seedHousehold("House Y", "house-y", hashY)
        respX    <- auth.login("shared", "passX", Some("house-x"))
        claimsX  <- auth.verify(respX.token.value)
        respY    <- auth.login("shared", "passY", Some("house-y"))
        claimsY  <- auth.verify(respY.token.value)
      } yield assertTrue(claimsX.hh == hhX) &&
        assertTrue(claimsY.hh == hhY) &&
        assertTrue(claimsX.hh != claimsY.hh)
    },
    test("right username+password in the WRONG household fails identically to a bad password") {
      for {
        _         <- cleanDb
        auth      <- makeAuth
        hashX     <- auth.hashPassword("passX")
        hashY     <- auth.hashPassword("passY")
        _         <- seedHousehold("House X", "house-x", hashX)
        _         <- seedHousehold("House Y", "house-y", hashY)
        // passY is a VALID password — but only in house-y. Presented against house-x it must fail.
        wrongHh   <- auth.login("shared", "passY", Some("house-x")).either
        // A genuinely bad password in the correct household.
        badPw     <- auth.login("shared", "nope", Some("house-x")).either
      } yield assertTrue(wrongHh == Left(AuthError.InvalidCredentials)) &&
        assertTrue(badPw == Left(AuthError.InvalidCredentials)) &&
        // Indistinguishable: same error value for both failure modes.
        assertTrue(wrongHh == badPw)
    },
    test("unknown slug fails identically to a bad password (no household enumeration)") {
      for {
        _        <- cleanDb
        auth     <- makeAuth
        hashX    <- auth.hashPassword("passX")
        _        <- seedHousehold("House X", "house-x", hashX)
        unknown  <- auth.login("shared", "passX", Some("no-such-house")).either
        badPw    <- auth.login("shared", "nope", Some("house-x")).either
      } yield assertTrue(unknown == Left(AuthError.InvalidCredentials)) &&
        assertTrue(unknown == badPw)
    },
    test("absent household resolves to the default household (self-hosted back-compat)") {
      for {
        _       <- cleanDb
        auth    <- makeAuth
        // The seeded single-install `admin` lives in the default household (id=1).
        resp    <- auth.login("admin", "changeme")
        claims  <- auth.verify(resp.token.value)
      } yield assertTrue(claims.sub == "admin") &&
        assertTrue(claims.hh == HouseholdId.Default)
    },
    test("empty/blank household string also resolves to the default household") {
      for {
        _      <- cleanDb
        auth   <- makeAuth
        resp   <- auth.login("admin", "changeme", Some("   "))
        claims <- auth.verify(resp.token.value)
      } yield assertTrue(claims.hh == HouseholdId.Default)
    },
    test("POST /api/auth/login via HTTP handler resolves the slug end-to-end") {
      for {
        _      <- cleanDb
        auth   <- makeAuth
        ur     <- ZIO.service[UserRepo]
        up     <- ZIO.service[UserProfileRepo]
        hr     <- ZIO.service[HouseholdRepo]
        hashX  <- auth.hashPassword("passX")
        hhX    <- seedHousehold("House X", "house-x", hashX)
        routes = AuthRoutes.routes(auth, ur, up, hr, RateLimiter.allowAll)
        okBody = LoginRequest("shared", "passX", Some("house-x")).toJson
        okReq  = Request
          .post(URL.decode("/api/auth/login").toOption.get, Body.fromString(okBody))
          .addHeader(Header.ContentType(MediaType.application.json))
        okResp <- routes.runZIO(okReq)
        // Wrong household through the HTTP path → 401, same as a bad password.
        badBody = LoginRequest("shared", "passX", Some("no-such-house")).toJson
        badReq  = Request
          .post(URL.decode("/api/auth/login").toOption.get, Body.fromString(badBody))
          .addHeader(Header.ContentType(MediaType.application.json))
        badResp <- routes.runZIO(badReq)
      } yield assertTrue(okResp.status == Status.Ok) &&
        assertTrue(badResp.status == Status.Unauthorized)
    },
  ) @@ TestAspect.sequential
}
