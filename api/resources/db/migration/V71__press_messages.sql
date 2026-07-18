-- V71__press_messages.sql
-- #2203 (epic #2197): persist the full press/PR correspondence — every inbound
-- press email and every outgoing AI reply — so the operator (household 1) can
-- review the autonomous press channel end to end.
--
-- SCHEMA-ONLY PR: per docs/process/migrations.md#migrations-back-compat this
-- migration ships alone (SQL + docs). The source that writes these rows (the
-- #2203 PressResponder, recording inbound at dispatch and outbound at reply) and
-- reads them (the household-1-only admin route + SPA page) lands in the follow-up
-- code PR. The existing feature suite is the back-compat gate.
--
-- ── Prod data-volume (docs/process/migrations.md#migrations-prod-data-volume) ─
-- Creates one new table. No unbounded-growth table (traffic_reports,
-- connection_events, block_events, rollups) is scanned or rewritten — metadata
-- only; trivially inside the 15-minute Render port-scan window. press_messages
-- itself grows only with press volume (a handful of emails), not with traffic.

-- ── press_messages ────────────────────────────────────────────────────────────
-- One row per message in the press channel, in either direction. Deliberately
-- NOT household-scoped: the press inbox (press@wifihaven.net) is a single,
-- company-global channel, surfaced only to the household-1 (operator) admin — the
-- read route enforces that gate; the table does not pretend the correspondence is
-- owned by a tenant.
--
--   direction    'inbound'  — a press email received (via the Cloudflare Email
--                             Worker → POST /api/press/inbound). peer_email = the
--                             sender (the journalist); outcome is NULL.
--                'outbound' — the AI reply emailed back (POST /api/press/agent/
--                             reply). peer_email = the recipient (the same
--                             journalist); outcome records the send result.
--   peer_email   the journalist's address (from for inbound, to for outbound).
--   subject      the message subject (inbound: as received; outbound: the Re:).
--   body         the message text (inbound: the received body; outbound: the AI
--                reply markdown as sent).
--   message_id   the email Message-ID header captured by the Worker (inbound);
--                empty for outbound (Resend assigns the sent id out of band).
--   in_reply_to  outbound → the inbound press_messages.id it answers, so the
--                admin view can pair each reply with its inquiry. NULL for
--                inbound (and for an outbound with no resolvable inbound).
--   outcome      outbound-only send result ('sent' | 'failed'); NULL for inbound.
--   created_at   when the row was recorded (receipt time / send time).
CREATE TABLE press_messages (
  id          BIGSERIAL PRIMARY KEY,
  direction   TEXT NOT NULL CHECK (direction IN ('inbound', 'outbound')),
  peer_email  TEXT NOT NULL,
  subject     TEXT NOT NULL DEFAULT '',
  body        TEXT NOT NULL DEFAULT '',
  message_id  TEXT NOT NULL DEFAULT '',
  in_reply_to BIGINT REFERENCES press_messages(id) ON DELETE SET NULL,
  outcome     TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The admin view lists newest-first; a small index keeps that ordered scan cheap
-- as the (low-volume) table grows.
CREATE INDEX press_messages_created_at_idx ON press_messages (created_at DESC);
