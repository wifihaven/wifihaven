-- Tests for openwrt/files/usr/lib/lua/wifihaven/usage.lua
-- Run with: cd openwrt && busted test/usage_spec.lua
--
-- Input shape: `nft -j list set inet wifihaven mac_ip_tracking`.
-- The `mac_ip_tracking` dynamic set is declared in render.lua with
-- `flags dynamic,timeout` + `counter`, so each set element carries its own
-- counter and the JSON layout is:
--   nftables[*].set.elem[*].elem.{val.concat:[<mac>,<ip>], counter:{packets,bytes}}

local usage = require("usage")

local NFT_JSON = [[{
  "nftables": [
    { "metainfo": { "version": "1.1.6", "json_schema_version": 1 } },
    { "set": {
        "family": "inet", "table": "wifihaven", "name": "mac_ip_tracking",
        "type": ["ether_addr", "ipv4_addr"],
        "flags": ["timeout", "dynamic"],
        "elem": [
          { "elem": {
              "val": { "concat": ["aa:bb:cc:11:22:33", "1.2.3.4"] },
              "expires": 21000,
              "counter": { "packets": 100, "bytes": 50000 } } },
          { "elem": {
              "val": { "concat": ["de:ad:be:ef:00:01", "8.8.8.8"] },
              "expires": 21000,
              "counter": { "packets": 20, "bytes": 1024 } } }
        ]
    }}
  ]
}]]

-- ── parse_nft_counters ────────────────────────────────────────────────────

describe("usage.parse_nft_counters", function()

  it("returns one entry per set element (skips metainfo)", function()
    local counters = usage.parse_nft_counters(NFT_JSON)
    assert.equal(2, #counters)
  end)

  it("extracts bytes and packets from each element counter", function()
    local counters = usage.parse_nft_counters(NFT_JSON)
    local by_mac = {}
    for _, c in ipairs(counters) do by_mac[c.mac] = c end
    assert.equal(50000, by_mac["aa:bb:cc:11:22:33"].bytes)
    assert.equal(100,   by_mac["aa:bb:cc:11:22:33"].packets)
    assert.equal(1024,  by_mac["de:ad:be:ef:00:01"].bytes)
  end)

  it("extracts mac and dst_ip from the val.concat tuple", function()
    local counters = usage.parse_nft_counters(NFT_JSON)
    local by_mac = {}
    for _, c in ipairs(counters) do by_mac[c.mac] = c end
    assert.equal("aa:bb:cc:11:22:33", by_mac["aa:bb:cc:11:22:33"].mac)
    assert.equal("1.2.3.4",           by_mac["aa:bb:cc:11:22:33"].dst_ip)
    assert.equal("de:ad:be:ef:00:01", by_mac["de:ad:be:ef:00:01"].mac)
    assert.equal("8.8.8.8",           by_mac["de:ad:be:ef:00:01"].dst_ip)
  end)

  it("returns empty list when the set has no elements", function()
    local empty = [[{"nftables":[
      {"metainfo":{"version":"1.1.6"}},
      {"set":{"name":"mac_ip_tracking"}}
    ]}]]
    assert.equal(0, #usage.parse_nft_counters(empty))
  end)

  it("returns empty list when no set entry is present", function()
    local empty = '{"nftables":[{"metainfo":{"version":"1.1.6"}}]}'
    assert.equal(0, #usage.parse_nft_counters(empty))
  end)

  it("handles a multi-octet IP like 192.168.100.200 correctly", function()
    local json_str = [[{"nftables":[
      {"set":{"name":"mac_ip_tracking","elem":[
        {"elem":{"val":{"concat":["aa:bb:cc:11:22:33","192.168.100.200"]},
                 "counter":{"packets":5,"bytes":999}}}
      ]}}
    ]}]]
    local counters = usage.parse_nft_counters(json_str)
    assert.equal(1, #counters)
    assert.equal("192.168.100.200", counters[1].dst_ip)
  end)

end)

-- ── merge_counters (#717) ─────────────────────────────────────────────────
--
-- The agent reads the two per-direction nft sets — `mac_ip_tracking` (tx,
-- device→remote) and `mac_ip_tracking_rx` (rx, remote→device) — and joins
-- them on (mac, dst_ip) before passing to the tracker and build_report.

describe("usage.merge_counters", function()

  it("joins matching (mac, dst_ip) into one record with both directions populated", function()
    local tx = { { mac = "aa:bb:cc:11:22:33", dst_ip = "1.2.3.4", bytes = 100, packets = 3 } }
    local rx = { { mac = "aa:bb:cc:11:22:33", dst_ip = "1.2.3.4", bytes = 900, packets = 7 } }
    local merged = usage.merge_counters(tx, rx)
    assert.equal(1, #merged)
    assert.equal(100, merged[1].bytes)
    assert.equal(3,   merged[1].packets)
    assert.equal(900, merged[1].bytes_out)
    assert.equal(7,   merged[1].packets_out)
  end)

  it("emits records for tx-only and rx-only (mac, dst_ip) pairs with the missing side zeroed", function()
    local tx = { { mac = "aa:bb:cc:11:22:33", dst_ip = "1.2.3.4", bytes = 100, packets = 1 } }
    local rx = { { mac = "de:ad:be:ef:00:01", dst_ip = "8.8.8.8", bytes = 200, packets = 2 } }
    local merged = usage.merge_counters(tx, rx)
    assert.equal(2, #merged)
    local by_mac = {}
    for _, c in ipairs(merged) do by_mac[c.mac] = c end
    assert.equal(100, by_mac["aa:bb:cc:11:22:33"].bytes)
    assert.equal(0,   by_mac["aa:bb:cc:11:22:33"].bytes_out)
    assert.equal(0,   by_mac["de:ad:be:ef:00:01"].bytes)
    assert.equal(200, by_mac["de:ad:be:ef:00:01"].bytes_out)
  end)

  it("treats nil tx or rx as empty (single-direction snapshot during a render swap)", function()
    local rx = { { mac = "aa:bb:cc:11:22:33", dst_ip = "1.2.3.4", bytes = 500, packets = 4 } }
    local merged = usage.merge_counters(nil, rx)
    assert.equal(1, #merged)
    assert.equal(0,   merged[1].bytes)
    assert.equal(500, merged[1].bytes_out)
  end)

  it("returns an empty list when both sides are empty", function()
    assert.equal(0, #usage.merge_counters({}, {}))
  end)

end)

-- ── build_report ──────────────────────────────────────────────────────────

describe("usage.build_report", function()

  -- nft_sets mirrors what render.lua + dnsmasq --ipset= populate at runtime
  local NF_SETS = {
    ["youtube.com"] = { ["1.2.3.4"] = true },
    ["google.com"]  = { ["8.8.8.8"] = true },
  }

  local COUNTERS = {
    { mac = "aa:bb:cc:11:22:33", dst_ip = "1.2.3.4", bytes = 50000, packets = 100 },
    { mac = "de:ad:be:ef:00:01", dst_ip = "8.8.8.8", bytes =  1024, packets =  20 },
  }

  local P_START  = "2026-05-08T14:00:00Z"
  local P_END    = "2026-05-08T14:01:00Z"
  local ROUTER   = "9c1f2e8a-0000-0000-0000-000000000001"
  local SAMPLE_S = 10
  local BUCKET_S = 60

  -- Build a tracker pre-populated so that every counter in `counters` resolves
  -- to a full bucket of activeSeconds via the normal sample_seconds × samples
  -- path. Keeps these tests focused on attribution / shape, not activity math.
  local function full_tracker(counters)
    local t = usage.new_tracker()
    for _, c in ipairs(counters) do
      t.active_samples[c.mac .. "|" .. c.dst_ip] = BUCKET_S / SAMPLE_S
    end
    return t
  end

  it("sets routerId, periodStart, periodEnd on the top-level report", function()
    local r = usage.build_report(COUNTERS, NF_SETS, P_START, P_END, ROUTER,
                                 nil, nil, full_tracker(COUNTERS), SAMPLE_S, BUCKET_S)
    assert.equal(ROUTER,  r.routerId)
    assert.equal(P_START, r.periodStart)
    assert.equal(P_END,   r.periodEnd)
  end)

  it("produces one record per (mac, host) pair", function()
    local r = usage.build_report(COUNTERS, NF_SETS, P_START, P_END, ROUTER,
                                 nil, nil, full_tracker(COUNTERS), SAMPLE_S, BUCKET_S)
    assert.equal(2, #r.records)
  end)

  it("attributes bytes to the hostname the dst_ip resolved to (via nft_sets)", function()
    local r = usage.build_report(COUNTERS, NF_SETS, P_START, P_END, ROUTER,
                                 nil, nil, full_tracker(COUNTERS), SAMPLE_S, BUCKET_S)
    local by_host = {}
    for _, rec in ipairs(r.records) do by_host[rec.host.value] = rec end
    assert.equal("fqdn",             by_host["youtube.com"].host.type)
    assert.equal("youtube.com",      by_host["youtube.com"].host.value)
    assert.equal("aa:bb:cc:11:22:33", by_host["youtube.com"].mac)
    assert.equal(50000,               by_host["youtube.com"].bytesIn)
  end)

  -- #717: bytesOut must be wired through from the merged counter's
  -- bytes_out field (the rx nft set), not hard-coded to 0.
  it("populates bytesOut from c.bytes_out (download direction, #717)", function()
    local counters = {
      { mac = "aa:bb:cc:11:22:33", dst_ip = "1.2.3.4",
        bytes = 50000, packets = 100, bytes_out = 4000000, packets_out = 3500 },
    }
    local r = usage.build_report(counters, NF_SETS, P_START, P_END, ROUTER,
                                 nil, nil, full_tracker(counters), SAMPLE_S, BUCKET_S)
    assert.equal(50000,    r.records[1].bytesIn)
    assert.equal(4000000,  r.records[1].bytesOut)
  end)

  it("defaults bytesOut to 0 when c.bytes_out is missing (tx-only snapshot)", function()
    local r = usage.build_report(COUNTERS, NF_SETS, P_START, P_END, ROUTER,
                                 nil, nil, full_tracker(COUNTERS), SAMPLE_S, BUCKET_S)
    for _, rec in ipairs(r.records) do
      assert.equal(0, rec.bytesOut)
    end
  end)

  it("credits one sample when bytes=0 but bytes_out>0 (rx-only flow first appearing post-tick)", function()
    local counters = {
      { mac = "aa:bb:cc:11:22:33", dst_ip = "1.2.3.4",
        bytes = 0, packets = 0, bytes_out = 1234, packets_out = 5 },
    }
    -- Empty tracker → falls through to the post-tick path. That path
    -- previously gated on `c.bytes > 0`; with #717 it must also treat
    -- bytes_out > 0 as activity.
    local r = usage.build_report(counters, NF_SETS, P_START, P_END, ROUTER,
                                 nil, nil, usage.new_tracker(), SAMPLE_S, BUCKET_S)
    assert.equal(SAMPLE_S, r.records[1].activeSeconds)
    assert.equal(1234,     r.records[1].bytesOut)
  end)

  -- #391: the "unknown" sentinel is gone. When DNS attribution misses we emit
  -- the dst_ip literal tagged as ipv4 or ipv6.
  it("falls back to dst_ip with type='ipv4' when dst_ip is not in any nft_set", function()
    local unk = { { mac = "aa:bb:cc:11:22:33", dst_ip = "9.9.9.9", bytes = 100, packets = 3 } }
    local r = usage.build_report(unk, NF_SETS, P_START, P_END, ROUTER,
                                 nil, nil, full_tracker(unk), SAMPLE_S, BUCKET_S)
    assert.equal(1, #r.records)
    assert.equal("ipv4",   r.records[1].host.type)
    assert.equal("9.9.9.9", r.records[1].host.value)
  end)

  it("falls back to dst_ip with type='ipv6' for an IPv6 dst_ip with no attribution", function()
    local unk = { { mac = "aa:bb:cc:11:22:33", dst_ip = "2001:db8::1", bytes = 100, packets = 3 } }
    local r = usage.build_report(unk, NF_SETS, P_START, P_END, ROUTER,
                                 nil, nil, full_tracker(unk), SAMPLE_S, BUCKET_S)
    assert.equal(1, #r.records)
    assert.equal("ipv6",       r.records[1].host.type)
    assert.equal("2001:db8::1", r.records[1].host.value)
  end)

  -- #287: nft_sets only carries hostnames for site_limits-tracked domains, so
  -- without the dnsmasq-query-log lookup every traffic_reports row landed with
  -- just the IP and the Sessions UI rendered every flow as unknown traffic.
  it("consults lookup_hostname (dns-cache) before falling back to IP", function()
    local counters = {
      { mac = "aa:bb:cc:11:22:33", dst_ip = "140.82.114.6", bytes = 100, packets = 3 },
    }
    local lookup = function(ip)
      if ip == "140.82.114.6" then return "api.github.com" end
      return nil
    end
    local r = usage.build_report(counters, {}, P_START, P_END, ROUTER,
                                 nil, lookup, full_tracker(counters), SAMPLE_S, BUCKET_S)
    assert.equal("fqdn",          r.records[1].host.type)
    assert.equal("api.github.com", r.records[1].host.value)
  end)

  it("prefers lookup_hostname over nft_sets when both have an entry", function()
    -- nft_sets reflects what dnsmasq's ipset= callback stored at resolve time
    -- for site_limits domains. If the dns-cache has a hit too, we trust that
    -- since it's the hostname the *client actually resolved*, not whatever
    -- ipset bucket the IP happens to live in.
    local counters = {
      { mac = "aa:bb:cc:11:22:33", dst_ip = "1.2.3.4", bytes = 100, packets = 3 },
    }
    local sets = { ["site_limit.example"] = { ["1.2.3.4"] = true } }
    local lookup = function(_) return "actual.example" end
    local r = usage.build_report(counters, sets, P_START, P_END, ROUTER,
                                 nil, lookup, full_tracker(counters), SAMPLE_S, BUCKET_S)
    assert.equal("fqdn",           r.records[1].host.type)
    assert.equal("actual.example", r.records[1].host.value)
  end)

  it("falls through to nft_sets when lookup_hostname misses", function()
    local counters = {
      { mac = "aa:bb:cc:11:22:33", dst_ip = "1.2.3.4", bytes = 100, packets = 3 },
    }
    local sets = { ["youtube.com"] = { ["1.2.3.4"] = true } }
    local lookup = function(_) return nil end
    local r = usage.build_report(counters, sets, P_START, P_END, ROUTER,
                                 nil, lookup, full_tracker(counters), SAMPLE_S, BUCKET_S)
    assert.equal("fqdn",        r.records[1].host.type)
    assert.equal("youtube.com", r.records[1].host.value)
  end)

  it("sets activeSeconds = bucket_seconds when the tracker observed a full bucket", function()
    local r = usage.build_report(COUNTERS, NF_SETS, P_START, P_END, ROUTER,
                                 nil, nil, full_tracker(COUNTERS), SAMPLE_S, BUCKET_S)
    for _, rec in ipairs(r.records) do
      assert.equal(BUCKET_S, rec.activeSeconds)
    end
  end)

  it("includes the mac address on each record", function()
    local r = usage.build_report(COUNTERS, NF_SETS, P_START, P_END, ROUTER,
                                 nil, nil, full_tracker(COUNTERS), SAMPLE_S, BUCKET_S)
    for _, rec in ipairs(r.records) do
      assert.truthy(rec.mac and #rec.mac > 0)
    end
  end)

  it("includes a non-nil ip field on each record (last-seen-ip for the API)", function()
    -- ip comes from a leases lookup; usage.build_report accepts an optional leases table
    local leases = {
      ["aa:bb:cc:11:22:33"] = "192.168.1.42",
      ["de:ad:be:ef:00:01"] = "192.168.1.10",
    }
    local r = usage.build_report(COUNTERS, NF_SETS, P_START, P_END, ROUTER,
                                 leases, nil, full_tracker(COUNTERS), SAMPLE_S, BUCKET_S)
    local by_mac = {}
    for _, rec in ipairs(r.records) do by_mac[rec.mac] = rec end
    assert.equal("192.168.1.42", by_mac["aa:bb:cc:11:22:33"].ip)
    assert.equal("192.168.1.10", by_mac["de:ad:be:ef:00:01"].ip)
  end)

  it("ip field is nil (not present) when leases table is not provided", function()
    local r = usage.build_report(COUNTERS, NF_SETS, P_START, P_END, ROUTER,
                                 nil, nil, full_tracker(COUNTERS), SAMPLE_S, BUCKET_S)
    -- no leases arg → ip field should be absent or nil on each record
    for _, rec in ipairs(r.records) do
      assert.is_nil(rec.ip)
    end
  end)

  it("JSON-encodes cleanly with all required API fields present", function()
    local json = require("cjson")
    local r    = usage.build_report(COUNTERS, NF_SETS, P_START, P_END, ROUTER,
                                    nil, nil, full_tracker(COUNTERS), SAMPLE_S, BUCKET_S)
    local dec  = json.decode(json.encode(r))
    assert.not_nil(dec.routerId)
    assert.not_nil(dec.periodStart)
    assert.not_nil(dec.periodEnd)
    assert.not_nil(dec.records)
    local rec = dec.records[1]
    assert.not_nil(rec.mac)
    assert.not_nil(rec.host)
    assert.not_nil(rec.host.type)
    assert.not_nil(rec.host.value)
    assert.not_nil(rec.activeSeconds)
    assert.not_nil(rec.bytesIn)
  end)

  it("handles an empty counters list (zero records)", function()
    local r = usage.build_report({}, NF_SETS, P_START, P_END, ROUTER,
                                 nil, nil, usage.new_tracker(), SAMPLE_S, BUCKET_S)
    assert.equal(0, #r.records)
  end)

end)

-- ── tracker (sub-minute activity sampler, #295, #516) ────────────────────
--
-- The router scrapes nft counters every sample_seconds (10 s) within the
-- usage bucket and feeds them to a tracker; the tracker remembers how many
-- distinct samples each (mac, dst_ip) saw counter growth, which build_report
-- converts into activeSeconds.  Counter reset / set-element expiry (bytes
-- goes DOWN) is treated as a fresh start so we don't double-count.

describe("usage.tracker", function()

  local function s(mac, dst_ip, bytes)
    return { mac = mac, dst_ip = dst_ip, bytes = bytes, packets = 0 }
  end

  it("new_tracker returns an empty tracker", function()
    local t = usage.new_tracker()
    assert.is_table(t)
    assert.is_table(t.active_samples)
    assert.is_nil(next(t.active_samples))
  end)

  it("counts a single sample with bytes > 0 (from 0 baseline) as 1 active sample", function()
    local t = usage.new_tracker()
    usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4", 100) })
    assert.equal(1, t.active_samples["aa:bb:cc:11:22:33|1.2.3.4"])
  end)

  it("counts each subsequent sample with growth as another active sample", function()
    local t = usage.new_tracker()
    usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4",  100) })
    usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4",  500) })
    usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4", 1200) })
    assert.equal(3, t.active_samples["aa:bb:cc:11:22:33|1.2.3.4"])
  end)

  it("does NOT count a sample where bytes are unchanged (no active sample)", function()
    local t = usage.new_tracker()
    usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4", 100) })  -- +1
    usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4", 100) })  -- no growth
    usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4", 100) })  -- no growth
    assert.equal(1, t.active_samples["aa:bb:cc:11:22:33|1.2.3.4"])
  end)

  it("treats a counter decrease (reset / element-expire-and-reappear) as a fresh active sample", function()
    local t = usage.new_tracker()
    usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4", 500) })  -- +1
    usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4",  50) })  -- reset, bytes>0 → +1
    assert.equal(2, t.active_samples["aa:bb:cc:11:22:33|1.2.3.4"])
  end)

  it("does not count a counter decrease to 0 as an active sample", function()
    local t = usage.new_tracker()
    usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4", 500) })  -- +1
    usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4",   0) })  -- decrease to 0 → no
    assert.equal(1, t.active_samples["aa:bb:cc:11:22:33|1.2.3.4"])
  end)

  it("tracks each (mac, dst_ip) independently", function()
    local t = usage.new_tracker()
    usage.tracker_sample(t, {
      s("aa:bb:cc:11:22:33", "1.2.3.4", 100),
      s("de:ad:be:ef:00:01", "8.8.8.8", 200),
    })
    usage.tracker_sample(t, {
      s("aa:bb:cc:11:22:33", "1.2.3.4", 100),     -- no growth
      s("de:ad:be:ef:00:01", "8.8.8.8", 999),     -- growth
    })
    assert.equal(1, t.active_samples["aa:bb:cc:11:22:33|1.2.3.4"])
    assert.equal(2, t.active_samples["de:ad:be:ef:00:01|8.8.8.8"])
  end)

  it("tracks each dst_ip independently for the same mac", function()
    local t = usage.new_tracker()
    usage.tracker_sample(t, {
      s("aa:bb:cc:11:22:33", "1.2.3.4", 100),
      s("aa:bb:cc:11:22:33", "5.6.7.8", 200),
    })
    usage.tracker_sample(t, {
      s("aa:bb:cc:11:22:33", "1.2.3.4", 100),
      s("aa:bb:cc:11:22:33", "5.6.7.8", 800),
    })
    assert.equal(1, t.active_samples["aa:bb:cc:11:22:33|1.2.3.4"])
    assert.equal(2, t.active_samples["aa:bb:cc:11:22:33|5.6.7.8"])
  end)

  -- #717: counter total is bytes + bytes_out, so a download-only flow still
  -- counts as active even when bytes (tx) stays at 0 across samples.
  it("counts activity from bytes_out growth alone (rx-only flow, #717)", function()
    local function rx_only(mac, dst_ip, bytes_out)
      return { mac = mac, dst_ip = dst_ip, bytes = 0, packets = 0,
               bytes_out = bytes_out, packets_out = 0 }
    end
    local t = usage.new_tracker()
    usage.tracker_sample(t, { rx_only("aa:bb:cc:11:22:33", "1.2.3.4",  500) })
    usage.tracker_sample(t, { rx_only("aa:bb:cc:11:22:33", "1.2.3.4", 1500) })
    usage.tracker_sample(t, { rx_only("aa:bb:cc:11:22:33", "1.2.3.4", 1500) })  -- no growth
    assert.equal(2, t.active_samples["aa:bb:cc:11:22:33|1.2.3.4"])
  end)

  it("tracker_reset clears all state", function()
    local t = usage.new_tracker()
    usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4", 100) })
    usage.tracker_reset(t)
    assert.is_nil(next(t.active_samples))
    -- After reset, the next sample starts from a fresh 0 baseline.
    usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4", 50) })
    assert.equal(1, t.active_samples["aa:bb:cc:11:22:33|1.2.3.4"])
  end)

end)

describe("usage.build_report with tracker", function()

  local NF_SETS  = { ["youtube.com"] = { ["1.2.3.4"] = true } }
  local P_START  = "2026-05-08T14:00:00Z"
  local P_END    = "2026-05-08T14:05:00Z"
  local ROUTER   = "9c1f2e8a-0000-0000-0000-000000000001"
  local SAMPLE_S = 10
  local BUCKET_S = 300

  local function s(mac, dst_ip, bytes)
    return { mac = mac, dst_ip = dst_ip, bytes = bytes, packets = 0 }
  end

  it("uses sample_seconds * active_samples from the tracker (1 sample → 10s)", function()
    local t = usage.new_tracker()
    usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4", 500) })
    local r = usage.build_report(
      { s("aa:bb:cc:11:22:33", "1.2.3.4", 500) },
      NF_SETS, P_START, P_END, ROUTER, nil, nil, t, SAMPLE_S, BUCKET_S)
    assert.equal(10, r.records[1].activeSeconds)
  end)

  it("reports bucket_seconds when all 30 samples (5 min @ 10s) were active", function()
    local t = usage.new_tracker()
    for i = 1, 30 do
      usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4", 100 * i) })
    end
    local r = usage.build_report(
      { s("aa:bb:cc:11:22:33", "1.2.3.4", 3000) },
      NF_SETS, P_START, P_END, ROUTER, nil, nil, t, SAMPLE_S, BUCKET_S)
    assert.equal(BUCKET_S, r.records[1].activeSeconds)
  end)

  it("caps activeSeconds at bucket_seconds even if the tracker recorded more (drift)", function()
    local t = usage.new_tracker()
    for i = 1, 35 do
      usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4", 100 * i) })
    end
    local r = usage.build_report(
      { s("aa:bb:cc:11:22:33", "1.2.3.4", 3500) },
      NF_SETS, P_START, P_END, ROUTER, nil, nil, t, SAMPLE_S, BUCKET_S)
    assert.equal(BUCKET_S, r.records[1].activeSeconds)
  end)

  it("falls back to one sample (sample_seconds) when bytes > 0 but tracker has no entry", function()
    local t = usage.new_tracker()
    -- tracker has seen device A but not B
    usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4", 100) })
    local r = usage.build_report(
      { s("de:ad:be:ef:00:01", "9.9.9.9", 200) },
      NF_SETS, P_START, P_END, ROUTER, nil, nil, t, SAMPLE_S, BUCKET_S)
    assert.equal(SAMPLE_S, r.records[1].activeSeconds)
  end)

  it("activeSeconds = 0 when tracker has no entry and bytes = 0", function()
    local t = usage.new_tracker()
    local r = usage.build_report(
      { s("aa:bb:cc:11:22:33", "1.2.3.4", 0) },
      NF_SETS, P_START, P_END, ROUTER, nil, nil, t, SAMPLE_S, BUCKET_S)
    assert.equal(0, r.records[1].activeSeconds)
  end)

  it("assigns activeSeconds per (mac, dst_ip) independently from the same tracker", function()
    local t = usage.new_tracker()
    -- device A: 1 active sample (10s)
    usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4", 100) })
    -- device B joins later with growth across 4 more samples (40s total)
    for i = 1, 4 do
      usage.tracker_sample(t, {
        s("aa:bb:cc:11:22:33", "1.2.3.4", 100),         -- no growth for A
        s("de:ad:be:ef:00:01", "8.8.8.8", 100 * i),     -- growth for B
      })
    end
    local r = usage.build_report({
      s("aa:bb:cc:11:22:33", "1.2.3.4", 100),
      s("de:ad:be:ef:00:01", "8.8.8.8", 400),
    }, NF_SETS, P_START, P_END, ROUTER, nil, nil, t, SAMPLE_S, BUCKET_S)
    local by_mac = {}
    for _, rec in ipairs(r.records) do by_mac[rec.mac] = rec end
    assert.equal(10, by_mac["aa:bb:cc:11:22:33"].activeSeconds)
    assert.equal(40, by_mac["de:ad:be:ef:00:01"].activeSeconds)
  end)

end)

-- ── post ──────────────────────────────────────────────────────────────────

describe("usage.post", function()

  local SAMPLE_REC = { mac="aa:bb:cc:11:22:33", host={ type="fqdn", value="x" }, activeSeconds=300, bytesIn=1, bytesOut=0 }
  local function with_records()
    return { routerId = "r1", periodStart = "t0", periodEnd = "t1", records = { SAMPLE_REC } }
  end

  it("POSTs to /api/router/usage with correct Authorization header", function()
    local got_url, got_hdrs, got_body
    local function post_fn(url, body, hdrs)
      got_url = url; got_body = body; got_hdrs = hdrs
      return 200, ""
    end
    local ok = usage.post("http://api:8080", "rt_tok", with_records(), post_fn)
    assert.is_true(ok)
    assert.truthy(got_url:find("/api/router/usage", 1, true))
    assert.equal("Bearer rt_tok", got_hdrs["Authorization"])
  end)

  it("JSON-encodes the report as the POST body", function()
    local json = require("cjson")
    local got_body
    local function post_fn(_url, body, _hdrs) got_body = body; return 200, "" end
    usage.post("http://api:8080", "rt_tok", with_records(), post_fn)
    local dec = json.decode(got_body)
    assert.equal("r1", dec.routerId)
  end)

  it("sets Content-Type: application/json", function()
    local got_hdrs
    local function post_fn(_url, _body, hdrs) got_hdrs = hdrs; return 200, "" end
    usage.post("http://api:8080", "rt_tok", with_records(), post_fn)
    assert.equal("application/json", got_hdrs["Content-Type"])
  end)

  it("returns false on HTTP 5xx", function()
    local function post_fn(_url, _body, _hdrs) return 500, "error" end
    assert.is_false(usage.post("http://api:8080", "rt_tok", with_records(), post_fn))
  end)

  it("returns false when post_fn returns nil status (connection failure)", function()
    local function post_fn(_url, _body, _hdrs) return nil, "" end
    assert.is_false(usage.post("http://api:8080", "rt_tok", with_records(), post_fn))
  end)

  it("skips POST and returns true when records is empty", function()
    local called = false
    local function post_fn(_url, _body, _hdrs) called = true; return 200, "" end
    local report = { routerId = "r1", periodStart = "t0", periodEnd = "t1", records = {} }
    assert.is_true(usage.post("http://api:8080", "rt_tok", report, post_fn))
    assert.is_false(called)
  end)

end)

-- ── usage retry queue (#309) ──────────────────────────────────────────────

describe("usage retry queue", function()

  local SAMPLE_REC = { mac = "aa:bb:cc:11:22:33", host = { type = "fqdn", value = "x" },
                       activeSeconds = 300, bytesIn = 1, bytesOut = 0 }

  local function report(period_start, period_end)
    return {
      routerId    = "r1",
      periodStart = period_start,
      periodEnd   = period_end,
      records     = { SAMPLE_REC },
    }
  end

  -- Deterministic "jitter" — return the midpoint (no randomness) so tests
  -- exercise exact nominal backoff values.
  local function no_jitter() return 0.5 end

  describe("post_with_retry", function()

    it("posts immediately and leaves queue empty on success", function()
      local q = usage.new_queue()
      local function post_fn(_url, _body, _hdrs) return 200, "" end
      local ok = usage.post_with_retry(
        "http://api", "tok", report("t0", "t1"), post_fn, q, 1000, no_jitter)
      assert.is_true(ok)
      assert.equal(0, #q.buckets)
    end)

    it("enqueues the report with next_attempt_at ≈ now + 30 on first failure", function()
      local q = usage.new_queue()
      local function post_fn(_url, _body, _hdrs) return 500, "err" end
      local ok = usage.post_with_retry(
        "http://api", "tok", report("t0", "t1"), post_fn, q, 1000, no_jitter)
      assert.is_false(ok)
      assert.equal(1, #q.buckets)
      assert.equal(1030, q.buckets[1].next_attempt_at)
      assert.equal(1,    q.buckets[1].attempts)
    end)

    it("skips queueing (and posting) empty-records reports", function()
      local q = usage.new_queue()
      local called = false
      local function post_fn(_url, _body, _hdrs) called = true; return 500, "" end
      local empty = { routerId = "r1", periodStart = "t0", periodEnd = "t1", records = {} }
      local ok = usage.post_with_retry("http://api", "tok", empty, post_fn, q, 1000, no_jitter)
      assert.is_true(ok)
      assert.is_false(called)
      assert.equal(0, #q.buckets)
    end)

  end)

  describe("backoff schedule", function()

    -- Walk the backoff sequence by repeatedly failing the same bucket and
    -- inspecting next_attempt_at - now.
    it("doubles per attempt: 30, 60, 120, 240, 480, 900 (capped)", function()
      local q = usage.new_queue()
      local function fail(_url, _body, _hdrs) return 500, "" end
      local expected = { 30, 60, 120, 240, 480, 900, 900, 900 }
      local now = 1000
      -- First failure enqueues the bucket.
      usage.post_with_retry("http://api", "tok", report("t0", "t1"), fail, q, now, no_jitter)
      assert.equal(now + 30, q.buckets[1].next_attempt_at)
      -- Subsequent drain attempts at-or-after next_attempt_at hit the same fail_fn.
      for i = 2, #expected do
        now = q.buckets[1].next_attempt_at
        usage.drain("http://api", "tok", q, fail, now, no_jitter)
        assert.equal(now + expected[i], q.buckets[1].next_attempt_at,
          "attempt " .. i .. ": expected " .. expected[i] .. "s backoff")
      end
    end)

    it("applies ±10% jitter around the nominal delay", function()
      -- rng returns 0 → -10% (low edge); 1 → +10% (high edge).
      local q1 = usage.new_queue()
      local function fail(_url, _body, _hdrs) return 500, "" end
      usage.post_with_retry("http://api", "tok", report("t0", "t1"), fail, q1, 1000, function() return 0 end)
      assert.equal(1000 + 27, q1.buckets[1].next_attempt_at)  -- 30 * 0.9

      local q2 = usage.new_queue()
      usage.post_with_retry("http://api", "tok", report("t0", "t1"), fail, q2, 1000, function() return 1 end)
      assert.equal(1000 + 33, q2.buckets[1].next_attempt_at)  -- 30 * 1.1
    end)

  end)

  describe("drain", function()

    it("does nothing when all buckets are scheduled for the future", function()
      local q = usage.new_queue()
      local function fail(_url, _body, _hdrs) return 500, "" end
      usage.post_with_retry("http://api", "tok", report("t0", "t1"), fail, q, 1000, no_jitter)
      -- Next attempt at 1030; drain at 1020 must NOT touch it.
      local calls = 0
      local function count_fn(_url, _body, _hdrs) calls = calls + 1; return 200, "" end
      usage.drain("http://api", "tok", q, count_fn, 1020, no_jitter)
      assert.equal(0, calls)
      assert.equal(1, #q.buckets)
    end)

    it("drains successful buckets oldest-first by periodEnd", function()
      local q = usage.new_queue()
      local function fail(_url, _body, _hdrs) return 500, "" end
      -- Enqueue two buckets out of chronological order.
      usage.post_with_retry("http://api", "tok", report("t2", "t3"), fail, q, 1000, no_jitter)
      usage.post_with_retry("http://api", "tok", report("t0", "t1"), fail, q, 1000, no_jitter)
      local seen = {}
      local function ok_fn(_url, body, _hdrs)
        local json = require("cjson")
        table.insert(seen, json.decode(body).periodEnd)
        return 200, ""
      end
      usage.drain("http://api", "tok", q, ok_fn, 9999, no_jitter)
      assert.equal("t1", seen[1])
      assert.equal("t3", seen[2])
      assert.equal(0, #q.buckets)
    end)

    it("stops at the first failure, leaving later buckets queued", function()
      local q = usage.new_queue()
      local function fail(_url, _body, _hdrs) return 500, "" end
      usage.post_with_retry("http://api", "tok", report("t0", "t1"), fail, q, 1000, no_jitter)
      usage.post_with_retry("http://api", "tok", report("t2", "t3"), fail, q, 1000, no_jitter)
      -- Both due at 1030; drain attempts only the first, then stops on failure.
      local attempts = 0
      local function fail_then_count(_url, _body, _hdrs)
        attempts = attempts + 1
        return 500, ""
      end
      usage.drain("http://api", "tok", q, fail_then_count, 1030, no_jitter)
      assert.equal(1, attempts, "drain must stop at first failure")
      assert.equal(2, #q.buckets, "no buckets removed on failure")
    end)

    it("reschedules failing bucket and continues retrying on subsequent drains", function()
      local q = usage.new_queue()
      local function fail(_url, _body, _hdrs) return 500, "" end
      usage.post_with_retry("http://api", "tok", report("t0", "t1"), fail, q, 1000, no_jitter)
      usage.drain("http://api", "tok", q, fail, 1030, no_jitter)
      -- Second attempt → backoff 60s from now (1030 + 60 = 1090).
      assert.equal(1090, q.buckets[1].next_attempt_at)
      assert.equal(2,    q.buckets[1].attempts)
    end)

  end)

end)
