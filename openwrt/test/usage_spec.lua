-- Tests for openwrt/files/usr/lib/lua/familydns/usage.lua
-- Run with: cd openwrt && busted test/usage_spec.lua
--
-- Counter name convention (produced by render.lua, consumed here):
--   ct_<mac_underscored>__<dst_ip_underscored>
--   e.g.  aa:bb:cc:11:22:33  +  1.2.3.4  →  ct_aa_bb_cc_11_22_33__1_2_3_4
-- Separator between MAC and IP is double-underscore (__) to avoid ambiguity
-- with the single-underscore replacements inside each field.

local usage = require("usage")

-- Sample `nft -j list counters table inet familydns` output
local NFT_JSON = [[{
  "nftables": [
    { "metainfo": { "version": "1.0.2", "json_schema_version": 1 } },
    { "counter": {
        "family": "inet", "table": "familydns",
        "name": "ct_aa_bb_cc_11_22_33__1_2_3_4",
        "packets": 100, "bytes": 50000
    }},
    { "counter": {
        "family": "inet", "table": "familydns",
        "name": "ct_de_ad_be_ef_00_01__8_8_8_8",
        "packets": 20, "bytes": 1024
    }}
  ]
}]]

-- ── parse_nft_counters ────────────────────────────────────────────────────

describe("usage.parse_nft_counters", function()

  it("returns one entry per counter object (skips metainfo)", function()
    local counters = usage.parse_nft_counters(NFT_JSON)
    assert.equal(2, #counters)
  end)

  it("extracts bytes and packets from each entry", function()
    local counters = usage.parse_nft_counters(NFT_JSON)
    local by_mac = {}
    for _, c in ipairs(counters) do by_mac[c.mac] = c end
    assert.equal(50000, by_mac["aa:bb:cc:11:22:33"].bytes)
    assert.equal(100,   by_mac["aa:bb:cc:11:22:33"].packets)
    assert.equal(1024,  by_mac["de:ad:be:ef:00:01"].bytes)
  end)

  it("decodes counter name back to mac and dst_ip", function()
    local counters = usage.parse_nft_counters(NFT_JSON)
    local by_mac = {}
    for _, c in ipairs(counters) do by_mac[c.mac] = c end
    assert.equal("aa:bb:cc:11:22:33", by_mac["aa:bb:cc:11:22:33"].mac)
    assert.equal("1.2.3.4",           by_mac["aa:bb:cc:11:22:33"].dst_ip)
    assert.equal("de:ad:be:ef:00:01", by_mac["de:ad:be:ef:00:01"].mac)
    assert.equal("8.8.8.8",           by_mac["de:ad:be:ef:00:01"].dst_ip)
  end)

  it("returns empty list when JSON contains no counter objects", function()
    local empty = '{"nftables":[{"metainfo":{"version":"1.0.2"}}]}'
    assert.equal(0, #usage.parse_nft_counters(empty))
  end)

  it("skips counters whose names do not match the ct_ convention", function()
    local foreign = [[{"nftables":[
      {"counter":{"family":"inet","table":"familydns","name":"unrelated","packets":1,"bytes":10}}
    ]}]]
    assert.equal(0, #usage.parse_nft_counters(foreign))
  end)

  it("handles a multi-octet IP like 192.168.100.200 correctly", function()
    local json_str = [[{"nftables":[
      {"counter":{"family":"inet","table":"familydns",
       "name":"ct_aa_bb_cc_11_22_33__192_168_100_200","packets":5,"bytes":999}}
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

  it("sets activeSeconds = 300 (full period) for any counter with bytes > 0", function()
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
