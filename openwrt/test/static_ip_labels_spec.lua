-- Tests for openwrt/files/usr/lib/lua/wifihaven/static_ip_labels.lua  (#1655 / #1708)
-- Run with: busted openwrt/test/static_ip_labels_spec.lua

local labels = require("static_ip_labels")

describe("static_ip_labels.lookup", function()
  it("returns the label and source for an IP inside Apple's 17.0.0.0/8", function()
    local label, source = labels.lookup("17.0.0.1")
    assert.equal("apple-push",      label)
    assert.equal("static-ip-range", source)
    -- Range edges
    assert.equal("apple-push", (labels.lookup("17.255.255.254")))
    assert.equal("apple-push", (labels.lookup("17.188.166.41")))
  end)

  it("returns the well-known DNS labels with the static-ip-range source", function()
    local l, s
    l, s = labels.lookup("8.8.8.8");  assert.equal("google-dns",     l); assert.equal("static-ip-range", s)
    l, s = labels.lookup("8.8.4.4");  assert.equal("google-dns",     l); assert.equal("static-ip-range", s)
    l, s = labels.lookup("1.1.1.1");  assert.equal("cloudflare-dns", l); assert.equal("static-ip-range", s)
    l, s = labels.lookup("1.0.0.1");  assert.equal("cloudflare-dns", l); assert.equal("static-ip-range", s)
  end)

  it("returns nil for an IP outside every range", function()
    assert.is_nil(labels.lookup("18.0.0.1"))         -- just past 17/8
    assert.is_nil(labels.lookup("16.255.255.255"))   -- just before 17/8
    assert.is_nil(labels.lookup("203.0.113.7"))      -- TEST-NET-3
    assert.is_nil(labels.lookup("8.8.8.9"))          -- one past google primary
  end)

  it("returns nil for malformed or empty input", function()
    assert.is_nil(labels.lookup(""))
    assert.is_nil(labels.lookup("not-an-ip"))
    assert.is_nil(labels.lookup("1.2.3"))
    assert.is_nil(labels.lookup("256.0.0.1"))         -- out-of-range octet
  end)

  it("returns nil for IPv6 (punted for now)", function()
    assert.is_nil(labels.lookup("fe80::1"))
    assert.is_nil(labels.lookup("2001:4860:4860::8888"))
  end)

  it("exposes the canonical source string", function()
    assert.equal("static-ip-range", labels.SOURCE)
  end)

  it("rejects a 0/0 entry — would match every flow (defense-in-depth)", function()
    -- The compiled set must NOT contain a wildcard. We can't drive a 0/0
    -- entry through the public table (it's frozen at module load), but we
    -- can assert that no compiled range matches an arbitrary unrelated IP.
    assert.is_nil(labels.lookup("203.0.113.7"))
    assert.is_nil(labels.lookup("198.51.100.1"))
    assert.is_nil(labels.lookup("0.0.0.0"))
  end)

  it("the initial map is intentionally small (operator-curated)", function()
    -- Pinning this size keeps a follow-up PR from quietly bloating the
    -- last-resort map past the point where reviewers can reason about it.
    -- Growing it is fine — bump this number alongside the new entry.
    assert.is_true(#labels._ranges <= 10,
      "static_ip_labels._ranges should stay <= 10 entries (operator-curated)")
  end)
end)
