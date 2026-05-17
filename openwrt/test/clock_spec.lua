-- Tests for openwrt/files/usr/lib/lua/wifihaven/clock.lua
-- Run with: cd openwrt && busted test/clock_spec.lua
--
-- The module picks its backend at require time, so we don't try to test the
-- branch selection in isolation here — we just verify that whichever backend
-- was selected returns a monotonically non-decreasing reading and that the
-- /proc/uptime fallback parses correctly when forced.

describe("clock module", function()

  it("monotonic_seconds returns a positive number", function()
    package.loaded["clock"] = nil
    package.loaded["wifihaven.clock"] = nil
    local clock = require("clock")
    local t = clock.monotonic_seconds()
    assert.is_number(t)
    assert.is_true(t > 0)
  end)

  it("two successive readings are non-decreasing", function()
    package.loaded["clock"] = nil
    local clock = require("clock")
    local a = clock.monotonic_seconds()
    local b = clock.monotonic_seconds()
    assert.is_true(b >= a, "expected b (" .. b .. ") >= a (" .. a .. ")")
  end)

  it("is independent of os.time() — wall-clock jumps don't move it", function()
    package.loaded["clock"] = nil
    package.loaded["wifihaven.clock"] = nil
    local clock = require("clock")
    -- Skip on systems where no real monotonic source is available (e.g.
    -- macOS dev box without luaposix and without /proc/uptime); the module
    -- explicitly falls back to os.time() as a last resort and this property
    -- can't hold. CI runs on Linux where /proc/uptime is always present.
    local uptime_available = io.open("/proc/uptime", "r")
    if uptime_available then uptime_available:close() end
    if clock._backend ~= "posix" and not uptime_available then
      return  -- environment lacks a monotonic source; nothing to test
    end

    local original_time = os.time
    local fake_now = 1700000000
    _G.os.time = function() return fake_now end

    local a = clock.monotonic_seconds()
    fake_now = fake_now - 1000  -- simulate backward wall-clock jump
    local b = clock.monotonic_seconds()
    _G.os.time = original_time

    -- monotonic source must not have moved backward with the wall clock
    assert.is_true(b >= a - 0.5,
      "monotonic reading dropped from " .. a .. " to " .. b ..
      " when os.time() was rolled back")
  end)
end)

-- Force the /proc/uptime fallback by stubbing require("posix.time") to fail,
-- then reloading the module. Verifies the BusyBox-friendly path parses the
-- first float from /proc/uptime correctly.
describe("clock /proc/uptime fallback", function()
  it("returns the uptime float from /proc/uptime", function()
    local original_require = _G.require
    -- Make posix.time look unavailable so the module picks the fallback.
    _G.require = function(name)
      if name == "posix.time" then
        error("simulated missing module: posix.time")
      end
      return original_require(name)
    end
    -- Drop the cached module so the backend branch re-evaluates.
    package.loaded["clock"] = nil
    package.loaded["wifihaven.clock"] = nil
    local ok, clock = pcall(original_require, "clock")
    _G.require = original_require
    assert.is_true(ok, "clock module failed to load with posix.time absent")
    assert.equal("uptime", clock._backend)

    local original_open = io.open
    _G.io.open = function(path, mode)
      if path == "/proc/uptime" then
        local s = "12345.67 99999.99\n"
        local pos = 1
        return {
          read = function(self, fmt)
            if pos > #s then return nil end
            local out = s
            pos = #s + 1
            return out:gsub("\n$", "")
          end,
          close = function() end,
        }
      end
      return original_open(path, mode)
    end
    local t = clock.monotonic_seconds()
    _G.io.open = original_open
    assert.equal(12345.67, t)
  end)
end)

-- Regression test for #336: the on_tick scheduler arithmetic
-- `(now - last_run) >= interval` must fire on monotonic cadence regardless
-- of wall-clock jumps. The agent itself is a daemon script we can't import
-- directly, so this verifies the underlying invariant the agent relies on.
describe("scheduler arithmetic with monotonic clock (#336)", function()
  local function should_fire(now, last_run, interval)
    return (now - last_run) >= interval
  end

  it("forward-jump-then-back regression: monotonic timer fires on cadence", function()
    -- Mirror the issue body's scenario but on a monotonic clock.
    -- Agent startup sets last_run = mono (initial poll has just happened).
    -- Subsequent polls must fire each time mono advances by `interval`,
    -- independent of any wall-clock churn in between.
    local interval = 60
    local mono = 100      -- arbitrary monotonic origin
    local last_run = mono -- agent just polled at startup

    -- Immediately after startup, no fire.
    assert.is_false(should_fire(mono, last_run, interval))

    -- 59s elapsed monotonically — still not time.
    mono = mono + 59
    assert.is_false(should_fire(mono, last_run, interval))

    -- 60s elapsed monotonically — fires. During this stretch, wall-clock
    -- might have jumped forward 900s and then snapped back; neither event
    -- touches `mono`, so the fire decision is unchanged.
    mono = mono + 1
    assert.is_true(should_fire(mono, last_run, interval),
      "second poll must fire on monotonic cadence regardless of wall-clock churn")
    last_run = mono

    -- next poll lands 60s later, on the dot.
    mono = mono + 60
    assert.is_true(should_fire(mono, last_run, interval))
  end)

  it("forward wall-clock jump on monotonic clock does NOT double-fire", function()
    local interval = 60
    local mono = 0
    local last_run = 0

    -- Pretend wall-clock jumped forward 900s; since we track monotonic,
    -- only 1s actually passed.
    mono = mono + 1
    assert.is_false(should_fire(mono, last_run, interval),
      "scheduler must not fire just because wall-clock moved — monotonic only +1s")
  end)
end)

describe("policy.poll_age_seconds clamp (#336 defensive bandage)", function()
  it("clamps to 0 when wall-clock jumped backward past last_successful_poll_ts", function()
    package.loaded["policy"] = nil
    package.loaded["wifihaven.policy"] = nil
    local policy = require("policy")
    policy.reset_poll_state()
    policy.mark_poll_success(1000)
    -- now < last_successful_poll_ts can happen after an NTP correction
    assert.equal(0, policy.poll_age_seconds(900))
    -- normal case still works
    assert.equal(50, policy.poll_age_seconds(1050))
    policy.reset_poll_state()
  end)
end)

describe("usage retry queue with monotonic now (#336)", function()
  -- The retry queue is internally consistent: it stores `next_attempt_at`
  -- against whatever `now` value the caller passes, and `drain` compares
  -- against the same source. As long as the agent always passes monotonic
  -- now, the queue is jump-safe. Verify this property end-to-end.
  it("backoff fires on monotonic cadence even after wall-clock would have stalled", function()
    package.loaded["usage"] = nil
    package.loaded["wifihaven.usage"] = nil
    local usage = require("usage")
    local q = usage.new_queue()
    local mono = 1000
    local rng = function() return 0.5 end  -- deterministic jitter

    -- enqueue a failed bucket at mono=1000
    local report = { periodEnd = "2025-01-01T00:00:00Z", records = { { foo = 1 } } }
    local failing_post = function() return 500, "oops", nil end
    local ok = usage.post_with_retry(
      "http://api", "tok", report, failing_post, q, mono, rng, nil)
    assert.is_false(ok)
    assert.equal(1, #q.buckets)
    local scheduled = q.buckets[1].next_attempt_at
    assert.is_true(scheduled > mono,
      "next_attempt_at must be in the (monotonic) future")

    -- Pretend wall-clock jumped backward 10000s; monotonic only advanced
    -- to `scheduled`. The drain compares against monotonic now, so the
    -- bucket should fire.
    local drained = false
    local good_post = function() drained = true; return 200, "ok", nil end
    usage.drain("http://api", "tok", q, good_post, scheduled, rng, nil)
    assert.is_true(drained,
      "drain must fire when monotonic now >= scheduled, regardless of wall-clock")
    assert.equal(0, #q.buckets)
  end)
end)
