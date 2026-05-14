-- conntrack.lua — conntrack new-flow watcher + event batcher for familydns-agent
--
-- Design notes:
--   * conntrack -E -e NEW (conntrack-tools package) is used rather than nftables
--     meta nftrace because it is available on stock OpenWrt 23.x without enabling
--     per-rule tracing and produces structured, line-oriented output suitable for
--     a Lua io.popen loop.
--   * Hostname attribution uses the in-memory nft_sets table that render.lua
--     populates from dnsmasq --ipset= callbacks.  We look up dest_ip against that
--     table to recover the hostname the client resolved, NOT reverse DNS.
--   * Both allowed and blocked flows are batched.  Blocking state comes from the
--     same nft_sets/policy tables that render.lua writes; we read them but never
--     modify them here.

local M = {}

local function default_log()
  local ok, l = pcall(require, "familydns.log")
  if ok then return l end
  return {
    info  = function(fmt, ...) io.stderr:write(string.format(fmt .. "\n", ...)) end,
    err   = function(fmt, ...) io.stderr:write(string.format(fmt .. "\n", ...)) end,
    warn  = function(fmt, ...) io.stderr:write(string.format(fmt .. "\n", ...)) end,
    debug = function() end,
  }
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
  return {
    ["type"]    = "connection_attempt",
    mac         = opts.mac,
    host        = host,
    destIp      = opts.dest_ip,
    allowed     = opts.allowed,
    reason      = reason,
    ts          = opts.ts,
  }
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
    local ok, clock = pcall(require, "familydns.clock")
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
  local jsonc = require("luci.jsonc")
  local i = 1
  while i <= #queue.batches do
    local b = queue.batches[i]
    if b.next_attempt_at > now then
      i = i + 1
    else
      local payload = jsonc.stringify({
        routerId = router_id,
        events   = b.events,
      })
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

-- ---------------------------------------------------------------------------
-- handle_flow(flow, ctx, batcher)
--
-- Processes a single outbound flow: emits a first_seen_mac event the first
-- time we observe a given MAC, then always emits a connection_attempt event.
--
-- ctx: {
--   arp_table      table   { ip -> mac }
--   nft_sets       table   { hostname -> { ip -> true } }
--   blocked_macs   table   { mac -> true }       (filled by render.update_shared)
--   blocked_reason table   { mac -> reason }     (filled by render.update_shared)
--   reported_macs  table   { mac -> true }  (mutated)
--   leases         table   { mac -> { ip, hostname } } (may be nil/empty)
--   ts             string  ISO8601 timestamp to attach to the events
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
  local hname
  if ctx.lookup_hostname then
    hname = ctx.lookup_hostname(flow.dst_ip)
  end
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
-- is_outbound(flow, lan_prefix) -> bool
--
-- Returns true when the source IP is on the LAN (egress flow).
-- lan_prefix example: "192.168.1."
-- ---------------------------------------------------------------------------
function M.is_outbound(flow, lan_prefix)
  return flow.src_ip:sub(1, #lan_prefix) == lan_prefix
end

-- ---------------------------------------------------------------------------
-- watch(cfg) — blocking event loop; called from the main agent daemon
--
-- cfg: {
--   router_id      string    UUID of this router
--   api_url        string    base URL, e.g. "http://192.168.1.1:8080"
--   router_token   string    bearer token
--   nft_sets       table     shared ref: { hostname -> { ip -> true } }
--   blocked_macs   table     shared ref: { mac -> true }  (filled by render.lua)
--   blocked_reason table     shared ref: { mac -> reason_string }
--   lan_prefix     string    default "192.168.1."
--   max_batch      int       default 50
--   flush_interval int       default 10  (seconds)
--   max_retries    int       default 3
--   base_delay     int       default 2   (seconds, doubles each retry)
--   http_post      function  injectable: post_fn(url, body) -> status, body
--   sleep_fn       function  injectable for tests
-- }
-- ---------------------------------------------------------------------------
function M.watch(cfg)
  local jsonc      = require("luci.jsonc")
  local log        = cfg.log            or default_log()
  local lan_prefix = cfg.lan_prefix     or "192.168.1."
  local max_batch  = cfg.max_batch      or 50
  local flush_int  = cfg.flush_interval or 10
  local max_retry  = cfg.max_retries    or 3
  local base_delay = cfg.base_delay     or 2

  local events_url = cfg.api_url .. "/api/router/events"
  local event_queue = cfg.event_queue or M.new_event_queue()

  local function do_post(url, body)
    return cfg.http_post(url, body, {
      ["Authorization"] = "Bearer " .. cfg.router_token,
      ["Content-Type"]  = "application/json",
    })
  end

  local batcher = M.new_batcher(max_batch, flush_int, function(events)
    log.debug("conntrack: flushing batch size=%d url=%s", #events, events_url)
    local payload = jsonc.stringify({
      routerId = cfg.router_id,
      events   = events,
    })
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
    if flow and M.is_outbound(flow, lan_prefix) then
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
        blocked_macs          = cfg.blocked_macs,
        blocked_reason        = cfg.blocked_reason,
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
