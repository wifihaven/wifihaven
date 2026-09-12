-- version.lua — read the baked-in agent package version (#771).
--
-- The OpenWRT Makefile writes `PKG_VERSION` into /usr/lib/wifihaven/VERSION
-- at install time. The agent reads it once at startup and reports it to the API
-- on every metrics push, as `RouterMetricsBatch.agentVersion` and as the
-- `agent_version{version=...}` info gauge.
--
-- #2736: it used to ride the `X-WifiHaven-Agent-Version` header on the
-- `GET /api/router/policy` checkin. The agent is websocket-only now and makes no
-- such request, so `RouterMetricsService` persists `routers.agent_version` from
-- the metrics batch instead — otherwise the column would freeze at whatever a
-- router was running at its last poll and the SPA would show it as current.
--
-- Returns nil if the file is missing or empty (older installs predating the
-- bake step). Callers must treat nil as "unknown"; the agent sends "" and the
-- server skips the write rather than blanking a known value.

local M = {}

local DEFAULT_PATH = "/usr/lib/wifihaven/VERSION"

function M.read(path, open_fn)
  path = path or DEFAULT_PATH
  open_fn = open_fn or io.open
  local f = open_fn(path, "r")
  if not f then return nil end
  local s = f:read("*a")
  f:close()
  if not s then return nil end
  s = s:gsub("%s+$", ""):gsub("^%s+", "")
  if s == "" then return nil end
  return s
end

return M
