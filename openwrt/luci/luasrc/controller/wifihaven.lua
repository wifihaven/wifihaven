module("luci.controller.wifihaven", package.seeall)

local http = require "luci.http"
local sys  = require "luci.sys"

function index()
  entry({"admin", "services", "wifihaven"},
        alias("admin", "services", "wifihaven", "status"),
        _("WifiHaven"), 60).dependent = true

  entry({"admin", "services", "wifihaven", "status"},
        template("wifihaven/status"),
        _("Status"), 10).leaf = true

  entry({"admin", "services", "wifihaven", "settings"},
        cbi("wifihaven/settings"),
        _("Settings"), 20).leaf = true

  -- JSON endpoints for the version table and on-demand update (#1181).
  -- These are called via XHR from status.htm; they proxy the rpcd wifihaven
  -- plugin methods so they run under the router operator's rpcd session.
  entry({"admin", "services", "wifihaven", "versions"},
        call("action_versions"), nil, nil).leaf = true

  entry({"admin", "services", "wifihaven", "update"},
        call("action_update"), nil, nil).leaf = true
end

-- GET /admin/services/wifihaven/versions
-- Returns {"agent_installed":"...","luci_installed":"...","available":"..."}
-- by calling the wifihaven rpcd plug-in.
function action_versions()
  http.prepare_content("application/json")
  local result = luci_rpcd_call("versions", "{}")
  http.write(result or '{"error":"rpcd call failed"}')
end

-- GET /admin/services/wifihaven/update
-- Runs /usr/sbin/wifihaven-update (blocking) via the wifihaven rpcd plug-in
-- and returns {"exit_code":N,"output":"...","log":"..."}.
function action_update()
  http.prepare_content("application/json")
  local result = luci_rpcd_call("update", "{}")
  http.write(result or '{"error":"rpcd call failed"}')
end

-- Call the wifihaven rpcd plug-in at /usr/libexec/rpcd/wifihaven.
-- method: "versions" or "update"
-- params: JSON string of parameters
-- Returns the raw JSON string output, or nil on error.
function luci_rpcd_call(method, params)
  local cmd = string.format(
    '/usr/libexec/rpcd/wifihaven call %s %s 2>/dev/null',
    luci.util.shellquote(method),
    luci.util.shellquote(params)
  )
  local out = sys.exec(cmd)
  if out and out ~= "" then
    return out
  end
  return nil
end
