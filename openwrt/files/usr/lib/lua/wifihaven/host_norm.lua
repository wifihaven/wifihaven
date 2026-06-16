-- host_norm.lua — single-source wire-host normalization (#1761).
--
-- A handful of attribution paths (SNI / Host-header capture, future plumbing
-- that interns "fqdn:port" pairs) can hand the wire emitters a hostname whose
-- value carries a trailing `:<port>` suffix. The API's Hostname validation
-- treats the port digits as part of the last label and rejects the record
-- ("invalid hostname label 'com:443'"), so the bad value 4xxs at ingest and
-- shows up on the prod data-quality-ingest dashboard.
--
-- The fix is to normalize at every wire-emit site (the three places that
-- construct `{type = "fqdn", value = h}` — conntrack.build_event,
-- nflog.build_event, usage.host_for_ip). All three call strip_port_suffix
-- so the helper, not a pattern, is the single source of truth.
--
-- This is the agent half of the back-compat pair: the API also accepts a
-- "fqdn:port" value and strips it server-side, so a fleet of pre-fix agents
-- ingests cleanly while the fleet rolls forward.

local M = {}

-- strip_port_suffix(host) -> host
--
-- Returns `host` with a trailing `:<digits>` suffix removed, idempotent on
-- already-clean values. Defends against IPv6 literals that contain colons:
-- a bare IPv6 ("::1", "fe80::1234") is left alone (it would never reach this
-- helper because v6 destinations are emitted as type="ipv6", but the helper
-- has to be safe regardless). A bracketed IPv6 ("[::1]:443") IS recognized
-- and stripped because the closing bracket disambiguates the port boundary.
--
-- Nil / empty pass through unchanged so the helper composes with the existing
-- nil-tolerant emitter branches without needing extra guards at every call
-- site.
function M.strip_port_suffix(host)
  if host == nil or host == "" then return host end
  -- Bracketed IPv6: "[v6]:port" -> "[v6]"
  if host:sub(1, 1) == "[" then
    return (host:gsub("(%])%:%d+$", "%1"))
  end
  -- Bare IPv6 contains at least two colons; treat as already-port-free.
  -- (A trailing `:%d+` on a v6 literal is ambiguous and we'd rather leave
  -- the value intact than guess wrong.)
  local _, ncolons = host:gsub(":", ":")
  if ncolons > 1 then return host end
  return (host:gsub(":%d+$", ""))
end

return M
