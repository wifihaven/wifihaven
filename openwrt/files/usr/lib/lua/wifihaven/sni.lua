-- sni.lua — TLS ClientHello SNI parser + Ethernet/IPv4/TCP framing helper.
--
-- The wifihaven-sni-tail sidecar captures the first packet of every outbound
-- TCP/443 flow off the LAN bridge and parses the TLS ClientHello to extract
-- the SNI server_name (#573). This recovers the hostname an app actually
-- contacted even when the client used DoH/DoT to resolve the name (so dnsmasq
-- never saw the query) or hard-coded the IP. The resulting (dst_ip, sni) pair
-- is fed back into the same dns_log cache the agent already reads for flow
-- attribution, so downstream eb_/ea_/bl_ matching, event labeling, and
-- conntrack-event fqdn emission all flow through unchanged.
--
-- v1 scope (#573):
--   - TLS 1.2 and TLS 1.3 ClientHello, single TCP segment (BPF snaplen=600).
--   - Single Ethernet+IPv4+TCP packet (no IPv4 options, no VLAN tag, no IPv6).
--   - server_name extension type 0x0000, name_type 0x00 (host_name).
--
-- Explicitly out of scope (followups, see #573 PR body):
--   - Encrypted ClientHello (ECH) — parser returns nil.
--   - QUIC / HTTP/3 Initial-packet SNI parsing.
--   - TLS 1.2 fragmented ClientHello reassembly across TCP segments.
--   - IPv6 outer header (followup; first-pass keeps the byte-walk simple).
--
-- Public API:
--   sni.parse_client_hello(payload)   → server_name string | nil
--   sni.parse_packet(eth_frame_bytes) → { dst_ip, src_mac, sni } | nil
--   sni.format_sni_line(ip, mac, sni) → "SNI\t<ip>\t<mac>\t<sni>\n"
--   sni.parse_sni_line(line)          → { dst_ip, src_mac, sni } | nil
--   sni.read_global_header(read_fn)   → { swapped } | nil   (pcap stream)
--   sni.read_record(read_fn, swapped) → { data } | nil      (pcap stream)
--   sni.iter_pcap(read_fn)            → iterator of packet bytes
--
-- Defensive style: every length field is checked against the remaining
-- payload before being trusted. A malformed record returns nil; it never
-- raises a Lua error (the sidecar must keep running through any payload).

local M = {}

-- ---------------------------------------------------------------------------
-- Byte helpers — Lua 5.1-safe (no string.unpack).
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

local function u24(buf, off)
  if off < 1 or off + 2 > #buf then return nil end
  local a, b, c = buf:byte(off, off + 2)
  return a * 65536 + b * 256 + c
end

-- Bounds-checked substring: returns nil if [off, off+len-1] is out of range.
local function slice(buf, off, len)
  if off < 1 or len < 0 or (off + len - 1) > #buf then return nil end
  return buf:sub(off, off + len - 1)
end

-- ---------------------------------------------------------------------------
-- TLS ClientHello parser.
-- ---------------------------------------------------------------------------

-- parse_client_hello(payload) → server_name string | nil
--
-- payload is the raw bytes of the TCP segment starting at the TLS record.
-- Returns the server_name from the SNI extension when present, or nil for
-- any of: non-handshake content type, non-ClientHello handshake, no SNI
-- extension, malformed/truncated record, name_type != host_name (0x00).
function M.parse_client_hello(payload)
  if type(payload) ~= "string" or #payload < 5 then return nil end

  -- TLS record header
  local content_type = u8(payload, 1)
  if content_type ~= 0x16 then return nil end -- not handshake
  local record_len = u16(payload, 4)
  if not record_len then return nil end
  -- NB: we deliberately do NOT reject when the declared record length exceeds
  -- the captured buffer. Real OpenSSL/curl TLS 1.3 ClientHellos are padded past
  -- ~512 bytes, so under the capture snaplen the tail (large key_share /
  -- padding extensions) is clipped — but the server_name extension sits near
  -- the FRONT and is therefore fully present in the captured prefix. Rejecting
  -- the whole record on declared-vs-captured length was the bug the Gate 2 e2e
  -- caught (every real ClientHello fell out as "no_sni"). The extension walk
  -- below is bounded by the actual buffer via the per-field u8/u16/slice reads
  -- (each returns nil past end-of-buffer), so a SNI beyond the captured bytes
  -- still yields nil — we just no longer discard one that IS present.

  -- Handshake header (inside the record)
  local hs_type = u8(payload, 6)
  if hs_type ~= 0x01 then return nil end -- not ClientHello
  local hs_len = u24(payload, 7)
  if not hs_len then return nil end

  -- ClientHello body starts at offset 10
  local off = 10
  local end_off = 10 + hs_len -- one past last byte of body

  -- legacy_version (2) + random (32) = 34 bytes
  off = off + 34
  if off > end_off then return nil end

  -- legacy_session_id: u8 length + bytes
  local sid_len = u8(payload, off); if not sid_len then return nil end
  off = off + 1 + sid_len
  if off > end_off then return nil end

  -- cipher_suites: u16 length + bytes
  local cs_len = u16(payload, off); if not cs_len then return nil end
  off = off + 2 + cs_len
  if off > end_off then return nil end

  -- compression_methods: u8 length + bytes
  local cm_len = u8(payload, off); if not cm_len then return nil end
  off = off + 1 + cm_len
  if off > end_off then return nil end

  -- extensions: u16 total length + extension records
  local ext_total = u16(payload, off); if not ext_total then return nil end
  off = off + 2
  local ext_end = off + ext_total
  if ext_end > end_off then return nil end

  -- Walk extension records
  while off + 4 <= ext_end do
    local ext_type = u16(payload, off);     if not ext_type then return nil end
    local ext_len  = u16(payload, off + 2); if not ext_len  then return nil end
    local ext_body_off = off + 4
    local ext_body_end = ext_body_off + ext_len
    if ext_body_end > ext_end then return nil end

    if ext_type == 0x0000 then -- server_name
      -- ServerNameList: u16 list length + entries
      if ext_body_off + 2 > ext_body_end then return nil end
      local sn_list_len = u16(payload, ext_body_off)
      if not sn_list_len then return nil end
      local list_off = ext_body_off + 2
      local list_end = list_off + sn_list_len
      if list_end > ext_body_end then return nil end

      -- First entry only — RFC 6066 §3 says list MAY contain multiple but
      -- in practice it's always one host_name. We'll take the first
      -- host_name entry we find.
      while list_off + 3 <= list_end do
        local name_type = u8(payload, list_off);     if not name_type then return nil end
        local sn_len    = u16(payload, list_off + 1); if not sn_len    then return nil end
        local sn_off    = list_off + 3
        if sn_off + sn_len > list_end then return nil end
        if name_type == 0x00 then -- host_name
          local sn = slice(payload, sn_off, sn_len)
          -- A real SNI is a domain; the shortest plausible one is "a.b" (3
          -- chars). Anything shorter is malformed — reject it (the value is
          -- only ever used as a cache key, but we don't want junk keys).
          if sn and #sn >= 3 then return sn end
          return nil
        end
        list_off = sn_off + sn_len
      end
      return nil -- SNI extension present but no host_name entry
    end

    off = ext_body_end
  end

  return nil
end

-- ---------------------------------------------------------------------------
-- Ethernet / IPv4 / TCP framing.
--
-- v1 handles untagged IPv4 over Ethernet, no VLAN, no IP options beyond the
-- standard 20-byte header (we honor IHL though). IPv6 is a followup.
-- ---------------------------------------------------------------------------

local function format_mac(a, b, c, d, e, f)
  return string.format("%02x:%02x:%02x:%02x:%02x:%02x", a, b, c, d, e, f)
end

-- parse_packet(eth_frame) → { dst_ip, src_mac, sni } | nil, reason
--
-- On failure returns (nil, reason) where reason is a bounded enum the sidecar
-- folds into a result= counter (see SHOULD-FIX #4/#7 — split the lumped
-- no_sni_total):
--   "truncated"    — frame too short to hold Ethernet+IPv4+TCP, or the TLS
--                    record/handshake length runs past the captured bytes
--                    (the common snaplen-cut case).
--   "ipv6_skipped" — non-IPv4 ethertype (v1 is IPv4-only; counts the dual-
--                    stack traffic we're missing).
--   "not_tcp"      — IPv4 but not TCP (shouldn't happen given the BPF, but
--                    bounded and cheap to distinguish).
--   "malformed"    — structurally invalid IPv4/TCP framing.
--   "no_sni"       — well-formed packet but the ClientHello carried no usable
--                    host_name (no SNI extension, ECH, or a name we rejected).
function M.parse_packet(eth_frame)
  if type(eth_frame) ~= "string" or #eth_frame < 14 + 20 + 20 then
    return nil, "truncated"
  end

  -- Ethernet header: dst (6), src (6), ethertype (2)
  local ethertype = u16(eth_frame, 13)
  if ethertype ~= 0x0800 then return nil, "ipv6_skipped" end -- IPv4 only in v1
  local sa, sb, sc, sd, se, sf = eth_frame:byte(7, 12)
  local src_mac = format_mac(sa, sb, sc, sd, se, sf)

  -- IPv4 header
  local ip_off = 15 -- 1-based, after 14-byte Ethernet
  local ver_ihl = u8(eth_frame, ip_off)
  if not ver_ihl then return nil, "malformed" end
  if math.floor(ver_ihl / 16) ~= 4 then return nil, "malformed" end
  local ihl = (ver_ihl % 16) * 4
  if ihl < 20 then return nil, "malformed" end
  if ip_off + ihl - 1 > #eth_frame then return nil, "truncated" end
  local proto = u8(eth_frame, ip_off + 9)
  if proto ~= 6 then return nil, "not_tcp" end -- TCP only
  local da, db, dc, dd = eth_frame:byte(ip_off + 16, ip_off + 19)
  if not da then return nil, "malformed" end
  local dst_ip = string.format("%d.%d.%d.%d", da, db, dc, dd)

  -- TCP header
  local tcp_off = ip_off + ihl
  if tcp_off + 20 - 1 > #eth_frame then return nil, "truncated" end
  local data_off_byte = u8(eth_frame, tcp_off + 12)
  if not data_off_byte then return nil, "malformed" end
  local data_off = math.floor(data_off_byte / 16) * 4
  if data_off < 20 then return nil, "malformed" end
  local payload_off = tcp_off + data_off
  if payload_off > #eth_frame then return nil, "truncated" end
  local payload = eth_frame:sub(payload_off)

  local sni = M.parse_client_hello(payload)
  if not sni then return nil, "no_sni" end
  return { dst_ip = dst_ip, src_mac = src_mac, sni = sni }
end

-- ---------------------------------------------------------------------------
-- IPC line format between wifihaven-sni-tail and wifihaven-dns-tail.
--
-- The two sidecars share the existing dns_cache writer (dns-tail), so SNI
-- captures must reach dns-tail as a line stream. sni-tail appends one TSV row
-- per ClientHello to /tmp/wifihaven-sni.log; dns-tail tails it alongside the
-- dnsmasq log and routes rows by the "SNI\t" prefix.
-- ---------------------------------------------------------------------------

function M.format_sni_line(dst_ip, src_mac, server_name)
  return string.format("SNI\t%s\t%s\t%s\n",
                       dst_ip or "", src_mac or "", server_name or "")
end

function M.parse_sni_line(line)
  if type(line) ~= "string" or line == "" then return nil end
  local ip, mac, sn = line:match("^SNI\t(%S+)\t(%S+)\t(%S+)%s*$")
  if not ip then return nil end
  return { dst_ip = ip, src_mac = mac, sni = sn }
end

-- route_line(line, deps) → handled (boolean)   (#573 SHOULD-FIX #1)
--
-- The wifihaven-dns-tail sidecar tails the dnsmasq query log AND the SNI
-- capture spool together (`tail -F dnsmasq.log sni.log`). This is the single
-- routing decision for each line, extracted from the sidecar's main loop so it
-- can be unit-tested in isolation:
--
--   * `tail -F` emits a "==> <file> <==" banner whenever it switches between
--     the two followed files — those are not dnsmasq lines and must never
--     reach the dnsmasq parsers, so they are consumed (handled = true) without
--     any side effect.
--   * "SNI\t<ip>\t<mac>\t<host>" rows from sni-tail are routed to the injected
--     insert_fn (cache.insert_sni), which is the SAME shared dns_cache writer
--     primitive DNS replies use — so dns-tail stays the sole writer of
--     paths.dns_cache (single-writer invariant preserved).
--   * Everything else (dnsmasq reply/query lines) returns handled = false so
--     the caller's existing dnsmasq handlers run.
--
-- deps.insert_fn(ip, host) is required; the routing is otherwise pure, so the
-- function never touches the cache, the spool, or flush bookkeeping itself.
function M.route_line(line, deps)
  if type(line) ~= "string" or line == "" then return false end
  if line:sub(1, 4) == "==> " then return true end -- tail -F switch banner
  local sn = M.parse_sni_line(line)
  if not sn then return false end                   -- dnsmasq line: fall through
  deps.insert_fn(sn.dst_ip, sn.sni)
  return true
end

-- ---------------------------------------------------------------------------
-- pcap stream reader (#573).
--
-- The wifihaven-sni-tail sidecar reads tcpdump's `-w -` output: a 24-byte
-- global header (magic 0xa1b2c3d4 / 0xd4c3b2a1 for the two byte orders)
-- followed by a sequence of 16-byte record headers each framing incl_len
-- captured packet bytes. This reader used to live as inline local functions in
-- the sidecar and was never executed by a test. It now lives here as pure,
-- injectable functions: a `read_fn(n)` returns up to n bytes or nil at EOF, so
-- the sidecar backs it with the io.popen handle and tests back it with an
-- in-memory string cursor — single source of truth for the framing logic.
-- ---------------------------------------------------------------------------

-- read_exact(read_fn, n) → exactly n bytes, or nil if EOF/short read.
-- read_fn(k) returns up to k bytes (or nil/"" at EOF); we loop until we have n.
local function read_exact(read_fn, n)
  if n <= 0 then return "" end
  local out = {}
  local got = 0
  while got < n do
    local chunk = read_fn(n - got)
    if not chunk or #chunk == 0 then return nil end
    out[#out + 1] = chunk
    got = got + #chunk
  end
  return table.concat(out)
end

-- read_global_header(read_fn) → { swapped = bool } | nil
--
-- Consumes the 24-byte pcap global header. The magic's first byte distinguishes
-- the two byte orders: native 0xa1b2c3d4 starts 0xa1; swapped 0xd4c3b2a1 starts
-- 0xd4. Returns nil if the stream is too short to hold a header (e.g. tcpdump
-- never produced a pcap stream — wrong interface / rejected BPF).
function M.read_global_header(read_fn)
  local hdr = read_exact(read_fn, 24)
  if not hdr or #hdr < 4 then return nil end
  local b1 = hdr:byte(1)
  local swapped = (b1 == 0xd4)
  return { swapped = swapped }
end

-- read_record(read_fn, swapped) → { data = <packet bytes> } | nil
--
-- Reads one 16-byte record header (ts_sec, ts_usec, incl_len, orig_len),
-- honoring `swapped` for the little/big-endian incl_len, then reads incl_len
-- captured bytes. Returns nil at clean EOF, on a truncated record header, or
-- when incl_len overruns the available bytes (snaplen-cut tail / pipe close) —
-- never raises.
function M.read_record(read_fn, swapped)
  local rec = read_exact(read_fn, 16)
  if not rec then return nil end
  local function u32(off)
    local a, b, c, d = rec:byte(off, off + 3)
    if swapped then a, b, c, d = d, c, b, a end
    return ((a * 256 + b) * 256 + c) * 256 + d
  end
  local incl_len = u32(9)
  local data = read_exact(read_fn, incl_len)
  if not data then return nil end
  return { data = data }
end

-- iter_pcap(read_fn) → function() → packet bytes | nil
--
-- Consumes the global header once, then returns an iterator that yields each
-- record's captured packet bytes in turn and nil at EOF (or on a truncated
-- trailing record, which terminates the iteration cleanly). If the global
-- header is absent/short the iterator yields nothing — the sidecar treats a
-- nil header as "tcpdump produced no pcap stream" and exits. Usable directly in
-- a `for pkt in sni.iter_pcap(read_fn) do ... end` loop.
function M.iter_pcap(read_fn)
  local gh = M.read_global_header(read_fn)
  return function()
    if not gh then return nil end
    local rec = M.read_record(read_fn, gh.swapped)
    if not rec then return nil end
    return rec.data
  end
end

-- lan_device(cursor) → string   (#573 SHOULD-FIX #3)
--
-- Probe the LAN bridge name from UCI (network.lan.device) so the sidecar
-- captures off the actual bridge instead of a hardcoded br-lan — routers using
-- a custom bridge name (br0, multi-WAN configs) otherwise produce no SNI rows
-- silently. Falls back to "br-lan" when the option (or the cursor) is absent.
-- The cursor is injected so this is unit-testable without a live UCI.
function M.lan_device(cursor)
  if cursor then
    local ok, dev = pcall(function()
      return cursor:get("network", "lan", "device")
    end)
    if ok and dev and dev ~= "" then return dev end
  end
  return "br-lan"
end

return M
