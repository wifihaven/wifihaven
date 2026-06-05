-- V49__add_pause_mode.sql
-- #1418: per-profile "hard pause" — a true off-switch that blocks even the
-- otherwise-allowlisted hosts (an allowed-mode app's hosts, the #1105 exempt
-- carve-out, and the #1307 global infra allowlist).
--
-- Today's pause is "soft": a paused profile drops all forwarded traffic EXCEPT
-- its `extraAllowed` hosts, so an allowed app + the infra allowlist stay
-- reachable through a pause (#421/#1413). "Hard pause" is the opposite — no
-- carve-outs.
--
-- No new wire field: hard pause is expressed *functionally* in the snapshot per
-- the wire-shape rules in CLAUDE.md ("express new policy via existing functional
-- fields — never add a field for the concept itself"). When a profile is
-- hard-paused, PolicyService.computeBlockRules ships `blocked = true` with
-- `extraAllowed` emptied (only the block-page / admin-UI hosts survive) for that
-- MAC. The router's @blocked_macs carve-out is driven purely by the presence of
-- `extraAllowed`, so an empty list ⇒ the MAC drops unconditionally with no
-- `ip daddr != @ea_…` exception. The router stays oblivious to "hard vs soft".
--
-- This is a SCHEMA-ONLY migration (per the migration-isolation rule in
-- CLAUDE.md): it ships with no source or tests. The PolicyService /
-- ProfileRepo / route / SPA changes that READ and WRITE this column are the
-- follow-up PR. Until then the column is inert — nothing reads it, every
-- existing profile is 'soft', and behavior is unchanged.
--
-- `profiles` is a small metadata table, NOT one of the unbounded-growth event
-- tables (traffic_reports, connection_events, …). Adding a column with a
-- constant default is metadata-only in PG 11+ (no table rewrite), so this is
-- safe on the Flyway startup critical path even at prod data volume.
--
-- Nullable-by-default contract: the column has a NOT NULL DEFAULT 'soft', so
-- image-(N-1) — which never names `pause_mode` in any INSERT/UPDATE — keeps
-- working unchanged; existing rows and any new rows it writes are 'soft', i.e.
-- today's behavior.

ALTER TABLE profiles
  ADD COLUMN pause_mode TEXT NOT NULL DEFAULT 'soft'
    CHECK (pause_mode IN ('soft', 'hard'));
