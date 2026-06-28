-- ws_poll_dormant_spec.lua — #2037 policy-poll dormancy while ws is healthy.
--
-- With ws enabled the agent runs a persistent push socket AND, until this
-- change, still HTTP-polled `GET /api/router/policy` every 5 s. The design
-- (docs/design/websocket-transport.md §3.1) says the poll timer goes DORMANT on
-- a healthy ws link and resumes only after the link is down past
-- `ws_fallback_after` (default 300 s). The decision is the SAME freshness
-- predicate the outbound tee already uses, now exposed as
-- `ws_outbound.is_healthy` so the two gates cannot drift (single source of
-- truth). These specs pin:
--   (a) ws enabled + fresh health sentinel  ⇒ link healthy ⇒ poll dormant
--                                              (no HTTP policy fetch).
--   (b) ws enabled + stale/absent sentinel past fallback ⇒ poll resumes.
--   (c) a resumed poll that FAILS still drives the #331/#422 failover render
--       path for closed-mode profiles.
--
-- Pure over injected now/health/http — runs on the dev host as under OpenWrt
-- Lua 5.1. The agent's on_tick policy block is a monolith (not require-able), so
-- the behavioural "tick" model below wires the REAL decision functions
-- (`ws_outbound.is_healthy` for the dormancy gate, `policy.failover_transition`
-- for the failover edge) into the same shape on_tick uses.

local ws_outbound = require("wifihaven.ws_outbound")
local policy      = require("policy")

-- ── (a)/(b): the dormancy gate is ws_outbound.is_healthy ──────────────────
describe("ws_outbound.is_healthy — #2037 poll-dormancy gate", function()
  local function gate(opts)
    return ws_outbound.is_healthy({
      enabled        = opts.enabled,
      health_read    = function() return opts.health end,  -- nil ⇒ absent
      now            = function() return opts.now end,
      fallback_after = opts.fallback_after or 300,
    })
  end

  it("(a) enabled + fresh sentinel ⇒ healthy (poll dormant)", function()
    assert.is_true(gate({ enabled = true, health = 1000, now = 1100 })) -- 100s < 300
  end)

  it("treats the exact fallback boundary as still fresh (inclusive)", function()
    assert.is_true(gate({ enabled = true, health = 1000, now = 1300 })) -- 300s == fallback
  end)

  it("(b) enabled + stale sentinel past fallback ⇒ not healthy (poll resumes)", function()
    assert.is_false(gate({ enabled = true, health = 1000, now = 1301 })) -- 301s > 300
  end)

  it("(b) enabled + absent sentinel ⇒ not healthy (poll resumes)", function()
    assert.is_false(gate({ enabled = true, health = nil, now = 1100 }))
  end)

  it("disabled ws ⇒ never healthy even with a fresh sentinel (poll always runs)", function()
    assert.is_false(gate({ enabled = false, health = 1100, now = 1100 }))
  end)
end)

-- ── Behavioural tick model: dormancy + failover preservation ──────────────
-- Mirrors the agent on_tick policy block: when the ws link is healthy we SKIP
-- the HTTP fetch; otherwise we fetch and, on a failed fetch, run the real
-- failover transition. Uses the REAL is_healthy / failover_transition.
local function poll_tick(state, deps)
  if ws_outbound.is_healthy(deps.ws) then
    state.skipped = (state.skipped or 0) + 1
    return  -- dormant: no HTTP policy fetch
  end
  local code = deps.http_get()           -- recorded fake; 200/304 ok, else fail
  state.fetched = (state.fetched or 0) + 1
  local fetch_ok = (code == 200 or code == 304)
  local _, _, new_in_failover = policy.failover_transition(state.in_failover, fetch_ok)
  state.in_failover = new_in_failover
end

describe("on_tick policy gate — #2037 dormancy + #331/#422 failover", function()
  local function deps(opts)
    local fetches = { n = 0 }
    return {
      fetches = fetches,
      ws = {
        enabled        = opts.enabled,
        health_read    = function() return opts.health end,
        now            = function() return opts.now end,
        fallback_after = 300,
      },
      http_get = function() fetches.n = fetches.n + 1; return opts.code or 200 end,
    }
  end

  it("(a) healthy link ⇒ no HTTP policy fetch, no failover", function()
    local d = deps({ enabled = true, health = 1000, now = 1100 })
    local st = { in_failover = false }
    poll_tick(st, d)
    assert.are.equal(0, d.fetches.n)
    assert.are.equal(1, st.skipped)
    assert.is_false(st.in_failover)
  end)

  it("(b) stale link ⇒ HTTP policy fetch resumes", function()
    local d = deps({ enabled = true, health = 1000, now = 2000, code = 200 })
    local st = { in_failover = false }
    poll_tick(st, d)
    assert.are.equal(1, d.fetches.n)
    assert.is_false(st.in_failover)
  end)

  it("(c) resumed poll that FAILS still trips failover render", function()
    local d = deps({ enabled = true, health = 1000, now = 2000, code = 0 }) -- stale + curl error
    local st = { in_failover = false }
    poll_tick(st, d)
    assert.are.equal(1, d.fetches.n)
    assert.is_true(st.in_failover)  -- failover chain applied for closed-mode profiles
  end)

  it("ws disabled ⇒ always polls (unchanged behaviour)", function()
    local d = deps({ enabled = false, health = 9999, now = 9999, code = 304 })
    local st = { in_failover = false }
    poll_tick(st, d)
    assert.are.equal(1, d.fetches.n)
  end)
end)
