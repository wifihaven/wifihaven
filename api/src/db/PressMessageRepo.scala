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
}

class PressMessageRepoLive(xa: Transactor[Task]) extends PressMessageRepo {
  private val cols =
    fr"id, direction, peer_email, subject, body, message_id, in_reply_to, outcome, created_at"

  def recordInbound(
      peerEmail: String,
      subject: String,
      body: String,
      messageId: String,
  ): Task[Long] =
    sql"""INSERT INTO press_messages (direction, peer_email, subject, body, message_id)
          VALUES ('inbound', $peerEmail, $subject, $body, $messageId)
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
}
