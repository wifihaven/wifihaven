-- tick_guard.lua — on_tick liveness accounting and apply priority (#2785).
--
-- #2024 fixed one half of the stall class: with conntrack silent, on_tick was
-- never called at all, so the watcher now multiplexes a wall-clock heartbeat
-- sentinel into the popen stream. That fix cannot see the OTHER half. On
-- 2026-09-13 the heartbeat was arriving on schedule and both processes were
-- RUNNING; the loop simply never got back to read the pipe, because it was
-- blocked inside a step (an unbounded curl — see http_bounds). A pushed
-- snapshot sat persisted-but-unapplied for 4m11s and the agent reported
-- `usage timer: window=558s … on_tick stalled (#2024)`.
--
-- The #2024 usage-window detector fired correctly and is kept. It is a
-- late, indirect signal though: it only speaks once per usage bucket, only when
-- there were counters to fold, and it says nothing about WHICH step blocked.
-- This module adds the direct ones:
--
--   observe()          the gap between consecutive on_tick entries — the loop's
--                      own pulse, independent of the usage timer.
--   step_over_budget() per-step attribution, so the next stall names its cause
--                      instead of needing an SSH into the router.
--   apply_pending()    is a snapshot the sidecar persisted still unapplied?
--   should_defer()     while one is, hold back discretionary network work so
--                      the apply is not queued behind it — but only for a
--                      bounded window, because a router that stops reporting is
--                      invisible, and that was the incident's second symptom.
local M = {}

-- A tick gap past this counts as a stall. A normal heartbeat gap is
-- conntrack_tick_interval (1 s); a legitimate slow tick is an apply that
-- restarts dnsmasq, which the policy_apply_duration_seconds histogram puts at a
-- few seconds. It also has to clear the step budget below, or a step behaving
-- exactly as configured would be reported as a stalled loop. 30 s does both,
-- and is still an order of magnitude below the 251 s the incident produced.
M.DEFAULT_STALL_SECONDS = 30

-- A single in-tick step past this budget gets attributed. Above the normal
-- whole-apply cost so a routine apply does not flag, and above
-- http_bounds.DEFAULT_MAX_SECONDS (10) so a curl call that runs to its own
-- configured timeout is not reported as slow — it is doing what it was told.
-- The bulk blocklist fetch (http_bulk_max_time, 120 s) deliberately CAN exceed
-- this: a two-minute list download really did hold the loop, and it happens
-- only when a list version changed, so it is worth an attribution.
M.DEFAULT_STEP_BUDGET_SECONDS = 15

-- How long discretionary network work may be held back for a pending apply.
M.DEFAULT_DEFER_SECONDS = 60

-- The bounded `step` label enum for agent_slow_step_total. Bounded-cardinality
-- rule (AGENTS.md#instrument-new-functionality): these are code constants, never
-- a per-mac / per-host / per-url value.
M.STEPS = {
  "ws_apply",
  "block_page_token",
  "blocklist_refresh",
  "eb_refresh",
  "usage_report",
  "metrics_push",
}

-- check_bounds(slice, http_max, step_budget, stall) -> ok, why
--   The ordering the four knobs must keep:
--     slice < http_max < step_budget < stall
--   Each rung says "the inner thing is allowed to finish before the outer thing
--   complains about it". Violating it does not break enforcement, it breaks the
--   SIGNAL: a step at its own timeout gets counted as slow, or a slow step gets
--   counted as a stalled loop, and the operator learns to ignore both. The agent
--   calls this at startup and warns loudly rather than silently mis-reporting.
function M.check_bounds(slice, http_max, step_budget, stall)
  local rungs = {
    { "eb_refresh_slice_seconds", slice,        "http_max_time",        http_max },
    { "http_max_time",            http_max,     "tick_step_budget",     step_budget },
    { "tick_step_budget",         step_budget,  "tick_stall_threshold", stall },
  }
  for _, r in ipairs(rungs) do
    local lo, hi = tonumber(r[2]), tonumber(r[4])
    if lo and hi and lo >= hi then
      return false, string.format("%s (%s) must be below %s (%s)", r[1], tostring(lo), r[3], tostring(hi))
    end
  end
  return true, nil
end

function M.new_state()
  return { last_tick = nil, defer_since = nil }
end

-- observe(state, mono, threshold) -> gap, stalled
--   `mono` is CLOCK_MONOTONIC seconds (#336: never the wall clock — an NTP step
--   must not read as a stall). Returns nil gap on the first tick, when there is
--   nothing to measure against. A non-positive delta is treated as no
--   measurement rather than a stall; the monotonic clock should not go
--   backwards, and if it does, inventing a stall from it helps nobody.
function M.observe(state, mono, threshold)
  threshold = tonumber(threshold) or M.DEFAULT_STALL_SECONDS
  local prev = state.last_tick
  state.last_tick = mono
  if prev == nil then return nil, false end
  local gap = mono - prev
  if gap <= 0 then return gap, false end
  return gap, gap > threshold
end

-- step_over_budget(elapsed, budget) -> boolean
--   Tolerant of a nil/garbage elapsed: this is called from inside on_tick,
--   which is not pcall'd, so an arithmetic error here would take the loop down
--   in the name of watching the loop.
function M.step_over_budget(elapsed, budget)
  local e = tonumber(elapsed)
  if not e then return false end
  return e > (tonumber(budget) or M.DEFAULT_STEP_BUDGET_SECONDS)
end

-- Label used for a step name that is not in STEPS, so a typo or a new call site
-- cannot become a free-form label value.
M.OTHER_STEP = "other"

local STEP_SET = {}
for _, s in ipairs(M.STEPS) do STEP_SET[s] = true end

-- Lua 5.1 has no table.pack; this is the idiom, and it keeps nil holes.
-- unpack is a global on 5.1 (the router) and table.unpack on the 5.2+ dev host.
local function pack(...) return { n = select("#", ...), ... } end
local unpack_ = table.unpack or unpack

-- run_step(opts, step, fn, ...) -> fn's return values   (#2796)
--   Times one in-tick step and, past opts.budget, increments
--   agent_slow_step_total{step} and warns, so the next stall names its cause in
--   Grafana. This used to live inline in the agent, where no spec could drive
--   it, and the metric never emitted on prod because the slow work sat just
--   OUTSIDE the timed calls. Keep the heavy part of a step inside `fn`.
--   opts: { budget, now_fn, reg, metrics, log }
--   Returns fn's FULL arity (http_get returns three values).
function M.run_step(opts, step, fn, ...)
  local t0 = opts.now_fn()
  local r = pack(fn(...))
  local elapsed = opts.now_fn() - t0
  if M.step_over_budget(elapsed, opts.budget) then
    local label = STEP_SET[step] and step or M.OTHER_STEP
    opts.metrics.inc_counter(opts.reg, "agent_slow_step_total", { step = label })
    if opts.log then
      opts.log.warn("on_tick: step %q took %.1fs (budget %ss) — it blocked the cooperative loop " ..
                    "for that long (#2785)", tostring(step), elapsed, tostring(opts.budget))
    end
  end
  return unpack_(r, 1, r.n)
end

-- apply_pending(trigger_etag, current_etag) -> boolean
--   `trigger_etag` is what the sidecar last persisted (paths.ws_pending, nil
--   when there is no trigger file); `current_etag` is what the agent has
--   applied. A cold agent (current nil) with a trigger present is pending.
function M.apply_pending(trigger_etag, current_etag)
  if trigger_etag == nil or trigger_etag == "" then return false end
  return trigger_etag ~= current_etag
end

-- should_defer(state, apply_pending, mono, max_seconds) -> boolean
--   True while an apply is pending AND we have not been deferring for longer
--   than `max_seconds`. The cap is the important half: an apply that keeps
--   failing (a bad snapshot, an nft load error) must not silence the metrics
--   push forever, or the fleet loses the very signal that would show it.
function M.should_defer(state, apply_pending, mono, max_seconds)
  if not apply_pending then
    state.defer_since = nil
    return false
  end
  max_seconds = tonumber(max_seconds) or M.DEFAULT_DEFER_SECONDS
  if state.defer_since == nil then
    state.defer_since = mono
    return true
  end
  return (mono - state.defer_since) <= max_seconds
end

return M
