package wifihaven.api.db

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import wifihaven.shared.types.*
import wifihaven.api.db.TypeMeta.given
import zio.*
import zio.interop.catz.*

import java.time.Instant

// ── Support data-access consent (#2419, epic #2197) ──────────────────────────
// The `support_thread_consent` table (V84): the SERVER-SIDE record that a customer explicitly
// allowed the support assistant to read their OWN household summary for ONE Plain thread. A
// dispatch for a thread with a live grant mints the #2241 agent token with `dataAccess=true`;
// without one the household-read endpoint refuses the token.
//
// SECURITY (docs/ops/support-data-consent.md): the ONLY writer is the customer's own
// JWT-authenticated grant action. Consent is never inferred from the untrusted inbound message
// text, and the agent — whose sole credential is the thread-bound token — has no path into this
// repo. `(household, thread)` is the whole key and is supplied by the caller from already-verified
// state, so a household-A grant can never widen a household-B token.

/**
 * A live consent grant as the dispatch path needs it. Deliberately projects only the window
 * (`expiresAt` / `revokedAt`) and the audit pointer; the `(household, thread)` key is what the
 * caller already had in hand.
 */
final case class DbSupportThreadConsent(
    grantedByUserId: Option[UserId],
    grantedAt: Instant,
    expiresAt: Instant,
    revokedAt: Option[Instant],
)

trait SupportConsentRepo {

  /**
   * Record (or refresh) the customer's grant for `(household, thread)`, live until `expiresAt`.
   * UPSERTs on the UNIQUE `(household_id, thread_id)` key — a re-grant after expiry or revocation
   * refreshes the window and CLEARS `revoked_at` rather than accumulating history rows.
   * `grantedByUserId` is the audit trail (which admin granted).
   */
  def grant(
      household: HouseholdId,
      threadId: String,
      grantedByUserId: Option[UserId],
      now: Instant,
      expiresAt: Instant,
  ): Task[Unit]

  /**
   * Withdraw consent ahead of expiry (the customer's "stop allowing" action). Stamps `revoked_at`
   * on a live row; returns true iff a live grant was actually revoked (so a double-revoke, or a
   * revoke of a thread that never consented, is observably a no-op rather than a silent success).
   */
  def revoke(household: HouseholdId, threadId: String, now: Instant): Task[Boolean]

  /**
   * Is there a LIVE grant for `(household, thread)` as of `now` — i.e. a row that is neither
   * revoked nor expired? This is the single predicate the dispatch path consults to decide the
   * token's `dataAccess` scope. Both key columns are bound, so consent on another thread or another
   * household can never satisfy it.
   */
  def isGranted(household: HouseholdId, threadId: String, now: Instant): Task[Boolean]

  /** Read the raw row (live or not) — the audit/read-back path used by tests and diagnostics. */
  def find(household: HouseholdId, threadId: String): Task[Option[DbSupportThreadConsent]]
}

class SupportConsentRepoLive(xa: Transactor[Task]) extends SupportConsentRepo {

  def grant(
      household: HouseholdId,
      threadId: String,
      grantedByUserId: Option[UserId],
      now: Instant,
      expiresAt: Instant,
  ): Task[Unit] =
    sql"""INSERT INTO support_thread_consent
            (household_id, thread_id, granted_by_user_id, granted_at, expires_at, revoked_at)
          VALUES ($household, $threadId, $grantedByUserId, $now, $expiresAt, NULL)
          ON CONFLICT (household_id, thread_id) DO UPDATE
            SET granted_by_user_id = EXCLUDED.granted_by_user_id,
                granted_at         = EXCLUDED.granted_at,
                expires_at         = EXCLUDED.expires_at,
                revoked_at         = NULL""".update.run
      .transact(xa)
      .unit

  def revoke(household: HouseholdId, threadId: String, now: Instant): Task[Boolean] =
    sql"""UPDATE support_thread_consent SET revoked_at = $now
          WHERE household_id = $household AND thread_id = $threadId
            AND revoked_at IS NULL AND expires_at > $now""".update.run
      .transact(xa)
      .map(_ == 1)

  def isGranted(household: HouseholdId, threadId: String, now: Instant): Task[Boolean] =
    sql"""SELECT 1 FROM support_thread_consent
          WHERE household_id = $household AND thread_id = $threadId
            AND revoked_at IS NULL AND expires_at > $now"""
      .query[Int]
      .option
      .transact(xa)
      .map(_.isDefined)

  def find(household: HouseholdId, threadId: String): Task[Option[DbSupportThreadConsent]] =
    sql"""SELECT granted_by_user_id, granted_at, expires_at, revoked_at
          FROM support_thread_consent
          WHERE household_id = $household AND thread_id = $threadId"""
      .query[(Option[UserId], Instant, Instant, Option[Instant])]
      .map { case (u, g, e, r) => DbSupportThreadConsent(u, g, e, r) }
      .option
      .transact(xa)
}
