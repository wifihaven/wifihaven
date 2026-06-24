#!/usr/bin/env lua
-- run_echo_server.lua — standalone runner for echo_server.lua (spike #1845).
-- Used by the local round-trip harness to run the echo peer in its OWN process
-- (matching the real deployment: the ws sidecar and the API are separate
-- processes), avoiding single-event-loop scheduling artifacts. TARGET/Linux.
--   lua run_echo_server.lua <port>
local SCRIPT_DIR = (arg and arg[0] and arg[0]:match("^(.*/)") or "./")
-- The ws_frame/ws_crypto modules were promoted into the shipped agent package
-- (openwrt/files/usr/lib/lua/wifihaven/) by #1848; resolve them from there
-- (qualified `wifihaven.*` require) plus the spike dir for echo_server itself.
package.path = SCRIPT_DIR .. "?.lua;"
  .. SCRIPT_DIR .. "../../files/usr/lib/lua/?.lua;" .. package.path
local cq = require("cqueues")
local server = require("echo_server")
local port = tonumber(arg[1] or "8800")
-- Optional TLS: `lua run_echo_server.lua <port> tls <cert.pem> <key.pem>`.
local opts = { port = port }
if arg[2] == "tls" then
  local x509 = require("openssl.x509")
  local pkey = require("openssl.pkey")
  opts.tls = true
  opts.cert = x509.new(io.open(arg[3]):read("*a"))
  opts.key  = pkey.new(io.open(arg[4]):read("*a"))
end
local srv = server.listen(opts)
local loop = cq.new()
loop:wrap(function() srv:run() end)
io.write("[echo_server] listening on 127.0.0.1:" .. port .. "\n"); io.flush()
loop:loop()
