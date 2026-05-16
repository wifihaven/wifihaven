-- Tests for openwrt/files/usr/lib/lua/familydns/block_page.lua (#437)
-- Run with: cd openwrt && busted test/block_page_spec.lua

local bp = require("block_page")

describe("block_page.parse_arp", function()
  local arp = table.concat({
    "IP address       HW type     Flags       HW address            Mask     Device",
    "192.168.1.10     0x1         0x2         aa:bb:cc:11:22:33     *        br-lan",
    "192.168.1.11     0x1         0x2         de:ad:be:ef:00:01     *        br-lan",
    "192.168.1.12     0x1         0x0         00:00:00:00:00:00     *        br-lan",
  }, "\n")

  it("returns the MAC for a known IP", function()
    assert.equals("aa:bb:cc:11:22:33", bp.parse_arp(arp, "192.168.1.10"))
  end)

  it("returns nil for unknown IP", function()
    assert.is_nil(bp.parse_arp(arp, "192.168.99.99"))
  end)

  it("skips entries with the all-zero MAC (stale ARP)", function()
    assert.is_nil(bp.parse_arp(arp, "192.168.1.12"))
  end)

  it("returns nil on nil / empty input", function()
    assert.is_nil(bp.parse_arp(nil, "192.168.1.10"))
    assert.is_nil(bp.parse_arp(arp, ""))
  end)

  it("lower-cases the returned MAC for stable lookups", function()
    local upper = "192.168.1.99   0x1 0x2 AA:BB:CC:DD:EE:FF * br-lan"
    assert.equals("aa:bb:cc:dd:ee:ff", bp.parse_arp(upper, "192.168.1.99"))
  end)
end)

describe("block_page.parse_reasons", function()
  local content = table.concat({
    "aa:bb:cc:11:22:33\tPaused",
    "de:ad:be:ef:00:01\tTimeLimit",
  }, "\n") .. "\n"

  it("returns the reason for a known MAC", function()
    assert.equals("Paused",    bp.parse_reasons(content, "aa:bb:cc:11:22:33"))
    assert.equals("TimeLimit", bp.parse_reasons(content, "de:ad:be:ef:00:01"))
  end)

  it("is case-insensitive on the MAC lookup", function()
    assert.equals("Paused", bp.parse_reasons(content, "AA:BB:CC:11:22:33"))
  end)

  it("returns nil for an unknown MAC", function()
    assert.is_nil(bp.parse_reasons(content, "ff:ff:ff:ff:ff:ff"))
  end)

  it("returns nil on nil content (reasons file missing)", function()
    assert.is_nil(bp.parse_reasons(nil, "aa:bb:cc:11:22:33"))
  end)
end)

describe("block_page.build_dest_url", function()
  it("emits a fully-formed /blocked URL with host, reason, and mac", function()
    local u = bp.build_dest_url(
      "http://api.example.com", "youtube.com", "aa:bb:cc:11:22:33", "Paused")
    assert.truthy(u:find("http://api.example.com/blocked", 1, true))
    assert.truthy(u:find("host=youtube.com", 1, true))
    assert.truthy(u:find("reason=Paused", 1, true))
    assert.truthy(u:find("mac=aa%3Abb%3Acc%3A11%3A22%3A33", 1, true))
  end)

  it("returns nil when api_url is not configured", function()
    assert.is_nil(bp.build_dest_url(nil, "x.com", "aa:bb:cc:11:22:33", "Paused"))
    assert.is_nil(bp.build_dest_url("", "x.com", "aa:bb:cc:11:22:33", "Paused"))
  end)

  it("tolerates a missing mac/reason (still emits a valid URL for fallback display)", function()
    local u = bp.build_dest_url("http://api.example.com", "x.com", nil, nil)
    assert.truthy(u:find("mac=", 1, true))
    assert.truthy(u:find("reason=", 1, true))
  end)
end)

describe("block_page.render_html", function()
  it("emits a redirect document containing the dest URL when api_url is set", function()
    local html = bp.render_html(
      "http://api.example.com", "youtube.com", "aa:bb:cc:11:22:33", "Paused")
    assert.truthy(html:find("window.location.replace", 1, true))
    assert.truthy(html:find("reason=Paused", 1, true))
    assert.truthy(html:find("http://api.example.com/blocked", 1, true))
  end)

  it("falls back to inline copy when api_url is missing", function()
    local html = bp.render_html(nil, "youtube.com", "aa:bb:cc:11:22:33", "Paused")
    assert.truthy(html:find("This profile is paused.", 1, true))
    assert.is_nil(html:find("window.location.replace", 1, true))
  end)

  it("inline copy covers every MacBlockReason and a fallback", function()
    assert.equals("This profile is paused.",                  bp.inline_copy_for("Paused"))
    assert.equals("This is scheduled quiet time.",            bp.inline_copy_for("Schedule"))
    assert.equals("Daily screen time limit reached.",         bp.inline_copy_for("TimeLimit"))
    assert.equals("This device has been blocked by a parent.",bp.inline_copy_for("Manual"))
    assert.equals("This site is blocked.",                    bp.inline_copy_for("Bogus"))
    assert.equals("This site is blocked.",                    bp.inline_copy_for(nil))
  end)

  it("escapes the host in the inline page so it can't break out of HTML", function()
    local html = bp.render_html(nil, "<script>alert(1)</script>", nil, "Paused")
    assert.is_nil(html:find("<script>alert(1)</script>", 1, true))
    assert.truthy(html:find("&lt;script&gt;", 1, true))
  end)
end)
