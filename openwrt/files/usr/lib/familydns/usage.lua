-- usage.lua — nftables counter scraper and usage reporter
--
-- Counter naming convention (must match render.lua + conntrack.lua):
--   ct_<mac_underscored>__<dst_ip_underscored>
--   e.g.  aa:bb:cc:11:22:33 → dst 1.2.3.4  →  ct_aa_bb_cc_11_22_33__1_2_3_4
--   ':'  and '.'  →  '_'
--   MAC and IP separated by double-underscore '__' to avoid ambiguity.
--
-- Public API:
--   usage.parse_nft_counters(json_str)
--     → list of { mac, dst_ip, bytes, packets }
--     Input: JSON from `nft -j list counters table inet familydns`
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

-- ---------------------------------------------------------------------------
-- decode_counter_name(name) → mac, dst_ip  or  nil
-- ---------------------------------------------------------------------------
local function decode_counter_name(name)
  if name:sub(1, 3) ~= "ct_" then return nil end
  local body = name:sub(4)

  -- split on the first "__" to separate MAC-encoded from IP-encoded
  local mac_enc, ip_enc = body:match("^(.-)__(.+)$")
  if not mac_enc then return nil end

  -- MAC: 6 hex octets separated by '_'
  local mac_parts = {}
  for p in mac_enc:gmatch("[^_]+") do mac_parts[#mac_parts + 1] = p end
  if #mac_parts ~= 6 then return nil end

  -- IP: 4 decimal octets separated by '_'
  local ip_parts = {}
  for p in ip_enc:gmatch("[^_]+") do ip_parts[#ip_parts + 1] = p end
  if #ip_parts ~= 4 then return nil end

  return table.concat(mac_parts, ":"), table.concat(ip_parts, ".")
end

-- ---------------------------------------------------------------------------
-- usage.parse_nft_counters(json_str)
-- ---------------------------------------------------------------------------
function M.parse_nft_counters(json_str)
  local json     = require("cjson")
  local decoded  = json.decode(json_str)
  local result   = {}

  for _, entry in ipairs(decoded.nftables or {}) do
    local c = entry.counter
    if c and type(c.name) == "string" then
      local mac, dst_ip = decode_counter_name(c.name)
      if mac then
        result[#result + 1] = {
          mac     = mac,
          dst_ip  = dst_ip,
          bytes   = c.bytes   or 0,
          packets = c.packets or 0,
        }
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
      mac            = c.mac,
      hostname       = hostname,
      active_seconds = (c.bytes > 0) and 300 or 0,
      bytes_in       = c.bytes,
      bytes_out      = 0,   -- nftables ingress-only counters; egress tracked separately
    }
    if leases then
      rec.ip = leases[c.mac]  -- may be nil if MAC not in lease table
    end
    records[#records + 1] = rec
  end

  return {
    router_id    = router_id,
    period_start = period_start,
    period_end   = period_end,
    records      = records,
  }
end

-- ---------------------------------------------------------------------------
-- usage.post(api_url, router_token, report, post_fn)
-- ---------------------------------------------------------------------------
function M.post(api_url, router_token, report, post_fn)
  local json = require("cjson")
  local body = json.encode(report)
  local url  = api_url .. "/api/router/usage"
  local hdrs = {
    ["Authorization"] = "Bearer " .. router_token,
    ["Content-Type"]  = "application/json",
  }

  local status, _ = post_fn(url, body, hdrs)
  if status and status >= 200 and status < 300 then
    return true
  end
  io.stderr:write(string.format(
    "[familydns] usage.post: POST failed, status=%s\n", tostring(status)))
  return false
end

return M
