-- V87__named_schedules_household_unique_block_events_router.sql
-- Closes the three schema-level tenancy gaps the #2563 isolation sweep found
-- and no existing issue covered. Refs #2572, #2563, #2126, #2142, #2125, #2266.
--
-- SCHEMA-ONLY PR (docs/process/migrations.md#migrations-back-compat): migration
-- + docs, nothing else. The existing feature suite is the back-compat gate —
-- it applies this migration and then drives image-(N-1)'s `api/src` against it.
-- The source that adopts the new shape (household-scoped
-- `NamedScheduleRepo.findByName`, and stamping `block_events.router_id` at the
-- `POST /api/router/decision` call site) lands in the follow-up source PR,
-- together with its tests.
--
-- ── 1. named_schedules.name: global UNIQUE → UNIQUE (household_id, name) ─────
-- V50 created `name TEXT NOT NULL UNIQUE` when the product was single-household.
-- V72 (#2126) added `household_id` and scoped the reads but deliberately left
-- the name unique global ("a separate change" — V72 lines 47-52); that change
-- was never filed until #2572. #2125 widened the equivalent `devices` and
-- `time_usage` global uniques; `named_schedules` was missed.
--
-- Live effect of leaving it: household A names a schedule "Bedtime" and
-- household B's `POST /api/schedules` with the same name fails on a row B
-- cannot see. "Bedtime" / "School hours" / "Homework" are exactly the names
-- every household picks, so this is a routine collision, and a (weak)
-- enumeration oracle for other households' schedule names.
--
-- Back-compat under this swap: the widened key is strictly WEAKER than the one
-- it replaces (every pair unique under UNIQUE(name) is also unique under
-- UNIQUE(household_id, name)), so no existing row can violate it and no
-- image-(N-1) write can start failing. Image-(N-1)'s `ScheduleRoutes` also
-- keeps its unscoped `findByName` name-taken pre-check, which still rejects a
-- cross-household duplicate at the source layer — so no duplicate name can
-- actually be created until the follow-up source PR scopes that check. That is
-- deliberate: `findByName` reads with Doobie `.option`, which fails on multiple
-- rows, so the read must be scoped in the same change that lets duplicates
-- exist. Dropping the constraint here alone is inert and safe.
--
-- ── 2. named_schedules.household_id: DROP DEFAULT ───────────────────────────
-- V72 line 83 set `DEFAULT 1` as expand-window scaffolding so image-(N-1)
-- inserts (which omitted the column) landed in household 1. The #2126 source
-- PR shipped and every insert now stamps `household_id` explicitly
-- (`NamedScheduleRepoLive.create`, api/src/db/Repos.scala) — it is the ONLY
-- insert path into this table in the tree. So the default is dead scaffolding,
-- and it is precisely the dark-by-default shape #2265/#2266 banned: a missing
-- scope silently resolving to household 1 instead of failing loudly. The column
-- stays NOT NULL, so after this an unscoped insert errors instead of guessing.
-- (#2142 tracks the same leftover on `profiles` / `household_settings` /
-- `time_usage` — the three V65 tables. It predates V72 and does not cover this
-- fourth one.)
--
-- ── 3. block_events: no tenancy key at all → add router_id ──────────────────
-- V2 line 52 created `block_events(id, mac, hostname, reason, ts)`. Unlike
-- every other growth table it has neither `household_id` NOR `router_id`, so
-- there is not even a transitive route to a household. Its only attribution key
-- is `mac`, and V74 dropped `devices_mac_key`, so a MAC no longer identifies a
-- household.
--
-- Nothing leaks today: both reads (`BlockEventRepo.recent`, `.listForMac`) are
-- dead — no `api/src` caller (#2571). The finding is that the schema is LOADED:
-- the moment any panel, export, or support-agent read is pointed at this table
-- it leaks across households with no predicate available to stop it, because
-- the key was never recorded at write time. Retro-fitting one for existing rows
-- is impossible, so the column is added now, ahead of the first reader.
--
-- `router_id` (not `household_id`) is the key, matching `traffic_reports` (V2)
-- and `connection_events` (V4): the write path — `POST /api/router/decision` in
-- RouterRoutes — authenticates a router and already holds it, and
-- `routers.household_id` (V65) gives the household by join. Recording the
-- narrower fact keeps a single source of truth for the router→household
-- mapping instead of denormalizing it a second time.
--
-- NULLABLE, no default, no backfill. Pre-existing rows have no derivable
-- router: `mac` cannot identify one post-V74, and inventing a value would be a
-- confabulation. Nullable also makes the add metadata-only (see below) and
-- keeps image-(N-1)'s `INSERT INTO block_events(mac,host_type,host_value,
-- reason,reason_text)` — which omits the column — working unchanged, which is
-- what the back-compat gate exercises. The follow-up source PR makes the field
-- required in `BlockEventInsert`, so every NEW row carries it; NULL then means
-- exactly "written before V87", not "unknown scope".
--
-- ON DELETE CASCADE + the router index mirror `traffic_reports.router_id` /
-- `connection_events.router_id`. `RouterRepo.delete` (api/src/db/Repos.scala)
-- is a live path, and an unindexed FK would make every router delete seq-scan
-- an unbounded-growth table.
--
-- ── Prod data-volume (docs/process/migrations.md#migrations-prod-data-volume) ─
-- Measured against PROD, not dev/staging, on 2026-08-02 (read-only psql against
-- wifihaven-pg-prod, PostgreSQL 16.14, schema at Flyway V86):
--   * block_events    — 0 rows, 32 kB total relation size, relkind 'r' (a plain
--     table; it was never partitioned, unlike traffic_reports/connection_events).
--     A 30-day retention sweep (#2086) keeps it bounded regardless.
--   * named_schedules — 2 rows;  households — 2 rows;  routers — 2 rows.
-- So every statement here is trivially instant at real prod volume, far inside
-- the 15-minute Render port-scan window. Independently of the row count, the
-- nullable `ADD COLUMN` with no default is metadata-only in PG 11+ (no table
-- rewrite), which is what would keep it safe as block_events grows — but the
-- count above is the measured fact, not the inference.
--
-- No CREATE INDEX CONCURRENTLY is used anywhere here: Flyway wraps each
-- migration in a transaction and CONCURRENTLY cannot run inside one. The plain
-- index builds are on a 0-row and a 2-row table.

-- 1. Widen the schedule-name uniqueness to per-household.
ALTER TABLE named_schedules DROP CONSTRAINT named_schedules_name_key;
ALTER TABLE named_schedules
  ADD CONSTRAINT named_schedules_household_name_key UNIQUE (household_id, name);

-- 2. V72's idx_named_schedules_household is now redundant: the unique index
--    backing the constraint above leads with household_id, so it already serves
--    every `WHERE household_id = ?` lookup (listAllForHousehold, householdOf).
DROP INDEX idx_named_schedules_household;

-- 3. Retire the expand-window default; every insert stamps household_id.
ALTER TABLE named_schedules ALTER COLUMN household_id DROP DEFAULT;

-- 4. Give block_events a tenancy key (transitively, via routers.household_id).
ALTER TABLE block_events
  ADD COLUMN router_id UUID REFERENCES routers(id) ON DELETE CASCADE;
CREATE INDEX idx_block_events_router_ts ON block_events(router_id, ts DESC);
