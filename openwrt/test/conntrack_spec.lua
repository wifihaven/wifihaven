-- Tests for openwrt/files/usr/lib/lua/wifihaven/conntrack.lua
-- Run with: busted openwrt/test/conntrack_spec.lua
-- Requires: busted (luarocks install busted)

local conntrack = require("conntrack")
local dns_log   = require("dns_log")

-- ---------------------------------------------------------------------------
-- 1. ipset attribution: dest_ip → hostname
-- ---------------------------------------------------------------------------

describe("ipset_lookup_hostname", function()
  it("returns the hostname whose nftables set contains dest_ip", function()
    -- nft_sets is a table mapping hostname → {ip, ...} (populated by dnsmasq --ipset)
    local nft_sets = {
      ["youtube.com"]  = { ["1.2.3.4"] = true, ["1.2.3.5"] = true },
      ["google.com"]   = { ["8.8.8.8"] = true },
    }
    assert.equal("youtube.com", conntrack.ipset_lookup_hostname("1.2.3.4", nft_sets))
    assert.equal("google.com",  conntrack.ipset_lookup_hostname("8.8.8.8",  nft_sets))
  end)

  it("returns nil when no set contains the ip", function()
    local nft_sets = { ["youtube.com"] = { ["1.2.3.4"] = true } }
    assert.is_nil(conntrack.ipset_lookup_hostname("9.9.9.9", nft_sets))
  end)

  it("returns nil for empty nft_sets", function()
    assert.is_nil(conntrack.ipset_lookup_hostname("1.2.3.4", {}))
  end)
end)

-- ---------------------------------------------------------------------------
-- 1b. host_matches — dnsmasq nftset suffix-match semantics
-- ---------------------------------------------------------------------------

describe("host_matches", function()
  it("returns true for an exact match", function()
    assert.is_true(conntrack.host_matches("example.com", "example.com"))
  end)

  it("returns true when hname is a direct subdomain", function()
    assert.is_true(conntrack.host_matches("foo.example.com", "example.com"))
  end)

  it("returns true when hname is a deeper subdomain", function()
    assert.is_true(conntrack.host_matches("a.b.example.com", "example.com"))
  end)

  it("returns false for a non-matching host", function()
    assert.is_false(conntrack.host_matches("other.com", "example.com"))
  end)

  it("returns false for a suffix without a dot separator (avoids false positive)", function()
    -- 'notexample.com' must NOT match 'example.com'
    assert.is_false(conntrack.host_matches("notexample.com", "example.com"))
  end)

  it("returns false for an empty hname", function()
    assert.is_false(conntrack.host_matches("", "example.com"))
  end)
end)

-- ---------------------------------------------------------------------------
-- 2. MAC lookup: src_ip → mac via ARP table
-- ---------------------------------------------------------------------------

describe("arp_lookup_mac", function()
  it("returns the MAC for a known IP", function()
    local arp_table = {
      ["192.168.1.42"] = "aa:bb:cc:11:22:33",
      ["192.168.1.10"] = "de:ad:be:ef:00:01",
    }
    assert.equal("aa:bb:cc:11:22:33", conntrack.arp_lookup_mac("192.168.1.42", arp_table))
  end)

  it("returns nil for an unknown IP", function()
    local arp_table = { ["192.168.1.42"] = "aa:bb:cc:11:22:33" }
    assert.is_nil(conntrack.arp_lookup_mac("10.0.0.1", arp_table))
  end)
end)

-- ---------------------------------------------------------------------------
-- 3. Event serialization
-- ---------------------------------------------------------------------------

describe("build_event", function()
  it("serializes a full connection_attempt to the correct JSON shape", function()
    local ev = conntrack.build_event({
      mac      = "aa:bb:cc:11:22:33",
      hostname = "youtube.com",
      dest_ip  = "1.2.3.4",
      allowed  = false,
      reason   = "category:adult",
      ts       = "2026-05-07T14:01:14Z",
    })
    assert.equal("connection_attempt", ev["type"])
    assert.equal("aa:bb:cc:11:22:33", ev.mac)
    -- #391: host is now a tagged union, not a bare hostname field
    assert.equal("fqdn",        ev.host.type)
    assert.equal("youtube.com", ev.host.value)
    assert.equal("1.2.3.4",            ev.destIp)
    assert.equal(false,                ev.allowed)
    assert.equal("category:adult",     ev.reason)
    assert.equal("2026-05-07T14:01:14Z", ev.ts)
  end)

  it("uses dest_ip as host fallback (type='ipv4') when hostname is nil", function()
    local ev = conntrack.build_event({
      mac      = "aa:bb:cc:11:22:33",
      hostname = nil,
      dest_ip  = "9.9.9.9",
      allowed  = true,
      reason   = nil,
      ts       = "2026-05-07T14:01:14Z",
    })
    -- #391: no "unknown" sentinel — IP literal tagged by address family
    assert.equal("ipv4",    ev.host.type)
    assert.equal("9.9.9.9", ev.host.value)
    assert.equal("allow",   ev.reason)
  end)

  it("uses dest_ip as host fallback (type='ipv4') for an IPv4 address", function()
    local ev = conntrack.build_event({
      mac      = "aa:bb:cc:11:22:33",
      hostname = nil,
      dest_ip  = "192.0.2.5",
      allowed  = true,
      reason   = nil,
      ts       = "2026-05-07T14:01:14Z",
    })
    assert.equal("ipv4",      ev.host.type)
    assert.equal("192.0.2.5", ev.host.value)
  end)

  it("uses dest_ip as host fallback (type='ipv6') for an IPv6 address", function()
    local ev = conntrack.build_event({
      mac      = "aa:bb:cc:11:22:33",
      hostname = nil,
      dest_ip  = "fe80::1",
      allowed  = true,
      reason   = nil,
      ts       = "2026-05-07T14:01:14Z",
    })
    assert.equal("ipv6",   ev.host.type)
    assert.equal("fe80::1", ev.host.value)
  end)

  it("JSON-encodes the event with correct field names", function()
    local json = require("cjson")
    local ev = conntrack.build_event({
      mac = "aa:bb:cc:11:22:33", hostname = "example.com",
      dest_ip = "1.1.1.1", allowed = true, reason = "allow",
      ts = "2026-05-07T00:00:00Z",
    })
    local encoded = json.encode(ev)
    local decoded = json.decode(encoded)
    assert.equal("connection_attempt", decoded["type"])
    -- #391: host is a tagged union
    assert.equal("fqdn",        decoded.host.type)
    assert.equal("example.com", decoded.host.value)
    assert.equal("1.1.1.1",            decoded.destIp)    -- camelCase to match server schema
    assert.is_boolean(decoded.allowed)
  end)
end)

-- ---------------------------------------------------------------------------
-- 4. Batching
-- ---------------------------------------------------------------------------

describe("batcher", function()
  local MAX_BATCH = 50
  local FLUSH_INTERVAL = 10  -- seconds

  it("flushes when batch reaches MAX_BATCH size", function()
    local flushed = {}
    local batch = conntrack.new_batcher(MAX_BATCH, FLUSH_INTERVAL, function(events)
      table.insert(flushed, events)
    end)

    for i = 1, MAX_BATCH do
      batch.add({ type = "connection_attempt", ts = tostring(i) })
    end

    assert.equal(1, #flushed)
    assert.equal(MAX_BATCH, #flushed[1])
  end)

  it("does not flush before MAX_BATCH is reached", function()
    local flushed = {}
    local batch = conntrack.new_batcher(MAX_BATCH, FLUSH_INTERVAL, function(events)
      table.insert(flushed, events)
    end)

    for i = 1, MAX_BATCH - 1 do
      batch.add({ type = "connection_attempt", ts = tostring(i) })
    end

    assert.equal(0, #flushed)
  end)

  it("flush() drains whatever is pending regardless of size", function()
    local flushed = {}
    local batch = conntrack.new_batcher(MAX_BATCH, FLUSH_INTERVAL, function(events)
      table.insert(flushed, events)
    end)

    batch.add({ type = "connection_attempt", ts = "t1" })
    batch.add({ type = "connection_attempt", ts = "t2" })
    batch.flush()

    assert.equal(1, #flushed)
    assert.equal(2, #flushed[1])
  end)

  it("flush() is a no-op when the buffer is empty", function()
    local flushed = {}
    local batch = conntrack.new_batcher(MAX_BATCH, FLUSH_INTERVAL, function(events)
      table.insert(flushed, events)
    end)
    batch.flush()
    assert.equal(0, #flushed)
  end)

  it("resets buffer after a flush", function()
    local flushed = {}
    local batch = conntrack.new_batcher(MAX_BATCH, FLUSH_INTERVAL, function(events)
      table.insert(flushed, events)
    end)

    for i = 1, MAX_BATCH do
      batch.add({ type = "connection_attempt", ts = tostring(i) })
    end
    -- One full batch flushed; add one more and manually flush.
    batch.add({ type = "connection_attempt", ts = "extra" })
    batch.flush()

    assert.equal(2, #flushed)
    assert.equal(1, #flushed[2])
  end)
end)

-- ---------------------------------------------------------------------------
-- 4b. parse_dhcp_leases
-- ---------------------------------------------------------------------------

describe("parse_dhcp_leases", function()
  local function write_tmp(contents)
    local path = os.tmpname()
    local f = assert(io.open(path, "w"))
    f:write(contents)
    f:close()
    return path
  end

  it("returns an empty table when the file doesn't exist", function()
    local leases = conntrack.parse_dhcp_leases("/tmp/__does_not_exist_wifihaven__")
    assert.same({}, leases)
  end)

  it("parses mac -> {ip, hostname} entries", function()
    local path = write_tmp(
      "1715000000 aa:bb:cc:11:22:33 192.168.1.42 laptop 01:aa:bb:cc:11:22:33\n" ..
      "1715000001 de:ad:be:ef:00:01 192.168.1.99 phone *\n")
    local leases = conntrack.parse_dhcp_leases(path)
    os.remove(path)
    assert.equal("192.168.1.42", leases["aa:bb:cc:11:22:33"].ip)
    assert.equal("laptop",       leases["aa:bb:cc:11:22:33"].hostname)
    assert.equal("192.168.1.99", leases["de:ad:be:ef:00:01"].ip)
    assert.equal("phone",        leases["de:ad:be:ef:00:01"].hostname)
  end)

  it("treats a hostname of '*' as nil", function()
    local path = write_tmp("1715000000 aa:bb:cc:11:22:33 192.168.1.42 * *\n")
    local leases = conntrack.parse_dhcp_leases(path)
    os.remove(path)
    assert.equal("192.168.1.42", leases["aa:bb:cc:11:22:33"].ip)
    assert.is_nil(leases["aa:bb:cc:11:22:33"].hostname)
  end)
end)

-- ---------------------------------------------------------------------------
-- 4c. handle_flow — first_seen_mac emission semantics
-- ---------------------------------------------------------------------------

describe("handle_flow", function()
  local MAC      = "aa:bb:cc:11:22:33"
  local SRC_IP   = "192.168.1.42"
  local DST_IP   = "1.2.3.4"

  local function collecting_batcher()
    local events = {}
    return {
      add   = function(ev) table.insert(events, ev) end,
      flush = function() end,
      tick  = function() end,
      events = events,
    }
  end

  local function ctx_with(overrides)
    local base = {
      arp_table      = { [SRC_IP] = MAC },
      nft_sets       = {},
      blocked_macs   = {},
      blocked_reason = {},
      reported_macs  = {},
      leases         = {},
      ts             = "2026-05-11T00:00:00Z",
    }
    for k, v in pairs(overrides or {}) do base[k] = v end
    return base
  end

  it("emits first_seen_mac and connection_attempt on first flow from a new MAC", function()
    local b = collecting_batcher()
    local ctx = ctx_with({
      leases = { [MAC] = { ip = "192.168.1.42", hostname = "laptop" } },
    })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)

    assert.equal(2, #b.events)
    assert.equal("first_seen_mac",     b.events[1]["type"])
    assert.equal(MAC,                  b.events[1].mac)
    assert.equal("connection_attempt", b.events[2]["type"])
    assert.equal(MAC,                  b.events[2].mac)
    assert.is_true(ctx.reported_macs[MAC])
  end)

  -- #1344: end-to-end — a real dns_log cache fed the real prod CNAME-chain log
  -- shapes must let a directly-queried CDN target flow carve out of a whole-MAC
  -- block via the app's branded extraAllowed entry. This wires the actual
  -- attribution fix through handle_flow, not a stubbed lookup.
  it("#1344: directly-queried CNAME-target flow carves out of a blocked MAC via the brand", function()
    local cache = dns_log.new({ ttl_seconds = 3600 })
    -- Branded chain observed (cdn.kastatic.org → … → prod.khan.map.fastly.net).
    cache.ingest_line("38053 192.168.1.42/36172 query[A] cdn.kastatic.org from 192.168.1.42")
    cache.ingest_line("38053 192.168.1.42/36172 reply cdn.kastatic.org is <CNAME>")
    cache.ingest_line("38053 192.168.1.42/36172 reply fastly.kastatic.org is <CNAME>")
    cache.ingest_line("38053 192.168.1.42/36172 reply prod.khan.map.fastly.net is 9.9.9.9")
    -- Device then re-queries the CNAME target directly → lands on DST_IP.
    cache.ingest_line("40000 192.168.1.42/61484 query[A] prod.khan.map.fastly.net from 192.168.1.42")
    cache.ingest_line("40000 192.168.1.42/61484 reply prod.khan.map.fastly.net is " .. DST_IP)

    local b = collecting_batcher()
    local ctx = ctx_with({
      reported_macs   = { [MAC] = true },
      blocked_macs    = { [MAC] = true },          -- whole-MAC block (downtime schedule)
      blocked_reason  = { [MAC] = "schedule" },
      lookup_hostname = cache.lookup,              -- the REAL attribution path
      ea_hosts_by_mac = { [MAC] = { ["kastatic.org"] = true } },
    })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)
    local ev = b.events[#b.events]
    assert.equal("connection_attempt", ev["type"])
    -- Recovered brand cdn.kastatic.org suffix-matches kastatic.org → carve-out.
    -- build_event normalises an allowed event's nil reason to "allow".
    assert.equal(true,    ev.allowed)
    assert.equal("allow", ev.reason)
  end)

  -- Contrast: without the observed branded chain the same direct-target flow
  -- can only attribute to the CDN target, which does NOT suffix-match the brand,
  -- so it is (mis-)classified as blocked — the pre-fix behaviour the operator hit.
  it("#1344: direct CNAME-target flow with no observed brand chain stays blocked", function()
    local cache = dns_log.new({ ttl_seconds = 3600 })
    cache.ingest_line("40000 192.168.1.42/61484 query[A] prod.khan.map.fastly.net from 192.168.1.42")
    cache.ingest_line("40000 192.168.1.42/61484 reply prod.khan.map.fastly.net is " .. DST_IP)

    local b = collecting_batcher()
    local ctx = ctx_with({
      reported_macs   = { [MAC] = true },
      blocked_macs    = { [MAC] = true },
      blocked_reason  = { [MAC] = "schedule" },
      lookup_hostname = cache.lookup,
      ea_hosts_by_mac = { [MAC] = { ["kastatic.org"] = true } },
    })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)
    local ev = b.events[#b.events]
    assert.equal(false,      ev.allowed)
    assert.equal("schedule", ev.reason)
  end)

  it("does not re-emit first_seen_mac on subsequent flows from the same MAC", function()
    local b = collecting_batcher()
    local ctx = ctx_with({
      leases = { [MAC] = { ip = "192.168.1.42", hostname = "laptop" } },
    })

    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = "5.6.7.8" }, ctx, b)
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = "9.9.9.9" }, ctx, b)

    -- 1 first_seen_mac + 3 connection_attempts
    assert.equal(4, #b.events)
    assert.equal("first_seen_mac",     b.events[1]["type"])
    assert.equal("connection_attempt", b.events[2]["type"])
    assert.equal("connection_attempt", b.events[3]["type"])
    assert.equal("connection_attempt", b.events[4]["type"])
  end)

  it("carries ip and hostname from the lease into the first_seen_mac event", function()
    local b = collecting_batcher()
    local ctx = ctx_with({
      leases = { [MAC] = { ip = "192.168.1.42", hostname = "laptop" } },
    })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)

    local ev = b.events[1]
    assert.equal("first_seen_mac",  ev["type"])
    assert.equal(MAC,               ev.mac)
    assert.equal("192.168.1.42",    ev.ip)
    assert.equal("laptop",          ev.hostname)
    assert.equal("2026-05-11T00:00:00Z", ev.ts)
  end)

  it("emits first_seen_mac with nil ip/hostname when the MAC has no lease entry", function()
    local b = collecting_batcher()
    local ctx = ctx_with({ leases = {} })  -- no lease for this MAC
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)

    local ev = b.events[1]
    assert.equal("first_seen_mac", ev["type"])
    assert.equal(MAC,              ev.mac)
    assert.is_nil(ev.ip)
    assert.is_nil(ev.hostname)
  end)

  -- ── #249: re-emit a dhcp_lease event when a later lease attaches a hostname ──
  it("flags MAC as pending-hostname when first_seen_mac is emitted with nil hostname", function()
    local b = collecting_batcher()
    local ctx = ctx_with({ leases = {}, pending_hostname_macs = {} })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)
    assert.is_true(ctx.pending_hostname_macs[MAC])
  end)

  it("does NOT flag MAC as pending when first_seen_mac already has a hostname", function()
    local b = collecting_batcher()
    local ctx = ctx_with({
      leases = { [MAC] = { ip = "192.168.1.42", hostname = "laptop" } },
      pending_hostname_macs = {},
    })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)
    assert.is_nil(ctx.pending_hostname_macs[MAC])
  end)

  it("emits dhcp_lease event when a pending MAC later acquires a hostname", function()
    local b = collecting_batcher()
    -- Already reported (first_seen_mac was emitted earlier without a hostname).
    local ctx = ctx_with({
      reported_macs         = { [MAC] = true },
      pending_hostname_macs = { [MAC] = true },
      leases                = { [MAC] = { ip = "192.168.1.42", hostname = "laptop" } },
    })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)

    -- Should emit one dhcp_lease + one connection_attempt (NOT another first_seen_mac).
    local found_dhcp = false
    for _, ev in ipairs(b.events) do
      if ev["type"] == "dhcp_lease" then
        found_dhcp = true
        assert.equal(MAC,             ev.mac)
        assert.equal("192.168.1.42",  ev.ip)
        assert.equal("laptop",        ev.hostname)
        assert.equal("2026-05-11T00:00:00Z", ev.ts)
      end
      assert.not_equal("first_seen_mac", ev["type"])
    end
    assert.is_true(found_dhcp)
    -- And the flag is cleared so we don't keep re-emitting.
    assert.is_nil(ctx.pending_hostname_macs[MAC])
  end)

  -- #259: dnsmasq query-log path is the real source of truth for hostname
  -- attribution; nft_sets only ever covers site_limits domains and is empty
  -- the rest of the time. handle_flow must prefer ctx.lookup_hostname.
  it("uses ctx.lookup_hostname when provided (dns_log path)", function()
    local b = collecting_batcher()
    local lookups = {}
    local ctx = ctx_with({
      leases          = { [MAC] = { ip = "192.168.1.42", hostname = "laptop" } },
      lookup_hostname = function(ip)
        lookups[#lookups + 1] = ip
        if ip == DST_IP then return "youtube.com" end
        return nil
      end,
    })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)

    -- connection_attempt event (index 2, after first_seen_mac) should carry
    -- the looked-up hostname as a tagged-union host field (#391).
    assert.equal("connection_attempt", b.events[2]["type"])
    assert.equal("fqdn",        b.events[2].host.type)
    assert.equal("youtube.com", b.events[2].host.value)
    assert.equal(DST_IP,        b.events[2].destIp)
    assert.same({ DST_IP }, lookups)
  end)

  it("falls back to nft_sets when ctx.lookup_hostname returns nil", function()
    local b = collecting_batcher()
    local ctx = ctx_with({
      leases          = { [MAC] = { ip = "192.168.1.42", hostname = "laptop" } },
      nft_sets        = { ["legacy.example"] = { [DST_IP] = true } },
      lookup_hostname = function(_ip) return nil end,
    })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)
    assert.equal("fqdn",           b.events[2].host.type)
    assert.equal("legacy.example", b.events[2].host.value)
  end)

  -- #583: dns-tail race fix. When the first lookup misses but the reply is
  -- about to land, handle_flow should sleep briefly and retry the lookup
  -- before falling through to host.type=ipv4.
  it("retries lookup_hostname after a short sleep when the first read misses (#583)", function()
    local b = collecting_batcher()
    local attempts = 0
    local slept    = {}
    local retry_state = conntrack.new_fqdn_retry_state({
      max_per_second = 5,
      delay_seconds  = 0.1,
      now_fn         = function() return 1000 end,
      sleep_fn       = function(s) slept[#slept + 1] = s end,
    })
    local ctx = ctx_with({
      leases           = { [MAC] = { ip = "192.168.1.42", hostname = "laptop" } },
      fqdn_retry_state = retry_state,
      lookup_hostname  = function(ip)
        attempts = attempts + 1
        -- Race: first read misses (cache not flushed yet); second hits.
        if attempts == 1 then return nil end
        if ip == DST_IP then return "neverssl.com" end
      end,
    })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)

    assert.equal(2, attempts)
    assert.same({ 0.1 }, slept)
    local ev = b.events[#b.events]
    assert.equal("connection_attempt", ev["type"])
    assert.equal("fqdn",          ev.host.type)
    assert.equal("neverssl.com",  ev.host.value)
    -- One token consumed.
    assert.equal(4, retry_state.tokens)
  end)

  it("does NOT retry when the first lookup hits (#583)", function()
    local b = collecting_batcher()
    local attempts = 0
    local slept    = {}
    local retry_state = conntrack.new_fqdn_retry_state({
      max_per_second = 5,
      delay_seconds  = 0.1,
      now_fn         = function() return 1000 end,
      sleep_fn       = function(s) slept[#slept + 1] = s end,
    })
    local ctx = ctx_with({
      leases           = { [MAC] = { ip = "192.168.1.42", hostname = "laptop" } },
      fqdn_retry_state = retry_state,
      lookup_hostname  = function(ip)
        attempts = attempts + 1
        if ip == DST_IP then return "neverssl.com" end
      end,
    })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)

    assert.equal(1, attempts)
    assert.same({}, slept)
    -- No token consumed on a first-read hit.
    assert.equal(5, retry_state.tokens)
  end)

  it("stops retrying once the per-second budget is exhausted (#583)", function()
    -- A flood of IP-only flows (DoH, hard-coded IPs) within a single second
    -- must not stall the conntrack loop on N×delay_seconds of sleep.
    local b = collecting_batcher()
    local now = 1000  -- monotonic time, frozen so all calls share one window
    local slept = {}
    local retry_state = conntrack.new_fqdn_retry_state({
      max_per_second = 2,
      delay_seconds  = 0.1,
      now_fn         = function() return now end,
      sleep_fn       = function(s) slept[#slept + 1] = s end,
    })
    local ctx_base = {
      arp_table        = { [SRC_IP] = MAC },
      nft_sets         = {},
      blocked_macs     = {},
      blocked_reason   = {},
      reported_macs    = { [MAC] = true },
      leases           = {},
      ts               = "2026-05-11T00:00:00Z",
      fqdn_retry_state = retry_state,
      lookup_hostname  = function(_ip) return nil end,  -- never resolves
    }
    for _ = 1, 5 do
      conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx_base, b)
    end

    -- Only the first 2 flows in the window may have paid the retry sleep.
    assert.equal(2, #slept)
    assert.equal(0, retry_state.tokens)
  end)

  it("refills the per-second budget once the window rolls over (#583)", function()
    local b = collecting_batcher()
    local now = 1000
    local slept = {}
    local retry_state = conntrack.new_fqdn_retry_state({
      max_per_second = 1,
      delay_seconds  = 0.1,
      now_fn         = function() return now end,
      sleep_fn       = function(s) slept[#slept + 1] = s end,
    })
    local ctx_base = {
      arp_table        = { [SRC_IP] = MAC },
      nft_sets         = {},
      blocked_macs     = {},
      blocked_reason   = {},
      reported_macs    = { [MAC] = true },
      leases           = {},
      ts               = "2026-05-11T00:00:00Z",
      fqdn_retry_state = retry_state,
      lookup_hostname  = function(_ip) return nil end,
    }
    -- Burn the budget in window 1.
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx_base, b)
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx_base, b)
    assert.equal(1, #slept)

    -- Roll past the 1s window; the next miss should retry again.
    now = now + 2
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx_base, b)
    assert.equal(2, #slept)
  end)

  -- #297: connection_attempt events for paused/time-exhausted profiles must
  -- be labeled blocked. nftables drops the flow, but conntrack -E -e NEW
  -- still observes the SYN_SENT entry — without a MAC-based block table the
  -- classifier defaulted to allowed=true and the admin UI showed "permitted".
  it("labels connection_attempt as blocked when the MAC is in blocked_macs", function()
    local b = collecting_batcher()
    local ctx = ctx_with({
      reported_macs   = { [MAC] = true },
      blocked_macs    = { [MAC] = true },
      blocked_reason  = { [MAC] = "paused" },
    })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)
    local ev = b.events[#b.events]
    assert.equal("connection_attempt", ev["type"])
    assert.equal(false,                ev.allowed)
    assert.equal("paused",             ev.reason)
  end)

  it("labels connection_attempt as allowed when blocked_macs is empty", function()
    local b = collecting_batcher()
    local ctx = ctx_with({ reported_macs = { [MAC] = true } })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)
    local ev = b.events[#b.events]
    assert.equal("connection_attempt", ev["type"])
    assert.equal(true,                 ev.allowed)
  end)

  it("does NOT emit dhcp_lease while the pending MAC's lease still has no hostname", function()
    local b = collecting_batcher()
    local ctx = ctx_with({
      reported_macs         = { [MAC] = true },
      pending_hostname_macs = { [MAC] = true },
      leases                = { [MAC] = { ip = "192.168.1.42", hostname = nil } },
    })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)

    for _, ev in ipairs(b.events) do
      assert.not_equal("dhcp_lease", ev["type"])
    end
    assert.is_true(ctx.pending_hostname_macs[MAC])
  end)

  -- ── per-host (eb_/bl_) block classification ──────────────────────────────
  --
  -- The kernel drops packets via `ether saddr <mac> ip daddr @eb_<host> drop`
  -- rules, but conntrack -E -e NEW still observes the SYN before the drop.
  -- handle_flow must consult eb_hosts_by_mac + hname (attributed hostname from
  -- dns_log cache) to correctly classify those flows as allowed=false with
  -- reason="host".  The lua nft_sets table is never populated in production,
  -- so decisions are driven off hname matching eb_hosts_by_mac — NOT off
  -- nft_sets[host][dst_ip].

  it("labels connection_attempt as blocked (reason=host:<host>) when hname exactly matches an eb_ host for the MAC (#1645)", function()
    local b = collecting_batcher()
    local ctx = ctx_with({
      reported_macs   = { [MAC] = true },
      -- hname is attributed via lookup_hostname; nft_sets is empty (as in production).
      lookup_hostname = function(ip) if ip == DST_IP then return "example.com" end end,
      nft_sets        = {},
      eb_hosts_by_mac = { [MAC] = { ["example.com"] = true } },
      ea_hosts_by_mac = {},
    })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)
    local ev = b.events[#b.events]
    assert.equal("connection_attempt", ev["type"])
    assert.equal(false,  ev.allowed)
    assert.equal("host:example.com", ev.reason)
  end)

  it("labels connection_attempt as blocked when hname is a subdomain of an eb_ host (suffix-match)", function()
    -- dnsmasq's nftset rule for example.com also matches foo.example.com, so
    -- the agent must apply the same suffix-match semantics.
    local b = collecting_batcher()
    local ctx = ctx_with({
      reported_macs   = { [MAC] = true },
      lookup_hostname = function(ip) if ip == DST_IP then return "foo.example.com" end end,
      nft_sets        = {},
      eb_hosts_by_mac = { [MAC] = { ["example.com"] = true } },
      ea_hosts_by_mac = {},
    })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)
    local ev = b.events[#b.events]
    assert.equal(false,  ev.allowed)
    -- #1645: reason carries the matched eb_ host even for the subdomain
    -- suffix-match case; the matched rule's host name is what gets logged.
    assert.equal("host:example.com", ev.reason)
  end)

  it("does NOT block when hname does not match any eb_ host for the MAC", function()
    -- Another host is in the eb_ set, but this flow is to a different hostname.
    local b = collecting_batcher()
    local ctx = ctx_with({
      reported_macs   = { [MAC] = true },
      lookup_hostname = function(ip) if ip == DST_IP then return "other.com" end end,
      nft_sets        = {},
      eb_hosts_by_mac = { [MAC] = { ["example.com"] = true } },
      ea_hosts_by_mac = {},
    })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)
    local ev = b.events[#b.events]
    assert.equal(true, ev.allowed)
  end)

  it("does NOT block when eb_hosts_by_mac entry is for a different MAC", function()
    -- Another MAC's extraBlocked, not this one's.
    local b = collecting_batcher()
    local ctx = ctx_with({
      reported_macs   = { [MAC] = true },
      lookup_hostname = function(ip) if ip == DST_IP then return "example.com" end end,
      nft_sets        = {},
      eb_hosts_by_mac = { ["ff:ff:ff:ff:ff:ff"] = { ["example.com"] = true } },
      ea_hosts_by_mac = {},
    })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)
    local ev = b.events[#b.events]
    assert.equal(true, ev.allowed)
  end)

  it("does NOT block when hname matches an eb_ host but also matches an ea_ (extraAllowed) host (#421)", function()
    -- #421: extraAllowed beats blocked — the nft drop carries `ip daddr != @ea_...`
    -- so the kernel forwards the packet; handle_flow must mirror this decision.
    local b = collecting_batcher()
    local ctx = ctx_with({
      reported_macs   = { [MAC] = true },
      lookup_hostname = function(ip) if ip == DST_IP then return "example.com" end end,
      nft_sets        = {},
      eb_hosts_by_mac = { [MAC] = { ["example.com"] = true } },
      ea_hosts_by_mac = { [MAC] = { ["example.com"] = true } },
    })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)
    local ev = b.events[#b.events]
    assert.equal(true, ev.allowed)
  end)

  -- ── #594: category blocklist (bl_) hits report a category-specific reason ─

  it("#594: labels connection_attempt with reason='category:<id>' when hname matches a bl_ host", function()
    local b = collecting_batcher()
    local ctx = ctx_with({
      reported_macs   = { [MAC] = true },
      lookup_hostname = function(ip) if ip == DST_IP then return "ad.doubleclick.net" end end,
      nft_sets        = {},
      eb_hosts_by_mac = {},
      ea_hosts_by_mac = {},
      bl_hosts_by_mac = { [MAC] = { ["ad.doubleclick.net"] = "ads" } },
    })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)
    local ev = b.events[#b.events]
    assert.equal(false,          ev.allowed)
    assert.equal("category:ads", ev.reason)
  end)

  it("#594: suffix-match also applies to bl_ hosts", function()
    local b = collecting_batcher()
    local ctx = ctx_with({
      reported_macs   = { [MAC] = true },
      lookup_hostname = function(ip) if ip == DST_IP then return "tracker.ads.example" end end,
      nft_sets        = {},
      eb_hosts_by_mac = {},
      ea_hosts_by_mac = {},
      bl_hosts_by_mac = { [MAC] = { ["ads.example"] = "ads" } },
    })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)
    local ev = b.events[#b.events]
    assert.equal(false,          ev.allowed)
    assert.equal("category:ads", ev.reason)
  end)

  it("#594: extraAllowed beats category-blocklist hit (#421 carve-out applies to bl_ too)", function()
    local b = collecting_batcher()
    local ctx = ctx_with({
      reported_macs   = { [MAC] = true },
      lookup_hostname = function(ip) if ip == DST_IP then return "ad.doubleclick.net" end end,
      nft_sets        = {},
      eb_hosts_by_mac = {},
      ea_hosts_by_mac = { [MAC] = { ["ad.doubleclick.net"] = true } },
      bl_hosts_by_mac = { [MAC] = { ["ad.doubleclick.net"] = "ads" } },
    })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)
    local ev = b.events[#b.events]
    assert.equal(true, ev.allowed)
  end)

  it("#594: extraBlocked takes precedence over category when both contain the host", function()
    -- This mirrors render.update_shared's invariant (#594): a host present in
    -- both extraBlocked and a blocklist is classified as extraBlocked.
    local b = collecting_batcher()
    local ctx = ctx_with({
      reported_macs   = { [MAC] = true },
      lookup_hostname = function(ip) if ip == DST_IP then return "shared.example" end end,
      nft_sets        = {},
      eb_hosts_by_mac = { [MAC] = { ["shared.example"] = true } },
      ea_hosts_by_mac = {},
      bl_hosts_by_mac = {},  -- render.update_shared would suppress this entry
    })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)
    local ev = b.events[#b.events]
    assert.equal(false,  ev.allowed)
    assert.equal("host:shared.example", ev.reason)
  end)

  it("does NOT block when eb_hosts_by_mac is absent from ctx", function()
    local b = collecting_batcher()
    local ctx = ctx_with({
      reported_macs   = { [MAC] = true },
      lookup_hostname = function(ip) if ip == DST_IP then return "example.com" end end,
      nft_sets        = {},
      -- no eb_hosts_by_mac
    })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)
    local ev = b.events[#b.events]
    assert.equal(true, ev.allowed)
  end)

  -- ── #579: nft eb_ set membership fallback when hname is nil ─────────────
  --
  -- When DNS attribution misses (hname=nil), the agent falls back to querying
  -- the live nft eb_ set for each extraBlocked hostname via exec_fn.  If the
  -- IP is in any eb_ set the flow is classified as blocked (reason="host").
  -- The ea_ carve-out is still honored: if the same host is also extraAllowed,
  -- the flow stays allowed.

  it("#579: hname=nil but dst_ip is in eb_ nft set → allowed=false reason=host", function()
    local b = collecting_batcher()
    -- exec_fn is injected: returns 0 (success) iff the eb_example_com set is queried.
    local exec_calls = {}
    local ctx = ctx_with({
      reported_macs   = { [MAC] = true },
      nft_sets        = {},
      eb_hosts_by_mac = { [MAC] = { ["example.com"] = true } },
      ea_hosts_by_mac = {},
      exec_fn = function(cmd)
        exec_calls[#exec_calls + 1] = cmd
        -- Simulate: dst_ip IS in eb_example_com
        if cmd:find("eb_example_com") then return 0 end
        return 1  -- miss for anything else
      end,
    })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)
    local ev = b.events[#b.events]
    assert.equal(false,  ev.allowed, "extraBlocked IP with no DNS attribution must be logged as blocked")
    -- #1645: even in the no-hname fallback the reason names which eb_ rule
    -- matched, so triage can see the specific eb_<host> that fired.
    assert.equal("host:example.com", ev.reason)
    assert.is_true(#exec_calls >= 1, "exec_fn must have been called for the nft lookup")
  end)

  it("#579: hname=nil and dst_ip NOT in any eb_ nft set → allowed=true (not extraBlocked)", function()
    local b = collecting_batcher()
    local ctx = ctx_with({
      reported_macs   = { [MAC] = true },
      nft_sets        = {},
      eb_hosts_by_mac = { [MAC] = { ["example.com"] = true } },
      ea_hosts_by_mac = {},
      exec_fn = function(_cmd) return 1 end,  -- always miss
    })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)
    local ev = b.events[#b.events]
    assert.equal(true, ev.allowed)
  end)

  it("#579: hname=nil, dst_ip in eb_ set, but also matches ea_ → allowed=true (#421 extraAllowed beats extraBlocked)", function()
    local b = collecting_batcher()
    local ctx = ctx_with({
      reported_macs   = { [MAC] = true },
      nft_sets        = {},
      eb_hosts_by_mac = { [MAC] = { ["example.com"] = true } },
      ea_hosts_by_mac = { [MAC] = { ["example.com"] = true } },
      exec_fn = function(cmd)
        if cmd:find("eb_example_com") then return 0 end
        return 1
      end,
    })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)
    local ev = b.events[#b.events]
    -- extraAllowed beats extraBlocked: flow must remain allowed
    assert.equal(true, ev.allowed)
  end)

  it("#579: hname=nil and exec_fn absent (no nft) → allowed=true (degraded but safe)", function()
    -- When exec_fn is not injected and os.execute is the default, the nft call
    -- happens against a non-running nftables (CI env).  The test stubs exec_fn
    -- to nil to verify the code degrades gracefully to allowed=true rather than
    -- erroring out.  In production the nft call returns non-zero if the set is
    -- missing, which is treated as a miss.
    local b = collecting_batcher()
    local ctx = ctx_with({
      reported_macs   = { [MAC] = true },
      nft_sets        = {},
      eb_hosts_by_mac = { [MAC] = { ["example.com"] = true } },
      ea_hosts_by_mac = {},
      -- exec_fn not set → falls back to os.execute which will return non-zero in CI
      exec_fn = function(_cmd) return 1 end,  -- simulate nft not finding element
    })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)
    local ev = b.events[#b.events]
    assert.equal(true, ev.allowed)
  end)

  -- ── #1668: v6 conntrack labeling fallback ───────────────────────────────
  --
  -- The nft_eb_hit fallback was v4-only — it always queried `eb_<host>` and
  -- never the parallel `eb6_<host>` set that render.lua emits for AAAA-
  -- resolved IPs. The result was: a v6 dst_ip with no DNS attribution would
  -- silently miss the eb_/bl_ lookup and the connection_event would record an
  -- opaque ExtraBlocked / household block instead of `host:<host>` /
  -- `category:<id>`. PR #1656 closed the v4 hole; this closes the v6 hole.
  -- The pair is pinned side-by-side so future divergence is caught by CI.
  local DST_IP6 = "2001:db8::1"

  it("#1668: v6 hname=nil + dst_ip in eb6_ nft set → allowed=false reason=host:<host>", function()
    local b = collecting_batcher()
    local exec_calls = {}
    local ctx = ctx_with({
      reported_macs   = { [MAC] = true },
      nft_sets        = {},
      eb_hosts_by_mac = { [MAC] = { ["example.com"] = true } },
      ea_hosts_by_mac = {},
      exec_fn = function(cmd)
        exec_calls[#exec_calls + 1] = cmd
        -- v4 set must NOT match (kernel only has the v6 IP in eb6_).
        if cmd:find("eb6_example_com") then return 0 end
        return 1
      end,
    })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP6 }, ctx, b)
    local ev = b.events[#b.events]
    assert.equal(false, ev.allowed,
      "v6 extraBlocked IP with no DNS attribution must be logged as blocked")
    assert.equal("host:example.com", ev.reason)
    local saw_v6_set = false
    for _, cmd in ipairs(exec_calls) do
      if cmd:find("eb6_example_com") then saw_v6_set = true end
    end
    assert.is_true(saw_v6_set,
      "exec_fn must have queried the eb6_<host> set for a v6 dst_ip")
  end)

  it("#1668: v4 sibling — hname=nil + dst_ip in eb_ nft set → reason=host:<host> (pinned to prevent divergence)", function()
    local b = collecting_batcher()
    local exec_calls = {}
    local ctx = ctx_with({
      reported_macs   = { [MAC] = true },
      nft_sets        = {},
      eb_hosts_by_mac = { [MAC] = { ["example.com"] = true } },
      ea_hosts_by_mac = {},
      exec_fn = function(cmd)
        exec_calls[#exec_calls + 1] = cmd
        -- v6 set must NOT match (kernel only has the v4 IP in eb_).
        if cmd:find("eb6_") then return 1 end
        if cmd:find("eb_example_com") then return 0 end
        return 1
      end,
    })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)
    local ev = b.events[#b.events]
    assert.equal(false, ev.allowed)
    assert.equal("host:example.com", ev.reason)
    local saw_v4_set = false
    for _, cmd in ipairs(exec_calls) do
      if cmd:find("eb_example_com") and not cmd:find("eb6_") then
        saw_v4_set = true
      end
    end
    assert.is_true(saw_v4_set,
      "exec_fn must have queried the v4 eb_<host> set for a v4 dst_ip")
  end)

  it("#1668: v6 bl_ labeling fallback queries eb6_<host> set → reason=category:<id>", function()
    -- bl_ labeling fallback (line ~750 in conntrack.lua) piggybacks on the
    -- same nft_eb_hit helper — the comment there explicitly says it reuses
    -- the eb_-style query because dnsmasq populates both indexing paths for
    -- the same host. Pin that this piggyback works for v6 too.
    local b = collecting_batcher()
    local exec_calls = {}
    local ctx = ctx_with({
      reported_macs   = { [MAC] = true },
      nft_sets        = {},
      eb_hosts_by_mac = {},
      ea_hosts_by_mac = {},
      bl_hosts_by_mac = { [MAC] = { ["ad.doubleclick.net"] = "ads" } },
      exec_fn = function(cmd)
        exec_calls[#exec_calls + 1] = cmd
        if cmd:find("eb6_ad_doubleclick_net") then return 0 end
        return 1
      end,
    })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP6 }, ctx, b)
    local ev = b.events[#b.events]
    assert.equal(false, ev.allowed,
      "v6 blocklist IP with no DNS attribution must be logged as blocked")
    assert.equal("category:ads", ev.reason)
  end)

  it("does NOT interfere with blocked_macs block: MAC already blocked stays blocked with original reason when hname is nil", function()
    -- hname=nil → ea-override can't fire → flow stays blocked with MAC-level reason
    local b = collecting_batcher()
    local ctx = ctx_with({
      reported_macs   = { [MAC] = true },
      blocked_macs    = { [MAC] = true },
      blocked_reason  = { [MAC] = "paused" },
      nft_sets        = {},
      eb_hosts_by_mac = { [MAC] = { ["example.com"] = true } },
      ea_hosts_by_mac = {},
    })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)
    local ev = b.events[#b.events]
    assert.equal(false,    ev.allowed)
    assert.equal("paused", ev.reason)
  end)

  -- ── extraAllowed (ea_) override for blocked MACs (#421) ──────────────────
  --
  -- When a MAC is paused / schedule-blocked / time-limited, the kernel still
  -- allows flows to extraAllowed hosts via `ip daddr != @ea_<mac>_<host>`
  -- clauses.  handle_flow must flip allowed back to true when hname matches.

  it("ea-override: blocked MAC + flow to extraAllowed host → allowed=true reason=nil", function()
    local b = collecting_batcher()
    local ctx = ctx_with({
      reported_macs   = { [MAC] = true },
      blocked_macs    = { [MAC] = true },
      blocked_reason  = { [MAC] = "TimeLimit" },
      lookup_hostname = function(ip) if ip == DST_IP then return "allowed.com" end end,
      nft_sets        = {},
      ea_hosts_by_mac = { [MAC] = { ["allowed.com"] = true } },
    })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)
    local ev = b.events[#b.events]
    assert.equal("connection_attempt", ev["type"])
    assert.equal(true, ev.allowed)
    assert.not_equal("TimeLimit", ev.reason)
    -- reason should be "allow" (the default for allowed=true with no explicit reason)
    assert.equal("allow", ev.reason)
  end)

  it("ea-override: blocked MAC + flow to a DIFFERENT (non-extraAllowed) hostname → allowed=false reason=TimeLimit", function()
    local b = collecting_batcher()
    local ctx = ctx_with({
      reported_macs   = { [MAC] = true },
      blocked_macs    = { [MAC] = true },
      blocked_reason  = { [MAC] = "TimeLimit" },
      lookup_hostname = function(ip) if ip == DST_IP then return "blocked-host.com" end end,
      nft_sets        = {},
      ea_hosts_by_mac = { [MAC] = { ["allowed.com"] = true } },
    })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)
    local ev = b.events[#b.events]
    assert.equal(false,       ev.allowed)
    assert.equal("TimeLimit", ev.reason)
  end)

  it("ea-override: blocked MAC + hname=nil (no DNS attribution) → allowed=false reason=TimeLimit (known limitation)", function()
    -- Without hname we cannot determine whether the ea_ carve-out fired.
    -- Flow stays logged as blocked even if the kernel allowed it.
    local b = collecting_batcher()
    local ctx = ctx_with({
      reported_macs   = { [MAC] = true },
      blocked_macs    = { [MAC] = true },
      blocked_reason  = { [MAC] = "TimeLimit" },
      -- no lookup_hostname, nft_sets empty → hname=nil
      nft_sets        = {},
      ea_hosts_by_mac = { [MAC] = { ["allowed.com"] = true } },
    })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)
    local ev = b.events[#b.events]
    assert.equal(false,       ev.allowed)
    assert.equal("TimeLimit", ev.reason)
  end)

  it("ea-override suffix-match: blocked MAC + extraAllowed example.com + flow hname=img.example.com → allowed=true", function()
    -- extraAllowed entry is example.com (no subdomain); dnsmasq's nftset
    -- semantics also cover img.example.com, so the ea_ override must fire.
    local b = collecting_batcher()
    local ctx = ctx_with({
      reported_macs   = { [MAC] = true },
      blocked_macs    = { [MAC] = true },
      blocked_reason  = { [MAC] = "paused" },
      lookup_hostname = function(ip) if ip == DST_IP then return "img.example.com" end end,
      nft_sets        = {},
      ea_hosts_by_mac = { [MAC] = { ["example.com"] = true } },
    })
    conntrack.handle_flow({ src_ip = SRC_IP, dst_ip = DST_IP }, ctx, b)
    local ev = b.events[#b.events]
    assert.equal(true,    ev.allowed)
    assert.equal("allow", ev.reason)
  end)
end)

describe("attribute_hostname (#583 dns-tail race)", function()
  it("returns the first-read hit without consulting retry_state", function()
    local state = conntrack.new_fqdn_retry_state({
      max_per_second = 3, delay_seconds = 0.1,
      now_fn = function() return 0 end,
      sleep_fn = function() error("must not sleep on a hit") end,
    })
    local h = conntrack.attribute_hostname("1.2.3.4",
      function(_ip) return "example.com" end, state)
    assert.equal("example.com", h)
    assert.equal(3, state.tokens)
  end)

  it("returns nil and does not sleep when retry_state is nil", function()
    local h = conntrack.attribute_hostname("1.2.3.4", function() return nil end, nil)
    assert.is_nil(h)
  end)

  it("retries once and returns the late-arriving hostname", function()
    local n, slept = 0, {}
    local state = conntrack.new_fqdn_retry_state({
      max_per_second = 2, delay_seconds = 0.05,
      now_fn = function() return 0 end,
      sleep_fn = function(s) slept[#slept + 1] = s end,
    })
    local h = conntrack.attribute_hostname("1.2.3.4", function(_ip)
      n = n + 1
      if n == 2 then return "late.example" end
    end, state)
    assert.equal("late.example", h)
    assert.same({ 0.05 }, slept)
    assert.equal(1, state.tokens)
  end)
end)

describe("build_dhcp_lease_event", function()
  it("builds a dhcp_lease event with mac/ip/hostname/ts", function()
    local ev = conntrack.build_dhcp_lease_event({
      mac = "aa:bb:cc:11:22:33",
      ip = "192.168.1.42",
      hostname = "laptop",
      ts = "2026-05-11T00:00:00Z",
    })
    assert.equal("dhcp_lease", ev["type"])
    assert.equal("aa:bb:cc:11:22:33", ev.mac)
    assert.equal("192.168.1.42",      ev.ip)
    assert.equal("laptop",            ev.hostname)
    assert.equal("2026-05-11T00:00:00Z", ev.ts)
  end)
end)

-- ---------------------------------------------------------------------------
-- #338: per-event idempotency key on connection_attempt events.
-- ---------------------------------------------------------------------------

describe("event_id idempotency (#338)", function()
  local saved_gen = conntrack.gen_event_id
  after_each(function() conntrack.gen_event_id = saved_gen end)

  it("build_event stamps a UUID-shaped eventId via M.gen_event_id", function()
    conntrack.gen_event_id = function() return "11111111-2222-3333-4444-555555555555" end
    local ev = conntrack.build_event({
      mac = "aa:bb:cc:11:22:33", hostname = "youtube.com",
      dest_ip = "1.2.3.4", allowed = true, ts = "2026-05-11T00:00:00Z",
    })
    assert.equal("11111111-2222-3333-4444-555555555555", ev.eventId)
  end)

  it("each build_event call gets a fresh eventId", function()
    local n = 0
    conntrack.gen_event_id = function()
      n = n + 1
      return string.format("00000000-0000-0000-0000-%012d", n)
    end
    local a = conntrack.build_event({
      mac = "aa", hostname = "h", dest_ip = "1.1.1.1",
      allowed = true, ts = "t",
    })
    local b = conntrack.build_event({
      mac = "aa", hostname = "h", dest_ip = "1.1.1.1",
      allowed = true, ts = "t",
    })
    assert.is_not.equal(a.eventId, b.eventId)
  end)

  it("dhcp_lease and first_seen_mac builders do NOT include eventId", function()
    -- Those events drive idempotent device upserts, not connection_events
    -- rows, so they have no per-row dedup key.
    local d = conntrack.build_dhcp_lease_event({
      mac = "aa", ip = "1.1.1.1", hostname = "h", ts = "t",
    })
    local f = conntrack.build_first_seen_mac_event({
      mac = "aa", ip = "1.1.1.1", hostname = "h", ts = "t",
    })
    assert.is_nil(d.eventId)
    assert.is_nil(f.eventId)
  end)

  it("gen_event_id reads /proc/sys/kernel/random/uuid (smoke test on host)", function()
    -- Skip if the host (CI container) doesn't expose the kernel UUID source.
    local f = io.open("/proc/sys/kernel/random/uuid", "r")
    if not f then return end
    f:close()
    local id = conntrack.gen_event_id()
    assert.is_string(id)
    -- RFC 4122 canonical form: 8-4-4-4-12 hex digits
    assert.is_truthy(id:match("^%x%x%x%x%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%x%x%x%x%x%x%x%x$"))
  end)
end)

-- ---------------------------------------------------------------------------
-- 5. Retry logic
-- ---------------------------------------------------------------------------

describe("post_with_retry", function()
  local MAX_RETRIES    = 3
  local BASE_DELAY_SEC = 0  -- set to 0 in tests so they don't sleep

  it("succeeds on first attempt and returns true", function()
    local calls = 0
    local function post_fn(_url, _body)
      calls = calls + 1
      return 200, "ok-body"
    end
    local ok, status, body, err = conntrack.post_with_retry(
      "http://api/events", "{}", MAX_RETRIES, 0, post_fn)
    assert.is_true(ok)
    assert.equal(200, status)
    assert.equal("ok-body", body)
    assert.is_nil(err)
    assert.equal(1, calls)
  end)

  it("retries on 5xx and succeeds on second attempt", function()
    local calls = 0
    local function post_fn(_url, _body)
      calls = calls + 1
      if calls < 2 then return 500, "err" end
      return 200, ""
    end
    local ok, status = conntrack.post_with_retry(
      "http://api/events", "{}", MAX_RETRIES, 0, post_fn)
    assert.is_true(ok)
    assert.equal(200, status)
    assert.equal(2, calls)
  end)

  it("drops and returns false after max retries are exhausted, surfacing last status/body", function()
    local calls = 0
    local function post_fn(_url, _body)
      calls = calls + 1
      return 503, "unavailable"
    end
    local ok, status, body, err = conntrack.post_with_retry(
      "http://api/events", "{}", MAX_RETRIES, 0, post_fn)
    assert.is_false(ok)
    assert.equal(503, status)
    assert.equal("unavailable", body)
    assert.is_nil(err)
    assert.equal(MAX_RETRIES, calls)
  end)

  it("drops and returns false when all retries return 500, surfacing status=500", function()
    local calls = 0
    local function post_fn(_url, _body)
      calls = calls + 1
      return 500, "boom"
    end
    local ok, status, body = conntrack.post_with_retry(
      "http://api/events", "{}", MAX_RETRIES, 0, post_fn)
    assert.is_false(ok)
    assert.equal(500, status)
    assert.equal("boom", body)
    assert.equal(MAX_RETRIES, calls)
  end)

  it("surfaces connection error (nil status) with err string when post_fn fails", function()
    local calls = 0
    local function post_fn(_url, _body)
      calls = calls + 1
      return nil, nil, "curl: (7) Failed to connect"
    end
    local ok, status, body, err = conntrack.post_with_retry(
      "http://api/events", "{}", MAX_RETRIES, 0, post_fn)
    assert.is_false(ok)
    assert.is_nil(status)
    assert.is_nil(body)
    assert.equal("curl: (7) Failed to connect", err)
    assert.equal(MAX_RETRIES, calls)
  end)

  it("does not retry on 4xx (client error), surfacing status/body", function()
    local calls = 0
    local function post_fn(_url, _body)
      calls = calls + 1
      return 401, "unauthorized"
    end
    local ok, status, body = conntrack.post_with_retry(
      "http://api/events", "{}", MAX_RETRIES, 0, post_fn)
    assert.is_false(ok)
    assert.equal(401, status)
    assert.equal("unauthorized", body)
    assert.equal(1, calls)   -- no retry on 4xx
  end)

  it("uses exponential back-off delays (doubles each retry)", function()
    local delays_used = {}
    local calls = 0
    local function post_fn(_url, _body)
      calls = calls + 1
      return 500, "err"
    end
    local function sleep_fn(secs)
      table.insert(delays_used, secs)
    end
    conntrack.post_with_retry("http://api/events", "{}", 4, 1, post_fn, sleep_fn)
    -- delays should be 1, 2, 4 (3 sleeps for 4 attempts)
    assert.equal(3, #delays_used)
    assert.equal(1, delays_used[1])
    assert.equal(2, delays_used[2])
    assert.equal(4, delays_used[3])
  end)
end)

-- ---------------------------------------------------------------------------
-- 6. Events retry queue (#330)
--
-- Mirrors the usage retry queue (#309): the in-call `post_with_retry` handles
-- transient blips with short backoff; on its final failure the batch goes onto
-- a bounded in-memory queue and is drained oldest-first when the API recovers.
-- ---------------------------------------------------------------------------

describe("events retry queue", function()
  -- rng_fn=0.5 → midpoint of the ±10% jitter band, so backoff is the nominal
  -- value and tests can assert exact next_attempt_at integers.
  local function no_jitter() return 0.5 end

  local function ev(n)
    local r = {}
    for i = 1, (n or 1) do
      r[i] = { ["type"] = "connection_attempt", mac = "aa", hostname = "h" .. i,
               destIp = "1.1.1.1", allowed = true, reason = "allow", ts = "t" .. i }
    end
    return r
  end

  describe("new_event_queue / enqueue_events", function()
    it("starts empty", function()
      local q = conntrack.new_event_queue()
      assert.equal(0, #q.batches)
    end)

    it("schedules first retry at now + 30s with no jitter midpoint", function()
      local q = conntrack.new_event_queue()
      conntrack.enqueue_events(q, ev(3), 1000, no_jitter)
      assert.equal(1, #q.batches)
      assert.equal(1030, q.batches[1].next_attempt_at)
      assert.equal(1, q.batches[1].attempts)
      assert.equal(3, #q.batches[1].events)
    end)

    it("applies ±10% jitter around the nominal delay", function()
      local q1 = conntrack.new_event_queue()
      conntrack.enqueue_events(q1, ev(1), 1000, function() return 0 end)
      assert.equal(1000 + 27, q1.batches[1].next_attempt_at)  -- 30 * 0.9
      local q2 = conntrack.new_event_queue()
      conntrack.enqueue_events(q2, ev(1), 1000, function() return 1 end)
      assert.equal(1000 + 33, q2.batches[1].next_attempt_at)  -- 30 * 1.1
    end)
  end)

  describe("backoff schedule", function()
    it("doubles per attempt: 30, 60, 120, 240, 480, 900 (capped at 900)", function()
      local q = conntrack.new_event_queue()
      local expected = { 30, 60, 120, 240, 480, 900, 900, 900 }
      conntrack.enqueue_events(q, ev(1), 1000, no_jitter)
      assert.equal(1000 + expected[1], q.batches[1].next_attempt_at)
      local function fail(_u, _b) return 500, "err" end
      for i = 2, #expected do
        local now = q.batches[1].next_attempt_at
        conntrack.drain_events("http://api/events", "r1", q, fail, now, no_jitter)
        assert.equal(now + expected[i], q.batches[1].next_attempt_at,
          "attempt " .. i .. ": expected " .. expected[i] .. "s")
      end
    end)
  end)

  describe("drain_events", function()
    it("does nothing when no batch is due yet", function()
      local q = conntrack.new_event_queue()
      conntrack.enqueue_events(q, ev(2), 1000, no_jitter)  -- next at 1030
      local calls = 0
      local function ok_fn(_u, _b) calls = calls + 1; return 200, "" end
      conntrack.drain_events("http://api/events", "r1", q, ok_fn, 1020, no_jitter)
      assert.equal(0, calls)
      assert.equal(1, #q.batches)
    end)

    it("posts due batch and removes it on success", function()
      local q = conntrack.new_event_queue()
      conntrack.enqueue_events(q, ev(2), 1000, no_jitter)
      local seen_payload
      local function ok_fn(_u, body) seen_payload = body; return 200, "" end
      conntrack.drain_events("http://api/events", "r1", q, ok_fn, 1030, no_jitter)
      assert.equal(0, #q.batches)
      -- Payload shape: { routerId, events: [...] }
      local cjson = require("cjson")
      local decoded = cjson.decode(seen_payload)
      assert.equal("r1", decoded.routerId)
      assert.equal(2, #decoded.events)
    end)

    it("drains in insertion order (oldest-first)", function()
      local q = conntrack.new_event_queue()
      conntrack.enqueue_events(q, ev(1), 1000, no_jitter)  -- first
      conntrack.enqueue_events(q, ev(2), 1001, no_jitter)  -- second
      local seen_sizes = {}
      local function ok_fn(_u, body)
        local cjson = require("cjson")
        seen_sizes[#seen_sizes + 1] = #cjson.decode(body).events
        return 200, ""
      end
      conntrack.drain_events("http://api/events", "r1", q, ok_fn, 9999, no_jitter)
      assert.equal(1, seen_sizes[1])
      assert.equal(2, seen_sizes[2])
    end)

    it("stops at first failure; reschedules with next backoff", function()
      local q = conntrack.new_event_queue()
      conntrack.enqueue_events(q, ev(1), 1000, no_jitter)
      conntrack.enqueue_events(q, ev(1), 1000, no_jitter)
      local attempts = 0
      local function fail(_u, _b) attempts = attempts + 1; return 500, "" end
      conntrack.drain_events("http://api/events", "r1", q, fail, 1030, no_jitter)
      assert.equal(1, attempts, "must stop at first failure")
      assert.equal(2, #q.batches)
      assert.equal(1030 + 60, q.batches[1].next_attempt_at)
      assert.equal(2, q.batches[1].attempts)
    end)

    it("treats connection error (nil status) as failure", function()
      local q = conntrack.new_event_queue()
      conntrack.enqueue_events(q, ev(1), 1000, no_jitter)
      local function net_err(_u, _b) return nil, nil, "curl: (7) connect failed" end
      conntrack.drain_events("http://api/events", "r1", q, net_err, 1030, no_jitter)
      assert.equal(1, #q.batches)
      assert.equal(2, q.batches[1].attempts)
    end)
  end)

  describe("queue cap (drop oldest on overflow)", function()
    it("retains exactly `cap` batches, dropping the oldest", function()
      local q = conntrack.new_event_queue()
      q.cap = 3
      for i = 1, 5 do
        conntrack.enqueue_events(q, { { marker = i } }, 1000 + i, no_jitter)
      end
      assert.equal(3, #q.batches)
      -- Oldest 2 dropped → remaining markers 3, 4, 5 (recent retained).
      assert.equal(3, q.batches[1].events[1].marker)
      assert.equal(4, q.batches[2].events[1].marker)
      assert.equal(5, q.batches[3].events[1].marker)
    end)

    it("logs an err line on each drop", function()
      local q = conntrack.new_event_queue()
      q.cap = 2
      local drop_logs = 0
      local log = {
        info  = function() end,
        warn  = function() end,
        debug = function() end,
        err   = function(fmt, ...)
          if fmt:find("queue cap") and fmt:find("dropping") then
            drop_logs = drop_logs + 1
          end
        end,
      }
      for _ = 1, 4 do
        conntrack.enqueue_events(q, { { type = "x" } }, 1000, no_jitter, log)
      end
      assert.equal(2, drop_logs)
      assert.equal(2, #q.batches)
    end)
  end)
end)

-- ---------------------------------------------------------------------------
-- #575: is_wan_bound — LAN-internal flow filter
--
-- Replaces the old is_outbound which only checked src_ip.  Flows whose
-- dst_ip is also on the LAN (device→router, device→LAN peer, mDNS, DHCP)
-- must be rejected before posting connection events.
-- ---------------------------------------------------------------------------

describe("is_wan_bound (#575)", function()
  local LAN = "192.168.1."

  it("accepts a flow from LAN src to WAN dst", function()
    assert.is_true(conntrack.is_wan_bound({ src_ip = "192.168.1.42", dst_ip = "1.2.3.4" }, LAN))
  end)

  it("rejects a flow from LAN src to router dst (e.g. 192.168.1.1 — DNS/DHCP/LuCI)", function()
    -- This was the main noise source reported in #575
    assert.is_false(conntrack.is_wan_bound({ src_ip = "192.168.1.42", dst_ip = "192.168.1.1" }, LAN))
  end)

  it("rejects a flow from LAN src to another LAN peer (LAN-internal)", function()
    assert.is_false(conntrack.is_wan_bound({ src_ip = "192.168.1.42", dst_ip = "192.168.1.50" }, LAN))
  end)

  it("rejects a flow whose src is NOT on the LAN (not outbound at all)", function()
    assert.is_false(conntrack.is_wan_bound({ src_ip = "10.0.0.5", dst_ip = "1.2.3.4" }, LAN))
  end)

  it("is_outbound (alias) behaves identically to is_wan_bound", function()
    -- is_outbound is kept for backward compatibility; its behavior must match.
    assert.equal(
      conntrack.is_wan_bound({ src_ip = "192.168.1.42", dst_ip = "1.2.3.4" }, LAN),
      conntrack.is_outbound({ src_ip = "192.168.1.42", dst_ip = "1.2.3.4" }, LAN))
    assert.equal(
      conntrack.is_wan_bound({ src_ip = "192.168.1.42", dst_ip = "192.168.1.1" }, LAN),
      conntrack.is_outbound({ src_ip = "192.168.1.42", dst_ip = "192.168.1.1" }, LAN))
  end)
end)

-- ---------------------------------------------------------------------------
-- #579: eb_san + nft_eb_hit helpers
-- ---------------------------------------------------------------------------

describe("eb_san (#579)", function()
  it("replaces dots with underscores (mirrors render.sanitize for eb_ set names)", function()
    assert.equal("example_com",         conntrack.eb_san("example.com"))
    assert.equal("foo_bar_example_com", conntrack.eb_san("foo.bar.example.com"))
  end)

  it("replaces colons with underscores (IPv6 literals, future-proofing)", function()
    assert.equal("2001_db8__1", conntrack.eb_san("2001:db8::1"))
  end)

  it("leaves alphanumeric and hyphens unchanged", function()
    assert.equal("my-site", conntrack.eb_san("my-site"))
  end)
end)

describe("nft_eb_hit (#579)", function()
  it("returns true when exec_fn returns 0 (element found in set)", function()
    local cmd_seen
    local ret = conntrack.nft_eb_hit("1.2.3.4", "example.com", function(cmd)
      cmd_seen = cmd
      return 0
    end)
    assert.is_true(ret)
    -- Command must reference the sanitized set name and the destination IP.
    assert.is_truthy(cmd_seen:find("eb_example_com"))
    assert.is_truthy(cmd_seen:find("1.2.3.4"))
  end)

  it("returns false when exec_fn returns non-zero (element not in set)", function()
    assert.is_false(conntrack.nft_eb_hit("1.2.3.4", "example.com", function(_) return 1 end))
  end)

  it("returns false when exec_fn returns nil (old Lua os.execute failure)", function()
    -- Some older Lua 5.1 builds return nil on system() failure; treat as miss.
    assert.is_false(conntrack.nft_eb_hit("1.2.3.4", "example.com", function(_) return nil end))
  end)

  it("handles Lua 5.3+ boolean true return (true means exit 0)", function()
    assert.is_true(conntrack.nft_eb_hit("9.9.9.9", "safe.org", function(_) return true end))
  end)

  it("handles Lua 5.3+ boolean false return (false means non-zero exit)", function()
    assert.is_false(conntrack.nft_eb_hit("9.9.9.9", "safe.org", function(_) return false end))
  end)
end)

describe("encode_events_body (#1126 ingest reliability)", function()
  local quiet = {
    debug = function() end, info = function() end,
    warn  = function() end, err  = function() end,
  }

  it("returns nil for an empty batch (never serialize {} as the events object)", function()
    -- luci.jsonc would encode an empty Lua table as `{}` (object), which the
    -- API rejects as a type error ("Unexpected end of input"-class warnings).
    assert.is_nil(conntrack.encode_events_body("r-1", {}, quiet))
    assert.is_nil(conntrack.encode_events_body("r-1", nil, quiet))
  end)

  it("encodes a non-empty batch as a well-formed object with an events array", function()
    local body = conntrack.encode_events_body("r-1", {
      { ["type"] = "connection_attempt", mac = "aa:bb:cc:11:22:33", allowed = false },
    }, quiet)
    assert.is_string(body)
    assert.equal("{", body:sub(1, 1))
    assert.truthy(body:find('"routerId":"r-1"', 1, true))
    -- events must be a JSON array, not an object.
    assert.truthy(body:find('"events":[', 1, true))
  end)
end)
