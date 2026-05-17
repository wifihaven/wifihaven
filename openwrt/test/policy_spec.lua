-- Tests for openwrt/files/usr/lib/lua/wifihaven/policy.lua
-- Run with: cd openwrt && busted test/policy_spec.lua

local policy = require("policy")

-- Minimal valid snapshot JSON (§6.2 wire contract, post-#354 shape).
-- devices/profiles are JSON objects (Maps) keyed by mac / stringified
-- profileId. The agent never sees raw schedules / time-limits — they're
-- collapsed into BlockRules.blocked server-side.
local SNAPSHOT_JSON = [[{
  "etag": "sha256:abc123",
  "generatedAt": "2026-05-08T14:00:00Z",
  "devices": {
    "aa:bb:cc:11:22:33": { "profileId": 3, "name": "kid-ipad", "rules": null }
  },
  "profiles": {
    "3": {
      "name": "kids",
      "rules": {
        "blocked": false,
        "blockReason": null,
        "extraBlocked": ["tiktok.com"],
        "extraAllowed": [],
        "blocklistIds": ["ads"],
        "blockIpOnly": false
      },
      "failureMode": "block-all"
    }
  },
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
    assert.equal("kids",     snap.profiles["3"].name)
    assert.equal("kid-ipad", snap.devices["aa:bb:cc:11:22:33"].name)
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

  it("writes dnsmasq conf to /tmp/dnsmasq.d/wifihaven.conf", function()
    local writes = {}
    policy.apply(decode_snap(),
      function(path, _content) writes[path] = true; return true, nil end,
      function(_cmd) return 0 end)
    assert.truthy(writes["/tmp/dnsmasq.d/wifihaven.conf"])
  end)

  it("writes nft fragment to /tmp/nftables.d/wifihaven.nft", function()
    local writes = {}
    policy.apply(decode_snap(),
      function(path, _content) writes[path] = true; return true, nil end,
      function(_cmd) return 0 end)
    assert.truthy(writes["/tmp/nftables.d/wifihaven.nft"])
  end)

  -- #328: SIGHUP doesn't re-read conf-dir, so a `reload` leaves the new
  -- /tmp/dnsmasq.d/wifihaven.conf entries silently inactive until something
  -- else restarts dnsmasq. We must `restart` to pick up dhcp-host= and
  -- nftset= directives. Post-#414 the restart is conditional on the
  -- rendered content actually differing from the on-disk copy; pass
  -- read_fn=nil-returning to simulate a cold first apply.
  local function fresh_opts()
    return { read_fn = function(_path) return nil end }
  end

  it("calls `/etc/init.d/dnsmasq restart` (not reload) after writing (#328)", function()
    local reloads = {}
    policy.apply(decode_snap(),
      function(_path, _content) return true, nil end,
      function(cmd) table.insert(reloads, cmd); return 0 end,
      nil, fresh_opts())
    local found_restart, found_reload = false, false
    for _, cmd in ipairs(reloads) do
      if cmd == "/etc/init.d/dnsmasq restart" then found_restart = true end
      if cmd == "/etc/init.d/dnsmasq reload"  then found_reload  = true end
    end
    assert.is_true(found_restart,
      "expected `/etc/init.d/dnsmasq restart`; got: " .. table.concat(reloads, " | "))
    assert.is_false(found_reload,
      "must not use `reload` — SIGHUP doesn't re-read conf-dir (#328)")
  end)

  it("calls an nft reload command after writing", function()
    local reloads = {}
    policy.apply(decode_snap(),
      function(_path, _content) return true, nil end,
      function(cmd) table.insert(reloads, cmd); return 0 end,
      nil, fresh_opts())
    local found = false
    for _, cmd in ipairs(reloads) do
      if cmd:find("nft") then found = true end
    end
    assert.is_true(found, "expected an nft reload command")
  end)

  -- #414: avoid the DNS service blip when policy.apply runs but the rendered
  -- dnsmasq.conf is byte-identical to what's on disk. Most apply calls flip
  -- only nft-side state (blocked_macs membership from schedule / pause /
  -- time-limit transitions), so the dnsmasq.conf side is stable.
  it("skips dnsmasq restart when rendered conf matches on-disk copy (#414)", function()
    local render = require("render")
    local rendered = render.dnsmasq(decode_snap())
    local reloads = {}
    policy.apply(decode_snap(),
      function(_path, _content) return true, nil end,
      function(cmd) table.insert(reloads, cmd); return 0 end,
      nil,
      { read_fn = function(path)
          if path == "/tmp/dnsmasq.d/wifihaven.conf" then return rendered end
          return nil
        end })
    for _, cmd in ipairs(reloads) do
      assert.is_nil(cmd:find("dnsmasq"),
        "must not restart dnsmasq when conf is unchanged; got: " .. cmd)
    end
    -- nft reload still runs unconditionally
    local found_nft = false
    for _, cmd in ipairs(reloads) do
      if cmd:find("nft ") then found_nft = true end
    end
    assert.is_true(found_nft, "nft reload should still run even when dnsmasq is unchanged")
  end)

  it("restarts dnsmasq when on-disk copy differs from rendered content (#414)", function()
    local reloads = {}
    policy.apply(decode_snap(),
      function(_path, _content) return true, nil end,
      function(cmd) table.insert(reloads, cmd); return 0 end,
      nil,
      { read_fn = function(_path) return "# stale content\n" end })
    local found_restart = false
    for _, cmd in ipairs(reloads) do
      if cmd == "/etc/init.d/dnsmasq restart" then found_restart = true end
    end
    assert.is_true(found_restart,
      "expected restart when on-disk copy is stale; got: " .. table.concat(reloads, " | "))
  end)

  it("restarts dnsmasq on first apply when no conf exists on disk (#414)", function()
    local reloads = {}
    policy.apply(decode_snap(),
      function(_path, _content) return true, nil end,
      function(cmd) table.insert(reloads, cmd); return 0 end,
      nil,
      { read_fn = function(_path) return nil end })
    local found_restart = false
    for _, cmd in ipairs(reloads) do
      if cmd == "/etc/init.d/dnsmasq restart" then found_restart = true end
    end
    assert.is_true(found_restart,
      "expected restart on first apply (missing file); got: " .. table.concat(reloads, " | "))
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
    assert.truthy(contents["/tmp/dnsmasq.d/wifihaven.conf"] and
                  #contents["/tmp/dnsmasq.d/wifihaven.conf"] > 0)
    assert.truthy(contents["/tmp/nftables.d/wifihaven.nft"] and
                  #contents["/tmp/nftables.d/wifihaven.nft"] > 0)
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
    assert.truthy(nft_cmds[1]:find("/tmp/nftables.d/wifihaven.nft", 1, true),
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

  -- ── #328 smoke check: confirm dnsmasq actually picked up the new policy ──
  -- A snapshot whose profile rules contain at least one extraBlocked entry,
  -- so render.dnsmasq emits address=/.../# and the smoke probe path fires.
  local SMOKE_SNAPSHOT_JSON = [[{
    "etag": "sha256:smoke",
    "generatedAt": "2026-05-08T14:00:00Z",
    "devices": {
      "aa:bb:cc:11:22:33": { "profileId": 3, "name": "k", "rules": null }
    },
    "profiles": {
      "3": {
        "name": "kids",
        "rules": {
          "blocked": false, "blockReason": null,
          "extraBlocked": ["badsite.example.com"],
          "extraAllowed": [], "blocklistIds": [], "blockIpOnly": false
        },
        "failureMode": "block-all"
      }
    },
    "blocklists": {}
  }]]

  local function decode_smoke()
    local json = require("cjson")
    return json.decode(SMOKE_SNAPSHOT_JSON)
  end

  local function capture_log()
    local warns, infos = {}, {}
    return {
      info  = function(fmt, ...) infos[#infos+1] = string.format(fmt, ...) end,
      err   = function() end,
      warn  = function(fmt, ...) warns[#warns+1] = string.format(fmt, ...) end,
      debug = function() end,
    }, warns, infos
  end

  -- Force dnsmasq_changed=true for the smoke-probe tests so the probe path
  -- always runs regardless of any leftover /tmp/dnsmasq.d file on disk (#414).
  local function smoke_opts(extra)
    local o = { read_fn = function(_p) return nil end }
    for k, v in pairs(extra or {}) do o[k] = v end
    return o
  end

  it("invokes dns_check_fn with an extraBlocked host after the restart (#328 / #351)", function()
    local probed
    policy.apply(decode_smoke(),
      function(_p, _c) return true, nil end,
      function(_cmd) return 0 end,
      nil,
      smoke_opts({ dns_check_fn = function(domain) probed = domain; return "93.184.216.34" end }))
    assert.equal("badsite.example.com", probed)
  end)

  -- Post-#351 semantics inversion (Truth 1): DNS is NOT the enforcement
  -- plane. Blocked hosts must resolve to a real IP — a sinkhole-shaped
  -- answer means dnsmasq is still applying a stale `address=/.../#`
  -- config, which is the failure we now want to catch.

  it("logs a warning when dns_check_fn returns a sinkhole IP (post-#351)", function()
    local stub_log, warns = capture_log()
    policy.apply(decode_smoke(),
      function(_p, _c) return true, nil end,
      function(_cmd) return 0 end,
      stub_log,
      smoke_opts({ dns_check_fn = function(_domain) return "0.0.0.0" end }))
    local matched = false
    for _, w in ipairs(warns) do
      if w:find("smoke", 1, true) and w:find("0.0.0.0", 1, true) then
        matched = true
      end
    end
    assert.is_true(matched,
      "expected a smoke-check warning mentioning the sinkhole IP; got: " ..
      table.concat(warns, " | "))
  end)

  it("logs a warning when dns_check_fn returns empty (NXDOMAIN — stale or upstream-broken)", function()
    local stub_log, warns = capture_log()
    policy.apply(decode_smoke(),
      function(_p, _c) return true, nil end,
      function(_cmd) return 0 end,
      stub_log,
      smoke_opts({ dns_check_fn = function(_d) return "" end }))
    local matched = false
    for _, w in ipairs(warns) do
      if w:find("smoke", 1, true) then matched = true end
    end
    assert.is_true(matched)
  end)

  it("does NOT warn when dns_check_fn returns a real upstream IP (post-#351)", function()
    local stub_log, warns = capture_log()
    policy.apply(decode_smoke(),
      function(_p, _c) return true, nil end,
      function(_cmd) return 0 end,
      stub_log,
      smoke_opts({ dns_check_fn = function(_d) return "93.184.216.34" end }))
    for _, w in ipairs(warns) do
      assert.is_nil(w:find("smoke", 1, true),
        "unexpected smoke-check warning: " .. w)
    end
  end)

  it("logs a warning when dns_check_fn returns nil (no answer at all)", function()
    local stub_log, warns = capture_log()
    policy.apply(decode_smoke(),
      function(_p, _c) return true, nil end,
      function(_cmd) return 0 end,
      stub_log,
      smoke_opts({ dns_check_fn = function(_d) return nil end }))
    local matched = false
    for _, w in ipairs(warns) do
      if w:find("smoke", 1, true) then matched = true end
    end
    assert.is_true(matched)
  end)

  it("skips dns_check_fn when the snapshot has no extraBlocked entries", function()
    local called = false
    local snap = decode_smoke()
    snap.profiles["3"].rules.extraBlocked = {}
    policy.apply(snap,
      function(_p, _c) return true, nil end,
      function(_cmd) return 0 end,
      nil,
      smoke_opts({ dns_check_fn = function(_d) called = true; return "93.184.216.34" end }))
    assert.is_false(called,
      "dns_check_fn should not be called when there is no extraBlocked host to probe")
  end)

  it("still returns true when dns_check_fn reports a sinkhole result (post-#351 — propagation is a separate concern)", function()
    local ok = policy.apply(decode_smoke(),
      function(_p, _c) return true, nil end,
      function(_cmd) return 0 end,
      nil,
      { dns_check_fn = function(_d) return "0.0.0.0" end })
    assert.is_true(ok,
      "smoke-check failure must not fail the apply — propagation is a separate concern")
  end)

  -- #331/#385: opts pass-through. Use an inline snapshot with an explicit
  -- failureMode="block-all" profile so render.lua's failover branch (which
  -- gates on `prof.failureMode == "block-all"`) actually fires.
  local function snap_with_closed_profile()
    return {
      etag = "sha256:test",
      generatedAt = "2026-05-08T14:00:00Z",
      devices = {
        ["aa:aa:aa:00:00:01"] = { profileId = 1, name = "kid-A", rules = nil },
      },
      profiles = {
        ["1"] = {
          name = "kids",
          rules = {
            blocked = false, blockReason = nil,
            extraBlocked = {}, extraAllowed = {}, blocklistIds = {}, blockIpOnly = false,
          },
          failureMode = "block-all",
        },
      },
      blocklists = {},
    }
  end

  it("passes opts through to render.nft so failover branch fires (#331/#422)", function()
    local nft_content
    policy.apply(snap_with_closed_profile(),
      function(path, content)
        if path:find("%.nft$") then nft_content = content end
        return true, nil
      end,
      function(_cmd) return 0 end,
      nil,
      { poll_failed = true })
    assert.truthy(nft_content)
    assert.truthy(nft_content:find("set failover_drop", 1, true),
      "expected failover_drop set when opts.poll_failed=true")
    assert.truthy(nft_content:find("wifihaven_failover", 1, true),
      "expected wifihaven_failover chain when opts.poll_failed=true")
    assert.truthy(nft_content:find("aa:aa:aa:00:00:01", 1, true),
      "expected the Closed-profile device MAC inside the failover set")
  end)

  it("omitting opts leaves the failover branch unreached (#331 regression)", function()
    local nft_content
    policy.apply(snap_with_closed_profile(),
      function(path, content)
        if path:find("%.nft$") then nft_content = content end
        return true, nil
      end,
      function(_cmd) return 0 end)
    assert.truthy(nft_content)
    assert.is_nil(nft_content:find("failover_drop", 1, true),
      "no failover artifacts when opts omitted")
  end)

end)

-- ── policy.failover_transition (#331) ─────────────────────────────────────

describe("policy.failover_transition (#422)", function()

  it("no-op when fetch succeeds and we were not in failover", function()
    local should, opts, new = policy.failover_transition(false, true)
    assert.is_false(should)
    assert.is_nil(opts)
    assert.is_false(new)
  end)

  it("trips failover immediately on a single failed fetch (#422)", function()
    local should, opts, new = policy.failover_transition(false, false)
    assert.is_true(should)
    assert.is_true(opts.poll_failed)
    assert.is_true(new)
  end)

  it("does NOT re-trigger while already in failover (dedupe)", function()
    local should, opts, new = policy.failover_transition(true, false)
    assert.is_false(should)
    assert.is_nil(opts)
    assert.is_true(new)
  end)

  it("lifts failover on next successful fetch with opts.poll_failed=false", function()
    local should, opts, new = policy.failover_transition(true, true)
    assert.is_true(should)
    assert.is_false(opts.poll_failed)
    assert.is_false(new)
  end)

  it("rapid recover + re-fail re-arms the transition", function()
    -- fail #1 → trip
    local s1, _, nif1 = policy.failover_transition(false, false)
    assert.is_true(s1); assert.is_true(nif1)
    -- success → lift
    local s2, opts2, nif2 = policy.failover_transition(nif1, true)
    assert.is_true(s2); assert.is_false(opts2.poll_failed); assert.is_false(nif2)
    -- fail #2 → trip again
    local s3, opts3, nif3 = policy.failover_transition(nif2, false)
    assert.is_true(s3); assert.is_true(opts3.poll_failed); assert.is_true(nif3)
  end)

end)

-- ── policy.save_snapshot / load_snapshot (flash persistence, #309) ────────

describe("policy.save_snapshot", function()

  local SNAPSHOT_PATH = "/etc/wifihaven/policy.json"

  local function decode_snap()
    local json = require("cjson")
    return json.decode(SNAPSHOT_JSON)
  end

  it("writes JSON to a tmp file and renames to /etc/wifihaven/policy.json", function()
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
    assert.equal("kids", decoded.profiles["3"].name)
    assert.equal("kid-ipad", decoded.devices["aa:bb:cc:11:22:33"].name)
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
    assert.equal("kids", snap.profiles["3"].name)
  end)

  it("reads from /etc/wifihaven/policy.json", function()
    local read_path
    policy.load_snapshot(function(path) read_path = path; return SNAPSHOT_JSON end)
    assert.equal("/etc/wifihaven/policy.json", read_path)
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

-- ── policy.format_poll_age (#410) ────────────────────────────────────────
--
-- string.format("%d", math.huge) leaves "%d" literal in the output (since
-- math.huge isn't integer-coercible), producing malformed log lines like
-- `poll_age=%ds inf` on a cold-boot agent. format_poll_age normalises both
-- branches so callers can use %s.

describe("policy.format_poll_age", function()

  it("returns \"inf\" for the math.huge cold-boot sentinel", function()
    assert.equal("inf", policy.format_poll_age(math.huge))
  end)

  it("formats finite seconds as \"<n>s\"", function()
    assert.equal("0s",   policy.format_poll_age(0))
    assert.equal("60s",  policy.format_poll_age(60))
    assert.equal("301s", policy.format_poll_age(301))
  end)

end)
