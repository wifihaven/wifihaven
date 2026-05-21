-- V23__heartbeat_host_patterns.sql
-- #788: FQDN allowlist for the server-side heartbeat filter.
--
-- Adds a configurable list of FQDN patterns (same `*.foo.com` / `foo.com`
-- semantics as Presence.matchesPattern). Any traffic_reports row whose
-- host matches one of these patterns is classified as a heartbeat by
-- Presence.isHeartbeat, regardless of bytes / active-fraction. This closes
-- the gap from #779 where well-known background hosts (APNs, RCS, Apple
-- iCloud Private Relay, time sync, captive probes) occasionally burst
-- past the byte floor on TLS resumption / OCSP / cert rotation.
--
-- Ships with a default seed list of well-known heartbeat hosts. Operator
-- can edit via PUT /api/admin/household-settings.

ALTER TABLE household_settings
  ADD COLUMN heartbeat_host_patterns TEXT[] NOT NULL DEFAULT ARRAY[
    '*.push.apple.com',
    '*.apple-dns.net',
    '*.akadns.net',
    '*.ess.apple.com',
    'time.apple.com',
    'gdmf.apple.com',
    'pancake.apple.com',
    'mask.icloud.com',
    'mask-h2.icloud.com',
    'mask-api.icloud.com',
    '*.rcs.telephony.goog',
    'mtalk.google.com',
    'connectivitycheck.gstatic.com',
    'captive.apple.com',
    '*.ntp.org',
    'time.cloudflare.com'
  ]::TEXT[];
