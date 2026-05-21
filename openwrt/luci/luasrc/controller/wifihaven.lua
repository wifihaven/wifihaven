module("luci.controller.wifihaven", package.seeall)

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
end
