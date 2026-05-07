-- Tests for openwrt/files/usr/lib/familydns/conntrack.lua
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
    assert.equal("1.2.3.4",            ev.dest_ip)
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
    assert.equal("1.1.1.1",            decoded.dest_ip)   -- snake_case, not camelCase
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
-- 5. Retry logic
-- ---------------------------------------------------------------------------

describe("post_with_retry", function()
  local MAX_RETRIES    = 3
  local BASE_DELAY_SEC = 0  -- set to 0 in tests so they don't sleep

  it("succeeds on first attempt and returns true", function()
    local calls = 0
    local function post_fn(_url, _body)
      calls = calls + 1
      return 200, ""
    end
    local ok = conntrack.post_with_retry("http://api/events", "{}", MAX_RETRIES, 0, post_fn)
    assert.is_true(ok)
    assert.equal(1, calls)
  end)

  it("retries on 5xx and succeeds on second attempt", function()
    local calls = 0
    local function post_fn(_url, _body)
      calls = calls + 1
      if calls < 2 then return 500, "err" end
      return 200, ""
    end
    local ok = conntrack.post_with_retry("http://api/events", "{}", MAX_RETRIES, 0, post_fn)
    assert.is_true(ok)
    assert.equal(2, calls)
  end)

  it("drops and returns false after max retries are exhausted", function()
    local calls = 0
    local function post_fn(_url, _body)
      calls = calls + 1
      return 503, "unavailable"
    end
    local ok = conntrack.post_with_retry("http://api/events", "{}", MAX_RETRIES, 0, post_fn)
    assert.is_false(ok)
    assert.equal(MAX_RETRIES, calls)
  end)

  it("does not retry on 4xx (client error)", function()
    local calls = 0
    local function post_fn(_url, _body)
      calls = calls + 1
      return 401, "unauthorized"
    end
    local ok = conntrack.post_with_retry("http://api/events", "{}", MAX_RETRIES, 0, post_fn)
    assert.is_false(ok)
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
