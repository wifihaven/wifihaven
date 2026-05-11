-- Tests for openwrt/files/usr/lib/lua/familydns/policy.lua
-- Run with: cd openwrt && busted test/policy_spec.lua

local policy = require("policy")

-- Minimal valid snapshot JSON (§5.2 wire contract)
local SNAPSHOT_JSON = [[{
  "etag": "sha256:abc123",
  "generated_at": "2026-05-08T14:00:00Z",
  "default_profile_id": 3,
  "devices": [
    { "mac": "aa:bb:cc:11:22:33", "profile_id": 3, "name": "kid-ipad" }
  ],
  "profiles": [
    {
      "id": 3, "name": "kids", "paused": false,
      "blocked_categories": ["ads"],
      "extra_blocked": ["tiktok.com"],
      "extra_allowed": [],
      "schedules": [],
      "daily_minutes": 120,
      "site_limits": [],
      "time_used_today": { "total_minutes": 0, "by_domain": {} },
      "extensions_today_minutes": 0
    }
  ],
  "blocklists": {}
}]]

-- ── policy.fetch ──────────────────────────────────────────────────────────

describe("policy.fetch", function()

  it("returns decoded snapshot and etag on HTTP 200", function()
    local function get_fn(_url, _hdrs)
      return 200, SNAPSHOT_JSON, { etag = "sha256:abc123" }
    end
    local snap, etag = policy.fetch("http://api:8080", "rt_tok", nil, get_fn)
    assert.not_nil(snap)
    assert.equal("sha256:abc123", etag)
    assert.equal(3,          snap.profiles[1].id)
    assert.equal("kid-ipad", snap.devices[1].name)
  end)

  it("returns nil snapshot (no rewrite needed) and unchanged etag on HTTP 304", function()
    local function get_fn(_url, _hdrs)
      return 304, "", {}
    end
    local snap, etag = policy.fetch("http://api:8080", "rt_tok", "sha256:abc123", get_fn)
    assert.is_nil(snap)
    assert.equal("sha256:abc123", etag)
  end)

  it("sends If-None-Match header when a prior etag is available", function()
    local sent_hdrs
    local function get_fn(_url, hdrs)
      sent_hdrs = hdrs
      return 304, "", {}
    end
    policy.fetch("http://api:8080", "rt_tok", "sha256:prev", get_fn)
    assert.equal("sha256:prev", sent_hdrs["If-None-Match"])
  end)

  it("sends Authorization: Bearer header with the router token", function()
    local sent_hdrs
    local function get_fn(_url, hdrs)
      sent_hdrs = hdrs
      return 200, SNAPSHOT_JSON, {}
    end
    policy.fetch("http://api:8080", "rt_tok_xyz", nil, get_fn)
    assert.equal("Bearer rt_tok_xyz", sent_hdrs["Authorization"])
  end)

  it("includes the etag in the request URL as ?since= param", function()
    local called_url
    local function get_fn(url, _hdrs)
      called_url = url
      return 304, "", {}
    end
    policy.fetch("http://api:8080", "rt_tok", "sha256:prev", get_fn)
    assert.truthy(called_url:find("sha256:prev", 1, true))
  end)

  it("URL-encodes the etag in the ?since= param so quotes are not literal", function()
    local called_url
    local function get_fn(url, _hdrs)
      called_url = url
      return 304, "", {}
    end
    -- Canonical HTTP etag includes surrounding double quotes.
    policy.fetch("http://api:8080", "rt_tok", '"sha256:abc123"', get_fn)
    assert.truthy(called_url)
    assert.is_nil(called_url:find('"', 1, true),
      "URL must not contain literal double-quote characters: " .. tostring(called_url))
    -- Sanity: the encoded form should appear.
    assert.truthy(called_url:find("%22", 1, true),
      "expected percent-encoded quote (%22) in URL: " .. tostring(called_url))
  end)

  it("returns nil, nil on a 5xx error", function()
    local function get_fn(_url, _hdrs) return 503, "unavailable", {} end
    local snap, etag = policy.fetch("http://api:8080", "rt_tok", nil, get_fn)
    assert.is_nil(snap)
    assert.is_nil(etag)
  end)

  it("returns nil, nil when get_fn returns nil status (connection failure)", function()
    local function get_fn(_url, _hdrs) return nil, "", {} end
    local snap, etag = policy.fetch("http://api:8080", "rt_tok", nil, get_fn)
    assert.is_nil(snap)
    assert.is_nil(etag)
  end)

  it("requests /api/router/policy endpoint", function()
    local called_url
    local function get_fn(url, _hdrs)
      called_url = url
      return 200, SNAPSHOT_JSON, {}
    end
    policy.fetch("http://api:8080", "rt_tok", nil, get_fn)
    assert.truthy(called_url:find("/api/router/policy", 1, true))
  end)

end)

-- ── policy.apply ──────────────────────────────────────────────────────────

describe("policy.apply", function()

  local function decode_snap()
    local json = require("cjson")
    return json.decode(SNAPSHOT_JSON)
  end

  it("writes dnsmasq conf to /tmp/dnsmasq.d/familydns.conf", function()
    local writes = {}
    policy.apply(decode_snap(),
      function(path, _content) writes[path] = true; return true, nil end,
      function(_cmd) return 0 end)
    assert.truthy(writes["/tmp/dnsmasq.d/familydns.conf"])
  end)

  it("writes nft fragment to /tmp/nftables.d/familydns.nft", function()
    local writes = {}
    policy.apply(decode_snap(),
      function(path, _content) writes[path] = true; return true, nil end,
      function(_cmd) return 0 end)
    assert.truthy(writes["/tmp/nftables.d/familydns.nft"])
  end)

  it("calls a dnsmasq reload command after writing", function()
    local reloads = {}
    policy.apply(decode_snap(),
      function(_path, _content) return true, nil end,
      function(cmd) table.insert(reloads, cmd); return 0 end)
    local found = false
    for _, cmd in ipairs(reloads) do
      if cmd:find("dnsmasq") then found = true end
    end
    assert.is_true(found, "expected a dnsmasq reload command")
  end)

  it("calls an nft reload command after writing", function()
    local reloads = {}
    policy.apply(decode_snap(),
      function(_path, _content) return true, nil end,
      function(cmd) table.insert(reloads, cmd); return 0 end)
    local found = false
    for _, cmd in ipairs(reloads) do
      if cmd:find("nft") then found = true end
    end
    assert.is_true(found, "expected an nft reload command")
  end)

  it("skips both reload commands when the dnsmasq write fails", function()
    local reloads = {}
    policy.apply(decode_snap(),
      function(path, _content)
        if path:find("dnsmasq") then return nil, "disk full" end
        return true, nil
      end,
      function(cmd) table.insert(reloads, cmd); return 0 end)
    assert.equal(0, #reloads)
  end)

  it("skips both reload commands when the nft write fails", function()
    local reloads = {}
    policy.apply(decode_snap(),
      function(path, _content)
        if path:find("%.nft$") then return nil, "disk full" end
        return true, nil
      end,
      function(cmd) table.insert(reloads, cmd); return 0 end)
    assert.equal(0, #reloads)
  end)

  it("writes non-empty content for both files", function()
    local contents = {}
    policy.apply(decode_snap(),
      function(path, content) contents[path] = content; return true, nil end,
      function(_cmd) return 0 end)
    assert.truthy(contents["/tmp/dnsmasq.d/familydns.conf"] and
                  #contents["/tmp/dnsmasq.d/familydns.conf"] > 0)
    assert.truthy(contents["/tmp/nftables.d/familydns.nft"] and
                  #contents["/tmp/nftables.d/familydns.nft"] > 0)
  end)

  it("returns true on success", function()
    local ok = policy.apply(decode_snap(),
      function(_path, _content) return true, nil end,
      function(_cmd) return 0 end)
    assert.is_true(ok)
  end)

  it("returns false when a write fails", function()
    local ok = policy.apply(decode_snap(),
      function(_path, _content) return nil, "io error" end,
      function(_cmd) return 0 end)
    assert.is_false(ok)
  end)

end)
