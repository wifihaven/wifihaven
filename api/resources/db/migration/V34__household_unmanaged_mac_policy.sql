-- V34__household_unmanaged_mac_policy.sql
-- #961: first-class household policy for MACs the admin has never enrolled.
--
-- `policy`:
--   - "allow" (default): unmanaged MACs flow freely; admin still gets a
--     #711 alert and can enroll the device at leisure. The default is allow
--     because surprise-bricking a freshly-joined family device (guest phone,
--     new IoT) is a worse failure mode than letting it through for the few
--     minutes it takes the admin to review the alert.
--   - "block": API marks unmanaged MACs Manual-blocked at snapshot build, so
--     the router enforces via the existing per-MAC override path. HTTP/80
--     DNATs to the block page when `blockPage` is true.
--
-- `blockPage`: whether HTTP requests from blocked unmanaged MACs land on the
-- in-house block page (true) or get a plain RST (false). Only consulted when
-- policy = "block".

ALTER TABLE household_settings
  ADD COLUMN unmanaged_mac_policy JSONB NOT NULL
    DEFAULT '{"policy":"allow","blockPage":true}'::jsonb;
