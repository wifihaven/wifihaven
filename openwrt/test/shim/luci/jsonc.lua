-- Test-only shim: on OpenWrt, the agent uses luci-lib-jsonc (luci.jsonc).
-- That C module isn't on luarocks, so for local/CI tests we provide a thin
-- compatibility layer over lua-cjson with the two methods we actually call.
local cjson = require("cjson")

local M = {}

function M.parse(s)
  local ok, val = pcall(cjson.decode, s)
  if not ok then return nil end
  return val
end

function M.stringify(v)
  return cjson.encode(v)
end

return M
