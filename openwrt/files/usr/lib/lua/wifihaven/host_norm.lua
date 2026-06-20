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

-- canon_ip(ip) -> canonical key form for the dns→host attribution cache (#1793).
--
-- The cache is keyed by the destination IP string, but the two paths that
-- produce that key spell IPv6 differently:
--   * dnsmasq's query log (and `conntrack -E/-L`) emit the COMPRESSED RFC 5952
--     form, e.g. `2607:f8b0:400f:801::2002`.
--   * the kernel netfilter LOG action that drives the nflog drop-visibility
--     path (#1122) emits the FULLY-EXPANDED zero-padded form, e.g.
--     `DST=2607:f8b0:400f:0801:0000:0000:0000:2002`.
-- An exact-string `tbl[dst_ip]` lookup therefore misses for every *blocked* v6
-- flow (which is the only path that reaches the agent via the kernel LOG), and
-- the connection_event records a bare literal instead of the resolved FQDN.
--
-- canon_ip collapses both spellings to ONE key — the fully-expanded, lowercase,
-- zero-padded 8-group form — so an expanded query hits a compressed insert (and
-- vice-versa). It is the single source of cache-key canonicalization: dns_log
-- (store / lookup / load_table) and the agent's lookup_hostname all route
-- through it.
--
-- v4 literals, embedded-v4 v6 (`::ffff:1.2.3.4`), and anything that isn't a
-- well-formed hex v6 literal pass through unchanged (lowercased for the v6
-- shapes) — canonicalization must never corrupt a key, only normalize the v6
-- spellings we actually produce. nil / empty pass through so the helper
-- composes with nil-tolerant call sites.
function M.canon_ip(ip)
  if type(ip) ~= "string" or ip == "" then return ip end
  -- No colon → IPv4 or a non-IP; nothing to canonicalize.
  if not ip:find(":", 1, true) then return ip end
  -- Strip a zone id (`fe80::1%br-lan`) — never present on a dst, but be safe.
  local pct = ip:find("%", 1, true)
  local addr = pct and ip:sub(1, pct - 1) or ip
  addr = addr:lower()
  -- Anything with a non-hex/colon char (other than an embedded-v4 dot) is not a
  -- shape we expand; return it lowercased rather than risk corrupting the key.
  if addr:find("[^%x:%.]") then return addr end
  -- Embedded IPv4 tail (`::ffff:1.2.3.4`) — too rare on our dst path to expand
  -- precisely; leave intact (lowercased) so the key is at least stable.
  if addr:find("%.", 1, true) then return addr end

  local dc = addr:find("::", 1, true)
  local head, tail
  if dc then
    head = addr:sub(1, dc - 1)
    tail = addr:sub(dc + 2)
  else
    head = addr
  end

  local function groups(s)
    local g = {}
    if s == nil or s == "" then return g end
    for part in (s .. ":"):gmatch("([^:]*):") do g[#g + 1] = part end
    return g
  end

  local hg = groups(head)
  local tg = tail ~= nil and groups(tail) or {}
  local result = {}
  for _, g in ipairs(hg) do result[#result + 1] = g end
  if dc then
    local missing = 8 - (#hg + #tg)
    if missing < 0 then return addr end  -- malformed (too many groups for `::`)
    for _ = 1, missing do result[#result + 1] = "0" end
  end
  for _, g in ipairs(tg) do result[#result + 1] = g end
  if #result ~= 8 then return addr end   -- not a full address; leave alone

  for i, g in ipairs(result) do
    if g == "" then g = "0" end
    if #g > 4 then return addr end       -- malformed group
    result[i] = string.rep("0", 4 - #g) .. g
  end
  return table.concat(result, ":")
end

return M
