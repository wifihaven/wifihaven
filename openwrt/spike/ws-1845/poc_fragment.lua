#!/usr/bin/env lua
-- poc_fragment.lua — on-target proof for the #1959 ws frame reassembly fix.
-- TARGET/Linux only (needs cqueues + openssl), same as poc_echo.lua.
--
-- Sends a LARGE (default 12 KiB) text payload to a fragmenting echo server
-- (run_echo_server.lua with WS_ECHO_FRAGMENT set). The server echoes it back
-- as a TEXT(FIN=0) + CONTINUATION(0x0) sequence with a PING and an unsolicited
-- PONG interleaved between fragments. The REAL ws_client:recv must reassemble
-- those fragments — answering the interleaved ping, and deferring the
-- interleaved pong (#2731), without corrupting the message — into the
-- exact bytes we sent. A byte-for-byte match proves the §5.4 reassembly works
-- on the real Lua-5.1 + cqueues stack, which the pure busted spec
-- (ws_frame_spec.lua) can't exercise. Before #1959 the client returned only
-- the first ~chunk and this FAILS the length/equality check.
--
--   lua poc_fragment.lua <uri> [payload_bytes]
-- Exit 0 = reassembled payload == sent payload. Non-zero = mismatch/failure.

local SCRIPT_DIR = (arg and arg[0] and arg[0]:match("^(.*/)") or "./")
package.path = SCRIPT_DIR .. "?.lua;"
  .. SCRIPT_DIR .. "../../files/usr/lib/lua/?.lua;" .. package.path

local cqueues = require("cqueues")
local ws_client = require("wifihaven.ws_client")

local uri = arg[1] or "ws://127.0.0.1:8800"
local N = tonumber(arg[2] or "12288")        -- 12 KiB > the ~4 KiB edge boundary

local function log(...) io.write("[frag] ", table.concat({ ... }, " "), "\n"); io.flush() end

-- A non-uniform payload so a wrong offset/truncation can't accidentally match:
-- a repeating 0..9 ramp, distinct per position modulo 10.
local function make_payload(n)
  local t = {}
  for i = 1, n do t[i] = string.char(48 + ((i - 1) % 10)) end
  return table.concat(t)
end

local loop = cqueues.new()
local ok_overall = false

loop:wrap(function()
  local c, err = ws_client.connect(uri,
    { connect_timeout = 10, insecure = (os.getenv("WS_INSECURE") ~= nil) })
  if not c then log("connect failed:", tostring(err)); return end
  log("connected + handshake OK")

  local sent = make_payload(N)
  assert(c:send_text(sent))
  log("sent", tostring(#sent), "byte payload")

  -- recv() must internally reassemble the fragmented echo + answer the
  -- interleaved ping + pong. A single recv() returns the WHOLE reassembled
  -- message: neither control frame may cut it short.
  local op, payload = c:recv(15)
  if not op then
    log("recv failed:", tostring(payload))
  elseif #payload ~= #sent then
    log("LENGTH MISMATCH: got", tostring(#payload), "want", tostring(#sent),
      "(truncated — reassembly broken)")
  elseif payload ~= sent then
    log("CONTENT MISMATCH: same length but bytes differ")
  else
    log("reassembled", tostring(#payload), "bytes, exact match")
    ok_overall = true
  end
  c:close()
end)

local ok, lerr = loop:loop()
if not ok then log("event loop error:", tostring(lerr)) end

if ok_overall then
  log("RESULT: PASS — fragmented message reassembled intact (#1959)")
  os.exit(0)
else
  log("RESULT: FAIL — see log above")
  os.exit(1)
end
