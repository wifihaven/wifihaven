-- Tests for openwrt/files/usr/lib/lua/familydns/conntrack.lua
-- Run with: busted openwrt/test/conntrack_spec.lua
-- Requires: busted (luarocks install busted)

local conntrack = require("conntrack")

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
    assert.equal("youtube.com",        ev.hostname)
    assert.equal("1.2.3.4",            ev.destIp)
    assert.equal(false,                ev.allowed)
    assert.equal("category:adult",     ev.reason)
    assert.equal("2026-05-07T14:01:14Z", ev.ts)
  end)

  it("uses dest_ip as hostname fallback when hostname is nil", function()
    local ev = conntrack.build_event({
      mac      = "aa:bb:cc:11:22:33",
      hostname = nil,
      dest_ip  = "9.9.9.9",
      allowed  = true,
      reason   = nil,
      ts       = "2026-05-07T14:01:14Z",
    })
    assert.equal("9.9.9.9", ev.hostname)
    assert.equal("allow",   ev.reason)
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
    assert.equal("example.com",        decoded.hostname)
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
    local leases = conntrack.parse_dhcp_leases("/tmp/__does_not_exist_familydns__")
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
      blocked_ips    = {},
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
