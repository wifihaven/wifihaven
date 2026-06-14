-- Tests for openwrt/files/usr/lib/lua/wifihaven/static_ip_labels.lua  (#1655)
-- Run with: busted openwrt/test/static_ip_labels_spec.lua

local labels = require("static_ip_labels")

describe("static_ip_labels.lookup", function()
  it("returns the label for an IP inside Apple's 17.0.0.0/8", function()
    assert.equal("apple-push", labels.lookup("17.0.0.1"))
    assert.equal("apple-push", labels.lookup("17.255.255.254"))
    assert.equal("apple-push", labels.lookup("17.188.166.41"))
  end)

  it("returns the well-known DNS labels", function()
    assert.equal("google-dns",     labels.lookup("8.8.8.8"))
    assert.equal("google-dns",     labels.lookup("8.8.4.4"))
    assert.equal("cloudflare-dns", labels.lookup("1.1.1.1"))
    assert.equal("cloudflare-dns", labels.lookup("1.0.0.1"))
  end)

  it("returns nil for an IP outside every range", function()
    assert.is_nil(labels.lookup("18.0.0.1"))         -- just past 17/8
    assert.is_nil(labels.lookup("16.255.255.255"))   -- just before 17/8
    assert.is_nil(labels.lookup("9.9.9.9"))
    assert.is_nil(labels.lookup("192.168.1.42"))
  end)

  it("returns nil for malformed or empty input", function()
    assert.is_nil(labels.lookup(nil))
    assert.is_nil(labels.lookup(""))
    assert.is_nil(labels.lookup("not.an.ip"))
    assert.is_nil(labels.lookup("17.0.0"))
  end)

  it("returns nil for IPv6 addresses (initial map is v4-only)", function()
    assert.is_nil(labels.lookup("2001:db8::1"))
    assert.is_nil(labels.lookup("::1"))
  end)

  it("keeps the initial map small (< 10 entries) so growth is operator-curated", function()
    -- Tripwire: the map is intentionally a starter. Per the module header
    -- + docs/architecture.md §7.2 ("Static IP-range labels"), new entries
    -- must land with prod evidence that the range dominates the
    -- unattributed-IP tail AND has an unambiguous well-known owner. If
    -- this assertion ever needs raising, re-justify in the PR body.
    assert.is_true(#labels._ranges < 10)
  end)
end)
