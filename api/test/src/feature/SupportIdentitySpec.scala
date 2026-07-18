package wifihaven.api.feature

import wifihaven.api.{JwtConfig, PlainConfig, SupportConfig}
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.routes.SupportRoutes
import wifihaven.api.support.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import doobie.*
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*

/**
 * #2199 (support intake B, epic #2197) — the household-gated Plain widget identity +
 * household→Plain customer mapping, end to end. Full stack, embedded Postgres, NO repo mocks; only
 * the external [[PlainClient]] is stubbed with a recorder (docs/process/testing.md — mock ONLY
 * external I/O).
 *
 * The load-bearing pins:
 *   - an authed admin gets a server-signed identity for THEIR OWN household (`tenantIdentifier =
 *     household_id`, `emailHash = HMAC(secret, their-own-email)`), and household A can NEVER obtain
 *     household B's identity — the household-gating boundary (the injection/security note);
 *   - an unauthenticated request gets no identity (401);
 *   - the customer upsert carries the right `tenantIdentifier` + entitlement attributes;
 *   - with NO Plain keys set everything is DARK (`configured=false`, no upsert) — back-compat.
 */
object SupportIdentitySpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val jwt = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)

  private val IdentitySecret = "plain-chat-identity-secret-xyz"
  private val AppId          = "plainApp_123"

  // Widget ON: #2266 explicit flag + both the public app id and the identity secret set. Write API
  // OFF here — the recorder stub stands in for the Plain write client, so we assert the mapping
  // without a network.
  private val widgetCfg =
    SupportConfig(plain =
      PlainConfig(widgetEnabled = true, identitySecret = IdentitySecret, appId = AppId),
    )

  // Everything default ⇒ DARK (widgetEnabled=false, writeEnabled=false).
  private val darkCfg = SupportConfig()

  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      hr    <- ZIO.service[HouseholdRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwt, clock, hr): AuthService

  private def login(auth: AuthService, identifier: String, pw: String): Task[String] =
    auth
      .login(identifier, pw)
      .mapError(e => new RuntimeException(s"login failed: $e"))
      .map(_.token.value)

  // Create an admin in `hh` with a bound (lowercase) email and a known password, cleared of the
  // first-login must-change flag so requireAdmin passes.
  private def seedAdmin(
      auth: AuthService,
      userRepo: UserRepo,
      hh: HouseholdId,
      username: String,
      email: String,
      pw: String,
  ): Task[Unit] =
    for {
      hashed <- auth.hashPassword(pw)
      id     <- userRepo.create(username, hashed, "admin", hh, Some(email.toLowerCase))
      _      <- userRepo.clearMustChangePassword(id)
    } yield ()

  private def getIdentity(
      routes: Routes[Any, Response],
      token: Option[String],
  ): Task[(Status, String)] = {
    val base = Request.get(URL.decode("/api/support/identity").toOption.get)
    val req  = token.fold(base)(t => base.addHeader(Header.Authorization.Bearer(t)))
    routes.runZIO(req).flatMap(r => r.body.asString.map((r.status, _)))
  }

  private def parse(body: String): Task[SupportIdentityResponse] =
    ZIO.fromEither(body.fromJson[SupportIdentityResponse]).mapError(new RuntimeException(_))

  def spec = suite("Support widget identity + mapping (#2199)")(
    test("authed admin gets a server-signed identity for their OWN household; A can't get B's") {
      for {
        _        <- cleanDb
        userRepo <- ZIO.service[UserRepo]
        hhRepo   <- ZIO.service[HouseholdRepo]
        billRepo <- ZIO.service[HouseholdBillingRepo]
        auth     <- makeAuth
        // Household A = default (id 1); household B provisioned fresh.
        hhB      <- hhRepo.create("Family B", "family-b")
        _        <- seedAdmin(
          auth,
          userRepo,
          HouseholdId.Default,
          "admin_a",
          "a@example.com",
          "pwpwpwpw11",
        )
        _        <- seedAdmin(auth, userRepo, hhB, "admin_b", "b@example.com", "pwpwpwpw22")
        // A beta entitlement for household B (fresh — the default household already has a seeded
        // billing row) so the mapping carries a plan attribute for B.
        _        <- billRepo.create(hhB, "beta", founding = true)
        rec      <- PlainClient.recorder
        svc    = SupportService(widgetCfg, userRepo, hhRepo, billRepo, PlainClient.recording(rec))
        routes = SupportRoutes.routes(auth, svc)
        // A logs in via bare username (default household); B via slug/username.
        tokenA      <- login(auth, "admin_a", "pwpwpwpw11")
        tokenB      <- login(auth, "family-b/admin_b", "pwpwpwpw22")
        (sA, bodyA) <- getIdentity(routes, Some(tokenA))
        (sB, bodyB) <- getIdentity(routes, Some(tokenB))
        idA         <- parse(bodyA)
        idB         <- parse(bodyB)
        customers   <- rec.customers.get
        expectedHashA = SupportService.hmacSha256Hex(IdentitySecret, "a@example.com")
        expectedHashB = SupportService.hmacSha256Hex(IdentitySecret, "b@example.com")
      } yield assertTrue(sA == Status.Ok, sB == Status.Ok) &&
        // A's identity is A's household + A's email hash — never B's.
        assertTrue(idA.configured) &&
        assertTrue(idA.appId.contains(AppId)) &&
        assertTrue(idA.email.contains("a@example.com")) &&
        assertTrue(idA.tenantIdentifier.contains(HouseholdId.Default.value.toString)) &&
        assertTrue(idA.emailHash.contains(expectedHashA)) &&
        // B's identity is B's household + B's email hash, and its entitlement attribute reflects B's
        // billing status (beta / founding).
        assertTrue(idB.configured) &&
        assertTrue(idB.tenantIdentifier.contains(hhB.value.toString)) &&
        assertTrue(idB.emailHash.contains(expectedHashB)) &&
        assertTrue(idB.plan.contains("beta"), idB.founding.contains(true)) &&
        // Household-gating: A and B never collide on tenant or hash.
        assertTrue(idA.tenantIdentifier != idB.tenantIdentifier) &&
        assertTrue(idA.emailHash != idB.emailHash) &&
        assertTrue(expectedHashA != expectedHashB) &&
        // The lazy customer upsert carried the CALLER'S OWN household as tenantIdentifier for each.
        assertTrue(
          customers.exists(c =>
            c.tenantIdentifier == HouseholdId.Default.value.toString && c.email == "a@example.com",
          ),
        ) &&
        assertTrue(
          customers.exists(c =>
            c.tenantIdentifier == hhB.value.toString &&
              c.email == "b@example.com" &&
              c.attributes.get("plan").contains("beta"),
          ),
        )
    },
    test("unauthenticated request gets no widget identity (401)") {
      for {
        _        <- cleanDb
        userRepo <- ZIO.service[UserRepo]
        hhRepo   <- ZIO.service[HouseholdRepo]
        billRepo <- ZIO.service[HouseholdBillingRepo]
        auth     <- makeAuth
        rec      <- PlainClient.recorder
        svc    = SupportService(widgetCfg, userRepo, hhRepo, billRepo, PlainClient.recording(rec))
        routes = SupportRoutes.routes(auth, svc)
        (s, _)    <- getIdentity(routes, None)
        customers <- rec.customers.get
      } yield assertTrue(s == Status.Unauthorized, customers.isEmpty)
    },
    test("an email-less admin is not identifiable — dark, no upsert") {
      for {
        _        <- cleanDb
        userRepo <- ZIO.service[UserRepo]
        hhRepo   <- ZIO.service[HouseholdRepo]
        billRepo <- ZIO.service[HouseholdBillingRepo]
        auth     <- makeAuth
        // The V1-seeded default `admin` has NO email — log in as it.
        rec      <- PlainClient.recorder
        svc    = SupportService(widgetCfg, userRepo, hhRepo, billRepo, PlainClient.recording(rec))
        routes = SupportRoutes.routes(auth, svc)
        token     <- login(auth, "admin", "changeme")
        (s, body) <- getIdentity(routes, Some(token))
        id        <- parse(body)
        customers <- rec.customers.get
      } yield assertTrue(s == Status.Ok, !id.configured, id.emailHash.isEmpty, customers.isEmpty)
    },
    test("with no Plain keys set everything is DARK (configured=false, no upsert) — back-compat") {
      for {
        _        <- cleanDb
        userRepo <- ZIO.service[UserRepo]
        hhRepo   <- ZIO.service[HouseholdRepo]
        billRepo <- ZIO.service[HouseholdBillingRepo]
        auth     <- makeAuth
        _        <- seedAdmin(
          auth,
          userRepo,
          HouseholdId.Default,
          "admin_c",
          "c@example.com",
          "pwpwpwpw33",
        )
        rec      <- PlainClient.recorder
        // darkCfg: no app id / identity secret ⇒ widget dark; no api key ⇒ write dark.
        svc    = SupportService(darkCfg, userRepo, hhRepo, billRepo, PlainClient.recording(rec))
        routes = SupportRoutes.routes(auth, svc)
        token     <- login(auth, "admin_c", "pwpwpwpw33")
        (s, body) <- getIdentity(routes, Some(token))
        id        <- parse(body)
        customers <- rec.customers.get
      } yield assertTrue(s == Status.Ok) &&
        assertTrue(
          !id.configured,
          id.appId.isEmpty,
          id.emailHash.isEmpty,
          id.tenantIdentifier.isEmpty,
        ) &&
        // Dark ⇒ no Plain call at all.
        assertTrue(customers.isEmpty)
    },
  ) @@ TestAspect.sequential
}
