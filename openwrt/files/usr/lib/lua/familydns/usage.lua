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
--   usage.build_report(counters, nft_sets, period_start, period_end, router_id [, leases])
--     → report table ready for JSON encoding and POST /api/router/usage
--     nft_sets: { hostname → { ip → true } }  (maintained by render.update_shared + dnsmasq)
--     leases:   { mac → ip }  (optional; populates the ip field on each record)
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
-- hostname_for_ip(dst_ip, nft_sets) → string
-- ---------------------------------------------------------------------------
local function hostname_for_ip(dst_ip, nft_sets)
  for hostname, ips in pairs(nft_sets or {}) do
    if ips[dst_ip] then return hostname end
  end
  return "unknown"
end

-- ---------------------------------------------------------------------------
-- usage.build_report(counters, nft_sets, period_start, period_end, router_id [, leases])
-- ---------------------------------------------------------------------------
-- active_seconds heuristic (§9 architecture doc):
--   Any counter with bytes > 0 in the 5-min bucket counts as the full period (300 s).
function M.build_report(counters, nft_sets, period_start, period_end, router_id, leases)
  local records = {}

  for _, c in ipairs(counters or {}) do
    local hostname = hostname_for_ip(c.dst_ip, nft_sets)
    local rec = {
      mac           = c.mac,
      hostname      = hostname,
      activeSeconds = (c.bytes > 0) and 300 or 0,
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

return M
