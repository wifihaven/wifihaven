-- ws_frame.lua — pure-Lua RFC 6455 websocket framing for the agent ws spike (#1845).
--
-- WHY HAND-ROLLED: the OpenWrt feed packages `cqueues` (the async event loop +
-- TLS socket the sidecar needs) but NOT a Lua websocket library — `lua-http`
-- and its dep chain (luaossl, basexx, lpeg, lpeg_patterns, binaryheap, fifo)
-- are absent from both the 23.05 ipk feed and the snapshot/apk feed. RFC 6455
-- *client* framing is small and stable, so we hand-roll it over the cqueues
-- TLS socket rather than vendoring a six-package dependency tree onto the
-- router image. See spike findings doc.
--
-- This module is PURE (no I/O, no cqueues) so it is unit-testable on the dev
-- macOS host under Lua 5.5 exactly as it runs under OpenWrt's Lua 5.1 — the
-- same split quic.lua uses. All bit math is plain arithmetic (no `bit`/`bit32`
-- dependency, neither of which is guaranteed in the cqueues Lua 5.1 env), and
-- the SHA-1/Base64/random primitives the handshake needs are DEPENDENCY-
-- INJECTED so the caller can back them with lua-openssl on the target or with
-- the pure-Lua ws_crypto.lua fallback in tests.
--
-- RFC 6455 §5.2 frame layout, §5.7 examples, §1.3 opening-handshake accept key.

local M = {}

-- ── opcodes (RFC 6455 §5.2) ──────────────────────────────────────────────────
M.OP_CONTINUATION = 0x0
M.OP_TEXT         = 0x1
M.OP_BINARY       = 0x2
M.OP_CLOSE        = 0x8
M.OP_PING         = 0x9
M.OP_PONG         = 0xA

-- The reason string ws_client:recv returns when it consumed a control PONG —
-- not a message, but proof the peer answered our heartbeat. Defined HERE, next
-- to the opcode it names, so the producer (ws_client:recv), the consumer
-- (ws_loop.classify_recv) and the specs that script a fake client all read one
-- value instead of three hand-matched string literals (#2731).
M.RECV_PONG       = "pong"

-- The fixed GUID RFC 6455 §1.3 appends to the client key before SHA-1 to form
-- the Sec-WebSocket-Accept value the server echoes.
local WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

-- Arithmetic byte-XOR (no `bit` lib). Inputs/outputs are 0..255. Mirrors the
-- bxor in quic.lua so this file loads identically under Lua 5.1 and 5.5+.
local function bxor8(a, b)
  local r, bit = 0, 1
  for _ = 1, 8 do
    local abit = a % 2
    local bbit = b % 2
    if abit ~= bbit then r = r + bit end
    a = (a - abit) / 2
    b = (b - bbit) / 2
    bit = bit * 2
  end
  return r
end

-- Big-endian integer → n bytes.
local function be_bytes(value, n)
  local out = {}
  for i = n, 1, -1 do
    out[i] = string.char(value % 256)
    value = math.floor(value / 256)
  end
  return table.concat(out)
end

-- Big-endian bytes → integer (n ≤ 6 so it stays within Lua 5.1 double range;
-- websocket payloads we send/receive are far under 2^48).
local function be_int(s, from, n)
  local v = 0
  for i = 0, n - 1 do
    v = v * 256 + s:byte(from + i)
  end
  return v
end

-- Apply the 4-byte mask key to a payload (RFC 6455 §5.3). Symmetric: the same
-- function masks (encode) and unmasks (decode).
local function apply_mask(payload, key)
  if not key or #key ~= 4 then return payload end
  local out = {}
  local kb = { key:byte(1, 4) }
  for i = 1, #payload do
    out[i] = string.char(bxor8(payload:byte(i), kb[((i - 1) % 4) + 1]))
  end
  return table.concat(out)
end
M.apply_mask = apply_mask

-- encode(opcode, payload, mask_key) → wire bytes.
--
-- mask_key MUST be a 4-byte string for client→server frames (RFC 6455 §5.3
-- requires every client frame be masked). Pass nil only when encoding a
-- server→client frame in a test. `fin` defaults true (we never fragment our
-- own sends; payloads are bounded by ws_max_frame_bytes upstream).
function M.encode(opcode, payload, mask_key, fin)
  payload = payload or ""
  if fin == nil then fin = true end

  local b1 = opcode % 16
  if fin then b1 = b1 + 0x80 end

  local masked = (mask_key ~= nil)
  local mask_flag = masked and 0x80 or 0x00
  local len = #payload

  local header
  if len < 126 then
    header = string.char(b1, mask_flag + len)
  elseif len < 65536 then
    header = string.char(b1, mask_flag + 126) .. be_bytes(len, 2)
  else
    header = string.char(b1, mask_flag + 127) .. be_bytes(len, 8)
  end

  if masked then
    return header .. mask_key .. apply_mask(payload, mask_key)
  end
  return header .. payload
end

-- decode(buf) → frame, consumed   (frame = {fin, opcode, payload})
--          or → nil, 0            (incomplete: need more bytes, buffer kept)
--          or → false, err        (protocol error)
--
-- Server→client frames are unmasked per RFC 6455 §5.1, but we honour the MASK
-- bit defensively so a misbehaving/proxied peer doesn't corrupt the payload.
-- Returns how many bytes were consumed so the caller can slice the read buffer.
function M.decode(buf)
  local n = #buf
  if n < 2 then return nil, 0 end

  local b1 = buf:byte(1)
  local b2 = buf:byte(2)
  local fin = (b1 >= 0x80)
  local opcode = b1 % 16
  local masked = (b2 >= 0x80)
  local len = b2 % 128

  local offset = 3
  if len == 126 then
    if n < 4 then return nil, 0 end
    len = be_int(buf, 3, 2)
    offset = 5
  elseif len == 127 then
    if n < 10 then return nil, 0 end
    -- High 4 bytes must be zero for any frame we'd ever see; guard the double.
    if be_int(buf, 3, 4) ~= 0 then return false, "frame_too_large" end
    len = be_int(buf, 7, 4)
    offset = 11
  end

  local mask_key
  if masked then
    if n < offset + 3 then return nil, 0 end
    mask_key = buf:sub(offset, offset + 3)
    offset = offset + 4
  end

  local frame_end = offset + len - 1
  if n < frame_end then return nil, 0 end

  local payload = buf:sub(offset, frame_end)
  if masked then payload = apply_mask(payload, mask_key) end

  return { fin = fin, opcode = opcode, payload = payload }, frame_end
end

-- accept_key(client_key, sha1_bin_fn, base64_fn) → expected Sec-WebSocket-Accept.
--
-- RFC 6455 §1.3: base64( sha1( client_key .. GUID ) ). The client verifies the
-- server's handshake reply carries exactly this. sha1_bin_fn must return the
-- 20 raw bytes (NOT hex); base64_fn standard base64. Both injected.
function M.accept_key(client_key, sha1_bin_fn, base64_fn)
  return base64_fn(sha1_bin_fn(client_key .. WS_GUID))
end

-- new_client_key(rand16_fn, base64_fn) → 16-random-bytes base64'd, the value
-- for the outgoing Sec-WebSocket-Key header (RFC 6455 §4.1).
function M.new_client_key(rand16_fn, base64_fn)
  return base64_fn(rand16_fn(16))
end

-- ── message reassembly (RFC 6455 §5.4) ───────────────────────────────────────
--
-- decode() returns ONE frame at a time; a single application message may be
-- split across a leading data frame (TEXT/BINARY, FIN=0) plus CONTINUATION
-- (0x0) frames, with control frames (ping/pong/close) optionally interleaved.
-- The server (and intermediaries — Render's edge re-fragments large outbound
-- frames at ~4 KiB, #1959) can split a large `policy` push this way, so the
-- agent MUST reassemble or it truncates the snapshot to garbage.
--
-- This is the part of the cqueues client's recv loop that has no I/O, so it
-- lives here (PURE, no cqueues) where it is unit-tested on the dev host AND
-- runs identically under OpenWrt Lua 5.1 — the same split the rest of this
-- module uses. The cqueues client (ws_client:recv) feeds each decoded frame to
-- push() and acts on the verdict.
--
-- reassembler() → object with push(frame) → (status, a, b):
--   "message", opcode, payload  — a complete application message (the START
--                                 data opcode + concatenated payload). Returned
--                                 for an unfragmented data frame, or the final
--                                 CONTINUATION of a fragmented one.
--   "control", opcode, payload  — a control frame (ping/pong/close); the caller
--                                 handles it (pong/close) and keeps reading.
--                                 Reassembly state is untouched (§5.4 allows
--                                 control frames between message fragments).
--   "more"                      — fragment accepted; need more frames.
--   "error",  reason            — a protocol violation; the caller must drop the
--                                 connection. reason ∈ { unexpected_continuation,
--                                 interleaved_data, fragmented_control,
--                                 oversized_control, message_too_large }.
--
-- reassembler(max_bytes) — cap the TOTAL assembled message size (default 1 MiB).
-- decode()'s 64-bit-length guard bounds a SINGLE frame; this bounds the sum
-- across continuation frames so a buggy/hostile peer streaming endless
-- continuations cannot grow self.parts unbounded and OOM the RAM-constrained
-- router. A real `policy` snapshot is far under this.
local Reassembler = {}
Reassembler.__index = Reassembler

local DEFAULT_MAX_BYTES = 1024 * 1024

function M.reassembler(max_bytes)
  return setmetatable(
    { op = nil, parts = nil, size = 0, max_bytes = max_bytes or DEFAULT_MAX_BYTES },
    Reassembler)
end

function Reassembler:push(frame)
  local opcode = frame.opcode

  -- Control frames (0x8..0xF) are never part of a data message and MUST NOT be
  -- fragmented (§5.5) and MUST carry ≤125 payload bytes. They pass straight
  -- through; an in-progress fragmented data message is unaffected.
  if opcode >= 0x8 then
    if not frame.fin then return "error", "fragmented_control" end
    if #frame.payload > 125 then return "error", "oversized_control" end
    return "control", opcode, frame.payload
  end

  if opcode == M.OP_CONTINUATION then
    if not self.op then return "error", "unexpected_continuation" end
    self.size = self.size + #frame.payload
    if self.size > self.max_bytes then return "error", "message_too_large" end
    self.parts[#self.parts + 1] = frame.payload
    if not frame.fin then return "more" end
    local payload = table.concat(self.parts)
    local start_op = self.op
    self.op, self.parts, self.size = nil, nil, 0   -- reset for the next message
    return "message", start_op, payload
  end

  -- A data frame (TEXT/BINARY). A NEW data frame while a message is still being
  -- assembled is illegal — only continuations and control frames may follow a
  -- FIN=0 data frame (§5.4).
  if self.op then return "error", "interleaved_data" end

  if frame.fin then                          -- the common unfragmented case
    return "message", opcode, frame.payload
  end

  -- Start of a fragmented message: remember the opcode, buffer the first chunk.
  if #frame.payload > self.max_bytes then return "error", "message_too_large" end
  self.op = opcode
  self.parts = { frame.payload }
  self.size = #frame.payload
  return "more"
end

return M
