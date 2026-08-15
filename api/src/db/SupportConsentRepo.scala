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

/**
 * #2709 — an OUTSTANDING consent link: one the server posted into a thread that the customer has
 * neither redeemed nor withdrawn, and which has not lapsed. While one exists the agent's own text
 * is refused on that thread, so this is the value the security guard turns on.
 *
 * `explainedAt` is the anti-dead-end half rather than part of the predicate: the server answers a
 * customer asking "what is this link?" with its OWN fixed explanation, and this records that the
 * explanation has already been posted so a looping agent cannot repeat it.
 */
final case class OutstandingConsentLink(nonce: String, explainedAt: Option[Instant])

/** How an outstanding consent link ENDED — the V89 `resolution` vocabulary, in one place. */
enum LinkResolution(val label: String) {

  /** The customer allowed it (or allowed a sibling link on the same thread). */
  case Redeemed extends LinkResolution("redeemed")

  /** The customer withdrew — the outstanding link dies with the decision. */
  case Withdrawn extends LinkResolution("withdrawn")

  /**
   * A later prompt replaced it. Note what this does and does not say: it resolves the row, so the
   * link stops counting as OUTSTANDING for the #2709 exclusion — it does not make the link
   * unredeemable. `grant` gates single-use on `consumed_at`, never on `resolution` (#2453's rule),
   * so a superseded link the customer can still see still works until it is consumed, withdrawn, or
   * lapses. That is deliberate: a customer clicking the older of two genuine links they were sent
   * should not get an error.
   */
  case Superseded extends LinkResolution("superseded")
}

trait SupportConsentRepo {

  /**
   * #2709 — record that the server has POSTED a consent prompt carrying `nonce` into `(household,
   * thread)`, and RESOLVE any link that was already outstanding there as
   * [[LinkResolution.Superseded]]. Answers how many were superseded, so the caller can meter a
   * substitution rather than let it be silent.
   *
   * Both halves are one transaction, so a failed insert cannot leave the previous link resolved and
   * nothing in its place — which would read as "no link outstanding" on a thread that still has one
   * in front of the customer.
   *
   * It supersedes what it can SEE, which is not quite "at most one row is ever outstanding": two
   * genuinely concurrent calls can each supersede nothing and both insert. [[outstandingLink]]
   * tolerates that by construction (it takes the newest), and the security answer is unchanged
   * either way — the thread is muted while ANY row is outstanding, so an extra row can only mute,
   * never unmute. Do not read this as a uniqueness guarantee; the schema does not enforce one.
   */
  def recordPrompt(
      household: HouseholdId,
      threadId: String,
      nonce: String,
      postedAt: Instant,
      linkExpiresAt: Instant,
  ): Task[Int]

  /**
   * #2709 — the outstanding link on `(household, thread)` as of `now`, if any. The ONE predicate
   * the reply path consults, and the whole guard:
   * {{{resolution IS NULL AND consumed_at IS NULL AND link_expires_at > now}}}
   *
   * Note what is NOT here: expiry is the absence of a write, not a fourth resolution. A guard whose
   * effect is to SILENCE the agent must not depend on a sweeper having run — if one ever stopped,
   * every thread it missed would stay muted. `consumed_at IS NULL` is redundant with `resolution IS
   * NULL` for rows this code writes and is kept for the rows the PRE-#2709 image wrote during the
   * deploy window: those are redemptions carrying a NULL resolution, and without the clause one
   * would read as a live prompt (V89 states the same asymmetry).
   */
  def outstandingLink(
      household: HouseholdId,
      threadId: String,
      now: Instant,
  ): Task[Option[OutstandingConsentLink]]

  /**
   * #2709 — DISCARD the row [[recordPrompt]] wrote, because the prompt was never posted. The only
   * caller is the `PlainOutcome.Disabled` branch: our own write half is off, so nothing was
   * attempted and no link can be in front of the customer. Answers whether a row was actually
   * removed.
   *
   * A DELETE and not a resolution, because there is no honest `resolution` for it — the link never
   * existed for the customer, so recording it as redeemed / withdrawn / superseded would each be a
   * false statement in the ledger. Refuses to touch a row that is consumed or already resolved, so
   * it can never erase a live link's history. Scoped by household + thread like every other
   * statement here.
   */
  def discardPrompt(household: HouseholdId, threadId: String, nonce: String): Task[Boolean]

  /**
   * #2709 — claim the right to post the server's fixed explanation of `nonce`, exactly once. True
   * iff THIS call won it. A conditional `WHERE explained_at IS NULL` update rather than a
   * read-then-write, because the caller racing itself is the untrusted one: an agent firing `reply`
   * in a loop (or twice concurrently) would otherwise pass a read and spam the customer with copies
   * of our own message.
   *
   * Scoped by household + thread as well as the nonce. The nonce is a 128-bit PK and always comes
   * from an [[outstandingLink]] read already scoped by the verified token, so the scoping is
   * redundant TODAY — it is here so the statement is safe on its own terms rather than by virtue of
   * its one call site, which is the #2630 lesson.
   */
  def claimExplainer(
      household: HouseholdId,
      threadId: String,
      nonce: String,
      now: Instant,
  ): Task[Boolean]

  /**
   * #2709 — give the explainer claim BACK, because the write provably never happened. Only the
   * `PlainOutcome.Disabled` branch calls it, on the same reasoning as everywhere else in this flow:
   * a dark Plain client is the one outcome that proves non-delivery, while an `Error` collapses a
   * refusal, a transport failure and a timeout — and a write that timed out may well have landed.
   * Releasing on `Error` would let a `reply` loop retry until one landed, which is the duplicate
   * this claim exists to prevent.
   *
   * NOT unconditional: it gives back only a claim that is still OUTSTANDING and unclaimed-again —
   * the customer can redeem or withdraw between the liveness read and this call, and a settled
   * row's audit fields are not ours to rewrite afterwards. Refusing changes nothing observable,
   * since a resolved row can never be returned by [[outstandingLink]] again.
   */
  def releaseExplainer(household: HouseholdId, threadId: String, nonce: String): Task[Unit]

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
   *
   * #2709 — a successful grant also RESOLVES every outstanding link on `(household, thread)`, not
   * only the one redeemed. Once the customer has said yes there is no decision pending, so no link
   * on that thread can still be "live" for the purposes of the agent-text exclusion. Resolving only
   * the redeemed nonce would leave a superseded sibling outstanding and mute the agent on a thread
   * whose customer had just consented.
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
   *
   * #2709 — it ALSO resolves any outstanding link on the pair as [[LinkResolution.Withdrawn]], and
   * does so UNCONDITIONALLY, whatever the Boolean says. "Don't allow" on a prompt that was never
   * granted is the common shape (the customer declining the ask), and it is exactly the case that
   * must lift the agent-text exclusion: a customer who says no must not be left without an
   * assistant for the rest of the link's 24 hours.
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

  // #2709 — resolve every link outstanding on the pair. First resolution wins (`resolution IS
  // NULL` is part of the WHERE), so a redeemed link is never relabelled by a later withdrawal.
  private def resolveOutstanding(
      household: HouseholdId,
      threadId: String,
      how: LinkResolution,
  ): ConnectionIO[Int] = {
    val label = how.label
    sql"""UPDATE support_consent_link_use SET resolution = $label
          WHERE household_id = $household AND thread_id = $threadId
            AND resolution IS NULL AND consumed_at IS NULL""".update.run
  }

  def recordPrompt(
      household: HouseholdId,
      threadId: String,
      nonce: String,
      postedAt: Instant,
      linkExpiresAt: Instant,
  ): Task[Int] =
    (for {
      superseded <- resolveOutstanding(household, threadId, LinkResolution.Superseded)
      _          <- sql"""INSERT INTO support_consent_link_use
                            (nonce, household_id, thread_id, posted_at, consumed_at,
                             link_expires_at, resolution)
                          VALUES ($nonce, $household, $threadId, $postedAt, NULL,
                                  $linkExpiresAt, NULL)""".update.run
    } yield superseded).transact(xa)

  def outstandingLink(
      household: HouseholdId,
      threadId: String,
      now: Instant,
  ): Task[Option[OutstandingConsentLink]] =
    sql"""SELECT nonce, explained_at FROM support_consent_link_use
          WHERE household_id = $household AND thread_id = $threadId
            AND resolution IS NULL AND consumed_at IS NULL
            AND link_expires_at > $now
          ORDER BY posted_at DESC
          LIMIT 1"""
      .query[(String, Option[Instant])]
      .option
      .transact(xa)
      .map(_.map(OutstandingConsentLink.apply.tupled))

  def discardPrompt(household: HouseholdId, threadId: String, nonce: String): Task[Boolean] =
    sql"""DELETE FROM support_consent_link_use
          WHERE nonce = $nonce AND household_id = $household AND thread_id = $threadId
            AND consumed_at IS NULL AND resolution IS NULL""".update.run
      .transact(xa)
      .map(_ == 1)

  def claimExplainer(
      household: HouseholdId,
      threadId: String,
      nonce: String,
      now: Instant,
  ): Task[Boolean] =
    sql"""UPDATE support_consent_link_use SET explained_at = $now
          WHERE nonce = $nonce AND household_id = $household AND thread_id = $threadId
            AND explained_at IS NULL""".update.run
      .transact(xa)
      .map(_ == 1)

  def releaseExplainer(household: HouseholdId, threadId: String, nonce: String): Task[Unit] =
    // Guarded on the SETTLED state as well as the key: the customer can redeem or withdraw between
    // the liveness read and this release, and a resolved row's audit fields are not ours to rewrite
    // afterwards. Refusing changes no behaviour — a resolved row can never be returned by
    // `outstandingLink` again, so the explainer could not re-post either way — it keeps the
    // statement correct on its own terms rather than by virtue of its call site.
    sql"""UPDATE support_consent_link_use SET explained_at = NULL
          WHERE nonce = $nonce AND household_id = $household AND thread_id = $threadId
            AND explained_at IS NOT NULL
            AND consumed_at IS NULL AND resolution IS NULL""".update.run
      .transact(xa)
      .unit

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
      // #2453: SPEND the link, in ONE statement decided by the nonce PK rather than by a read we
      // could race. `rows == 0` IS "already used".
      //
      // #2709 moved WHERE that fact lives, not what decides it. The row now normally exists
      // already — written when the prompt was POSTED — so consumption can no longer be "did the
      // INSERT conflict"; it is "did the upsert find `consumed_at IS NULL`". The INSERT branch
      // still matters and is not dead code: it covers a link minted by the PRE-#2709 image, which
      // posted no row, so those links stay redeemable exactly once across the deploy.
      //
      // `resolution` is COALESCEd rather than overwritten: first resolution wins, so redeeming a
      // link that a later prompt had already superseded records what actually ended it. Either way
      // it is non-NULL, so the #2709 exclusion lifts.
      spent <- sql"""INSERT INTO support_consent_link_use
                      (nonce, household_id, thread_id, posted_at, consumed_at, link_expires_at,
                       resolution)
                    VALUES ($nonce, $household, $threadId, $now, $now, $linkExpiresAt, 'redeemed')
                    ON CONFLICT (nonce) DO UPDATE
                      SET consumed_at = $now,
                          resolution  =
                            COALESCE(support_consent_link_use.resolution, 'redeemed')
                      WHERE support_consent_link_use.consumed_at IS NULL""".update.run.map(_ == 0)
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
          //
          // This depends on READ COMMITTED, where each STATEMENT takes a fresh snapshot — which is
          // Postgres's default and what we run (nothing in `api/src` sets a transaction isolation
          // level). Under REPEATABLE READ the whole transaction would share one snapshot taken
          // before the winner committed, and this re-read would still see no row. If an isolation
          // level is ever set globally, revisit this branch.
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
          for {
            _ <- sql"""INSERT INTO support_thread_consent
                 (household_id, thread_id, granted_by_user_id, granted_at, expires_at, revoked_at)
               VALUES ($household, $threadId, $grantedByUserId, $now, $expiresAt, NULL)
               ON CONFLICT (household_id, thread_id) DO UPDATE
                 SET granted_by_user_id = EXCLUDED.granted_by_user_id,
                     granted_at         = EXCLUDED.granted_at,
                     expires_at         = EXCLUDED.expires_at,
                     revoked_at         = NULL""".update.run
            // #2709: the customer has answered, so nothing on this thread is still being asked. Any
            // SIBLING link left outstanding (a superseded earlier prompt) is resolved with it —
            // otherwise it would keep the agent muted on a thread that had just consented.
            _ <- resolveOutstanding(household, threadId, LinkResolution.Redeemed)
          } yield if live then GrantOutcome.AlreadyLive else GrantOutcome.Transitioned
    } yield out).transact(xa)

  def revoke(household: HouseholdId, threadId: String, now: Instant): Task[Boolean] =
    (for {
      revokedLive <- sql"""UPDATE support_thread_consent SET revoked_at = $now
                           WHERE household_id = $household AND thread_id = $threadId
                             AND revoked_at IS NULL AND expires_at > $now""".update.run.map(_ == 1)
      // #2709 — unconditional, and NOT folded into the Boolean above: "Don't allow" on a prompt
      // that was never granted revokes nothing but still ends the ask, and that is precisely the
      // case that must give the agent its voice back. Same transaction, so a withdrawal can never
      // half-apply and leave a dead link reading as live.
      _           <- resolveOutstanding(household, threadId, LinkResolution.Withdrawn)
    } yield revokedLive).transact(xa)

  def isGranted(household: HouseholdId, threadId: String, now: Instant): Task[Boolean] =
    sql"""SELECT 1 FROM support_thread_consent
          WHERE household_id = $household AND thread_id = $threadId
            AND revoked_at IS NULL AND expires_at > $now"""
      .query[Int]
      .option
      .transact(xa)
      .map(_.isDefined)
}
