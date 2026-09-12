-- ws_outbound_spec.lua — unit tests for the #1848 agent-side outbound bridge.
--
-- The main agent hands its outbound usage/events bodies to the ws sidecar over
-- the bounded spool instead of POSTing them itself (the sidecar frames + sends
-- them). #2736 removed the HTTP fall-through this shipped with, so the spool is
-- now the ONLY exit for those two ops in every link state; everything else —
-- the metrics push, the block-page token fetch — still passes straight through
-- to the real http_post. Pure over injected io/now/http_post, so it runs on the
-- dev host as under OpenWrt Lua 5.1.
--
-- The link-state predicates (is_healthy / health_age / stale_after) live here
-- too: they no longer gate the bridge, but the agent's failover edge and the
-- ws_health_age_seconds gauge both read them, so they still need pinning.
-- See test/ws_only_transport_spec.lua for the ws-only behaviour as a whole.

local ws_outbound = require("wifihaven.ws_outbound")

local USAGE_URL   = "http://api/api/router/usage"
local EVENTS_URL  = "http://api/api/router/events"
local METRICS_URL = "http://api/api/router/metrics"

-- a recording http_post stub
local function rec_post()
  local calls = {}
  local fn = function(url, body, _headers)
    calls[#calls + 1] = { url = url, body = body }
    return 200, "ok-http", nil
  end
  return fn, calls
end

describe("ws_outbound.make — usage/events go to the spool", function()
  local function tee(spooled, http, spool_ok)
    return ws_outbound.make({
      http_post    = http,
      spool_append = function(l)
        if spool_ok == false then return nil end
        spooled[#spooled + 1] = l
        return true
      end,
    })
  end

  it("spools a usage frame and does NOT call http_post", function()
    local http, calls = rec_post()
    local spooled = {}
    local status = tee(spooled, http)(USAGE_URL, '{"records":[1]}', {})
    assert.are.equal(200, status)               -- synthetic success (owned by sidecar)
    assert.are.equal(0, #calls)
    assert.are.equal(1, #spooled)
    assert.are.equal('{"op":"usage","payload":{"records":[1]}}', spooled[1])
  end)

  it("spools an events frame", function()
    local http, calls = rec_post()
    local spooled = {}
    tee(spooled, http)(EVENTS_URL, '{"events":[]}', {})
    assert.are.equal(0, #calls)
    assert.are.equal('{"op":"events","payload":{"events":[]}}', spooled[1])
  end)

  it("never bridges a non-usage/events URL (e.g. metrics) — stays on HTTP", function()
    -- Load-bearing, not incidental: the metrics push is what keeps the ws_*
    -- observability series (and alert W15's ws_health_age_seconds) arriving
    -- while the socket is down. This is also this file's proof that http_post
    -- is wired at all, so "http_post was never called" above means something.
    local http, calls = rec_post()
    local spooled = {}
    tee(spooled, http)(METRICS_URL, '{"m":1}', {})
    assert.are.equal(1, #calls)
    assert.are.equal(0, #spooled)
  end)

  it("#2736 a failed spool write reports failure instead of POSTing", function()
    -- Pre-#2736 this fell through to HTTP. With no REST ingest left, a non-2xx
    -- is the honest answer: it hands the datum back to the caller's retry queue
    -- (usage.post and the conntrack event batcher both read status that way).
    -- Returning 200 would silently drop the bucket.
    local http, calls = rec_post()
    local status = tee({}, http, false)(USAGE_URL, '{"records":[]}', {})
    assert.are_not.equal(200, status)
    assert.are.equal(0, #calls)
  end)
end)

-- ── link-state predicates ──────────────────────────────────────────────────

describe("ws_outbound.stale_after — #2736", function()
  -- ws.fallback_after is deleted with the fallback it named. The link-down
  -- judgement is derived from the cadence that actually refreshes the sentinel
  -- (the sidecar's heartbeat pong, #2731), single-sourced here so the agent
  -- cannot pick a different bound.
  it("is a fixed multiple of the heartbeat cadence", function()
    assert.are.equal(ws_outbound.STALE_HEARTBEATS * 30, ws_outbound.stale_after(30))
    assert.are.equal(ws_outbound.STALE_HEARTBEATS * 10, ws_outbound.stale_after(10))
  end)

  it("leaves real headroom over the observed steady state", function()
    -- Prod's sentinel age never left [0, 31] against a 30s heartbeat over the
    -- whole retained window of the gauge. The bound must clear that.
    assert.is_true(ws_outbound.stale_after(30) > 31)
  end)
end)

describe("ws_outbound.is_healthy", function()
  local function at(t)
    return ws_outbound.is_healthy({
      health_read = function() return 1000 end,
      now         = function() return t end,
      stale_after = 90,
    })
  end

  it("is true while the sentinel is within stale_after (boundary inclusive)", function()
    assert.is_true(at(1050))
    assert.is_true(at(1090))
  end)

  it("is false once the sentinel is past stale_after", function()
    assert.is_false(at(1091))
  end)

  it("is false when the sentinel is absent (never connected / disconnected)", function()
    assert.is_false(ws_outbound.is_healthy({
      health_read = function() return nil end,
      now         = function() return 1050 end,
      stale_after = 90,
    }))
  end)
end)

-- ── #2731: the sentinel's age, exposed for the fleet dashboard ──────────────
-- The 9%-suppression bug was invisible from the metrics we shipped: every
-- series said the link was up (ws_state 1, frames flowing earlier in the day)
-- and nothing said the sentinel the gate actually reads had gone stale. It took
-- an SSH to the prod router to see it. ws_health_age_seconds closes that gap,
-- and it is derived from the SAME read is_healthy uses so the two cannot drift.
-- Since #2736 it is also the fleet's primary router-health signal: alert W15
-- fires on it, because a router that loses ws goes quiet on every other channel.
describe("ws_outbound.health_age — #2731", function()
  it("is the seconds since the sidecar last touched the sentinel", function()
    assert.are.equal(100, ws_outbound.health_age({
      health_read = function() return 1000 end,
      now         = function() return 1100 end,
    }))
  end)

  it("is nil when the sentinel is absent (never connected / disconnected)", function()
    -- The agent reports nil as -1, which is the arm W15 exists to catch: a
    -- negative no greater-than threshold could ever see.
    assert.is_nil(ws_outbound.health_age({
      health_read = function() return nil end,
      now         = function() return 1100 end,
    }))
  end)

  it("agrees with is_healthy at the staleness boundary (one freshness rule)", function()
    local read = function() return 1000 end
    local function at(t)
      local o = { health_read = read, stale_after = 90, now = function() return t end }
      return ws_outbound.health_age(o), ws_outbound.is_healthy(o)
    end
    local age_in, healthy_in = at(1090)
    assert.are.equal(90, age_in)
    assert.is_true(healthy_in)
    local age_out, healthy_out = at(1091)
    assert.are.equal(91, age_out)
    assert.is_false(healthy_out)
  end)

  it("never reports an age while the gate is false for a non-age reason", function()
    -- The gauge and the gate must agree on WHY they are off, not just that they
    -- are: whenever health_age reports no age, is_healthy must also be false.
    local o = {
      health_read = function() return nil end,
      now         = function() return 99999 end,
      stale_after = 90,
    }
    assert.is_nil(ws_outbound.health_age(o))
    assert.is_false(ws_outbound.is_healthy(o))
  end)
end)
