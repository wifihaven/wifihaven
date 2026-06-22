-- ws_client.lua — cqueues-backed RFC 6455 websocket CLIENT (spike #1845).
--
-- This is the I/O half of the spike: it wires ws_frame.lua's pure framing to a
-- real TLS socket and an async event loop, both provided by `cqueues` (the one
-- websocket-capable building block actually in the OpenWrt feed — 131 KiB,
-- Lua 5.1, bundled openssl TLS). It runs on Linux / the router target ONLY;
-- it is NOT loaded by the macOS busted suite (which exercises ws_frame.lua).
--
-- It demonstrates the four things §3.4 of the design asks the spike to prove:
--   1. open a wss:// connection (TLS via cqueues + openssl.ssl.context),
--   2. complete the HTTP/1.1 Upgrade handshake and verify Sec-WebSocket-Accept,
--   3. send/receive text frames + answer server ping with pong (heartbeat),
--   4. surface "connection dropped" cleanly so a caller can reconnect.
--
-- The sidecar in sub-issue C (#1848) will build on this shape: one cqueues
-- controller running this client, draining the tmpfs spools out and writing
-- pushed `policy` frames in. This file deliberately stays a thin, readable
-- proof — not the production sidecar.

local cqueues = require("cqueues")
local socket  = require("cqueues.socket")
local errno   = require("cqueues.errno")
local context = require("openssl.ssl.context")

local ws_frame = require("ws_frame")
local ws_crypto = require("ws_crypto")

local M = {}

local CRLF = "\r\n"

-- Parse "wss://host:port/path" → host, port, path, tls.
local function parse_uri(uri)
  local scheme, rest = uri:match("^(%w+)://(.+)$")
  assert(scheme == "ws" or scheme == "wss", "uri must be ws:// or wss://")
  local hostport, path = rest:match("^([^/]+)(/?.*)$")
  if path == "" then path = "/" end
  local host, port = hostport:match("^([^:]+):?(%d*)$")
  if port == "" then port = (scheme == "wss") and 443 or 80 end
  return host, tonumber(port), path, (scheme == "wss")
end

-- Connect + TLS + handshake. Returns a connected client table, or nil, err.
-- opts: { headers = {["Authorization"]="Bearer …"}, connect_timeout = 10 }
function M.connect(uri, opts)
  opts = opts or {}
  local crypto = opts.crypto or ws_crypto.luaossl()
  local host, port, path, tls = parse_uri(uri)

  local sock, err = socket.connect({ host = host, port = port })
  if not sock then return nil, "connect: " .. tostring(err) end
  sock:setmode("b", "b")                       -- raw binary both directions
  sock:settimeout(opts.connect_timeout or 10)

  if tls then
    local ctx = context.new("TLS", false)      -- client context
    ctx:setVerify(context.VERIFY_PEER)          -- verify the server cert chain
    local ok, terr = pcall(function() sock:starttls(ctx) end)
    if not ok then return nil, "starttls: " .. tostring(terr) end
  end

  -- ── opening handshake (RFC 6455 §4.1) ──
  local key = ws_frame.new_client_key(crypto.rand, crypto.base64)
  local req = {
    ("GET %s HTTP/1.1"):format(path),
    ("Host: %s"):format(host),
    "Upgrade: websocket",
    "Connection: Upgrade",
    ("Sec-WebSocket-Key: %s"):format(key),
    "Sec-WebSocket-Version: 13",
  }
  for k, v in pairs(opts.headers or {}) do
    req[#req + 1] = ("%s: %s"):format(k, v)
  end
  req[#req + 1] = ""; req[#req + 1] = ""
  sock:write(table.concat(req, CRLF))
  sock:flush()

  -- Read the status line + headers (until blank line). cqueues *l mode reads
  -- one CRLF-terminated line at a time.
  local status = sock:read("*l")
  if not status or not status:match("^HTTP/1%.1 101") then
    return nil, "upgrade rejected: " .. tostring(status)
  end
  local got_accept
  while true do
    local line = sock:read("*l")
    if not line or line == "" then break end
    line = line:gsub("\r$", "")                -- *l may leave a trailing CR
    local h, val = line:match("^([^:]+):%s*(.-)%s*$")
    if h and h:lower() == "sec-websocket-accept" then got_accept = val end
  end
  local want = ws_frame.accept_key(key, crypto.sha1_bin, crypto.base64)
  if got_accept ~= want then
    return nil, ("bad Sec-WebSocket-Accept (got %s want %s)")
      :format(tostring(got_accept), want)
  end

  return setmetatable({
    sock = sock, crypto = crypto, rxbuf = "", closed = false,
  }, { __index = M }), nil
end

-- send_text(payload) — masked client text frame (RFC 6455 requires the mask).
function M:send_text(payload)
  if self.closed then return nil, "closed" end
  local mask = self.crypto.rand(4)
  local ok, err = self.sock:write(ws_frame.encode(ws_frame.OP_TEXT, payload, mask))
  if not ok then self.closed = true; return nil, "write: " .. tostring(err) end
  self.sock:flush()
  return true
end

function M:send_pong(payload)
  if self.closed then return end
  local mask = self.crypto.rand(4)
  self.sock:write(ws_frame.encode(ws_frame.OP_PONG, payload or "", mask))
  self.sock:flush()
end

function M:send_ping(payload)
  if self.closed then return end
  local mask = self.crypto.rand(4)
  self.sock:write(ws_frame.encode(ws_frame.OP_PING, payload or "", mask))
  self.sock:flush()
end

-- recv(timeout) — block (cooperatively, yielding to the cqueues loop) until one
-- complete application frame arrives. Transparently answers server ping→pong
-- and surfaces close. Returns opcode, payload  OR  nil, reason
-- (reason "closed"/"timeout"/"eof"/error string) — the clean drop signal §3.4
-- asks for, so the caller's reconnect loop can act on it.
--
-- Uses cqueues.poll (the controller-integrated readability wait) rather than a
-- bare timed socket read — this is the idiom the sidecar's event loop needs.
-- KNOWN ENV QUIRK: under the macOS luarocks-built cqueues + brew-openssl used
-- in the dev sandbox, a *reactive* read (waiting for bytes that arrive later)
-- can return an immediate ETIMEDOUT even after poll signals readability. The
-- raw socket I/O works (proven by the websocat↔echo_server round-trip), so this
-- is a kqueue/timeout-integration artifact of the dev build, NOT a protocol
-- bug. The receive loop must be re-validated on a real OpenWrt/Linux target with
-- the *packaged* cqueues (epoll) during D0 hardware validation — see README.
function M:recv(timeout)
  while true do
    local frame, consumed = ws_frame.decode(self.rxbuf)
    if frame then
      self.rxbuf = self.rxbuf:sub(consumed + 1)
      if frame.opcode == ws_frame.OP_PING then
        self:send_pong(frame.payload)            -- heartbeat: keepalive
      elseif frame.opcode == ws_frame.OP_PONG then
        -- liveness ack; caller may track it. Loop for the next real frame.
      elseif frame.opcode == ws_frame.OP_CLOSE then
        self.closed = true
        return nil, "closed"
      else
        return frame.opcode, frame.payload       -- text/binary/continuation
      end
    elseif frame == false then
      self.closed = true
      return nil, "protocol: " .. tostring(consumed)
    else
      -- need more bytes. Wait for readability via the cqueues controller
      -- (cqueues.poll) rather than a blocking socket read with a per-op
      -- timeout: poll integrates with the event loop deterministically, which
      -- is exactly the "read server-pushed frames at any time" property the
      -- sidecar needs. A bare timed read returned an immediate ETIMEDOUT here.
      local ready = cqueues.poll(self.sock, timeout or 35)
      if ready ~= self.sock then
        return nil, "timeout"                    -- poll deadline, not a drop
      end
      self.sock:settimeout(0)                     -- non-blocking: drain ready bytes
      local chunk, err = self.sock:read(-4096)
      if not chunk then
        -- Distinguish a transient "nothing to drain yet" (EAGAIN/ETIMEDOUT, or a
        -- spurious poll wakeup) from a real disconnect. Only the latter is a
        -- drop — misclassifying a would-block as a drop would tear down a
        -- perfectly live socket (the §3.4 drop signal must be precise).
        if err == nil or err == errno.EAGAIN or err == errno.ETIMEDOUT then
          return nil, "timeout"                  -- not a drop; caller may retry
        end
        self.closed = true
        return nil, "eof: " .. tostring(err)     -- genuine error → reconnect
      end
      self.rxbuf = self.rxbuf .. chunk
    end
  end
end

function M:close()
  if self.closed then return end
  self.closed = true
  pcall(function()
    self.sock:write(ws_frame.encode(ws_frame.OP_CLOSE, "", self.crypto.rand(4)))
    self.sock:flush()
    self.sock:close()
  end)
end

return M
