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
-- #2782 — the second half of the story. The resolve above shelled out to `dig`,
-- which OpenWRT does not ship and openwrt/Makefile never depended on, and the
-- empty stdout that came back read as a successful resolve with no records. So
-- this module was a no-op on every router from the day it shipped, reporting
-- 100% success while doing nothing: prod counted 179,349,241 `resolves_ok` and
-- zero failures, with `eb_www_amazon_com` sitting empty while `www.amazon.com`
-- was in a profile's extraBlocked. Resolution now goes through
-- `wifihaven.resolver`, and a cycle carries an explicit host budget — with the
-- resolver dead the volume never mattered, and prod's blocklist membership is
-- 161,523 hosts.
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
-- dns_tail_sets / policy / conntrack); the agent wires `wifihaven.resolver`
-- and `os.execute`. Set-name construction is delegated to render.lua's
-- `eb_set_name` / `eb6_set_name` / `bl_set_name` / `bl6_set_name` exports so
-- the (host|id) → nft-set-name mapping has a single source of truth.

local dns_tail_sets = require("wifihaven.dns_tail_sets")
local render        = require("wifihaven.render")
local resolver      = require("wifihaven.resolver")

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
-- Default resolver
-- ---------------------------------------------------------------------------

-- #2782: this used to shell out to `dig`, which OpenWRT does not ship, and read
-- the resulting empty stdout as a successful resolve with no records — so the
-- whole module ran as a no-op that reported 100% success. Resolution now goes
-- through `wifihaven.resolver` (BusyBox `nslookup`, present on every image),
-- which returns nil when the resolver could not run at all. `refresh_one` below
-- already counts nil as `resolves_err`; it just never received one.
--
-- `popen_fn` is injectable for tests; production passes nothing and gets
-- `io.popen`.
function M.default_resolver(host, popen_fn)
  return resolver.resolve(host, popen_fn)
end

-- ---------------------------------------------------------------------------
-- refresh
-- ---------------------------------------------------------------------------

-- Per-cycle host budget. #2782: until the resolver was fixed every resolve was
-- a no-op, so nobody noticed the cycle queueing one per blocklist member —
-- 161,523 of them on the prod router, 328,161 resolves an hour. Sequential
-- shell-outs at that volume would starve dnsmasq (#1864), so a cycle now
-- processes at most this many hosts. 500 hosts = 1000 local lookups, a few
-- seconds every `eb_refresh_interval`.
--
-- extraBlocked spends the budget first: it is operator-authored, it is tens of
-- entries, and it is the host class #2782 is about. Blocklist members take what
-- is left, round-robin from a cursor the caller carries across cycles, so a
-- 161k backlog rotates instead of always re-doing its first N. Full blocklist
-- coverage is NOT claimed at this cadence — the bl_ sets' primary population
-- path is dnsmasq's `--nftset=` callback at client-resolve time, and a
-- principled top-up scoped to hosts we have actually seen resolved is
-- TODO(#2783).
M.DEFAULT_MAX_HOSTS = 500

-- refresh(opts) → { hosts, resolves_ok, resolves_err, adds, skipped, bl_cursor }
--
-- opts:
--   eb_hosts  : list of apex hosts to refresh eb_/eb6_ for
--   bl_pairs  : list of {host=..., id=...} to refresh bl_/bl6_ for
--   max_hosts : per-cycle host budget (default M.DEFAULT_MAX_HOSTS). Absent is
--               the default, NOT unbounded.
--   bl_cursor : 1-based index into bl_pairs to resume from (default 1). Pass
--               back the `bl_cursor` from the previous cycle's result.
--   nft_table : nftables table name, e.g. "inet wifihaven"
--   resolver  : host → { v4, v6 } | nil (nil counts as resolves_err)
--   exec_fn   : os.execute-style shell executor (capture in tests)
--   log       : optional logger { debug=, info=, warn= } — debug/info only
--
-- `skipped` is the work the budget left undone this cycle. It is reported, not
-- swallowed: a cycle that can never catch up should be visible on the
-- dashboard, not inferred from a suspiciously round `hosts`.
function M.refresh(opts)
  local stats = {
    hosts = 0, resolves_ok = 0, resolves_err = 0, adds = 0, skipped = 0,
  }
  local budget = tonumber(opts.max_hosts) or M.DEFAULT_MAX_HOSTS

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

  local eb_hosts = opts.eb_hosts or {}
  for _, host in ipairs(eb_hosts) do
    if stats.hosts >= budget then
      stats.skipped = stats.skipped + 1
    else
      refresh_one(host, render.eb_set_name(host), render.eb6_set_name(host))
    end
  end

  local bl_pairs = opts.bl_pairs or {}
  local n        = #bl_pairs
  local cursor   = tonumber(opts.bl_cursor) or 1
  if cursor < 1 or cursor > n then cursor = 1 end
  local taken = 0
  while taken < n and stats.hosts < budget do
    local pair = bl_pairs[cursor]
    local id   = tostring(pair.id)
    refresh_one(pair.host, render.bl_set_name(id), render.bl6_set_name(id))
    taken  = taken + 1
    cursor = cursor + 1
    if cursor > n then cursor = 1 end
  end
  stats.skipped   = stats.skipped + (n - taken)
  -- Where the next cycle picks up. Meaningless with no blocklist members, and
  -- `refresh` is then a no-op for them anyway.
  stats.bl_cursor = (n > 0) and cursor or 1

  return stats
end

return M
