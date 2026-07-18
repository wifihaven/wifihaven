-- V73__profiles_is_global_per_household.sql
-- #2286 (multi-tenant, epic #622 / gap 4 of #2108). Widen the global-sentinel
-- uniqueness from installation-wide to per-household.
--
-- V59 added `is_global` with a partial UNIQUE index on `(is_global) WHERE
-- is_global = TRUE`, which permits at most ONE global-sentinel row in the whole
-- `profiles` table — correct while only the default install (household 1)
-- existed. V65 then made `profiles` multi-tenant (added `household_id`) but did
-- NOT widen this index, so the design of "one global-sentinel profile per
-- household" (#2108; the household filter already lives in
-- `ProfileRepo.getGlobalForHousehold` / `PolicyService`) was physically
-- blocked: a second household's `INSERT ... is_global = TRUE` would collide with
-- household 1's row. Beta-provisioned households therefore got no global profile
-- at all, and `GET /api/profiles/global` 404s for them.
--
-- Replace the single-column partial index with a `(household_id, is_global)`
-- partial index so at most one global sentinel can exist PER household, while
-- still permitting one per household. Widening a uniqueness constraint is
-- strictly more permissive, so image-(N-1) code (the seed's
-- `INSERT ... ON CONFLICT DO NOTHING` for household 1, the unscoped `getGlobal`
-- reader) keeps working unchanged against this schema — the existing api.test
-- suite is the back-compat gate.
--
-- `profiles` is a tiny table (8 rows on prod at V65's own estimate); DROP INDEX
-- + CREATE UNIQUE INDEX is sub-second and holds no long lock. Not an
-- unbounded-growth event table, so no prod-scale runtime concern.
--
-- PROD has a single household today, so no backfill is required here: the code
-- PR (#2286 follow-up) seeds each NEW household's global sentinel at
-- provisioning; household 1 already has its row (Main.scala boot seed / V59
-- lineage).

DROP INDEX profiles_is_global_unique;

CREATE UNIQUE INDEX profiles_is_global_unique
  ON profiles (household_id, is_global) WHERE is_global = TRUE;
