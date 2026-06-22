#!/usr/bin/env lua
-- run_echo_server.lua — standalone runner for echo_server.lua (spike #1845).
-- Used by the local round-trip harness to run the echo peer in its OWN process
-- (matching the real deployment: the ws sidecar and the API are separate
-- processes), avoiding single-event-loop scheduling artifacts. TARGET/Linux.
--   lua run_echo_server.lua <port>
package.path = (arg and arg[0] and arg[0]:match("^(.*/)") or "./") .. "?.lua;" .. package.path
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
