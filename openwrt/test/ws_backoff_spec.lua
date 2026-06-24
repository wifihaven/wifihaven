-- ws_backoff_spec.lua — unit tests for the #1848 reconnect backoff policy.
--
-- The wifihaven-ws sidecar reconnects with exponential backoff + jitter
-- (design §5.1: 1s → 2s → 4s → … cap 60s, reset on a successful connect). The
-- math is a pure function so it is tested here on the dev host exactly as it
-- runs under OpenWrt Lua 5.1; the live reconnect loop is validated on plex.lan.

local backoff = require("wifihaven.ws_backoff")

describe("ws_backoff.delay", function()
  -- A deterministic rng so the jittered bound is exact in tests.
  local function rng(v) return function() return v end end

  it("grows exponentially from a 1s base before jitter (rng=0 → low edge)", function()
    -- full-jitter low edge is half the nominal (0.5 + 0.5*rng), rng=0.
    assert.are.equal(0.5, backoff.delay(1, rng(0)))   -- nominal 1
    assert.are.equal(1.0, backoff.delay(2, rng(0)))   -- nominal 2
    assert.are.equal(2.0, backoff.delay(3, rng(0)))   -- nominal 4
    assert.are.equal(4.0, backoff.delay(4, rng(0)))   -- nominal 8
  end)

  it("returns the full nominal at the jitter high edge (rng=1)", function()
    assert.are.equal(1.0, backoff.delay(1, rng(1)))
    assert.are.equal(2.0, backoff.delay(2, rng(1)))
    assert.are.equal(8.0, backoff.delay(4, rng(1)))
  end)

  it("caps the nominal at 60s no matter how high the attempt climbs", function()
    -- attempt 10 nominal would be 2^9=512; capped to 60. High edge = 60.
    assert.are.equal(60.0, backoff.delay(10, rng(1)))
    assert.are.equal(60.0, backoff.delay(100, rng(1)))
    -- low edge after cap is 30.
    assert.are.equal(30.0, backoff.delay(10, rng(0)))
  end)

  it("keeps every jittered value within [0.5*nominal, nominal]", function()
    for attempt = 1, 12 do
      local lo = backoff.delay(attempt, rng(0))
      local hi = backoff.delay(attempt, rng(1))
      local mid = backoff.delay(attempt, rng(0.5))
      assert.is_true(lo <= mid and mid <= hi,
        string.format("attempt=%d lo=%s mid=%s hi=%s", attempt, lo, mid, hi))
      assert.is_true(hi <= 60.0)
      assert.is_true(lo >= 0.5)
    end
  end)

  it("treats attempt<1 as the first attempt (defensive)", function()
    assert.are.equal(1.0, backoff.delay(0, rng(1)))
    assert.are.equal(1.0, backoff.delay(-5, rng(1)))
  end)

  it("defaults to math.random when no rng is injected (stays in bounds)", function()
    local d = backoff.delay(3)
    assert.is_true(d >= 2.0 and d <= 4.0)
  end)
end)
