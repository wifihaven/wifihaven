-- V79__alerts_household_mac_fk.sql
-- Multi-tenant EPIC (#622) — CONSTRAIN step (3 of 3) of the #2283 expand/contract,
-- sequenced AFTER the #2283 source PR deploys (V78 added the column; the source
-- PR populates it on every insert and scopes reads/dedup on it).
--
-- ── What this restores ───────────────────────────────────────────────────────
-- V74__drop_devices_mac_key_rework_alerts_fk.sql (#2277) DROPPED the V37
-- single-column `alerts_mac_fkey` (`alerts.mac REFERENCES devices(mac) ON DELETE
-- CASCADE`) because it depended on the global devices(mac) unique index V74 was
-- removing. V74 deliberately did NOT repoint it to the composite key, because the
-- composite FK is COUPLED to a source change: image-(N-1) alert inserts did not
-- set `household_id`, so a composite FK would reject any alert raised for a
-- non-household-1 device (V74 header: it turns `MultiTenantIsolationSpec` pin 1
-- RED). Now that the #2283 source PR stamps `household_id` on every insert — the
-- discovering router's household for new_device, the device's own household for
-- access_request — that coupling is resolved and the composite FK can land.
--
--   (household_id, mac) → devices(household_id, mac) ON DELETE CASCADE
--
-- references V65's `uq_devices_household_mac` UNIQUE(household_id, mac), the sole
-- MAC-uniqueness rule after V74. This restores the per-household cascade that
-- kept an alert from outliving its device (lost in the V74→#2283 interim), now
-- correctly scoped to the tenancy key so deleting household A's device never
-- touches household B's same-MAC alerts.
--
-- ── Orphan purge (required before the FK can be added) ───────────────────────
-- Between V74 and this migration the FK carried no ON DELETE CASCADE, so a
-- deleted device could leave its alerts orphaned. The composite FK is validated
-- against existing rows at ADD time, so any orphan (no matching
-- (household_id, mac) device row) would block it. We DELETE those first — which
-- is exactly what the restored ON DELETE CASCADE would have done to them — so
-- the constraint applies cleanly. On a healthy install this deletes zero rows.
--
-- ── Drop the DEFAULT ─────────────────────────────────────────────────────────
-- V78 added `household_id NOT NULL DEFAULT 1` so image-(N-1) inserts (which
-- omitted the column) stayed valid during the expand window. The #2283 source PR
-- now sets `household_id` explicitly on EVERY insert, so the default is no longer
-- needed and is dropped — an unset household_id must fail loudly (NOT NULL with
-- no default), never silently land in household 1.
--
-- ── SCHEMA-ONLY PR (docs/process/migrations.md#migrations-back-compat, #2098) ─
-- Ships alone (SQL + docs only — no source/tests/CI/fixtures). This PR is stacked
-- on the #2283 source PR, so its diff-vs-base is just this file and the existing
-- api.test feature suite (with the source change present) is the back-compat
-- gate: it applies this migration and exercises every alert-insert path
-- (raiseNewDevice, createAccessRequest) against the composite FK. It must merge
-- only AFTER the source PR is on main and deployed.
--
-- ── Prod data-volume (docs/process/migrations.md#migrations-prod-data-volume) ─
-- `alerts` is a tiny bounded table. The orphan DELETE and both ALTERs touch only
-- these few rows / metadata. The FK ADD validates existing rows with an index
-- (uq_devices_household_mac) backing the lookup. Sub-second, far inside the
-- 15-minute Render port-scan window. No unbounded-growth table is touched.

-- 1. Purge any alert orphaned during the V74→#2283 interim (no matching
--    (household_id, mac) device row) — the restored CASCADE would have removed
--    them — so the composite FK validates cleanly. Zero rows on a healthy DB.
DELETE FROM alerts a
 WHERE NOT EXISTS (
   SELECT 1 FROM devices d
    WHERE d.household_id = a.household_id
      AND d.mac = a.mac
 );

-- 2. Restore the per-household FK + ON DELETE CASCADE, repointed to the composite
--    (household_id, mac) key (V65's uq_devices_household_mac).
ALTER TABLE alerts
  ADD CONSTRAINT alerts_household_mac_fkey
  FOREIGN KEY (household_id, mac)
  REFERENCES devices (household_id, mac)
  ON DELETE CASCADE;

-- 3. Drop the expand-window DEFAULT: the #2283 source PR sets household_id on
--    every insert, so an unset value must now fail loudly rather than default to
--    household 1.
ALTER TABLE alerts ALTER COLUMN household_id DROP DEFAULT;
