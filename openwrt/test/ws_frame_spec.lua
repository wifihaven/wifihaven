-- ws_frame_spec.lua — unit tests for the spike #1845 RFC 6455 framing layer.
-- Runs on the dev macOS host (Lua 5.5) with no native deps; the same module
-- runs under OpenWrt's Lua 5.1. Vectors are taken verbatim from RFC 6455 so a
-- green run proves wire-correctness against the spec, not against ourselves.
--
-- Note: ws_client.lua / poc_echo.lua are NOT tested here — they require cqueues
-- + openssl and must be exercised on a Linux/router target (see spike README).

-- Resolve the spike module dir whether busted runs from the repo root or from
-- openwrt/ (run_tests.sh cwd). Both candidates are harmless if absent.
package.path = "./spike/ws-1845/?.lua;./openwrt/spike/ws-1845/?.lua;" .. package.path

local frame  = require("ws_frame")
local crypto = require("ws_crypto").pure()

-- byte string → "0xAB 0xCD …" for readable failure diffs
local function hex(s)
  local t = {}
  for i = 1, #s do t[i] = string.format("0x%02x", s:byte(i)) end
  return table.concat(t, " ")
end

describe("ws_frame", function()
  describe("crypto primitives (RFC vectors)", function()
    it("SHA-1 matches the FIPS 'abc' vector", function()
      -- a9993e364706816aba3e25717850c26c9cd0d89d
      local want = "\169\153\62\54\71\6\129\106\186\62\37\113\120\80\194\108\156\208\216\157"
      assert.are.equal(hex(want), hex(crypto.sha1_bin("abc")))
    end)

    it("Base64 matches known vectors", function()
      assert.are.equal("", crypto.base64(""))
      assert.are.equal("Zg==", crypto.base64("f"))
      assert.are.equal("Zm8=", crypto.base64("fo"))
      assert.are.equal("Zm9v", crypto.base64("foo"))
      assert.are.equal("Zm9vYg==", crypto.base64("foob"))
      assert.are.equal("Zm9vYmFy", crypto.base64("foobar"))
    end)
  end)

  describe("opening handshake (RFC 6455 §1.3)", function()
    it("computes the canonical Sec-WebSocket-Accept", function()
      -- RFC 6455 §1.3 worked example.
      local accept = frame.accept_key("dGhlIHNhbXBsZSBub25jZQ==",
        crypto.sha1_bin, crypto.base64)
      assert.are.equal("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", accept)
    end)

    it("new_client_key is 16 random bytes base64'd (24 chars, ==)", function()
      local k = frame.new_client_key(crypto.rand, crypto.base64)
      assert.are.equal(24, #k)
      assert.are.equal("==", k:sub(-2)) -- 16 bytes → 24 b64 chars ending ==
    end)
  end)

  describe("encode (RFC 6455 §5.7 examples)", function()
    it("encodes an UNMASKED single-frame text 'Hello'", function()
      -- 0x81 0x05 H e l l o
      local want = string.char(0x81, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f)
      assert.are.equal(hex(want), hex(frame.encode(frame.OP_TEXT, "Hello")))
    end)

    it("encodes a MASKED single-frame text 'Hello' (key 0x37fa213d)", function()
      -- 0x81 0x85 0x37 0xfa 0x21 0x3d 0x7f 0x9f 0x4d 0x51 0x58
      local mask = string.char(0x37, 0xfa, 0x21, 0x3d)
      local want = string.char(0x81, 0x85, 0x37, 0xfa, 0x21, 0x3d,
        0x7f, 0x9f, 0x4d, 0x51, 0x58)
      assert.are.equal(hex(want), hex(frame.encode(frame.OP_TEXT, "Hello", mask)))
    end)

    it("uses 16-bit extended length for 126..65535 byte payloads", function()
      local payload = string.rep("x", 200)
      local out = frame.encode(frame.OP_BINARY, payload)
      assert.are.equal(0x82, out:byte(1))         -- FIN + binary
      assert.are.equal(126, out:byte(2))          -- length marker
      assert.are.equal(0, out:byte(3))            -- 200 = 0x00C8
      assert.are.equal(200, out:byte(4))
      assert.are.equal(204, #out)                 -- 2 + 2 + 200
    end)

    it("uses 64-bit extended length for >= 65536 byte payloads", function()
      local payload = string.rep("y", 70000)
      local out = frame.encode(frame.OP_BINARY, payload)
      assert.are.equal(127, out:byte(2))          -- 64-bit marker
      assert.are.equal(70000 + 10, #out)          -- 2 + 8 + payload
    end)
  end)

  describe("decode", function()
    it("round-trips an unmasked text frame", function()
      local wire = frame.encode(frame.OP_TEXT, "Hello")
      local f, consumed = frame.decode(wire)
      assert.is_truthy(f)
      assert.are.equal(frame.OP_TEXT, f.opcode)
      assert.is_true(f.fin)
      assert.are.equal("Hello", f.payload)
      assert.are.equal(#wire, consumed)
    end)

    it("decodes a MASKED frame back to plaintext", function()
      local mask = string.char(0x37, 0xfa, 0x21, 0x3d)
      local wire = frame.encode(frame.OP_TEXT, "Hello", mask)
      local f = frame.decode(wire)
      assert.are.equal("Hello", f.payload)
    end)

    it("round-trips a 16-bit extended-length frame", function()
      local payload = string.rep("z", 300)
      local f = frame.decode(frame.encode(frame.OP_BINARY, payload))
      assert.are.equal(payload, f.payload)
      assert.are.equal(frame.OP_BINARY, f.opcode)
    end)

    it("returns nil,0 when the header is incomplete", function()
      local f, consumed = frame.decode("\129")    -- only 1 byte
      assert.is_nil(f)
      assert.are.equal(0, consumed)
    end)

    it("returns nil,0 when the payload is incomplete", function()
      local wire = frame.encode(frame.OP_TEXT, "Hello")
      local f, consumed = frame.decode(wire:sub(1, 4)) -- truncated mid-payload
      assert.is_nil(f)
      assert.are.equal(0, consumed)
    end)

    it("decodes only the first frame from a buffer holding two", function()
      local two = frame.encode(frame.OP_TEXT, "AAA")
        .. frame.encode(frame.OP_PING, "")
      local f, consumed = frame.decode(two)
      assert.are.equal("AAA", f.payload)
      -- the remaining bytes are a complete ping frame
      local f2 = frame.decode(two:sub(consumed + 1))
      assert.are.equal(frame.OP_PING, f2.opcode)
    end)

    it("recognises control opcodes (ping/pong/close)", function()
      assert.are.equal(frame.OP_PING, frame.decode(frame.encode(frame.OP_PING, "")).opcode)
      assert.are.equal(frame.OP_PONG, frame.decode(frame.encode(frame.OP_PONG, "")).opcode)
      assert.are.equal(frame.OP_CLOSE, frame.decode(frame.encode(frame.OP_CLOSE, "")).opcode)
    end)

    it("rejects an absurd 64-bit length with high bits set", function()
      -- byte2=127, then 8 length bytes with a non-zero high word
      local bad = string.char(0x82, 127, 0x01, 0, 0, 0, 0, 0, 0, 0)
      local f, err = frame.decode(bad)
      assert.is_false(f)
      assert.are.equal("frame_too_large", err)
    end)
  end)

  describe("mask symmetry", function()
    it("apply_mask is its own inverse", function()
      local key = string.char(0x01, 0x02, 0x03, 0x04)
      local data = "the quick brown fox jumps over the lazy dog"
      local masked = frame.apply_mask(data, key)
      assert.are_not.equal(data, masked)
      assert.are.equal(data, frame.apply_mask(masked, key))
    end)
  end)
end)
