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

describe("block_page module surface (#1618)", function()
  -- After #1615/#1617 the handler stopped reading the agent's on-disk reason /
  -- blocked-host files; the SPA derives the canonical reason from
  -- GET /api/blocked. The now-unused parse_reasons / parse_blocked_hosts /
  -- inline_copy_for helpers are gone.
  it("no longer exposes parse_reasons / parse_blocked_hosts / inline_copy_for", function()
    assert.is_nil(bp.parse_reasons)
    assert.is_nil(bp.parse_blocked_hosts)
    assert.is_nil(bp.inline_copy_for)
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

-- #2566/#2569/#2322: the block-page token is what tells the unauthenticated
-- /api/blocked + /api/access-requests endpoints WHICH HOUSEHOLD is asking.
describe("block_page.build_dest_url bpt (#2566/#2569/#2322)", function()
  it("appends the block-page token when the agent has one", function()
    local u = bp.build_dest_url(
      "https://app.wifihaven.net", "youtube.com", "aa:bb:cc:11:22:33", "tok-123")
    assert.truthy(u:find("bpt=tok-123", 1, true))
  end)

  it("url-encodes the token rather than splicing it raw", function()
    local u = bp.build_dest_url(
      "https://app.wifihaven.net", "youtube.com", "aa:bb:cc:11:22:33", "a+b/c=d&e")
    assert.is_nil(u:find("a+b/c=d&e", 1, true))
    assert.truthy(u:find("bpt=a%2Bb%2Fc%3Dd%26e", 1, true))
  end)

  -- The API↔agent wire is additive both ways: an agent with no token yet (API
  -- too old to serve one, or the first fetch hasn't landed) must still emit a
  -- working redirect, and it must not emit an empty bpt= the API would then
  -- have to treat as "invalid" rather than "absent".
  it("omits bpt entirely when the token is nil or empty", function()
    local none  = bp.build_dest_url("https://app.wifihaven.net", "x.com", "aa:bb:cc:11:22:33")
    local empty = bp.build_dest_url("https://app.wifihaven.net", "x.com", "aa:bb:cc:11:22:33", "")
    assert.is_nil(none:find("bpt", 1, true))
    assert.is_nil(empty:find("bpt", 1, true))
    assert.truthy(none:find("mac=", 1, true))
  end)

  it("carries the token through render_html's redirect document", function()
    local html = bp.render_html(
      "https://app.wifihaven.net", "youtube.com", "aa:bb:cc:11:22:33", "tok-123")
    assert.truthy(html:find("bpt=tok-123", 1, true))
  end)
end)

describe("block_page.fetch_token (#2566/#2569/#2322)", function()
  local function get_returning(status, body)
    local seen = {}
    return seen, function(url, headers)
      seen.url, seen.headers = url, headers
      return status, body, {}
    end
  end

  it("returns the token and authenticates as the router", function()
    local seen, get = get_returning(200, '{"token":"rid.sig"}')
    local tok, err = bp.fetch_token("http://api.example.com", "rt_secret", get)
    assert.equals("rid.sig", tok)
    assert.is_nil(err)
    assert.equals("http://api.example.com/api/router/block-page-token", seen.url)
    assert.equals("Bearer rt_secret", seen.headers["Authorization"])
  end)

  -- An API that predates the endpoint 404s. That is a supported state, not an
  -- error: the agent keeps running and simply omits bpt from the redirect.
  it("reports a non-200 as no-token rather than raising", function()
    local _, get = get_returning(404, "not found")
    local tok, err = bp.fetch_token("http://api.example.com", "rt_secret", get)
    assert.is_nil(tok)
    assert.equals("status_404", err)
  end)

  it("rejects a 200 whose body has no usable token", function()
    for _, body in ipairs({ "not json", "{}", '{"token":""}' }) do
      local _, get = get_returning(200, body)
      local tok = bp.fetch_token("http://api.example.com", "rt_secret", get)
      assert.is_nil(tok)
    end
  end)

  it("does not call out at all when api_url is unset", function()
    local called = false
    local tok, err = bp.fetch_token("", "rt_secret", function() called = true end)
    assert.is_nil(tok)
    assert.equals("no_api_url", err)
    assert.is_false(called)
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

  it("escapes the host in the inline page so it can't break out of HTML", function()
    local html = bp.render_html(nil, "<script>alert(1)</script>", nil)
    assert.is_nil(html:find("<script>alert(1)</script>", 1, true))
    assert.truthy(html:find("&lt;script&gt;", 1, true))
  end)
end)
