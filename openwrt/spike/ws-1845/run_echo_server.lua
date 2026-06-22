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
local srv = server.listen({ port = port })
local loop = cq.new()
loop:wrap(function() srv:run() end)
io.write("[echo_server] listening on 127.0.0.1:" .. port .. "\n"); io.flush()
loop:loop()
