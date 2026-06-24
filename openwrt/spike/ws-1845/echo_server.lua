-- echo_server.lua — minimal cqueues RFC 6455 echo SERVER for the spike (#1845).
--
-- Purpose: a fully-controlled loopback peer so the POC / harness can exercise
-- the complete client duplex path (connect → handshake → send → recv → ping/
-- pong → close) over real cqueues TCP/TLS sockets, without depending on a third-
-- party echo daemon's plumbing quirks. It reuses ws_frame.lua for framing, so a
-- green round-trip also cross-checks decode(masked client frame) against
-- encode(unmasked server frame).
--
-- NOT production code and NOT shipped to the router — a spike test fixture.
-- TARGET/Linux only (needs cqueues + luaossl), same as ws_client.lua.
--
--   M.listen{port=, tls=false, cert=, key=} → server object with :run() (call
--   inside a cqueues coroutine) and :stop().

local socket  = require("cqueues.socket")
local ws_frame = require("wifihaven.ws_frame")
local ws_crypto = require("wifihaven.ws_crypto")

local M = {}
local CRLF = "\r\n"

local function handshake(sock, crypto)
  local key
  while true do
    local line = sock:read("*l")
    if not line then return false end
    line = line:gsub("\r$", "")
    if line == "" then break end
    local h, v = line:match("^([^:]+):%s*(.-)%s*$")
    if h and h:lower() == "sec-websocket-key" then key = v end
  end
  if not key then return false end
  local accept = ws_frame.accept_key(key, crypto.sha1_bin, crypto.base64)
  sock:write(table.concat({
    "HTTP/1.1 101 Switching Protocols",
    "Upgrade: websocket",
    "Connection: Upgrade",
    "Sec-WebSocket-Accept: " .. accept,
    "", "",
  }, CRLF))
  sock:flush()
  return true
end

-- Serve one connection: echo every text/binary frame back (unmasked, as a
-- server must), answer ping with pong, honour close.
local DEBUG = os.getenv("WS_ECHO_DEBUG")
local function dbg(...) if DEBUG then io.write("[echo] ", table.concat({...}, " "), "\n"); io.flush() end end

local function serve(sock, crypto)
  sock:setmode("b", "b")
  if not handshake(sock, crypto) then sock:close(); return end
  dbg("handshake done, entering data loop")
  local buf = ""
  while true do
    local frame, consumed = ws_frame.decode(buf)
    if frame then
      dbg("decoded opcode", tostring(frame.opcode), "payload", tostring(frame.payload))
      buf = buf:sub(consumed + 1)
      if frame.opcode == ws_frame.OP_CLOSE then
        sock:write(ws_frame.encode(ws_frame.OP_CLOSE, "")); sock:flush(); break
      elseif frame.opcode == ws_frame.OP_PING then
        sock:write(ws_frame.encode(ws_frame.OP_PONG, frame.payload)); sock:flush()
      elseif frame.opcode == ws_frame.OP_PONG then
        -- ignore
      else
        local out = ws_frame.encode(frame.opcode, frame.payload)
        local ok, werr = sock:write(out)
        sock:flush()
        dbg("echoed", #out, "bytes, write ok=", tostring(ok), "err=", tostring(werr))
      end
    elseif frame == false then
      break
    else
      sock:settimeout(30)
      local chunk, rerr = sock:read(-4096)
      if not chunk then dbg("read ended:", tostring(rerr)); break end
      dbg("read +" .. #chunk .. " bytes")
      buf = buf .. chunk
    end
  end
  sock:close()
end

function M.listen(opts)
  local crypto = ws_crypto.luaossl()
  local srv = socket.listen("127.0.0.1", opts.port)
  local self = { srv = srv, running = true }
  function self:run()
    while self.running do
      local sock = srv:accept(0.5)
      if sock then
        if opts.tls then
          local context = require("openssl.ssl.context")
          local ctx = context.new("TLS", true) -- server context
          ctx:setCertificate(opts.cert)
          ctx:setPrivateKey(opts.key)
          pcall(function() sock:starttls(ctx) end)
        end
        serve(sock, crypto)
      end
    end
    srv:close()
  end
  function self:stop() self.running = false end
  return self
end

return M
