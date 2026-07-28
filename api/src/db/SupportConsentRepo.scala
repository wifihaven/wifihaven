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
 * #2460 / #2453 — what a consent-grant attempt DID. Every case is decided inside the one
 * transaction that writes, so nothing here can be re-derived by a caller from a separate read.
 */
enum GrantOutcome {

  /** The pair moved from no-live-grant to live. #2460's resume key — the ONLY case that resumes. */
  case Transitioned

  /**
   * A grant was already live. Either a fresh link re-confirming it, or (since #2453) the SAME link
   * presented again while its grant still stands — a page reload. Idempotent for the customer, and
   * it does not extend the window.
   */
  case AlreadyLive

  /**
   * #2453 — the link's nonce was already consumed AND there is no live grant now: the customer
   * withdrew, or the grant lapsed. This is the replay a captured link would otherwise win. Refused,
   * writes nothing.
   */
  case LinkSpent

  /**
   * #2453 — the link was minted BEFORE the record's `revoked_at`. An unredeemed link that was
   * outstanding when the customer withdrew cannot silently undo the withdrawal. Refused, writes
   * nothing (beyond spending the nonce, which is the point).
   */
  case LinkStale
}

trait SupportConsentRepo {

  /**
   * Record (or refresh) the customer's grant for `(household, thread)`, live until `expiresAt`.
   * UPSERTs on the UNIQUE `(household_id, thread_id)` key — a re-grant after expiry or revocation
   * refreshes the window and CLEARS `revoked_at` rather than accumulating history rows.
   * `grantedByUserId` is the audit trail (which admin granted).
   *
   * The result reports what the call DID ([[GrantOutcome]]), decided by the same transaction that
   * writes. #2460 keys the consent RESUME (the server re-dispatching the customer's question) off
   * [[GrantOutcome.Transitioned]], so it cannot be a separate read-then-write: that would let two
   * concurrent Allow clicks both observe "not live" and both re-dispatch, double-answering the
   * customer.
   *
   * #2453 — the LINK is single-use, and cannot outlive a withdrawal. `nonce` is the link's, and
   * redemption consumes it in `support_consent_link_use` (V85, PK on `nonce`, so consumption is
   * decided by the INSERT itself). `linkIssuedAt` / `linkExpiresAt` are the LINK's own mint and
   * expiry instants (from the signed token) — NOT the grant window, which starts at redemption: V85
   * defines `link_expires_at` as "when the spent link would have lapsed anyway", which is only true
   * of the link's own `exp`. Two refusals follow, and both write no grant:
   *   - nonce already consumed and no live grant now ⇒ [[GrantOutcome.LinkSpent]] — the replay
   *     shape. A consumed nonce whose grant IS still live is the benign reload:
   *     [[GrantOutcome.AlreadyLive]], no write, so a replay cannot even EXTEND the window.
   *   - `linkIssuedAt` before the record's `revoked_at` ⇒ [[GrantOutcome.LinkStale]].
   *
   * Only the ALLOW path calls this. [[revoke]] deliberately consumes nothing and is gated on
   * nothing: a withdrawal must never be blockable by a spent link.
   */
  def grant(
      household: HouseholdId,
      threadId: String,
      nonce: String,
      linkIssuedAt: Instant,
      linkExpiresAt: Instant,
      grantedByUserId: Option[UserId],
      now: Instant,
      expiresAt: Instant,
  ): Task[GrantOutcome]

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
      nonce: String,
      linkIssuedAt: Instant,
      linkExpiresAt: Instant,
      grantedByUserId: Option[UserId],
      now: Instant,
      expiresAt: Instant,
  ): Task[GrantOutcome] =
    // ONE transaction decides the link check, the write, and whether it was a TRANSITION (#2460).
    // The prior state is read `FOR UPDATE`, so the row lock is held across the upsert: a second
    // grant for the same pair blocks until this commits and then observes the LIVE row. (Two
    // simultaneous FIRST grants — no row to lock yet — can still both report a transition; they
    // carry the same question, and the dispatch caps bound the cost. Two simultaneous redemptions
    // of the SAME link cannot, since #2453: the nonce PK serializes them.) Deliberately separate
    // statements rather than a CTE: a read CTE alongside a data-modifying one does not reliably
    // observe the pre-write state, which is the whole signal here.
    (for {
      prev <- sql"""SELECT (revoked_at IS NULL AND expires_at > $now), revoked_at
                       FROM support_thread_consent
                      WHERE household_id = $household AND thread_id = $threadId
                      FOR UPDATE""".query[(Boolean, Option[Instant])].option
      live    = prev.exists(_._1)
      revoked = prev.flatMap(_._2)
      // #2453: SPEND the link. `DO NOTHING` on the nonce PK means `rows == 0` IS "already used" —
      // the uniqueness constraint decides it, not a read we could race.
      spent <- sql"""INSERT INTO support_consent_link_use
                      (nonce, household_id, thread_id, consumed_at, link_expires_at)
                    VALUES ($nonce, $household, $threadId, $now, $linkExpiresAt)
                    ON CONFLICT (nonce) DO NOTHING""".update.run.map(_ == 0)
      out   <-
        if spent then
          // A re-presented link: benign while its own grant still stands (a page reload), a REPLAY
          // once the customer withdrew or it lapsed. Either way it writes nothing, so it can
          // neither restore access nor extend the window.
          //
          // Re-read liveness rather than reusing `live` from above (review run 1). On a FIRST-ever
          // grant there is no row for the `FOR UPDATE` to lock, so two genuinely concurrent
          // redemptions of one link both read `live = false`; the loser then blocks on the nonce PK,
          // wakes to `spent = true`, and with the stale `live` would report LinkSpent — 400-ing a
          // customer whose consent had in fact just succeeded, and firing the expect-zero
          // `link_spent` security panel on a double-click. By this point the nonce INSERT has
          // already serialized behind the winner's COMMIT, so this read sees the committed grant.
          sql"""SELECT (revoked_at IS NULL AND expires_at > $now)
                  FROM support_thread_consent
                 WHERE household_id = $household AND thread_id = $threadId"""
            .query[Boolean]
            .option
            .map(l =>
              if l.getOrElse(false) then GrantOutcome.AlreadyLive else GrantOutcome.LinkSpent,
            )
        else if revoked.exists(_.isAfter(linkIssuedAt)) then
          // A link that was outstanding when the customer withdrew. The nonce is spent above (it is
          // dead either way); the grant is not written.
          doobie.free.connection.pure(GrantOutcome.LinkStale)
        else
          sql"""INSERT INTO support_thread_consent
                 (household_id, thread_id, granted_by_user_id, granted_at, expires_at, revoked_at)
               VALUES ($household, $threadId, $grantedByUserId, $now, $expiresAt, NULL)
               ON CONFLICT (household_id, thread_id) DO UPDATE
                 SET granted_by_user_id = EXCLUDED.granted_by_user_id,
                     granted_at         = EXCLUDED.granted_at,
                     expires_at         = EXCLUDED.expires_at,
                     revoked_at         = NULL""".update.run
            .map(_ => if live then GrantOutcome.AlreadyLive else GrantOutcome.Transitioned)
    } yield out).transact(xa)

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
