package wifihaven.api.feature

import wifihaven.api.{BetaConfig, EmailConfig, JwtConfig}
import wifihaven.api.auth.*
import wifihaven.api.beta.*
import wifihaven.api.db.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.api.notify.{EmailOutcome, EmailSender, Notifier}
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
    def alertCreated(a: Alert): UIO[Unit]                                               = ZIO.unit
    def betaInvite(email: String, slug: String, inviteUrl: String, ttl: Int): UIO[Unit] = ZIO.unit
  }

  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      // #2140: inject the DB-backed HouseholdRepo so login can resolve a provisioned household's
      // slug (the default-only stub the 3-arg constructor supplies knows only slug `default`).
      hr    <- ZIO.service[HouseholdRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwt, clock, hr): AuthService

  // #2164: single-identifier login — a non-default household names itself via the `slug/username`
  // identifier form; the default household (slug `default`) is reachable by a bare username.
  private def login(
      auth: AuthService,
      user: String,
      pw: String,
      slug: Option[String] = None,
  ): Task[String] =
    auth
      .login(slug.fold(user)(s => s"$s/$user"), pw)
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

  // Build the beta stack with a caller-supplied clock so the invite TTL can be advanced, and an
  // optional notifier so a test can capture invite (re)sends — via a recording EmailSender (#2190)
  // or the resend path (#2222).
  private def build(
      betaClock: Clock,
      notifier: Notifier = noopNotifier,
  ): ZIO[TestDatabase.AllRepos & Clock, Nothing, Built] =
    for {
      br   <- ZIO.service[BetaRequestRepo]
      hr   <- ZIO.service[HouseholdRepo]
      ur   <- ZIO.service[UserRepo]
      hbr  <- ZIO.service[HouseholdBillingRepo]
      auth <- makeAuth
    } yield {
      // #2135: billing is disabled here (no Stripe keys) — provisionCustomer is a no-op, so the beta
      // pipeline behaves exactly as before this seam landed. Webhook/checkout are exercised in
      // BillingWebhookSpec with a stubbed StripeClient.
      val billing =
        wifihaven.api.billing.BillingService(
          wifihaven.api.billing.StripeClient.noop,
          hbr,
          betaClock,
          wifihaven.api.StripeConfig(),
        )
      val svc     = BetaService(br, hr, ur, auth, notifier, betaClock, BetaConfig(), billing)
      Built(svc, br, hr, ur, auth, BetaRoutes.routes(auth, svc, br, ur, RateLimiter.allowAll))
    }

  // #2190: the production invite-email path is Notifier.EmailNotifier.betaInvite → EmailSender. Mock
  // ONLY the external email I/O (the sender), everything else real. A recording sender captures each
  // send; the failing sender models a transport error (Resend maps any error to EmailOutcome.Failed).
  private def emailNotifier(sender: EmailSender): ZIO[HouseholdSettingsRepo, Nothing, Notifier] =
    ZIO.serviceWith[HouseholdSettingsRepo](hs => Notifier.EmailNotifier(hs, sender, EmailConfig()))

  private val failingSender: EmailSender = new EmailSender {
    def send(to: String, subject: String, htmlBody: String): UIO[EmailOutcome] =
      ZIO.succeed(EmailOutcome.Failed)
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
          // #2164: the admin's global identity is its bound email — log in by email (single-identifier).
          .login("fam@example.com", "supersecret123")
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
    test("intake with a missing/blank name is rejected with a clean 400 (#2217)") {
      for {
        _   <- cleanDb
        ctl <- TestClock.makeWithControl(TestClock.schoolDayAfternoon)
        (bc, _) = ctl
        b             <- build(bc)
        // Absent name.
        (sMissing, _) <- postJson(
          b.routes,
          "/api/beta/request",
          None,
          CreateBetaRequest("noname@example.com").toJson,
        )
        // Present but whitespace-only.
        (sBlank, _)   <- postJson(
          b.routes,
          "/api/beta/request",
          None,
          CreateBetaRequest("blank@example.com", Some("   ")).toJson,
        )
        xa            <- ZIO.service[Transactor[Task]]
        count         <- sql"SELECT COUNT(*) FROM beta_requests"
          .query[Long]
          .unique
          .transact(xa)
      } yield assertTrue(sMissing == Status.BadRequest, sBlank == Status.BadRequest) &&
        // No row is created for a rejected intake.
        assertTrue(count == 0L)
    },
    test("two households with the same name get distinct, collision-safe slugs (#2217)") {
      for {
        _   <- cleanDb
        ctl <- TestClock.makeWithControl(TestClock.schoolDayAfternoon)
        (bc, _) = ctl
        b       <- build(bc)
        opToken <- login(b.auth, "admin", "changeme")
        approve = (email: String) =>
          for {
            _             <- postJson(
              b.routes,
              "/api/beta/request",
              None,
              CreateBetaRequest(email, Some("The Smith Family")).toJson,
            )
            (_, listBody) <- getJson(b.routes, "/api/operator/beta-requests", opToken)
            reqId         <- ZIO
              .fromEither(listBody.fromJson[List[BetaRequestSummary]])
              .mapError(new RuntimeException(_))
              // The queue lists pending newest-or-oldest first; pick the row for THIS email.
              .map(_.find(_.email == email).get.id)
            (_, apBody)   <- postJson(
              b.routes,
              s"/api/operator/beta-requests/${reqId.value}/approve",
              Some(opToken),
              "",
            )
            ap            <- ZIO
              .fromEither(apBody.fromJson[ApproveBetaResponse])
              .mapError(new RuntimeException(_))
          } yield ap.slug
        slug1 <- approve("smith1@example.com")
        slug2 <- approve("smith2@example.com")
      } yield
      // First gets the clean derived slug; the second is de-duplicated with a numeric suffix.
      assertTrue(slug1 == "the-smith-family", slug2 == "the-smith-family-2")
    },
    test("a name that slugifies to empty falls back to a valid slug satisfying the CHECK (#2217)") {
      for {
        _   <- cleanDb
        ctl <- TestClock.makeWithControl(TestClock.schoolDayAfternoon)
        (bc, _) = ctl
        b                  <- build(bc)
        xa                 <- ZIO.service[Transactor[Task]]
        opToken            <- login(b.auth, "admin", "changeme")
        // "!!!" is a non-empty name (passes the intake guard) but slugifies to nothing.
        _                  <- postJson(
          b.routes,
          "/api/beta/request",
          None,
          CreateBetaRequest("symbols@example.com", Some("!!!")).toJson,
        )
        (_, listBody)      <- getJson(b.routes, "/api/operator/beta-requests", opToken)
        reqId              <- ZIO
          .fromEither(listBody.fromJson[List[BetaRequestSummary]])
          .mapError(new RuntimeException(_))
          .map(_.head.id)
        (sApprove, apBody) <- postJson(
          b.routes,
          s"/api/operator/beta-requests/${reqId.value}/approve",
          Some(opToken),
          "",
        )
        ap                 <- ZIO
          .fromEither(apBody.fromJson[ApproveBetaResponse])
          .mapError(new RuntimeException(_))
        // The V66 CHECK (slug ~ '^[a-z0-9-]+$') accepted the persisted slug.
        persisted          <- sql"SELECT slug FROM households WHERE id = ${ap.householdId}"
          .query[Option[String]]
          .unique
          .transact(xa)
      } yield assertTrue(sApprove == Status.Ok) &&
        assertTrue(ap.slug.nonEmpty, ap.slug.matches("^[a-z0-9-]+$")) &&
        assertTrue(persisted.contains(ap.slug))
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
    test("#2190 — approve auto-sends the invite email to the requester carrying the invite URL") {
      for {
        _   <- cleanDb
        ctl <- TestClock.makeWithControl(TestClock.schoolDayAfternoon)
        (bc, _) = ctl
        ref                     <- Ref.make(List.empty[EmailSender.Sent])
        notifier                <- emailNotifier(EmailSender.recording(ref))
        b                       <- build(bc, notifier)
        opToken                 <- login(b.auth, "admin", "changeme")
        _                       <- postJson(
          b.routes,
          "/api/beta/request",
          None,
          CreateBetaRequest("Fam@Example.com", Some("The Test Family")).toJson,
        )
        (_, listBody)           <- getJson(b.routes, "/api/operator/beta-requests", opToken)
        reqId                   <- ZIO
          .fromEither(listBody.fromJson[List[BetaRequestSummary]])
          .mapError(new RuntimeException(_))
          .map(_.head.id)
        (sApprove, approveBody) <- postJson(
          b.routes,
          s"/api/operator/beta-requests/${reqId.value}/approve",
          Some(opToken),
          "",
        )
        approve                 <- ZIO
          .fromEither(approveBody.fromJson[ApproveBetaResponse])
          .mapError(new RuntimeException(_))
        sent                    <- ref.get
      } yield assertTrue(sApprove == Status.Ok) &&
        // Exactly one email, to the requester's normalised address (NOT a household notify_email).
        assertTrue(sent.size == 1, sent.head.to == "fam@example.com") &&
        // The body carries the exact single-use accept link the operator response also returns.
        assertTrue(sent.head.htmlBody.contains(approve.inviteUrl)) &&
        assertTrue(sent.head.htmlBody.contains("/welcome?token="))
    },
    test(
      "#2190 — an email transport failure still provisions the household and returns the invite URL",
    ) {
      for {
        _   <- cleanDb
        ctl <- TestClock.makeWithControl(TestClock.schoolDayAfternoon)
        (bc, _) = ctl
        notifier                <- emailNotifier(failingSender)
        b                       <- build(bc, notifier)
        xa                      <- ZIO.service[Transactor[Task]]
        opToken                 <- login(b.auth, "admin", "changeme")
        _                       <- postJson(
          b.routes,
          "/api/beta/request",
          None,
          CreateBetaRequest("fail@example.com", Some("Fail Family")).toJson,
        )
        (_, listBody)           <- getJson(b.routes, "/api/operator/beta-requests", opToken)
        reqId                   <- ZIO
          .fromEither(listBody.fromJson[List[BetaRequestSummary]])
          .mapError(new RuntimeException(_))
          .map(_.head.id)
        (sApprove, approveBody) <- postJson(
          b.routes,
          s"/api/operator/beta-requests/${reqId.value}/approve",
          Some(opToken),
          "",
        )
        approve                 <- ZIO
          .fromEither(approveBody.fromJson[ApproveBetaResponse])
          .mapError(new RuntimeException(_))
        // The provisioning transaction committed despite the send failing (fail-open).
        hhCount                 <- sql"SELECT COUNT(*) FROM households WHERE slug = ${approve.slug}"
          .query[Long]
          .unique
          .transact(xa)
      } yield
      // Approval succeeds and the invite URL is still returned as the operator's manual-resend path.
      assertTrue(sApprove == Status.Ok) &&
        assertTrue(approve.inviteUrl.contains("/welcome?token=")) &&
        assertTrue(hhCount == 1L)
    },
    test(
      "#2222 — second request from a PENDING applicant is a no-op — no new row, no invite, generic 200",
    ) {
      for {
        _   <- cleanDb
        ctl <- TestClock.makeWithControl(TestClock.schoolDayAfternoon)
        (bc, _) = ctl
        ref      <- Ref.make(List.empty[EmailSender.Sent])
        notifier <- emailNotifier(EmailSender.recording(ref))
        b        <- build(bc, notifier)
        xa       <- ZIO.service[Transactor[Task]]
        body = CreateBetaRequest("pend@example.com", Some("Pend Family")).toJson
        (s1, _) <- postJson(b.routes, "/api/beta/request", None, body)
        (s2, _) <- postJson(b.routes, "/api/beta/request", None, body)
        sent    <- ref.get
        count   <- sql"SELECT COUNT(*) FROM beta_requests WHERE email='pend@example.com'"
          .query[Long]
          .unique
          .transact(xa)
        status  <- sql"SELECT status FROM beta_requests WHERE email='pend@example.com'"
          .query[String]
          .unique
          .transact(xa)
      } yield assertTrue(s1 == Status.Ok, s2 == Status.Ok) &&
        assertTrue(count == 1L, status == "pending") &&
        // A pending duplicate sends NO invite email.
        assertTrue(sent.isEmpty)
    },
    test(
      "#2222 — second request from an APPROVED applicant re-mints + re-sends the invite (no new row)",
    ) {
      for {
        _   <- cleanDb
        ctl <- TestClock.makeWithControl(TestClock.schoolDayAfternoon)
        (bc, _) = ctl
        ref           <- Ref.make(List.empty[EmailSender.Sent])
        notifier      <- emailNotifier(EmailSender.recording(ref))
        b             <- build(bc, notifier)
        xa            <- ZIO.service[Transactor[Task]]
        opToken       <- login(b.auth, "admin", "changeme")
        _             <- postJson(
          b.routes,
          "/api/beta/request",
          None,
          CreateBetaRequest("appr@example.com", Some("Appr Family")).toJson,
        )
        (_, listBody) <- getJson(b.routes, "/api/operator/beta-requests", opToken)
        reqId         <- ZIO
          .fromEither(listBody.fromJson[List[BetaRequestSummary]])
          .mapError(new RuntimeException(_))
          .map(_.head.id)
        _             <- postJson(
          b.routes,
          s"/api/operator/beta-requests/${reqId.value}/approve",
          Some(opToken),
          "",
        )
        hash0     <- sql"SELECT invite_token_hash FROM beta_requests WHERE email='appr@example.com'"
          .query[String]
          .unique
          .transact(xa)
        // Ignore the approve-time invite — measure only the resend triggered by the duplicate intake.
        _         <- ref.set(Nil)
        (sDup, _) <- postJson(
          b.routes,
          "/api/beta/request",
          None,
          CreateBetaRequest("appr@example.com", Some("Appr Family")).toJson,
        )
        sent      <- ref.get
        hash1     <- sql"SELECT invite_token_hash FROM beta_requests WHERE email='appr@example.com'"
          .query[String]
          .unique
          .transact(xa)
        count     <- sql"SELECT COUNT(*) FROM beta_requests WHERE email='appr@example.com'"
          .query[Long]
          .unique
          .transact(xa)
      } yield assertTrue(sDup == Status.Ok) &&
        // No duplicate row despite the second submit.
        assertTrue(count == 1L) &&
        // The invite was re-sent exactly once, to the requester's address, with a fresh /welcome link.
        assertTrue(sent.size == 1) &&
        assertTrue(sent.head.to == "appr@example.com") &&
        assertTrue(sent.head.htmlBody.contains("/welcome?token=")) &&
        // The token was re-minted (only the hash is stored — the prior link can't be reused).
        assertTrue(hash1 != hash0)
    },
    test(
      "#2222 — second request from a REJECTED applicant is a silent no-op — no new row, no invite, no reopen",
    ) {
      for {
        _   <- cleanDb
        ctl <- TestClock.makeWithControl(TestClock.schoolDayAfternoon)
        (bc, _) = ctl
        ref           <- Ref.make(List.empty[EmailSender.Sent])
        notifier      <- emailNotifier(EmailSender.recording(ref))
        b             <- build(bc, notifier)
        xa            <- ZIO.service[Transactor[Task]]
        opToken       <- login(b.auth, "admin", "changeme")
        _             <- postJson(
          b.routes,
          "/api/beta/request",
          None,
          CreateBetaRequest("rej@example.com", Some("Rej Family")).toJson,
        )
        (_, listBody) <- getJson(b.routes, "/api/operator/beta-requests", opToken)
        reqId         <- ZIO
          .fromEither(listBody.fromJson[List[BetaRequestSummary]])
          .mapError(new RuntimeException(_))
          .map(_.head.id)
        _             <- postJson(
          b.routes,
          s"/api/operator/beta-requests/${reqId.value}/reject",
          Some(opToken),
          "",
        )
        _             <- ref.set(Nil)
        (sDup, _)     <- postJson(
          b.routes,
          "/api/beta/request",
          None,
          CreateBetaRequest("rej@example.com", Some("Rej Family")).toJson,
        )
        sent          <- ref.get
        count         <- sql"SELECT COUNT(*) FROM beta_requests WHERE email='rej@example.com'"
          .query[Long]
          .unique
          .transact(xa)
        status        <- sql"SELECT status FROM beta_requests WHERE email='rej@example.com'"
          .query[String]
          .unique
          .transact(xa)
      } yield assertTrue(sDup == Status.Ok) &&
        // No new row, no auto-reopen (stays rejected), no email.
        assertTrue(count == 1L, status == "rejected", sent.isEmpty)
    },
    test(
      "#2222 — intake response is byte-identical across new / pending / approved / rejected (no enumeration)",
    ) {
      for {
        _   <- cleanDb
        ctl <- TestClock.makeWithControl(TestClock.schoolDayAfternoon)
        (bc, _) = ctl
        b        <- build(bc)
        opToken  <- login(b.auth, "admin", "changeme")
        // (a) brand-new email.
        newResp  <- postJson(
          b.routes,
          "/api/beta/request",
          None,
          CreateBetaRequest("new@example.com", Some("New Fam")).toJson,
        )
        // (b) pending duplicate.
        _        <- postJson(
          b.routes,
          "/api/beta/request",
          None,
          CreateBetaRequest("pend@example.com", Some("Pend Fam")).toJson,
        )
        pendResp <- postJson(
          b.routes,
          "/api/beta/request",
          None,
          CreateBetaRequest("pend@example.com", Some("Pend Fam")).toJson,
        )
        // (c) approved duplicate.
        _        <- postJson(
          b.routes,
          "/api/beta/request",
          None,
          CreateBetaRequest("appr2@example.com", Some("Appr2 Fam")).toJson,
        )
        (_, lb)  <- getJson(b.routes, "/api/operator/beta-requests", opToken)
        apprId   <- ZIO
          .fromEither(lb.fromJson[List[BetaRequestSummary]])
          .mapError(new RuntimeException(_))
          .map(_.find(_.email == "appr2@example.com").get.id)
        _        <- postJson(
          b.routes,
          s"/api/operator/beta-requests/${apprId.value}/approve",
          Some(opToken),
          "",
        )
        apprResp <- postJson(
          b.routes,
          "/api/beta/request",
          None,
          CreateBetaRequest("appr2@example.com", Some("Appr2 Fam")).toJson,
        )
        // (d) rejected duplicate.
        _        <- postJson(
          b.routes,
          "/api/beta/request",
          None,
          CreateBetaRequest("rej2@example.com", Some("Rej2 Fam")).toJson,
        )
        (_, lb2) <- getJson(b.routes, "/api/operator/beta-requests", opToken)
        rejId    <- ZIO
          .fromEither(lb2.fromJson[List[BetaRequestSummary]])
          .mapError(new RuntimeException(_))
          .map(_.find(_.email == "rej2@example.com").get.id)
        _        <- postJson(
          b.routes,
          s"/api/operator/beta-requests/${rejId.value}/reject",
          Some(opToken),
          "",
        )
        rejResp  <- postJson(
          b.routes,
          "/api/beta/request",
          None,
          CreateBetaRequest("rej2@example.com", Some("Rej2 Fam")).toJson,
        )
      } yield
      // Every second-submit response — regardless of the target email's state — is byte-identical in
      // both status and body, so the endpoint reveals nothing about which emails exist or their state.
      assertTrue(newResp == pendResp, pendResp == apprResp, apprResp == rejResp)
    },
  ) @@ TestAspect.sequential
}
