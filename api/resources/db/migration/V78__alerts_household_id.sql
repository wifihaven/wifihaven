-- V78__alerts_household_id.sql
-- Multi-tenant EPIC (#622) — source-follow-up #2283 to the #2277 schema PR
-- (which shipped as V74__drop_devices_mac_key_rework_alerts_fk.sql).
--
-- ── What V74 left open ───────────────────────────────────────────────────────
-- V74 (#2277) dropped the global `devices_mac_key` UNIQUE(mac) so the SAME
-- randomized MAC can now exist as a device row in two different households
-- (#2151 scenario 4: cross-household same-MAC discovery). To do that it also had
-- to DROP `alerts_mac_fkey` (the single-column `alerts.mac REFERENCES
-- devices(mac)` FK from V37), because that FK depended on the devices(mac)
-- unique index it was removing. V74 deliberately did NOT repoint the FK to the
-- composite `(household_id, mac)` key, because the composite FK is COUPLED to a
-- source change and cannot ship schema-only: image-(N-1) alert-insert paths
-- (`raiseNewDevice`, `createAccessRequest`) don't set a household, so a composite
-- FK would reject any alert raised for a non-household-1 device.
--
-- Until #2283 lands, alert reads isolate per-household only via #2108's
-- transitive `devices` join (`AlertRepoLive.baseSelect` LEFT JOIN devices
-- d ON d.mac = a.mac, filtered on d.household_id). That join is UNAMBIGUOUS
-- only while MACs are distinct across households — exactly the invariant V74
-- just removed. With a shared MAC, `d.mac = a.mac` matches BOTH households'
-- device rows, so the alert leaks into the wrong household's list, and
-- `raiseNewDevice`'s GLOBAL `WHERE NOT EXISTS (… mac …)` dedup collapses two
-- households' new-device discovery of the same MAC into ONE shared alert.
--
-- ── This migration (expand/contract step 1 of 3, #2283) ─────────────────────
-- Give `alerts` its OWN tenancy key so reads/dedup can scope on it instead of
-- the now-ambiguous device join:
--
--   ADD COLUMN household_id BIGINT NOT NULL DEFAULT 1 REFERENCES households(id)
--
-- This is INERT until the #2283 source PR populates it on insert and tightens
-- the read join + dedup to `a.household_id`. `NOT NULL DEFAULT 1` keeps
-- image-(N-1) inserts valid (they omit the column → it defaults to household 1,
-- the single backfill household), exactly as V65 (#2104) added household_id to
-- the four roots. The composite FK `(household_id, mac) → devices(household_id,
-- mac) ON DELETE CASCADE` and the drop of `DEFAULT 1` are the expand/contract
-- CONSTRAIN step (#2283 step 3), sequenced AFTER the source PR deploys so every
-- insert populates the column.
--
-- ── Backfill ─────────────────────────────────────────────────────────────────
-- Existing alert rows predate the shared-MAC capability (V74 shipped
-- immediately before this), so every alert's `mac` maps to exactly one device
-- row today and the backfill is unambiguous for current data. Defensively, if a
-- mac ever matches multiple device rows the correlated subquery picks the LOWEST
-- household_id deterministically (`ORDER BY d.household_id LIMIT 1`). Alerts with
-- NO matching device row (orphaned by the V74-interim loss of ON DELETE CASCADE)
-- keep the `DEFAULT 1` the ADD COLUMN already stamped — harmless, since the
-- source PR's read scope will drop any alert not belonging to the caller's
-- household.
--
-- ── SCHEMA-ONLY PR (docs/process/migrations.md#migrations-back-compat, #2098) ─
-- Ships alone (SQL + docs only — no source/tests/CI/fixtures). The existing
-- api.test feature suite run against this schema is the unconditional back-compat
-- gate: it proves image-(N-1) source still drives image-N's schema (the new
-- column is nullable-by-default from the insert's perspective, so unchanged
-- INSERTs keep working).
--
-- ── Prod data-volume (docs/process/migrations.md#migrations-prod-data-volume) ─
-- `alerts` is a tiny bounded table (admin-resolved new_device / access_request
-- rows — one row per discovered device / access request, not a traffic-growth
-- table). The ADD COLUMN with a constant default is a metadata-only change in
-- PG11+, and the backfill UPDATE scans only these few rows. Sub-second, far
-- inside the 15-minute Render port-scan window. No V-split needed. No unbounded
-- table (traffic_reports / connection_events / rollups) is touched.

-- 1. Add the tenancy key. Constant DEFAULT 1 → metadata-only add; existing rows
--    and future image-(N-1) inserts (which omit the column) both land in
--    household 1 until the #2283 source PR sets it explicitly.
ALTER TABLE alerts
  ADD COLUMN household_id BIGINT NOT NULL DEFAULT 1 REFERENCES households(id);

-- 2. Backfill from each alert's device row. Deterministic pick on the (today
--    unambiguous) mac→device mapping; orphaned alerts keep DEFAULT 1.
UPDATE alerts a
   SET household_id = (
     SELECT d.household_id
       FROM devices d
      WHERE d.mac = a.mac
      ORDER BY d.household_id
      LIMIT 1
   )
 WHERE EXISTS (SELECT 1 FROM devices d WHERE d.mac = a.mac);

-- 3. Index the new predicate so the #2283 source PR's household-scoped alert
--    reads (`WHERE a.household_id = $hh`) and per-household dedup stay cheap.
CREATE INDEX idx_alerts_household ON alerts(household_id);
