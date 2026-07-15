-- ws_pending.lua — the #2229 event-driven ws-apply trigger format.
--
-- The websocket sidecar (wifihaven-ws) writes encode(etag, uptime) to
-- paths.ws_pending the instant it persists a server-pushed snapshot to
-- policy.json. The main agent reads that tiny file on EVERY on_tick (cheap — a
-- few bytes, no full-snapshot parse) and decode()s it to learn:
--   * which etag is pending — so it applies the push within one heartbeat tick
--     instead of waiting up to ws.apply_interval for the poll-of-disk gate; and
--   * the CLOCK_MONOTONIC (/proc/uptime) instant the sidecar persisted it — so
--     the agent can observe ws_push_apply_latency_seconds (persist→apply)
--     without a wall clock shared across the two processes.
--
-- Keeping the format in ONE module makes the writer (sidecar) and reader (agent)
-- share a single definition — they cannot drift. The file is a single short
-- line, overwritten (not appended) per push, so it is naturally bounded.

local M = {}

-- encode(etag, uptime) -> string
--   `uptime` may be nil (the sidecar couldn't read /proc/uptime); it then
--   round-trips to nil through decode and the agent simply skips the latency
--   observation for that apply.
function M.encode(etag, uptime)
  return tostring(etag or "") .. "\t" .. tostring(uptime or "")
end

-- decode(line) -> etag, uptime | nil
--   Returns nil when the line is absent/empty (no etag pending). `uptime` is a
--   number, or nil when the writer left it blank / it is unparseable. Tolerant
--   of a legacy bare-etag line (no tab) so an old sidecar paired with a new
--   agent still triggers (it just yields no latency stamp).
function M.decode(line)
  if type(line) ~= "string" then return nil end
  local etag, up = line:match("^(.-)\t(%S*)%s*$")
  if not etag then etag = line:match("^(.-)%s*$") end
  if not etag or etag == "" then return nil end
  return etag, tonumber(up)
end

return M
