-- V72__named_schedules_household.sql
-- (renumbered from V71 after #2279's V71__press_messages.sql landed on main first)
-- Multi-tenant isolation follow-up to sub-issue E (#2108, epic #2085/#622,
-- design docs/design/multi-tenant-isolation.md §2 gap 4). Refs #2126, #622.
--
-- Adds the tenancy key to `named_schedules`. V50 created the table single-
-- household (no `household_id` — see its "Single-household" note), and V65
-- (#2104) added `household_id` to the four roots + screen-time tables but NOT
-- to `named_schedules`. So `GET /api/schedules` (NamedScheduleRepo.listAll)
-- returns EVERY household's named schedules — a cross-tenant read with no column
-- to filter on. `named_schedules` has no direct profile FK (it attaches to
-- profiles M:N via `profile_schedule_rules`), so scoping "via an attached
-- profile" would wrongly hide freshly-created-but-unattached schedules from the
-- authoring UI. The correct fix is this schema change: a real `household_id`
-- column the source-side adoption (#2126 source PR) filters/stamps on.
--
-- SCHEMA-ONLY PR: per docs/process/migrations.md#migrations-back-compat this
-- migration ships alone (SQL + docs). The source that reads/writes
-- `named_schedules.household_id` (ScheduleRoutes adopting `listAllForHousehold`
-- + stamping household on create, and the per-id household guards) lands in the
-- follow-up source PR. The existing feature suite is the back-compat gate: it
-- applies this migration against image-(N-1) source, whose create still does
-- `INSERT INTO named_schedules(name,description)` (no household_id) and whose
-- reads select `id,name,description` — both unaffected by an additive column
-- that carries a constant DEFAULT (see below).
--
-- ── Backfill: derive from an attached profile; default the rest to household 1 ─
-- The column is added nullable, backfilled deliberately, then set NOT NULL:
--   1. A schedule referenced by a profile (via `profile_schedule_rules`) inherits
--      that profile's `household_id`. `MIN(...)` makes it deterministic even if a
--      schedule were somehow attached across households (impossible today —
--      single-household prod, every profile is household 1 post-V65 — but the
--      subquery is order-independent regardless).
--   2. Any schedule not referenced by a profile (freshly created and unattached,
--      or attached only via app rules) defaults to the single existing install's
--      household (`HouseholdId.Default` = 1). Prod is single-household, so this
--      is exact, not a guess.
--
-- ── NOT NULL DEFAULT 1 (mirrors V65) — why the constant default stays ─────────
-- Like V65, the settled column is NOT NULL DEFAULT 1: a CONSTANT default makes
-- image-(N-1) inserts that omit `household_id` land in household 1 (the
-- back-compat gate), and makes the add metadata-only in PG 11+ (no table
-- rewrite). A later expand/contract migration can drop the default once every
-- write path stamps `household_id` explicitly (the #2126 source PR does), same
-- as V65's follow-up E plan.
--
-- ── Name uniqueness left global (out of scope, additive-only) ─────────────────
-- V50's `named_schedules.name TEXT NOT NULL UNIQUE` stays a GLOBAL unique here.
-- Widening it to UNIQUE(household_id, name) is a separate change (and a gate
-- risk under the additive-only rule); single-household prod is unaffected, and
-- the name-taken check is not a data-leak surface. Tracked with the broader
-- per-household-uniqueness work, not this leak fix.
--
-- ── Prod data-volume (docs/process/migrations.md#migrations-prod-data-volume) ─
-- `named_schedules` is a bounded metadata table — rows scale with the handful of
-- schedules the operator defines, NOT with event volume (it is not one of the
-- unbounded-growth tables: traffic_reports, connection_events, block_events,
-- rollups). The metadata-only add, the tiny backfill UPDATEs, and the index
-- build are each sub-millisecond even at full prod volume — no rewrite lock, far
-- inside the 15-minute Render port-scan window.

-- 1. Add the column nullable (no default yet) so the backfill is deliberate.
ALTER TABLE named_schedules ADD COLUMN household_id BIGINT REFERENCES households(id);

-- 2. Backfill from an attached profile where the schedule is referenced by one.
UPDATE named_schedules ns
   SET household_id = (
     SELECT MIN(p.household_id)
       FROM profile_schedule_rules psr
       JOIN profiles p ON p.id = psr.profile_id
      WHERE psr.schedule_id = ns.id
   )
 WHERE ns.household_id IS NULL
   AND EXISTS (
     SELECT 1 FROM profile_schedule_rules psr WHERE psr.schedule_id = ns.id
   );

-- 3. Any still-unattached schedule → the single existing install (household 1).
UPDATE named_schedules SET household_id = 1 WHERE household_id IS NULL;

-- 4. Enforce presence + a constant default so image-(N-1) inserts (which omit
--    household_id) keep landing in household 1 (back-compat gate).
ALTER TABLE named_schedules ALTER COLUMN household_id SET DEFAULT 1;
ALTER TABLE named_schedules ALTER COLUMN household_id SET NOT NULL;

-- 5. Index the tenancy predicate (mirrors V65's idx_{profiles,devices,...}_household).
CREATE INDEX idx_named_schedules_household ON named_schedules(household_id);
