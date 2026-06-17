-- V59__profiles_is_global_drop_globals.sql
-- Step 1 of #1769 (see #1770). Collapse the household-global policy surface
-- from dedicated `global_*` tables + `household_settings.global_*` flags into
-- a single sentinel row in `profiles` flagged `is_global = TRUE`. The
-- per-profile policy machinery (app assignments, blocklists, flags) is then
-- reused by step 2 (#1771) to assemble the global rules, eliminating the
-- duplicate machinery flagged by AGENTS.md §single-source-of-truth.
--
-- Prod's `global_allow` / `global_blocks` / `global_blocklists` tables and
-- the `household_settings.global_*` flags were verified EMPTY / default on
-- 2026-06-16 via `GET /api/global/policy` against api.wifihaven.net; no
-- backfill is required. All four objects (three tables + the flag columns)
-- are tiny — DROP TABLE / DROP COLUMN is sub-second even at prod data
-- volume (none of these are unbounded-growth event tables).
--
-- The umbrella's "global_flags" entry refers to the flag columns added by
-- V48 to `household_settings` (no `global_flags` table was ever created).
-- Both are dropped here for symmetry with their `global_allow` / `global_blocks`
-- / `global_blocklists` siblings.
--
-- This is a **schema-only** migration, kept suite-green by the prior step 0
-- (#1775, merged) which already deleted the dead `GlobalPolicyRepo` /
-- `/api/global/*` / `household_settings.global_*` readers. After this PR,
-- the `is_global` column exists but no row carries `TRUE` and no Scala code
-- reads it; the sentinel-profile **seed** plus its policy wiring lands
-- atomically with step 2 (#1771) — folding the seed into step 2 keeps the
-- /api/profiles wire response unchanged for step 1 (no third profile leaks
-- before step 2 hides it). The unique partial index here makes that seed
-- idempotent: step 2 can `INSERT … ON CONFLICT DO NOTHING` without race.

-- 1. Add the is_global sentinel flag, with a unique partial index so at
--    most one Global profile can ever exist. Constant default ⇒
--    metadata-only ADD COLUMN in PG 11+ (no table rewrite).
ALTER TABLE profiles
  ADD COLUMN is_global BOOLEAN NOT NULL DEFAULT FALSE;

CREATE UNIQUE INDEX profiles_is_global_unique
  ON profiles (is_global) WHERE is_global = TRUE;

-- 2. Drop the legacy household-global tables. Prod-verified empty
--    2026-06-16; no backfill.
DROP TABLE IF EXISTS global_allow;
DROP TABLE IF EXISTS global_blocks;
DROP TABLE IF EXISTS global_blocklists;

-- 3. Drop the legacy household-global flag columns added by V48. Prod
--    values are at the install defaults (verified 2026-06-16); step 2
--    (#1771) reintroduces equivalent semantics on the sentinel profile via
--    its existing `paused` / `block_ip_only` columns (plus the computed
--    BlockReason).
ALTER TABLE household_settings
  DROP COLUMN global_blocked,
  DROP COLUMN global_block_reason,
  DROP COLUMN global_block_ip_only;
