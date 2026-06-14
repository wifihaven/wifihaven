-- Tests for openwrt/files/usr/lib/lua/wifihaven/sni_reassembly.lua
--
-- Reassembly buffer for TLS ClientHellos that span multiple TCP segments
-- (#1653). The buffer is per-(TCP 4-tuple) and bounded three ways:
--   - per-flow byte cap     (8 KB; bigger than this is suspicious / adversarial)
--   - concurrent-flow cap   (64 flows LRU)
--   - TTL                   (5 s; handshakes complete in << 1s)
--
-- The module is pure Lua with an injected clock (`opts.now_fn`) so tests can
-- exercise the TTL / LRU bounds deterministically. The parser primitive
-- (sni.parse_client_hello) is reused — reassembly does NOT fork the parser.
--
-- Run with: busted openwrt/test/sni_reassembly_spec.lua

local sni    = require("sni")
local reasm  = require("sni_reassembly")

-- ---------------------------------------------------------------------------
-- Binary fixture helpers (mirrored from sni_spec — kept local so this file
-- can be run in isolation).
-- ---------------------------------------------------------------------------

local function bytes(...)
  local t = { ... }
  local s = {}
  for i, b in ipairs(t) do s[i] = string.char(b) end
  return table.concat(s)
end

local function u8(n) return string.char(n) end
local function u16(n) return string.char(math.floor(n / 256) % 256, n % 256) end
local function u24(n)
  return string.char(math.floor(n / 65536) % 256,
                     math.floor(n / 256) % 256,
                     n % 256)
end

-- Build a ClientHello with a (potentially big) leading extension followed by
-- the server_name extension at the end of the extension block. We use this to
-- synthesize a CH whose server_name sits past byte ~1460 (typical MSS), so the
-- single-segment parse misses it and reassembly is required.
local function build_big_client_hello(sni_str, leading_ext_len)
  local random = string.rep("\0", 32)
  local session_id = u8(0)
  local cipher_suites = u16(2) .. bytes(0x13, 0x01)
  local compression = u8(1) .. u8(0)

  local server_name_entry = u8(0) .. u16(#sni_str) .. sni_str
  local server_name_list  = u16(#server_name_entry) .. server_name_entry
  local sni_ext = u16(0x0000) .. u16(#server_name_list) .. server_name_list

  -- Leading extension (e.g. key_share with a hybrid PQ payload).
  local lead_ext = u16(0x0033) .. u16(leading_ext_len) .. string.rep("\0", leading_ext_len)

  local extensions = lead_ext .. sni_ext
  local ext_block  = u16(#extensions) .. extensions

  local body =
      u16(0x0303) .. random .. session_id ..
      cipher_suites .. compression .. ext_block

  local handshake = u8(0x01) .. u24(#body) .. body
  local record    = u8(0x16) .. u16(0x0303) .. u16(#handshake) .. handshake
  return record
end

local function key_of(src_ip, src_port, dst_ip, dst_port)
  return string.format("%s:%d-%s:%d", src_ip, src_port, dst_ip, dst_port)
end

-- ---------------------------------------------------------------------------
-- parse_client_hello — incomplete-vs-no_sni reason distinction (precondition
-- for reassembly: the caller must know which kind of nil to retry).
-- ---------------------------------------------------------------------------

describe("parse_client_hello — reason on nil (#1653)", function()
  it("returns reason='incomplete' when the record header declares more bytes than present", function()
    local hello = build_big_client_hello("example.com", 1800)
    -- Split at byte 1000: clearly mid-CH, server_name lives past byte ~1800.
    local seg1 = hello:sub(1, 1000)
    local r, reason = sni.parse_client_hello(seg1)
    assert.is_nil(r)
    assert.equal("incomplete", reason)
  end)

  it("returns reason='no_sni' for a complete CH with no server_name extension", function()
    -- Reuse the small builder via sni_spec's recipe.
    local random = string.rep("\0", 32)
    local ks = u16(0x0033) .. u16(20) .. string.rep("\0", 20)
    local exts = ks
    local body =
        u16(0x0303) .. random ..
        u8(0) ..
        u16(2) .. bytes(0x13, 0x01) ..
        u8(1) .. u8(0) ..
        u16(#exts) .. exts
    local handshake = u8(0x01) .. u24(#body) .. body
    local rec = u8(0x16) .. u16(0x0303) .. u16(#handshake) .. handshake
    local r, reason = sni.parse_client_hello(rec)
    assert.is_nil(r)
    assert.equal("no_sni", reason)
  end)

  it("returns reason='not_handshake' for a payload that does not start with 0x16", function()
    local r, reason = sni.parse_client_hello(string.rep("\x17", 64))
    assert.is_nil(r)
    assert.equal("not_handshake", reason)
  end)
end)

-- ---------------------------------------------------------------------------
-- Reassembly module — happy path, single segment.
-- ---------------------------------------------------------------------------

describe("sni_reassembly", function()
  local function fresh(now_ref)
    return reasm.new({
      cap_bytes = 8192,
      cap_flows = 64,
      ttl_s     = 5,
      now_fn    = function() return now_ref.t end,
    })
  end

  it("single-segment CH parses immediately with result='parsed'", function()
    local now = { t = 100 }
    local b = fresh(now)
    local hello = build_big_client_hello("single.example.com", 50)
    local result, sn = b:feed(key_of("10.0.0.1", 54321, "1.2.3.4", 443), hello)
    assert.equal("parsed", result)
    assert.equal("single.example.com", sn)
    assert.equal(0, b:size())  -- no buffered entry
  end)

  it("two-segment CH: segment 1 returns 'incomplete'; segment 2 returns 'reassembled' with the SNI", function()
    local now = { t = 100 }
    local b = fresh(now)
    local hello = build_big_client_hello("split.example.com", 1800)
    -- Split well before the server_name extension (which sits past ~1830).
    local seg1 = hello:sub(1, 1000)
    local seg2 = hello:sub(1001)
    local key = key_of("10.0.0.2", 50001, "1.2.3.5", 443)

    local r1, sn1 = b:feed(key, seg1)
    assert.equal("incomplete", r1)
    assert.is_nil(sn1)
    assert.equal(1, b:size())

    now.t = 101
    local r2, sn2 = b:feed(key, seg2)
    assert.equal("reassembled", r2)
    assert.equal("split.example.com", sn2)
    assert.equal(0, b:size())  -- buffer cleared on success
  end)

  it("ignores a payload that does not start with 0x16 when no buffer entry exists ('not_handshake')", function()
    local now = { t = 100 }
    local b = fresh(now)
    local key = key_of("10.0.0.3", 50002, "1.2.3.6", 443)
    local r, sn = b:feed(key, string.rep("\x17", 64))
    assert.equal("not_handshake", r)
    assert.is_nil(sn)
    assert.equal(0, b:size())
  end)

  -- -----------------------------------------------------------------------
  -- TTL eviction.
  -- -----------------------------------------------------------------------

  it("TTL eviction: a buffered flow older than ttl_s is dropped on the next feed for that key", function()
    local now = { t = 100 }
    local b = fresh(now)
    local hello = build_big_client_hello("ttl.example.com", 1800)
    local seg1 = hello:sub(1, 1000)
    local seg2 = hello:sub(1001)
    local key = key_of("10.0.0.4", 50003, "1.2.3.7", 443)

    assert.equal("incomplete", (b:feed(key, seg1)))
    assert.equal(1, b:size())

    -- Advance past TTL.
    now.t = 100 + 6
    -- Segment 2 alone does NOT start with 0x16, so without the buffered seg1
    -- it fails as not_handshake. The stale entry must have been evicted.
    local r, sn = b:feed(key, seg2)
    assert.equal("not_handshake", r)
    assert.is_nil(sn)
    assert.equal(0, b:size())
  end)

  it("evict_expired removes all stale entries", function()
    local now = { t = 100 }
    local b = fresh(now)
    local hello = build_big_client_hello("evict.example.com", 1800)
    local seg1 = hello:sub(1, 1000)

    for i = 1, 3 do
      b:feed(key_of("10.0.0.5", 50000 + i, "1.2.3.8", 443), seg1)
    end
    assert.equal(3, b:size())

    now.t = 200  -- well past TTL
    local n = b:evict_expired()
    assert.equal(3, n)
    assert.equal(0, b:size())
  end)

  -- -----------------------------------------------------------------------
  -- Concurrent-flow LRU cap.
  -- -----------------------------------------------------------------------

  it("LRU eviction: filling cap_flows then adding one more drops the oldest entry", function()
    local now = { t = 100 }
    local b = reasm.new({
      cap_bytes = 8192,
      cap_flows = 4,
      ttl_s     = 60,
      now_fn    = function() return now.t end,
    })
    local hello = build_big_client_hello("lru.example.com", 1800)
    local seg1 = hello:sub(1, 1000)

    local keys = {}
    for i = 1, 4 do
      keys[i] = key_of("10.0.0.6", 50000 + i, "1.2.3.9", 443)
      assert.equal("incomplete", (b:feed(keys[i], seg1)))
      now.t = now.t + 1  -- monotonic increment so LRU order is stable
    end
    assert.equal(4, b:size())

    -- Insert a 5th flow → oldest (keys[1]) must be evicted.
    local k5 = key_of("10.0.0.6", 60000, "1.2.3.9", 443)
    assert.equal("incomplete", (b:feed(k5, seg1)))
    assert.equal(4, b:size())  -- still at cap

    -- Continuation for the evicted flow comes in: should NOT reassemble (its
    -- buffer is gone, the continuation's first byte isn't a handshake header).
    local seg2 = hello:sub(1001)
    local r, sn = b:feed(keys[1], seg2)
    assert.equal("not_handshake", r)
    assert.is_nil(sn)
  end)

  -- -----------------------------------------------------------------------
  -- Per-flow byte cap.
  -- -----------------------------------------------------------------------

  it("per-flow byte cap: appending past cap_bytes drops the flow (result='dropped_byte_cap')", function()
    local now = { t = 100 }
    local b = reasm.new({
      cap_bytes = 2048,    -- small cap for the test
      cap_flows = 64,
      ttl_s     = 5,
      now_fn    = function() return now.t end,
    })
    local hello = build_big_client_hello("oversize.example.com", 4000)
    local seg1 = hello:sub(1, 1500)
    local seg2 = hello:sub(1501, 3000)  -- pushes total past 2048
    local key = key_of("10.0.0.7", 50007, "1.2.3.10", 443)

    assert.equal("incomplete", (b:feed(key, seg1)))
    assert.equal(1, b:size())

    local r, sn = b:feed(key, seg2)
    assert.equal("dropped_byte_cap", r)
    assert.is_nil(sn)
    assert.equal(0, b:size())  -- entry dropped
  end)

  it("per-flow byte cap: a single oversize first segment is dropped immediately", function()
    local now = { t = 100 }
    local b = reasm.new({
      cap_bytes = 2048,
      cap_flows = 64,
      ttl_s     = 5,
      now_fn    = function() return now.t end,
    })
    -- A CH whose declared record length is huge AND whose payload alone
    -- exceeds the byte cap. The 0x16 prefix is present so it isn't 'not_handshake'.
    local hello = build_big_client_hello("oversize2.example.com", 4000)
    -- Take the first 3000 bytes — record header says ~4080, parser returns
    -- incomplete, but the bytes themselves exceed cap_bytes.
    local big_seg = hello:sub(1, 3000)
    local key = key_of("10.0.0.8", 50008, "1.2.3.11", 443)

    local r, sn = b:feed(key, big_seg)
    assert.equal("dropped_byte_cap", r)
    assert.is_nil(sn)
    assert.equal(0, b:size())
  end)
end)
