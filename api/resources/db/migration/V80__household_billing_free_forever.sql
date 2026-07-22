-- V80__household_billing_free_forever.sql
-- Multi-tenant EPIC (#622) — schema step for #2356: a persisted, grantable
-- `free_forever` billing status.
--
-- ── What #2356 needs ─────────────────────────────────────────────────────────
-- The operator wants a first-class, GRANTABLE billing status for households
-- that must never be billed and never enter the beta→paid flip funnel
-- (partners, friends-and-family, internal households; household 1 seeded as the
-- first one). Today `household_billing.status` has an inline CHECK from V66:
--
--   status TEXT NOT NULL DEFAULT 'beta'
--     CHECK (status IN ('beta', 'active', 'lapsed'))
--
-- so writing `'free_forever'` is rejected at the DB. This migration ADDITIVELY
-- widens that CHECK to admit the new value. It adds NO column and changes no
-- other constraint — the new status is just another value the existing column
-- may hold.
--
-- ── Semantics of the new value (enforced by the #2356 source PR, not here) ────
-- A `free_forever` household is never charged, never flip-targeted (the cohort
-- and flip queries already filter `status='beta'`, so a different status is
-- excluded by construction — no INNER JOIN that could drop legitimate single-
-- household rows), and its Checkout/Portal surfaces are hidden/no-op. It is NOT
-- a lapse: PolicyService only serves the permissive snapshot for `'lapsed'`, so
-- a `free_forever` household keeps full enforcement (it uses the product).
-- The status column stays the ONE billing state (single-source-of-truth) — this
-- is a new value on the existing machine, not a parallel flag.
--
-- ── Why the CHECK is drop-and-recreate ───────────────────────────────────────
-- V66 declared the CHECK inline on the column, so Postgres auto-named it
-- `household_billing_status_check`. Widening an IN-list means dropping that
-- constraint and recreating it with the extra value; there is no in-place
-- "add allowed value" for a CHECK. Existing rows all hold one of the three
-- prior values, so the recreated (superset) constraint validates instantly.
--
-- ── SCHEMA-ONLY PR (docs/process/migrations.md#migrations-back-compat, #2098) ─
-- Ships alone (this SQL + docs only — no source/tests/CI/fixtures). The existing
-- api.test feature suite run against this schema is the unconditional back-compat
-- gate: the widening is a pure superset, so image-(N-1) source (which only ever
-- writes 'beta'/'active'/'lapsed') keeps working unchanged. The #2356 source PR
-- that first WRITES 'free_forever' must not deploy until this migration has
-- deployed (ordering noted on both PRs).
--
-- ── Prod data-volume (docs/process/migrations.md#migrations-prod-data-volume) ─
-- `household_billing` is a tiny bounded table (one row per household — dozens at
-- beta scale, not a traffic-growth table). Dropping/recreating a CHECK on it is
-- a metadata operation plus a one-shot validation scan of a handful of rows:
-- sub-millisecond, far inside the 15-minute Render port-scan window. No V-split
-- needed. No unbounded table (traffic_reports / connection_events / rollups) is
-- touched.

-- Widen the status domain: drop V66's inline CHECK and recreate it as a superset
-- that admits 'free_forever'. Named explicitly so a future widening can target it
-- by name rather than relying on Postgres's auto-generated identifier.
ALTER TABLE household_billing
  DROP CONSTRAINT household_billing_status_check;

ALTER TABLE household_billing
  ADD CONSTRAINT household_billing_status_check
  CHECK (status IN ('beta', 'active', 'lapsed', 'free_forever'));
