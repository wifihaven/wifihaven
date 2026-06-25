-- ws_client.lua — cqueues-backed RFC 6455 websocket CLIENT.
--
-- Proven on the real Lua 5.1 + packaged-cqueues/luaossl target by the #1845
-- spike; promoted from spike/ws-1845/ into the shipped agent package by #1848,
-- where the wifihaven-ws sidecar (ws_loop.lua) drives it. It wires
-- ws_frame.lua's pure framing to a real TLS socket and an async event loop,
-- both provided by `cqueues` (the one websocket-capable building block actually
-- in the OpenWrt feed — 131 KiB, Lua 5.1, bundled openssl TLS). It runs on
-- Linux / the router target ONLY (it `require`s cqueues + luaossl at load time),
-- so the macOS busted suite never loads it — ws_loop_spec.lua exercises the
-- sidecar's orchestration against a FAKE client instead, and the live cqueues
-- round-trip is validated on plex.lan (Lua 5.1) via spike/ws-1845/e2e_test.sh.
--
-- It provides the four things the transport needs:
--   1. open a wss:// connection (TLS via cqueues + openssl.ssl.context),
--   2. complete the HTTP/1.1 Upgrade handshake and verify Sec-WebSocket-Accept,
--   3. send/receive text frames + answer server ping with pong (heartbeat),
--   4. surface "connection dropped" cleanly so a caller can reconnect.

local socket  = require("cqueues.socket")
local errno   = require("cqueues.errno")
local context = require("openssl.ssl.context")

local ws_frame = require("wifihaven.ws_frame")
local ws_crypto = require("wifihaven.ws_crypto")

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
  -- Make every socket I/O RETURN `nil, errno` instead of throwing. cqueues'
  -- default onerror throws for non-timeout errors, but on Lua 5.1 (OpenWrt) we
  -- cannot `pcall` a cqueues call that yields (starttls/read/write all yield to
  -- the controller) — "attempt to yield across a C-call boundary". A returning
  -- onerror lets us check return values everywhere with zero pcall. (MUST be
  -- set before starttls.)
  sock:onerror(function(_, _, why) return why end)
  sock:settimeout(opts.connect_timeout or 10)

  if tls then
    local ctx = context.new("TLS", false)      -- client context
    -- Verify the server cert chain by default. opts.insecure disables it — for
    -- self-signed loopback testing ONLY; production keeps VERIFY_PEER.
    ctx:setVerify(opts.insecure and context.VERIFY_NONE or context.VERIFY_PEER)
    -- Call starttls DIRECTLY (no pcall — it yields during the TLS handshake);
    -- returns the socket on success, nil+errno on failure (no throw, per the
    -- returning onerror above).
    local ok, terr = sock:starttls(ctx)
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

  -- Read the status line + headers (until the blank separator line). cqueues
  -- `*l` strips only the LF, so each line — including the blank separator —
  -- retains a trailing CR ("\r"). We MUST strip that CR BEFORE testing for the
  -- blank line: testing `== ""` first never matches the "\r" separator, so the
  -- loop reads one line too many; that extra `*l` hits end-of-headers, returns
  -- nil, and poisons the socket's read buffer so every later read times out.
  -- (Found on a real Lua-5.1 + packaged-cqueues target — see README.)
  local status = sock:read("*l")
  if not status or not status:gsub("\r$", ""):match("^HTTP/1%.1 101") then
    return nil, "upgrade rejected: " .. tostring(status)
  end
  local got_accept
  while true do
    local line = sock:read("*l")
    if not line then break end
    line = line:gsub("\r$", "")                -- strip CR *before* the blank test
    if line == "" then break end               -- blank separator → headers done
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
-- Cooperative blocking read with a deadline. A read timeout is expected in
-- steady state (the quiet gaps between heartbeats), so we MUST `clearerr` the
-- preserved per-socket timeout after each benign timeout — cqueues otherwise
-- accumulates them toward its "unchecked error limit" and the controller aborts
-- the whole loop. Validated on a real Lua-5.1 + packaged-cqueues (epoll) target:
-- without the clearerr the connection dies after a few idle heartbeat cycles.
function M:recv(timeout)
  -- Reassemble fragmented messages (RFC 6455 §5.4): a large server push (e.g. a
  -- #1849 `policy` snapshot, which Render's edge re-fragments at ~4 KiB) arrives
  -- as TEXT(FIN=0) + CONTINUATION frames. Returning the first frame raw (the
  -- pre-#1959 behaviour) truncates the snapshot to unparseable JSON. The pure
  -- reassembler (ws_frame) accumulates fragments and passes interleaved control
  -- frames back for us to answer; it persists across recv() calls so a message
  -- split over multiple socket reads still completes.
  self.reasm = self.reasm or ws_frame.reassembler()
  while true do
    local frame, consumed = ws_frame.decode(self.rxbuf)
    if frame then
      self.rxbuf = self.rxbuf:sub(consumed + 1)
      local status, a, b = self.reasm:push(frame)
      if status == "control" then
        local opcode, payload = a, b
        if opcode == ws_frame.OP_PING then
          self:send_pong(payload)                -- heartbeat: keepalive
        elseif opcode == ws_frame.OP_CLOSE then
          self.closed = true
          return nil, "closed"
        end
        -- OP_PONG: liveness ack; loop for the next frame.
      elseif status == "message" then
        return a, b                              -- (opcode, reassembled payload)
      elseif status == "error" then
        self.closed = true
        return nil, "protocol: " .. tostring(a)
      end
      -- "more": a fragment was buffered; keep reading for the rest.
    elseif frame == false then
      self.closed = true
      return nil, "protocol: " .. tostring(consumed)
    else
      -- need more bytes — cooperative blocking read with a deadline.
      self.sock:settimeout(timeout or 35)
      local chunk, err = self.sock:read(-4096)    -- up to 4096B, returns what's ready
      if not chunk then
        -- A timeout/would-block is expected and NOT a drop; clear the preserved
        -- error so it doesn't count toward cqueues' unchecked-error abort limit.
        if err == errno.ETIMEDOUT or err == errno.EAGAIN then
          self.sock:clearerr("r")
          return nil, "timeout"                  -- connection still live
        end
        self.closed = true                        -- real error / EOF → reconnect
        return nil, err and ("eof: " .. tostring(err)) or "eof"
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
