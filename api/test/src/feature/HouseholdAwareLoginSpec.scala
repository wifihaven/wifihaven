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
 * Login can no longer resolve the household from the username: the request carries an optional
 * household **slug** (`households.slug`, V66/#2131), and the user lookup is keyed on
 * `(household_id, username)`. This spec pins the operator's requirement (2026-07-09): the household
 * is taken from the request, NOT the username; a valid credential presented against the wrong
 * household fails identically to a bad password (no cross-household enumeration); and an absent
 * slug resolves to the default household so self-hosted single-household deploys log in exactly as
 * before (back-compat).
 *
 * NOTE on the same-username variant. V65 kept the GLOBAL `users_username_key UNIQUE(username)`
 * alongside the per-household composite unique (`V65__households.sql:52`), so the literal "same
 * username in two households" INSERT is not yet representable — it is DB-blocked until that global
 * unique is dropped (TODO(#2147), a schema-only follow-up, mirroring the deferred `devices_mac_key`
 * drop that `MultiTenantIsolationSpec` pin 4a works around). The login CODE path
 * (`findByUsername(hh, u)`) already handles it; this spec proves the achievable-and-equivalent
 * property with DISTINCT-household users: the household is never derived from the username, and a
 * user in one household can never authenticate against another. Once #2147 lands, the distinct
 * usernames below collapse to a single shared username.
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

  // Seed a household with `slug`, containing a single user `username` whose password hash is `pwHash`.
  private def seedHousehold(
      name: String,
      slug: String,
      username: String,
      pwHash: String,
  ): RIO[Transactor[Task], HouseholdId] =
    for {
      xa <- ZIO.service[Transactor[Task]]
      hh <- sql"INSERT INTO households(name, slug) VALUES ($name, $slug) RETURNING id"
        .query[HouseholdId]
        .unique
        .transact(xa)
      _  <-
        sql"""INSERT INTO users(username, password_hash, role, must_change_password, household_id)
              VALUES ($username, $pwHash, 'admin', false, $hh)""".update.run.transact(xa)
    } yield hh

  def spec = suite("Household-aware login (#2140)")(
    test(
      "each household's user authenticates via its own slug, minting that household's hh claim",
    ) {
      for {
        _       <- cleanDb
        auth    <- makeAuth
        hashX   <- auth.hashPassword("passX")
        hashY   <- auth.hashPassword("passY")
        hhX     <- seedHousehold("House X", "house-x", "alpha", hashX)
        hhY     <- seedHousehold("House Y", "house-y", "beta", hashY)
        respX   <- auth.login("alpha", "passX", Some("house-x"))
        claimsX <- auth.verify(respX.token.value)
        respY   <- auth.login("beta", "passY", Some("house-y"))
        claimsY <- auth.verify(respY.token.value)
      } yield assertTrue(claimsX.hh == hhX) &&
        assertTrue(claimsY.hh == hhY) &&
        assertTrue(claimsX.hh != claimsY.hh)
    },
    test("the household comes from the request, never the username (no slug → default household)") {
      // The operator's core requirement: a user in household X must NOT authenticate just because
      // the username is right — with no slug, login resolves to the DEFAULT household, where this
      // user does not exist, so it fails exactly like a bad password.
      for {
        _      <- cleanDb
        auth   <- makeAuth
        hashX  <- auth.hashPassword("passX")
        _      <- seedHousehold("House X", "house-x", "alpha", hashX)
        noSlug <- auth.login("alpha", "passX").either
        badPw  <- auth.login("alpha", "nope", Some("house-x")).either
      } yield assertTrue(noSlug == Left(AuthError.InvalidCredentials)) &&
        assertTrue(noSlug == badPw)
    },
    test("a valid credential presented against the WRONG household fails like a bad password") {
      for {
        _       <- cleanDb
        auth    <- makeAuth
        hashX   <- auth.hashPassword("passX")
        hashY   <- auth.hashPassword("passY")
        _       <- seedHousehold("House X", "house-x", "alpha", hashX)
        _       <- seedHousehold("House Y", "house-y", "beta", hashY)
        // alpha/passX is valid — but only in house-x. Presented against house-y it must fail.
        wrongHh <- auth.login("alpha", "passX", Some("house-y")).either
        badPw   <- auth.login("beta", "nope", Some("house-y")).either
      } yield assertTrue(wrongHh == Left(AuthError.InvalidCredentials)) &&
        assertTrue(badPw == Left(AuthError.InvalidCredentials)) &&
        // Indistinguishable: same error value for both failure modes.
        assertTrue(wrongHh == badPw)
    },
    test("unknown slug fails identically to a bad password (no household enumeration)") {
      for {
        _       <- cleanDb
        auth    <- makeAuth
        hashX   <- auth.hashPassword("passX")
        _       <- seedHousehold("House X", "house-x", "alpha", hashX)
        unknown <- auth.login("alpha", "passX", Some("no-such-house")).either
        badPw   <- auth.login("alpha", "nope", Some("house-x")).either
      } yield assertTrue(unknown == Left(AuthError.InvalidCredentials)) &&
        assertTrue(unknown == badPw)
    },
    test("absent household resolves to the default household (self-hosted back-compat)") {
      for {
        _      <- cleanDb
        auth   <- makeAuth
        // The seeded single-install `admin` lives in the default household (id=1).
        resp   <- auth.login("admin", "changeme")
        claims <- auth.verify(resp.token.value)
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
    test("the seeded default household is reachable by its explicit `default` slug too") {
      for {
        _      <- cleanDb
        auth   <- makeAuth
        resp   <- auth.login("admin", "changeme", Some("default"))
        claims <- auth.verify(resp.token.value)
      } yield assertTrue(claims.hh == HouseholdId.Default)
    },
    test("POST /api/auth/login via HTTP handler resolves the slug end-to-end") {
      for {
        _     <- cleanDb
        auth  <- makeAuth
        ur    <- ZIO.service[UserRepo]
        up    <- ZIO.service[UserProfileRepo]
        hashX <- auth.hashPassword("passX")
        _     <- seedHousehold("House X", "house-x", "alpha", hashX)
        // `auth` already has the real HouseholdRepo injected, so the login route resolves the slug.
        routes = AuthRoutes.routes(auth, ur, up, RateLimiter.allowAll)
        okBody = LoginRequest("alpha", "passX", Some("house-x")).toJson
        okReq  = Request
          .post(URL.decode("/api/auth/login").toOption.get, Body.fromString(okBody))
          .addHeader(Header.ContentType(MediaType.application.json))
        okResp <- routes.runZIO(okReq)
        // Wrong household through the HTTP path → 401, same as a bad password.
        badBody = LoginRequest("alpha", "passX", Some("no-such-house")).toJson
        badReq  = Request
          .post(URL.decode("/api/auth/login").toOption.get, Body.fromString(badBody))
          .addHeader(Header.ContentType(MediaType.application.json))
        badResp <- routes.runZIO(badReq)
      } yield assertTrue(okResp.status == Status.Ok) &&
        assertTrue(badResp.status == Status.Unauthorized)
    },
  ) @@ TestAspect.sequential
}
