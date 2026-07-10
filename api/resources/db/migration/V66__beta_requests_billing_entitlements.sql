-- V66__beta_requests_billing_entitlements.sql
-- Multi-tenant Phase-5 sub-issue P5-1 (#2131, epic #622). Authored against the
-- authoritative Phase-5 design doc docs/design/multi-tenant-launch.md (PR #2141,
-- merged); business inputs in docs/design/pricing-analysis.md §6–§7. Where the
-- doc supersedes #2131's original issue body, the doc wins (noted inline below).
--
-- SCHEMA-ONLY PR: per docs/process/migrations.md#migrations-back-compat this
-- migration ships alone (SQL + docs). The source that reads/writes these
-- tables lands in follow-ups P5-2..P5-8 (#2132–#2140). The existing feature
-- suite is the back-compat gate.
--
-- Contents:
--   1. beta_requests       — request-gated beta signup queue (doc §3.1)
--   2. household_billing   — per-household Stripe linkage + subscription state (doc §5.1)
--   3. households.router_cap — per-household router entitlement (doc §6)
--   4. households.slug     — human-facing unique household id for login (doc §4, #2140)
--   5. DROP the V65 household_id DEFAULT 1s proven explicit (#2139 audit) —
--      4 of 7 tables; the rest deferred to #2142 (see section 5)
--
-- ── Prod data-volume (docs/process/migrations.md#migrations-prod-data-volume) ─
-- Touches only small bounded tables (households: 1 row on prod; users: 6) and
-- creates two new empty tables. No unbounded-growth table (traffic_reports,
-- connection_events, block_events, rollups) is scanned or rewritten — all
-- statements are metadata-only or single-row; well inside the 15-minute
-- Render port-scan window.

-- ── 1. beta_requests ─────────────────────────────────────────────────────────
-- The beta-access request queue: beta is request-gated, not open signup; the
-- operator manually approves (pricing §5). household_id is nullable — it is
-- stamped at provisioning time (P5-2 #2132), after approval.
-- NB: named beta_requests, NOT "access requests" — AccessRequest is an
-- existing block-page domain concept (shared/src/Models.scala
-- AccessRequestKind); do not collide.
CREATE TABLE beta_requests (
  id                BIGSERIAL PRIMARY KEY,
  email             TEXT NOT NULL UNIQUE,
  name              TEXT,
  note              TEXT,
  status            TEXT NOT NULL DEFAULT 'pending'
                      CHECK (status IN ('pending', 'approved', 'rejected')),
  requested_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  decided_at        TIMESTAMPTZ,
  decided_by        BIGINT REFERENCES users(id),
  invite_token_hash TEXT UNIQUE,
  invite_expires_at TIMESTAMPTZ,
  household_id      BIGINT REFERENCES households(id)
);

CREATE INDEX idx_beta_requests_status ON beta_requests(status);

-- ── 2. household_billing ─────────────────────────────────────────────────────
-- One row per household: the Stripe linkage + subscription state (doc §5.1).
-- Pricing §7: entitlements resolve per household from the subscription's
-- Price/Product, never from a global constant; the Stripe surface is
-- deliberately minimal (Customer at provisioning, subscription only at
-- conversion).
--
-- SUPERSEDES #2131's original issue body (operator decision 2026-07-09, doc
-- §5.1 + §5.3, appended to the issue as a scope-change note): the read-only
-- grace/locked model is dropped. On lapse, enforcement stops (fail-open — the
-- router gets a permissive snapshot) rather than the household going read-only.
-- So:
--   * status CHECK is ('beta','active','lapsed') — 'grace'/'locked' removed.
--   * lapsed_at TIMESTAMPTZ is added (a record of WHEN the lapse happened;
--     NOT a deletion timer — there is no scheduled purge, doc §5.3).
-- ONE status machine serves both the non-conversion flip (#2137) and the
-- dunning lapse (#2135); neither invents its own states.
--   status: 'beta' → 'active'   (checkout.session.completed)
--           'beta' → 'lapsed'   (non-conversion at flip)
--           'active' → 'lapsed' (dunning lapse)
--           'lapsed' → 'active' (recovery via Checkout/Portal)
CREATE TABLE household_billing (
  household_id           BIGINT PRIMARY KEY REFERENCES households(id),
  stripe_customer_id     TEXT UNIQUE,
  stripe_subscription_id TEXT UNIQUE,
  price_id               TEXT,
  status                 TEXT NOT NULL DEFAULT 'beta'
                           CHECK (status IN ('beta', 'active', 'lapsed')),
  founding               BOOLEAN NOT NULL DEFAULT TRUE,
  current_period_end     TIMESTAMPTZ,
  lapsed_at              TIMESTAMPTZ,
  updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Seed the operator household (id 1). status 'active', NOT the 'beta'
-- default: the operator household is internal and never billing-gated —
-- 'beta' would put it in the request-gated funnel and eventually the lapse
-- path (doc §5.3), which must never apply to it. founding stays TRUE.
INSERT INTO household_billing (household_id, status, founding)
VALUES (1, 'active', TRUE);

-- ── 3. households.router_cap ─────────────────────────────────────────────────
-- Per-household router entitlement (pricing §6): the public launch plan is
-- 1 router; multi-router is internal/founding-only at launch, with the later
-- multi-home tier raising the cap per household — never a global constant.
-- This DEFAULT 1 is a real entitlement default (every new household starts
-- on the 1-router plan), not a V65-style backfill crutch — it stays.
ALTER TABLE households ADD COLUMN router_cap INT NOT NULL DEFAULT 1;

-- Operator household runs multiple routers (gateway + APs); flag it past the
-- public cap per §6 ("internal and founding households simply flagged past
-- the cap").
UPDATE households SET router_cap = 10 WHERE id = 1;

-- ── 4. households.slug ───────────────────────────────────────────────────────
-- Human-facing unique household identifier, used on the login form (#2140 —
-- households.name is not unique). P5-2 (#2132) generates slugs at
-- provisioning; P5-8 (#2140) consumes them at login. Lowercase [a-z0-9-]
-- only, CHECK-enforced.
--
-- Deviation from the #2131 sketch: NOT NULL is NOT set here. Currently-
-- deployed source and the test seeders create households without a slug
-- (INSERT INTO households(name) — TestDatabase.scala:344,
-- RouterHouseholdBindingSpec.scala:48, JwtHouseholdClaimSpec.scala:44), so
-- SET NOT NULL turns the back-compat gate red (verified: 17 tests) and a
-- schema-only PR cannot touch those seeders. Same expand/contract shape as
-- the V65 DEFAULT-1 scaffolding: TODO(#2142) makes every household-creating
-- write stamp slug explicitly, then SET NOT NULL in a follow-up migration.
-- UNIQUE and the format CHECK are NULL-tolerant, so they land now.
ALTER TABLE households ADD COLUMN slug TEXT;

UPDATE households SET slug = 'default' WHERE id = 1;
UPDATE households SET slug = 'household-' || id WHERE slug IS NULL;

ALTER TABLE households ADD CONSTRAINT uq_households_slug UNIQUE (slug);
ALTER TABLE households ADD CONSTRAINT households_slug_format_check
  CHECK (slug ~ '^[a-z0-9-]+$');

-- ── 5. DROP the V65 household_id DEFAULT 1s (4 of 7) ─────────────────────────
-- V65 added household_id BIGINT NOT NULL DEFAULT 1 to seven tables so that
-- then-deployed source (which inserted without household_id) kept working;
-- the default was always scaffolding to be removed once every write path
-- stamps household_id explicitly (expand/contract, V65 header).
--
-- PR #2139's audit greps every INSERT into these seven tables in api/src and
-- shows each site stamps household_id explicitly — users/profiles/
-- household_settings/time_extensions fixed in #2139 itself; devices/
-- time_usage/time_extensions(MAC-keyed) explicit since #2108; routers since
-- #2106. No other INSERTs into these tables exist in api/src.
--
-- DROPPED here (every write path explicit, api/src AND api/test seeders):
-- users, routers, devices, time_extensions.
--
-- KEPT (documented per the drop-only-where-proven rule): profiles,
-- household_settings, time_usage. Raw test-side INSERTs still rely on the
-- default — TestDatabase.scala:83 (household_settings template seed),
-- TestDatabase.scala:90 + LegacyAppMigrationSpec.scala:75/:139 (profiles),
-- RetentionSweepJobSpec.scala:89 (time_usage) — and a schema-only PR cannot
-- touch test sources (docs/process/migrations.md#migrations-back-compat);
-- dropping them turns the back-compat gate red at template init (verified).
-- TODO(#2142): make those seeders explicit, then drop the remaining three
-- defaults in a follow-up migration.
--
-- Verified live (psql \d + information_schema against a fresh V65 schema,
-- 2026-07-09): exactly these seven tables carry household_id with
-- column_default = 1. ALTER COLUMN ... DROP DEFAULT is metadata-only.
ALTER TABLE users           ALTER COLUMN household_id DROP DEFAULT;
ALTER TABLE routers         ALTER COLUMN household_id DROP DEFAULT;
ALTER TABLE devices         ALTER COLUMN household_id DROP DEFAULT;
ALTER TABLE time_extensions ALTER COLUMN household_id DROP DEFAULT;
