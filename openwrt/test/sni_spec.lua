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

-- An ECH extension (#1650) — RFC draft-ietf-tls-esni type 0xfe0d. The body is
-- opaque; the parser only needs to detect the type, so an arbitrary fixed body
-- is sufficient for the fixture.
local function build_ech_ext(payload_len)
  payload_len = payload_len or 32
  return u16(0xfe0d) .. u16(payload_len) .. string.rep("\0", payload_len)
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

  it("extracts SNI from a snaplen-clipped ClientHello when the server_name ext is in the captured prefix (#573)", function()
    -- Regression for the prod-equivalent bug the Gate 2 e2e caught: real
    -- OpenSSL/curl TLS 1.3 ClientHellos are padded past ~512 bytes, so under
    -- the capture snaplen the tail (large key_share / padding) is clipped. The
    -- server_name extension sits near the FRONT, so it is fully present in the
    -- captured prefix — the parser must still return it instead of rejecting
    -- the whole record because its declared length exceeds the buffer.
    local sni_str = "example.com"
    local entry   = u8(0) .. u16(#sni_str) .. sni_str
    local list    = u16(#entry) .. entry
    local sni_ext = u16(0x0000) .. u16(#list) .. list
    -- SNI FIRST, then a large trailing extension that the snaplen clip removes.
    local big_ext = u16(0x0033) .. u16(800) .. string.rep("\0", 800)
    local exts    = sni_ext .. big_ext
    local body =
      u16(0x0303) .. string.rep("\0", 32) ..   -- legacy_version + random
      u8(0) ..                                 -- empty session id
      u16(2) .. bytes(0x13, 0x01) ..           -- one cipher suite
      u8(1) .. u8(0) ..                        -- null compression
      u16(#exts) .. exts                       -- extensions (declared full size)
    local handshake = u8(0x01) .. u24(#body) .. body
    local rec       = u8(0x16) .. u16(0x0303) .. u16(#handshake) .. handshake
    -- Clip well past the SNI ext (~byte 80) but far short of the declared
    -- ~880-byte record, exactly as a small snaplen would.
    local clipped = rec:sub(1, 130)
    assert.equal("example.com", sni.parse_client_hello(clipped))
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
-- 3b. parse_packet — Ethernet / IPv6 / TCP framing (#1652)
-- ---------------------------------------------------------------------------
--
-- Dual-stack clients reaching CDNs over IPv6 must attribute by SNI just like
-- v4. The downstream cache/conntrack path already keys by string IP and has
-- parallel eb6_/bl6_/resolved6_ sets for v6 (#1668), so parse_packet just
-- needs to parse the v6 outer header (fixed 40 bytes + extension-header chain
-- per RFC 8200) and emit the canonical RFC 5952 textual destination.

local function u16str(n) return string.char(math.floor(n / 256) % 256, n % 256) end

-- Build an Ethernet+IPv6+TCP frame carrying `payload`. `ext_chain` is an
-- optional list of { next_header = u8, body = <bytes (must include the
-- hdr_ext_len byte and pad to multiples of 8)> } extension headers inserted
-- between the fixed v6 header and the TCP header. The fixed header's
-- next_header is the first ext_chain entry's type, or TCP (6) if no chain.
local function build_eth_ipv6_tcp(opts)
  opts = opts or {}
  local src_mac = opts.src_mac or { 0x76, 0x2d, 0x95, 0x47, 0xd1, 0x8e }
  -- dst_ip is 16 raw bytes; default = 2607:f8b0:4004:0c08:0000:0000:0000:200e
  local dst_ip  = opts.dst_ip  or {
    0x26, 0x07, 0xf8, 0xb0, 0x40, 0x04, 0x0c, 0x08,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x20, 0x0e,
  }
  local src_ip  = opts.src_ip  or {
    0xfd, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01,
  }
  local payload = opts.payload or ""
  local ext_chain = opts.ext_chain or {}

  local eth = string.char(0,0,0,0,0,0) ..
              string.char(src_mac[1], src_mac[2], src_mac[3],
                          src_mac[4], src_mac[5], src_mac[6]) ..
              u16str(0x86dd)

  -- TCP header (20 bytes) + payload.
  local tcp =
      u16str(54321) .. u16str(443) ..
      string.rep("\0", 4) ..       -- seq
      string.rep("\0", 4) ..       -- ack
      string.char(0x50, 0x18) ..   -- data offset 20, PSH+ACK
      u16str(0) .. u16str(0) .. u16str(0) ..
      payload

  -- Build extension-header chain, walking back-to-front so each header points
  -- to the next. The final extension's next_header is TCP (6).
  local ext_bytes = ""
  local first_nh = 6 -- TCP if no extensions
  if #ext_chain > 0 then
    for i = #ext_chain, 1, -1 do
      local nh
      if i == #ext_chain then nh = 6 else nh = ext_chain[i + 1].next_header end
      ext_bytes = string.char(nh) .. ext_chain[i].body .. ext_bytes
    end
    first_nh = ext_chain[1].next_header
  end

  local payload_len = #ext_bytes + #tcp
  -- IPv6 fixed header: ver(4)/tc(8)/flow(20) = 4 bytes, payload_len(2),
  -- next_header(1), hop_limit(1), src(16), dst(16) = 40 bytes total.
  local ipv6 =
      string.char(0x60, 0, 0, 0) ..
      u16str(payload_len) ..
      string.char(first_nh) ..
      string.char(64) ..
      string.char(src_ip[1], src_ip[2], src_ip[3], src_ip[4],
                  src_ip[5], src_ip[6], src_ip[7], src_ip[8],
                  src_ip[9], src_ip[10], src_ip[11], src_ip[12],
                  src_ip[13], src_ip[14], src_ip[15], src_ip[16]) ..
      string.char(dst_ip[1], dst_ip[2], dst_ip[3], dst_ip[4],
                  dst_ip[5], dst_ip[6], dst_ip[7], dst_ip[8],
                  dst_ip[9], dst_ip[10], dst_ip[11], dst_ip[12],
                  dst_ip[13], dst_ip[14], dst_ip[15], dst_ip[16])

  return eth .. ipv6 .. ext_bytes .. tcp
end

describe("parse_packet (Ethernet/IPv6/TCP) — #1652", function()
  it("attributes a plain IPv6 ClientHello (no extension headers)", function()
    local hello = build_client_hello({ sni = "www.youtube.com" })
    local pkt = build_eth_ipv6_tcp({
      payload = hello,
      src_mac = { 0x76, 0x2d, 0x95, 0x47, 0xd1, 0x8e },
      dst_ip  = {
        0x26, 0x07, 0xf8, 0xb0, 0x40, 0x04, 0x0c, 0x08,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x20, 0x0e,
      },
    })
    local r, reason = sni.parse_packet(pkt)
    assert.is_nil(reason)
    assert.is_not_nil(r)
    assert.equal("2607:f8b0:4004:c08::200e", r.dst_ip)
    assert.equal("76:2d:95:47:d1:8e",        r.src_mac)
    assert.equal("www.youtube.com",          r.sni)
  end)

  it("walks past a Hop-by-Hop extension header to find the TCP payload", function()
    -- Hop-by-Hop options: next_header(1) + hdr_ext_len(1) + 6 bytes of options
    -- = one 8-byte block. hdr_ext_len encodes (total_octets/8 - 1) = 0.
    local hbh = { next_header = 0, body = string.char(0) .. string.rep("\0", 6) }
    local hello = build_client_hello({ sni = "calendar.google.com" })
    local pkt = build_eth_ipv6_tcp({ payload = hello, ext_chain = { hbh },
                                     dst_ip = {
                                       0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0,
                                       0, 0, 0, 0, 0, 0, 0x00, 0x42,
                                     } })
    local r, reason = sni.parse_packet(pkt)
    assert.is_nil(reason)
    assert.is_not_nil(r)
    assert.equal("2001:db8::42",         r.dst_ip)
    assert.equal("calendar.google.com",  r.sni)
  end)

  it("walks past chained Hop-by-Hop + Routing extension headers", function()
    -- Two 8-byte extension headers chained: HBH (0) → Routing (43) → TCP.
    local hbh    = { next_header = 0,  body = string.char(0) .. string.rep("\0", 6) }
    local route  = { next_header = 43, body = string.char(0) .. string.rep("\0", 6) }
    local pkt = build_eth_ipv6_tcp({
      payload = build_client_hello({ sni = "www.example.com" }),
      ext_chain = { hbh, route },
    })
    local r, reason = sni.parse_packet(pkt)
    assert.is_nil(reason)
    assert.is_not_nil(r)
    assert.equal("www.example.com", r.sni)
  end)

  it("formats a fully-populated v6 address in RFC 5952 canonical form", function()
    -- No all-zero runs ⇒ no `::` compression, just trimmed leading zeros.
    local pkt = build_eth_ipv6_tcp({
      payload = build_client_hello({ sni = "a.b" }),
      dst_ip  = {
        0x20, 0x01, 0x0d, 0xb8, 0x85, 0xa3, 0x00, 0x00,
        0x00, 0x00, 0x8a, 0x2e, 0x03, 0x70, 0x73, 0x34,
      },
    })
    local r = sni.parse_packet(pkt)
    assert.is_not_nil(r)
    assert.equal("2001:db8:85a3::8a2e:370:7334", r.dst_ip)
  end)

  it("no longer returns reason=ipv6_skipped for a v6 ClientHello", function()
    local pkt = build_eth_ipv6_tcp({ payload = build_client_hello({ sni = "example.com" }) })
    local r, reason = sni.parse_packet(pkt)
    assert.is_not_nil(r)
    assert.is_nil(reason)
    assert.are_not.equal("ipv6_skipped", reason)
  end)

  it("returns reason=truncated for a v6 frame too short for the fixed header", function()
    local frame = string.rep("\0", 6) ..
                  string.char(0x76, 0x2d, 0x95, 0x47, 0xd1, 0x8e) ..
                  string.char(0x86, 0xdd) .. string.rep("\0", 20)
    local r, reason = sni.parse_packet(frame)
    assert.is_nil(r)
    assert.equal("truncated", reason)
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

-- ---------------------------------------------------------------------------
-- 5. route_line — the dns-tail SNI/dnsmasq routing seam (#573 SHOULD-FIX #1)
-- ---------------------------------------------------------------------------
--
-- wifihaven-dns-tail tails the dnsmasq log AND the SNI capture spool together
-- via `tail -F a b`. Each line is classified by sni.route_line, which:
--   (a) routes "SNI\t..." lines to the injected insert_fn (cache.insert_sni)
--   (b) skips "==> file <==" tail-banner lines emitted on file switch
--   (c) returns false for dnsmasq reply lines so they fall through to the
--       existing dnsmasq handlers (the single dns_cache writer stays dns-tail).
describe("route_line — dns-tail SNI routing seam", function()
  it("routes an SNI line to insert_fn and returns true", function()
    local seen = {}
    local handled = sni.route_line(
      sni.format_sni_line("142.250.80.46", "76:2d:95:47:d1:8e", "example.com"),
      { insert_fn = function(ip, host) seen = { ip = ip, host = host } end })
    assert.is_true(handled)
    assert.equal("142.250.80.46", seen.ip)
    assert.equal("example.com",   seen.host)
  end)

  it("skips a `tail -F` switch-banner line without calling insert_fn", function()
    local calls = 0
    local handled = sni.route_line(
      "==> /tmp/wifihaven-sni.log <==",
      { insert_fn = function() calls = calls + 1 end })
    assert.is_true(handled)        -- consumed (not a dnsmasq line)
    assert.equal(0, calls)         -- but no cache insert
  end)

  it("returns false for a dnsmasq reply line (falls through to dns handlers)", function()
    local calls = 0
    local handled = sni.route_line(
      "Nov 12 10:00:01 dnsmasq[1234]: reply youtube.com is 142.250.80.46",
      { insert_fn = function() calls = calls + 1 end })
    assert.is_false(handled)
    assert.equal(0, calls)
  end)

  it("returns false for an empty / nil line", function()
    assert.is_false(sni.route_line("", { insert_fn = function() end }))
    assert.is_false(sni.route_line(nil, { insert_fn = function() end }))
  end)
end)

-- ---------------------------------------------------------------------------
-- 6. parse_packet failure-reason categorization (#573 SHOULD-FIX #4/#7)
-- ---------------------------------------------------------------------------
--
-- On a nil result, parse_packet returns a bounded reason string as its second
-- value so the sidecar can split the lumped no_sni_total counter into distinct
-- failure modes (truncated / no_sni / malformed / ipv6_skipped). The reason
-- enum is small and fixed; it feeds the result= IPC metrics file.
describe("parse_packet — failure reason (second return value)", function()
  it("returns reason=not_ip for a non-IP ethertype frame (e.g. ARP) — #1652", function()
    -- 0x86dd (IPv6) is no longer skipped (see §3b). ARP (0x0806) and other
    -- non-IP ethertypes still fall out into the bounded catch-all bucket.
    local frame = string.rep("\0", 6) ..
                  string.char(0x76, 0x2d, 0x95, 0x47, 0xd1, 0x8e) ..
                  string.char(0x08, 0x06) .. string.rep("\0", 60)
    local r, reason = sni.parse_packet(frame)
    assert.is_nil(r)
    assert.equal("not_ip", reason)
  end)

  it("returns reason=truncated for a too-short frame", function()
    local r, reason = sni.parse_packet(string.rep("\0", 20))
    assert.is_nil(r)
    assert.equal("truncated", reason)
  end)

  it("returns reason=no_sni for a valid IPv4/TCP packet whose payload has no SNI", function()
    local hello = build_client_hello({ sni = "x", omit_sni_ext = true,
                                       extra_exts = build_key_share_ext(20) })
    local pkt = build_eth_ipv4_tcp({ payload = hello })
    local r, reason = sni.parse_packet(pkt)
    assert.is_nil(r)
    assert.equal("no_sni", reason)
  end)

  it("returns a parsed result with no reason for a good ClientHello", function()
    local pkt = build_eth_ipv4_tcp({ payload = build_client_hello({ sni = "example.com" }) })
    local r, reason = sni.parse_packet(pkt)
    assert.is_not_nil(r)
    assert.equal("example.com", r.sni)
    assert.is_nil(reason)
  end)
end)

-- ---------------------------------------------------------------------------
-- 7. lan_device — probe the LAN bridge from UCI (#573 SHOULD-FIX #3)
-- ---------------------------------------------------------------------------
--
-- The sidecar must not hardcode br-lan. sni.lan_device reads network.lan.device
-- from an injected UCI cursor and falls back to br-lan when unset, so a router
-- whose LAN bridge is named br0 still gets captured.
describe("lan_device — UCI LAN-bridge probe", function()
  local function fake_cursor(dev)
    return { get = function(_, pkg, sec, opt)
      if pkg == "network" and sec == "lan" and opt == "device" then return dev end
      return nil
    end }
  end

  it("returns network.lan.device when set", function()
    assert.equal("br0", sni.lan_device(fake_cursor("br0")))
  end)

  it("falls back to br-lan when network.lan.device is unset", function()
    assert.equal("br-lan", sni.lan_device(fake_cursor(nil)))
  end)

  it("falls back to br-lan when no cursor is available", function()
    assert.equal("br-lan", sni.lan_device(nil))
  end)
end)

-- ---------------------------------------------------------------------------
-- 7b. ECH (Encrypted ClientHello) detection (#1650)
-- ---------------------------------------------------------------------------
--
-- An ECH-enabled ClientHello carries extension type 0xfe0d (RFC
-- draft-ietf-tls-esni). The real server_name is inside the encrypted inner
-- ClientHello; the outer ClientHello may carry a public/outer server_name
-- naming the gateway hostname (best honest attribution we can offer).
--
-- The parser surfaces ECH via a second return value:
--   * outer SNI present  → (outer_name, "ech")    — best-effort attribution
--   * outer SNI absent   → (nil,        "ech")    — counted, not attributed
--   * no ECH extension   → (name|nil,   nil)      — existing behaviour
--
-- parse_packet propagates the flag: on ECH it returns ({...}|nil, "ech") so
-- the sni-tail sidecar buckets the capture under sni_clienthellos_total{result=
-- "ech"} (a metric enum value already reserved by AGENTS.md / #1650).
describe("parse_client_hello — ECH detection (#1650)", function()
  it("returns (outer_name, \"ech\") when ECH ext is present alongside an outer server_name", function()
    -- Outer ClientHello carries both server_name (the gateway hostname) AND
    -- the ECH extension. We attribute via the outer name with an ECH label.
    local rec = build_client_hello({
      sni = "cloudflare-ech.com",
      extra_exts = build_ech_ext(48),
    })
    local name, label = sni.parse_client_hello(rec)
    assert.equal("cloudflare-ech.com", name)
    assert.equal("ech", label)
  end)

  it("returns (nil, \"ech\") when ECH ext is present and no server_name extension is", function()
    local rec = build_client_hello({
      sni = "ignored", omit_sni_ext = true,
      extra_exts = build_ech_ext(40),
    })
    local name, label = sni.parse_client_hello(rec)
    assert.is_nil(name)
    assert.equal("ech", label)
  end)

  it("returns (name, nil) for a non-ECH ClientHello (existing behaviour unchanged)", function()
    local rec = build_client_hello({ sni = "example.com" })
    local name, label = sni.parse_client_hello(rec)
    assert.equal("example.com", name)
    assert.is_nil(label)
  end)
end)

describe("parse_packet — ECH detection (#1650)", function()
  it("returns result and reason=\"ech\" when ECH is present with an outer SNI", function()
    local hello = build_client_hello({
      sni = "cloudflare-ech.com",
      extra_exts = build_ech_ext(48),
    })
    local pkt = build_eth_ipv4_tcp({
      payload = hello,
      src_mac = { 0x76, 0x2d, 0x95, 0x47, 0xd1, 0x8e },
      dst_ip  = { 142, 250, 80, 46 },
    })
    local r, reason = sni.parse_packet(pkt)
    assert.is_not_nil(r)
    assert.equal("cloudflare-ech.com", r.sni)
    assert.equal("142.250.80.46",      r.dst_ip)
    assert.equal("76:2d:95:47:d1:8e",  r.src_mac)
    assert.equal("ech", reason)
  end)

  it("returns nil and reason=\"ech\" when ECH is present with no outer SNI", function()
    local hello = build_client_hello({
      sni = "x", omit_sni_ext = true,
      extra_exts = build_ech_ext(40),
    })
    local pkt = build_eth_ipv4_tcp({ payload = hello })
    local r, reason = sni.parse_packet(pkt)
    assert.is_nil(r)
    assert.equal("ech", reason)
  end)
end)

-- ---------------------------------------------------------------------------
-- 8. <3-char SNI rejected (#573 NIT #3)
-- ---------------------------------------------------------------------------
describe("parse_client_hello — minimum SNI length", function()
  it("rejects a 1-char SNI (no real domain is shorter than a.b)", function()
    assert.is_nil(sni.parse_client_hello(build_client_hello({ sni = "x" })))
  end)

  it("accepts a 3-char SNI (a.b)", function()
    assert.equal("a.b", sni.parse_client_hello(build_client_hello({ sni = "a.b" })))
  end)
end)

-- ---------------------------------------------------------------------------
-- 9. pcap stream reader + full pcap→cache integration (#573)
-- ---------------------------------------------------------------------------
--
-- The wifihaven-sni-tail sidecar reads tcpdump's `-w -` pcap stream: a 24-byte
-- global header (magic 0xa1b2c3d4 / 0xd4c3b2a1 for the two byte orders) then a
-- sequence of 16-byte record headers each followed by incl_len packet bytes.
-- That stream reader used to live as inline local functions in the sidecar and
-- was never exercised by a test. It now lives in sni.lua as injectable, pure
-- functions backed by a read_fn(n) → bytes|nil cursor, so the assembled
-- pipeline (pcap bytes → packets → parse_packet → SNI line → route_line →
-- cache) can be driven in-process with zero router/tcpdump involvement.
--
--   sni.read_global_header(read_fn) → { swapped = bool } | nil
--   sni.read_record(read_fn, swapped) → { data = <packet bytes> } | nil (EOF)
--   sni.iter_pcap(read_fn) → function() returning successive packet payloads
--                            (nil at EOF), after consuming the global header.

-- A read_fn over an in-memory byte string: returns up to n bytes per call and
-- nil at EOF, mirroring the io.popen handle:read(n) contract the sidecar uses.
local function string_reader(s)
  local pos = 1
  return function(n)
    if pos > #s then return nil end
    local chunk = s:sub(pos, pos + n - 1)
    pos = pos + #chunk
    if #chunk == 0 then return nil end
    return chunk
  end
end

-- 32-bit little/big-endian encoders for pcap header fields.
local function u32le(n)
  return string.char(n % 256,
                     math.floor(n / 256) % 256,
                     math.floor(n / 65536) % 256,
                     math.floor(n / 16777216) % 256)
end
local function u32be(n)
  return string.char(math.floor(n / 16777216) % 256,
                     math.floor(n / 65536) % 256,
                     math.floor(n / 256) % 256,
                     n % 256)
end

-- Build a 24-byte pcap global header for a given on-wire byte order.
--
-- The reader (sni.read_global_header) keys off the magic's first byte:
--   byte1 = 0xa1  (magic bytes a1 b2 c3 d4)  → swapped=false → BIG-endian fields
--   byte1 = 0xd4  (magic bytes d4 c3 b2 a1)  → swapped=true  → LITTLE-endian fields
-- i.e. the magic 0xa1b2c3d4 laid down big-endian, or laid down little-endian.
-- The header/record length fields must therefore use the matching encoder.
local function pcap_global_header(swapped)
  local enc, magic
  if swapped then
    magic = string.char(0xd4, 0xc3, 0xb2, 0xa1) -- little-endian on the wire
    enc = u32le
  else
    magic = string.char(0xa1, 0xb2, 0xc3, 0xd4) -- big-endian on the wire
    enc = u32be
  end
  -- version_major(2) version_minor(2) thiszone(4) sigfigs(4) snaplen(4) net(4)
  -- (the reader only inspects the magic; the rest just has to be 24 bytes.)
  local rest = string.char(0, 2) .. string.char(0, 4) ..
               string.rep("\0", 4) .. string.rep("\0", 4) ..
               enc(600) ..
               enc(1)                                   -- LINKTYPE_ETHERNET
  return magic .. rest
end

-- Build a 16-byte pcap record header framing a packet of incl_len bytes, in
-- the matching byte order, followed by the packet bytes themselves.
local function pcap_record(packet, swapped)
  local enc = swapped and u32le or u32be
  local ts_sec, ts_usec = 0, 0
  local incl_len = #packet
  local orig_len = #packet
  local hdr = enc(ts_sec) .. enc(ts_usec) .. enc(incl_len) .. enc(orig_len)
  return hdr .. packet
end

-- Assemble a full pcap stream from a list of Ethernet frames.
local function pcap_stream(frames, swapped)
  local parts = { pcap_global_header(swapped) }
  for _, f in ipairs(frames) do
    parts[#parts + 1] = pcap_record(f, swapped)
  end
  return table.concat(parts)
end

-- Drive the full pipeline assembled from the extracted module functions:
--   read_fn → iter_pcap → parse_packet → format_sni_line → route_line → cache
-- Returns the fake cache (ip→host) plus the count of records iterated.
local function run_pipeline(stream)
  local cache = {}
  local insert_fn = function(ip, host) cache[ip] = host end
  local next_pkt = sni.iter_pcap(string_reader(stream))
  local records = 0
  for pkt in next_pkt do
    records = records + 1
    local r = sni.parse_packet(pkt)
    if r then
      local line = sni.format_sni_line(r.dst_ip, r.src_mac, r.sni)
      sni.route_line(line, { insert_fn = insert_fn })
    end
  end
  return cache, records
end

describe("read_global_header — pcap magic / byte order", function()
  it("detects the native (little-endian magic) byte order", function()
    local gh = sni.read_global_header(string_reader(pcap_global_header(false)))
    assert.is_not_nil(gh)
    assert.is_false(gh.swapped)
  end)

  it("detects the swapped byte order", function()
    local gh = sni.read_global_header(string_reader(pcap_global_header(true)))
    assert.is_not_nil(gh)
    assert.is_true(gh.swapped)
  end)

  it("returns nil when the stream is too short to hold a global header", function()
    assert.is_nil(sni.read_global_header(string_reader("\xa1\xb2")))
  end)
end)

describe("read_record — per-record framing", function()
  it("reads incl_len bytes after the 16-byte header (native order)", function()
    local packet = string.rep("P", 42)
    local stream = pcap_record(packet, false)
    local rec = sni.read_record(string_reader(stream), false)
    assert.is_not_nil(rec)
    assert.equal(packet, rec.data)
  end)

  it("honors the swapped byte order for incl_len", function()
    local packet = string.rep("Q", 17)
    local stream = pcap_record(packet, true)
    local rec = sni.read_record(string_reader(stream), true)
    assert.is_not_nil(rec)
    assert.equal(packet, rec.data)
  end)

  it("returns nil at clean EOF (no record header present)", function()
    assert.is_nil(sni.read_record(string_reader(""), false))
  end)

  it("returns nil on a truncated record header (partial 16-byte header)", function()
    assert.is_nil(sni.read_record(string_reader(string.rep("\0", 8)), false))
  end)

  it("returns nil when incl_len overruns the available bytes", function()
    -- 16-byte header claiming incl_len=100, but only 4 packet bytes follow.
    -- swapped=false → big-endian fields (see pcap_global_header).
    local hdr = u32be(0) .. u32be(0) .. u32be(100) .. u32be(100)
    assert.is_nil(sni.read_record(string_reader(hdr .. "abcd"), false))
  end)
end)

describe("iter_pcap → parse_packet → route_line → cache (full pipeline)", function()
  local function hello_packet(host, mac, ip)
    return build_eth_ipv4_tcp({
      payload = build_client_hello({ sni = host }),
      src_mac = mac,
      dst_ip  = ip,
    })
  end

  it("attributes a single ClientHello's dst_ip to its SNI host (native order)", function()
    local stream = pcap_stream({
      hello_packet("calendar.google.com",
                   { 0x76, 0x2d, 0x95, 0x47, 0xd1, 0x8e }, { 142, 250, 80, 46 }),
    }, false)
    local cache, records = run_pipeline(stream)
    assert.equal(1, records)
    assert.equal("calendar.google.com", cache["142.250.80.46"])
  end)

  it("attributes multiple ClientHellos across records", function()
    local stream = pcap_stream({
      hello_packet("calendar.google.com",
                   { 0x76, 0x2d, 0x95, 0x47, 0xd1, 0x8e }, { 142, 250, 80, 46 }),
      hello_packet("www.youtube.com",
                   { 0xfa, 0x10, 0xcd, 0x84, 0x78, 0x22 }, { 142, 250, 72, 14 }),
    }, false)
    local cache = run_pipeline(stream)
    assert.equal("calendar.google.com", cache["142.250.80.46"])
    assert.equal("www.youtube.com",     cache["142.250.72.14"])
  end)

  it("parses identically under the swapped pcap byte order", function()
    local frames = {
      hello_packet("calendar.google.com",
                   { 0x76, 0x2d, 0x95, 0x47, 0xd1, 0x8e }, { 142, 250, 80, 46 }),
      hello_packet("www.youtube.com",
                   { 0xfa, 0x10, 0xcd, 0x84, 0x78, 0x22 }, { 142, 250, 72, 14 }),
    }
    local native  = run_pipeline(pcap_stream(frames, false))
    local swapped = run_pipeline(pcap_stream(frames, true))
    assert.same(native, swapped)
    assert.equal("calendar.google.com", swapped["142.250.80.46"])
    assert.equal("www.youtube.com",     swapped["142.250.72.14"])
  end)

  it("skips a non-TLS packet without polluting the cache, still draining the stream", function()
    local stream = pcap_stream({
      build_eth_ipv4_tcp({ payload = string.rep("X", 64),       -- not a ClientHello
                           dst_ip = { 10, 0, 0, 9 } }),
      hello_packet("www.youtube.com",
                   { 0xfa, 0x10, 0xcd, 0x84, 0x78, 0x22 }, { 142, 250, 72, 14 }),
    }, false)
    local cache, records = run_pipeline(stream)
    assert.equal(2, records)                       -- both records iterated
    assert.is_nil(cache["10.0.0.9"])               -- non-TLS not attributed
    assert.equal("www.youtube.com", cache["142.250.72.14"])
  end)

  it("terminates cleanly on a truncated trailing record header (no Lua error)", function()
    local good = pcap_stream({
      hello_packet("calendar.google.com",
                   { 0x76, 0x2d, 0x95, 0x47, 0xd1, 0x8e }, { 142, 250, 80, 46 }),
    }, false)
    -- Append a partial (8-byte) record header that snaplen/pipe-close could cut.
    local stream = good .. string.rep("\0", 8)
    local cache, records = run_pipeline(stream)
    assert.equal(1, records)                       -- only the complete record
    assert.equal("calendar.google.com", cache["142.250.80.46"])
  end)

  it("returns nil iterator state when the global header is absent/short", function()
    -- iter_pcap with a stream too short for a global header yields nothing.
    local next_pkt = sni.iter_pcap(string_reader("\xa1\xb2"))
    assert.is_nil(next_pkt())
  end)
end)
