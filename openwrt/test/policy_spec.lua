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

-- ── policy.clock_skew (issue #312) ────────────────────────────────────────

describe("policy.clock_skew", function()

  -- Stub os.time so the "now" the fetch logic compares against the API's
  -- Date header is deterministic. 2026-05-08T14:00:30Z (30s after the
  -- canonical SNAPSHOT_JSON generated_at) — verified via os.time of the
  -- broken-down struct: this is the real UTC epoch.
  local FAKE_NOW = 1778245230
  local real_os_time

  before_each(function()
    real_os_time = os.time
    -- Only stub the no-arg form (the "current time" query). The
    -- broken-down-time → epoch conversion form (os.time({year=...})) still
    -- needs the real implementation so the agent's RFC 1123 Date parser
    -- can resolve the API timestamp.
    os.time = function(t)
      if t then return real_os_time(t) end
      return FAKE_NOW
    end
  end)
  after_each(function()
    os.time = real_os_time
  end)

  it("returns nil before any successful fetch has measured drift", function()
    -- Reload the module to clear any cached state from prior tests.
    package.loaded["familydns.policy"] = nil
    package.loaded["policy"] = nil
    local fresh = require("policy")
    assert.is_nil(fresh.clock_skew())
  end)

  it("captures positive drift when the router clock is ahead of the API", function()
    package.loaded["familydns.policy"] = nil
    package.loaded["policy"] = nil
    local fresh = require("policy")
    -- API says it's 14:00:00; router says 14:00:30 → drift = +30
    local function get_fn(_url, _hdrs)
      return 200, SNAPSHOT_JSON,
             { Date = "Fri, 08 May 2026 14:00:00 GMT" }
    end
    fresh.fetch("http://api:8080", "rt_tok", nil, get_fn)
    assert.equal(30, fresh.clock_skew())
  end)

  it("captures negative drift when the router clock is behind the API", function()
    package.loaded["familydns.policy"] = nil
    package.loaded["policy"] = nil
    local fresh = require("policy")
    -- API says 14:01:00; router (FAKE_NOW) is at 14:00:30 → drift = -30
    local function get_fn(_url, _hdrs)
      return 200, SNAPSHOT_JSON,
             { Date = "Fri, 08 May 2026 14:01:00 GMT" }
    end
    fresh.fetch("http://api:8080", "rt_tok", nil, get_fn)
    assert.equal(-30, fresh.clock_skew())
  end)

  it("looks up the Date header case-insensitively (curl emits lowercase)", function()
    package.loaded["familydns.policy"] = nil
    package.loaded["policy"] = nil
    local fresh = require("policy")
    local function get_fn(_url, _hdrs)
      return 200, SNAPSHOT_JSON,
             { date = "Fri, 08 May 2026 14:00:00 GMT" }
    end
    fresh.fetch("http://api:8080", "rt_tok", nil, get_fn)
    assert.equal(30, fresh.clock_skew())
  end)

  it("also updates skew on a 304 Not Modified response", function()
    -- The agent's most common response in steady state is 304; we still
    -- need to capture skew, otherwise the warning never updates after the
    -- first poll.
    package.loaded["familydns.policy"] = nil
    package.loaded["policy"] = nil
    local fresh = require("policy")
    local function get_fn(_url, _hdrs)
      return 304, "",
             { Date = "Fri, 08 May 2026 14:00:00 GMT" }
    end
    fresh.fetch("http://api:8080", "rt_tok", "sha256:abc", get_fn)
    assert.equal(30, fresh.clock_skew())
  end)

  it("logs a warning when |drift| exceeds 60 seconds", function()
    package.loaded["familydns.policy"] = nil
    package.loaded["policy"] = nil
    local fresh = require("policy")
    local warnings = {}
    local stub_log = {
      info  = function() end,
      err   = function() end,
      warn  = function(fmt, ...) warnings[#warnings+1] = string.format(fmt, ...) end,
      debug = function() end,
    }
    -- 5-minute skew (300s)
    local function get_fn(_url, _hdrs)
      return 200, SNAPSHOT_JSON,
             { Date = "Fri, 08 May 2026 13:55:30 GMT" }
    end
    fresh.fetch("http://api:8080", "rt_tok", nil, get_fn, stub_log)
    assert.equal(300, fresh.clock_skew())
    local matched = false
    for _, w in ipairs(warnings) do
      if w:find("skew", 1, true) or w:find("clock", 1, true) then matched = true end
    end
    assert.is_true(matched, "expected a clock-skew warning log line")
  end)

  it("does NOT log a warning for small drift (≤ 60s)", function()
    package.loaded["familydns.policy"] = nil
    package.loaded["policy"] = nil
    local fresh = require("policy")
    local warnings = {}
    local stub_log = {
      info  = function() end,
      err   = function() end,
      warn  = function(fmt, ...) warnings[#warnings+1] = string.format(fmt, ...) end,
      debug = function() end,
    }
    local function get_fn(_url, _hdrs)
      return 200, SNAPSHOT_JSON,
             { Date = "Fri, 08 May 2026 14:00:00 GMT" }  -- 30s drift
    end
    fresh.fetch("http://api:8080", "rt_tok", nil, get_fn, stub_log)
    assert.equal(0, #warnings)
  end)

  it("leaves clock_skew unchanged when the response omits a Date header", function()
    package.loaded["familydns.policy"] = nil
    package.loaded["policy"] = nil
    local fresh = require("policy")
    -- First call seeds a known skew.
    fresh.fetch("http://api:8080", "rt_tok", nil, function()
      return 200, SNAPSHOT_JSON, { Date = "Fri, 08 May 2026 14:00:00 GMT" }
    end)
    assert.equal(30, fresh.clock_skew())
    -- Second call has no Date header — skew must not regress to nil/0.
    fresh.fetch("http://api:8080", "rt_tok", nil, function()
      return 200, SNAPSHOT_JSON, {}
    end)
    assert.equal(30, fresh.clock_skew())
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

  -- #308: the atomic-swap (boot skeleton → runtime table) is baked into
  -- the rendered nft file as `add+delete` prelude statements, so policy.apply
  -- no longer needs a separate `nft delete table ...` shell command before
  -- the `nft -f`. The single `nft -f` invocation must do the whole swap in
  -- one atomic transaction.
  it("issues exactly one nft command and it is `nft -f` on the rendered file (#308)", function()
    local nft_cmds = {}
    policy.apply(decode_snap(),
      function(_path, _content) return true, nil end,
      function(cmd)
        if cmd:find("nft") then table.insert(nft_cmds, cmd) end
        return 0
      end)
    assert.equal(1, #nft_cmds,
      "expected exactly one nft command (atomic swap is in the rendered file)")
    assert.truthy(nft_cmds[1]:find("nft -f", 1, true),
      "expected `nft -f` invocation; got: " .. tostring(nft_cmds[1]))
    assert.truthy(nft_cmds[1]:find("/tmp/nftables.d/familydns.nft", 1, true),
      "expected the rendered file path in the nft command")
    assert.is_nil(nft_cmds[1]:find("delete table", 1, true),
      "policy.apply must not issue a separate `nft delete table` — the prelude in the rendered file handles it atomically")
  end)

  it("returns false when a write fails", function()
    local ok = policy.apply(decode_snap(),
      function(_path, _content) return nil, "io error" end,
      function(_cmd) return 0 end)
    assert.is_false(ok)
  end)

end)

-- ── policy.save_snapshot / load_snapshot (flash persistence, #309) ────────

describe("policy.save_snapshot", function()

  local SNAPSHOT_PATH = "/etc/familydns/policy.json"

  local function decode_snap()
    local json = require("cjson")
    return json.decode(SNAPSHOT_JSON)
  end

  it("writes JSON to a tmp file and renames to /etc/familydns/policy.json", function()
    local writes = {}
    local renames = {}
    local ok = policy.save_snapshot(decode_snap(),
      function(path, content) writes[path] = content; return true, nil end,
      function(from, to) table.insert(renames, { from = from, to = to }); return true end)
    assert.is_true(ok)
    assert.equal(1, #renames)
    assert.equal(SNAPSHOT_PATH, renames[1].to)
    -- Tmp path is some path other than the final path.
    assert.not_equal(SNAPSHOT_PATH, renames[1].from)
    -- The tmp path is what received the write.
    assert.truthy(writes[renames[1].from])
    -- And the final path was NEVER written directly (atomic).
    assert.is_nil(writes[SNAPSHOT_PATH])
  end)

  it("writes JSON that round-trips to the original snapshot", function()
    local json = require("cjson")
    local captured_content
    policy.save_snapshot(decode_snap(),
      function(_path, content) captured_content = content; return true, nil end,
      function(_from, _to) return true end)
    local decoded = json.decode(captured_content)
    assert.equal(3, decoded.profiles[1].id)
    assert.equal("kid-ipad", decoded.devices[1].name)
  end)

  it("returns false and does not rename when the write fails", function()
    local renamed = false
    local ok = policy.save_snapshot(decode_snap(),
      function(_path, _content) return nil, "disk full" end,
      function(_from, _to) renamed = true; return true end)
    assert.is_false(ok)
    assert.is_false(renamed)
  end)

  it("returns false when the rename fails", function()
    local ok = policy.save_snapshot(decode_snap(),
      function(_path, _content) return true, nil end,
      function(_from, _to) return nil, "rename failed" end)
    assert.is_false(ok)
  end)

end)

describe("policy.load_snapshot", function()

  it("returns the decoded snapshot when the read returns valid JSON", function()
    local snap = policy.load_snapshot(function(_path) return SNAPSHOT_JSON end)
    assert.not_nil(snap)
    assert.equal(3, snap.profiles[1].id)
  end)

  it("reads from /etc/familydns/policy.json", function()
    local read_path
    policy.load_snapshot(function(path) read_path = path; return SNAPSHOT_JSON end)
    assert.equal("/etc/familydns/policy.json", read_path)
  end)

  it("returns nil when the file is missing (read_fn returns nil)", function()
    assert.is_nil(policy.load_snapshot(function(_path) return nil end))
  end)

  it("returns nil when the file contents are corrupt JSON", function()
    assert.is_nil(policy.load_snapshot(function(_path) return "{ not valid json" end))
  end)

  it("returns nil when the file is empty", function()
    assert.is_nil(policy.load_snapshot(function(_path) return "" end))
  end)

end)

-- ── policy.mark_poll_success / poll_age_seconds (#309, drives #311) ──────

describe("policy.mark_poll_success / poll_age_seconds", function()

  before_each(function()
    -- Reset module-level state between tests.
    policy.reset_poll_state()
  end)

  it("poll_age_seconds returns math.huge before any successful poll", function()
    assert.equal(math.huge, policy.poll_age_seconds(1000))
  end)

  it("mark_poll_success(t) makes poll_age_seconds(t) return 0", function()
    policy.mark_poll_success(1000)
    assert.equal(0, policy.poll_age_seconds(1000))
  end)

  it("poll_age_seconds grows monotonically as `now` advances", function()
    policy.mark_poll_success(1000)
    assert.equal(0,   policy.poll_age_seconds(1000))
    assert.equal(60,  policy.poll_age_seconds(1060))
    assert.equal(300, policy.poll_age_seconds(1300))
  end)

  it("a later mark_poll_success resets the age to 0", function()
    policy.mark_poll_success(1000)
    assert.equal(120, policy.poll_age_seconds(1120))
    policy.mark_poll_success(1200)
    assert.equal(0,   policy.poll_age_seconds(1200))
  end)

  it("last_successful_poll_ts is exposed as a module field", function()
    assert.is_nil(policy.last_successful_poll_ts)
    policy.mark_poll_success(1234)
    assert.equal(1234, policy.last_successful_poll_ts)
  end)

end)
