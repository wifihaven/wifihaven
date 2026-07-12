package wifihaven.api.feature

import wifihaven.api.{BetaConfig, JwtConfig}
import wifihaven.api.auth.*
import wifihaven.api.beta.*
import wifihaven.api.db.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.api.notify.Notifier
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock
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
 * #2132 (multi-tenant P5-2, epic #622) — the beta request → operator approval → provisioning →
 * invite accept pipeline, end to end. Full stack, embedded Postgres, NO repo mocks; the invite TTL
 * boundary is driven by a controllable [[TestClock]] (design §3.4). The cross-household isolation
 * pins for this surface live in [[MultiTenantIsolationSpec]] (THE acceptance gate).
 */
object BetaProvisioningSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val jwt = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)

  private val noopNotifier: Notifier = new Notifier {
    def alertCreated(a: Alert): UIO[Unit]                                                 = ZIO.unit
    def betaHouseholdProvisioned(email: String, slug: String, hh: HouseholdId): UIO[Unit] = ZIO.unit
  }

  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      // #2140: inject the DB-backed HouseholdRepo so login can resolve a provisioned household's
      // slug (the default-only stub the 3-arg constructor supplies knows only slug `default`).
      hr    <- ZIO.service[HouseholdRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwt, clock, hr): AuthService

  private def login(
      auth: AuthService,
      user: String,
      pw: String,
      slug: Option[String] = None,
  ): Task[String] =
    auth
      .login(user, pw, slug)
      .mapError(e => new RuntimeException(s"login failed: $e"))
      .map(_.token.value)

  private def getJson(
      routes: Routes[Any, Response],
      path: String,
      token: String,
  ): Task[(Status, String)] =
    for {
      resp <- routes.runZIO(
        Request.get(URL.decode(path).toOption.get).addHeader(Header.Authorization.Bearer(token)),
      )
      body <- resp.body.asString
    } yield (resp.status, body)

  private def postJson(
      routes: Routes[Any, Response],
      path: String,
      token: Option[String],
      body: String,
  ): Task[(Status, String)] = {
    val base = Request
      .post(URL.decode(path).toOption.get, Body.fromString(body))
      .addHeader(Header.ContentType(MediaType.application.json))
    val req  = token.fold(base)(t => base.addHeader(Header.Authorization.Bearer(t)))
    routes.runZIO(req).flatMap(r => r.body.asString.map((r.status, _)))
  }

  private case class Built(
      svc: BetaService,
      betaRepo: BetaRequestRepo,
      householdRepo: HouseholdRepo,
      userRepo: UserRepo,
      auth: AuthService,
      routes: Routes[Any, Response],
  )

  // Build the beta stack with a caller-supplied clock so the invite TTL can be advanced.
  private def build(betaClock: Clock): ZIO[TestDatabase.AllRepos & Clock, Nothing, Built] =
    for {
      br   <- ZIO.service[BetaRequestRepo]
      hr   <- ZIO.service[HouseholdRepo]
      ur   <- ZIO.service[UserRepo]
      auth <- makeAuth
    } yield {
      val svc = BetaService(br, hr, ur, auth, noopNotifier, betaClock, BetaConfig())
      Built(svc, br, hr, ur, auth, BetaRoutes.routes(auth, svc, br, ur, RateLimiter.allowAll))
    }

  private def tokenFromInviteUrl(url: String): String = url.split("token=").last

  def spec = suite("Beta provisioning pipeline (#2132)")(
    test("happy path — request → operator approve → invite → accept → new admin logs into new hh") {
      for {
        _   <- cleanDb
        ctl <- TestClock.makeWithControl(TestClock.schoolDayAfternoon)
        (bc, _) = ctl
        b              <- build(bc)
        xa             <- ZIO.service[Transactor[Task]]
        opToken        <- login(b.auth, "admin", "changeme")
        (s1, _)        <- postJson(
          b.routes,
          "/api/beta/request",
          None,
          CreateBetaRequest("Fam@Example.com", Some("The Test Family")).toJson,
        )
        (s2, listBody) <- getJson(b.routes, "/api/operator/beta-requests", opToken)
        summaries      <- ZIO
          .fromEither(listBody.fromJson[List[BetaRequestSummary]])
          .mapError(new RuntimeException(_))
        reqId = summaries.head.id
        (s3, approveBody) <- postJson(
          b.routes,
          s"/api/operator/beta-requests/${reqId.value}/approve",
          Some(opToken),
          "",
        )
        approve           <- ZIO
          .fromEither(approveBody.fromJson[ApproveBetaResponse])
          .mapError(new RuntimeException(_))
        token = tokenFromInviteUrl(approve.inviteUrl)
        // Design §3.4 (2026-07-10): the accept payload is {token, password} — no username/email.
        (s4, acceptBody) <- postJson(
          b.routes,
          "/api/beta/accept",
          None,
          AcceptInviteRequest(token, "supersecret123").toJson,
        )
        accept           <- ZIO
          .fromEither(acceptBody.fromJson[AcceptInviteResponse])
          .mapError(new RuntimeException(_))
        // The admin's email is bound from beta_requests.email (globally unique, V67) and username
        // defaults to `admin`. Verify the row directly.
        adminHh          <- sql"SELECT household_id FROM users WHERE email='fam@example.com'"
          .query[HouseholdId]
          .unique
          .transact(xa)
        adminUsername    <- sql"SELECT username FROM users WHERE email='fam@example.com'"
          .query[String]
          .unique
          .transact(xa)
        newLogin         <- b.auth
          // The first admin is `admin`; log into the NEW household by its slug (per-household usernames).
          .login("admin", "supersecret123", Some(approve.slug))
          .mapError(e => new RuntimeException(s"login failed: $e"))
        claims           <- b.auth
          .verify(newLogin.token.value)
          .mapError(e => new RuntimeException(s"verify failed: $e"))
      } yield assertTrue(s1 == Status.Ok, s2 == Status.Ok, s3 == Status.Ok, s4 == Status.Ok) &&
        // Slug derived from the household name, lowercased + dashed.
        assertTrue(approve.slug == "the-test-family") &&
        // The invite URL points at the SPA accept page.
        assertTrue(approve.inviteUrl.contains("/welcome?token=")) &&
        // The email was normalised (trim + lowercase) on intake.
        assertTrue(summaries.head.email == "fam@example.com") &&
        assertTrue(accept.slug == approve.slug) &&
        // The admin is `admin`, email-bound to the approved request's address.
        assertTrue(accept.username == "admin", adminUsername == "admin") &&
        assertTrue(adminHh == approve.householdId) &&
        // The new admin logs into the NEW household, not the default operator household.
        assertTrue(claims.hh == approve.householdId, claims.hh != HouseholdId.Default) &&
        // The new admin is not forced through a first-login password change (they set it themselves).
        assertTrue(!newLogin.mustChangePassword)
    },
    test("invite token past its TTL is rejected (Clock-driven)") {
      for {
        _   <- cleanDb
        ctl <- TestClock.makeWithControl(TestClock.schoolDayAfternoon)
        (bc, tc) = ctl
        b                <- build(bc)
        opToken          <- login(b.auth, "admin", "changeme")
        _                <- postJson(
          b.routes,
          "/api/beta/request",
          None,
          CreateBetaRequest("late@example.com", Some("Late Family")).toJson,
        )
        (_, listBody)    <- getJson(b.routes, "/api/operator/beta-requests", opToken)
        reqId            <- ZIO
          .fromEither(listBody.fromJson[List[BetaRequestSummary]])
          .mapError(new RuntimeException(_))
          .map(_.head.id)
        (_, approveBody) <- postJson(
          b.routes,
          s"/api/operator/beta-requests/${reqId.value}/approve",
          Some(opToken),
          "",
        )
        token            <- ZIO
          .fromEither(approveBody.fromJson[ApproveBetaResponse])
          .mapError(new RuntimeException(_))
          .map(a => tokenFromInviteUrl(a.inviteUrl))
        // Push the clock past the default 7-day (168h) TTL.
        _                <- tc.advance(java.time.Duration.ofHours(169))
        (sExpired, _)    <- postJson(
          b.routes,
          "/api/beta/accept",
          None,
          AcceptInviteRequest(token, "supersecret123").toJson,
        )
      } yield assertTrue(sExpired == Status.BadRequest)
    },
    test("an accepted invite token cannot be replayed (single-use)") {
      for {
        _   <- cleanDb
        ctl <- TestClock.makeWithControl(TestClock.schoolDayAfternoon)
        (bc, _) = ctl
        b                <- build(bc)
        opToken          <- login(b.auth, "admin", "changeme")
        _                <- postJson(
          b.routes,
          "/api/beta/request",
          None,
          CreateBetaRequest("once@example.com", Some("Once Family")).toJson,
        )
        (_, listBody)    <- getJson(b.routes, "/api/operator/beta-requests", opToken)
        reqId            <- ZIO
          .fromEither(listBody.fromJson[List[BetaRequestSummary]])
          .mapError(new RuntimeException(_))
          .map(_.head.id)
        (_, approveBody) <- postJson(
          b.routes,
          s"/api/operator/beta-requests/${reqId.value}/approve",
          Some(opToken),
          "",
        )
        token            <- ZIO
          .fromEither(approveBody.fromJson[ApproveBetaResponse])
          .mapError(new RuntimeException(_))
          .map(a => tokenFromInviteUrl(a.inviteUrl))
        (sFirst, _)      <- postJson(
          b.routes,
          "/api/beta/accept",
          None,
          AcceptInviteRequest(token, "supersecret123").toJson,
        )
        // Same token — must be rejected; the token was burned on first use.
        (sReplay, _)     <- postJson(
          b.routes,
          "/api/beta/accept",
          None,
          AcceptInviteRequest(token, "supersecret123").toJson,
        )
      } yield assertTrue(sFirst == Status.Ok, sReplay == Status.BadRequest)
    },
    test("duplicate intake is idempotent and leaks no enumeration signal") {
      for {
        _   <- cleanDb
        ctl <- TestClock.makeWithControl(TestClock.schoolDayAfternoon)
        (bc, _) = ctl
        b  <- build(bc)
        xa <- ZIO.service[Transactor[Task]]
        body = CreateBetaRequest("dup@example.com", Some("Dup")).toJson
        (s1, body1) <- postJson(b.routes, "/api/beta/request", None, body)
        (s2, body2) <- postJson(b.routes, "/api/beta/request", None, body)
        // Exactly one row despite two POSTs.
        count       <- sql"SELECT COUNT(*) FROM beta_requests WHERE email='dup@example.com'"
          .query[Long]
          .unique
          .transact(xa)
      } yield assertTrue(s1 == Status.Ok, s2 == Status.Ok) &&
        // Byte-identical response body — a duplicate is indistinguishable from a first request.
        assertTrue(body1 == body2) &&
        assertTrue(count == 1L)
    },
  ) @@ TestAspect.sequential
}
