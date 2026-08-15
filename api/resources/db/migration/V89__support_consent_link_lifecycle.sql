-- V89__support_consent_link_lifecycle.sql
-- #2709 (epic #2197, Beta Launch, SECURITY): give the V85 consent-link ledger a
-- POSTED state, so "an unredeemed consent link is live on this thread" is a
-- durable server-side fact rather than something we can only observe inside the
-- one agent session that posted it.
--
-- ── Why this state has to exist ──────────────────────────────────────────────
-- #2667 made the consent TURN server-authored: within one agent session the two
-- customer-visible thread writes (the agent's `reply` and the server's consent
-- prompt) are mutually exclusive, claimed atomically in
-- `DispatchTracker.claimThreadWrite`. That closes same-turn adjacency —
-- attacker-influenced text beside a genuine, correctly-signed consent link,
-- under our own `🤖 WifiHaven support assistant` attribution.
--
-- It does not close the NEXT turn, and the link stays redeemable for
-- `SupportResponder.ConsentLinkTtl` (24h). The exclusion is keyed on the
-- SESSION, and every inbound customer message dispatches a fresh session with
-- an empty claim map. So: session S1 posts the prompt; the customer asks the
-- natural question ("what is this link?"); that message dispatches S2, which
-- has claimed nothing and replies — under the same banner, directly beneath a
-- live genuine link. A prompt-injected S2 can then frame the link that is
-- already on screen ("click the link above and sign in with your password to
-- verify your identity"). #2453 stops S2 from SEEING the URL; S2 does not need
-- the URL to frame a link the customer can already see.
--
-- The correct rule is keyed on the THREAD and the LINK'S LIFETIME, not the
-- session: no agent-authored message while an unredeemed grant link is live on
-- this thread. Nothing in the system could answer that question. The grant
-- token is a stateless HMAC whose nonce is consumed only at REDEMPTION, and
-- `DispatchTracker`'s in-memory entry is replaced on the next dispatch and
-- evicted at the agent-token TTL.
--
-- ── Why here and not a new table ─────────────────────────────────────────────
-- `support_consent_link_use` (V85, #2453) already IS the per-link record; it
-- was simply only ever written at the END of a link's life. Adding a second
-- store for "this link was posted" would be a parallel record of the same
-- object's lifecycle — two places to keep in agreement about whether one link
-- is live (docs/process/single-source-of-truth.md: COLLAPSE, don't
-- keep-in-sync). So V85 becomes the whole lifecycle: a row is written when the
-- prompt is POSTED and resolved when the link dies. Its identity is unchanged —
-- one row per link, keyed by the nonce in the signed token — and the PK is
-- still what decides single-use.
--
-- ── The columns ──────────────────────────────────────────────────────────────
-- `consumed_at` becomes NULLABLE. A row now exists from the moment the prompt is
-- posted, and `consumed_at IS NULL` is exactly "this link has not been
-- redeemed". Single-use enforcement moves from "did the INSERT conflict" to "did
-- the upsert find `consumed_at IS NULL`" — still one atomic statement decided by
-- the PK, so two concurrent redemptions of one link still cannot both grant.
--
-- `posted_at` is when the server put the prompt in front of the customer.
--
-- `resolution` is the TERMINAL state, and NULL means outstanding. It is written
-- once and never rewritten (first resolution wins), which is what lets the
-- source layer read liveness as a single predicate:
--
--     resolution IS NULL AND consumed_at IS NULL AND link_expires_at > now
--
-- Three of the four ways a link dies are a write here — `redeemed` (the
-- customer allowed it), `withdrawn` (the customer withdrew, so the outstanding
-- link is dead too), `superseded` (a later prompt replaced it) — and the fourth,
-- EXPIRY, needs no write at all: it falls out of `link_expires_at > now`
-- evaluated against the injected Clock. That matters for a guard that SILENCES
-- the agent: a resolution path that depended on a sweeper running would leave
-- the agent muted on a thread if the sweeper ever stopped.
--
-- `explained_at` is the anti-dead-end half. While a link is live the agent's own
-- text is refused, so a customer asking "what is this link?" would otherwise get
-- silence — the exact failure #2419 was filed for. The server instead posts a
-- fixed, server-authored explanation, and this column is what makes it fire
-- EXACTLY ONCE per link: the source layer claims it with a conditional UPDATE
-- (`WHERE explained_at IS NULL`), so a looping or hostile agent calling `reply`
-- a hundred times cannot spam the customer with a hundred copies.
--
-- ── Back-compat (docs/process/migrations.md#migrations-back-compat) ──────────
-- SCHEMA-ONLY PR: SQL + docs, no source. The existing feature suite is the gate,
-- so the PRE-#2709 image must still work against this schema. It does:
--   * the old redemption INSERT names (nonce, household_id, thread_id,
--     consumed_at, link_expires_at) and would fail against a bare NOT NULL
--     `posted_at`, so `posted_at` carries a DEFAULT. The default exists FOR THAT
--     WINDOW ONLY — the #2709 source always supplies the instant from the
--     injected Clock (`wifihaven.shared.Clock`), never NOW(), so tests and
--     production continue to agree on one instant.
--   * `consumed_at` only LOSES a constraint, so every existing write still
--     satisfies it.
--   * `resolution` and `explained_at` are nullable with no default, so the old
--     INSERT that never names them still succeeds.
-- The one asymmetry worth stating: a row the OLD image inserts during the
-- deploy window is a redemption, but carries `resolution IS NULL`. That is why
-- the liveness predicate above also requires `consumed_at IS NULL` — a consumed
-- link is not an outstanding prompt, whichever image wrote the row. Without that
-- clause a redemption landing between the two deploys would read as a live
-- prompt and mute the agent on that thread until the link lapsed.
--
-- ── Prod data volume (docs/process/migrations.md#migrations-prod-data-volume) ─
-- `support_consent_link_use` holds one row per consent link ever REDEEMED —
-- single digits on prod as of this migration, and human-scale forever (it is
-- bounded by customers clicking Allow in support conversations). It is not one
-- of the unbounded-growth tables. `ADD COLUMN ... NULL` and `ADD COLUMN ... NOT
-- NULL DEFAULT` are both catalog-only on Postgres 11+; `DROP NOT NULL` is
-- catalog-only outright; the backfill and the partial index touch a handful of
-- rows. Runtime is milliseconds — nowhere near the 15-minute Render port-scan
-- window.

ALTER TABLE support_consent_link_use ALTER COLUMN consumed_at DROP NOT NULL;

-- When the server posted the prompt carrying this link. NOT NULL: every row
-- describes a link that was posted. The DEFAULT is the back-compat scaffold
-- described above and nothing in the source relies on it.
ALTER TABLE support_consent_link_use
  ADD COLUMN posted_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- Existing rows are all redemptions written by the pre-#2709 image: the only
-- instant we know about them is when they were consumed, and they are resolved
-- by definition. Backfilling `resolution` matters — a legacy row left NULL would
-- read as an outstanding prompt and silence the agent on that thread.
UPDATE support_consent_link_use SET posted_at = consumed_at WHERE consumed_at IS NOT NULL;

-- How the link DIED. NULL = still outstanding. Written once; first resolution
-- wins. The CHECK is the bounded vocabulary — it is also what the metric label
-- is derived from, so a new value cannot appear on the dashboard without
-- appearing here first.
ALTER TABLE support_consent_link_use
  ADD COLUMN resolution TEXT
  CHECK (resolution IS NULL OR resolution IN ('redeemed', 'withdrawn', 'superseded'));

UPDATE support_consent_link_use SET resolution = 'redeemed' WHERE consumed_at IS NOT NULL;

-- When the server posted its fixed explanation of this link. The conditional
-- UPDATE that stamps it is what makes the explanation fire exactly once per
-- link, so a looping agent cannot turn the anti-dead-end message into spam.
ALTER TABLE support_consent_link_use ADD COLUMN explained_at TIMESTAMPTZ;

-- The read on the hot path: EVERY agent reply asks "is a link outstanding on
-- this (household, thread)?". Partial on the predicate's own two NULL clauses,
-- so the index holds only outstanding links — a handful at any instant, versus
-- the full history a plain composite index would carry — and an index-only scan
-- answers it. `household_id` leads because it is the scoping column every
-- support read is keyed by (#2085).
CREATE INDEX support_consent_link_use_outstanding_idx
  ON support_consent_link_use (household_id, thread_id)
  WHERE resolution IS NULL AND consumed_at IS NULL;
