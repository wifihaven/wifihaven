-- usage.lua — nftables counter scraper and usage reporter
--
-- Per-(mac, dst_ip) byte/packet accounting comes from the dynamic set
-- `mac_ip_tracking` declared in render.lua:
--
--   set mac_ip_tracking {
--     type ether_addr . ipv4_addr
--     flags dynamic,timeout
--     counter
--     ...
--   }
--   chain familydns_account {
--     type filter hook forward priority 1; policy accept;
--     update @mac_ip_tracking { ether saddr . ip daddr } counter
--   }
--
-- Each set element carries its own counter, so we read
-- `nft -j list set inet familydns mac_ip_tracking` and walk
-- `nftables[*].set.elem[*].elem.{val.concat:[mac,ip], counter:{packets,bytes}}`.
-- After a successful POST the agent calls
-- `nft reset set inet familydns mac_ip_tracking` to zero the per-element
-- counters in place so the next bucket starts clean.
--
-- Public API:
--   usage.parse_nft_counters(json_str)
--     → list of { mac, dst_ip, bytes, packets }
--     Input: JSON from `nft -j list set inet familydns mac_ip_tracking`
--
--   usage.build_report(counters, nft_sets, period_start, period_end, router_id
--                      [, leases [, lookup_hostname [, tracker]]])
--     → report table ready for JSON encoding and POST /api/router/usage
--     nft_sets:        { hostname → { ip → true } }  (maintained by render.update_shared + dnsmasq)
--     leases:          { mac → ip }  (optional; populates the ip field on each record)
--     lookup_hostname: fn(dst_ip) → string|nil  (optional; consulted before
--                      falling back to "unknown" — same dnsmasq-query-log
--                      cache the conntrack path uses, see #287)
--     tracker:         opaque tracker from usage.new_tracker(), fed every 60 s
--                      via usage.tracker_sample() during the bucket; supplies
--                      real per-minute activeSeconds (issue #295).
--
--   usage.new_tracker() / usage.tracker_sample(t, counters) / usage.tracker_reset(t)
--     Per-minute activity sampler — see comment above the function.
--
--   usage.post(api_url, router_token, report, post_fn)
--     → bool
--     post_fn(url, body, headers) → status_code, body_str

local M = {}

-- log is injectable; default to the real logger wrapper, fall back to stderr.
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
-- usage.parse_nft_counters(json_str)
--
-- Walks the JSON output of `nft -j list set inet familydns mac_ip_tracking`
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
    if h then return { type = "fqdn", value = h } end
  end
  for hostname, ips in pairs(nft_sets or {}) do
    if ips[dst_ip] then return { type = "fqdn", value = hostname } end
  end
  local kind = (dst_ip and dst_ip:find(":", 1, true)) and "ipv6" or "ipv4"
  return { type = kind, value = dst_ip }
end

-- ---------------------------------------------------------------------------
-- Activity tracker — per-minute "is this (mac, dst_ip) actively using the
-- network?" sampling.  See #295.
-- ---------------------------------------------------------------------------
-- The nftables set element counters in `mac_ip_tracking` are reset to 0 once
-- per 5-min bucket.  The agent calls `tracker_sample` every 60 s with the
-- currently-parsed counters; each call that observes byte growth bumps the
-- (mac, dst_ip)'s active-minute count.  At end-of-bucket `build_report`
-- multiplies that count by 60 (capped at 300) to produce `activeSeconds`,
-- and the agent resets the tracker for the next bucket.
--
-- Wire format is unchanged — `activeSeconds` is still an integer.  The
-- minute granularity is purely router-side; sub-minute is a future extension
-- (issue #295 "Future work") that only needs to change SECONDS_PER_SAMPLE
-- and the sampling cadence in the agent loop.
local SECONDS_PER_SAMPLE = 60
local BUCKET_SECONDS     = 300

local function tracker_key(mac, dst_ip) return mac .. "|" .. dst_ip end

function M.new_tracker()
  return { last = {}, active_minutes = {} }
end

-- Compare each counter to its previous byte total; an increase counts as one
-- active minute.  A counter that went DOWN is treated as a fresh start
-- (element expired and reappeared, or counters reset out-of-band); we still
-- count it as an active minute if the new value is > 0.
function M.tracker_sample(tracker, counters)
  for _, c in ipairs(counters or {}) do
    local key  = tracker_key(c.mac, c.dst_ip)
    local prev = tracker.last[key] or 0
    if c.bytes > prev then
      tracker.active_minutes[key] = (tracker.active_minutes[key] or 0) + 1
      tracker.last[key] = c.bytes
    elseif c.bytes < prev then
      if c.bytes > 0 then
        tracker.active_minutes[key] = (tracker.active_minutes[key] or 0) + 1
      end
      tracker.last[key] = c.bytes
    end
  end
end

function M.tracker_reset(tracker)
  tracker.last           = {}
  tracker.active_minutes = {}
end

-- ---------------------------------------------------------------------------
-- usage.build_report(counters, nft_sets, period_start, period_end, router_id
--                    [, leases [, lookup_hostname [, tracker]]])
-- ---------------------------------------------------------------------------
-- activeSeconds:
--   * With a tracker: 60 × active_minutes[(mac, dst_ip)], capped at 300.
--     A counter present in `counters` but missing from the tracker (e.g. set
--     element first appeared after the last per-minute sample) falls back to
--     one active minute when bytes > 0.
--   * Without a tracker (legacy / back-compat): bytes > 0 → 300, else 0.
function M.build_report(counters, nft_sets, period_start, period_end, router_id, leases, lookup_hostname, tracker)
  local records = {}

  for _, c in ipairs(counters or {}) do
    local host = host_for_ip(c.dst_ip, nft_sets, lookup_hostname)
    local active_seconds
    if tracker then
      local minutes = tracker.active_minutes[tracker_key(c.mac, c.dst_ip)]
      if minutes then
        active_seconds = math.min(SECONDS_PER_SAMPLE * minutes, BUCKET_SECONDS)
      else
        active_seconds = (c.bytes > 0) and SECONDS_PER_SAMPLE or 0
      end
    else
      active_seconds = (c.bytes > 0) and BUCKET_SECONDS or 0
    end
    local rec = {
      mac           = c.mac,
      host          = host,
      activeSeconds = active_seconds,
      bytesIn       = c.bytes,
      bytesOut      = 0,   -- nftables ingress-only counters; egress tracked separately
    }
    if leases then
      rec.ip = leases[c.mac]  -- may be nil if MAC not in lease table
    end
    records[#records + 1] = rec
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
