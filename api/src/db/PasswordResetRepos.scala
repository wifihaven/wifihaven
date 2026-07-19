package wifihaven.api.db

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import wifihaven.shared.types.*
import wifihaven.api.db.TypeMeta.given
import zio.*
import zio.interop.catz.*

import java.time.Instant

// ── Forgot / reset password (#2308, epic #622) ──────────────────────────────
// The `password_reset_tokens` table (V77): single-use, short-TTL tokens that back the
// forgot-password / reset-via-email-link flow (design: issue #2308). Only the token HASH is stored;
// the plaintext rides in the emailed reset link and is never persisted, so a DB read cannot
// reconstruct a usable token (mirrors the beta invite-token discipline).

/**
 * A `password_reset_tokens` row as the reset flow needs it. Deliberately projects only what the
 * consume path checks — the owning user, the TTL boundary, and whether the token has already been
 * used. The `token_hash` is the lookup key (never re-emitted); `id`/`created_at` are internal.
 */
case class DbPasswordResetToken(
    userId: UserId,
    expiresAt: Instant,
    usedAt: Option[Instant],
)

trait PasswordResetTokenRepo {

  /**
   * Mint a reset token for `userId`: store its `tokenHash` (SHA-256 of the raw token) with the TTL
   * boundary `expiresAt`. `used_at` starts NULL. The UNIQUE(token_hash) constraint makes a hash
   * collision (astronomically unlikely for a 256-bit token) a loud failure rather than a silent
   * overwrite.
   */
  def create(userId: UserId, tokenHash: Sha256Hex, expiresAt: Instant): Task[Unit]

  /**
   * Look up a token by its hash. Returns the row (with its expiry + used state) or None for an
   * unknown hash. The service distinguishes unknown / used / expired purely for the outcome metric
   * — the response is identical for all rejection reasons (no enumeration).
   */
  def findByHash(tokenHash: Sha256Hex): Task[Option[DbPasswordResetToken]]

  /**
   * Atomically consume the token: stamp `used_at = now`, but ONLY if it is still unused and
   * unexpired. Returns true iff this call actually consumed it — so two concurrent resets of the
   * same link can never both succeed (single-use is enforced at the DB, not by a check-then-act
   * race). The guard also re-checks expiry so a token that lapses between the read and the consume
   * is rejected.
   */
  def markUsed(tokenHash: Sha256Hex, now: Instant): Task[Boolean]

  /**
   * Opportunistic housekeeping: delete tokens already past their TTL. Bounds table growth without a
   * separate sweep job — called best-effort from the request path. Returns the row count deleted.
   */
  def deleteExpired(now: Instant): Task[Int]
}

class PasswordResetTokenRepoLive(xa: Transactor[Task]) extends PasswordResetTokenRepo {

  def create(userId: UserId, tokenHash: Sha256Hex, expiresAt: Instant): Task[Unit] =
    sql"""INSERT INTO password_reset_tokens(token_hash, user_id, expires_at)
          VALUES($tokenHash, $userId, $expiresAt)""".update.run
      .transact(xa)
      .unit

  def findByHash(tokenHash: Sha256Hex): Task[Option[DbPasswordResetToken]] =
    sql"""SELECT user_id, expires_at, used_at
          FROM password_reset_tokens WHERE token_hash=$tokenHash"""
      .query[(UserId, Instant, Option[Instant])]
      .map { case (uid, exp, used) => DbPasswordResetToken(uid, exp, used) }
      .option
      .transact(xa)

  def markUsed(tokenHash: Sha256Hex, now: Instant): Task[Boolean] =
    sql"""UPDATE password_reset_tokens SET used_at=$now
          WHERE token_hash=$tokenHash AND used_at IS NULL AND expires_at > $now""".update.run
      .transact(xa)
      .map(_ == 1)

  def deleteExpired(now: Instant): Task[Int] =
    sql"DELETE FROM password_reset_tokens WHERE expires_at <= $now".update.run
      .transact(xa)
}
