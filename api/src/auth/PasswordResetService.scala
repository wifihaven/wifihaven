package wifihaven.api.auth

import wifihaven.api.EmailConfig
import wifihaven.api.db.*
import wifihaven.api.metrics.AppMetrics
import wifihaven.api.notify.Notifier
import wifihaven.api.observability.LogContext
import wifihaven.api.policy.PolicyService
import wifihaven.shared.*
import zio.{Clock as _, *}

import java.security.SecureRandom
import java.util.Base64

// ── Forgot / reset password (#2308, epic #622) ──────────────────────────────
// The self-service recovery path for a household admin who forgot their password. Two effects behind
// the two unauthenticated `/api/auth/*` routes:
//   - requestReset(email): resolve the email → user, mint a single-use short-TTL token, email the
//     `/reset-password?token=…` link. ALWAYS succeeds and is content-free — a registered and an
//     unregistered email are indistinguishable (no account enumeration).
//   - resetPassword(token, newPassword): validate the token, enforce the #2084 password policy, set
//     the new bcrypt hash, mark the token used (single-use), and bump `token_version` (#2080) so the
//     user's existing JWTs are invalidated.

/** Typed failures the reset route maps to HTTP statuses. `requestReset` never fails (fail-open). */
sealed trait PasswordResetError
object PasswordResetError {
  // Unknown / already-used token, or a lost single-use race. Never distinguished from expiry in the
  // response (no enumeration) — the split exists only for the outcome metric.
  case object InvalidToken        extends PasswordResetError
  case object Expired             extends PasswordResetError
  // New password below the #2084 minimum. Checked BEFORE the token is consumed, so a weak-password
  // attempt does not burn the link — the user can retry with the same email.
  case object WeakPassword        extends PasswordResetError
  case class Db(cause: Throwable) extends PasswordResetError
}

trait PasswordResetService {

  /**
   * Handle a forgot-password request for `email`. Resolves the (globally-unique) email to a user
   * and, if found, mints a single-use short-TTL token + emails the reset link. ALWAYS returns Unit
   * and is content-free — the caller can't tell whether the address is registered. Never fails: a
   * DB/email hiccup is logged and swallowed so the response stays generic even on error (no
   * enumeration via status codes either).
   */
  def requestReset(email: String): UIO[Unit]

  /**
   * Consume a reset token and set the new password. Fails with a typed [[PasswordResetError]] the
   * route maps to a 400 (invalid/expired → generic "invalid or expired reset link"; weak → the
   * policy message). On success: sets the bcrypt hash, marks the token used (single-use), clears
   * must_change_password, and bumps token_version (invalidating every prior JWT).
   */
  def resetPassword(token: String, newPassword: String): IO[PasswordResetError, Unit]
}

class PasswordResetServiceLive(
    userRepo: UserRepo,
    tokenRepo: PasswordResetTokenRepo,
    auth: AuthService,
    notifier: Notifier,
    emailCfg: EmailConfig,
    clock: Clock,
) extends PasswordResetService {

  import PasswordResetService.*

  // A fresh 256-bit SecureRandom token, url-safe base64, `pr_`-prefixed. Only the raw token is
  // emailed (in the reset link); only its SHA-256 hash is ever persisted. Mirrors BetaService's
  // invite-token recipe.
  private def newResetToken(): String = {
    val bytes = new Array[Byte](32)
    new SecureRandom().nextBytes(bytes)
    "pr_" + Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
  }

  def requestReset(email: String): UIO[Unit] = {
    // Match the `users.email` write-side contract (Repos.scala): emails are stored lowercase-
    // normalized, so normalize the lookup key the same way (the login path does this too).
    val normalized = email.trim.toLowerCase
    LogContext.annotate(LogContext.User, normalized) {
      (for {
        now  <- clock.instant
        // Opportunistic housekeeping — best-effort, never blocks the response on failure.
        _    <- tokenRepo.deleteExpired(now).ignore
        user <- userRepo.findByEmail(normalized)
        _    <- user match {
          case None    =>
            // Unregistered address: send nothing. INTERNAL metric only — the route returns the SAME
            // generic 200, so this is not observable to the caller.
            AppMetrics.passwordReset("request_no_account")
          case Some(u) =>
            val rawToken = newResetToken()
            val hash     = PolicyService.hashToken(rawToken)
            val expires  = now.plus(PasswordResetService.TokenTtl)
            tokenRepo.create(u.id, hash, expires) *>
              notifier.passwordReset(
                normalized,
                emailCfg.passwordResetUrl(rawToken),
                PasswordResetService.TtlMinutes,
              ) *>
              AppMetrics.passwordReset("request_sent")
        }
      } yield ())
        // Fail-open: never let a DB/transport error surface (or change timing branch observably) —
        // log it and return the same generic Unit. The response is content-free regardless.
        .catchAll(e =>
          ZIO.logWarning(s"password reset request errored: ${e.getMessage}") *>
            AppMetrics.passwordReset("request_no_account"),
        )
    }
  }

  def resetPassword(token: String, newPassword: String): IO[PasswordResetError, Unit] =
    for {
      // Enforce the password policy FIRST so a weak password doesn't consume the single-use token.
      _ <- ZIO
        .fail(PasswordResetError.WeakPassword)
        .when(!AuthService.isPasswordStrongEnough(newPassword))
        .tapError(_ => AppMetrics.passwordReset("reset_weak_password"))
      hash = PolicyService.hashToken(token)
      now      <- clock.instant
      // A wrong/forged/consumed token resolves to nothing → InvalidToken (never distinguish "unknown"
      // from "already used" in the response — no enumeration signal).
      rowOpt   <- tokenRepo.findByHash(hash).mapError(PasswordResetError.Db(_))
      row      <- ZIO
        .fromOption(rowOpt)
        .orElseFail(PasswordResetError.InvalidToken)
        .tapError(_ => AppMetrics.passwordReset("reset_invalid_token"))
      _        <- ZIO
        .fail(PasswordResetError.InvalidToken)
        .when(row.usedAt.isDefined)
        .tapError(_ => AppMetrics.passwordReset("reset_invalid_token"))
      _        <- ZIO
        .fail(PasswordResetError.Expired)
        .when(now.isAfter(row.expiresAt))
        .tapError(_ => AppMetrics.passwordReset("reset_expired"))
      // Atomic single-use consume: only one concurrent reset of the same link can win. A false here
      // means we lost the race (or it lapsed in between) → treat as invalid (same generic response).
      consumed <- tokenRepo.markUsed(hash, now).mapError(PasswordResetError.Db(_))
      _        <- ZIO
        .fail(PasswordResetError.InvalidToken)
        .when(!consumed)
        .tapError(_ => AppMetrics.passwordReset("reset_invalid_token"))
      // Apply the new password via the shared rotation primitive: hash + store + clear
      // must_change_password + bump token_version (#2080, revoking prior JWTs). Single source of
      // truth with AuthService.changePassword — the invariant can't drift between the two paths.
      _        <- auth.setPassword(row.userId, newPassword).mapError(PasswordResetError.Db(_))
      _        <- AppMetrics.passwordReset("reset_ok")
    } yield ()
}

object PasswordResetService {

  // #2308: the reset-token lifetime — short by design (single-use recovery link). 30 min sits inside
  // the issue's ~30–60 min guidance. Single-sourced: both the expiry stamp (TokenTtl) and the email
  // body's "expires in N minutes" wording (TtlMinutes) derive from this one value.
  val TtlMinutes: Int    = 30
  val TokenTtl: Duration = Duration.fromSeconds(TtlMinutes.toLong * 60)
}
