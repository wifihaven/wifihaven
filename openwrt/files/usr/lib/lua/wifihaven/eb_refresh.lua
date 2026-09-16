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

-- #2782: this shelled out to `dig`, which OpenWRT does not ship and
-- openwrt/Makefile never depended on. `default_dig` sent stderr to /dev/null
-- and returned "", and the old `if not v4_out and not v6_out` guard let that
-- through because an empty Lua string is truthy — so a resolver that could not
-- run reported a successful resolve with no records. The sweep therefore added
-- nothing on any router from the day #1658 shipped, while counting every host
-- `resolves_ok`: 179,349,241 of them on the prod family router, with zero
-- failures ever recorded and `eb_www_amazon_com` sitting empty while
-- www.amazon.com was in a profile's extraBlocked.
--
-- Those forks were not free, which is #2785: two `io.popen` per host across a
-- ~160k inventory is ~320k fork+exec pairs per sweep, and that is the 558s
-- `on_tick` stall. #2786 sliced the sweep so it can no longer hold the loop;
-- this restores what the sweep is supposed to achieve while it runs.
--
-- Resolution goes through `wifihaven.resolver` (BusyBox `nslookup`, present on
-- every OpenWRT image), which returns nil when the resolver could not run at
-- all. `refresh_one` already counts nil as `resolves_err`; it never received
-- one. `popen_fn` is injectable for tests; production passes nothing.
function M.default_resolver(host, popen_fn)
  return resolver.resolve(host, popen_fn)
end

-- ---------------------------------------------------------------------------
-- refresh
-- ---------------------------------------------------------------------------

-- refresh(opts) → { hosts, resolves_ok, resolves_empty, resolves_err, adds }
--
-- opts:
--   eb_hosts  : list of apex hosts to refresh eb_/eb6_ for
--   bl_pairs  : list of {host=..., id=...} to refresh bl_/bl6_ for
--   nft_table : nftables table name, e.g. "inet wifihaven"
--   resolver  : host → { v4, v6 } | nil (nil counts as resolves_err)
--   exec_fn   : os.execute-style shell executor (capture in tests)
--   log       : optional logger { debug=, info=, warn= } — debug/info only
-- #2785 additions to opts:
--   deadline_seconds : wall-clock budget for THIS pass. nil = no budget (the
--                      pre-#2785 behaviour, kept for the boot path and tests).
--   now_fn           : monotonic clock, defaults to os.clock-free os.time via
--                      the caller; injected so the budget is testable.
--   start_index      : 1-based cursor into the flat (eb_hosts .. bl_pairs)
--                      sequence. nil/0 starts a new sweep.
--
-- Returns stats with three extra fields:
--   done       : true when the sweep reached the end of the inventory.
--   next_index : where to resume (0 when done).
--   inventory  : total entries in the sweep, so the caller can report the
--                capacity that made this expensive in the first place.
function M.refresh(opts)
  local stats =
    { hosts = 0, resolves_ok = 0, resolves_empty = 0, resolves_err = 0, adds = 0 }

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
    -- #2782: split "ran and returned nothing" out of `ok`. An output shape the
    -- parser stops understanding yields zero records and is otherwise
    -- indistinguishable from NXDOMAIN — which is exactly how this module read
    -- as healthy for months.
    local v4, v6 = r.v4 or {}, r.v6 or {}
    if #v4 == 0 and #v6 == 0 then
      stats.resolves_empty = stats.resolves_empty + 1
    else
      stats.resolves_ok = stats.resolves_ok + 1
    end
    for _, ip in ipairs(v4) do
      if dns_tail_sets.nft_add_element(opts.nft_table, v4_set, ip, opts.exec_fn) then
        stats.adds = stats.adds + 1
      end
    end
    for _, ip in ipairs(v6) do
      if dns_tail_sets.nft_add_element(opts.nft_table, v6_set, ip, opts.exec_fn) then
        stats.adds = stats.adds + 1
      end
    end
  end

  -- eb_hosts and bl_pairs form ONE cursor space. Splitting them would leave the
  -- big half unbounded: bl_pairs holds every member host of every subscribed
  -- blocklist, which on the prod family router is ~160k entries, and that is
  -- the half that produced the 558 s stall.
  local eb  = opts.eb_hosts or {}
  local bl  = opts.bl_pairs or {}
  local n   = #eb + #bl
  stats.inventory = n

  local deadline = tonumber(opts.deadline_seconds)
  local now_fn   = opts.now_fn
  local started  = (deadline and now_fn) and now_fn() or nil

  local i = tonumber(opts.start_index) or 1
  if i < 1 then i = 1 end

  while i <= n do
    -- Budget check BEFORE the work, not after: the point is to bound how long
    -- the caller's loop is held, and one more resolve past the line is one more
    -- fork+exec pair the cooperative tick waits on.
    if started and (now_fn() - started) >= deadline then
      stats.done       = false
      stats.next_index = i
      return stats
    end
    if i <= #eb then
      local host = eb[i]
      refresh_one(host, render.eb_set_name(host), render.eb6_set_name(host))
    else
      local pair = bl[i - #eb]
      local id   = tostring(pair.id)
      refresh_one(pair.host, render.bl_set_name(id), render.bl6_set_name(id))
    end
    i = i + 1
  end

  stats.done       = true
  stats.next_index = 0
  return stats
end

return M
