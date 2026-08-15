-- conntrack.lua — conntrack new-flow watcher + event batcher for wifihaven-agent
--
-- Design notes:
--   * conntrack -E -e NEW (conntrack-tools package) is used rather than nftables
--     meta nftrace because it is available on stock OpenWrt 23.x without enabling
--     per-rule tracing and produces structured, line-oriented output suitable for
--     a Lua io.popen loop.
--   * Hostname attribution uses the dns_log cache (dnsmasq query-log path, #259).
--     The in-memory nft_sets table is populated by render.lua only for site_limits
--     domains and is NOT a complete IP→hostname mirror; per-host (eb_/bl_) and
--     extraAllowed (ea_) decisions are therefore driven off the attributed hostname
--     (hname) matched against eb_hosts_by_mac / ea_hosts_by_mac using dnsmasq's
--     nftset suffix-match semantics (exact OR subdomain), NOT off nft_sets[host][ip].
--   * Both allowed and blocked flows are batched.  Blocking state comes from the
--     same policy tables that render.lua writes; we read them but never modify them.
--   * Reporting limitation: when DNS attribution is unavailable (hname=nil) and the
--     MAC is paused/time-limited, the agent cannot tell whether the kernel's ea_
--     carve-out fired.  In that case the flow is logged as blocked even if the
--     kernel actually allowed it (flows to extraAllowed hosts from a blocked MAC
--     with no DNS attribution are under-reported as blocked).  The same limitation
--     applies to per-host eb_ classification: without hname we cannot match against
--     eb_hosts_by_mac and the flow is left as allowed.

local M = {}

local static_ip_labels = require("wifihaven.static_ip_labels")
local host_norm        = require("wifihaven.host_norm")

-- #2024 idle-heartbeat cadence. watch()'s cooperative timers (usage flush, the
-- 10 s activity sampler, policy poll, nflog drain, metrics) all run inside
-- cfg.on_tick, which was driven ONLY by an incoming `conntrack -E -e NEW` line.
-- On a quiet LAN (one device on a long-lived websocket/stream emits no NEW
-- events) on_tick stalled for minutes: the usage window ballooned to span the
-- whole un-monitored gap and the sampler starved — the root cause of the #2016
-- over-count. The watcher now multiplexes a wall-clock heartbeat: a shell loop
-- echoes this sentinel line into the conntrack popen stream every
-- tick_interval seconds (see watch()'s popen command), and the read loop treats
-- a sentinel as an inert tick — it skips flow parsing but STILL drives on_tick,
-- so the timers fire on a wall-clock cadence regardless of conntrack traffic.
-- The leading control byte (0x01, SOH) guarantees the sentinel can never
-- collide with a printable-ASCII conntrack line, so parse_conntrack_line never
-- mistakes it for a flow.
M.TICK_SENTINEL = "\001wh_tick"

-- sanitize_tick_interval(v) -> int >= 1  (#2024)
--
-- The heartbeat cadence reaches BOTH string.format("%d", n) (the popen command
-- builder) and shell `sleep n`, each of which needs a positive integer. On the
-- Lua 5.1 target string.format("%d", 2.5) is a HARD ERROR
-- (`integer expected, got number`) — which, raised inside watch()'s foreground
-- loop, would crash the agent into a procd respawn storm — and `sleep 0`
-- busy-loops. A fractional / zero / negative / non-numeric UCI value collapses
-- to the 1 s default rather than bricking or spinning the watcher.
--
-- #2620: this is no longer only about `%d`/`sleep`. conntrack_tick_interval is
-- now ALSO the ws-path cadence for the drop-event pipeline
-- (nflog.pipeline_interval → nflog.due), so a nil would be a nil compare inside
-- an un-pcall'd on_tick and a 0 would collapse that gate to "always" — the
-- per-conntrack-line flush this sanitizer's callers exist to avoid. The agent
-- therefore sanitizes at the READ (wifihaven-agent's `tick_int`), and watch()
-- re-sanitizes its own argument; the function is idempotent, so both is fine.
function M.sanitize_tick_interval(v)
  local n = tonumber(v)
  if not n then return 1 end
  n = math.floor(n)
  if n < 1 then return 1 end
  return n
end

-- ---------------------------------------------------------------------------
-- eb_san(host) -> string
--
-- Sanitize an extraBlocked hostname into the nftables set-name suffix used by
-- render.lua: replace dots and colons with underscores, matching the
-- sanitize() function in render.lua.  Used to build "eb_<san>" set names when
-- falling back to nft membership queries for IP→hostname attribution misses
-- (#579).
-- ---------------------------------------------------------------------------
function M.eb_san(host)
  return (host:gsub("[%.%:]", "_"))
end

-- ---------------------------------------------------------------------------
-- list_procs() -> { { pid, ppid, cmdline }, ... }   (default scanner)
--
-- Enumerates the live process table by reading /proc/<pid>/status (for PPid)
-- and /proc/<pid>/cmdline (NUL-separated argv joined with spaces). Used by
-- kill_orphan_watchers; injectable as opts.list_procs_fn for tests.
-- ---------------------------------------------------------------------------
local function default_list_procs()
  local out = {}
  local p = io.popen("ls /proc 2>/dev/null", "r")
  if not p then return out end
  for name in p:lines() do
    local pid = tonumber(name)
    if pid then
      local sf = io.open("/proc/" .. pid .. "/status", "r")
      local ppid
      if sf then
        for line in sf:lines() do
          local v = line:match("^PPid:%s*(%d+)")
          if v then ppid = tonumber(v); break end
        end
        sf:close()
      end
      local cf = io.open("/proc/" .. pid .. "/cmdline", "r")
      local cmdline = ""
      if cf then
        local raw = cf:read("*a") or ""
        cf:close()
        -- argv is NUL-separated with a trailing NUL; replace NUL with space
        -- and strip the trailing whitespace so it matches a plain command line.
        cmdline = raw:gsub("%z", " "):gsub("%s+$", "")
      end
      if ppid then
        out[#out + 1] = { pid = pid, ppid = ppid, cmdline = cmdline }
      end
    end
  end
  p:close()
  return out
end

-- ---------------------------------------------------------------------------
-- kill_orphan_watchers(opts) -> int (orphans killed)  -- #1716
--
-- Scan /proc for `conntrack -E -e NEW` processes that have been reparented to
-- init (PPID = 1) — those are leftover children of a prior wifihaven-agent
-- instance. SIGTERM each one before opening this agent's own conntrack
-- subscription.
--
-- Why this is needed: conntrack subscribes to NFCT_ALL_CT_GROUPS on its
-- netlink socket, idles waiting for events, and only ever writes to stdout
-- when an event arrives. When the agent that opened it via io.popen() is
-- replaced (procd restart, CD reinstall, upgrade), the child is reparented
-- to init. SIGPIPE fires only on write, so an idle orphan with no events
-- never gets SIGPIPE and never dies. Across many restarts the orphans
-- accumulate, every netfilter event wakes all subscribers, and CPU + load
-- climb. Prod #1716: 24-day uptime → 16 orphans → agent CPU 22-24% / load
-- 3.2; SIGTERMing the orphans dropped agent CPU to 0%.
--
-- opts:
--   list_procs_fn  function() -> { {pid, ppid, cmdline}, ... }   injectable
--                  process scanner; defaults to default_list_procs above
--                  (reads /proc). Tests pass a closure over a canned list.
--   kill_fn        function(pid) -> bool                          injectable;
--                  defaults to `kill <pid>` via os.execute. Returns true on
--                  successful signal delivery (best-effort).
--
-- Matching is intentionally narrow: we only sweep processes whose cmdline is
-- EXACTLY `conntrack -E -e NEW` (the agent's invocation) — not `conntrack -L`,
-- `conntrack -F`, `conntrack -E -e NEW -p tcp`, etc. — so an operator's
-- transient conntrack tool isn't stomped.
-- ---------------------------------------------------------------------------
local AGENT_CONNTRACK_CMDLINE = "conntrack -E -e NEW"

function M.kill_orphan_watchers(opts)
  opts = opts or {}
  local list = opts.list_procs_fn or default_list_procs
  local kill = opts.kill_fn or function(pid)
    -- os.execute return shape varies by Lua version: int exit-code on 5.1/5.2,
    -- boolean true on 5.3+ for a clean exit. Mirror nft_eb_hit's normalisation.
    local ret = os.execute("kill " .. tostring(pid) .. " 2>/dev/null")
    return ret == 0 or ret == true
  end
  local killed = 0
  for _, p in ipairs(list()) do
    if p.ppid == 1 and p.cmdline == AGENT_CONNTRACK_CMDLINE then
      kill(p.pid)
      killed = killed + 1
    end
  end
  return killed
end

-- ---------------------------------------------------------------------------
-- nft_eb_hit(dst_ip, eb_host, exec_fn) -> bool
--
-- Returns true when dst_ip is a current member of the nftables set
-- `eb_<san(eb_host)>` (v4) or `eb6_<san(eb_host)>` (v6) in the `inet wifihaven`
-- table. Family is detected from dst_ip — a colon means IPv6, otherwise v4 —
-- matching the same idiom used in build_event below.
--
-- exec_fn(cmd) -> exit_code is injectable for tests (defaults to os.execute).
-- A return value of 0 (success) means the IP is in the set; any other value
-- (or a nil return from os.execute on old Lua 5.1 runtimes) is treated as a
-- miss.
--
-- Called only when DNS attribution (hname) is unavailable for a flow that
-- targets a MAC with extraBlocked entries, so one nft query per eb_host per
-- flow — acceptable because it is the slow-path (#579 logging correction) AND
-- because extraBlocked is small (single digits per profile in production).
--
-- #2719: the category-blocklist fallback used to piggyback on this helper too,
-- iterating every MEMBER HOST of every assigned list (180,343 on the prod
-- family router). It now probes the per-list bl_<id>/bl6_<id> sets via
-- nft_bl_hit instead — see the loop in handle_flow. Do not re-point the bl_
-- path back here.
-- ---------------------------------------------------------------------------
function M.nft_eb_hit(dst_ip, eb_host, exec_fn)
  local san = M.eb_san(eb_host)
  return M.nft_set_hit(dst_ip, "eb_" .. san, "eb6_" .. san, exec_fn)
end

-- ---------------------------------------------------------------------------
-- nft_set_hit(dst_ip, set4, set6, exec_fn) -> bool   (#2719)
--
-- The one place an `nft get element` membership probe is spelled. Family is
-- detected from dst_ip — a colon means IPv6, otherwise v4 — matching the same
-- idiom used in build_event below. exec_fn(cmd) -> exit_code is injectable for
-- tests (defaults to os.execute); a 0 / true return means the IP is in the set
-- and anything else (including a nil from an old runtime) is a miss.
--
-- Every slow-path probe — eb_, bl_, ea_, global_allow — goes through here so
-- the command shape and the Lua 5.1-vs-5.3 os.execute return normalisation
-- exist once. os.execute returns an exit code (number) on 5.1/5.2 and a
-- (bool, "exit", code) tuple on 5.3+/LuaJIT; the router runs 5.1.
-- ---------------------------------------------------------------------------
function M.nft_set_hit(dst_ip, set4, set6, exec_fn)
  exec_fn = exec_fn or os.execute
  local family_v6 = dst_ip:find(":", 1, true) ~= nil
  local cmd = string.format(
    "nft get element inet wifihaven %s '{ %s }' >/dev/null 2>&1",
    family_v6 and set6 or set4, dst_ip)
  local ret = exec_fn(cmd)
  if type(ret) == "boolean" then
    return ret  -- Lua 5.3+: true = success (exit 0)
  end
  return ret == 0  -- Lua 5.1/5.2: 0 = success
end

-- ---------------------------------------------------------------------------
-- bl_san(id) -> string   (#2719)
--
-- The nftables set-name suffix render.lua uses for the per-list sets.
-- Delegates to render.bl_sanitize rather than re-implementing it: a sanitizer
-- that drifted from render's would silently turn every category probe into a
-- miss, which reads as "nothing blocked" rather than as an error. Required
-- lazily so conntrack keeps loading in any context that does not already pull
-- render in (the agent loads both). Note this is NOT eb_san's rule — bl_ ids
-- also collapse hyphens and whitespace, host-derived names do not.
-- ---------------------------------------------------------------------------
local _render_module
function M.bl_san(id)
  if _render_module == nil then
    _render_module = require("wifihaven.render")
  end
  return _render_module.bl_sanitize(tostring(id))
end

-- ---------------------------------------------------------------------------
-- nft_bl_hit(dst_ip, bl_id, exec_fn) -> bool   (#2719)
--
-- Returns true when dst_ip is a current member of the per-blocklist nftables
-- set `bl_<san(id)>` (v4) or `bl6_<san(id)>` (v6).
--
-- This is THE category probe on the DNS-attribution-miss path. The kernel
-- already maintains one set per list (dnsmasq's nftset= callback adds each
-- resolved member IP at resolve time — render.lua's shard directive
-- `nftset=/<host>/4#inet#wifihaven#bl_<id>,6#inet#wifihaven#bl6_<id>`), so a
-- flow can be tested with one query per assigned LIST instead of one per
-- member HOST — 10 forks instead of 180,343 on the prod family router.
--
-- Trade: the matched member host is not recoverable from a per-list probe, so
-- an event labelled on this path carries `category:<id>` with no host. That is
-- the whole label the block page and the connection_event log consume anyway
-- (#594); the per-host granularity (#1636/#1640) survives on the normal
-- DNS-attributed path, where the host is known for free.
--
-- Consequence to respect at the call site: this probe hits far more often than
-- the per-host eb_-style probe it replaced (a pure blocklist member has no
-- eb_<host> set at all, so that query almost always missed). The kernel's
-- category drop is `... ip daddr @bl_<id> != @ea_<mac>_<host> != @global_allow`
-- — a bl_ hit ALONE is not a drop, and the carve-out sets have to be probed
-- too before the flow may be labelled blocked. See nft_carve_hit.
-- ---------------------------------------------------------------------------
function M.nft_bl_hit(dst_ip, bl_id, exec_fn)
  local san = M.bl_san(bl_id)
  return M.nft_set_hit(dst_ip, "bl_" .. san, "bl6_" .. san, exec_fn)
end

-- ---------------------------------------------------------------------------
-- nft_ea_hit(dst_ip, mac, ea_host, exec_fn) -> bool   (#2719)
-- nft_ga_hit(dst_ip, exec_fn) -> bool                 (#2719)
--
-- The two carve-out sets the kernel's eb_/bl_ drop rules except on: the
-- per-(MAC, host) extraAllowed set `ea_<mac>_<host>` / `ea6_…` (#421/#496) and
-- the fleet-wide `global_allow` / `global_allow6` (#1319). Set names mirror
-- render.lua's ea_set_name / GLOBAL_ALLOW4 — both use render.sanitize, which is
-- eb_san's rule (dots and colons only), so eb_san is the right sanitizer here
-- and bl_san is not.
-- ---------------------------------------------------------------------------
function M.nft_ea_hit(dst_ip, mac, ea_host, exec_fn)
  local suffix = M.eb_san(mac) .. "_" .. M.eb_san(ea_host)
  return M.nft_set_hit(dst_ip, "ea_" .. suffix, "ea6_" .. suffix, exec_fn)
end

function M.nft_ga_hit(dst_ip, exec_fn)
  return M.nft_set_hit(dst_ip, "global_allow", "global_allow6", exec_fn)
end

-- ---------------------------------------------------------------------------
-- is_attributable_dst(ip) -> bool   (#2719)
--
-- False for destination addresses that can NEVER appear in a DNS-resolved
-- nftables set, because nothing resolves to them: IPv6 multicast (ff00::/8),
-- IPv6 link-local (fe80::/10), IPv4 multicast (224.0.0.0/4), IPv4 link-local
-- (169.254.0.0/16), the IPv4 limited broadcast 255.255.255.255 and the rest of
-- 240.0.0.0/4 reserved space, and 0.0.0.0/8.
--
-- The v4 broadcast is the direct analogue of the v6 flow in the incident: a
-- DHCPv4 discover/renew from a LAN device to 255.255.255.255 is not in
-- lan_prefix either, so without this it would classify WAN-bound and reach a
-- fallback it can never satisfy.
--
-- A DHCPv6 solicit to ff02::1:2 is what wedged the prod agent for six hours:
-- it reached the DNS-miss fallback, and since a multicast dst is in no eb6_ /
-- bl6_ set the loop was guaranteed to run to exhaustion. Every one of these
-- ranges is link-scoped LAN traffic, so the flow is neither WAN-bound nor
-- attributable — is_wan_bound rejects it outright and handle_flow's slow path
-- guards on it a second time.
-- ---------------------------------------------------------------------------
function M.is_attributable_dst(ip)
  if type(ip) ~= "string" or ip == "" then return false end
  if ip:find(":", 1, true) then
    local head = ip:lower():sub(1, 3)
    if head:sub(1, 2) == "ff" then return false end          -- ff00::/8 multicast
    if head == "fe8" or head == "fe9"
       or head == "fea" or head == "feb" then return false end -- fe80::/10 link-local
    return true
  end
  local a, b = ip:match("^(%d+)%.(%d+)%.")
  if not a then return true end
  a, b = tonumber(a), tonumber(b)
  if a >= 224 and a <= 239 then return false end             -- 224.0.0.0/4 multicast
  if a >= 240 then return false end                          -- 240.0.0.0/4 reserved + 255.255.255.255
  if a == 169 and b == 254 then return false end             -- 169.254.0.0/16 link-local
  if a == 0 then return false end                            -- 0.0.0.0/8 "this network"
  return true
end

-- Slow-path ceilings (#2719). These are a structural backstop, not a tuning
-- knob: with the per-list category probe and the non-attributable-destination
-- filter in place a real flow costs at most (extraBlocked hosts + assigned
-- blocklist ids + extraAllowed hosts + 1) probes — single-to-low-double digits
-- in production. The ceiling exists so that a candidate set nobody anticipated
-- still cannot stop the agent, because every agent timer (policy apply, usage
-- flush, event flush, metrics push, ws pending-apply) runs from the conntrack
-- watcher's on_tick and therefore stops with it. They are code constants, not
-- UCI options — an operator has no reason to raise a ceiling whose only job is
-- to keep the agent alive, and watch() accepts overrides for tests only.
--
-- The cap is PER STAGE, not one pool the stages draw from in order. A single
-- pool lets a large extraBlocked set (each Blocked-mode app contributes its
-- whole host list) exhaust the budget before the category and carve-out probes
-- run at all — which would silently stop category classification and pin the
-- ceiling metric high, exactly the state the metric exists to distinguish.
-- Three stages × 64 probes is still a hard per-flow bound.
M.SLOW_PATH_MAX_PROBES  = 64
M.SLOW_PATH_MAX_SECONDS = 0.5

-- sorted_keys(t) -> array of t's keys in ascending order (#2719). Slow-path
-- iteration must be deterministic: under a tripped ceiling, `pairs` order
-- decides WHICH candidates were probed, so identical input could otherwise
-- classify differently between runs.
local function sorted_keys(t)
  local keys = {}
  for k in pairs(t or {}) do keys[#keys + 1] = k end
  table.sort(keys)
  return keys
end

local _clock_module
local function default_now()
  if _clock_module == nil then
    local ok, c = pcall(require, "wifihaven.clock")
    if not ok then ok, c = pcall(require, "clock") end
    _clock_module = (ok and c) or false
  end
  if _clock_module and _clock_module.monotonic_seconds then
    return _clock_module.monotonic_seconds()
  end
  -- Last-resort fallback only (clock.lua itself prefers luaposix, then
  -- /proc/uptime). os.time() is whole-second, so against it a sub-second
  -- SLOW_PATH_MAX_SECONDS reads as "0 to 1 s" rather than as its literal
  -- value; the probe cap is the binding constraint in that case.
  return os.time()
end

-- new_probe_budget(ctx) -> budget   (#2719)
--
-- Per-flow nft probe ceiling, with a separate probe count per stage and ONE
-- wall-clock deadline shared across all of them (wall clock is the resource
-- the agent actually runs out of; stages cannot each get their own).
--   budget.take(stage) → true while another probe is allowed for that stage
--   budget.tripped()   → nil, or "probes" / "deadline" once a ceiling was hit
-- The clock is read lazily on first take() so a DNS-attributed flow — the
-- common case, which probes nothing — pays nothing for the budget's existence.
local function new_probe_budget(ctx)
  local max_probes  = ctx.slow_path_max_probes  or M.SLOW_PATH_MAX_PROBES
  local max_seconds = ctx.slow_path_max_seconds or M.SLOW_PATH_MAX_SECONDS
  local now         = ctx.now_fn or default_now
  local started
  local used        = {}
  local tripped
  return {
    take = function(stage)
      local n = used[stage] or 0
      if n >= max_probes then
        tripped = tripped or "probes"
        return false
      end
      if started == nil then started = now() end
      if max_seconds > 0 and (now() - started) >= max_seconds then
        tripped = tripped or "deadline"
        return false
      end
      used[stage] = n + 1
      return true
    end,
    tripped = function() return tripped end,
  }
end

-- ---------------------------------------------------------------------------
-- host_matches(hname, host) -> bool
--
-- Returns true when hname is an exact match for host, or when hname is a
-- subdomain of host (i.e. hname ends with ".<host>").  This mirrors the
-- suffix-match semantics of dnsmasq's `nftset=/<host>/...` directive, which
-- also populates the nft set for all subdomains.
--
-- Examples:
--   host_matches("example.com",     "example.com") → true
--   host_matches("foo.example.com", "example.com") → true   (subdomain)
--   host_matches("notexample.com",  "example.com") → false  (no dot prefix)
--   host_matches("foo.bar",         "example.com") → false
-- ---------------------------------------------------------------------------
function M.host_matches(hname, host)
  if hname == host then return true end
  -- Check suffix ".<host>" — avoids false positive on e.g. "notexample.com"
  -- matching "example.com".
  return hname:sub(-(#host + 1)) == "." .. host
end

local function default_log()
  local ok, l = pcall(require, "wifihaven.log")
  if ok then return l end
  return {
    info  = function(fmt, ...) io.stderr:write(string.format(fmt .. "\n", ...)) end,
    err   = function(fmt, ...) io.stderr:write(string.format(fmt .. "\n", ...)) end,
    warn  = function(fmt, ...) io.stderr:write(string.format(fmt .. "\n", ...)) end,
    debug = function() end,
  }
end

-- ---------------------------------------------------------------------------
-- new_fqdn_retry_state(opts) -> state
--
-- Per-second retry budget for the FQDN-attribution race fix (#583).
--
-- The wifihaven-dns-tail sidecar tails dnsmasq's query log and writes
-- /tmp/wifihaven-dns-cache.txt; the conntrack watcher reads that file when a
-- new flow's SYN passes the router. The two paths race: a fresh DNS reply
-- can arrive at the kernel a few milliseconds before dns-tail has parsed
-- it + flushed the snapshot, so the lookup misses and the connection_attempt
-- event ships with host.type=ipv4 even though dnsmasq just resolved the host.
--
-- A short re-read after a small sleep usually catches it. The budget caps
-- how many flows per second can pay this latency so a flood of genuinely
-- IP-only flows (DoH, Apple Private Relay, hard-coded IP literals) does
-- not stall the conntrack loop.
--
-- opts: { max_per_second = 10, delay_seconds = 0.1, now_fn, sleep_fn }
-- ---------------------------------------------------------------------------
local function monotonic_now()
  local ok, clock = pcall(require, "wifihaven.clock")
  if ok then return clock.monotonic_seconds() end
  return os.time()
end

local function default_sleep(s)
  -- BusyBox usleep takes microseconds; fall back to `sleep` for whole seconds
  -- if usleep is missing on some exotic build.
  local us = math.floor((s or 0) * 1e6)
  if us <= 0 then return end
  os.execute(string.format("usleep %d 2>/dev/null || sleep %d", us, math.max(1, math.floor(s))))
end

function M.new_fqdn_retry_state(opts)
  opts = opts or {}
  local max = opts.max_per_second or 10
  local now_fn = opts.now_fn or monotonic_now
  return {
    max_per_second = max,
    tokens         = max,
    window_start   = now_fn(),
    delay_seconds  = opts.delay_seconds or 0.1,
    now_fn         = opts.now_fn,
    sleep_fn       = opts.sleep_fn,
  }
end

-- ---------------------------------------------------------------------------
-- attribute_hostname(dst_ip, lookup_fn, retry_state) -> string | nil
--
-- Look up the hostname for dst_ip; if the first lookup misses and the
-- retry_state has tokens in the current 1-second window, sleep
-- retry_state.delay_seconds and look up again. Closes the FQDN-attribution
-- race (#583) where the dnsmasq reply line resolving dst_ip has not yet
-- been ingested + atomically flushed to the cache file by wifihaven-dns-tail
-- at the moment conntrack -E NEW fires.
--
-- retry_state is mutated (token decrement, window refill); callers should
-- share one state across the watch loop so the budget is honored globally.
-- Passing retry_state=nil disables the retry (used by tests and by callers
-- that don't want to spend cycles on this).
-- ---------------------------------------------------------------------------
function M.attribute_hostname(dst_ip, lookup_fn, retry_state)
  if not lookup_fn then return nil end
  local h = lookup_fn(dst_ip)
  if h or not retry_state then return h end

  local now_fn = retry_state.now_fn or monotonic_now
  local now = now_fn()
  if (now - (retry_state.window_start or 0)) >= 1 then
    retry_state.window_start = now
    retry_state.tokens = retry_state.max_per_second
  end
  if (retry_state.tokens or 0) <= 0 then return nil end
  retry_state.tokens = retry_state.tokens - 1

  local sleep_fn = retry_state.sleep_fn or default_sleep
  sleep_fn(retry_state.delay_seconds)
  return lookup_fn(dst_ip)
end

-- ---------------------------------------------------------------------------
-- ipset_lookup_hostname(dest_ip, nft_sets) -> string | nil
--
-- nft_sets: { hostname -> { ip -> true } }  (maintained by render.lua)
-- Returns the hostname whose set contains dest_ip, or nil.
-- ---------------------------------------------------------------------------
function M.ipset_lookup_hostname(dest_ip, nft_sets)
  for hostname, ips in pairs(nft_sets) do
    if ips[dest_ip] then
      return hostname
    end
  end
  return nil
end

-- ---------------------------------------------------------------------------
-- arp_lookup_mac(src_ip, arp_table) -> string | nil
--
-- arp_table: { ip -> mac }  (populated by parse_arp_table())
-- ---------------------------------------------------------------------------
function M.arp_lookup_mac(src_ip, arp_table)
  return arp_table[src_ip]
end

-- ---------------------------------------------------------------------------
-- parse_arp_table(lan_dev) -> { ip -> mac }
--
-- Reads /proc/net/arp at call time (v4) AND `ip -6 neigh` (v6 NDP cache).
-- The kernel exposes the v4 ARP table as a proc file but the v6 NDP cache
-- only over netlink, so we shell out for the v6 half. Called per-event so
-- the MAC table stays fresh without a background refresh loop.
--
-- v6 is load-bearing here: without it, the watch loop reaches a v6 conntrack
-- NEW with `src=<lan-ula>`, asks parse_arp_table for the MAC of that v6
-- src_ip, gets nil, and emits a connection_attempt event whose `mac` field
-- is nil. The fake-API Gate 2 attribution suite (#1677) asserts on
-- `mac == client.mac AND host.value contains LEAF_HOST` — a nil mac fails
-- the assert and is_wan_bound's #1690 family-fork looks like it didn't
-- close the loop. (#1691.)
--
-- #2368: when `lan_dev` is given, the v6 neigh query is scoped to that dev
-- (`ip -6 neigh show dev <lan_dev>`) so ONLY LAN-bridge neighbors enter the
-- set. Unfiltered, `ip -6 neigh show` returns neighbors on every interface —
-- including the WAN — and the upstream/default router (which emits its own
-- IPv6 via RA/DHCPv6-PD/NDP) shows up as a neighbor on the WAN iface. That
-- made is_wan_bound class the router's self-sourced flows as LAN (src in set,
-- dst not) and the agent autocreated the edge router as a phantom household
-- device. The v4 half stays unfiltered — is_wan_bound bounds v4 by
-- `lan_prefix`, so a WAN v4 gateway never passes and needs no dev scoping.
-- Omitting `lan_dev` preserves the pre-#2368 unfiltered behavior (back-compat).
-- ---------------------------------------------------------------------------
function M.parse_arp_table(lan_dev)
  local result = {}
  -- /proc/net/arp columns: IP, HWtype, Flags, HWaddr, Mask, Device
  local f = io.open("/proc/net/arp", "r")
  if f then
    f:read("*l")  -- skip header
    for line in f:lines() do
      local parts = {}
      for w in line:gmatch("%S+") do parts[#parts + 1] = w end
      if #parts >= 4 and parts[4] ~= "00:00:00:00:00:00" then
        result[parts[1]] = parts[4]
      end
    end
    f:close()
  end
  -- v6 NDP cache. Format examples:
  --   `2001:db8::1 dev br-lan lladdr 02:e2:fa:11:22:33 router REACHABLE`
  --   `fdaa:bbbb:cccc::147 dev br-lan lladdr 02:e2:fa:8e:c2:ce STALE`
  --   `fe80::2 dev eth1  used 0/0/0 probes 6 FAILED`   (no lladdr — skip)
  -- The `lladdr <mac>` token is the load-bearing piece; entries in FAILED
  -- state have no lladdr and we skip them naturally. `show dev <lan_dev>`
  -- (#2368) drops the `dev …` token from each line, but the ip/lladdr parse
  -- is position-independent and unaffected. `lan_dev` is interpolated into a
  -- shell command, so require it to look like a real interface name
  -- (alphanumerics plus `.`/`_`/`-`, e.g. br-lan, eth0.2) before trusting it —
  -- defense-in-depth against a malformed UCI value; anything else falls back to
  -- the unfiltered dump.
  local dev_ok = lan_dev and lan_dev ~= "" and lan_dev:match("^[%w._-]+$")
  local neigh_cmd = dev_ok
    and ("ip -6 neigh show dev " .. lan_dev .. " 2>/dev/null")
    or  "ip -6 neigh show 2>/dev/null"
  local pf = io.popen(neigh_cmd)
  if pf then
    for line in pf:lines() do
      local ip = line:match("^(%S+)")
      local mac = line:match("lladdr%s+(%S+)")
      if ip and mac and mac ~= "00:00:00:00:00:00" then
        result[ip] = mac
      end
    end
    pf:close()
  end
  return result
end

-- ---------------------------------------------------------------------------
-- parse_dhcp_leases(path) -> { mac -> { ip = string, hostname = string|nil } }
--
-- Reads a dnsmasq lease file (default /tmp/dhcp.leases).  Each line is:
--   <expiry-epoch> <mac> <ip> <hostname-or-*> <client-id-or-*>
-- A hostname of "*" is treated as absent (nil).
-- Returns an empty table if the file can't be opened.
-- ---------------------------------------------------------------------------
function M.parse_dhcp_leases(path)
  local result = {}
  local f = io.open(path or "/tmp/dhcp.leases", "r")
  if not f then return result end
  for line in f:lines() do
    local parts = {}
    for w in line:gmatch("%S+") do parts[#parts + 1] = w end
    if #parts >= 3 then
      local mac      = parts[2]
      local ip       = parts[3]
      local hostname = parts[4]
      if hostname == "*" or hostname == "" then hostname = nil end
      result[mac] = { ip = ip, hostname = hostname }
    end
  end
  f:close()
  return result
end

-- ---------------------------------------------------------------------------
-- gen_event_id() -> string | nil
--
-- Returns a fresh RFC 4122 UUID from the kernel's random pool. Used as the
-- per-event idempotency key on connection_attempt events so retry-queue
-- replays (#330) collapse on insert at the API rather than landing as
-- duplicate rows (#338).
--
-- Returns nil if /proc/sys/kernel/random/uuid is unreadable; the API treats
-- a missing eventId as "older agent" and generates one server-side, so a
-- nil here degrades to the pre-#338 behavior (no dedup) rather than dropping
-- the event.
--
-- Injectable via cfg.gen_event_id for tests.
-- ---------------------------------------------------------------------------
function M.gen_event_id()
  local f = io.open("/proc/sys/kernel/random/uuid", "r")
  if not f then return nil end
  local id = f:read("*l")
  f:close()
  return id
end

-- ---------------------------------------------------------------------------
-- build_first_seen_mac_event(opts) -> table
--
-- opts: { mac, ip, hostname, ts }
-- ip / hostname may be nil (e.g. MAC has no DHCP lease — static IP client).
-- ---------------------------------------------------------------------------
function M.build_first_seen_mac_event(opts)
  return {
    ["type"] = "first_seen_mac",
    mac      = opts.mac,
    ip       = opts.ip,
    hostname = opts.hostname,
    ts       = opts.ts,
  }
end

-- ---------------------------------------------------------------------------
-- build_dhcp_lease_event(opts) -> table
--
-- Emitted when a previously hostname-less MAC later acquires a hostname via
-- DHCP (fixes #249 — the race where the first conntrack flow precedes the
-- dnsmasq lease write, leaving the device named "unknown" forever).
-- ---------------------------------------------------------------------------
function M.build_dhcp_lease_event(opts)
  return {
    ["type"] = "dhcp_lease",
    mac      = opts.mac,
    ip       = opts.ip,
    hostname = opts.hostname,
    ts       = opts.ts,
  }
end

-- ---------------------------------------------------------------------------
-- build_event(opts) -> table
--
-- opts: { mac, hostname, host_label_source, dest_ip, allowed, reason, ts }
-- Returns a Lua table ready for JSON encoding.
--
-- Per #391, the emitted `host` is a tagged union (HostId): an FQDN when the
-- agent has DNS attribution for the flow, otherwise an IPv4 or IPv6 literal
-- tagged by address family. This replaces the old bare `hostname` field that
-- silently put IP literals where a hostname was expected, breaking site-limit
-- pattern matching and polluting the admin UI.
--
-- Per #1708, if `host_label_source` is set the host is emitted as a `label`
-- variant — { type = "label", value = opts.hostname, source = host_label_source }
-- — instead of an fqdn. Labels are produced by the static_ip_labels map
-- (last-resort attribution for IP-range owners that bypass dnsmasq) and
-- must NEVER be confused with a real DNS/SNI-derived hostname; the API
-- side's HostMatch.matchesAny refuses to pattern-match labels against any
-- apex. build_event is the SINGLE place that knows how to render each
-- HostId variant — callers thread the kind/source through opts, they
-- never construct the wire shape themselves.
--
-- reason nil → "allow" when allowed=true, "blocked" when false.
-- ---------------------------------------------------------------------------
function M.build_event(opts)
  local host
  if opts.host_label_source and opts.hostname then
    host = { type = "label", value = opts.hostname, source = opts.host_label_source }
  elseif opts.hostname then
    -- #1761: strip a trailing :<port> so the API's Hostname validation
    -- doesn't reject the record. Single-source via host_norm.
    host = { type = "fqdn", value = host_norm.strip_port_suffix(opts.hostname) }
  else
    local kind = (opts.dest_ip and opts.dest_ip:find(":", 1, true)) and "ipv6" or "ipv4"
    host = { type = kind, value = opts.dest_ip }
  end
  local reason
  if opts.reason then
    reason = opts.reason
  elseif opts.allowed then
    reason = "allow"
  else
    reason = "blocked"
  end
  -- #338: stamp an idempotency key now (in-memory; survives the in-call retry
  -- and the longer #330 queue). Tests stub M.gen_event_id directly.
  return {
    ["type"]    = "connection_attempt",
    mac         = opts.mac,
    host        = host,
    destIp      = opts.dest_ip,
    allowed     = opts.allowed,
    reason      = reason,
    ts          = opts.ts,
    eventId     = M.gen_event_id(),
  }
end

-- ---------------------------------------------------------------------------
-- encode_events_body(router_id, events, log) -> string | nil
--
-- Serialize a RouterEventsRequest body, refusing to produce anything the API
-- can't parse. This is the emitter half of the #1126 ingest-reliability fix:
-- the recurring `router events: deserialization failed … Unexpected end of
-- input` warnings come from malformed / empty bodies reaching the wire.
--
--   * Empty (or nil) events -> nil. luci.jsonc encodes an empty Lua table as
--     `{}` (a JSON *object*), which the API decodes as a type error; and an
--     empty batch is pointless to POST. Callers must skip the POST on nil.
--   * A non-string / non-object stringify result -> nil (logged). Guards
--     against a truncated encoding burning a retry slot or tripping the ingest
--     warning. A non-empty Lua sequence always encodes as a JSON array, so the
--     `events` field is well-formed whenever this returns a string.
-- ---------------------------------------------------------------------------
function M.encode_events_body(router_id, events, log)
  log = log or default_log()
  if not events or next(events) == nil then return nil end
  local jsonc   = require("luci.jsonc")
  local payload = jsonc.stringify({ routerId = router_id, events = events })
  if type(payload) ~= "string" or payload:sub(1, 1) ~= "{" then
    log.err("conntrack: refusing to POST malformed events body (got %s)", tostring(payload))
    return nil
  end
  return payload
end

-- ---------------------------------------------------------------------------
-- new_batcher(max_size, flush_interval_sec, flush_fn) -> batcher
--
-- Returns an object with:
--   batcher.add(event)   — append event; auto-flush when buffer hits max_size
--   batcher.flush()      — flush whatever is pending (no-op if empty)
--   batcher.tick(now)    — call periodically; flushes if interval has elapsed
--
-- flush_fn(events) is called with the list of accumulated events.
-- ---------------------------------------------------------------------------
-- now_fn is optional; defaults to a monotonic-clock source so interval-based
-- flushes survive wall-clock jumps (#336). Tests inject a fake to drive time
-- deterministically.
function M.new_batcher(max_size, flush_interval_sec, flush_fn, now_fn)
  now_fn = now_fn or function()
    local ok, clock = pcall(require, "wifihaven.clock")
    if ok then return clock.monotonic_seconds() end
    return os.time()
  end
  local buf          = {}
  local last_flush   = now_fn()

  local self = {}

  function self.flush()
    if #buf == 0 then return end
    local to_send = buf
    buf = {}
    flush_fn(to_send)
  end

  function self.add(event)
    buf[#buf + 1] = event
    if #buf >= max_size then
      self.flush()
    end
  end

  function self.tick(now)
    now = now or now_fn()
    if (now - last_flush) >= flush_interval_sec then
      last_flush = now
      self.flush()
    end
  end

  return self
end

-- ---------------------------------------------------------------------------
-- post_with_retry(url, body, max_retries, base_delay, post_fn, sleep_fn)
--   -> ok, last_status, last_body, last_err
--
-- post_fn(url, body) -> status_code, body_str [, err_str]
-- sleep_fn(seconds)  — injectable for tests (defaults to os.execute("sleep N"))
--
-- Retries on 5xx / connection errors with exponential back-off.
-- Does NOT retry on 4xx (client errors are not transient).
-- Returns (true, status, body, nil) on success, or
--         (false, last_status, last_body, last_err) when retries are exhausted
--         or a 4xx short-circuits retrying.
-- ---------------------------------------------------------------------------
function M.post_with_retry(url, body, max_retries, base_delay, post_fn, sleep_fn)
  sleep_fn = sleep_fn or function(s)
    os.execute("sleep " .. tostring(math.floor(s)))
  end

  local last_status, last_body, last_err
  local delay = base_delay
  for attempt = 1, max_retries do
    local status, resp_body, err = post_fn(url, body)
    last_status, last_body, last_err = status, resp_body, err
    if status and status >= 200 and status < 300 then
      return true, status, resp_body, nil
    end
    if status and status >= 400 and status < 500 then
      -- Client error: no point retrying.
      return false, status, resp_body, err
    end
    -- 5xx or nil (connection failure): retry with back-off.
    if attempt < max_retries then
      sleep_fn(delay)
      delay = delay * 2
    end
  end
  return false, last_status, last_body, last_err
end

-- ---------------------------------------------------------------------------
-- Events retry queue (#330). Mirrors the usage retry queue from #309:
-- the in-call `post_with_retry` above handles transient blips with short
-- backoff; on its final failure the events batch is enqueued here and drained
-- oldest-first when the API recovers.
--
-- Backoff: 30, 60, 120, 240, 480, 900 seconds (capped at 900), ±10% jitter —
-- identical to usage.lua so docs/resilience.md §2 covers both with one rule.
--
-- Bounded in-memory queue. Events are higher-volume than usage, so when the
-- cap is exceeded we drop the OLDEST batches and keep recent activity —
-- prolonged outage tradeoff is documented in docs/resilience.md §2.
-- ---------------------------------------------------------------------------
local BACKOFF_BASE       = 30
local BACKOFF_MAX        = 900   -- 15 min
local EVENTS_QUEUE_CAP   = 1000  -- batches; conntrack flushes up to ~6/min, so ~5–10 min of buffer

local function next_backoff(attempts, rng_fn)
  local nominal = BACKOFF_BASE * (2 ^ (attempts - 1))
  if nominal > BACKOFF_MAX then nominal = BACKOFF_MAX end
  local j = (rng_fn or math.random)()
  local scale = 0.9 + 0.2 * j
  return math.floor(nominal * scale + 0.5)
end

function M.new_event_queue()
  return { batches = {}, cap = EVENTS_QUEUE_CAP }
end

-- enqueue_events(queue, events, now, rng_fn, log)
-- Push an event batch with attempts=1; if the queue exceeds its cap, drop the
-- oldest batches (intentional — keep recent activity over ancient under
-- prolonged outage) and log each drop loudly.
function M.enqueue_events(queue, events, now, rng_fn, log)
  log = log or default_log()
  queue.batches[#queue.batches + 1] = {
    events          = events,
    attempts        = 1,
    next_attempt_at = now + next_backoff(1, rng_fn),
  }
  local cap = queue.cap or EVENTS_QUEUE_CAP
  while #queue.batches > cap do
    local dropped = table.remove(queue.batches, 1)
    log.err("conntrack: events queue cap %d exceeded, dropping oldest batch (%d events)",
            cap, #(dropped.events or {}))
  end
end

-- drain_events(events_url, router_id, queue, post_fn, now, rng_fn, log)
-- Walk the queue in insertion order. For each batch whose next_attempt_at ≤
-- now, POST it. On success remove and continue; on failure reschedule with
-- the next backoff and STOP draining — same shape as usage.drain so that a
-- still-flapping API doesn't get hammered with the whole backlog at once.
function M.drain_events(events_url, router_id, queue, post_fn, now, rng_fn, log)
  log = log or default_log()
  local i = 1
  while i <= #queue.batches do
    local b = queue.batches[i]
    if b.next_attempt_at > now then
      i = i + 1
    else
      local payload = M.encode_events_body(router_id, b.events, log)
      if not payload then
        -- Empty/malformed batch should never reach the queue, but if it does,
        -- drop it rather than wedge the drain on an unsendable body.
        table.remove(queue.batches, i)
      else
        local status, _body, _err = post_fn(events_url, payload)
        if status and status >= 200 and status < 300 then
          table.remove(queue.batches, i)
        else
          b.attempts        = b.attempts + 1
          b.next_attempt_at = now + next_backoff(b.attempts, rng_fn)
          log.warn("conntrack: drain batch failed attempt=%d events=%d; next in %ds (status=%s)",
                   b.attempts, #b.events, b.next_attempt_at - now, tostring(status))
          return
        end
      end
    end
  end
end

-- ---------------------------------------------------------------------------
-- handle_flow(flow, ctx, batcher)
--
-- Processes a single outbound flow: emits a first_seen_mac event the first
-- time we observe a given MAC, then always emits a connection_attempt event.
--
-- ctx: {
--   arp_table        table   { ip -> mac }
--   nft_sets         table   { hostname -> { ip -> true } }
--   blocked_macs     table   { mac -> true }       (filled by render.update_shared)
--   blocked_reason   table   { mac -> reason }     (filled by render.update_shared)
--   eb_hosts_by_mac  table   { mac -> { hostname -> true } }
--                            (filled by render.update_shared — per-host eb_/bl_ drops)
--   ea_hosts_by_mac  table   { mac -> { hostname -> true } }
--                            (filled by render.update_shared — extraAllowed carve-outs)
--   bl_ids_by_mac    table   { mac -> { blocklist_id -> true } }
--                            (filled by render.update_shared — the ASSIGNED
--                            list ids, not their membership; the DNS-miss
--                            category probe iterates these, #2719)
--   exec_fn          func    (optional) injectable exec_fn(cmd) -> exit_code;
--                            used by nft_eb_hit fallback when hname is nil (#579)
--   inc_counter_fn   func    (optional) inc_counter_fn(name, labels, by) —
--                            agent metric sink for the #2719 slow-path ceiling
--   slow_path_max_probes  int (optional) per-flow nft probe ceiling (#2719)
--   slow_path_max_seconds num (optional) per-flow wall-clock ceiling (#2719)
--   now_fn           func    (optional) monotonic clock for the ceiling (tests)
--   fqdn_retry_state table   (optional) per-second retry budget for the
--                            FQDN-attribution race (#583); created by
--                            new_fqdn_retry_state. Passing nil disables retries.
--   reported_macs    table   { mac -> true }  (mutated)
--   leases           table   { mac -> { ip, hostname } } (may be nil/empty)
--   ts               string  ISO8601 timestamp to attach to the events
-- }
-- ---------------------------------------------------------------------------
function M.handle_flow(flow, ctx, batcher)
  local log = ctx.log or default_log()
  local mac = M.arp_lookup_mac(flow.src_ip, ctx.arp_table or {})

  if mac and not (ctx.reported_macs or {})[mac] then
    local lease = (ctx.leases or {})[mac]
    local ip, hostname
    if lease then ip = lease.ip; hostname = lease.hostname end
    log.debug("handle_flow: first_seen_mac mac=%s ip=%s hostname=%s",
              mac, tostring(ip), tostring(hostname))
    batcher.add(M.build_first_seen_mac_event({
      mac = mac, ip = ip, hostname = hostname, ts = ctx.ts,
    }))
    if ctx.reported_macs then ctx.reported_macs[mac] = true end
    -- #249: if we emitted first_seen_mac with no hostname (DHCP race or
    -- iOS Private Address without option 12), remember to re-emit once
    -- dnsmasq's lease file catches up.
    if not hostname and ctx.pending_hostname_macs then
      ctx.pending_hostname_macs[mac] = true
    end
  elseif mac and ctx.pending_hostname_macs and ctx.pending_hostname_macs[mac] then
    local lease = (ctx.leases or {})[mac]
    if lease and lease.hostname then
      log.debug("handle_flow: dhcp_lease (late) mac=%s ip=%s hostname=%s",
                mac, tostring(lease.ip), tostring(lease.hostname))
      batcher.add(M.build_dhcp_lease_event({
        mac = mac, ip = lease.ip, hostname = lease.hostname, ts = ctx.ts,
      }))
      ctx.pending_hostname_macs[mac] = nil
    end
  end

  -- Hostname attribution: prefer the injected lookup (dnsmasq query-log cache,
-- see dns_log.lua + #259), fall back to ipset attribution. Both can miss for
-- direct-IP traffic; build_event uses dest_ip as a last resort.
  -- #583: the dns-tail sidecar may not have flushed the cache for a freshly
  -- resolved dst_ip by the time conntrack -E NEW fires here. attribute_hostname
  -- retries the lookup once after a short pause when the first read misses,
  -- gated by a per-second budget on ctx.fqdn_retry_state so genuinely IP-only
  -- flows (DoH, hard-coded IPs) don't dominate the loop.
  local hname = M.attribute_hostname(flow.dst_ip, ctx.lookup_hostname, ctx.fqdn_retry_state)
  if not hname then
    hname = M.ipset_lookup_hostname(flow.dst_ip, ctx.nft_sets or {})
  end
  -- #1655 / #1708: last-resort static IP-range → label fallback for flows
  -- with no SNI and no DNS resolution (Apple APNs on 17.0.0.0/8, well-known
  -- public DNS resolvers, etc.). LABELS ONLY — see static_ip_labels.lua
  -- header. The precedence (DNS > SNI-via-shared-cache > nft_sets > static
  -- map) is intentional: a real attribution must always beat a static guess.
  -- The label is emitted as a HostId.Label variant (`type="label"`); the
  -- source string is threaded through to build_event so the wire shape is
  -- assembled in exactly one place.
  local hlabel_source
  if not hname then
    hname, hlabel_source = static_ip_labels.lookup(flow.dst_ip)
  end

  -- #1708 carve-out / blocklist matching uses `match_hname`, NOT `hname`:
  -- label-typed attributions ("apple-push", "google-dns") must not feed the
  -- string-level suffix tests below (extraAllowed carve-out, extraBlocked
  -- host_matches, category-blocklist host_matches). The server-side
  -- HostMatch.matchesAny already returns false for HostId.Label, so a label
  -- can never appear in ea_hosts/eb_hosts/bl_hosts in the first place — this
  -- is defense-in-depth keeping the label/fqdn boundary local to the agent.
  -- For label-attributed flows we fall through to the IP-based nft_eb_hit
  -- path, which is the correct semantics: there is no real hostname here,
  -- only an IP and a synthetic display name.
  local match_hname = (hlabel_source == nil) and hname or nil

  -- Per-MAC block lookup (#297): pause and time-limit block every flow from
  -- the device, regardless of destination IP. render.update_shared rebuilds
  -- blocked_macs/blocked_reason from the policy snapshot on every poll.
  local allowed = not (mac and ctx.blocked_macs and ctx.blocked_macs[mac])
  local reason
  if not allowed and ctx.blocked_reason then
    reason = ctx.blocked_reason[mac]
  end

  -- extraAllowed override for blocked MACs (#421): if the MAC is blocked by
  -- the per-MAC rule (pause / schedule / time-limit) but hname matches a host
  -- in ea_hosts_by_mac[mac] (using dnsmasq's suffix-match semantics), the
  -- kernel's `ip daddr != @ea_<mac>_<host>` clause fires and the packet IS
  -- forwarded.  Flip allowed back to true so the event is reported correctly.
  --
  -- Limitation: when hname is nil (no DNS attribution), we cannot determine
  -- whether the ea_ carve-out fired.  The flow is left as blocked=true even
  -- if the kernel allowed it.  This is documented as a known reporting gap
  -- when DNS attribution is missing for flows from a blocked MAC.
  if not allowed and match_hname and mac then
    local ea_hosts = ctx.ea_hosts_by_mac and ctx.ea_hosts_by_mac[mac]
    if ea_hosts then
      for ea_host in pairs(ea_hosts) do
        if M.host_matches(match_hname, ea_host) then
          allowed = true
          reason  = nil
          break
        end
      end
    end
  end

  -- Per-host (eb_/bl_) block check: if the MAC is still considered allowed
  -- above, check whether hname matches an extraBlocked or blocklist host for
  -- this MAC using dnsmasq's nftset suffix-match semantics.  The kernel
  -- enforces drops via:
  --   ether saddr <mac> ip daddr @eb_<host> drop
  --   ether saddr <mac> ip daddr @bl_<id>   drop
  -- We drive this decision off the attributed hostname (hname) matched against
  -- eb_hosts_by_mac / bl_hosts_by_mac, NOT off nft_sets[host][dst_ip].  The
  -- lua nft_sets table is never populated in production (only the kernel
  -- ipsets are); nft_sets is only a partial attribution fallback for
  -- site_limits domains.
  --
  -- Each eb_/bl_ drop carries `ip daddr != @ea_<mac>_<host>` exception clauses,
  -- so we must NOT classify as blocked when hname also matches a host in
  -- ea_hosts_by_mac[mac] (#421).
  --
  -- When hname is nil (DNS attribution miss — e.g. DoH, Apple Private Relay,
  -- or a dns-tail race), fall back to querying the live nft set membership for
  -- each extraBlocked host's eb_ set (#579).  This is the slow path: one
  -- `nft get element` call per eb_host per flow.  ctx.exec_fn is injectable
  -- for tests; defaults to os.execute.
  --
  -- #594: extraBlocked hits report reason="host"; category-blocklist hits
  -- report reason="category:<id>" so the block page and connection_event log
  -- can name the matched list.
  --
  -- #2719: the slow path is bounded three ways. (1) A destination that cannot
  -- be in any DNS-resolved set — multicast, link-local, broadcast — is never
  -- probed at all. (2) The category loop probes one bl_<id> set per assigned
  -- list rather than one eb_-style set per member host. (3) A per-flow, per-
  -- stage probe budget caps every loop. Each nft probe is a fork+exec inside
  -- the watcher's foreground loop, so an unbounded loop here does not slow the
  -- agent down — it stops it (six hours on prod, agent alive and silent).
  local slow_path_ok = M.is_attributable_dst(flow.dst_ip)
  local budget = new_probe_budget(ctx)
  local function check_ea_carveout(eb_hit_host)
    local ea_hosts = ctx.ea_hosts_by_mac and ctx.ea_hosts_by_mac[mac]
    if not ea_hosts then return false end
    for ea_host in pairs(ea_hosts) do
      if match_hname then
        if M.host_matches(match_hname, ea_host) then return true end
      else
        if ea_host == eb_hit_host then return true end
      end
    end
    return false
  end

  -- #2719: the carve-out check for a slow-path hit, where there is no hostname
  -- to compare against. The kernel's per-host and per-category drops are
  --   ... daddr @eb_<host>|@bl_<id> != @ea_<mac>_<host> … != @global_allow
  -- so a set hit ALONE is not a drop. With no attribution the only way to
  -- evaluate the exception clauses is to ask the kernel about the same sets it
  -- excepts on: the MAC's per-(mac, host) ea_ sets and the fleet-wide
  -- global_allow. Returns "carved" (the kernel let this flow through),
  -- "clear" (no carve-out applies), or "unknown" when the budget ran out
  -- mid-check — and "unknown" must NOT be labelled blocked, because claiming a
  -- drop we could not verify is how an allowed host ends up reported as
  -- category-blocked (the #2601 shape, as a false event rather than a false
  -- drop).
  local function slow_path_carve_state()
    if not budget.take("carve") then return "unknown" end
    if M.nft_ga_hit(flow.dst_ip, ctx.exec_fn) then return "carved" end
    local ea_hosts = ctx.ea_hosts_by_mac and ctx.ea_hosts_by_mac[mac]
    if ea_hosts then
      for _, ea_host in ipairs(sorted_keys(ea_hosts)) do
        if not budget.take("carve") then return "unknown" end
        if M.nft_ea_hit(flow.dst_ip, mac, ea_host, ctx.exec_fn) then
          return "carved"
        end
      end
    end
    return "clear"
  end

  if allowed and mac then
    local eb_hosts = ctx.eb_hosts_by_mac and ctx.eb_hosts_by_mac[mac]
    if eb_hosts and (match_hname or slow_path_ok) then
      local eb_hit = false
      local eb_hit_host
      -- Sorted, not pairs(): under a tripped ceiling the subset of hosts that
      -- actually get probed must not vary run to run, or an intermittently
      -- capped flow classifies differently for identical input.
      for _, host in ipairs(sorted_keys(eb_hosts)) do
        if match_hname then
          if M.host_matches(match_hname, host) then
            eb_hit = true; eb_hit_host = host; break
          end
        else
          if not budget.take("eb") then break end
          if M.nft_eb_hit(flow.dst_ip, host, ctx.exec_fn) then
            eb_hit = true; eb_hit_host = host; break
          end
        end
      end
      -- On the slow path check_ea_carveout can only match by host name, which
      -- a probe hit does supply here — but the kernel also excepts on
      -- global_allow and on ea_ sets for OTHER hosts covering the same IP, so
      -- the nft carve check runs too and a "carved"/"unknown" answer wins.
      if eb_hit and not match_hname then
        local carve = slow_path_carve_state()
        if carve ~= "clear" then eb_hit = false end
      end
      if eb_hit and not check_ea_carveout(eb_hit_host) then
        allowed = false
        -- #1645: name the matched eb_<host> rule so triage can see which
        -- extraBlocked entry fired (otherwise an IP-anycast overlap on a
        -- shared CDN looks like an opaque "host" block). Symmetric with
        -- the `category:<id>` reason for the bl_ path below.
        --
        -- When `eb_hosts` for this MAC contains more than one host whose
        -- ipset covers the same `dst_ip` (rare overlap, e.g. same anycast
        -- IP resolved for two different extraBlocked apex domains), the
        -- labeled `eb_hit_host` is the first in sorted order (#2719 made the
        -- iteration deterministic; it used to be `pairs` order). The drop
        -- itself is unaffected (the kernel already matched at least one eb_
        -- set); only the debug label is chosen here.
        reason  = "host:" .. eb_hit_host
      end
    end
  end

  -- #594: per-host category-blocklist check. Same machinery as eb_ above but
  -- the matched host's blocklist id is carried into the reason string. Skip
  -- if eb_ already classified the flow as blocked (extraBlocked wins).
  if allowed and mac then
    local bl_hosts = ctx.bl_hosts_by_mac and ctx.bl_hosts_by_mac[mac]
    local bl_hit_host
    local bl_hit_id
    if match_hname and bl_hosts then
      -- Fast path: the attributed hostname is matched against the MAC's
      -- membership table, so the event names both the list AND the host.
      for host, id in pairs(bl_hosts) do
        if M.host_matches(match_hname, host) then
          bl_hit_host = host; bl_hit_id = id; break
        end
      end
    elseif not match_hname and slow_path_ok then
      -- #2719 slow path: one probe per ASSIGNED LIST against the kernel's
      -- bl_<id>/bl6_<id> set, which dnsmasq's nftset= callback already
      -- populates with every resolved member IP. The old shape probed one
      -- eb_-style set per MEMBER HOST of every list (180,343 forks on the
      -- prod family router) purely so the reason string could name the host;
      -- a correct `category:<id>` label is worth more than an agent that
      -- stops. ctx.bl_ids_by_mac is maintained by render.update_shared from
      -- the snapshot's blocklistIds — it is the ids themselves, never a
      -- reduction over the membership table, so reading it is O(1) in list
      -- size. Ids are probed in sorted order so the label is deterministic
      -- when a dst_ip is in more than one list.
      local bl_ids = ctx.bl_ids_by_mac and ctx.bl_ids_by_mac[mac]
      if bl_ids then
        for _, id in ipairs(sorted_keys(bl_ids)) do
          if not budget.take("bl") then break end
          if M.nft_bl_hit(flow.dst_ip, id, ctx.exec_fn) then
            bl_hit_id = id; break
          end
        end
        -- A bl_ hit is only a drop if neither carve-out set covers the IP.
        -- Unlike the eb_ path there is no host name to compare here, so the
        -- kernel's own sets are the only available answer. "unknown" (budget
        -- exhausted mid-check) is treated as not-blocked: reporting a drop we
        -- could not verify would put an allowed host in the household's
        -- Connection Events as category-blocked.
        if bl_hit_id and slow_path_carve_state() ~= "clear" then
          bl_hit_id = nil
        end
      end
    end
    if bl_hit_id and not check_ea_carveout(bl_hit_host) then
      allowed = false
      reason  = "category:" .. tostring(bl_hit_id)
    end
  end

  -- #2719: a tripped ceiling means some flow's candidate set outgrew the slow
  -- path's assumptions. Meter it so a recurrence is a visible series instead
  -- of a silently mislabelled event (the wedge itself was invisible — the
  -- agent stopped emitting the very metrics that would have shown it).
  local capped = budget.tripped()
  if capped then
    log.warn("handle_flow: slow-path probe ceiling hit (%s) dst=%s mac=%s — " ..
             "flow labelled without full nft membership check",
             capped, tostring(flow.dst_ip), tostring(mac))
    if ctx.inc_counter_fn then
      ctx.inc_counter_fn("conntrack_slow_path_capped_total", { reason = capped }, 1)
    end
  end
  log.debug("handle_flow: connection_attempt src=%s dst=%s mac=%s hostname=%s allowed=%s reason=%s",
            flow.src_ip, flow.dst_ip, tostring(mac), tostring(hname),
            tostring(allowed), tostring(reason))
  batcher.add(M.build_event({
    mac               = mac,
    hostname          = hname,
    host_label_source = hlabel_source,
    dest_ip           = flow.dst_ip,
    allowed           = allowed,
    reason            = reason,
    ts                = ctx.ts,
  }))
end

-- ---------------------------------------------------------------------------
-- parse_conntrack_line(line) -> { src_ip, dst_ip, proto, dport } | nil
--
-- Parses a line from `conntrack -E -e NEW`.  Example input:
--   [NEW] tcp 6 120 SYN_SENT src=192.168.1.42 dst=1.2.3.4 sport=54321 dport=443 ...
-- ---------------------------------------------------------------------------
function M.parse_conntrack_line(line)
  if not line:find("%[NEW%]") then return nil end
  local proto  = line:match("%[NEW%]%s+(%a+)")
  local src_ip = line:match("src=(%S+)")
  local dst_ip = line:match("dst=(%S+)")
  local dport  = line:match("dport=(%d+)")
  if not (src_ip and dst_ip) then return nil end
  return { src_ip = src_ip, dst_ip = dst_ip, proto = proto, dport = tonumber(dport) }
end

-- ---------------------------------------------------------------------------
-- is_wan_bound(flow, lan_prefix, lan_prefix_v6) -> bool
--
-- Returns true when the flow is an outbound WAN-destined flow: src_ip is on
-- the LAN AND dst_ip is NOT on the LAN.  This filters out LAN-internal flows
-- (device → router, device → LAN peer, mDNS, DHCP, etc.) that should never
-- appear in connection_events — they are noise with no parental-control signal.
-- (#575)
--
-- Family is detected from src_ip — a colon means IPv6, otherwise v4 — so a
-- single helper handles both stacks (#1688).
--
-- v4 LAN membership is a stable RFC1918 prefix match (`lan_prefix`).
--
-- v6 (#1796): a static `lan_prefix_v6` cannot identify LAN-sourced traffic in
-- practice. Home IPv6 is not NATed, so devices source internet flows from their
-- GUA — which is ISP-delegated and dynamic — not the ULA. Matching on the ULA
-- (the only stable prefix) therefore records ZERO internet v6, which is why the
-- option shipped commented-out and every unconfigured router silently dropped
-- all v6 connection events. Instead, when the caller passes `lan_ip_set` (the
-- NDP/ARP neighbor map { ip -> mac } the agent already builds for v6 MAC
-- attribution), a v6 flow is LAN-bound iff `src` is a known LAN neighbor and
-- `dst` is not — covering GUA, ULA, and privacy/temporary addresses with no
-- prefix bookkeeping. `lan_prefix_v6`, if authored, is honored as an additional
-- accept path (union). With neither a neighbor set nor a prefix, v6 is rejected
-- (back-compat: the prior empty-prefix default).
--
-- lan_prefix    example: "192.168.1."
-- lan_prefix_v6 example: "fdaa:bbbb:cccc:"  (optional override; usually unset)
-- lan_ip_set    example: { ["2601:280:4700:f32::42"] = "aa:bb:.." }  (parse_arp_table)
-- ---------------------------------------------------------------------------
function M.is_wan_bound(flow, lan_prefix, lan_prefix_v6, lan_ip_set)
  -- #2719: multicast and link-local destinations are link-scoped LAN traffic
  -- by definition — a DHCPv6 solicit to ff02::1:2, an mDNS query to
  -- 224.0.0.251 — so they are exactly the noise #575 exists to filter. They
  -- reached here only because the LAN tests are membership tests on the
  -- neighbor set / prefix, and no neighbor entry or prefix ever covers a
  -- multicast group. Filtering at the classifier rather than only at the
  -- slow-path guard means such flows never become connection_events at all,
  -- which is the correct semantics AND removes the only category of flow that
  -- is guaranteed to miss every attribution path.
  if not M.is_attributable_dst(flow.dst_ip) then return false end
  local is_v6 = flow.src_ip:find(":", 1, true) ~= nil
  if not is_v6 then
    if not lan_prefix or lan_prefix == "" then return false end
    return flow.src_ip:sub(1, #lan_prefix) == lan_prefix
       and flow.dst_ip:sub(1, #lan_prefix) ~= lan_prefix
  end
  -- v6: neighbor-membership (preferred) unioned with an optional authored prefix.
  -- lan_ip_set MUST be scoped to LAN-bridge neighbors (parse_arp_table(lan_dev),
  -- #2368). The earlier "all interfaces is safe" assumption was FALSE: the
  -- upstream/default router emits its own IPv6 (RA/DHCPv6-PD/NDP), so an
  -- unscoped `ip -6 neigh show` puts the WAN gateway's LL/ULA in the set as a
  -- flow *src* with a non-LAN dst → classed LAN-sourced → the edge router
  -- autocreated as a phantom device. Scoping the set to the LAN dev keeps only
  -- real LAN neighbors (GUA/ULA/privacy addrs, #1796) here.
  local src_lan, dst_lan = false, false
  if lan_ip_set then
    src_lan = lan_ip_set[flow.src_ip] ~= nil
    dst_lan = lan_ip_set[flow.dst_ip] ~= nil
  end
  if lan_prefix_v6 and lan_prefix_v6 ~= "" then
    src_lan = src_lan or flow.src_ip:sub(1, #lan_prefix_v6) == lan_prefix_v6
    dst_lan = dst_lan or flow.dst_ip:sub(1, #lan_prefix_v6) == lan_prefix_v6
  end
  return src_lan and not dst_lan
end

-- is_outbound is kept as a backward-compatible alias so existing call sites
-- outside the watch loop continue to work. New code should use is_wan_bound.
-- @deprecated use is_wan_bound
function M.is_outbound(flow, lan_prefix, lan_prefix_v6)
  return M.is_wan_bound(flow, lan_prefix, lan_prefix_v6)
end

-- ---------------------------------------------------------------------------
-- watch(cfg) — blocking event loop; called from the main agent daemon
--
-- cfg: {
--   router_id      string    UUID of this router
--   api_url        string    base URL, e.g. "http://192.168.1.1:8080"
--   router_token   string    bearer token
--   nft_sets          table     shared ref: { hostname -> { ip -> true } }
--   blocked_macs      table     shared ref: { mac -> true }  (filled by render.lua)
--   blocked_reason    table     shared ref: { mac -> reason_string }
--   eb_hosts_by_mac   table     shared ref: { mac -> { hostname -> true } }
--   ea_hosts_by_mac   table     shared ref: { mac -> { hostname -> true } }
--   lan_prefix     string    default "192.168.1."
--   lan_prefix_v6  string    optional v6 LAN ULA prefix (e.g. "fdaa:bbbb:cccc:")
--                            — when unset, v6 flows are filtered out (#1688).
--   lan_dev        string    LAN bridge dev (e.g. "br-lan") — scopes the v6 NDP
--                            neighbor set so WAN-side neighbors (the upstream
--                            router) don't autocreate as devices (#2368). Empty
--                            → unfiltered (back-compat).
--   max_batch      int       default 50
--   flush_interval int       default 10  (seconds)
--   max_retries    int       default 3
--   base_delay     int       default 2   (seconds, doubles each retry)
--   http_post      function  injectable: post_fn(url, body) -> status, body
--   sleep_fn       function  injectable for tests
--   exec_fn        function  injectable: exec_fn(cmd) -> exit_code (default os.execute)
--                            Used by the nft eb_ membership fallback for nil-hname flows (#579).
--   fqdn_retry_max_per_second int  (optional) cap on FQDN-attribution retries per
--                                  second when the first lookup misses (#583).
--   fqdn_retry_delay_seconds  num  (optional) sleep before the second lookup
--                                  (default 0.1 — long enough to absorb the
--                                  typical dns-tail flush latency).
--   fqdn_retry_sleep_fn       function (optional) injectable sleep for tests.
--   fqdn_retry_state          table   (optional) inject a pre-built retry state
--                                     (tests; usually omitted).
-- }
-- ---------------------------------------------------------------------------
function M.watch(cfg)
  local log           = cfg.log            or default_log()
  local lan_prefix    = cfg.lan_prefix     or "192.168.1."
  local lan_prefix_v6 = cfg.lan_prefix_v6  or ""
  -- #2368: LAN bridge dev used to scope the v6 NDP neighbor set so WAN-side
  -- neighbors (the upstream/default router) never enter it. Empty → unfiltered
  -- (back-compat); the agent passes the probed `network.lan.device`.
  local lan_dev       = cfg.lan_dev        or ""
  local max_batch     = cfg.max_batch      or 50
  local flush_int  = cfg.flush_interval or 10
  local max_retry  = cfg.max_retries    or 3
  local base_delay = cfg.base_delay     or 2

  local events_url = cfg.api_url .. "/api/router/events"
  local event_queue = cfg.event_queue or M.new_event_queue()

  -- #583: per-second budget for FQDN-attribution race retries.
  local fqdn_retry_state = cfg.fqdn_retry_state or M.new_fqdn_retry_state({
    max_per_second = cfg.fqdn_retry_max_per_second,
    delay_seconds  = cfg.fqdn_retry_delay_seconds,
    sleep_fn       = cfg.fqdn_retry_sleep_fn,
  })

  local function do_post(url, body)
    return cfg.http_post(url, body, {
      ["Authorization"] = "Bearer " .. cfg.router_token,
      ["Content-Type"]  = "application/json",
    })
  end

  local batcher = M.new_batcher(max_batch, flush_int, function(events)
    log.debug("conntrack: flushing batch size=%d url=%s", #events, events_url)
    local payload = M.encode_events_body(cfg.router_id, events, log)
    -- #1126: empty/malformed bodies never go to the wire. The batcher only
    -- flushes non-empty buffers, so this is defense-in-depth.
    if not payload then return end
    local ok, last_status, last_body, last_err = M.post_with_retry(
      events_url, payload, max_retry, base_delay, do_post, cfg.sleep_fn)
    if not ok then
      -- #330: instead of dropping after the in-call retries are exhausted,
      -- enqueue the batch for long-form retry mirroring the usage queue (#309).
      -- drain happens in the main watch loop below on every tick.
      local body_str = last_body and tostring(last_body) or ""
      if #body_str > 200 then body_str = body_str:sub(1, 200) .. "...(truncated)" end
      log.warn("conntrack: events POST failed after %d in-call retries (status=%s body=%q) err=%s; enqueuing %d events (queue depth=%d)",
               max_retry, tostring(last_status), body_str, tostring(last_err),
               #events, #event_queue.batches + 1)
      M.enqueue_events(event_queue, events, os.time(), nil, log)
    else
      log.debug("conntrack: POST events success status=%d events=%d",
                last_status, #events)
    end
  end)

  -- #1126: hand the batcher to the caller so the agent's nflog drain path can
  -- add synthesized forward-drop events to the SAME batch + retry queue (the
  -- whole loop is single-fibered/cooperative, so this is race-free). The
  -- batcher's max-size auto-flush and the per-iteration batcher.tick() below
  -- then flush conntrack and nflog events together.
  if cfg.register_batcher then cfg.register_batcher(batcher) end

  -- #2024 idle-heartbeat cadence (seconds). The shell wrapper below echoes
  -- M.TICK_SENTINEL into the popen stream every tick_int seconds so the read
  -- loop wakes — and drives on_tick — even when conntrack is silent. Sanitize
  -- to a positive integer: tick_int feeds string.format("%d") and shell `sleep`
  -- (a fractional value is a hard error on Lua 5.1; 0 busy-loops).
  local tick_int = M.sanitize_tick_interval(cfg.tick_interval)

  log.info("conntrack: starting watcher lan_prefix=%s lan_prefix_v6=%s max_batch=%d flush_interval=%ds tick_interval=%ds",
           lan_prefix, lan_prefix_v6, max_batch, flush_int, tick_int)
  -- #1688: NO `-f` family flag. conntrack(8) documents the default as ipv4,
  -- but for `-E` (events) conntrack-tools opens the netlink socket with
  -- NFCT_ALL_CT_GROUPS — a family-agnostic subscription — and the `-f` flag
  -- only constrains table dumps (`-L`). So omitting `-f` is how we read BOTH
  -- v4 and v6 NEW events from a single reader. This is load-bearing for the
  -- v6 attribution path: the Gate 2 e2e suite (`test_v6_*.py`) verifies it
  -- end-to-end, and `+kmod-nf-conntrack6` in the package DEPENDS ensures the
  -- kernel-side v6 hooks the netlink stream pulls from are present.
  --
  -- #2024: conntrack is backgrounded and a heartbeat loop echoes the sentinel
  -- alongside it, so read("*l") returns at least once per tick_int even with
  -- zero NEW events. Both processes write to the same popen pipe; sentinel and
  -- conntrack lines are far under PIPE_BUF (4096 B), so writes are atomic and
  -- never tear. Notes on the wrapper:
  --   * conntrack's argv stays EXACTLY `conntrack -E -e NEW` (the redirect and
  --     `&` are shell syntax, not argv), so the #1716 orphan sweeper — which
  --     matches that exact /proc cmdline at ppid==1 — still reaps a conntrack
  --     left behind across a procd restart.
  --   * `kill -0 $ct` guards the heartbeat: when conntrack dies the loop exits,
  --     the wrapper closes the pipe, read() returns nil, and watch() breaks
  --     (then procd respawns the agent). Without this guard the live heartbeat
  --     would hold the pipe open and MASK a conntrack crash — never restarting.
  --   * `echo ... || break` exits the wrapper when the agent closes the read
  --     end (SIGPIPE on echo), so the wrapper does not linger.
  local open_reader = cfg.open_reader or function()
    local cmd = string.format(
      "conntrack -E -e NEW 2>/dev/null & ct=$!; "
        .. "while kill -0 \"$ct\" 2>/dev/null; do echo '%s' || break; sleep %d; done",
      M.TICK_SENTINEL, tick_int)
    return io.popen(cmd, "r")
  end
  local handle = open_reader()
  if not handle then
    log.err("conntrack: cannot start conntrack -E -e NEW")
    return
  end

  local reported_macs         = {}
  local pending_hostname_macs = {}
  local leases_path           = cfg.leases_path or "/tmp/dhcp.leases"

  -- #1796: v6 LAN-source is decided by NDP-neighbor membership, so the neighbor
  -- table must be available *before* the wan-bound check (v4 still uses the
  -- prefix and needs no table). Cache it per wall-clock second so a burst of
  -- conntrack NEW events can't shell out to `ip -6 neigh` once per line.
  local arp_cache, arp_cache_sec = nil, nil
  local function neighbor_table()
    local now = os.time()
    if arp_cache and arp_cache_sec == now then return arp_cache end
    -- #2368: scope the v6 NDP set to the LAN bridge dev.
    arp_cache, arp_cache_sec = M.parse_arp_table(lan_dev), now
    return arp_cache
  end

  while true do
    local line = handle:read("*l")
    if not line then break end

    -- #2024: a heartbeat sentinel is an inert wake — skip flow parsing but fall
    -- through to batcher.tick() / event-queue drain / on_tick below so the
    -- cooperative timers fire on the wall-clock cadence even with no NEW flows.
    local flow = (line ~= M.TICK_SENTINEL) and M.parse_conntrack_line(line) or nil
    -- Only v6 needs the neighbor set for the LAN-source decision; v4 stays on
    -- the prefix and pays no per-line table cost.
    local lan_ip_set = (flow and flow.src_ip:find(":", 1, true)) and neighbor_table() or nil
    if flow and M.is_wan_bound(flow, lan_prefix, lan_prefix_v6, lan_ip_set) then
      -- v6 reuses the cached neighbor table (src is guaranteed present — it just
      -- passed the membership check); v4 fetches a fresh ARP table as before
      -- (lan_dev scopes only the v6 half; the v4 /proc/net/arp lookup is
      -- unaffected — #2368).
      local arp = lan_ip_set or M.parse_arp_table(lan_dev)
      local mac_candidate = M.arp_lookup_mac(flow.src_ip, arp)
      -- Parse the lease file when (a) MAC is new, or (b) MAC is pending a
      -- late hostname (#249 — re-emit dhcp_lease once dnsmasq writes it).
      local leases
      if mac_candidate and
         (not reported_macs[mac_candidate] or pending_hostname_macs[mac_candidate]) then
        leases = M.parse_dhcp_leases(leases_path)
      end

      M.handle_flow(flow, {
        arp_table             = arp,
        nft_sets              = cfg.nft_sets or {},
        lookup_hostname       = cfg.lookup_hostname,
        fqdn_retry_state      = fqdn_retry_state,
        blocked_macs          = cfg.blocked_macs,
        blocked_reason        = cfg.blocked_reason,
        eb_hosts_by_mac       = cfg.eb_hosts_by_mac,
        ea_hosts_by_mac       = cfg.ea_hosts_by_mac,
        bl_hosts_by_mac       = cfg.bl_hosts_by_mac,
        bl_ids_by_mac         = cfg.bl_ids_by_mac,
        exec_fn               = cfg.exec_fn,
        inc_counter_fn        = cfg.inc_counter_fn,
        slow_path_max_probes  = cfg.slow_path_max_probes,
        slow_path_max_seconds = cfg.slow_path_max_seconds,
        reported_macs         = reported_macs,
        pending_hostname_macs = pending_hostname_macs,
        leases                = leases or {},
        ts                    = os.date("!%Y-%m-%dT%H:%M:%SZ"),
        log                   = log,
      }, batcher)
    end

    batcher.tick()

    -- Drain the events retry queue on every iteration. Cheap when empty
    -- (just checks #batches); when populated, drain_events posts at most one
    -- batch per tick (stops at first failure — see comment on drain_events).
    if #event_queue.batches > 0 then
      M.drain_events(events_url, cfg.router_id, event_queue, do_post,
                     os.time(), nil, log)
    end

    -- Drive co-operative timers (policy poll, usage report) from the main agent.
    if cfg.on_tick then cfg.on_tick() end
  end

  handle:close()
  batcher.flush()
end

return M
