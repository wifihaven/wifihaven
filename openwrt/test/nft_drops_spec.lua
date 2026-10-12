-- nft_drops_spec.lua — enforcement-plane forward-drop counter parsing (#1325).

local nft_drops = require("wifihaven.nft_drops")

describe("nft_drops.classify_reason", function()
  it("maps the per-host eb_ drop to host_block", function()
    assert.equal("host_block", nft_drops.classify_reason("host"))
  end)

  -- #1645: post-rollout the per-host drop comment is `host:<host>` so the
  -- matched eb_<host> rule can be named. Both the legacy bare `host` and
  -- the new `host:<host>` form fold into the same bounded `host_block`
  -- metrics bucket — the host name itself is forbidden as a label per the
  -- §4 cardinality firewall.
  it("collapses host:<host> into host_block (#1645)", function()
    assert.equal("host_block", nft_drops.classify_reason("host:youtubei.googleapis.com"))
    assert.equal("host_block", nft_drops.classify_reason("host:tiktok.com"))
  end)

  it("maps the blockIpOnly drop to ip_only", function()
    assert.equal("ip_only", nft_drops.classify_reason("ip_only"))
  end)

  it("collapses any category:<id> drop into category_block", function()
    assert.equal("category_block", nft_drops.classify_reason("category:7"))
    assert.equal("category_block", nft_drops.classify_reason("category:adult"))
  end)

  it("folds every whole-MAC MacBlockReason into whole_mac", function()
    for _, r in ipairs({ "Paused", "Schedule", "TimeLimit", "Manual", "Unmanaged", "blocked" }) do
      assert.equal("whole_mac", nft_drops.classify_reason(r))
    end
  end)

  it("defaults an unrecognised reason to whole_mac (bounded label space)", function()
    assert.equal("whole_mac", nft_drops.classify_reason("SomeFutureReason"))
    -- #2847: CheckedOut shipped API-side with no agent change; it lands here.
    assert.equal("whole_mac", nft_drops.classify_reason("CheckedOut"))
    assert.equal("whole_mac", nft_drops.classify_reason(nil))
  end)
end)

-- A representative `nft -j list chain inet wifihaven wifihaven_block` body: four
-- drop rules (whole-MAC, eb_ host, bl_ category, blockIpOnly) each carrying a
-- counter + wh_drop comment, plus a non-drop rule with no comment.
local CHAIN_JSON = [[
{ "nftables": [
  { "metainfo": { "version": "1.0.8" } },
  { "rule": { "family": "inet", "table": "wifihaven", "chain": "wifihaven_block",
              "comment": "wh_drop:aa:bb:cc:dd:ee:01:Schedule",
              "expr": [ { "match": {} },
                        { "counter": { "packets": 1000, "bytes": 64000 } },
                        { "log": { "group": 1 } },
                        { "drop": null } ] } },
  { "rule": { "family": "inet", "table": "wifihaven", "chain": "wifihaven_block",
              "comment": "wh_drop:aa:bb:cc:dd:ee:02:host",
              "expr": [ { "counter": { "packets": 40, "bytes": 2000 } },
                        { "drop": null } ] } },
  { "rule": { "family": "inet", "table": "wifihaven", "chain": "wifihaven_block",
              "comment": "wh_drop:aa:bb:cc:dd:ee:03:category:7",
              "expr": [ { "counter": { "packets": 250, "bytes": 12000 } },
                        { "drop": null } ] } },
  { "rule": { "family": "inet", "table": "wifihaven", "chain": "wifihaven_block",
              "comment": "wh_drop:aa:bb:cc:dd:ee:04:ip_only",
              "expr": [ { "counter": { "packets": 9, "bytes": 540 } },
                        { "drop": null } ] } },
  { "rule": { "family": "inet", "table": "wifihaven", "chain": "wifihaven_block",
              "expr": [ { "counter": { "packets": 99999, "bytes": 1 } } ] } }
] }
]]

describe("nft_drops.parse_drop_counters", function()
  it("sums each rule's packets into its classified reason bucket", function()
    local by = nft_drops.parse_drop_counters(CHAIN_JSON)
    assert.equal(1000, by.whole_mac)
    assert.equal(40, by.host_block)
    assert.equal(250, by.category_block)
    assert.equal(9, by.ip_only)
  end)

  it("ignores rules with no wh_drop comment (e.g. accounting/accept rules)", function()
    local by = nft_drops.parse_drop_counters(CHAIN_JSON)
    -- The trailing comment-less rule's 99999 packets must not land anywhere.
    local total = (by.whole_mac or 0) + (by.host_block or 0)
                + (by.category_block or 0) + (by.ip_only or 0)
    assert.equal(1000 + 40 + 250 + 9, total)
  end)

  it("aggregates multiple rules sharing a reason across MACs", function()
    local json = [[
{ "nftables": [
  { "rule": { "comment": "wh_drop:aa:bb:cc:dd:ee:01:host",
              "expr": [ { "counter": { "packets": 5, "bytes": 1 } }, { "drop": null } ] } },
  { "rule": { "comment": "wh_drop:aa:bb:cc:dd:ee:02:host",
              "expr": [ { "counter": { "packets": 7, "bytes": 1 } }, { "drop": null } ] } }
] }
]]
    local by = nft_drops.parse_drop_counters(json)
    assert.equal(12, by.host_block)
  end)

  it("returns an empty table for an absent chain / unparseable JSON", function()
    assert.same({}, nft_drops.parse_drop_counters(""))
    assert.same({}, nft_drops.parse_drop_counters("not json"))
    assert.same({}, nft_drops.parse_drop_counters(nil))
  end)
end)

describe("nft_drops.read", function()
  it("parses the injected exec_fn output", function()
    local by = nft_drops.read(function()
      return CHAIN_JSON
    end)
    assert.equal(1000, by.whole_mac)
    assert.equal(250, by.category_block)
  end)

  -- #2901: a MAC with extraAllowed hosts has its whole-MAC, host and category
  -- drops in its own carve chain (wh_carve_<mac>), so reading only the
  -- wifihaven_block chain would miss them. Read the whole table, terse (-t
  -- leaves out set elements, which the counters don't need).
  it("reads the whole table tersely, not one chain", function()
    local seen
    nft_drops.read(function(cmd) seen = cmd; return "" end)
    assert.equal("nft -j -t list table inet wifihaven", seen)
  end)

  it("counts drops in a carve chain alongside the base chain", function()
    local json = [[
{ "nftables": [
  { "chain": { "family": "inet", "table": "wifihaven", "name": "wh_carve_aabbccddee01" } },
  { "rule": { "chain": "wh_carve_aabbccddee01", "comment": "wh_drop:aa:bb:cc:dd:ee:01:Paused",
              "expr": [ { "counter": { "packets": 3, "bytes": 1 } }, { "drop": null } ] } },
  { "rule": { "chain": "wh_carve_aabbccddee01", "comment": "wh_drop:aa:bb:cc:dd:ee:01:host:x.example",
              "expr": [ { "counter": { "packets": 4, "bytes": 1 } }, { "drop": null } ] } },
  { "rule": { "chain": "wifihaven_block", "comment": "wh_drop:aa:bb:cc:dd:ee:02:Paused",
              "expr": [ { "counter": { "packets": 5, "bytes": 1 } }, { "drop": null } ] } }
] }
]]
    local by = nft_drops.read(function() return json end)
    assert.equal(8, by.whole_mac)
    assert.equal(4, by.host_block)
  end)
end)
