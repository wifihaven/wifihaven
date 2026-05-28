-- version.lua — read the baked-in agent package version (#771).
--
-- The OpenWRT Makefile writes `PKG_VERSION` into /usr/lib/wifihaven/VERSION
-- at install time. The agent reads it once at startup and sends it on every
-- /api/router/policy checkin via the `X-WifiHaven-Agent-Version` header so
-- the API can record which version each router is running.
--
-- Returns nil if the file is missing or empty (older installs predating the
-- bake step). Callers must treat nil as "unknown" and omit the header.

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
