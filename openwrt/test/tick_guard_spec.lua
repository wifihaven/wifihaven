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

describe("tick_guard defaults", function()
  it("sets a stall threshold well above a normal apply but far below the 251s incident", function()
    assert.is_true(tick_guard.DEFAULT_STALL_SECONDS >= 10)
    assert.is_true(tick_guard.DEFAULT_STALL_SECONDS <= 60)
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
