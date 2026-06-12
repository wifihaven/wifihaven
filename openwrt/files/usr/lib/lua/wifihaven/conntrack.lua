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
-- flow — acceptable because it is the slow-path (#579 logging correction).
--
-- The bl_ labeling fallback in handle_flow piggybacks on this same helper
-- (the comment there explains why both paths share the eb_-style query), so
-- branching family here fixes v6 attribution for category drops too (#1668).
-- ---------------------------------------------------------------------------
function M.nft_eb_hit(dst_ip, eb_host, exec_fn)
  exec_fn = exec_fn or os.execute
  local san  = M.eb_san(eb_host)
  local family_v6 = dst_ip:find(":", 1, true) ~= nil
  local set_prefix = family_v6 and "eb6_" or "eb_"
  local cmd  = string.format(
    "nft get element inet wifihaven %s%s '{ %s }' >/dev/null 2>&1",
    set_prefix, san, dst_ip)
  local ret = exec_fn(cmd)
  -- os.execute returns exit-code (number) on Lua 5.1/5.2, or a
  -- (bool,"exit",code) tuple on Lua 5.3+/LuaJIT.  Handle both.
  if type(ret) == "boolean" then
    return ret  -- Lua 5.3+: true = success (exit 0)
  end
  return ret == 0  -- Lua 5.1/5.2: 0 = success
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
-- parse_arp_table() -> { ip -> mac }
--
-- Reads /proc/net/arp at call time.  Called per-event so the MAC table stays
-- fresh without a background refresh loop.
-- ---------------------------------------------------------------------------
function M.parse_arp_table()
  local result = {}
  local f = io.open("/proc/net/arp", "r")
  if not f then return result end
  f:read("*l")  -- skip header
  for line in f:lines() do
    local ip, _, _, mac = line:match("^(%S+)%s+%S+%s+%S+%s+(%S+)%s+")
    -- mac field is 4th column; re-match properly
    local parts = {}
    for w in line:gmatch("%S+") do parts[#parts + 1] = w end
    -- /proc/net/arp columns: IP, HWtype, Flags, HWaddr, Mask, Device
    if #parts >= 4 and parts[4] ~= "00:00:00:00:00:00" then
      result[parts[1]] = parts[4]
    end
  end
  f:close()
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
-- opts: { mac, hostname, dest_ip, allowed, reason, ts }
-- Returns a Lua table ready for JSON encoding.
--
-- Per #391, the emitted `host` is a tagged union (HostId): an FQDN when the
-- agent has DNS attribution for the flow, otherwise an IPv4 or IPv6 literal
-- tagged by address family. This replaces the old bare `hostname` field that
-- silently put IP literals where a hostname was expected, breaking site-limit
-- pattern matching and polluting the admin UI.
--
-- reason nil → "allow" when allowed=true, "blocked" when false.
-- ---------------------------------------------------------------------------
function M.build_event(opts)
  local host
  if opts.hostname then
    host = { type = "fqdn", value = opts.hostname }
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
--   exec_fn          func    (optional) injectable exec_fn(cmd) -> exit_code;
--                            used by nft_eb_hit fallback when hname is nil (#579)
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
  if not allowed and hname and mac then
    local ea_hosts = ctx.ea_hosts_by_mac and ctx.ea_hosts_by_mac[mac]
    if ea_hosts then
      for ea_host in pairs(ea_hosts) do
        if M.host_matches(hname, ea_host) then
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
  local function check_ea_carveout(eb_hit_host)
    local ea_hosts = ctx.ea_hosts_by_mac and ctx.ea_hosts_by_mac[mac]
    if not ea_hosts then return false end
    for ea_host in pairs(ea_hosts) do
      if hname then
        if M.host_matches(hname, ea_host) then return true end
      else
        if ea_host == eb_hit_host then return true end
      end
    end
    return false
  end

  if allowed and mac then
    local eb_hosts = ctx.eb_hosts_by_mac and ctx.eb_hosts_by_mac[mac]
    if eb_hosts then
      local eb_hit = false
      local eb_hit_host
      for host in pairs(eb_hosts) do
        if hname then
          if M.host_matches(hname, host) then
            eb_hit = true; eb_hit_host = host; break
          end
        else
          if M.nft_eb_hit(flow.dst_ip, host, ctx.exec_fn) then
            eb_hit = true; eb_hit_host = host; break
          end
        end
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
        -- labeled `eb_hit_host` reflects whichever `pairs(eb_hosts)`
        -- iteration order surfaced first — non-deterministic per Lua
        -- semantics. The drop itself is unaffected (the kernel already
        -- matched at least one eb_ set); only the debug label can vary.
        reason  = "host:" .. eb_hit_host
      end
    end
  end

  -- #594: per-host category-blocklist check. Same machinery as eb_ above but
  -- the matched host's blocklist id is carried into the reason string. Skip
  -- if eb_ already classified the flow as blocked (extraBlocked wins).
  if allowed and mac then
    local bl_hosts = ctx.bl_hosts_by_mac and ctx.bl_hosts_by_mac[mac]
    if bl_hosts then
      local bl_hit_host
      local bl_hit_id
      for host, id in pairs(bl_hosts) do
        if hname then
          if M.host_matches(hname, host) then
            bl_hit_host = host; bl_hit_id = id; break
          end
        else
          -- Slow-path: query the live nft eb_-style set keyed on host. The
          -- category enforcement uses bl_<id> sets populated at resolve time
          -- per-host; the per-host eb-style query is still meaningful when
          -- DNS attribution is missing because the same host saturates both
          -- the eb_<host> and bl_<id> indexing paths via dnsmasq.
          if M.nft_eb_hit(flow.dst_ip, host, ctx.exec_fn) then
            bl_hit_host = host; bl_hit_id = id; break
          end
        end
      end
      if bl_hit_host and not check_ea_carveout(bl_hit_host) then
        allowed = false
        reason  = "category:" .. tostring(bl_hit_id)
      end
    end
  end
  log.debug("handle_flow: connection_attempt src=%s dst=%s mac=%s hostname=%s allowed=%s reason=%s",
            flow.src_ip, flow.dst_ip, tostring(mac), tostring(hname),
            tostring(allowed), tostring(reason))
  batcher.add(M.build_event({
    mac      = mac,
    hostname = hname,
    dest_ip  = flow.dst_ip,
    allowed  = allowed,
    reason   = reason,
    ts       = ctx.ts,
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
-- is_wan_bound(flow, lan_prefix) -> bool
--
-- Returns true when the flow is an outbound WAN-destined flow: src_ip is on
-- the LAN AND dst_ip is NOT on the LAN.  This filters out LAN-internal flows
-- (device → router, device → LAN peer, mDNS, DHCP, etc.) that should never
-- appear in connection_events — they are noise with no parental-control signal.
-- (#575)
--
-- lan_prefix example: "192.168.1."
-- ---------------------------------------------------------------------------
function M.is_wan_bound(flow, lan_prefix)
  return flow.src_ip:sub(1, #lan_prefix) == lan_prefix
     and flow.dst_ip:sub(1, #lan_prefix) ~= lan_prefix
end

-- is_outbound is kept as a backward-compatible alias so existing call sites
-- outside the watch loop continue to work. New code should use is_wan_bound.
-- @deprecated use is_wan_bound
function M.is_outbound(flow, lan_prefix)
  return M.is_wan_bound(flow, lan_prefix)
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
  local log        = cfg.log            or default_log()
  local lan_prefix = cfg.lan_prefix     or "192.168.1."
  local max_batch  = cfg.max_batch      or 50
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

  log.info("conntrack: starting watcher lan_prefix=%s max_batch=%d flush_interval=%ds",
           lan_prefix, max_batch, flush_int)
  local handle = io.popen("conntrack -E -e NEW 2>/dev/null", "r")
  if not handle then
    log.err("conntrack: cannot start conntrack -E -e NEW")
    return
  end

  local reported_macs         = {}
  local pending_hostname_macs = {}
  local leases_path           = cfg.leases_path or "/tmp/dhcp.leases"

  while true do
    local line = handle:read("*l")
    if not line then break end

    local flow = M.parse_conntrack_line(line)
    if flow and M.is_wan_bound(flow, lan_prefix) then
      local arp = M.parse_arp_table()
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
        exec_fn               = cfg.exec_fn,
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
