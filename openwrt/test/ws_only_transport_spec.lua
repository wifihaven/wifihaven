-- ws_only_transport_spec.lua — #2736, the agent is WEBSOCKET-ONLY.
--
-- Step 3 of the ws cutover. #2608 made the websocket the default transport and
-- #2731 closed the suppression gap; prod then measured `snapshot_poll_total`
-- increase = 0 over both 24h and 48h on both routers while
-- `policy_poll_skipped_total` climbed 28k in 24h — the agents alive and
-- actively choosing not to poll. #2736 deletes the poll rather than leaving it
-- dormant.
--
-- What these specs pin, and why each one is here:
--   (A) The REST snapshot poll is GONE FROM THE CODE, not merely gated off.
--   (B) usage/events never fall back to REST — the spool is the only exit.
--   (C) A router that loses ws keeps ENFORCING its last on-disk snapshot.
--   (D) Failover is keyed on ws-link health now, not on a poll result.
--   (E) The config that only existed to serve the fallback is gone.
--
-- LIVENESS ANCHORS. Most of this file asserts an ABSENCE ("no poll happens",
-- "http_post is never called"), and an absence assertion passes for free when
-- the harness is dead — the recorded lesson behind the whole #2731 measurement.
-- So every absence group here also asserts a POSITIVE fact through the SAME
-- rig: the same file read finds a string that must still be there, the same
-- fake `http_post` IS reached by a path that legitimately still uses it. If the
-- rig stops working, the anchor fails first.
--
-- Run with: cd openwrt && busted test/ws_only_transport_spec.lua

local policy      = require("policy")
local render      = require("render")
local ws_outbound = require("wifihaven.ws_outbound")

local AGENT_PATH  = "files/usr/sbin/wifihaven-agent"
local CONFIG_PATH = "files/etc/config/wifihaven"

local function slurp(path)
  local f = assert(io.open(path, "r"), "spec must run from openwrt/: " .. path)
  local s = f:read("*a")
  f:close()
  return s
end

-- ── (A) the REST snapshot poll is gone from the code ──────────────────────

describe("#2736 (A) the REST snapshot poll is removed, not just dormant", function()
  it("ANCHOR: the policy module really loaded (apply/load_snapshot present)", function()
    -- Without this, every `is_nil` below would pass for free on a broken
    -- require — the exact free-pass an absence assertion invites.
    assert.is_function(policy.apply)
    assert.is_function(policy.load_snapshot)
  end)

  it("policy.fetch no longer exists", function()
    assert.is_nil(policy.fetch)
  end)

  it("ANCHOR: the agent source was actually read (metrics push still on HTTP)", function()
    -- The metrics push deliberately STAYS on HTTP (ws_outbound tees only
    -- usage/events) so the ws_* observability series — including the
    -- ws_health_age_seconds that alert W15 keys on — still reach the server
    -- while the socket is down. It is also this group's proof-of-life: an
    -- empty or mis-pathed read cannot satisfy it.
    assert.is_not_nil(slurp(AGENT_PATH):find("/api/router/metrics", 1, true))
  end)

  it("the agent never calls GET /api/router/policy", function()
    assert.is_nil(slurp(AGENT_PATH):find("/api/router/policy", 1, true))
  end)

  it("the agent has no policy.fetch call site", function()
    assert.is_nil(slurp(AGENT_PATH):find("policy.fetch(", 1, true))
  end)

  it("the agent emits no snapshot_poll_total / policy_poll_skipped_total", function()
    local src = slurp(AGENT_PATH)
    assert.is_nil(src:find("snapshot_poll_total", 1, true))
    assert.is_nil(src:find("snapshot_poll_duration_seconds", 1, true))
    assert.is_nil(src:find("policy_poll_skipped_total", 1, true))
  end)
end)

-- ── (B) usage/events never fall back to REST ──────────────────────────────

describe("#2736 (B) the outbound tee has no HTTP fall-through", function()
  -- One rig for the whole group: a fake spool that can be told to fail, and a
  -- fake http_post that records every call. `health` is the sentinel timestamp
  -- the sidecar would have written (nil = absent = link down).
  local function rig(opts)
    opts = opts or {}
    local r = { posted = {}, spooled = {}, meters = {} }
    r.post = ws_outbound.make({
      http_post    = function(url, body, _h)
        r.posted[#r.posted + 1] = { url = url, body = body }
        return 200, "", nil
      end,
      spool_append = function(line)
        if opts.spool_fails then return false end
        r.spooled[#r.spooled + 1] = line
        return true
      end,
      metrics_inc  = function(result) r.meters[#r.meters + 1] = result end,
    })
    return r
  end

  it("ANCHOR: http_post is still wired and reachable for a non-teed op", function()
    -- Proves the fake CAN record a call. Without it, "http_post was never
    -- called" below would also hold for a tee that was never invoked at all.
    local r = rig({ health = nil })
    r.post("https://api.example/api/router/metrics", '{"m":1}', {})
    assert.are.equal(1, #r.posted)
    assert.are.equal("https://api.example/api/router/metrics", r.posted[1].url)
  end)

  it("spools usage even with the sentinel ABSENT (link down)", function()
    local r = rig({ health = nil })
    local code = r.post("https://api.example/api/router/usage", '{"u":1}', {})
    assert.are.equal(200, code)
    assert.are.equal(1, #r.spooled)
    assert.are.equal(0, #r.posted)
  end)

  it("spools events even with the sentinel STALE past the window", function()
    local r = rig({ health = 1, now = 100000, stale_after = 90 })
    r.post("https://api.example/api/router/events", '{"e":1}', {})
    assert.are.equal(1, #r.spooled)
    assert.are.equal(0, #r.posted)
  end)

  it("a failed spool write reports failure rather than POSTing it", function()
    -- Pre-#2736 this fell through to HTTP. With no REST ingest left, the datum
    -- must go back to the caller's retry queue: a non-2xx is that signal, and
    -- it must NOT be a silent 200 (which would drop the bucket on the floor).
    local r = rig({ health = 500, now = 520, spool_fails = true })
    local code = r.post("https://api.example/api/router/usage", '{"u":1}', {})
    assert.are_not.equal(200, code)
    assert.are.equal(0, #r.posted)
    assert.are.equal("spool_failed", r.meters[#r.meters])
  end)

  it("never meters an http_fallback outcome (the enum value is retired)", function()
    local r = rig({ health = nil })
    r.post("https://api.example/api/router/usage", '{"u":1}', {})
    for _, m in ipairs(r.meters) do
      assert.are_not.equal("http_fallback", m)
    end
  end)
end)

-- ── (C) a ws outage leaves enforcement running off the last snapshot ──────

describe("#2736 (C) enforcement survives a ws outage via the on-disk snapshot", function()
  local SNAPSHOT_JSON = [[{
    "etag": "sha256:offline",
    "generatedAt": "2026-08-23T12:00:00Z",
    "devices": { "aa:bb:cc:dd:ee:ff": { "profileId": 1, "name": "kid" } },
    "profiles": { "1": { "name": "kids", "failureMode": "last-known-good",
      "rules": { "blocked": true, "blockReason": "Schedule", "extraBlocked": [],
                 "extraAllowed": [], "blocklistIds": [], "blockIpOnly": false } } },
    "blocklists": {}
  }]]

  -- The whole point: no network of any kind is injected here. The only input
  -- is the file the ws sidecar last persisted.
  local function load(disk)
    return policy.load_snapshot(function(_path) return disk end)
  end

  it("ANCHOR: an EMPTY disk yields no snapshot and so no enforcement", function()
    -- The failing arm. Without it, "the snapshot still enforces" could pass on
    -- a rig that never loads anything and renders a default-deny skeleton.
    assert.is_nil(load(nil))
    assert.is_nil(load(""))
  end)

  it("the persisted snapshot still renders the per-MAC drop with no transport", function()
    local snap = load(SNAPSHOT_JSON)
    assert.is_not_nil(snap)
    assert.are.equal("sha256:offline", snap.etag)
    local nft = render.nft(snap)
    assert.is_not_nil(nft:find("aa:bb:cc:dd:ee:ff", 1, true),
      "the blocked MAC must still appear in the rendered ruleset")
  end)

  it("renders identically whether or not the link is up (transport-independent)", function()
    local snap = load(SNAPSHOT_JSON)
    assert.are.equal(render.nft(snap), render.nft(snap))
  end)
end)

-- ── (D) failover is keyed on ws-link health ───────────────────────────────

describe("#2736 (D) the failover edge reads the ws link, not a poll result", function()
  it("exposes the staleness bound derived from the heartbeat cadence", function()
    -- ws.fallback_after is deleted with the poll it served. The link-down
    -- judgement now comes off the cadence that actually refreshes the sentinel
    -- (the sidecar's heartbeat pong, #2731), single-sourced here.
    assert.is_number(ws_outbound.STALE_HEARTBEATS)
    assert.are.equal(ws_outbound.STALE_HEARTBEATS * 30, ws_outbound.stale_after(30))
  end)

  it("is_healthy reads the sentinel against stale_after, with no enabled gate", function()
    local function gate(health, now, stale)
      return ws_outbound.is_healthy({
        health_read = function() return health end,
        now         = function() return now end,
        stale_after = stale or 90,
      })
    end
    assert.is_true(gate(1000, 1050))   -- 50s < 90
    assert.is_true(gate(1000, 1090))   -- boundary is inclusive
    assert.is_false(gate(1000, 1091))
    assert.is_false(gate(nil, 1050))   -- absent sentinel is never fresh
  end)

  it("a healthy link lifts failover; a stale one trips it", function()
    -- The REAL failover_transition, driven by the REAL health predicate, in the
    -- shape on_tick uses. The `seen` tally is this group's liveness anchor: a
    -- model that never evaluated either branch cannot satisfy it.
    local seen = { healthy = 0, stale = 0 }
    local function tick(state, health, now)
      local link_ok = ws_outbound.is_healthy({
        health_read = function() return health end,
        now         = function() return now end,
        stale_after = 90,
      })
      seen[link_ok and "healthy" or "stale"] = seen[link_ok and "healthy" or "stale"] + 1
      local _, _, nif = policy.failover_transition(state.in_failover, link_ok)
      state.in_failover = nif
      return state
    end

    local st = { in_failover = false }
    tick(st, 1000, 1010)
    assert.is_false(st.in_failover, "a fresh sentinel must not trip failover")
    tick(st, 1000, 2000)
    assert.is_true(st.in_failover, "a stale sentinel must trip failover")
    tick(st, 2000, 2010)
    assert.is_false(st.in_failover, "a recovered link must lift failover")

    assert.are.equal(2, seen.healthy)
    assert.are.equal(1, seen.stale)
  end)
end)

-- ── (F) failover must not suppress the only policy path ──────────────────

describe("#2736 (F) a failover render does not stall the ws apply tick", function()
  -- THE BUG THIS PINS, found by Gate 2 rather than by review. The ws
  -- apply-on-push tick used to be gated on `not ts.in_failover_render` — "the
  -- failover chain owns the plane until the poll recovers" — which was safe
  -- only while the POLL was the thing that recovered. With the poll gone,
  -- failover trips on a stale ws sentinel and this tick is the ONLY way policy
  -- reaches enforcement, so the gate made failover suppress the sole apply
  -- path: a ws blip stalled a pushed snapshot for ~5 minutes on the real VM.

  it("the agent does not gate the ws apply tick on in_failover_render", function()
    local src = slurp(AGENT_PATH)
    assert.is_nil(src:find("if not ts.in_failover_render then", 1, true),
      "the ws apply-on-push tick must not be skipped while in failover")
  end)

  it("ANCHOR: the agent still HAS a failover render path", function()
    -- Without this, the assertion above would also pass on an agent that had
    -- dropped failover altogether, which would be a different bug.
    local src = slurp(AGENT_PATH)
    assert.is_not_nil(src:find("policy.failover_transition", 1, true))
    assert.is_not_nil(src:find("in_failover_render", 1, true))
  end)

  -- Behavioural model of on_tick's two blocks in their real order: the policy
  -- tick judges the link and may trip failover, then the ws apply tick reads
  -- the on-disk snapshot. Uses the REAL failover_transition and is_healthy.
  local function tick(state, opts)
    local link_ok = ws_outbound.is_healthy({
      health_read = function() return opts.health end,
      now         = function() return opts.now end,
      stale_after = 90,
    })
    local _, _, nif = policy.failover_transition(state.in_failover, link_ok)
    state.in_failover = nif
    -- ws apply tick: ungated by failover since #2736.
    if opts.pushed_etag and opts.pushed_etag ~= state.applied_etag then
      state.applied_etag = opts.pushed_etag
      state.applies = state.applies + 1
      state.in_failover = false   -- a push we applied proves the link is live
    end
    return state
  end

  local function fresh_state()
    return { in_failover = false, applied_etag = nil, applies = 0 }
  end

  it("ANCHOR: a pushed snapshot applies on a healthy link", function()
    local st = tick(fresh_state(), { health = 1000, now = 1010, pushed_etag = "e1" })
    assert.are.equal(1, st.applies)
    assert.is_false(st.in_failover)
  end)

  it("a stale link trips failover", function()
    local st = tick(fresh_state(), { health = 1000, now = 2000 })
    assert.is_true(st.in_failover)
  end)

  it("a snapshot pushed WHILE in failover still applies, and lifts it", function()
    local st = tick(fresh_state(), { health = 1000, now = 2000 })
    assert.is_true(st.in_failover, "precondition: we are in failover")
    -- The sidecar reconnected and persisted a snapshot; the sentinel read in
    -- THIS tick is still the stale one, which is exactly the window the old
    -- gate stalled in.
    st = tick(st, { health = 1000, now = 2001, pushed_etag = "e2" })
    assert.are.equal(1, st.applies, "the pushed snapshot must not wait for failover to lift")
    assert.is_false(st.in_failover, "applying a push proves the link is live")
  end)
end)

-- ── (E) the fallback's config is gone ─────────────────────────────────────

describe("#2736 (E) fallback-only config is removed", function()
  it("ANCHOR: the shipped config was actually read (ws heartbeat still there)", function()
    assert.is_not_nil(slurp(CONFIG_PATH):find("heartbeat_interval", 1, true))
  end)

  it("ships no ws.fallback_after option", function()
    -- The section's prose still names the retired key, which is useful; what
    -- must be gone is the shipped `option` line an agent would read.
    assert.is_nil(slurp(CONFIG_PATH):find("option fallback_after", 1, true))
  end)

  it("ships no ws.enabled opt-out (ws is the only transport)", function()
    -- Keeping the toggle after the poll is gone would let an operator strand a
    -- router with no policy transport at all. Scoped to the `config ws` section:
    -- the sni sidecar's own `option enabled` is unrelated and stays.
    local cfg = slurp(CONFIG_PATH)
    local ws_section = cfg:sub(cfg:find("config ws 'ws'", 1, true))
    assert.is_nil(ws_section:find("option enabled", 1, true))
  end)

  it("the agent reads no fallback_after and holds no ws-enabled flag", function()
    -- The prose above each removal still NAMES the retired keys, which is
    -- useful; what must be gone is the code that reads them.
    local src = slurp(AGENT_PATH)
    assert.is_nil(src:find('uci_get_ws("fallback_after"', 1, true))
    assert.is_nil(src:find("ws_fallback_after", 1, true))
    assert.is_nil(src:find('uci_get_ws("enabled"', 1, true))
    assert.is_nil(src:find("ws_enabled", 1, true))
  end)
end)
