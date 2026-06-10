-- Tests for openwrt/files/usr/lib/lua/wifihaven/block_page.lua (#437)
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

describe("block_page.parse_blocked_hosts (#594)", function()
  local content = table.concat({
    "aa:bb:cc:11:22:33\ttiktok.com\textra_blocked",
    "aa:bb:cc:11:22:33\tad.doubleclick.net\tcategory:ads",
    "de:ad:be:ef:00:01\tpornhub.com\tcategory:adult",
  }, "\n") .. "\n"

  it("returns 'extra_blocked' for a per-MAC extraBlocked entry", function()
    assert.equals("extra_blocked",
      bp.parse_blocked_hosts(content, "aa:bb:cc:11:22:33", "tiktok.com"))
  end)

  it("returns 'category:<id>' for a per-MAC blocklist entry", function()
    assert.equals("category:ads",
      bp.parse_blocked_hosts(content, "aa:bb:cc:11:22:33", "ad.doubleclick.net"))
    assert.equals("category:adult",
      bp.parse_blocked_hosts(content, "de:ad:be:ef:00:01", "pornhub.com"))
  end)

  it("matches subdomains via dnsmasq nftset suffix semantics", function()
    -- entry: tiktok.com → matches m.tiktok.com
    assert.equals("extra_blocked",
      bp.parse_blocked_hosts(content, "aa:bb:cc:11:22:33", "m.tiktok.com"))
  end)

  it("does NOT cross-match between MACs", function()
    assert.is_nil(bp.parse_blocked_hosts(content, "de:ad:be:ef:00:01", "tiktok.com"))
  end)

  it("does NOT match unrelated hosts (no false-positive suffix match)", function()
    -- "notexample.com" must not match an entry for "example.com"
    local c = "aa:bb:cc:11:22:33\texample.com\textra_blocked\n"
    assert.is_nil(bp.parse_blocked_hosts(c, "aa:bb:cc:11:22:33", "notexample.com"))
  end)

  it("returns nil on nil/empty inputs", function()
    assert.is_nil(bp.parse_blocked_hosts(nil, "aa:bb:cc:11:22:33", "tiktok.com"))
    assert.is_nil(bp.parse_blocked_hosts(content, nil, "tiktok.com"))
    assert.is_nil(bp.parse_blocked_hosts(content, "aa:bb:cc:11:22:33", nil))
    assert.is_nil(bp.parse_blocked_hosts(content, "aa:bb:cc:11:22:33", ""))
  end)

  it("is case-insensitive on MAC and host", function()
    assert.equals("extra_blocked",
      bp.parse_blocked_hosts(content, "AA:BB:CC:11:22:33", "TikTok.com"))
  end)
end)

describe("block_page.resolve_base (#1174)", function()
  -- The block-page redirect base is deployment config, separate from the API
  -- URL. In the cloud deploy the SPA (which serves /blocked) lives on a
  -- different host (wifihaven.net) than the API (api.wifihaven.net), so the
  -- redirect must target the SPA host, not api_url. resolve_base picks the
  -- configured block-page URL when set and falls back to api_url otherwise
  -- (the self-hosted / back-compat case, where the SPA is bundled with the API
  -- on the same host).
  it("returns the block-page URL when it differs from api_url (cloud case)", function()
    assert.equals("https://wifihaven.net",
      bp.resolve_base("https://wifihaven.net", "https://api.wifihaven.net"))
  end)

  it("falls back to api_url when the block-page URL is unset (self-hosted/back-compat)", function()
    assert.equals("https://api.wifihaven.net", bp.resolve_base(nil, "https://api.wifihaven.net"))
    assert.equals("https://api.wifihaven.net", bp.resolve_base("", "https://api.wifihaven.net"))
  end)

  it("post-#1171 cutover is a pure config change (app.wifihaven.net)", function()
    assert.equals("https://app.wifihaven.net",
      bp.resolve_base("https://app.wifihaven.net", "https://api.wifihaven.net"))
  end)
end)

describe("block_page.build_dest_url (#679/#1617: no reason= param)", function()
  it("emits a fully-formed /blocked URL with host and mac only", function()
    local u = bp.build_dest_url(
      "http://api.example.com", "youtube.com", "aa:bb:cc:11:22:33")
    assert.truthy(u:find("http://api.example.com/blocked", 1, true))
    assert.truthy(u:find("host=youtube.com", 1, true))
    assert.truthy(u:find("mac=aa%3Abb%3Acc%3A11%3A22%3A33", 1, true))
    -- Reason is derived API-side from GET /api/blocked (PR1 / #1615); the
    -- router no longer sends it on the redirect URL.
    assert.is_nil(u:find("reason=", 1, true))
  end)

  -- #1174: when the block-page base is the public SPA host (not api_url), the
  -- redirect targets the SPA, not the API host.
  it("targets the SPA host when given the block-page base (#1174)", function()
    local base = bp.resolve_base("https://wifihaven.net", "https://api.wifihaven.net")
    local u = bp.build_dest_url(base, "youtube.com", "aa:bb:cc:11:22:33")
    assert.truthy(u:find("https://wifihaven.net/blocked", 1, true))
    assert.is_nil(u:find("api.wifihaven.net", 1, true))
    assert.is_nil(u:find("reason=", 1, true))
  end)

  it("returns nil when api_url is not configured", function()
    assert.is_nil(bp.build_dest_url(nil, "x.com", "aa:bb:cc:11:22:33"))
    assert.is_nil(bp.build_dest_url("", "x.com", "aa:bb:cc:11:22:33"))
  end)

  it("tolerates a missing mac (still emits a valid URL for fallback display)", function()
    local u = bp.build_dest_url("http://api.example.com", "x.com", nil)
    assert.truthy(u:find("mac=", 1, true))
    assert.is_nil(u:find("reason=", 1, true))
  end)
end)

describe("block_page.render_html (#679/#1617: no reason= param)", function()
  it("emits a redirect document containing the dest URL when api_url is set", function()
    local html = bp.render_html(
      "http://api.example.com", "youtube.com", "aa:bb:cc:11:22:33")
    assert.truthy(html:find("window.location.replace", 1, true))
    assert.truthy(html:find("http://api.example.com/blocked", 1, true))
    -- Redirect URL must NOT carry a reason= param — SPA derives the reason
    -- from GET /api/blocked (PR1 / #1615).
    assert.is_nil(html:find("reason=", 1, true))
  end)

  -- #580: redirect page must carry a viewport meta and show neutral inline
  -- copy so iOS Safari users see content even if the cross-origin redirect is
  -- blocked. Post-#1617 the inline copy is no longer reason-keyed.
  it("redirect page includes viewport meta and neutral inline copy (#580)", function()
    local html = bp.render_html(
      "http://api.example.com", "youtube.com", "aa:bb:cc:11:22:33")
    assert.truthy(html:find('name="viewport"', 1, true))
    assert.truthy(html:find("This site is blocked.", 1, true))
  end)

  -- #580: inline fallback (no api_url) must also carry viewport meta.
  it("inline fallback includes viewport meta (#580)", function()
    local html = bp.render_html(nil, "youtube.com", "aa:bb:cc:11:22:33")
    assert.truthy(html:find('name="viewport"', 1, true))
  end)

  -- #1174: render_html redirects to whatever base it is given. With the SPA
  -- host as base, the redirect document points at the SPA, not the API host.
  it("redirect document points at the block-page base, not api_url (#1174)", function()
    local base = bp.resolve_base("https://wifihaven.net", "https://api.wifihaven.net")
    local html = bp.render_html(base, "youtube.com", "aa:bb:cc:11:22:33")
    assert.truthy(html:find("https://wifihaven.net/blocked", 1, true))
    assert.is_nil(html:find("api.wifihaven.net", 1, true))
    assert.is_nil(html:find("reason=", 1, true))
  end)

  it("falls back to neutral inline copy when api_url is missing", function()
    local html = bp.render_html(nil, "youtube.com", "aa:bb:cc:11:22:33")
    assert.truthy(html:find("This site is blocked.", 1, true))
    assert.is_nil(html:find("window.location.replace", 1, true))
  end)

  -- inline_copy_for + parse_reasons + parse_blocked_hosts are no longer called
  -- by the handler (#1617). They remain in the module for now and get deleted
  -- in PR3 (#1618) along with the agent-side reason-file writes.
  it("inline copy still resolves MacBlockReason / ExtraBlocked / fallback (kept for PR3)", function()
    assert.equals("This profile is paused.",                  bp.inline_copy_for("Paused"))
    assert.equals("This is scheduled quiet time.",            bp.inline_copy_for("Schedule"))
    assert.equals("Daily screen time limit reached.",         bp.inline_copy_for("TimeLimit"))
    assert.equals("This device has been blocked by a parent.",bp.inline_copy_for("Manual"))
    assert.equals("This site is blocked by the household.",   bp.inline_copy_for("ExtraBlocked"))
    assert.equals("This site is blocked.",                    bp.inline_copy_for("Bogus"))
    assert.equals("This site is blocked.",                    bp.inline_copy_for(nil))
  end)

  it("inline copy names the blocklist for a category:<id> reason (#594, kept for PR3)", function()
    assert.equals("Blocked category: ads.",   bp.inline_copy_for("category:ads"))
    assert.equals("Blocked category: adult.", bp.inline_copy_for("category:adult"))
  end)

  it("escapes the host in the inline page so it can't break out of HTML", function()
    local html = bp.render_html(nil, "<script>alert(1)</script>", nil)
    assert.is_nil(html:find("<script>alert(1)</script>", 1, true))
    assert.truthy(html:find("&lt;script&gt;", 1, true))
  end)
end)
