-- V48__global_policy.sql
-- #1317: schema foundation for the global policy layer + per-profile
-- default-deny. See docs/design/global-policy-layer.md §7 (curation/audit)
-- and §8 item 2.
--
-- This is a SCHEMA-ONLY migration (per the migration-isolation rule in
-- CLAUDE.md): it ships with no source or tests. The PolicyService that READS
-- these tables to assemble PolicySnapshot.global is the follow-up #1318; the
-- SPA that authors them is #1320. Until then these tables are inert — nothing
-- reads them, and the #1307 per-profile infra-allow copy still ships, so there
-- is no functional change from this migration alone.
--
-- All four objects are small lookup / settings surfaces — NOT the
-- unbounded-growth event tables (traffic_reports, connection_events, …). New
-- tables start empty; the household_settings + profiles ALTERs add columns
-- with constant defaults (metadata-only in PG 11+, no table rewrite). Safe on
-- the Flyway startup critical path even at prod data volume.
--
-- Wire mapping (resolved server-side into PolicySnapshot.global : BlockRules):
--   global_allow            -> global.extraAllowed   (always carves out every drop)
--   global_blocks           -> global.extraBlocked   (un-blockable except by global_allow)
--   global_blocklists       -> global.blocklistIds   (category lists for every MAC)
--   household_settings.global_blocked       -> global.blocked      (network lockdown)
--   household_settings.global_block_reason  -> global.blockReason  (block-page copy)
--   household_settings.global_block_ip_only -> global.blockIpOnly  (network-wide strict-IP)
-- The router never sees the *why* (reason/audit columns); those stay in the DB.

-- ── global_allow ──────────────────────────────────────────────────────────
-- The curated, security-sensitive always-reachable list. A host here is
-- reachable from EVERY device regardless of any block (global.extraAllowed is
-- the top of the precedence ladder, §5.2), so the table is append/soft-delete
-- and retains full history for audit. The active set feeding the wire is the
-- rows with removed_at IS NULL; the `reason` (connectivity survival, PKI,
-- WifiHaven UI/block page, operator carve-out) is server-side metadata only.
CREATE TABLE global_allow (
  id          BIGSERIAL PRIMARY KEY,
  host        TEXT        NOT NULL,
  reason      TEXT,
  added_by    BIGINT      REFERENCES users(id) ON DELETE SET NULL,
  added_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  removed_by  BIGINT      REFERENCES users(id) ON DELETE SET NULL,
  removed_at  TIMESTAMPTZ
);

-- PolicyService reads the ACTIVE set (`WHERE removed_at IS NULL`) to build
-- global.extraAllowed; the partial index covers that probe and, being UNIQUE
-- on host, prevents two live entries for the same host (re-adding a removed
-- host is fine — the soft-deleted row keeps removed_at set). Soft-deleted rows
-- are excluded from the index so history never collides.
CREATE UNIQUE INDEX idx_global_allow_active_host
  ON global_allow (host) WHERE removed_at IS NULL;

-- ── global_blocks ─────────────────────────────────────────────────────────
-- Hosts blocked for every MAC (global.extraBlocked). A profile/device may NOT
-- un-block these — only global_allow carves them out (§5.3). Same
-- append/soft-delete audit shape as global_allow for symmetry and so operator
-- block changes are equally auditable.
CREATE TABLE global_blocks (
  id          BIGSERIAL PRIMARY KEY,
  host        TEXT        NOT NULL,
  reason      TEXT,
  added_by    BIGINT      REFERENCES users(id) ON DELETE SET NULL,
  added_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  removed_by  BIGINT      REFERENCES users(id) ON DELETE SET NULL,
  removed_at  TIMESTAMPTZ
);

CREATE UNIQUE INDEX idx_global_blocks_active_host
  ON global_blocks (host) WHERE removed_at IS NULL;

-- ── global_blocklists ─────────────────────────────────────────────────────
-- Household-level association of category blocklists applied to every MAC
-- (global.blocklistIds). Single-household install, so no household_id — the
-- table itself is the household-scoped set. blocklist_id references the
-- first-class blocklists row (V31); deleting a blocklist drops its global
-- association. PolicyService selects all rows to build global.blocklistIds, so
-- the PK on blocklist_id is the only probe and needs no extra index.
CREATE TABLE global_blocklists (
  blocklist_id  TEXT        PRIMARY KEY REFERENCES blocklists(id) ON DELETE CASCADE,
  added_by      BIGINT      REFERENCES users(id) ON DELETE SET NULL,
  added_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── household_settings: global flags ──────────────────────────────────────
-- The flat global toggles that have no host list of their own. Single-row
-- table (id = 1, ensured at boot). All default to the allow-all / no-lockdown
-- values of BlockRules.allowAll, so an un-touched household carries no global
-- block. global_block_reason supplies block-page copy only when
-- global_blocked is true (mirrors MacBlockReason on a per-MAC block).
ALTER TABLE household_settings
  ADD COLUMN global_blocked        BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN global_block_reason   TEXT,
  ADD COLUMN global_block_ip_only  BOOLEAN NOT NULL DEFAULT FALSE;

-- ── profiles.default_deny ─────────────────────────────────────────────────
-- Per-profile default-deny baseline: block-all, only extraAllowed (plus
-- global.extraAllowed) reachable (§4). PolicyService collapses default_deny =
-- true into blocked = true + blockReason = DefaultDeny (the lowest-precedence
-- reason). Devices inherit it via the profile unless they carry a `rules`
-- override. Constant default ⇒ metadata-only ADD COLUMN.
ALTER TABLE profiles
  ADD COLUMN default_deny BOOLEAN NOT NULL DEFAULT FALSE;
