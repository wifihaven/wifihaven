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

trait SupportConsentRepo {

  /**
   * Record (or refresh) the customer's grant for `(household, thread)`, live until `expiresAt`.
   * UPSERTs on the UNIQUE `(household_id, thread_id)` key — a re-grant after expiry or revocation
   * refreshes the window and CLEARS `revoked_at` rather than accumulating history rows.
   * `grantedByUserId` is the audit trail (which admin granted).
   *
   * Returns TRUE iff this call TRANSITIONED the pair from no-live-grant to live — i.e. there was no
   * row, or the row was revoked/expired. A re-confirmation of a grant that was already live returns
   * false. #2460 keys the consent RESUME (the server re-dispatching the customer's question) off
   * that transition, so it must be decided by the same statement that writes: a separate
   * read-then-write would let two concurrent Allow clicks both observe "not live" and both
   * re-dispatch, double-answering the customer.
   */
  def grant(
      household: HouseholdId,
      threadId: String,
      grantedByUserId: Option[UserId],
      now: Instant,
      expiresAt: Instant,
  ): Task[Boolean]

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
}

class SupportConsentRepoLive(xa: Transactor[Task]) extends SupportConsentRepo {

  def grant(
      household: HouseholdId,
      threadId: String,
      grantedByUserId: Option[UserId],
      now: Instant,
      expiresAt: Instant,
  ): Task[Boolean] =
    // ONE transaction decides both the write and whether it was a TRANSITION (#2460). The prior
    // state is read `FOR UPDATE`, so the row lock is held across the upsert: a second grant for the
    // same pair blocks until this commits and then observes the LIVE row, reporting `false`. (Two
    // simultaneous FIRST grants — no row to lock yet — can still both report `true`; they carry the
    // same question, and the dispatch caps bound the cost.) Deliberately two statements rather than
    // a CTE: a read CTE alongside a data-modifying one does not reliably observe the pre-write
    // state, which is the whole signal here.
    (for {
      prev <- sql"""SELECT (revoked_at IS NULL AND expires_at > $now)
                      FROM support_thread_consent
                     WHERE household_id = $household AND thread_id = $threadId
                     FOR UPDATE""".query[Boolean].option
      _    <- sql"""INSERT INTO support_thread_consent
                     (household_id, thread_id, granted_by_user_id, granted_at, expires_at, revoked_at)
                   VALUES ($household, $threadId, $grantedByUserId, $now, $expiresAt, NULL)
                   ON CONFLICT (household_id, thread_id) DO UPDATE
                     SET granted_by_user_id = EXCLUDED.granted_by_user_id,
                         granted_at         = EXCLUDED.granted_at,
                         expires_at         = EXCLUDED.expires_at,
                         revoked_at         = NULL""".update.run
    } yield !prev.getOrElse(false)).transact(xa)

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
}
