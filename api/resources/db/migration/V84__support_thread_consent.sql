-- V84__support_thread_consent.sql
-- #2419 (epic #2197, Beta Launch): in-conversation data-access consent for the
-- Claude support responder.
--
-- Today the #2241 agent token's `dataAccess` scope is sourced from a
-- `dataConsent` flag on the Plain webhook payload that NOTHING ever sets, so it
-- is always false: the agent can never read the customer's own household
-- summary and dead-ends with "I don't have permission to look up your account
-- details". This table is the server-side record of an explicit CUSTOMER grant,
-- keyed by (household, Plain thread); a dispatch for a thread with a live grant
-- mints a token with dataAccess=true.
--
-- SECURITY MODEL (the point of the table — do not weaken it):
--   * A row is written ONLY by the authenticated customer's own action
--     (POST /api/support/consent, JWT-gated, household taken from the JWT).
--     Consent is NEVER inferred from the untrusted inbound message text, and
--     the support agent — whose only credential is the thread-bound #2241
--     token — has no path that writes here. The agent may REQUEST consent
--     (the server then posts the prompt into the thread); only the customer
--     grants it. Requesting and having are different privileges.
--   * (household_id, thread_id) is UNIQUE and both are part of every lookup, so
--     consent on thread A grants nothing on thread B, and household A's row can
--     never widen a household-B token (the #2107/#2108 isolation substrate).
--     Consent widens the token's DATA SCOPE only — never its household/thread
--     binding.
--   * expires_at bounds the grant (the source layer mints it a fixed TTL ahead
--     via the injected Clock) and revoked_at makes it revocable ahead of expiry
--     (the customer's "no longer allow" action; cross-ref #2259, the agent-token
--     revocation list). A grant is live iff
--     `revoked_at IS NULL AND expires_at > now`.
--   * granted_by_user_id is the audit trail: WHICH admin granted it. NULLable +
--     ON DELETE SET NULL so removing a user does not erase the grant record
--     itself (the household FK cascade is what actually reaps rows).
--
-- SCHEMA-ONLY PR: per docs/process/migrations.md#migrations-back-compat this
-- migration ships alone (SQL + docs). The source that writes/reads these rows
-- (SupportThreadConsentRepo, POST /api/support/agent/request-consent, POST
-- /api/support/consent, the dataAccess lookup at dispatch, the SPA consent page)
-- lands in the stacked follow-up PR once this merges. The existing feature suite
-- applying V84 over the seeded schema (embedded Postgres) is the clean-apply /
-- back-compat gate: image-(N-1) never references this table, so its code paths
-- are unaffected by its presence.
--
-- ── Prod data-volume (docs/process/migrations.md#migrations-prod-data-volume) ─
-- Fresh CREATE TABLE, no scan/rewrite of any existing table. The only reference
-- is the FK to the small bounded `households` table (prod: single-digit rows).
-- No unbounded-growth table (traffic_reports, connection_events, block_events,
-- rollups) is touched. Sub-second, well inside the 15-minute Render port-scan
-- window. No V-split needed. Row volume here is bounded by support-thread
-- volume (one row per household+thread that ever consents) — human-scale.

CREATE TABLE support_thread_consent (
  id                 BIGSERIAL PRIMARY KEY,
  household_id       BIGINT NOT NULL REFERENCES households(id) ON DELETE CASCADE,
  -- The Plain thread id (ULID-shaped, e.g. `th_01J…`). TEXT, not an enum/FK:
  -- Plain owns threads (#2199 scope) and we never mirror them locally.
  thread_id          TEXT NOT NULL,
  granted_by_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
  granted_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  expires_at         TIMESTAMPTZ NOT NULL,
  revoked_at         TIMESTAMPTZ,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  -- One consent record per (household, thread): a re-grant after expiry or
  -- revocation UPSERTs this row (refreshing expires_at, clearing revoked_at)
  -- rather than accumulating history rows. The audit trail for grants lives in
  -- the structured log (#2241 discipline), which is where the operator reads it.
  CONSTRAINT uq_support_thread_consent UNIQUE (household_id, thread_id)
);

-- The ONLY read path is "is there a live grant for THIS household + THIS
-- thread" — exactly the UNIQUE constraint's btree index above (household_id
-- leading, thread_id second), so no separate CREATE INDEX is needed. The
-- expires_at / revoked_at predicates filter a single fetched row; they are
-- deliberately not indexed (the table is human-scale and the lookup is already
-- a unique-index probe).
