-- usage.lua — nftables counter scraper and usage reporter
--
-- Per-flow byte/packet accounting comes from two dynamic sets declared in
-- render.lua, each updated from its own direction-scoped chain (#897):
--
--   set mac_ip_tracking {                   -- tx: device → remote
--     type ether_addr . ipv4_addr
--     flags dynamic,timeout
--     counter
--   }
--   set ip_pair_tracking_rx {               -- rx: remote → device
--     type ipv4_addr . ipv4_addr            -- (lan_device_ip, remote_ip)
--     flags dynamic,timeout
--     counter
--   }
--   chain wifihaven_account_tx {
--     type filter hook forward priority 1; policy accept;
--     iifname "br-lan" update @mac_ip_tracking { ether saddr . ip daddr } counter
--   }
--   chain wifihaven_account_rx {
--     type filter hook forward priority 1; policy accept;
--     oifname "br-lan" update @ip_pair_tracking_rx { ip daddr . ip saddr } counter
--   }
--
-- The rx set is intentionally NOT keyed on `ether daddr`: at the forward
-- hook the L2 daddr of a WAN→LAN routed packet is still the router's
-- WAN-interface MAC (the rewrite to the LAN device MAC happens at egress
-- via the neighbor cache). Keying on it attributed every download byte to
-- the router itself (#879). Instead the rx set records per-(lan_ip, remote_ip)
-- totals and the agent resolves the lan_ip back to a MAC via the dnsmasq
-- lease table; rx records for a lan_ip not in the lease table are dropped.
--
-- Public API:
--   usage.parse_nft_counters(json_str)
--     → list of { mac, dst_ip, bytes, packets }
--     Input: JSON from `nft -j list set inet wifihaven mac_ip_tracking`
--
--   usage.parse_nft_rx_counters(json_str)
--     → list of { lan_ip, remote_ip, bytes, packets }
--     Input: JSON from `nft -j list set inet wifihaven ip_pair_tracking_rx`
--
--   usage.merge_counters(tx, rx, ip_to_mac, log)
--     → list of { mac, dst_ip, bytes, packets, bytes_out, packets_out }
--     `tx` (device→remote) carries `{mac, dst_ip, bytes, packets}` and goes
--     into bytes/packets. `rx` (remote→device) carries
--     `{lan_ip, remote_ip, bytes, packets}` and is resolved to a MAC via
--     `ip_to_mac[lan_ip]` (the dnsmasq lease table); unresolved lan_ips are
--     dropped with a log line. Resolved rx contributions are attributed to
--     `(mac, remote_ip)` — the same shape as the tx side, so a single bucket
--     row carries both directions against the real remote host. On the wire
--     (#905) rx populates bytesIn and tx populates bytesOut; internally the
--     record's `bytes` (tx) and `bytes_out` (rx) field names refer to which
--     nft set the counter came from, not the wire direction.
--
--   usage.build_report(counters, nft_sets, period_start, period_end, router_id,
--                      leases, lookup_hostname, tracker,
--                      sample_seconds, bucket_seconds)
--     → report table ready for JSON encoding and POST /api/router/usage
--     nft_sets:        { hostname → { ip → true } }  (maintained by render.update_shared + dnsmasq)
--     leases:          { mac → ip }  (optional; populates the ip field on each record)
--     lookup_hostname: fn(dst_ip) → string|nil  (optional; consulted before
--                      falling back to "unknown" — same dnsmasq-query-log
--                      cache the conntrack path uses, see #287)
--     tracker:         opaque tracker from usage.new_tracker(), fed every
--                      sample_seconds via usage.tracker_sample() during the
--                      bucket; supplies real activeSeconds (issue #295).
--     sample_seconds:  cadence at which the agent feeds the tracker.
--     bucket_seconds:  length of the usage reporting window; activeSeconds
--                      is capped at this.
--
--   usage.new_tracker() / usage.tracker_sample(t, counters) / usage.tracker_reset(t)
--     Per-minute activity sampler — see comment above the function.
--
--   usage.post(api_url, router_token, report, post_fn)
--     → bool
--     post_fn(url, body, headers) → status_code, body_str

local host_norm = require("wifihaven.host_norm")

local M = {}

-- log is injectable; default to the real logger wrapper, fall back to stderr.
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
-- usage.parse_nft_counters(json_str)
--
-- Walks the JSON output of `nft -j list set inet wifihaven mac_ip_tracking`
-- and returns one record per set element with a non-zero counter.
--
-- Shape (nftables 1.x):
--   { "nftables": [
--       { "metainfo": {...} },
--       { "set": { "name": "mac_ip_tracking",
--                  "elem": [
--                    { "elem": { "val": { "concat": [<mac>, <ip>] },
--                                "counter": { "packets": N, "bytes": M } } },
--                    ...
--                  ] } }
--   ] }
-- ---------------------------------------------------------------------------
function M.parse_nft_counters(json_str)
  local jsonc   = require("luci.jsonc")
  local decoded = jsonc.parse(json_str)
  local result  = {}

  if not decoded then return result end

  for _, entry in ipairs(decoded.nftables or {}) do
    local set = entry.set
    if set and set.elem then
      for _, wrapper in ipairs(set.elem) do
        -- `nft -j` wraps each element as { elem = { val = ..., counter = ... } }
        local e = wrapper.elem or wrapper
        local val = e.val
        local concat = val and val.concat
        local counter = e.counter
        if concat and counter and concat[1] and concat[2] then
          result[#result + 1] = {
            mac     = concat[1],
            dst_ip  = concat[2],
            bytes   = counter.bytes   or 0,
            packets = counter.packets or 0,
          }
        end
      end
    end
  end

  return result
end

-- ---------------------------------------------------------------------------
-- usage.parse_nft_rx_counters(json_str)
--
-- Walks the JSON output of `nft -j list set inet wifihaven
-- ip_pair_tracking_rx` (#897: composite-keyed set, `type ipv4_addr .
-- ipv4_addr`). Each set element's `val` is a `concat` array of
-- [lan_device_ip, remote_ip].
-- ---------------------------------------------------------------------------
function M.parse_nft_rx_counters(json_str)
  local jsonc   = require("luci.jsonc")
  local decoded = jsonc.parse(json_str)
  local result  = {}

  if not decoded then return result end

  for _, entry in ipairs(decoded.nftables or {}) do
    local set = entry.set
    if set and set.elem then
      for _, wrapper in ipairs(set.elem) do
        local e = wrapper.elem or wrapper
        local val = e.val
        local concat = val and val.concat
        local counter = e.counter
        if concat and counter and concat[1] and concat[2] then
          result[#result + 1] = {
            lan_ip    = concat[1],
            remote_ip = concat[2],
            bytes     = counter.bytes   or 0,
            packets   = counter.packets or 0,
          }
        end
      end
    end
  end

  return result
end

-- ---------------------------------------------------------------------------
-- usage.merge_counters(tx, rx, ip_to_mac, log)
--
-- Joins tx (per (mac, remote_ip)) with rx (per (lan_ip, remote_ip), with
-- lan_ip resolved to mac via `ip_to_mac`). Records are keyed (mac, dst_ip)
-- where `dst_ip` is always the *remote* IP — same shape on both directions
-- so a single record carries the full bytesIn/bytesOut for one (device,
-- remote) flow.
--
--   * tx records add `bytes`/`packets` at (c.mac, c.dst_ip).
--   * rx records add `bytes_out`/`packets_out` at (resolved_mac, c.remote_ip).
--   * rx records whose `lan_ip` is not in `ip_to_mac` are dropped. We log a
--     summary count per bucket so unattributed download bytes are visible
--     in `logread` without spamming a line per packet.
-- ---------------------------------------------------------------------------
function M.merge_counters(tx, rx, ip_to_mac, log)
  local index = {}
  local result = {}
  local function key(mac, dst_ip) return mac .. "|" .. dst_ip end
  local function get_or_make(mac, dst_ip)
    local k = key(mac, dst_ip)
    local rec = index[k]
    if not rec then
      rec = {
        mac         = mac,
        dst_ip      = dst_ip,
        bytes       = 0,
        packets     = 0,
        bytes_out   = 0,
        packets_out = 0,
      }
      index[k] = rec
      result[#result + 1] = rec
    end
    return rec
  end
  for _, c in ipairs(tx or {}) do
    local rec = get_or_make(c.mac, c.dst_ip)
    rec.bytes   = rec.bytes   + (c.bytes   or 0)
    rec.packets = rec.packets + (c.packets or 0)
  end
  local unknown_n     = 0
  local unknown_bytes = 0
  for _, c in ipairs(rx or {}) do
    local mac = ip_to_mac and ip_to_mac[c.lan_ip] or nil
    if mac then
      local rec = get_or_make(mac, c.remote_ip)
      rec.bytes_out   = rec.bytes_out   + (c.bytes   or 0)
      rec.packets_out = rec.packets_out + (c.packets or 0)
    else
      unknown_n     = unknown_n     + 1
      unknown_bytes = unknown_bytes + (c.bytes or 0)
    end
  end
  if unknown_n > 0 and log and log.debug then
    log.debug("usage.merge_counters: dropped %d rx record(s) with unresolved lan_ip (bytes=%d)",
              unknown_n, unknown_bytes)
  end
  return result
end

-- ---------------------------------------------------------------------------
-- host_for_ip(dst_ip, nft_sets, lookup_hostname) → { type, value }
-- ---------------------------------------------------------------------------
-- Returns a HostId-shaped table (#391) identifying the destination of this
-- flow. Resolution order matches conntrack.handle_flow (#287):
--   1. dnsmasq-query-log cache (lookup_hostname) — covers any LAN client that
--      resolved through the router's dnsmasq. Result is an FQDN.
--   2. nft_sets — only populated for site_limits ipsets, but authoritative
--      when present (it's the hostname dnsmasq's ipset= callback recorded at
--      resolve time). Result is an FQDN.
--   3. dst_ip literal — DoH / Apple Private Relay / direct-IP traffic where
--      no DNS attribution exists. Tagged ipv4 or ipv6 based on the address
--      form, so site-limit pattern matching (FQDN-only) cleanly skips these.
local function host_for_ip(dst_ip, nft_sets, lookup_hostname)
  if lookup_hostname then
    local h = lookup_hostname(dst_ip)
    -- #1761: strip a trailing :<port> — see host_norm.lua.
    if h then return { type = "fqdn", value = host_norm.strip_port_suffix(h) } end
  end
  for hostname, ips in pairs(nft_sets or {}) do
    if ips[dst_ip] then
      return { type = "fqdn", value = host_norm.strip_port_suffix(hostname) }
    end
  end
  local kind = (dst_ip and dst_ip:find(":", 1, true)) and "ipv6" or "ipv4"
  return { type = kind, value = dst_ip }
end

-- ---------------------------------------------------------------------------
-- Activity tracker — sub-minute "is this (mac, dst_ip) actively using the
-- network?" sampling.  See #295 (#516).
-- ---------------------------------------------------------------------------
-- The nftables set element counters in `mac_ip_tracking` are reset to 0 once
-- per bucket.  The agent calls `tracker_sample` every `sample_seconds` with
-- the currently-parsed counters; each call that observes byte growth bumps
-- the (mac, dst_ip)'s active-sample count.  At end-of-bucket `build_report`
-- multiplies that count by `sample_seconds` (capped at `bucket_seconds`) to
-- produce `activeSeconds`, and the agent resets the tracker for the next
-- bucket.
--
-- Wire format is unchanged — `activeSeconds` is still an integer.

local function tracker_key(mac, dst_ip) return mac .. "|" .. dst_ip end

function M.new_tracker()
  return { last = {}, active_samples = {} }
end

-- Compare each counter to its previous byte total; an increase counts as one
-- active sample.  A counter that went DOWN is treated as a fresh start
-- (element expired and reappeared, or counters reset out-of-band); we still
-- count it as an active sample if the new value is > 0.
function M.tracker_sample(tracker, counters)
  for _, c in ipairs(counters or {}) do
    local key   = tracker_key(c.mac, c.dst_ip)
    local prev  = tracker.last[key] or 0
    -- Counter total spans both directions (#717) — growth in either bytes
    -- (device→remote) or bytes_out (remote→device) marks an active sample.
    local total = (c.bytes or 0) + (c.bytes_out or 0)
    if total > prev then
      tracker.active_samples[key] = (tracker.active_samples[key] or 0) + 1
      tracker.last[key] = total
    elseif total < prev then
      if total > 0 then
        tracker.active_samples[key] = (tracker.active_samples[key] or 0) + 1
      end
      tracker.last[key] = total
    end
  end
end

function M.tracker_reset(tracker)
  tracker.last           = {}
  tracker.active_samples = {}
end

-- ---------------------------------------------------------------------------
-- usage.build_report(counters, nft_sets, period_start, period_end, router_id,
--                    leases, lookup_hostname, tracker,
--                    sample_seconds, bucket_seconds)
-- ---------------------------------------------------------------------------
-- activeSeconds: sample_seconds × active_samples[(mac, dst_ip)], capped at
-- bucket_seconds.  A counter present in `counters` but missing from the
-- tracker (e.g. set element first appeared after the last sample) falls back
-- to one active sample when bytes > 0.
function M.build_report(counters, nft_sets, period_start, period_end, router_id,
                        leases, lookup_hostname, tracker,
                        sample_seconds, bucket_seconds)
  local records = {}

  for _, c in ipairs(counters or {}) do
    local host    = host_for_ip(c.dst_ip, nft_sets, lookup_hostname)
    local samples = tracker.active_samples[tracker_key(c.mac, c.dst_ip)] or 0
    local active_seconds
    if samples > 0 then
      active_seconds = math.min(sample_seconds * samples, bucket_seconds)
    elseif (c.bytes or 0) + (c.bytes_out or 0) > 0 then
      -- Set element first appeared after the final sample tick: credit one sample.
      active_seconds = sample_seconds
    else
      active_seconds = 0
    end
    -- mac_ip_tracking set elements live for 6h after their last packet (#1032);
    -- skip flows with no traffic this bucket rather than emit 0/0/0 records
    -- that inflated bucket bodies past the zio-http cap in #1017. The
    -- bytes>0/no-sample branch above still emits because active_seconds > 0.
    if active_seconds > 0 or (c.bytes or 0) > 0 or (c.bytes_out or 0) > 0 then
      local rec = {
        mac           = c.mac,
        host          = host,
        activeSeconds = active_seconds,
        -- Wire semantics (#905): bytesIn is from the device's POV — traffic
        -- arriving from the internet (rx counter). bytesOut is traffic leaving
        -- the device toward the internet (tx counter). The internal record
        -- fields here are named for their nft-set source (bytes = tx,
        -- bytes_out = rx) and the swap happens only at the wire boundary.
        bytesIn       = c.bytes_out or 0,
        bytesOut      = c.bytes     or 0,
        -- #730: destination IP these bytes were attributed to. Our accumulator
        -- is already keyed (mac, dst_ip), so this is a direct pass-through.
        -- The API persists it on traffic_reports.dest_ip and a follow-up uses
        -- it for write-time FQDN backfill against connection_events.
        destIp        = c.dst_ip,
      }
      if leases then
        rec.ip = leases[c.mac]  -- may be nil if MAC not in lease table
      end
      records[#records + 1] = rec
    end
  end

  return {
    routerId    = router_id,
    periodStart = period_start,
    periodEnd   = period_end,
    records     = records,
  }
end

-- ---------------------------------------------------------------------------
-- usage.post(api_url, router_token, report, post_fn)
-- ---------------------------------------------------------------------------
function M.post(api_url, router_token, report, post_fn, log)
  log = log or default_log()
  local jsonc = require("luci.jsonc")
  -- luci.jsonc encodes empty Lua tables as `{}` (object). The API requires
  -- `records` to be a JSON array, so when no usage was observed in this
  -- window, skip the POST entirely rather than send a malformed payload.
  if report.records and next(report.records) == nil then
    log.debug("usage.post: skipping (no records)")
    return true
  end
  local body = jsonc.stringify(report)
  local url  = api_url .. "/api/router/usage"
  local hdrs = {
    ["Authorization"] = "Bearer " .. router_token,
    ["Content-Type"]  = "application/json",
  }

  log.debug("usage.post: POST url=%s records=%d periodStart=%s periodEnd=%s",
            url, #report.records, tostring(report.periodStart),
            tostring(report.periodEnd))
  local status, resp_body, err = post_fn(url, body, hdrs)
  if status and status >= 200 and status < 300 then
    log.debug("usage.post: success status=%d", status)
    return true
  end
  local body_str = resp_body and tostring(resp_body) or ""
  if #body_str > 200 then body_str = body_str:sub(1, 200) .. "...(truncated)" end
  log.err("usage.post: POST failed (status=%s body=%q) err=%s",
          tostring(status), body_str, tostring(err))
  return false
end

-- ---------------------------------------------------------------------------
-- Retry queue (#309). In-memory tmpfs queue — usage is best-effort per
-- docs/resilience.md §1, so a power loss can forfeit pending buckets.
--
-- Backoff: 30, 60, 120, 240, 480, 900 seconds, capped at 900s, with ±10%
-- jitter. Drained sequentially in chronological order on each agent tick
-- so an API recovery doesn't stampede the server with one batched POST.
-- ---------------------------------------------------------------------------

local BACKOFF_BASE = 30
local BACKOFF_MAX  = 900   -- 15 min

-- next_backoff(attempts, rng_fn) → seconds
-- attempts is 1-indexed (1 = the first retry after the initial POST failed).
-- rng_fn() returns [0, 1]; nominal × (0.9 + 0.2 * rng_fn()).
local function next_backoff(attempts, rng_fn)
  local nominal = BACKOFF_BASE * (2 ^ (attempts - 1))
  if nominal > BACKOFF_MAX then nominal = BACKOFF_MAX end
  local j = (rng_fn or math.random)()
  local scale = 0.9 + 0.2 * j
  return math.floor(nominal * scale + 0.5)
end

function M.new_queue()
  return { buckets = {} }
end

local function enqueue_failure(queue, report, now, rng_fn, attempts)
  attempts = attempts or 1
  queue.buckets[#queue.buckets + 1] = {
    report           = report,
    attempts         = attempts,
    next_attempt_at  = now + next_backoff(attempts, rng_fn),
  }
end

-- post_with_retry(api_url, token, report, post_fn, queue, now, rng_fn, log)
-- → bool (true on immediate success; false if enqueued for retry).
function M.post_with_retry(api_url, token, report, post_fn, queue, now, rng_fn, log)
  log = log or default_log()
  if report.records and next(report.records) == nil then
    log.debug("usage.post_with_retry: skipping (no records)")
    return true
  end
  if M.post(api_url, token, report, post_fn, log) then
    return true
  end
  enqueue_failure(queue, report, now, rng_fn, 1)
  log.warn("usage.post_with_retry: enqueued bucket periodEnd=%s for retry",
           tostring(report.periodEnd))
  return false
end

local function periodEnd_lt(a, b)
  -- ISO-8601 lexical compare works for "%Y-%m-%dT%H:%M:%SZ".
  return tostring(a.report.periodEnd) < tostring(b.report.periodEnd)
end

-- drain(api_url, token, queue, post_fn, now, rng_fn, log)
-- Walks buckets in chronological order of periodEnd. For each bucket whose
-- next_attempt_at ≤ now, posts it. On success removes it and proceeds; on
-- failure reschedules with the next backoff and stops draining (so the
-- failing window's backoff is honored before later buckets are tried — this
-- also avoids hammering the API once it starts rejecting again).
function M.drain(api_url, token, queue, post_fn, now, rng_fn, log)
  log = log or default_log()
  table.sort(queue.buckets, periodEnd_lt)
  local i = 1
  while i <= #queue.buckets do
    local b = queue.buckets[i]
    if b.next_attempt_at > now then
      i = i + 1
    else
      if M.post(api_url, token, b.report, post_fn, log) then
        table.remove(queue.buckets, i)
      else
        b.attempts        = b.attempts + 1
        b.next_attempt_at = now + next_backoff(b.attempts, rng_fn)
        log.warn("usage.drain: bucket periodEnd=%s failed attempt=%d; next in %ds",
                 tostring(b.report.periodEnd), b.attempts, b.next_attempt_at - now)
        return
      end
    end
  end
end

return M
