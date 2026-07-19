package wifihaven.api.routes

import wifihaven.api.auth.*
import wifihaven.shared.*
import zio.*
import zio.http.*
import zio.json.*

/**
 * #2308 (epic #622): the forgot-password / reset-via-email-link flow. Two PUBLIC, unauthenticated,
 * rate-limited endpoints:
 *
 *   - `POST /api/auth/forgot-password` — request a reset link by email. ALWAYS returns the same
 *     generic 200 [[ForgotPasswordAck]] whether or not the email is registered (no account
 *     enumeration; the enumeration-safe posture matches the beta intake `POST /api/beta/request`,
 *     which also emails only when a record exists and returns a content-free 200 either way).
 *   - `POST /api/auth/reset-password` — consume the single-use, short-TTL token, enforce the #2084
 *     password policy, set the new hash, and bump `token_version` (#2080) so existing JWTs are
 *     invalidated. Invalid / expired tokens collapse to one generic 400.
 *
 * Both are rate-limited per source IP ([[wifihaven.api.auth.RateLimiter]], #2079): forgot-password
 * defends against email-bombing a known address, reset-password against token brute-force.
 */
object PasswordResetRoutes {

  // RFC 5321 max — cap the attacker-controlled email so a giant body can't be used as an amplifier.
  private val MaxEmailLength: Int = 320

  def routes(
      svc: PasswordResetService,
      forgotRateLimiter: RateLimiter,
      resetRateLimiter: RateLimiter,
  ): Routes[Any, Response] =
    Routes(
      // ── Request a reset link (public, rate-limited, enumeration-safe) ─────────
      Method.POST / "api" / "auth" / "forgot-password" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            allowed <- forgotRateLimiter.tryAcquire(s"forgot-password:${clientIp(req)}")
            _       <- ZIO
              .fail(ApiError.RateLimited("Too many requests; try again later"))
              .unless(allowed)
            body    <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            fr      <- ZIO
              .fromEither(body.fromJson[ForgotPasswordRequest])
              .mapError(ApiError.DecodeFailure(_))
            // Fire the (fail-open) request effect. It resolves the email → user, mints + emails a
            // token if registered, and is content-free either way — so the response below is
            // byte-identical regardless of whether the address exists.
            _       <- svc.requestReset(fr.email.take(MaxEmailLength))
          } yield Response.json(ForgotPasswordAck().toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },

      // ── Consume the token + set the new password (public, rate-limited) ───────
      Method.POST / "api" / "auth" / "reset-password" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            allowed <- resetRateLimiter.tryAcquire(s"reset-password:${clientIp(req)}")
            _       <- ZIO
              .fail(ApiError.RateLimited("Too many requests; try again later"))
              .unless(allowed)
            body    <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            rr      <- ZIO
              .fromEither(body.fromJson[ResetPasswordRequest])
              .mapError(ApiError.DecodeFailure(_))
            _       <- svc.resetPassword(rr.token, rr.newPassword).mapError(resetErrorToApi)
          } yield Response.json(ResetPasswordResponse().toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
    )

  // Invalid and expired both collapse to ONE generic 400 (no enumeration — the token reveals
  // nothing about whether it ever existed). Weak password surfaces the policy message so the user
  // can fix it. DB failure stays a 503 via ApiError.Db.
  private def resetErrorToApi(e: PasswordResetError): ApiError = e match {
    case PasswordResetError.InvalidToken => ApiError.BadRequest("invalid or expired reset link")
    case PasswordResetError.Expired      => ApiError.BadRequest("invalid or expired reset link")
    case PasswordResetError.WeakPassword =>
      ApiError.BadRequest(s"password must be at least ${AuthService.MinPasswordLength} characters")
    case PasswordResetError.Db(c)        => ApiError.Db(c)
  }
}
