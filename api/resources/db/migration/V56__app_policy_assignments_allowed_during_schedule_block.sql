-- #1679: Per-allowed-app toggle "allowed during schedule block".
--
-- Adds a per-(profile, app) flag to app_policy_assignments controlling
-- whether the app's host-set remains in the snapshot's per-MAC extraAllowed
-- set during a Schedule-reason block (blockReason = Schedule). Other block
-- reasons (Paused / TimeLimit / Manual) ignore this toggle.
--
-- The wire shape is UNCHANGED: this is consumed entirely server-side in
-- PolicyService snapshot assembly (per AGENTS.md "Express new policy via
-- existing functional fields"). The router still sees extraAllowed; it just
-- contains different hosts during a schedule window when this is false.
--
-- DEFAULT TRUE: existing assignments are preserved as "always allowed", which
-- matches current behavior (extraAllowed beats @blocked_macs, see #421). The
-- next image after this migration deploys is backward-compatible — code that
-- doesn't read this column behaves exactly as before.
--
-- Schema-only per AGENTS.md §migrations-back-compat. Code adoption follows in
-- a separate PR.

ALTER TABLE app_policy_assignments
  ADD COLUMN allowed_during_schedule_block BOOLEAN NOT NULL DEFAULT TRUE;
