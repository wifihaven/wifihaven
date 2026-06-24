-- ws_usage_split.lua — split an oversized UsageReport across `usage` frames (#1848).
--
-- Design §5.3 + the #1017 fix: every websocket frame is bounded by
-- ws_max_frame_bytes. A UsageReport whose encoded body exceeds the cap is the
-- exact failure #1017 hit over HTTP (one over-cap POST got rejected, losing the
-- whole window). Over the socket we instead split the report's `records` array
-- into N smaller UsageReports — each carrying the SAME routerId/periodStart/
-- periodEnd envelope, so every chunk is a standalone, independently-idempotent
-- report (the server dedups on (routerId, periodStart, mac, host), so a record
-- can never double-count no matter how the window is sliced).
--
-- Pure over an injected `encode_fn(report) → string` (the production wire is
-- luci.jsonc.stringify; tests inject a deterministic sizer), so it runs the same
-- under OpenWrt Lua 5.1 and the dev host.

local M = {}

-- shallow-copy the report envelope with a fresh records array.
local function with_records(report, records)
  local out = { records = records }
  for k, v in pairs(report) do
    if k ~= "records" then out[k] = v end
  end
  return out
end

-- split(report, max_bytes, encode_fn) → { report_chunk, … }
--
-- Greedily packs records into a chunk until adding the next one would exceed
-- max_bytes, then starts a new chunk. A single record that alone exceeds the cap
-- is emitted as its own solo chunk — we never drop usage data; a frame slightly
-- over the cap is preferable to losing the record (and the server accepts it; the
-- cap is the agent's self-imposed split point, not a hard server limit). An empty
-- report yields an empty list (nothing to send).
function M.split(report, max_bytes, encode_fn)
  local records = report.records or {}
  local chunks = {}
  if #records == 0 then return chunks end

  local current = {}
  local function flush()
    if #current > 0 then
      chunks[#chunks + 1] = with_records(report, current)
      current = {}
    end
  end

  for _, rec in ipairs(records) do
    local candidate = {}
    for i = 1, #current do candidate[i] = current[i] end
    candidate[#candidate + 1] = rec
    if #encode_fn(with_records(report, candidate)) <= max_bytes then
      -- candidate still fits — keep accumulating.
      current = candidate
    else
      -- candidate would overflow. Flush what we have, then start a new chunk
      -- with just this record (which becomes its own solo chunk if it alone
      -- exceeds the cap — the next record will overflow it and flush it).
      flush()
      current = { rec }
    end
  end
  flush()
  return chunks
end

return M
