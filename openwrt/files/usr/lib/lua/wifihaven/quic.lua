-- quic.lua — QUIC v1 Initial-packet SNI parser (#1651, follow-up to #573).
--
-- #573 / sni.lua parses TLS ClientHellos on TCP/443 only. Modern clients
-- (Google/YouTube/most CDNs) increasingly start fresh flows over QUIC on
-- UDP/443, where the SNI sits inside CRYPTO frames in an AEAD-encrypted
-- Initial packet. Without parsing it, the #1636/#1640 collateral-drop class
-- reappears for any QUIC-preferring app: dnsmasq never saw the resolve (the
-- client used DoH / a hard-coded IP) and the TCP capture branch sees no
-- ClientHello to attribute.
--
-- Scope (this PR):
--   * QUIC v1 only (RFC 9000 + RFC 9001). v2 (RFC 9369) and other versions
--     are bucketed under quic_unsupported_version and deferred.
--   * Client Initial packets only — that's where the ClientHello rides. We
--     skip short-header (1-RTT, steady-state) and other long-header types
--     (0-RTT/Handshake/Retry) cleanly.
--   * AEAD_AES_128_GCM only — RFC 9001 §5.1 specifies that Initial packets
--     ALWAYS use AES-128-GCM regardless of what the TLS handshake later
--     negotiates, so no ChaCha20-Poly1305 fallback is needed here.
--   * IPv4 only at the framing layer (parse_initial), matching sni.lua's v1.
--   * Attribution-only (feeds cache.insert_sni alongside the TCP path).
--     Blocking QUIC is #572's job.
--
-- Crypto is injected (see `crypto` parameter docs below) so the parser is
-- pure Lua and unit-testable without lua-openssl on the dev macOS host. The
-- production sidecar wires in a lua-openssl-backed crypto table on the
-- router.
--
-- Public API:
--   quic.parse_quic_packet(udp_payload, crypto)
--     → sni_string | nil, reason
--   quic.parse_initial(eth_frame, crypto)
--     → { dst_ip, src_mac, sni } | nil, reason
--   quic.QUIC_V1_SALT  — HKDF salt for the v1 initial_secret derivation.
--   quic.openssl_crypto(openssl) → crypto table backed by lua-openssl, for
--     the sidecar to wire in.
--
-- Reason enum (bounded — extends sni_clienthellos_total{result}):
--   quic_success            -- parsed, sni present (NOT used by parse_quic_packet
--                              directly; the sidecar emits this counter on the
--                              success path)
--   quic_short_header       -- 1-RTT packet; SNI not present, silently skipped
--   quic_unsupported_version-- not QUIC v1 (e.g. v2 0x6b3343cf, or grease)
--   quic_not_initial        -- long header but not packet-type Initial
--                              (0-RTT/Handshake/Retry); SNI not here
--   quic_truncated          -- packet shorter than required for the long-header
--                              fields or the declared length
--   quic_malformed          -- structurally invalid (bad varint, DCID overrun)
--   quic_decrypt_failed     -- AEAD tag check failed (key/nonce/AAD mismatch
--                              or genuinely malformed protected packet)
--   quic_no_crypto_frame    -- decrypted payload has no CRYPTO frame at offset 0
--                              (e.g. ACK-only Initial)
--   quic_no_sni             -- ClientHello reassembled but no usable host_name
--                              (e.g. ECH)
--   quic_not_ip             -- parse_initial saw a non-IPv4 ethertype (QUIC
--                              outer header is IPv4-only in v1 — v6 QUIC
--                              Initials would land here even though sni.lua
--                              gained v6 support in #1652)
--   quic_not_udp            -- parse_initial saw an IPv4 non-UDP packet
--   quic_not_443            -- parse_initial saw a UDP packet not to port 443
--
-- NOTE: the last three are only reachable when callers invoke parse_initial
-- directly (e.g. the unit tests). The production sidecar's dispatch() peeks
-- at ethertype / IPv4 proto BEFORE calling parse_initial and short-circuits
-- those cases under the TCP-path "not_ip" / generic "malformed" buckets, so
-- they will not tick in the sni_clienthellos_total metric on fleet routers —
-- the documented contract holds for direct API consumers.
--
-- A malformed/truncated packet returns (nil, reason) — never raises. The
-- sidecar runs as a long-lived loop; a single bad packet must not kill it.

local M = {}

-- HKDF salt for the QUIC v1 initial_secret derivation (RFC 9001 §5.2).
-- Stock OpenWrt ships Lua 5.1, whose string literal escapes do NOT include
-- \xNN — only \NNN (decimal) and \\ \" etc. We build the salt with
-- string.char so this file loads identically under 5.1 and 5.5+.
M.QUIC_V1_SALT = string.char(
  0x38, 0x76, 0x2c, 0xf7, 0xf5, 0x59, 0x34, 0xb3, 0x4d, 0x17,
  0x9a, 0xe6, 0xa4, 0xc8, 0x0c, 0xad, 0xcc, 0xbb, 0x7f, 0x0a)

local QUIC_V1_VERSION = string.char(0x00, 0x00, 0x00, 0x01)

-- ---------------------------------------------------------------------------
-- Byte / bit helpers — Lua 5.1-safe (no string.unpack, no bitwise operators).
-- ---------------------------------------------------------------------------

local function u8(buf, off)
  if off < 1 or off > #buf then return nil end
  return buf:byte(off)
end

local function u16(buf, off)
  if off < 1 or off + 1 > #buf then return nil end
  local a, b = buf:byte(off, off + 1)
  return a * 256 + b
end

local function slice(buf, off, len)
  if off < 1 or len < 0 or (off + len - 1) > #buf then return nil end
  return buf:sub(off, off + len - 1)
end

-- bit_and / bit_xor / bit_lshift / bit_rshift — Lua 5.1-portable. The agent
-- already mixes bit32 and arithmetic across modules; we stick to arithmetic
-- here so the file loads under stock Lua 5.1 on the router with no extra
-- dependency.
local function band(a, b)
  -- 8-bit only — that's all we ever need (byte / mask).
  local r, bit = 0, 1
  for i = 0, 7 do
    if (a % 2 == 1) and (b % 2 == 1) then r = r + bit end
    a = math.floor(a / 2); b = math.floor(b / 2); bit = bit * 2
  end
  return r
end

local function bxor(a, b)
  local r, bit = 0, 1
  for i = 0, 7 do
    local ab = a % 2; local bb = b % 2
    if ab ~= bb then r = r + bit end
    a = math.floor(a / 2); b = math.floor(b / 2); bit = bit * 2
  end
  return r
end

-- xor_bytes(a, b) — byte-wise XOR over the shorter of the two strings.
local function xor_bytes(a, b)
  local n = math.min(#a, #b)
  local out = {}
  for i = 1, n do
    out[i] = string.char(bxor(a:byte(i), b:byte(i)))
  end
  return table.concat(out)
end

-- ---------------------------------------------------------------------------
-- QUIC variable-length integer (RFC 9000 §16).
--
-- The first byte's two MSBs determine the encoded length:
--   00 → 1 byte  (6-bit value, 0..2^6-1)
--   01 → 2 bytes (14-bit value)
--   10 → 4 bytes (30-bit value)
--   11 → 8 bytes (62-bit value)
-- Returns (value, byte_count) or (nil) if truncated.
-- ---------------------------------------------------------------------------

local VARINT_LEN = { [0] = 1, [1] = 2, [2] = 4, [3] = 8 }

local function read_varint(buf, off)
  local first = u8(buf, off)
  if not first then return nil end
  local len = VARINT_LEN[math.floor(first / 64)]
  if off + len - 1 > #buf then return nil end
  local val = first % 64
  for i = 1, len - 1 do
    val = val * 256 + buf:byte(off + i)
  end
  return val, len
end

-- ---------------------------------------------------------------------------
-- Long-header parsing — RFC 9000 §17.2.
--
-- Returns a table with the fields below on success, or (nil, reason) on
-- malformed/truncated/non-Initial input. The result is everything we need to
-- (a) compute the sample offset for header-protection removal and (b)
-- reconstruct the AAD/nonce after HP removal:
--   dcid                : raw bytes (used to derive the initial keys)
--   length              : the QUIC "length" field — PN + payload + tag bytes
--   pn_offset           : 1-based index of first packet-number byte in `buf`
--
-- SCID and the token bytes ARE parsed (their varint-prefixed lengths drive
-- where `pn_offset` lands) but we don't surface them on the returned table
-- because no caller reads them — only the dcid drives key derivation and
-- only `length` / `pn_offset` drive HP-removal / AEAD bookkeeping.
-- ---------------------------------------------------------------------------

local function parse_long_header(buf)
  if #buf < 7 then return nil, "quic_truncated" end
  local first = buf:byte(1)
  -- Long header has the high bit set. Short header → silently skip.
  if first < 0x80 then return nil, "quic_short_header" end
  -- Fixed bit (bit 6) MUST be set on a valid QUIC packet. Some path-MTU
  -- discovery / grease packets may clear it; treat as malformed.
  if band(first, 0x40) == 0 then return nil, "quic_malformed" end

  -- Version (unprotected).
  local version = slice(buf, 2, 4)
  if not version then return nil, "quic_truncated" end
  if version ~= QUIC_V1_VERSION then return nil, "quic_unsupported_version" end

  -- Long packet type lives in bits 5..4 of the first byte. Per RFC 9001
  -- §5.4.1, header protection masks only the LOW 4 bits of the first byte
  -- (reserved bits + PN length); the type bits are UNPROTECTED, so we can
  -- read them directly. For QUIC v1, type=0 = Initial.
  local pkt_type = math.floor(band(first, 0x30) / 16)
  if pkt_type ~= 0 then return nil, "quic_not_initial" end

  -- DCID
  local off = 6
  local dcid_len = u8(buf, off); if not dcid_len then return nil, "quic_truncated" end
  if dcid_len > 20 then return nil, "quic_malformed" end
  off = off + 1
  local dcid = slice(buf, off, dcid_len)
  if not dcid then return nil, "quic_truncated" end
  off = off + dcid_len

  -- SCID (length-prefixed; bytes themselves skipped, not surfaced)
  local scid_len = u8(buf, off); if not scid_len then return nil, "quic_truncated" end
  if scid_len > 20 then return nil, "quic_malformed" end
  off = off + 1 + scid_len
  if off > #buf + 1 then return nil, "quic_truncated" end

  -- Initial-only: token_length (varint) + token bytes (skipped)
  local token_len, vlen = read_varint(buf, off)
  if not token_len then return nil, "quic_truncated" end
  off = off + vlen + token_len
  if off > #buf + 1 then return nil, "quic_truncated" end

  -- Length: bytes of PN + payload + 16-byte AEAD tag
  local length
  length, vlen = read_varint(buf, off)
  if not length then return nil, "quic_truncated" end
  off = off + vlen

  -- `off` now points at the first byte of the (protected) packet number.
  -- Verify the declared length doesn't run off the end of the buffer.
  if off + length - 1 > #buf then return nil, "quic_truncated" end
  -- AEAD tag is 16 bytes, so length must be at least 17 (≥1 byte PN + tag).
  if length < 17 then return nil, "quic_malformed" end

  return {
    dcid      = dcid,
    length    = length,
    pn_offset = off,
  }
end

-- ---------------------------------------------------------------------------
-- Initial-key derivation — RFC 9001 §5.2.
--   initial_secret  = HKDF-Extract(QUIC_V1_SALT, DCID)
--   client_initial  = HKDF-Expand-Label(initial_secret, "client in", "", 32)
--   key             = HKDF-Expand-Label(client_initial, "quic key", "", 16)
--   iv              = HKDF-Expand-Label(client_initial, "quic iv",  "", 12)
--   hp              = HKDF-Expand-Label(client_initial, "quic hp",  "", 16)
-- We only need the CLIENT keys (we're attributing client-originated flows).
-- ---------------------------------------------------------------------------

local function derive_client_initial_keys(dcid, crypto)
  local prk = crypto.hkdf_extract(M.QUIC_V1_SALT, dcid)
  local client_initial = crypto.hkdf_expand_label(prk, "client in", "", 32)
  return {
    key = crypto.hkdf_expand_label(client_initial, "quic key", "", 16),
    iv  = crypto.hkdf_expand_label(client_initial, "quic iv",  "", 12),
    hp  = crypto.hkdf_expand_label(client_initial, "quic hp",  "", 16),
  }
end

-- ---------------------------------------------------------------------------
-- Header-protection removal — RFC 9001 §5.4.
--
-- The HP sample is the 16 bytes starting at pn_offset + 4 in the protected
-- packet. (The "+ 4" assumes a maximum 4-byte packet number; we always sample
-- from that offset regardless of the actual PN length.) The mask is the first
-- 5 bytes of AES-128-ECB(hp_key, sample). For long headers, mask[0] is masked
-- with 0x0f and XORed into the first byte's low 4 bits (reserved bits + PN
-- length); mask[1..pn_len] XORs the packet-number bytes.
--
-- Returns the unprotected first byte, the truncated packet number (integer),
-- and the packet-number length in bytes, or (nil, reason) on failure.
-- ---------------------------------------------------------------------------

local function remove_header_protection(buf, pn_offset, hp_key, crypto)
  local sample_off = pn_offset + 4
  local sample = slice(buf, sample_off, 16)
  if not sample then return nil, "quic_truncated" end

  local mask = crypto.aes128_ecb(hp_key, sample)
  if not mask or #mask < 5 then return nil, "quic_decrypt_failed" end

  local protected_first = buf:byte(1)
  local unprotected_first = bxor(protected_first, band(mask:byte(1), 0x0f))
  local pn_len = (unprotected_first % 4) + 1

  -- XOR the packet-number bytes with mask[2..1+pn_len].
  local pn_value = 0
  for i = 1, pn_len do
    local b = bxor(buf:byte(pn_offset + i - 1), mask:byte(i + 1))
    pn_value = pn_value * 256 + b
  end

  return {
    first    = unprotected_first,
    pn_len   = pn_len,
    pn_value = pn_value,
  }
end

-- ---------------------------------------------------------------------------
-- Nonce + AAD construction — RFC 9001 §5.3 / RFC 5116.
--
-- The 12-byte AEAD nonce is the IV XORed with the packet-number left-padded
-- to 12 bytes (big-endian). The AAD is the *unprotected* header bytes — the
-- entire long-header prefix up through and including the packet number.
-- ---------------------------------------------------------------------------

local function build_nonce(iv, pn_value)
  local pn_bytes = {}
  for i = 12, 1, -1 do
    pn_bytes[i] = string.char(pn_value % 256)
    pn_value = math.floor(pn_value / 256)
  end
  return xor_bytes(iv, table.concat(pn_bytes))
end

local function build_aad(buf, pn_offset, unprotected_first, pn_len, pn_value)
  -- Header bytes 2..(pn_offset-1) are unchanged. The first byte is replaced
  -- with unprotected_first, and the pn_len bytes starting at pn_offset are
  -- replaced with the big-endian encoding of the truncated PN.
  local mid = slice(buf, 2, pn_offset - 2) or ""
  local pn_bytes = {}
  for i = pn_len, 1, -1 do
    pn_bytes[i] = string.char(pn_value % 256)
    pn_value = math.floor(pn_value / 256)
  end
  return string.char(unprotected_first) .. mid .. table.concat(pn_bytes)
end

-- ---------------------------------------------------------------------------
-- CRYPTO frame reassembly — RFC 9000 §19.6 (plus the other frame types
-- allowed in Initial packets per RFC 9000 §17.2.2: PADDING 0x00, PING 0x01,
-- ACK 0x02/0x03, CRYPTO 0x06, CONNECTION_CLOSE 0x1c).
--
-- We collect every CRYPTO frame's (offset, data) and concatenate in offset
-- order to recover the TLS bytes. In practice the entire ClientHello fits in
-- one CRYPTO frame at offset 0 inside a single Initial packet (the RFC 9001
-- §A.2 vector does), but the spec allows fragmentation and a future endpoint
-- might split.
--
-- Returns the reassembled TLS bytes (starting at offset 0) or nil on a
-- malformed payload / no CRYPTO frame.
-- ---------------------------------------------------------------------------

local function reassemble_crypto(payload)
  local off = 1
  local chunks = {}  -- list of {offset, data}
  while off <= #payload do
    local frame_type, vlen = read_varint(payload, off)
    if not frame_type then return nil end
    off = off + vlen

    if frame_type == 0x00 then
      -- PADDING — single-byte frame, the byte we just read IS the frame.
      -- Many implementations write large runs of PADDING; loop here so we
      -- don't pay an O(N) varint read per padding byte. (No-op in the loop
      -- body — off already advanced past it.)
    elseif frame_type == 0x01 then
      -- PING — single byte, already consumed.
    elseif frame_type == 0x02 or frame_type == 0x03 then
      -- ACK frame: largest_acked, ack_delay, ack_range_count, first_ack_range,
      -- then ack_range_count gap/range pairs; if 0x03, three ECN counts.
      local fields_to_skip = 4 -- largest_acked, ack_delay, ack_range_count, first_ack_range
      local ack_range_count
      local v
      for i = 1, fields_to_skip do
        v, vlen = read_varint(payload, off)
        if not v then return nil end
        if i == 3 then ack_range_count = v end
        off = off + vlen
      end
      for _ = 1, (ack_range_count or 0) do
        v, vlen = read_varint(payload, off)
        if not v then return nil end
        off = off + vlen
        v, vlen = read_varint(payload, off)
        if not v then return nil end
        off = off + vlen
      end
      if frame_type == 0x03 then
        for _ = 1, 3 do
          v, vlen = read_varint(payload, off)
          if not v then return nil end
          off = off + vlen
        end
      end
    elseif frame_type == 0x06 then
      -- CRYPTO frame: offset (varint), length (varint), data
      local crypto_off
      crypto_off, vlen = read_varint(payload, off); if not crypto_off then return nil end
      off = off + vlen
      local crypto_len
      crypto_len, vlen = read_varint(payload, off); if not crypto_len then return nil end
      off = off + vlen
      local data = slice(payload, off, crypto_len)
      if not data then return nil end
      off = off + crypto_len
      chunks[#chunks + 1] = { offset = crypto_off, data = data }
    elseif frame_type == 0x1c or frame_type == 0x1d then
      -- CONNECTION_CLOSE — error_code (v), [frame_type (v) if 0x1c],
      -- reason_phrase_length (v), reason. We won't see ClientHello after one
      -- of these; bail with whatever CRYPTO we've already gathered.
      break
    else
      -- Unknown frame type in an Initial packet. RFC 9000 §12.4 mandates
      -- PROTOCOL_VIOLATION here, but for attribution we just stop walking.
      break
    end
  end

  if #chunks == 0 then return nil end

  -- Sort by offset and concatenate. For the common single-frame case this is
  -- a no-op. Gaps in coverage (e.g. an offset-0 frame missing) mean we can't
  -- safely feed the TLS parser; bail.
  table.sort(chunks, function(a, b) return a.offset < b.offset end)
  if chunks[1].offset ~= 0 then return nil end
  local expected = 0
  local out = {}
  for _, c in ipairs(chunks) do
    if c.offset ~= expected then return nil end -- gap
    out[#out + 1] = c.data
    expected = expected + #c.data
  end
  return table.concat(out)
end

-- ---------------------------------------------------------------------------
-- TLS-record framing wrapper around sni.parse_client_hello.
--
-- The TLS ClientHello we extract from CRYPTO frames is in TLS HANDSHAKE form
-- (no outer TLS record header) — it starts directly with the Handshake.type
-- (0x01 ClientHello) and a 3-byte length. sni.parse_client_hello expects a
-- TLS Plaintext record (0x16 || version || length || handshake...), so we
-- prepend the synthetic record header before delegating. This preserves
-- single-source-of-truth: the SNI extension parser lives in sni.lua and is
-- the same code path the TCP capture uses.
-- ---------------------------------------------------------------------------

local function parse_tls_handshake(handshake_bytes)
  -- Lazy require so unit tests that don't exercise the success path don't
  -- need sni.lua on the search path. Try the production-namespaced form
  -- first and fall back to the bare module name the test harness puts on
  -- LUA_PATH (./files/usr/lib/lua/wifihaven/?.lua) so the same file works
  -- in both environments.
  local ok, sni = pcall(require, "wifihaven.sni")
  if not ok then sni = require("sni") end
  if #handshake_bytes < 4 then return nil end
  -- Synthetic record header: ContentType=0x16, Version=0x0303, Length.
  local rec_len = #handshake_bytes
  local rec_hdr = string.char(0x16, 0x03, 0x03,
                              math.floor(rec_len / 256) % 256,
                              rec_len % 256)
  return sni.parse_client_hello(rec_hdr .. handshake_bytes)
end

-- ---------------------------------------------------------------------------
-- parse_quic_packet(udp_payload, crypto) → sni | nil, reason
-- ---------------------------------------------------------------------------

function M.parse_quic_packet(udp_payload, crypto)
  if type(udp_payload) ~= "string" or #udp_payload < 7 then
    return nil, "quic_truncated"
  end

  local hdr, reason = parse_long_header(udp_payload)
  if not hdr then return nil, reason end

  local keys = derive_client_initial_keys(hdr.dcid, crypto)

  local hp_result, hp_reason = remove_header_protection(
    udp_payload, hdr.pn_offset, keys.hp, crypto)
  if not hp_result then return nil, hp_reason end

  local header_len = hdr.pn_offset - 1 + hp_result.pn_len
  local ct_len = hdr.length - hp_result.pn_len -- ciphertext + 16-byte tag
  local ciphertext = slice(udp_payload, header_len + 1, ct_len)
  if not ciphertext then return nil, "quic_truncated" end

  local nonce = build_nonce(keys.iv, hp_result.pn_value)
  local aad = build_aad(udp_payload, hdr.pn_offset,
                        hp_result.first, hp_result.pn_len, hp_result.pn_value)

  local plaintext = crypto.aes128_gcm_decrypt(keys.key, nonce, aad, ciphertext)
  if not plaintext then return nil, "quic_decrypt_failed" end

  local tls_bytes = reassemble_crypto(plaintext)
  if not tls_bytes then return nil, "quic_no_crypto_frame" end

  local sni_str = parse_tls_handshake(tls_bytes)
  if not sni_str then return nil, "quic_no_sni" end

  return sni_str
end

-- ---------------------------------------------------------------------------
-- parse_initial(eth_frame, crypto) → { dst_ip, src_mac, sni } | nil, reason
--
-- Ethernet/IPv4/UDP framing wrapper, mirroring sni.parse_packet's contract.
-- v1: IPv4 only (no VLAN, no IPv6, no IP options beyond the standard 20B).
-- ---------------------------------------------------------------------------

local function format_mac(a, b, c, d, e, f)
  return string.format("%02x:%02x:%02x:%02x:%02x:%02x", a, b, c, d, e, f)
end

function M.parse_initial(eth_frame, crypto)
  if type(eth_frame) ~= "string" or #eth_frame < 14 + 20 + 8 then
    return nil, "quic_truncated"
  end

  local ethertype = u16(eth_frame, 13)
  if ethertype ~= 0x0800 then return nil, "quic_not_ip" end
  local sa, sb, sc, sd, se, sf = eth_frame:byte(7, 12)
  local src_mac = format_mac(sa, sb, sc, sd, se, sf)

  local ip_off = 15
  local ver_ihl = u8(eth_frame, ip_off)
  if not ver_ihl or math.floor(ver_ihl / 16) ~= 4 then
    return nil, "quic_malformed"
  end
  local ihl = (ver_ihl % 16) * 4
  if ihl < 20 then return nil, "quic_malformed" end
  if ip_off + ihl - 1 > #eth_frame then return nil, "quic_truncated" end

  local proto = u8(eth_frame, ip_off + 9)
  if proto ~= 17 then return nil, "quic_not_udp" end

  local da, db, dc, dd = eth_frame:byte(ip_off + 16, ip_off + 19)
  local dst_ip = string.format("%d.%d.%d.%d", da, db, dc, dd)

  local udp_off = ip_off + ihl
  if udp_off + 8 - 1 > #eth_frame then return nil, "quic_truncated" end
  local dport = u16(eth_frame, udp_off + 2)
  if dport ~= 443 then return nil, "quic_not_443" end

  local payload_off = udp_off + 8
  if payload_off > #eth_frame then return nil, "quic_truncated" end
  local udp_payload = eth_frame:sub(payload_off)

  local sni_str, reason = M.parse_quic_packet(udp_payload, crypto)
  if not sni_str then return nil, reason end
  return { dst_ip = dst_ip, src_mac = src_mac, sni = sni_str }
end

-- ---------------------------------------------------------------------------
-- openssl_crypto(openssl) — build the crypto table the parser expects, backed
-- by lua-openssl. Called by the sidecar at startup. Kept here (rather than in
-- the sidecar script) so the binding lives next to the parser that defines
-- the contract — `crypto` shape changes touch one file.
--
-- HKDF Extract is HMAC-SHA256(salt, IKM); HKDF Expand follows RFC 5869 §2.3
-- (T(1) = HMAC(PRK, info || 0x01); T(i) = HMAC(PRK, T(i-1) || info || i)).
-- HKDF-Expand-Label adds the TLS 1.3 §7.1 label framing.
-- ---------------------------------------------------------------------------

function M.openssl_crypto(openssl)
  local function hmac_sha256(key, msg)
    return openssl.hmac.hmac("sha256", msg, key, true)
  end
  local function hkdf_extract(salt, ikm)
    return hmac_sha256(salt, ikm)
  end
  local function hkdf_expand(prk, info, length)
    local out = {}
    local t = ""
    local n = math.ceil(length / 32)
    for i = 1, n do
      t = hmac_sha256(prk, t .. info .. string.char(i))
      out[#out + 1] = t
    end
    return table.concat(out):sub(1, length)
  end
  local function hkdf_expand_label(prk, label, context, length)
    local full = "tls13 " .. label
    local info = string.char(math.floor(length / 256) % 256, length % 256) ..
                 string.char(#full) .. full ..
                 string.char(#context) .. context
    return hkdf_expand(prk, info, length)
  end

  local aes_ecb = openssl.cipher.get("aes-128-ecb")
  local aes_gcm = openssl.cipher.get("aes-128-gcm")

  return {
    hkdf_extract       = hkdf_extract,
    hkdf_expand_label  = hkdf_expand_label,
    aes128_ecb = function(key, sample)
      -- lua-openssl encrypt(plaintext, key, iv, padding) — pass padding=false
      -- so we get exactly the 16-byte block back with no PKCS#7 trailer.
      local ok, ct = pcall(function() return aes_ecb:encrypt(sample, key, nil, false) end)
      if not ok then return nil end
      return ct
    end,
    aes128_gcm_decrypt = function(key, nonce, aad, ciphertext)
      if #ciphertext < 16 then return nil end
      local ct = ciphertext:sub(1, #ciphertext - 16)
      local tag = ciphertext:sub(#ciphertext - 15)
      local ok, pt = pcall(function()
        local dc = aes_gcm:decrypt_new(key, nonce)
        dc:update(aad, true) -- AAD-only update; second arg = true
        local out = dc:update(ct)
        -- EVP_CTRL_AEAD_SET_TAG = 0x11; sets the expected tag BEFORE final().
        dc:ctrl(0x11, tag)
        local final = dc:final() or ""
        return out .. final
      end)
      if not ok then return nil end
      return pt
    end,
  }
end

return M
