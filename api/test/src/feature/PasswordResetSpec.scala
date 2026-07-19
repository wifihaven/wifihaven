package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.EmailConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.notify.{EmailSender, Notifier}
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.Clock.TestClock
import wifihaven.shared.types.HouseholdId
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*

/**
 * #2308: forgot-password / reset-via-email-link flow. Full-stack feature tests on embedded Postgres
 * (no repo mocks) with a TestClock for the token TTL and a recording [[EmailSender]] to observe the
 * emailed reset link without a network.
 *
 * The enumeration-safety guarantee is exercised structurally: a registered and an unregistered
 * email both drive the same `requestReset` effect and differ only in the server-side side effect
 * (an email to a real inbox) — the route returns a byte-identical generic 200 either way (asserted
 * in the route-level portion). The token proves nothing about account existence; a used/expired
 * token is rejected identically to an unknown one.
 */
object PasswordResetSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val jwtCfg   =
    JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)
  private val emailCfg =
    EmailConfig(
      enabled = true,
      resendApiKey = "re_test",
      fromAddress = "WifiHaven <alerts@wifihaven.net>",
      appBaseUrl = "https://app.example.com",
    )

  private val ParentEmail  = "parent@example.com"
  private val OrigPassword = "originalpass1"
  private val NewPassword  = "brandnewpassword1"

  private val TokenRe                                    = """token=([^"&]+)""".r
  private def extractToken(body: String): Option[String] =
    TokenRe.findFirstMatchIn(body).map(_.group(1))

  // Build the reset service against the real repos + a recording email sender. Returns the pieces a
  // test drives plus the `Ref` capturing sent emails.
  private def setup =
    for {
      _       <- cleanDb
      ur      <- ZIO.service[UserRepo]
      tr      <- ZIO.service[PasswordResetTokenRepo]
      hsRepo  <- ZIO.service[HouseholdSettingsRepo]
      clock   <- ZIO.service[Clock]
      sentRef <- Ref.make(List.empty[EmailSender.Sent])
      auth     = AuthServiceLive(ur, jwtCfg, clock)
      notifier = Notifier.EmailNotifier(hsRepo, EmailSender.recording(sentRef), emailCfg)
      svc      = PasswordResetServiceLive(ur, tr, auth, notifier, emailCfg, clock)
    } yield Env(ur, tr, clock, sentRef, auth, svc)

  private case class Env(
      ur: UserRepo,
      tr: PasswordResetTokenRepo,
      clock: Clock,
      sentRef: Ref[List[EmailSender.Sent]],
      auth: AuthService,
      svc: PasswordResetService,
  )

  // Create the household-1 admin `parent` with a known email + password.
  private def seedParent(env: Env): Task[Unit] =
    for {
      hash <- env.auth.hashPassword(OrigPassword)
      _    <- env.ur.create("parent", hash, "admin", HouseholdId.Default, Some(ParentEmail))
    } yield ()

  private def advance(clock: Clock, d: java.time.Duration): UIO[Unit] =
    clock match {
      case tc: TestClock => tc.advance(d)
      case _             => ZIO.unit
    }

  private def forgotReq(email: String, ip: String = "203.0.113.10") =
    Request
      .post(
        URL.decode("/api/auth/forgot-password").toOption.get,
        Body.fromString(ForgotPasswordRequest(email).toJson),
      )
      .addHeader(Header.ContentType(MediaType.application.json))
      .addHeader("X-Forwarded-For", ip)

  private def resetReq(token: String, newPassword: String, ip: String = "203.0.113.11") =
    Request
      .post(
        URL.decode("/api/auth/reset-password").toOption.get,
        Body.fromString(ResetPasswordRequest(token, newPassword).toJson),
      )
      .addHeader(Header.ContentType(MediaType.application.json))
      .addHeader("X-Forwarded-For", ip)

  def spec = suite("password reset (forgot-password flow)")(
    test("request for a registered email mints a token and emails the reset link") {
      for {
        env  <- setup
        _    <- seedParent(env)
        _    <- env.svc.requestReset(ParentEmail)
        sent <- env.sentRef.get
        one   = sent.headOption
        // The token row exists for the emailed link's token.
        token = one.flatMap(s => extractToken(s.htmlBody))
        row <- ZIO.foreach(token)(t =>
          env.tr.findByHash(wifihaven.api.policy.PolicyService.hashToken(t)),
        )
      } yield assertTrue(sent.length == 1) &&
        assertTrue(one.exists(_.to == ParentEmail)) &&
        assertTrue(one.exists(_.subject == "Reset your WifiHaven password")) &&
        assertTrue(one.exists(_.htmlBody.contains("/reset-password?token=pr_"))) &&
        assertTrue(token.isDefined) &&
        assertTrue(row.exists(_.exists(_.usedAt.isEmpty)))
    },
    test("request for an unregistered email sends nothing (no enumeration)") {
      for {
        env  <- setup
        _    <- seedParent(env)
        _    <- env.svc.requestReset("nobody@example.com")
        sent <- env.sentRef.get
      } yield assertTrue(sent.isEmpty)
    },
    test("request resolves the email case-insensitively") {
      for {
        env  <- setup
        _    <- seedParent(env)
        _    <- env.svc.requestReset("PARENT@Example.COM")
        sent <- env.sentRef.get
      } yield assertTrue(sent.length == 1) && assertTrue(sent.head.to == ParentEmail)
    },
    test("a valid token sets the new password, marks the token used, and invalidates old JWTs") {
      for {
        env       <- setup
        _         <- seedParent(env)
        oldToken  <- env.auth.login(ParentEmail, OrigPassword).map(_.token.value)
        preClaims <- env.auth.verify(oldToken)
        _         <- env.svc.requestReset(ParentEmail)
        sent      <- env.sentRef.get
        token = extractToken(sent.head.htmlBody).getOrElse("")
        _         <- env.svc.resetPassword(token, NewPassword)
        // Old JWT is revoked (token_version bumped, #2080).
        oldResult <- env.auth.verify(oldToken).either
        // The new password authenticates.
        relogin   <- env.auth.login(ParentEmail, NewPassword).either
        // The old password no longer authenticates.
        oldLogin  <- env.auth.login(ParentEmail, OrigPassword).either
        // The token is marked used (single-use).
        row       <- env.tr.findByHash(wifihaven.api.policy.PolicyService.hashToken(token))
      } yield assertTrue(preClaims.sub == "parent") &&
        assertTrue(oldResult == Left(AuthError.TokenRevoked)) &&
        assertTrue(relogin.isRight) &&
        assertTrue(oldLogin == Left(AuthError.InvalidCredentials)) &&
        assertTrue(row.exists(_.usedAt.isDefined))
    },
    test("a used token is rejected on a second reset") {
      for {
        env  <- setup
        _    <- seedParent(env)
        _    <- env.svc.requestReset(ParentEmail)
        sent <- env.sentRef.get
        token = extractToken(sent.head.htmlBody).getOrElse("")
        _      <- env.svc.resetPassword(token, NewPassword)
        second <- env.svc.resetPassword(token, "yetanotherpass1").either
      } yield assertTrue(second == Left(PasswordResetError.InvalidToken))
    },
    test("an expired token is rejected") {
      for {
        env  <- setup
        _    <- seedParent(env)
        _    <- env.svc.requestReset(ParentEmail)
        sent <- env.sentRef.get
        token = extractToken(sent.head.htmlBody).getOrElse("")
        // Advance past the 30-minute TTL.
        _      <- advance(env.clock, java.time.Duration.ofMinutes(31))
        result <- env.svc.resetPassword(token, NewPassword).either
      } yield assertTrue(result == Left(PasswordResetError.Expired))
    },
    test("an unknown token is rejected") {
      for {
        env    <- setup
        _      <- seedParent(env)
        result <- env.svc.resetPassword("pr_not-a-real-token", NewPassword).either
      } yield assertTrue(result == Left(PasswordResetError.InvalidToken))
    },
    test("a weak new password is rejected WITHOUT consuming the token") {
      for {
        env  <- setup
        _    <- seedParent(env)
        _    <- env.svc.requestReset(ParentEmail)
        sent <- env.sentRef.get
        token = extractToken(sent.head.htmlBody).getOrElse("")
        weak <- env.svc.resetPassword(token, "short").either
        // The token is still usable — a weak attempt must not burn the single-use link.
        good <- env.svc.resetPassword(token, NewPassword).either
      } yield assertTrue(weak == Left(PasswordResetError.WeakPassword)) &&
        assertTrue(good.isRight)
    },
    // ── Route-level: the enumeration-safe generic 200 + rate limit + status mapping ─────
    test(
      "POST /forgot-password returns a byte-identical generic 200 for registered and unregistered emails",
    ) {
      for {
        env <- setup
        _   <- seedParent(env)
        routes = PasswordResetRoutes.routes(env.svc, RateLimiter.allowAll, RateLimiter.allowAll)
        knownResp   <- routes.runZIO(forgotReq(ParentEmail))
        knownBody   <- knownResp.body.asString
        unknownResp <- routes.runZIO(forgotReq("nobody@example.com"))
        unknownBody <- unknownResp.body.asString
        // The registered email still triggers a send; the unregistered one does not — but the
        // responses are identical.
        sent        <- env.sentRef.get
      } yield assertTrue(knownResp.status == Status.Ok) &&
        assertTrue(unknownResp.status == Status.Ok) &&
        assertTrue(knownBody == unknownBody) &&
        assertTrue(knownBody == ForgotPasswordAck().toJson) &&
        assertTrue(sent.length == 1)
    },
    test("POST /forgot-password is rate-limited (429 once the window is exhausted)") {
      for {
        env     <- setup
        limiter <- RateLimiterLive.make(maxAttempts = 1, windowSeconds = 3600)
        routes = PasswordResetRoutes.routes(env.svc, limiter, RateLimiter.allowAll)
        first   <- routes.runZIO(forgotReq(ParentEmail, "203.0.113.20"))
        blocked <- routes.runZIO(forgotReq(ParentEmail, "203.0.113.20"))
      } yield assertTrue(first.status == Status.Ok) &&
        assertTrue(blocked.status == Status.TooManyRequests)
    },
    test("POST /reset-password 400s an invalid token and 200s a valid one") {
      for {
        env <- setup
        _   <- seedParent(env)
        routes = PasswordResetRoutes.routes(env.svc, RateLimiter.allowAll, RateLimiter.allowAll)
        bad  <- routes.runZIO(resetReq("pr_bogus", NewPassword))
        _    <- env.svc.requestReset(ParentEmail)
        sent <- env.sentRef.get
        token = extractToken(sent.head.htmlBody).getOrElse("")
        okResp <- routes.runZIO(resetReq(token, NewPassword))
      } yield assertTrue(bad.status == Status.BadRequest) &&
        assertTrue(okResp.status == Status.Ok)
    },
  ) @@ TestAspect.sequential
}
