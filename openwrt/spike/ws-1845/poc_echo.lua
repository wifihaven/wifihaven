#!/usr/bin/env lua
-- poc_echo.lua — runnable spike entrypoint (#1845). TARGET/Linux only (needs
-- cqueues + openssl). Proves the agent ws client end-to-end against a public
-- wss echo server, demonstrating: TLS connect, handshake, hello send, receive,
-- server-ping→pong heartbeat, clean drop detection, and reconnect backoff.
--
-- Run on a Linux box or the router (NOT macOS busted):
--   opkg/apk install cqueues          # pulls liblua5.1 + libopenssl
--   lua poc_echo.lua wss://ws.postman-echo.com/raw
--   lua poc_echo.lua wss://echo.websocket.events
--
-- Exit 0 = full round-trip + reconnect proven. Non-zero = failure (logged).
--
-- This is throwaway proof code; the real sidecar (#1848) reuses ws_client.lua
-- but is driven by the spool/snapshot bridge, not this demo loop.

local SCRIPT_DIR = (arg and arg[0] and arg[0]:match("^(.*/)") or "./")
-- ws_client/ws_frame/ws_crypto were promoted into the shipped agent package
-- (openwrt/files/usr/lib/lua/wifihaven/) by #1848; resolve the qualified
-- `wifihaven.*` requires from there.
package.path = SCRIPT_DIR .. "?.lua;"
  .. SCRIPT_DIR .. "../../files/usr/lib/lua/?.lua;" .. package.path

local cqueues = require("cqueues")
local ws_client = require("wifihaven.ws_client")

local uri = arg[1] or "wss://echo.websocket.events"
local HEARTBEAT_PROOF_SECS = tonumber(arg[2] or "8")

local function log(...) io.write("[poc] ", table.concat({ ... }, " "), "\n"); io.flush() end

-- Exponential backoff + jitter, cap 60s — the §5.1 reconnect policy.
local function backoff(attempt)
  local base = math.min(60, 2 ^ math.min(attempt, 6))
  return base * (0.5 + math.random() * 0.5)
end

local loop = cqueues.new()
local ok_overall = false

loop:wrap(function()
  local attempt = 0
  while attempt < 3 and not ok_overall do
    attempt = attempt + 1
    log("connect attempt", tostring(attempt), uri)
    -- WS_INSECURE=1 skips cert verification — for self-signed loopback testing.
    local c, err = ws_client.connect(uri,
      { connect_timeout = 10, insecure = (os.getenv("WS_INSECURE") ~= nil) })
    if not c then
      log("connect failed:", tostring(err), "— backing off")
      cqueues.sleep(backoff(attempt))
    else
      log("connected + handshake OK")

      -- 1. send a hello (mirrors the design's first agent→server frame)
      assert(c:send_text('{"op":"hello","payload":{"agentVersion":"spike"}}'))
      log("sent hello")

      -- 2. receive the echo
      local op, payload = c:recv(10)
      if not op then
        log("recv failed:", tostring(payload)); c:close()
        cqueues.sleep(backoff(attempt))
      else
        log("received echo:", payload)

        -- 3. drive a client ping; the echo server returns our payload (and
        --    real servers send their own pings, which recv() answers with
        --    pong transparently). Sit in recv() across the heartbeat window
        --    to prove the connection stays warm.
        c:send_ping("hb")
        local deadline = cqueues.monotime() + HEARTBEAT_PROOF_SECS
        while cqueues.monotime() < deadline do
          local rop, rpl = c:recv(2)
          if not rop and rpl ~= "timeout" then
            log("connection dropped during heartbeat:", tostring(rpl))
            break
          elseif rop then
            log("heartbeat-window frame:", tostring(rpl))
          end
        end

        -- 4. clean close + prove drop is surfaced
        c:close()
        log("closed cleanly; drop signal works")
        ok_overall = true
      end
    end
  end
end)

local ok, err = loop:loop()
if not ok then log("event loop error:", tostring(err)) end

if ok_overall then
  log("RESULT: PASS — wss connect/handshake/send/recv/ping-pong/reconnect proven")
  os.exit(0)
else
  log("RESULT: FAIL — see log above")
  os.exit(1)
end
