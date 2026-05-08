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
-- build_event(opts) -> table
--
-- opts: { mac, hostname, dest_ip, allowed, reason, ts }
-- hostname nil → falls back to dest_ip.
-- reason nil → "allow" when allowed=true, "blocked" when false.
-- Returns a Lua table ready for JSON encoding.
-- ---------------------------------------------------------------------------
function M.build_event(opts)
  local hostname = opts.hostname or opts.dest_ip
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
    hostname    = hostname,
    dest_ip     = opts.dest_ip,
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
function M.new_batcher(max_size, flush_interval_sec, flush_fn)
  local buf          = {}
  local last_flush   = os.time()

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
    now = now or os.time()
    if (now - last_flush) >= flush_interval_sec then
      last_flush = now
      self.flush()
    end
  end

  return self
end

-- ---------------------------------------------------------------------------
-- post_with_retry(url, body, max_retries, base_delay, post_fn, sleep_fn) -> bool
--
-- post_fn(url, body) -> status_code, body_str
-- sleep_fn(seconds)  — injectable for tests (defaults to os.execute("sleep N"))
--
-- Retries on 5xx / connection errors with exponential back-off.
-- Does NOT retry on 4xx (client errors are not transient).
-- Returns true on success, false after max_retries are exhausted.
-- Never blocks a caller waiting longer than necessary; callers should run this
-- in a coroutine or dedicate a goroutine-equivalent (Lua co-routine) if needed.
-- ---------------------------------------------------------------------------
function M.post_with_retry(url, body, max_retries, base_delay, post_fn, sleep_fn)
  sleep_fn = sleep_fn or function(s)
    os.execute("sleep " .. tostring(math.floor(s)))
  end

  local delay = base_delay
  for attempt = 1, max_retries do
    local status, _ = post_fn(url, body)
    if status and status >= 200 and status < 300 then
      return true
    end
    if status and status >= 400 and status < 500 then
      -- Client error: no point retrying.
      return false
    end
    -- 5xx or nil (connection failure): retry with back-off.
    if attempt < max_retries then
      sleep_fn(delay)
      delay = delay * 2
    end
  end
  return false
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
--   blocked_ips    table     shared ref: { ip -> true }  (filled by render.lua)
--   blocked_reason table     shared ref: { ip -> reason_string }
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
  local json       = require("cjson")
  local lan_prefix = cfg.lan_prefix     or "192.168.1."
  local max_batch  = cfg.max_batch      or 50
  local flush_int  = cfg.flush_interval or 10
  local max_retry  = cfg.max_retries    or 3
  local base_delay = cfg.base_delay     or 2

  local events_url = cfg.api_url .. "/api/router/events"

  local function do_post(url, body)
    return cfg.http_post(url, body, {
      ["Authorization"] = "Bearer " .. cfg.router_token,
      ["Content-Type"]  = "application/json",
    })
  end

  local batcher = M.new_batcher(max_batch, flush_int, function(events)
    local payload = json.encode({
      router_id = cfg.router_id,
      events    = events,
    })
    local ok = M.post_with_retry(events_url, payload, max_retry, base_delay, do_post, cfg.sleep_fn)
    if not ok then
      -- best-effort: log and continue; never block a flow waiting for the API
      io.stderr:write("[familydns] conntrack: failed to POST events batch after retries, dropping\n")
    end
  end)

  local handle = io.popen("conntrack -E -e NEW 2>/dev/null", "r")
  if not handle then
    io.stderr:write("[familydns] conntrack: cannot start conntrack -E -e NEW\n")
    return
  end

  while true do
    local line = handle:read("*l")
    if not line then break end

    local flow = M.parse_conntrack_line(line)
    if flow and M.is_outbound(flow, lan_prefix) then
      local arp   = M.parse_arp_table()
      local mac   = M.arp_lookup_mac(flow.src_ip, arp)
      local hname = M.ipset_lookup_hostname(flow.dst_ip, cfg.nft_sets or {})

      local allowed = not (cfg.blocked_ips and cfg.blocked_ips[flow.dst_ip])
      local reason
      if not allowed and cfg.blocked_reason then
        reason = cfg.blocked_reason[flow.dst_ip]
      end

      local ev = M.build_event({
        mac      = mac,
        hostname = hname,
        dest_ip  = flow.dst_ip,
        allowed  = allowed,
        reason   = reason,
        ts       = os.date("!%Y-%m-%dT%H:%M:%SZ"),
      })
      batcher.add(ev)
    end

    batcher.tick()

    -- Drive co-operative timers (policy poll, usage report) from the main agent.
    if cfg.on_tick then cfg.on_tick() end
  end

  handle:close()
  batcher.flush()
end

return M
