-- V85__support_consent_link_use.sql
-- #2453 (epic #2197): make the #2419 consent LINK single-use.
--
-- The problem this table closes. The consent link the server posts into a Plain
-- thread (`<app>/support/consent?g=g1.…`) is a stateless signed capability: any
-- number of redemptions succeed until it expires, and `SupportConsentRepo.grant`
-- UPSERTs with `revoked_at = NULL`. So a link captured from the thread — and
-- since #2430/#2441 the thread transcript re-enters the support agent's own
-- prompt, which is how #2453 was found — can be replayed to RE-GRANT data access
-- after the customer explicitly withdrew it. The customer's withdrawal is
-- silently undone by one click on an old link.
--
-- The fix has two halves; only this one needs schema:
--   1. (schema, HERE) every consent link carries a random `nonce`, and REDEMPTION
--      CONSUMES it. Redeeming a nonce that is already in this table is refused —
--      so a captured link is spent, not reusable.
--   2. (source, stacked follow-up) the link also carries its MINT time, and a
--      grant is refused when the link was minted BEFORE the row's `revoked_at` —
--      an unredeemed link outstanding at the moment of withdrawal cannot undo it.
--      That reads the existing `support_thread_consent.revoked_at` (V84), so it
--      adds no column.
--
-- SECURITY MODEL (do not weaken):
--   * `nonce` is the PRIMARY KEY, so consumption is decided by the INSERT itself:
--      a second redemption of the same link is a unique-violation, not a
--      read-then-write race. Two concurrent clicks on one link cannot both grant.
--   * Only the CUSTOMER's JWT-authenticated POST /api/support/consent writes here
--     (the same single-writer rule as V84's table). The support agent has no path
--     to this table, and REQUESTING consent still writes nothing.
--   * Consumption is recorded for the ALLOW action only. Withdrawal must never be
--     blockable — a customer whose link is already spent must still be able to
--     revoke — so the revoke path does not consume and is not gated on this table.
--   * `(household_id, thread_id)` are stored for the audit trail / cascade only;
--     the authorisation decision keys on `nonce` alone, which is exactly what the
--     link binds. They are NOT part of the lookup, so a mismatch here can never
--     widen a grant.
--   * Rows are keepable indefinitely (human-scale volume: one row per consent
--     link a customer actually redeems). `link_expires_at` records when the spent
--     link would have lapsed on its own, so a future reaper can drop rows whose
--     link could no longer be replayed anyway without needing to re-derive the
--     TTL. No reaper ships with this migration — nothing depends on one.
--
-- SCHEMA-ONLY PR: per docs/process/migrations.md#migrations-back-compat this
-- migration ships alone (SQL + docs). The source that writes/reads these rows
-- (the nonce on ConsentGrant, the consume-on-redeem in SupportConsentRepo,
-- the SupportResponder wiring) lands in the stacked follow-up PR once this
-- merges. The existing feature suite applying V85 over the seeded schema
-- (embedded Postgres) is the clean-apply / back-compat gate: image-(N-1) never
-- references this table, so its code paths are unaffected by its presence.
--
-- ── Prod data-volume (docs/process/migrations.md#migrations-prod-data-volume) ─
-- Fresh CREATE TABLE, no scan/rewrite of any existing table. The only reference
-- is the FK to the small bounded `households` table (prod: single-digit rows).
-- No unbounded-growth table (traffic_reports, connection_events, block_events,
-- rollups) is touched. Sub-second, well inside the 15-minute Render port-scan
-- window.

CREATE TABLE support_consent_link_use (
  -- The link's random nonce, as minted into the signed `g1.…` grant token. PK:
  -- the uniqueness constraint IS the single-use enforcement.
  nonce           TEXT PRIMARY KEY,
  household_id    BIGINT NOT NULL REFERENCES households(id) ON DELETE CASCADE,
  -- The Plain thread id (ULID-shaped). TEXT, not an FK: Plain owns threads
  -- (#2199 scope) and we never mirror them locally. Same rationale as V84.
  thread_id       TEXT NOT NULL,
  -- When the link was redeemed (from the injected Clock, not NOW(), so tests and
  -- production agree on one instant).
  consumed_at     TIMESTAMPTZ NOT NULL,
  -- The `exp` the redeemed link carried — when it would have lapsed anyway.
  link_expires_at TIMESTAMPTZ NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- The ONLY read path is the PK probe implied by the INSERT (`ON CONFLICT DO
-- NOTHING` on `nonce`), already served by the primary-key index. `household_id`
-- is deliberately not indexed: nothing queries by it — the FK exists for the
-- CASCADE reap, and a household delete against a human-scale table is a cheap
-- seq scan.
