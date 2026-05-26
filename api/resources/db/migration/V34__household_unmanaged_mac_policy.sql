-- V33__household_unmanaged_mac_policy.sql
-- #961: first-class household policy for MACs the admin has never enrolled.
--
-- `policy`:
--   - "block" (default): unmanaged MACs are denied internet egress and HTTP/80
--     DNATs to the block page once router-side enforcement lands.
--   - "allow": unmanaged MACs flow freely; admin still gets a #711 alert and
--     can enroll the device at leisure.
--
-- `blockPage`: whether HTTP requests from blocked unmanaged MACs land on the
-- in-house block page (true) or get a plain RST (false). Only consulted when
-- policy = "block".
--
-- Router-side enforcement is FROZEN behind Gate 2 (#654); for v1 the field is
-- persisted + surfaced in the SPA only. The router-wire snapshot extension
-- and nft fallthrough chain land together in the follow-up issue.

ALTER TABLE household_settings
  ADD COLUMN unmanaged_mac_policy JSONB NOT NULL
    DEFAULT '{"policy":"block","blockPage":true}'::jsonb;
