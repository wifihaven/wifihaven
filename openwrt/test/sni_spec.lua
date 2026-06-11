-- Tests for openwrt/files/usr/lib/lua/wifihaven/sni.lua
--
-- sni.lua parses TLS ClientHello records and extracts the SNI server_name
-- extension. v1 covers single-segment TLS 1.2 and TLS 1.3 ClientHellos on
-- TCP dst port 443 (#573). The parser is pure Lua, operates on a raw byte
-- string, and is unit-testable via busted with hand-rolled binary fixtures.
--
-- Run with: busted openwrt/test/sni_spec.lua

local sni = require("sni")

-- ---------------------------------------------------------------------------
-- Binary fixture helpers — keep test fixtures readable and easy to truncate.
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

-- Build a TLS ClientHello "record" containing only a server_name extension.
--
-- Layout (RFC 5246 / RFC 8446):
--   TLSPlaintext
--     ContentType        u8   = 0x16 (handshake)
--     ProtocolVersion    u16  = 0x0301..0x0303
--     Length             u16  = (rest of record)
--   Handshake
--     HandshakeType      u8   = 0x01 (client_hello)
--     Length             u24
--   ClientHello
--     legacy_version     u16
--     random             32 bytes
--     legacy_session_id  u8 length + bytes
--     cipher_suites      u16 length + bytes
--     compression_methods u8 length + bytes
--     extensions         u16 length + bytes
--
-- A single server_name extension has the shape:
--   extension_type    u16  = 0x0000
--   extension_data_len u16
--   server_name_list_len u16
--   name_type         u8   = 0x00 (host_name)
--   server_name_len   u16
--   server_name       bytes
local function build_client_hello(opts)
  opts = opts or {}
  local tls_version  = opts.tls_version or 0x0303      -- record-layer version
  local legacy_ver   = opts.legacy_version or 0x0303   -- ClientHello.legacy_version
  local sni          = opts.sni                        -- server_name string, or nil
  local extra_exts   = opts.extra_exts or ""           -- raw bytes prepended before SNI ext
  local omit_sni_ext = opts.omit_sni_ext or false
  local handshake_type = opts.handshake_type or 0x01   -- override to test ServerHello (0x02)

  -- random: 32 bytes of zeros (content irrelevant)
  local random = string.rep("\0", 32)
  local session_id = u8(0) -- empty session id
  local cipher_suites = u16(2) .. bytes(0x13, 0x01) -- one suite TLS_AES_128_GCM_SHA256
  local compression = u8(1) .. u8(0) -- null compression

  -- Build server_name extension
  local sni_ext = ""
  if not omit_sni_ext and sni then
    local server_name_entry = u8(0) .. u16(#sni) .. sni
    local server_name_list  = u16(#server_name_entry) .. server_name_entry
    sni_ext = u16(0x0000) .. u16(#server_name_list) .. server_name_list
  end

  local extensions = extra_exts .. sni_ext
  local ext_block  = u16(#extensions) .. extensions

  local client_hello_body =
      u16(legacy_ver) .. random .. session_id ..
      cipher_suites .. compression .. ext_block

  local handshake = u8(handshake_type) .. u24(#client_hello_body) .. client_hello_body
  local record    = u8(0x16) .. u16(tls_version) .. u16(#handshake) .. handshake
  return record
end

-- A key_share extension (TLS 1.3) — type 0x0033, opaque payload. Used to test
-- that the parser walks past non-SNI extensions and still finds server_name.
local function build_key_share_ext(payload_len)
  payload_len = payload_len or 38
  return u16(0x0033) .. u16(payload_len) .. string.rep("\0", payload_len)
end

-- ---------------------------------------------------------------------------
-- 1. parse_client_hello — happy paths
-- ---------------------------------------------------------------------------

describe("parse_client_hello", function()
  it("extracts SNI from a TLS 1.2 ClientHello with only the server_name extension", function()
    local rec = build_client_hello({ tls_version = 0x0303, legacy_version = 0x0303,
                                     sni = "example.com" })
    assert.equal("example.com", sni.parse_client_hello(rec))
  end)

  it("extracts SNI from a TLS 1.3 ClientHello where key_share precedes server_name", function()
    local rec = build_client_hello({
      tls_version = 0x0303, legacy_version = 0x0303,
      sni = "www.youtube.com",
      extra_exts = build_key_share_ext(40),
    })
    assert.equal("www.youtube.com", sni.parse_client_hello(rec))
  end)

  it("extracts SNI when record-layer version is 0x0301 (TLS 1.0 record framing, real ClientHellos do this)", function()
    local rec = build_client_hello({ tls_version = 0x0301, legacy_version = 0x0303,
                                     sni = "calendar.google.com" })
    assert.equal("calendar.google.com", sni.parse_client_hello(rec))
  end)
end)

-- ---------------------------------------------------------------------------
-- 2. parse_client_hello — graceful nil paths (no Lua errors)
-- ---------------------------------------------------------------------------

describe("parse_client_hello — nil-returning paths", function()
  it("returns nil when there is no server_name extension", function()
    local rec = build_client_hello({ sni = "example.com", omit_sni_ext = true,
                                     extra_exts = build_key_share_ext(20) })
    assert.is_nil(sni.parse_client_hello(rec))
  end)

  it("returns nil for a truncated record (record header says more than is present)", function()
    local rec = build_client_hello({ sni = "example.com" })
    local truncated = rec:sub(1, 50) -- way short of declared length
    assert.is_nil(sni.parse_client_hello(truncated))
  end)

  it("returns nil for a malformed server_name extension (length overruns)", function()
    -- Hand-build a record whose server_name extension claims length 9999 but
    -- the extension block ends much sooner.
    local random = string.rep("\0", 32)
    local body =
      u16(0x0303) .. random ..
      u8(0) ..             -- empty session id
      u16(2) .. bytes(0x13, 0x01) ..
      u8(1) .. u8(0) ..
      u16(8) ..            -- extensions total length = 8
      u16(0x0000) ..       -- ext type = SNI
      u16(9999) ..         -- ext data length = lie
      u16(9997) ..         -- server_name_list_len = lie
      u8(0) ..             -- name_type host_name
      u16(9994) ..         -- server_name_len = lie
      "x"                  -- only one body byte
    local handshake = u8(0x01) .. u24(#body) .. body
    local rec = u8(0x16) .. u16(0x0303) .. u16(#handshake) .. handshake
    assert.is_nil(sni.parse_client_hello(rec))
  end)

  it("returns nil for a non-ClientHello handshake (ServerHello)", function()
    local rec = build_client_hello({ sni = "example.com", handshake_type = 0x02 })
    assert.is_nil(sni.parse_client_hello(rec))
  end)

  it("returns nil for non-TLS payload (random bytes starting with 0x17 application_data)", function()
    local payload = u8(0x17) .. u16(0x0303) .. u16(16) .. string.rep("X", 16)
    assert.is_nil(sni.parse_client_hello(payload))
  end)

  it("returns nil for an empty / non-string input", function()
    assert.is_nil(sni.parse_client_hello(""))
    assert.is_nil(sni.parse_client_hello(nil))
    assert.is_nil(sni.parse_client_hello(42))
  end)

  it("returns nil for a server_name extension with name_type != host_name", function()
    -- Hand-build: name_type = 0xff (unknown). RFC 6066: parser must skip.
    local random = string.rep("\0", 32)
    local snlen = 11
    local server_name_entry = u8(0xff) .. u16(snlen) .. "example.com"
    local server_name_list  = u16(#server_name_entry) .. server_name_entry
    local sni_ext = u16(0x0000) .. u16(#server_name_list) .. server_name_list
    local body =
      u16(0x0303) .. random ..
      u8(0) ..
      u16(2) .. bytes(0x13, 0x01) ..
      u8(1) .. u8(0) ..
      u16(#sni_ext) .. sni_ext
    local handshake = u8(0x01) .. u24(#body) .. body
    local rec = u8(0x16) .. u16(0x0303) .. u16(#handshake) .. handshake
    assert.is_nil(sni.parse_client_hello(rec))
  end)
end)

-- ---------------------------------------------------------------------------
-- 3. parse_packet — Ethernet / IP / TCP framing
-- ---------------------------------------------------------------------------
--
-- The sni-tail sidecar feeds raw packets from `tcpdump -i br-lan -w -` (pcap
-- format), each link-layer record is an Ethernet frame:
--   Ethernet  (14 bytes; src MAC at offset 6..11)
--   IPv4      (variable, 20+ bytes; dst IP at IHL offset 16..19, proto at 9)
--   TCP       (variable, 20+ bytes; data offset in upper nibble of byte 12)
--   Payload   ← TLS record starts here

local function build_eth_ipv4_tcp(opts)
  opts = opts or {}
  local src_mac = opts.src_mac or { 0x76, 0x2d, 0x95, 0x47, 0xd1, 0x8e }
  local dst_ip  = opts.dst_ip  or { 142, 250, 80, 46 }
  local payload = opts.payload or ""

  -- Ethernet: dst (6), src (6), ethertype (2 = 0x0800 IPv4)
  local eth = string.char(0,0,0,0,0,0) ..
              string.char(src_mac[1], src_mac[2], src_mac[3],
                          src_mac[4], src_mac[5], src_mac[6]) ..
              u16(0x0800)

  -- TCP: src port, dst port, seq, ack, data-offset+flags, window, checksum, urg
  local tcp =
      u16(54321) .. u16(443) ..
      string.rep("\0", 4) ..       -- seq
      string.rep("\0", 4) ..       -- ack
      string.char(0x50, 0x18) ..   -- data offset 5*4=20 bytes, PSH+ACK
      u16(0) .. u16(0) .. u16(0) ..
      payload

  -- IPv4: VER+IHL, DSCP, total length, ident, flags+frag, TTL, proto=6 TCP,
  --       checksum, src ip, dst ip
  local total_len = 20 + #tcp
  local ip =
      string.char(0x45) .. string.char(0) .. u16(total_len) ..
      u16(0) .. u16(0) .. string.char(64) .. string.char(6) ..
      u16(0) ..
      string.char(192, 168, 1, 100) ..
      string.char(dst_ip[1], dst_ip[2], dst_ip[3], dst_ip[4])

  return eth .. ip .. tcp
end

describe("parse_packet (Ethernet/IPv4/TCP)", function()
  it("returns dst_ip, src_mac, sni for a single-segment ClientHello", function()
    local hello = build_client_hello({ sni = "example.com" })
    local pkt = build_eth_ipv4_tcp({
      payload = hello,
      src_mac = { 0x76, 0x2d, 0x95, 0x47, 0xd1, 0x8e },
      dst_ip  = { 142, 250, 80, 46 },
    })
    local r = sni.parse_packet(pkt)
    assert.is_not_nil(r)
    assert.equal("142.250.80.46",    r.dst_ip)
    assert.equal("76:2d:95:47:d1:8e", r.src_mac)
    assert.equal("example.com",      r.sni)
  end)

  it("returns nil when the TCP payload is not a TLS ClientHello", function()
    local pkt = build_eth_ipv4_tcp({ payload = string.rep("X", 64) })
    assert.is_nil(sni.parse_packet(pkt))
  end)

  it("returns nil for a short / truncated packet", function()
    assert.is_nil(sni.parse_packet(""))
    assert.is_nil(sni.parse_packet(string.rep("\0", 30)))
    assert.is_nil(sni.parse_packet(nil))
  end)
end)

-- ---------------------------------------------------------------------------
-- 4. format_sni_line / parse_sni_line — IPC line format between sidecars
-- ---------------------------------------------------------------------------
--
-- wifihaven-sni-tail emits lines into /tmp/wifihaven-sni.log; dns-tail tails
-- that file alongside the dnsmasq log and routes SNI-prefixed lines through
-- cache.insert_sni. The line format is a single TSV row prefixed with "SNI\t"
-- so dns-tail can disambiguate cheaply.
describe("format_sni_line / parse_sni_line", function()
  it("round-trips a (dst_ip, src_mac, sni) triple", function()
    local line = sni.format_sni_line("142.250.80.46", "76:2d:95:47:d1:8e", "example.com")
    assert.matches("^SNI\t", line)
    local r = sni.parse_sni_line(line)
    assert.is_not_nil(r)
    assert.equal("142.250.80.46",     r.dst_ip)
    assert.equal("76:2d:95:47:d1:8e", r.src_mac)
    assert.equal("example.com",        r.sni)
  end)

  it("returns nil for non-SNI lines (e.g. a dnsmasq log line)", function()
    assert.is_nil(sni.parse_sni_line(
      "Nov 12 10:00:01 dnsmasq[1234]: 7 192.168.1.42/54321 query[A] youtube.com from 192.168.1.42"))
    assert.is_nil(sni.parse_sni_line(""))
    assert.is_nil(sni.parse_sni_line(nil))
  end)
end)
