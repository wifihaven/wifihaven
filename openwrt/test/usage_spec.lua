-- Tests for openwrt/files/usr/lib/lua/familydns/usage.lua
-- Run with: cd openwrt && busted test/usage_spec.lua
--
-- Input shape: `nft -j list set inet familydns mac_ip_tracking`.
-- The `mac_ip_tracking` dynamic set is declared in render.lua with
-- `flags dynamic,timeout` + `counter`, so each set element carries its own
-- counter and the JSON layout is:
--   nftables[*].set.elem[*].elem.{val.concat:[<mac>,<ip>], counter:{packets,bytes}}

local usage = require("usage")

local NFT_JSON = [[{
  "nftables": [
    { "metainfo": { "version": "1.1.6", "json_schema_version": 1 } },
    { "set": {
        "family": "inet", "table": "familydns", "name": "mac_ip_tracking",
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
  local P_END    = "2026-05-08T14:05:00Z"
  local ROUTER   = "9c1f2e8a-0000-0000-0000-000000000001"

  it("sets routerId, periodStart, periodEnd on the top-level report", function()
    local r = usage.build_report(COUNTERS, NF_SETS, P_START, P_END, ROUTER)
    assert.equal(ROUTER,  r.routerId)
    assert.equal(P_START, r.periodStart)
    assert.equal(P_END,   r.periodEnd)
  end)

  it("produces one record per (mac, hostname) pair", function()
    local r = usage.build_report(COUNTERS, NF_SETS, P_START, P_END, ROUTER)
    assert.equal(2, #r.records)
  end)

  it("attributes bytes to the hostname the dst_ip resolved to (via nft_sets)", function()
    local r = usage.build_report(COUNTERS, NF_SETS, P_START, P_END, ROUTER)
    local by_hostname = {}
    for _, rec in ipairs(r.records) do by_hostname[rec.hostname] = rec end
    assert.equal("aa:bb:cc:11:22:33", by_hostname["youtube.com"].mac)
    assert.equal(50000,               by_hostname["youtube.com"].bytesIn)
  end)

  it("falls back hostname to 'unknown' when dst_ip is not in any nft_set", function()
    local unk = { { mac = "aa:bb:cc:11:22:33", dst_ip = "9.9.9.9", bytes = 100, packets = 3 } }
    local r = usage.build_report(unk, NF_SETS, P_START, P_END, ROUTER)
    assert.equal(1, #r.records)
    assert.equal("unknown", r.records[1].hostname)
  end)

  -- #287: nft_sets only carries hostnames for site_limits-tracked domains, so
  -- without the dnsmasq-query-log lookup every traffic_reports row landed as
  -- "unknown" and the Sessions UI rendered every flow as unknown traffic.
  it("consults lookup_hostname (dns-cache) before falling back to 'unknown'", function()
    local counters = {
      { mac = "aa:bb:cc:11:22:33", dst_ip = "140.82.114.6", bytes = 100, packets = 3 },
    }
    local lookup = function(ip)
      if ip == "140.82.114.6" then return "api.github.com" end
      return nil
    end
    local r = usage.build_report(counters, {}, P_START, P_END, ROUTER, nil, lookup)
    assert.equal("api.github.com", r.records[1].hostname)
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
    local r = usage.build_report(counters, sets, P_START, P_END, ROUTER, nil, lookup)
    assert.equal("actual.example", r.records[1].hostname)
  end)

  it("falls through to nft_sets when lookup_hostname misses", function()
    local counters = {
      { mac = "aa:bb:cc:11:22:33", dst_ip = "1.2.3.4", bytes = 100, packets = 3 },
    }
    local sets = { ["youtube.com"] = { ["1.2.3.4"] = true } }
    local lookup = function(_) return nil end
    local r = usage.build_report(counters, sets, P_START, P_END, ROUTER, nil, lookup)
    assert.equal("youtube.com", r.records[1].hostname)
  end)

  it("sets activeSeconds = 300 (full period) for any counter with bytes > 0 (legacy, no tracker)", function()
    local r = usage.build_report(COUNTERS, NF_SETS, P_START, P_END, ROUTER)
    for _, rec in ipairs(r.records) do
      assert.equal(300, rec.activeSeconds)
    end
  end)

  it("includes the mac address on each record", function()
    local r = usage.build_report(COUNTERS, NF_SETS, P_START, P_END, ROUTER)
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
    local r = usage.build_report(COUNTERS, NF_SETS, P_START, P_END, ROUTER, leases)
    local by_mac = {}
    for _, rec in ipairs(r.records) do by_mac[rec.mac] = rec end
    assert.equal("192.168.1.42", by_mac["aa:bb:cc:11:22:33"].ip)
    assert.equal("192.168.1.10", by_mac["de:ad:be:ef:00:01"].ip)
  end)

  it("ip field is nil (not present) when leases table is not provided", function()
    local r = usage.build_report(COUNTERS, NF_SETS, P_START, P_END, ROUTER)
    -- no leases arg → ip field should be absent or nil on each record
    for _, rec in ipairs(r.records) do
      assert.is_nil(rec.ip)
    end
  end)

  it("JSON-encodes cleanly with all required API fields present", function()
    local json = require("cjson")
    local r    = usage.build_report(COUNTERS, NF_SETS, P_START, P_END, ROUTER)
    local dec  = json.decode(json.encode(r))
    assert.not_nil(dec.routerId)
    assert.not_nil(dec.periodStart)
    assert.not_nil(dec.periodEnd)
    assert.not_nil(dec.records)
    local rec = dec.records[1]
    assert.not_nil(rec.mac)
    assert.not_nil(rec.hostname)
    assert.not_nil(rec.activeSeconds)
    assert.not_nil(rec.bytesIn)
  end)

  it("handles an empty counters list (zero records)", function()
    local r = usage.build_report({}, NF_SETS, P_START, P_END, ROUTER)
    assert.equal(0, #r.records)
  end)

end)

-- ── tracker (per-minute activity sampler) ─────────────────────────────────
--
-- The router scrapes nft counters every 60 s within a 5-min bucket and feeds
-- them to a tracker; the tracker remembers how many distinct minutes each
-- (mac, dst_ip) saw counter growth, which build_report converts into
-- activeSeconds.  Counter reset / set-element expiry (bytes goes DOWN) is
-- treated as a fresh start so we don't double-count.

describe("usage.tracker", function()

  local function s(mac, dst_ip, bytes)
    return { mac = mac, dst_ip = dst_ip, bytes = bytes, packets = 0 }
  end

  it("new_tracker returns an empty tracker", function()
    local t = usage.new_tracker()
    assert.is_table(t)
    assert.is_table(t.active_minutes)
    assert.is_nil(next(t.active_minutes))
  end)

  it("counts a single sample with bytes > 0 (from 0 baseline) as 1 active minute", function()
    local t = usage.new_tracker()
    usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4", 100) })
    assert.equal(1, t.active_minutes["aa:bb:cc:11:22:33|1.2.3.4"])
  end)

  it("counts each subsequent sample with growth as another active minute", function()
    local t = usage.new_tracker()
    usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4",  100) })
    usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4",  500) })
    usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4", 1200) })
    assert.equal(3, t.active_minutes["aa:bb:cc:11:22:33|1.2.3.4"])
  end)

  it("does NOT count a sample where bytes are unchanged", function()
    local t = usage.new_tracker()
    usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4", 100) })  -- +1
    usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4", 100) })  -- no growth
    usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4", 100) })  -- no growth
    assert.equal(1, t.active_minutes["aa:bb:cc:11:22:33|1.2.3.4"])
  end)

  it("treats a counter decrease (reset / element-expire-and-reappear) as a fresh active minute", function()
    local t = usage.new_tracker()
    usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4", 500) })  -- +1
    usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4",  50) })  -- reset, bytes>0 → +1
    assert.equal(2, t.active_minutes["aa:bb:cc:11:22:33|1.2.3.4"])
  end)

  it("does not count a counter decrease to 0 as an active minute", function()
    local t = usage.new_tracker()
    usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4", 500) })  -- +1
    usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4",   0) })  -- decrease to 0 → no
    assert.equal(1, t.active_minutes["aa:bb:cc:11:22:33|1.2.3.4"])
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
    assert.equal(1, t.active_minutes["aa:bb:cc:11:22:33|1.2.3.4"])
    assert.equal(2, t.active_minutes["de:ad:be:ef:00:01|8.8.8.8"])
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
    assert.equal(1, t.active_minutes["aa:bb:cc:11:22:33|1.2.3.4"])
    assert.equal(2, t.active_minutes["aa:bb:cc:11:22:33|5.6.7.8"])
  end)

  it("tracker_reset clears all state", function()
    local t = usage.new_tracker()
    usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4", 100) })
    usage.tracker_reset(t)
    assert.is_nil(next(t.active_minutes))
    -- After reset, the next sample starts from a fresh 0 baseline.
    usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4", 50) })
    assert.equal(1, t.active_minutes["aa:bb:cc:11:22:33|1.2.3.4"])
  end)

end)

describe("usage.build_report with tracker", function()

  local NF_SETS  = { ["youtube.com"] = { ["1.2.3.4"] = true } }
  local P_START  = "2026-05-08T14:00:00Z"
  local P_END    = "2026-05-08T14:05:00Z"
  local ROUTER   = "9c1f2e8a-0000-0000-0000-000000000001"

  local function s(mac, dst_ip, bytes)
    return { mac = mac, dst_ip = dst_ip, bytes = bytes, packets = 0 }
  end

  it("uses 60 * active_minutes from the tracker (1 minute → 60s)", function()
    local t = usage.new_tracker()
    usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4", 500) })
    local r = usage.build_report(
      { s("aa:bb:cc:11:22:33", "1.2.3.4", 500) },
      NF_SETS, P_START, P_END, ROUTER, nil, nil, t)
    assert.equal(60, r.records[1].activeSeconds)
  end)

  it("reports 300s when all 5 minutes were active", function()
    local t = usage.new_tracker()
    for i = 1, 5 do
      usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4", 100 * i) })
    end
    local r = usage.build_report(
      { s("aa:bb:cc:11:22:33", "1.2.3.4", 500) },
      NF_SETS, P_START, P_END, ROUTER, nil, nil, t)
    assert.equal(300, r.records[1].activeSeconds)
  end)

  it("caps activeSeconds at 300 even if the tracker recorded more than 5 minutes (drift)", function()
    local t = usage.new_tracker()
    for i = 1, 7 do
      usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4", 100 * i) })
    end
    local r = usage.build_report(
      { s("aa:bb:cc:11:22:33", "1.2.3.4", 700) },
      NF_SETS, P_START, P_END, ROUTER, nil, nil, t)
    assert.equal(300, r.records[1].activeSeconds)
  end)

  it("falls back to 60s when bytes > 0 but tracker has no entry (entry appeared after last sample)", function()
    local t = usage.new_tracker()
    -- tracker has seen device A but not B
    usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4", 100) })
    local r = usage.build_report(
      { s("de:ad:be:ef:00:01", "9.9.9.9", 200) },
      NF_SETS, P_START, P_END, ROUTER, nil, nil, t)
    assert.equal(60, r.records[1].activeSeconds)
  end)

  it("retains legacy 300 behavior when no tracker is passed (back-compat)", function()
    local r = usage.build_report(
      { s("aa:bb:cc:11:22:33", "1.2.3.4", 500) },
      NF_SETS, P_START, P_END, ROUTER)
    assert.equal(300, r.records[1].activeSeconds)
  end)

  it("assigns activeSeconds per (mac, dst_ip) independently from the same tracker", function()
    local t = usage.new_tracker()
    -- device A: 1 active minute
    usage.tracker_sample(t, { s("aa:bb:cc:11:22:33", "1.2.3.4", 100) })
    -- device B joins at minute 2 with growth across 4 more samples
    for i = 1, 4 do
      usage.tracker_sample(t, {
        s("aa:bb:cc:11:22:33", "1.2.3.4", 100),         -- no growth for A
        s("de:ad:be:ef:00:01", "8.8.8.8", 100 * i),     -- growth for B
      })
    end
    local r = usage.build_report({
      s("aa:bb:cc:11:22:33", "1.2.3.4", 100),
      s("de:ad:be:ef:00:01", "8.8.8.8", 400),
    }, NF_SETS, P_START, P_END, ROUTER, nil, nil, t)
    local by_mac = {}
    for _, rec in ipairs(r.records) do by_mac[rec.mac] = rec end
    assert.equal(60,  by_mac["aa:bb:cc:11:22:33"].activeSeconds)
    assert.equal(240, by_mac["de:ad:be:ef:00:01"].activeSeconds)
  end)

end)

-- ── post ──────────────────────────────────────────────────────────────────

describe("usage.post", function()

  local SAMPLE_REC = { mac="aa:bb:cc:11:22:33", hostname="x", activeSeconds=300, bytesIn=1, bytesOut=0 }
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
