package wifihaven.api.db

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import zio.*
import zio.interop.catz.*
import zio.json.*

import java.time.Instant

/**
 * #2296 (press correspondence log, epic #2197/#2203): one row of the press/PR correspondence, in
 * either direction. Mirrors the V71 `press_messages` schema (already in main — PR #2285).
 *
 * Press correspondence is a single **company-global** channel (press@wifihaven.net), NOT
 * tenant-owned — so there is deliberately NO `household_id` here (see the V71 migration comment).
 * The access gate lives on the READ route (`household == HouseholdId.Default`), not on the row.
 *
 *   - `direction` — `"inbound"` (a press email received) | `"outbound"` (the AI reply emailed
 *     back). The CHECK constraint pins the domain server-side.
 *   - `peerEmail` — the journalist's address (the `from` for inbound, the `to` for outbound).
 *   - `inReplyTo` — outbound → the inbound row it answers, so the admin view pairs each reply with
 *     its inquiry. `None` for inbound (and for an outbound whose inbound row was lost to a
 *     fail-open recording error).
 *   - `outcome` — outbound-only send result (`"sent"` | `"failed"`); `None` for inbound.
 *   - `references` — #2467, the V88 `references_header` column: the inbound `References` chain,
 *     normalised to a space-separated msg-id list and bounded to one RFC 5322 header line. Empty
 *     for a first-contact inbound, and always empty for outbound (the chain we emit is derived at
 *     send time, and Resend assigns the sent Message-ID out of band).
 */
case class PressMessage(
    id: Long,
    direction: String,
    peerEmail: String,
    subject: String,
    body: String,
    messageId: String,
    inReplyTo: Option[Long],
    outcome: Option[String],
    createdAt: Instant,
    references: String,
) derives JsonCodec

/**
 * #2296: persistence for the press correspondence log. Best-effort AUDIT — every write is called
 * fail-open by [[wifihaven.api.press.PressResponder]] (a DB hiccup must never break the inbound
 * webhook or the autonomous reply send), so these `Task`s are wrapped in `catchAll` at the call
 * site, not made total here.
 */
trait PressMessageRepo {

  /** Record a received press email; returns the new row's id so the reply can pair to it. */
  def recordInbound(
      peerEmail: String,
      subject: String,
      body: String,
      messageId: String,
      // #2467 — the normalised, bounded inbound `References` chain (empty for first contact).
      references: String,
  ): Task[Long]

  /**
   * Record an emitted AI reply. `inReplyTo` is the inbound row it answers (None if unresolvable).
   */
  def recordOutbound(
      peerEmail: String,
      subject: String,
      body: String,
      inReplyTo: Option[Long],
      outcome: String,
  ): Task[Long]

  /**
   * The most-recent `limit` messages, newest-first (index-backed by press_messages_created_at_idx).
   */
  def listRecent(limit: Int): Task[List[PressMessage]]

  /**
   * #2233 — the set of distinct `peer_email`s we have any SUCCESSFUL outbound correspondence with
   * (`direction='outbound'` and `outcome <> 'failed'` — a failed send is NOT "already reached", so
   * a re-run retries it). The press-outreach send path reads this as its cross-invocation
   * idempotency ledger: a peer already here is skipped, so re-running a partially-completed batch
   * never double-blasts a journalist. Reuses the #2296 `press_messages` log (no new table), which
   * is correct semantically — a peer we've already emailed the release to (or already replied to)
   * is a peer we don't re-outreach.
   */
  def outboundPeers(): Task[Set[String]]

  /**
   * #2437 — one row by primary key, for the escalation notice: the press responder re-reads the
   * ORIGINAL inquiry (by the id carried on the signed session token) so the operator email quotes
   * what the journalist actually wrote, not something the agent could have rewritten. `None` when
   * the row does not exist (the fail-open inbound recording missed, so the token carries id 0).
   */
  def findById(id: Long): Task[Option[PressMessage]]
}

class PressMessageRepoLive(xa: Transactor[Task]) extends PressMessageRepo {
  // Column order IS the PressMessage field order — `references_header` last, matching the field
  // V88 added to the end of the case class.
  private val cols =
    fr"""id, direction, peer_email, subject, body, message_id, in_reply_to, outcome, created_at,
         references_header"""

  def recordInbound(
      peerEmail: String,
      subject: String,
      body: String,
      messageId: String,
      references: String,
  ): Task[Long] =
    sql"""INSERT INTO press_messages
            (direction, peer_email, subject, body, message_id, references_header)
          VALUES ('inbound', $peerEmail, $subject, $body, $messageId, $references)
          RETURNING id""".query[Long].unique.transact(xa)

  def recordOutbound(
      peerEmail: String,
      subject: String,
      body: String,
      inReplyTo: Option[Long],
      outcome: String,
  ): Task[Long] =
    sql"""INSERT INTO press_messages (direction, peer_email, subject, body, in_reply_to, outcome)
          VALUES ('outbound', $peerEmail, $subject, $body, $inReplyTo, $outcome)
          RETURNING id""".query[Long].unique.transact(xa)

  def listRecent(limit: Int): Task[List[PressMessage]] =
    (fr"SELECT" ++ cols ++
      fr"FROM press_messages ORDER BY created_at DESC, id DESC LIMIT ${limit.toLong}")
      .query[PressMessage]
      .to[List]
      .transact(xa)

  // #2437: primary-key lookup — an index-organised single-row fetch, no plan concern.
  def findById(id: Long): Task[Option[PressMessage]] =
    (fr"SELECT" ++ cols ++ fr"FROM press_messages WHERE id = $id")
      .query[PressMessage]
      .option
      .transact(xa)

  def outboundPeers(): Task[Set[String]] =
    sql"""SELECT DISTINCT peer_email FROM press_messages
          WHERE direction = 'outbound' AND (outcome IS NULL OR outcome <> 'failed')"""
      .query[String]
      .to[List]
      .map(_.toSet)
      .transact(xa)
}
