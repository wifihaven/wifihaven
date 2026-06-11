-- eb_refresh.lua — periodic re-resolve of extraBlocked and category-blocklist
-- hosts so the per-host `eb_<sanhost>` / `eb6_<sanhost>` and per-blocklist
-- `bl_<sanid>` / `bl6_<sanid>` nftables sets stay populated ahead of their 1h
-- `flags dynamic,timeout` aging out (declared in render.lua).
--
-- #1658 — the leak this closes. Population today is one-shot at DNS resolve
-- time (dnsmasq's `--nftset=` callback + the dns-tail sidecar's #1346
-- alias-chain populator). The kernel set ages an IP out after 1h, but iOS /
-- iPadOS app DNS caches regularly outlive 1h — long-running connection pools
-- and background-fetch sockets reuse the cached `getaddrinfo` answer for the
-- lifetime of the pool. When the client connects to a cached IP outside the
-- 1h window the IP is no longer in `eb_<host>`, the per-MAC drop predicate
-- (`ip daddr @eb_<host>`) misses, traffic falls through to the priority-1
-- accounting chains, and the agent's `lookup_hostname` (long-lived dnsmasq
-- query-log cache) correctly re-attributes the bytes back to the FQDN at
-- usage-report time — surfacing as real engaged minutes against a host that
-- is explicitly in the kid profile's extraBlocked. #1649 confirmed this
-- mechanism in prod for `play.google.com` on Kid Mac / Prima iPad.
--
-- Approach: every `eb_refresh_interval` seconds (default 1800s — strictly
-- below the 1h kernel timeout so each entry sees ≥1 refresh per ageing
-- window), iterate the inventory of (host, owning sets) the agent already
-- maintains in `eb_hosts_by_mac` / `bl_hosts_by_mac` (rebuilt by
-- `render.update_shared` after every policy apply), re-resolve each unique
-- host via the local dnsmasq (matching the resolution path that drives the
-- `--nftset=` callback at client-resolve time), and `nft add element` each
-- answered IP into the destination set. nft tolerates duplicate adds; idempotence
-- is the kernel's job, not ours.
--
-- The leak is independent of which client cached the IP — the eb_/bl_ sets
-- are global, NOT per-MAC (see render.lua `eb_set_name`). So we walk by host,
-- not by (mac, host) — one resolve per distinct host per cycle, regardless of
-- how many MACs the host applies to.
--
-- Why not raise the kernel timeout instead: see #1658 — bigger timeouts
-- accumulate stale CDN IPs (Google rotates), inverting the leak (an IP that
-- *was* Google but is now someone else's webapp gets dropped). Removing the
-- timeout leaks the set indefinitely on tmpfs-backed kernel sets. The right
-- shape is "keep the set refreshed in step with client cache lifetime", which
-- is what this module does.
--
-- Subdomain coverage: this module re-resolves the APEX as authored. dnsmasq's
-- `--nftset=/<apex>/...` callback populates the set for subdomains too at
-- resolve time, and the dns-tail sidecar (#1346) covers the directly-queried
-- CNAME-target path. This module deliberately does not enumerate observed
-- subdomains — that would require walking the dns_log cache and pull a
-- separate inventory shape; defer to a follow-up if subdomain-only caches are
-- observed in prod. The apex refresh closes the headline leak (the #1649
-- evidence is apex-level `play.google.com`).
--
-- Pure logic with injected resolver + nft executor (same DI shape as
-- dns_tail_sets / policy / conntrack); the agent wires the real `dig` shell-out
-- and `os.execute`. Set-name construction is delegated to render.lua's
-- `eb_set_name` / `eb6_set_name` / `bl_set_name` / `bl6_set_name` exports so
-- the (host|id) → nft-set-name mapping has a single source of truth.

local dns_tail_sets = require("dns_tail_sets")
local render        = require("wifihaven.render")

local M = {}

-- ---------------------------------------------------------------------------
-- Inventory
-- ---------------------------------------------------------------------------

-- collect_inventory(eb_hosts_by_mac, bl_hosts_by_mac) → { eb_hosts, bl_pairs }
--
-- Walks the two per-MAC inventories `render.update_shared` builds after each
-- policy apply and collapses them to the work this module needs:
--
--   eb_hosts : sorted unique hostnames in any device's effective extraBlocked
--   bl_pairs : sorted unique (host, blocklist_id) pairs from any device's
--              effective category assignment.
--
-- The eb_/bl_ ipsets are global (not per-MAC), so the cross-MAC dedup is the
-- whole point — a host in extraBlocked on 4 devices is one resolve, not 4.
function M.collect_inventory(eb_hosts_by_mac, bl_hosts_by_mac)
  local eb_seen = {}
  for _, hosts in pairs(eb_hosts_by_mac or {}) do
    for host in pairs(hosts or {}) do eb_seen[host] = true end
  end
  local eb_hosts = {}
  for h in pairs(eb_seen) do eb_hosts[#eb_hosts + 1] = h end
  table.sort(eb_hosts)

  -- (host, id) is the dedup key: a host may live in different blocklists
  -- across MACs (in principle — render.update_shared assigns one id per host
  -- in practice via the eb-wins-over-bl rule, but be defensive).
  local bl_seen = {}
  local bl_pairs = {}
  for _, hosts in pairs(bl_hosts_by_mac or {}) do
    for host, id in pairs(hosts or {}) do
      local key = host .. "\0" .. tostring(id)
      if not bl_seen[key] then
        bl_seen[key] = true
        bl_pairs[#bl_pairs + 1] = { host = host, id = id }
      end
    end
  end
  -- Sort deterministically: by host, then by id. Stable iteration helps test
  -- assertions and metric labels.
  table.sort(bl_pairs, function(a, b)
    if a.host == b.host then return tostring(a.id) < tostring(b.id) end
    return a.host < b.host
  end)

  return { eb_hosts = eb_hosts, bl_pairs = bl_pairs }
end

-- ---------------------------------------------------------------------------
-- dig parsing (default resolver)
-- ---------------------------------------------------------------------------

-- parse_dig_output(stdout, family) → sorted list of valid IP literals.
--
-- `dig +short` prints answer values one per line. For an A/AAAA query the
-- terminal answer line is the IP; CNAME-shaped intermediate lines (e.g.
-- `youtube-ui.l.google.com.`) also appear and must be skipped. We filter via
-- dns_tail_sets.safe_addr (the same allow-list applied to dnsmasq-log-parsed
-- IPs in the dns-tail populator).
function M.parse_dig_output(stdout, family)
  if not stdout or stdout == "" then return {} end
  local out = {}
  local seen = {}
  for line in stdout:gmatch("[^\r\n]+") do
    local ip = line:match("^%s*(%S+)%s*$")
    if ip then
      local safe = dns_tail_sets.safe_addr(ip)
      if safe then
        -- safe_addr accepts both v4 and v6 shapes; family-check via dots/colons.
        local is_v6 = safe:find(":", 1, true) ~= nil
        if (family == "v6") == is_v6 and not seen[safe] then
          seen[safe] = true
          out[#out + 1] = safe
        end
      end
    end
  end
  table.sort(out)
  return out
end

local function default_dig(host, qtype)
  -- Hostname allow-list: a-z, 0-9, dot, hyphen, underscore (some dnsmasq
  -- aliases contain underscores; see #1572). Anything else is rejected to keep
  -- shell-out hermetic.
  if type(host) ~= "string" or host:find("[^%w%.%-_]") then return nil end
  local cmd = string.format(
    "dig %s @127.0.0.1 -p 53 %s +short +time=2 +tries=1 2>/dev/null",
    qtype, host)
  local f = io.popen(cmd, "r")
  if not f then return nil end
  local out = f:read("*a")
  f:close()
  return out
end

-- default_resolver(host) → { v4 = {...}, v6 = {...} } | nil
-- nil means the resolver itself failed (dig couldn't run); empty lists mean
-- the resolver answered but no addresses were returned (NXDOMAIN / no record).
function M.default_resolver(host)
  local v4_out = default_dig(host, "A")
  local v6_out = default_dig(host, "AAAA")
  if not v4_out and not v6_out then return nil end
  return {
    v4 = M.parse_dig_output(v4_out, "v4"),
    v6 = M.parse_dig_output(v6_out, "v6"),
  }
end

-- ---------------------------------------------------------------------------
-- refresh
-- ---------------------------------------------------------------------------

-- refresh(opts) → { hosts, resolves_ok, resolves_err, adds }
--
-- opts:
--   eb_hosts  : list of apex hosts to refresh eb_/eb6_ for
--   bl_pairs  : list of {host=..., id=...} to refresh bl_/bl6_ for
--   nft_table : nftables table name, e.g. "inet wifihaven"
--   resolver  : host → { v4, v6 } | nil (nil counts as resolves_err)
--   exec_fn   : os.execute-style shell executor (capture in tests)
--   log       : optional logger { debug=, info=, warn= } — debug/info only
function M.refresh(opts)
  local stats = { hosts = 0, resolves_ok = 0, resolves_err = 0, adds = 0 }

  local function refresh_one(host, v4_set, v6_set)
    stats.hosts = stats.hosts + 1
    local r = opts.resolver and opts.resolver(host)
    if not r then
      stats.resolves_err = stats.resolves_err + 1
      if opts.log and opts.log.debug then
        opts.log.debug("eb_refresh: resolve failed host=%s", host)
      end
      return
    end
    stats.resolves_ok = stats.resolves_ok + 1
    for _, ip in ipairs(r.v4 or {}) do
      if dns_tail_sets.nft_add_element(opts.nft_table, v4_set, ip, opts.exec_fn) then
        stats.adds = stats.adds + 1
      end
    end
    for _, ip in ipairs(r.v6 or {}) do
      if dns_tail_sets.nft_add_element(opts.nft_table, v6_set, ip, opts.exec_fn) then
        stats.adds = stats.adds + 1
      end
    end
  end

  for _, host in ipairs(opts.eb_hosts or {}) do
    refresh_one(host, render.eb_set_name(host), render.eb6_set_name(host))
  end
  for _, pair in ipairs(opts.bl_pairs or {}) do
    local id = tostring(pair.id)
    refresh_one(pair.host, render.bl_set_name(id), render.bl6_set_name(id))
  end
  return stats
end

return M
