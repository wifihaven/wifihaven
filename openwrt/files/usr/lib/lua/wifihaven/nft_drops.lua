-- nft_drops.lua — read the enforcement plane's forward-drop counters and
-- aggregate them by a bounded reason enum (#1325).
--
-- Per the architectural model, enforcement is nftables forward-drop on the
-- gateway (dnsmasq is only hostname attribution, never the enforcer). render.lua
-- emits every per-MAC drop rule in the `wifihaven_block` chain carrying a
-- `counter` statement and a `comment "wh_drop:<mac>:<reason>"` (#1122). This
-- module reads those rule counters and collapses them into the four bounded
-- drop reasons an operator alerts on, so the agent can ship
-- `enforcement_drops_total{reason}` in its 60 s metrics push (#1206).
--
-- The comment <reason> field is the router-side per-flow attribution string,
-- NOT a low-cardinality enum on its own (a whole-MAC drop carries the resolved
-- MacBlockReason — Paused / Schedule / TimeLimit / Manual / Unmanaged / blocked
-- — and a category drop carries `category:<blocklistId>`). classify_reason
-- folds those into the four bounded buckets the §4 cardinality firewall allows:
--
--   host        → host_block       (per-(MAC, host) eb_ drop, #515)
--   ip_only     → ip_only          (blockIpOnly un-resolved-daddr drop, #353)
--   category:*  → category_block   (per-(MAC, blocklistId) bl_ drop, #352)
--   <anything   → whole_mac        (whole-MAC @blocked_macs drop carrying a
--    else>                          MacBlockReason — Paused/Schedule/…/blocked)
--
-- There is deliberately NO per-mac / per-host / per-ip / per-blocklist-id
-- dimension: those are forbidden keys (§4.3). A category drop's blocklist id is
-- collapsed into the single `category_block` bucket.
--
-- Public API:
--   nft_drops.classify_reason(comment_reason) → bounded enum string
--   nft_drops.parse_drop_counters(json_str)   → { [reason_enum] = packets }
--   nft_drops.read(exec_fn?)                   → { [reason_enum] = packets }

local M = {}

-- Whole-MAC drop comment reasons: the resolved MacBlockReason the API server
-- stamped (PolicyService) plus the "blocked" fallback render.lua uses when no
-- reason was carried. Anything in here (and anything unrecognised) folds to the
-- single whole_mac bucket. host / ip_only / category:* are handled explicitly
-- in classify_reason and never reach this table.
local WHOLE_MAC_REASONS = {
  Paused    = true,
  Schedule  = true,
  TimeLimit = true,
  Manual    = true,
  Unmanaged = true,
  blocked   = true,
}

-- classify_reason(comment_reason) → bounded reason enum.
-- comment_reason is the `<reason>` portion of `wh_drop:<mac>:<reason>`.
function M.classify_reason(comment_reason)
  if type(comment_reason) ~= "string" then return "whole_mac" end
  -- #1645: post-rollout per-host drops carry `host:<host>` so the matched
  -- eb_<host> rule is named in events; the bare `host` legacy form (pre-
  -- rollout routers) still folds into the same bounded bucket. The host
  -- name itself never becomes a label — §4 cardinality firewall.
  if comment_reason == "host" or comment_reason:match("^host:") then
    return "host_block"
  elseif comment_reason == "ip_only" then
    return "ip_only"
  elseif comment_reason:match("^category:") then
    return "category_block"
  else
    -- Whole-MAC drops carry a MacBlockReason (or "blocked"); fold them — and
    -- any unrecognised future string — into the single whole_mac bucket so the
    -- label space stays bounded regardless of what reasons the server adds.
    return "whole_mac"
  end
end

-- parse_drop_counters(json_str) → { [reason_enum] = packets }
--
-- Walks `nft -j list chain inet wifihaven wifihaven_block` and sums each rule's
-- `counter` packets into its classified reason bucket. Rules without a
-- `wh_drop:` comment (e.g. the chain's `policy accept`) or without a counter
-- contribute nothing. Returns an empty table when the chain is absent (cold
-- boot before the first apply) or the JSON is unparseable — the caller folds an
-- empty sample as a no-op.
--
-- nft JSON rule shape:
--   { "rule": { "chain": "wifihaven_block",
--               "comment": "wh_drop:aa:bb:cc:dd:ee:ff:host",
--               "expr": [ ..., { "counter": { "packets": N, "bytes": M } },
--                         { "log": {...} }, { "drop": null } ] } }
function M.parse_drop_counters(json_str)
  local jsonc   = require("luci.jsonc")
  local result  = {}
  if type(json_str) ~= "string" or json_str == "" then return result end
  local decoded = jsonc.parse(json_str)
  if not decoded then return result end

  for _, entry in ipairs(decoded.nftables or {}) do
    local rule = entry.rule
    -- Only forward-drop rules we stamped carry a wh_drop: comment.
    local comment = rule and rule.comment
    if comment and type(comment) == "string" then
      -- wh_drop:<mac>:<reason>. The MAC is six hex octets joined by colons; the
      -- reason itself may contain a colon (e.g. `category:7`), so anchor on the
      -- fixed MAC shape and capture everything after it rather than splitting on
      -- the last colon (which would mis-parse `category:7` as reason `7`).
      local cmac_reason =
        comment:match("^wh_drop:%x%x:%x%x:%x%x:%x%x:%x%x:%x%x:(.+)$")
      if cmac_reason then
        -- Find this rule's counter statement (anonymous counter on the rule).
        local packets
        for _, ex in ipairs(rule.expr or {}) do
          if ex.counter and ex.counter.packets then
            packets = ex.counter.packets
            break
          end
        end
        if packets then
          local reason = M.classify_reason(cmac_reason)
          result[reason] = (result[reason] or 0) + packets
        end
      end
    end
  end

  return result
end

-- read(exec_fn?) → { [reason_enum] = packets }
--
-- Production helper: shell out to nft and parse. exec_fn(cmd) → stdout string
-- is injectable for tests; defaults to a popen read. A missing chain (nft
-- prints nothing / an error to stderr which we discard) yields {} via
-- parse_drop_counters.
function M.read(exec_fn)
  exec_fn = exec_fn or function(cmd)
    local p = io.popen(cmd .. " 2>/dev/null", "r")
    if not p then return "" end
    local out = p:read("*a") or ""
    p:close()
    return out
  end
  local json = exec_fn("nft -j list chain inet wifihaven wifihaven_block")
  return M.parse_drop_counters(json)
end

return M
