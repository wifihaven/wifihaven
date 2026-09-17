-- tick_guard_spec.lua — on_tick liveness accounting and apply priority (#2785).
--
-- Prod incident 2026-09-13: `wifihaven-ws` persisted two pushed snapshots at
-- 19:09:16 / 19:09:23; the agent did not apply either until 19:13:34 — 4m11s —
-- and then reported `usage timer: window=558s exceeds 2x usage_report_interval
-- (60s) — on_tick stalled (#2024)`. Both processes were RUNNING throughout, so
-- the loop was not dead, it was BLOCKED inside a step.
--
-- #2024's idle heartbeat fixed the other half of this class (conntrack silent
-- => no wake at all). It cannot see this half: the heartbeat sentinel arrives
-- on time, the loop just never gets back to read it. tick_guard is the
-- detector for the gap between consecutive on_tick entries, the per-step budget
-- that names WHICH step blocked, and the bounded deferral that lets a pending
-- apply jump ahead of discretionary network work.

local tick_guard = require("wifihaven.tick_guard")

describe("tick_guard.observe — the gap between consecutive ticks", function()
  it("reports no gap on the very first tick (nothing to compare against)", function()
    local st = tick_guard.new_state()
    local gap, stalled = tick_guard.observe(st, 100, 15)
    assert.is_nil(gap)
    assert.is_false(stalled)
  end)

  it("reports a healthy heartbeat gap as not stalled", function()
    local st = tick_guard.new_state()
    tick_guard.observe(st, 100, 15)
    local gap, stalled = tick_guard.observe(st, 101, 15)
    assert.are.equal(1, gap)
    assert.is_false(stalled)
  end)

  it("flags a gap past the threshold as a stall", function()
    local st = tick_guard.new_state()
    tick_guard.observe(st, 100, 15)
    local gap, stalled = tick_guard.observe(st, 100 + 251, 15)
    assert.are.equal(251, gap)
    assert.is_true(stalled)
  end)

  it("treats a gap exactly at the threshold as healthy (strictly greater stalls)", function()
    local st = tick_guard.new_state()
    tick_guard.observe(st, 100, 15)
    local _, stalled = tick_guard.observe(st, 115, 15)
    assert.is_false(stalled)
  end)

  it("does not flag a BACKWARD monotonic step (defensive; #336 clock lesson)", function()
    local st = tick_guard.new_state()
    tick_guard.observe(st, 100, 15)
    local gap, stalled = tick_guard.observe(st, 90, 15)
    assert.is_false(stalled)
    assert.is_true(gap == nil or gap <= 0)
  end)

  it("keeps detecting after a stall — the anchor advances every tick", function()
    -- LIVENESS ANCHOR for the negative assertions above: prove the detector is
    -- still armed after it fires once, so "not stalled" later means healthy
    -- rather than a detector that latched off.
    local st = tick_guard.new_state()
    tick_guard.observe(st, 0, 15)
    local _, first = tick_guard.observe(st, 300, 15)
    assert.is_true(first)
    local _, quiet = tick_guard.observe(st, 301, 15)
    assert.is_false(quiet)
    local _, again = tick_guard.observe(st, 601, 15)
    assert.is_true(again)
  end)
end)

describe("tick_guard.step_over_budget — which step blocked the loop", function()
  it("is false for a step inside its budget", function()
    assert.is_false(tick_guard.step_over_budget(0.4, 10))
  end)

  it("is true for a step that ran past its budget", function()
    assert.is_true(tick_guard.step_over_budget(300, 10))
  end)

  it("tolerates a nil/garbage elapsed rather than erroring inside on_tick", function()
    assert.is_false(tick_guard.step_over_budget(nil, 10))
  end)
end)

describe("tick_guard.apply_pending — is a persisted push still unapplied?", function()
  it("is false when there is no trigger file", function()
    assert.is_false(tick_guard.apply_pending(nil, "sha256:aaa"))
  end)

  it("is false when the trigger etag is the one already applied", function()
    assert.is_false(tick_guard.apply_pending("sha256:aaa", "sha256:aaa"))
  end)

  it("is true when the sidecar persisted an etag we have not applied", function()
    assert.is_true(tick_guard.apply_pending("sha256:bbb", "sha256:aaa"))
  end)

  it("is true on a cold agent that has applied nothing yet", function()
    assert.is_true(tick_guard.apply_pending("sha256:bbb", nil))
  end)
end)

describe("tick_guard.should_defer — pending apply jumps the queue, but not forever", function()
  it("does not defer when nothing is pending", function()
    local st = tick_guard.new_state()
    assert.is_false(tick_guard.should_defer(st, false, 100, 60))
  end)

  it("defers discretionary network work while an apply is pending", function()
    local st = tick_guard.new_state()
    assert.is_true(tick_guard.should_defer(st, true, 100, 60))
    assert.is_true(tick_guard.should_defer(st, true, 130, 60))
  end)

  it("stops deferring once the cap elapses, so reporting can never be starved", function()
    -- The incident's second symptom was silence: a router that stops reporting
    -- is invisible. A retry loop that never completes must not suppress the
    -- metrics push indefinitely.
    local st = tick_guard.new_state()
    assert.is_true(tick_guard.should_defer(st, true, 100, 60))
    assert.is_false(tick_guard.should_defer(st, true, 161, 60))
  end)

  it("re-arms the deferral window after the apply lands", function()
    local st = tick_guard.new_state()
    assert.is_true(tick_guard.should_defer(st, true, 100, 60))
    assert.is_false(tick_guard.should_defer(st, false, 105, 60))  -- applied
    assert.is_true(tick_guard.should_defer(st, true, 200, 60))    -- next push
  end)
end)

describe("tick_guard.check_bounds — the four knobs form one ordering (#2785 review)", function()
  -- Review of PR #2786 caught the shipped defaults inverting a rung: an
  -- http_max_time of 20s above a tick_step_budget of 10s meant a curl call that
  -- ran to its own configured timeout — behaving exactly as told — would be
  -- reported as having blocked the loop. Instrumentation that cries wolf gets
  -- ignored, which costs more than not having it.
  local http_bounds = require("wifihaven.http_bounds")

  it("accepts the shipped defaults", function()
    local ok, why = tick_guard.check_bounds(
      2, http_bounds.DEFAULT_MAX_SECONDS,
      tick_guard.DEFAULT_STEP_BUDGET_SECONDS, tick_guard.DEFAULT_STALL_SECONDS)
    assert.is_true(ok, tostring(why))
  end)

  it("rejects a step budget below the http timeout it has to contain", function()
    local ok, why = tick_guard.check_bounds(2, 20, 10, 30)
    assert.is_false(ok)
    assert.is_truthy(why:find("tick_step_budget", 1, true))
  end)

  it("rejects a stall threshold below the step budget", function()
    local ok = tick_guard.check_bounds(2, 10, 30, 15)
    assert.is_false(ok)
  end)

  it("rejects a slice budget that is not below the http timeout", function()
    local ok = tick_guard.check_bounds(30, 10, 60, 90)
    assert.is_false(ok)
  end)

  it("tolerates a non-numeric knob rather than erroring at startup", function()
    assert.is_true(tick_guard.check_bounds(nil, "banana", 15, 30))
  end)
end)

describe("tick_guard defaults", function()
  it("sets a stall threshold well above a normal apply but far below the 251s incident", function()
    assert.is_true(tick_guard.DEFAULT_STALL_SECONDS >= 10)
    assert.is_true(tick_guard.DEFAULT_STALL_SECONDS <= 60)
    -- and above the step budget, so a slow step is attributed as a slow step
    -- before the whole tick is called stalled.
    assert.is_true(tick_guard.DEFAULT_STALL_SECONDS > tick_guard.DEFAULT_STEP_BUDGET_SECONDS)
  end)

  it("bounds the deferral so a wedged apply cannot silence reporting", function()
    assert.is_true(tick_guard.DEFAULT_DEFER_SECONDS > 0)
    assert.is_true(tick_guard.DEFAULT_DEFER_SECONDS <= 300)
  end)

  it("names a bounded step enum — the metric label must never be free-form", function()
    local steps = tick_guard.STEPS
    assert.is_table(steps)
    local n = 0
    for _, s in ipairs(steps) do
      assert.is_string(s)
      assert.is_truthy(s:match("^[a-z_]+$"))
      n = n + 1
    end
    assert.is_true(n > 0)
    assert.is_true(n <= 12)
  end)
end)

-- #2796: agent_slow_step_total had never emitted on prod while
-- agent_tick_stall_total counted ~12 stalls/day on the family router. The emit
-- path lived inline in the agent script, where no spec could drive it, so
-- "an induced slow step names itself" was never pinned. run_step is that path,
-- and the agent's wh_timed_step delegates to it.
describe("tick_guard.run_step — slow-step attribution (#2796)", function()
  local metrics = require("wifihaven.metrics")

  -- A clock that returns the given readings in order (start, end).
  local function clock_of(...)
    local readings, i = { ... }, 0
    return function() i = i + 1; return readings[i] end
  end

  local function slow_step_count(reg, step)
    for _, c in pairs(reg.counters) do
      if c.name == "agent_slow_step_total" and c.labels and c.labels.step == step then
        return c.value
      end
    end
    return 0
  end

  local function opts(reg, now_fn, warned)
    return {
      budget  = 15,
      now_fn  = now_fn,
      reg     = reg,
      metrics = metrics,
      log     = { warn = function(fmt, ...) warned[#warned + 1] = string.format(fmt, ...) end },
    }
  end

  it("emits agent_slow_step_total{step} when an induced slow step runs past its budget", function()
    local reg, warned, ran = metrics.new(), {}, false
    local a, b, c = tick_guard.run_step(opts(reg, clock_of(100, 131), warned),
      "usage_report", function(x) ran = true; return x, nil, "third" end, "arg")

    -- liveness: the wrapped step really ran, and its full arity came back
    assert.is_true(ran)
    assert.are.equal("arg", a)
    assert.is_nil(b)
    assert.are.equal("third", c)

    assert.are.equal(1, slow_step_count(reg, "usage_report"))
    assert.are.equal(1, #warned)
    assert.is_truthy(warned[1]:find("usage_report", 1, true))

    -- and it reaches the pushed batch, not just the in-process registry
    local batch = metrics.build_batch(reg, "2026-09-17T00:00:00Z")
    local found = false
    for _, s in ipairs(batch.counters or {}) do
      if s.name == "agent_slow_step_total" then found = true end
    end
    assert.is_true(found)
  end)

  it("does not emit for a step inside its budget, though the same rig emits for a slow one", function()
    local reg, warned, ran = metrics.new(), {}, 0
    tick_guard.run_step(opts(reg, clock_of(100, 110), warned), "eb_refresh",
      function() ran = ran + 1 end)
    assert.are.equal(1, ran)
    assert.are.equal(0, slow_step_count(reg, "eb_refresh"))
    assert.are.equal(0, #warned)

    tick_guard.run_step(opts(reg, clock_of(200, 216), warned), "eb_refresh",
      function() ran = ran + 1 end)
    assert.are.equal(2, ran)
    assert.are.equal(1, slow_step_count(reg, "eb_refresh"))
  end)

  it("folds a step name outside STEPS into one bounded label rather than a free-form one", function()
    local reg = metrics.new()
    tick_guard.run_step(opts(reg, clock_of(0, 60), {}), "per-mac aa:bb:cc", function() end)
    assert.are.equal(0, slow_step_count(reg, "per-mac aa:bb:cc"))
    assert.are.equal(1, slow_step_count(reg, "other"))
  end)
end)
