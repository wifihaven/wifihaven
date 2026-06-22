-- Tests for openwrt/files/usr/lib/lua/wifihaven/quic.lua
--
-- quic.lua parses QUIC v1 Initial packets (UDP 443) and extracts the SNI
-- server_name from the TLS ClientHello carried in CRYPTO frames inside the
-- packet's AEAD-encrypted payload (#1651, follow-up to #573). This recovers
-- the hostname an HTTP/3 client actually contacted, restoring attribution
-- for QUIC flows that the v1 TCP-only ClientHello parser would miss.
--
-- The parser takes an injected `crypto` table (hkdf_extract, hkdf_expand_label,
-- aes128_ecb, aes128_gcm_decrypt) so the unit tests can plug in a stub that
-- echoes the RFC 9001 Appendix A.2 test vector inputs back as their known
-- outputs without pulling lua-openssl onto the dev macOS host. The production
-- sidecar wires in a lua-openssl-backed crypto table on the router.
--
-- Run with: busted openwrt/test/quic_spec.lua

local quic = require("quic")

-- ---------------------------------------------------------------------------
-- Hex / byte helpers.
-- ---------------------------------------------------------------------------

local function unhex(h)
  -- strip whitespace then decode
  h = (h:gsub("%s+", ""))
  return (h:gsub("%x%x", function(b) return string.char(tonumber(b, 16)) end))
end

local function hex(s)
  return (s:gsub(".", function(c) return string.format("%02x", c:byte()) end))
end

-- ---------------------------------------------------------------------------
-- RFC 9001 §A.2 "Client Initial" test vector.
-- ---------------------------------------------------------------------------
--
-- DCID                : 8394c8f03e515708
-- HKDF salt (QUIC v1) : 38762cf7f55934b34d179ae6a4c80cadccbb7f0a
-- initial_secret     -> 7db5df06e7a69e432496adedb008519235952215 96ae2ae9fb8115c1e9ed0a44
-- client_initial      : c00cf151ca5be075ed0ebfb5c80323c42d6b7db67881289af4008f1f6c357aea
-- key                 : 1f369613dd76d5467730efcbe3b1a22d
-- iv                  : fa044b2f42a3fd3b46fb255c
-- hp                  : 9f50449e04a0e810283a1e9933adedd2
-- sample              : d1b1c98dd7689fb8ec11d242b123dc9b
-- mask                : 437b9aec36 (first 5 bytes of AES-128-ECB(hp, sample))
-- unprotected header  : c300000001088394c8f03e5157080000449e00000002
-- packet number       : 2 (4-byte encoding)
-- nonce               : iv XOR (0..00 00 00 00 02) = fa044b2f42a3fd3b46fb255e

local RFC_DCID    = unhex("8394c8f03e515708")
local RFC_SALT    = unhex("38762cf7f55934b34d179ae6a4c80cadccbb7f0a")
local RFC_PRK     = unhex("7db5df06e7a69e432496adedb008519235952215" ..
                          "96ae2ae9fb8115c1e9ed0a44")
local RFC_CLIENT_INITIAL = unhex(
  "c00cf151ca5be075ed0ebfb5c80323c42d6b7db67881289af4008f1f6c357aea")
local RFC_KEY    = unhex("1f369613dd76d5467730efcbe3b1a22d")
local RFC_IV     = unhex("fa044b2f42a3fd3b46fb255c")
local RFC_HP     = unhex("9f50449e04a0e810283a1e9933adedd2")
local RFC_SAMPLE = unhex("d1b1c98dd7689fb8ec11d242b123dc9b")
local RFC_MASK5  = unhex("437b9aec36")
local RFC_NONCE  = unhex("fa044b2f42a3fd3b46fb255e")

-- The unprotected payload — a CRYPTO frame carrying a TLS ClientHello with
-- SNI = "example.com" — plus PADDING frames out to 1162 bytes.
local RFC_UNPROTECTED_PAYLOAD = unhex([[
  060040f1010000ed0303ebf8fa56f12939b9584a3896472ec40bb863cfd3e868
  04fe3a47f06a2b69484c000004130113 02010000c000000010000e00000b6578
  616d706c652e636f6dff01000100000a 00080006001d00170018001000070005
  04616c706e0005000501000000000033 00260024001d00209370b2c9caa47fba
  baf4559fedba753de171fa71f50f1ce1 5d43e994ec74d748002b000302030400
  0d0010000e0403050306030203080408 050806002d00020101001c0002400100
  3900320408ffffffffffffffff050480 00ffff07048000ffff08011001048000
  75300901100f088394c8f03e51570806 048000ffff
]]) .. string.rep("\0", 917)  -- pad to 1162 bytes (CRYPTO frame is 245 bytes)

-- The unprotected header (used as AEAD AAD).
local RFC_UNPROTECTED_HEADER =
  unhex("c300000001088394c8f03e5157080000449e00000002")

-- The full protected client Initial packet as it appears on the wire (UDP
-- payload — i.e. starting at the first byte of the QUIC long header). Source:
-- RFC 9001 Appendix A.2.
local RFC_PROTECTED_PACKET = unhex([[
  c000000001088394c8f03e5157080000 449e7b9aec34d1b1c98dd7689fb8ec11
  d242b123dc9bd8bab936b47d92ec356c 0bab7df5976d27cd449f63300099f399
  1c260ec4c60d17b31f8429157bb35a12 82a643a8d2262cad67500cadb8e7378c
  8eb7539ec4d4905fed1bee1fc8aafba1 7c750e2c7ace01e6005f80fcb7df6212
  30c83711b39343fa028cea7f7fb5ff89 eac2308249a02252155e2347b63d58c5
  457afd84d05dfffdb20392844ae81215 4682e9cf012f9021a6f0be17ddd0c208
  4dce25ff9b06cde535d0f920a2db1bf3 62c23e596d11a4f5a6cf3948838a3aec
  4e15daf8500a6ef69ec4e3feb6b1d98e 610ac8b7ec3faf6ad760b7bad1db4ba3
  485e8a94dc250ae3fdb41ed15fb6a8e5 eba0fc3dd60bc8e30c5c4287e53805db
  059ae0648db2f64264ed5e39be2e20d8 2df566da8dd5998ccabdae053060ae6c
  7b4378e846d29f37ed7b4ea9ec5d82e7 961b7f25a9323851f681d582363aa5f8
  9937f5a67258bf63ad6f1a0b1d96dbd4 faddfcefc5266ba6611722395c906556
  be52afe3f565636ad1b17d508b73d874 3eeb524be22b3dcbc2c7468d54119c74
  68449a13d8e3b95811a198f3491de3e7 fe942b330407abf82a4ed7c1b311663a
  c69890f4157015853d91e923037c227a 33cdd5ec281ca3f79c44546b9d90ca00
  f064c99e3dd97911d39fe9c5d0b23a22 9a234cb36186c4819e8b9c5927726632
  291d6a418211cc2962e20fe47feb3edf 330f2c603a9d48c0fcb5699dbfe58964
  25c5bac4aee82e57a85aaf4e2513e4f0 5796b07ba2ee47d80506f8d2c25e50fd
  14de71e6c418559302f939b0e1abd576 f279c4b2e0feb85c1f28ff18f58891ff
  ef132eef2fa09346aee33c28eb130ff2 8f5b766953334113211996d20011a198
  e3fc433f9f2541010ae17c1bf202580f 6047472fb36857fe843b19f5984009dd
  c324044e847a4f4a0ab34f719595de37 252d6235365e9b84392b061085349d73
  203a4a13e96f5432ec0fd4a1ee65accd d5e3904df54c1da510b0ff20dcc0c77f
  cb2c0e0eb605cb0504db87632cf3d8b4 dae6e705769d1de354270123cb11450e
  fc60ac47683d7b8d0f811365565fd98c 4c8eb936bcab8d069fc33bd801b03ade
  a2e1fbc5aa463d08ca19896d2bf59a07 1b851e6c239052172f296bfb5e724047
  90a2181014f3b94a4e97d117b4381303 68cc39dbb2d198065ae3986547926cd2
  162f40a29f0c3c8745c0f50fba3852e5 66d44575c29d39a03f0cda721984b6f4
  40591f355e12d439ff150aab7613499d bd49adabc8676eef023b15b65bfc5ca0
  6948109f23f350db82123535eb8a7433 bdabcb909271a6ecbcb58b936a88cd4e
  8f2e6ff5800175f113253d8fa9ca8885 c2f552e657dc603f252e1a8e308f76f0
  be79e2fb8f5d5fbbe2e30ecadd220723 c8c0aea8078cdfcb3868263ff8f09400
  54da48781893a7e49ad5aff4af300cd8 04a6b6279ab3ff3afb64491c85194aab
  760d58a606654f9f4400e8b38591356f bf6425aca26dc85244259ff2b19c41b9
  f96f3ca9ec1dde434da7d2d392b905dd f3d1f9af93d1af5950bd493f5aa731b4
  056df31bd267b6b90a079831aaf579be 0a39013137aac6d404f518cfd4684064
  7e78bfe706ca4cf5e9c5453e9f7cfd2b 8b4c8d169a44e55c88d4a9a7f9474241
  e221af44860018ab0856972e194cd934
]])

-- ---------------------------------------------------------------------------
-- Stub crypto: returns RFC-known outputs for RFC-known inputs.
--
-- The QUIC parser logic is what the unit tests verify — header parsing, HP
-- mask XOR, AAD construction, nonce derivation, CRYPTO frame reassembly,
-- ClientHello extraction. The AES + HKDF math itself is a library call we
-- don't own; in production the sidecar wires it to lua-openssl. Here the stub
-- asserts on its inputs (so a bug in nonce/AAD/sample construction shows up
-- as an explicit "stub got unexpected input" failure, not a silent nil
-- decryption result).
-- ---------------------------------------------------------------------------

local function stub_crypto()
  return {
    hkdf_extract = function(salt, ikm)
      assert(salt == RFC_SALT, "hkdf_extract: salt mismatch")
      assert(ikm == RFC_DCID, "hkdf_extract: ikm mismatch")
      return RFC_PRK
    end,
    hkdf_expand_label = function(prk, label, context, length)
      assert(prk == RFC_PRK or prk == RFC_CLIENT_INITIAL,
             "hkdf_expand_label: unexpected prk")
      assert(context == "", "hkdf_expand_label: non-empty context")
      if prk == RFC_PRK and label == "client in" and length == 32 then
        return RFC_CLIENT_INITIAL
      elseif prk == RFC_CLIENT_INITIAL and label == "quic key" and length == 16 then
        return RFC_KEY
      elseif prk == RFC_CLIENT_INITIAL and label == "quic iv" and length == 12 then
        return RFC_IV
      elseif prk == RFC_CLIENT_INITIAL and label == "quic hp" and length == 16 then
        return RFC_HP
      end
      error("hkdf_expand_label: unexpected (label=" .. label ..
            ", length=" .. tostring(length) .. ")")
    end,
    aes128_ecb = function(key, sample)
      assert(key == RFC_HP, "aes128_ecb: key mismatch")
      assert(sample == RFC_SAMPLE,
             "aes128_ecb: sample mismatch got=" .. hex(sample))
      -- AES-128-ECB(hp_key, sample) — only the first 5 bytes are used as the
      -- HP mask. We return the full 16-byte block padded with zeros; the
      -- parser MUST only use the first 5 bytes.
      return RFC_MASK5 .. string.rep("\0", 11)
    end,
    aes128_gcm_decrypt = function(key, nonce, aad, ciphertext)
      assert(key == RFC_KEY,
             "aes128_gcm_decrypt: key mismatch got=" .. hex(key))
      assert(nonce == RFC_NONCE,
             "aes128_gcm_decrypt: nonce mismatch got=" .. hex(nonce))
      assert(aad == RFC_UNPROTECTED_HEADER,
             "aes128_gcm_decrypt: AAD mismatch got=" .. hex(aad))
      -- The stub doesn't verify the tag — that's the library's job. It just
      -- returns the known plaintext when inputs match.
      return RFC_UNPROTECTED_PAYLOAD
    end,
  }
end

-- ---------------------------------------------------------------------------
-- Ethernet / IPv4 / UDP framing — minimal builder so we can feed parse_initial
-- a complete eth_frame the way the sidecar's BPF capture will.
-- ---------------------------------------------------------------------------

local function build_eth_ipv4_udp(src_mac_bytes, dst_ip_bytes, udp_payload)
  local dst_mac = string.rep("\0", 6)
  local ethertype = string.char(0x08, 0x00) -- IPv4
  local eth = dst_mac .. src_mac_bytes .. ethertype

  -- IPv4 header (20 bytes, no options)
  local ihl_ver = string.char(0x45)
  local tos     = string.char(0)
  local total   = 20 + 8 + #udp_payload
  local total_b = string.char(math.floor(total / 256), total % 256)
  local idflags = string.char(0, 0, 0x40, 0) -- DF set
  local ttl     = string.char(64)
  local proto   = string.char(17) -- UDP
  local checksum= string.char(0, 0) -- ignored by our parser
  local src_ip  = string.rep("\0", 4)
  local ip      = ihl_ver .. tos .. total_b .. idflags .. ttl .. proto ..
                  checksum .. src_ip .. dst_ip_bytes

  -- UDP header (8 bytes)
  local sport   = string.char(0x80, 0x00) -- 32768
  local dport   = string.char(0x01, 0xbb) -- 443
  local ulen    = 8 + #udp_payload
  local ulen_b  = string.char(math.floor(ulen / 256), ulen % 256)
  local ucksum  = string.char(0, 0) -- optional in IPv4, our parser tolerates 0
  local udp     = sport .. dport .. ulen_b .. ucksum

  return eth .. ip .. udp .. udp_payload
end

-- ---------------------------------------------------------------------------
-- Tests.
-- ---------------------------------------------------------------------------

describe("quic", function()
  describe("parse_quic_packet (UDP payload)", function()
    it("extracts SNI from the RFC 9001 A.2 protected client Initial", function()
      local sni = quic.parse_quic_packet(RFC_PROTECTED_PACKET, stub_crypto())
      assert.are.equal("example.com", sni)
    end)

    it("returns nil/quic_unsupported_version on a v2 long header", function()
      -- Same header shape but version field set to 0x6b3343cf (QUIC v2 RFC
      -- 9369). The salt for v2 is different so we can't reuse the keys; the
      -- parser must reject it cleanly before touching any crypto.
      local v2 = "\192" ..             -- long header, Initial-like form
                 "\107\051\067\207" .. -- version v2
                 "\008" .. RFC_DCID .. -- DCID
                 "\000" ..             -- SCID len 0
                 "\000" ..             -- token len 0
                 "\068\158" ..         -- length (varint, doesn't matter)
                 string.rep("\0", 1200) -- bogus payload
      local sni, reason = quic.parse_quic_packet(v2, stub_crypto())
      assert.is_nil(sni)
      assert.are.equal("quic_unsupported_version", reason)
    end)

    it("returns nil/quic_not_initial on a long header non-Initial (Handshake) packet", function()
      -- Long header, Initial-like prefix but packet-type bits = 0x02 (Handshake)
      -- in the first byte. We can't decrypt Handshake with Initial keys.
      local hs = "\224" ..             -- 1100 0000 → 0xe0 = type=10 (Handshake)
                 "\000\000\000\001" .. -- v1
                 "\008" .. RFC_DCID ..
                 "\000" ..             -- SCID len 0
                 "\068\158" ..         -- length
                 string.rep("\0", 1200)
      local sni, reason = quic.parse_quic_packet(hs, stub_crypto())
      assert.is_nil(sni)
      assert.are.equal("quic_not_initial", reason)
    end)

    it("returns nil/quic_short_header on a short-header (1-RTT) packet", function()
      -- First-byte high bit clear = short header. SNI is only in Initials, so
      -- we silently skip — this is the steady-state case (most QUIC traffic
      -- on UDP 443 is 1-RTT once the handshake settles).
      local short = "\064" .. string.rep("\0", 100)
      local sni, reason = quic.parse_quic_packet(short, stub_crypto())
      assert.is_nil(sni)
      assert.are.equal("quic_short_header", reason)
    end)

    it("returns nil/quic_truncated on a packet shorter than the long header", function()
      local sni, reason = quic.parse_quic_packet("\192\000\000", stub_crypto())
      assert.is_nil(sni)
      assert.are.equal("quic_truncated", reason)
    end)
  end)

  -- ECH (Encrypted ClientHello, #1650) on the QUIC path. The detection itself
  -- lives in sni.parse_client_hello (single-source-of-truth); these tests pin
  -- the QUIC-side wiring — that parse_quic_packet and parse_initial propagate
  -- the second-return label as "quic_ech" so the sni-tail sidecar buckets it
  -- distinctly from plain quic_success.
  describe("parse_quic_packet — ECH detection (#1650)", function()
    -- Build a TLS handshake (no outer record header — QUIC carries the bare
    -- handshake inside CRYPTO frames) for a ClientHello carrying ECH.
    local function u16be(n) return string.char(math.floor(n / 256) % 256, n % 256) end
    local function u24be(n)
      return string.char(math.floor(n / 65536) % 256,
                         math.floor(n / 256) % 256,
                         n % 256)
    end

    local function build_handshake(opts)
      opts = opts or {}
      local random       = string.rep("\0", 32)
      local session_id   = "\0"
      local cipher_suites = u16be(2) .. "\019\001"
      local compression  = "\001\000"
      local exts = ""
      if opts.sni then
        local sn = "\0" .. u16be(#opts.sni) .. opts.sni
        local sn_list = u16be(#sn) .. sn
        exts = exts .. u16be(0x0000) .. u16be(#sn_list) .. sn_list
      end
      if opts.ech then
        local body = string.rep("\0", 32)
        exts = exts .. u16be(0xfe0d) .. u16be(#body) .. body
      end
      local ext_block = u16be(#exts) .. exts
      local body = u16be(0x0303) .. random .. session_id ..
                   cipher_suites .. compression .. ext_block
      return "\001" .. u24be(#body) .. body
    end

    -- Wrap handshake bytes in a single CRYPTO frame (type 0x06, offset 0, len)
    -- and pad to ≥1162 bytes — the QUIC v1 spec requires client Initials to be
    -- ≥1200 wire bytes, but parse_quic_packet's only constraint here is that
    -- the CRYPTO frame sits at offset 0 in the plaintext.
    local function crypto_frame_payload(handshake_bytes)
      local hs_len = #handshake_bytes
      -- 2-byte varint for length: 0x4000 | len when len ≤ 16383.
      local len_varint = string.char(0x40 + math.floor(hs_len / 256), hs_len % 256)
      local frame = "\006" .. "\000" .. len_varint .. handshake_bytes
      local pad = math.max(0, 1162 - #frame)
      return frame .. string.rep("\0", pad)
    end

    -- Stub crypto whose aes128_gcm_decrypt returns a chosen plaintext (the
    -- caller's CRYPTO frame). All other RFC vector assertions stay in place,
    -- so the HP / nonce / AAD construction is still exercised end-to-end.
    local function stub_crypto_with_plaintext(plaintext)
      local s = stub_crypto()
      s.aes128_gcm_decrypt = function(_, _, _, _) return plaintext end
      return s
    end

    it("returns (outer_sni, quic_ech) when the outer ClientHello has ECH + a public SNI", function()
      local hs = build_handshake({ sni = "cloudflare-ech.com", ech = true })
      local sni, reason = quic.parse_quic_packet(
        RFC_PROTECTED_PACKET,
        stub_crypto_with_plaintext(crypto_frame_payload(hs)))
      assert.are.equal("cloudflare-ech.com", sni)
      assert.are.equal("quic_ech", reason)
    end)

    it("returns (nil, quic_ech) when the outer ClientHello has ECH but no public SNI", function()
      local hs = build_handshake({ ech = true })
      local sni, reason = quic.parse_quic_packet(
        RFC_PROTECTED_PACKET,
        stub_crypto_with_plaintext(crypto_frame_payload(hs)))
      assert.is_nil(sni)
      assert.are.equal("quic_ech", reason)
    end)

    it("parse_initial propagates quic_ech with the eth/ipv4/udp framing", function()
      local hs = build_handshake({ sni = "use.tls-ech.dev", ech = true })
      local mac = string.char(0xfa, 0x10, 0xcd, 0x84, 0x78, 0x22)
      local ip  = string.char(104, 16, 132, 229)
      local frame = build_eth_ipv4_udp(mac, ip, RFC_PROTECTED_PACKET)
      local r, reason = quic.parse_initial(
        frame, stub_crypto_with_plaintext(crypto_frame_payload(hs)))
      assert.is_not_nil(r)
      assert.are.equal("104.16.132.229",  r.dst_ip)
      assert.are.equal("fa:10:cd:84:78:22", r.src_mac)
      assert.are.equal("use.tls-ech.dev", r.sni)
      assert.are.equal("quic_ech", reason)
    end)
  end)

  describe("parse_initial (full eth/ipv4/udp frame)", function()
    it("extracts dst_ip, src_mac, sni from a wire-format frame", function()
      local mac = string.char(0xfa, 0x10, 0xcd, 0x84, 0x78, 0x22)
      local ip  = string.char(142, 250, 81, 14) -- 142.250.81.14
      local frame = build_eth_ipv4_udp(mac, ip, RFC_PROTECTED_PACKET)
      local r, reason = quic.parse_initial(frame, stub_crypto())
      assert.is_nil(reason)
      assert.is_not_nil(r)
      assert.are.equal("142.250.81.14", r.dst_ip)
      assert.are.equal("fa:10:cd:84:78:22", r.src_mac)
      assert.are.equal("example.com", r.sni)
    end)

    it("returns truncated on a frame too small for eth+ip+udp", function()
      local _, reason = quic.parse_initial(string.rep("\0", 30), stub_crypto())
      assert.are.equal("quic_truncated", reason)
    end)

    it("returns not_ip on a non-IPv4 ethertype (QUIC is v4-only in v1)", function()
      -- IPv6 ethertype here — sni.parse_packet (#1652) parses v6 itself, but
      -- the QUIC outer-header path is IPv4-only and bounces v6 frames out as
      -- quic_not_ip. The sidecar's dispatch() will route v6 frames to the
      -- TCP path instead of calling parse_initial in practice, so this label
      -- only ticks from direct API callers.
      local f = string.rep("\0", 12) .. "\134\221" .. string.rep("\0", 100)
      local _, reason = quic.parse_initial(f, stub_crypto())
      assert.are.equal("quic_not_ip", reason)
    end)

    it("returns not_udp on a TCP IPv4 packet", function()
      local mac = string.rep("\0", 6)
      local ip  = string.char(1, 2, 3, 4)
      -- Build an eth+ipv4 with proto=6 (TCP) and bogus tail
      local dst_mac = string.rep("\0", 6)
      local ethertype = "\008\000"
      local eth = dst_mac .. mac .. ethertype
      local total = 20 + 20
      local total_b = string.char(math.floor(total / 256), total % 256)
      local hdr = "\069\000" .. total_b .. "\000\000\064\000\064" ..
                  "\006" .. -- proto=TCP
                  "\000\000" .. string.rep("\0", 4) .. ip
      local frame = eth .. hdr .. string.rep("\0", 20)
      local _, reason = quic.parse_initial(frame, stub_crypto())
      assert.are.equal("quic_not_udp", reason)
    end)
  end)
end)
