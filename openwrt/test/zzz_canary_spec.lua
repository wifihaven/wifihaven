-- TEMPORARY canary for #1877 — REMOVED in the green commit.
-- A static-list run_tests.sh never runs this file, so the coverage guard
-- (spec_coverage_spec.test.sh) fails on it; once discovery globs test/*_spec.lua
-- the canary runs automatically and the guard passes. It must NOT ship.
describe("zzz_canary (#1877 discovery proof)", function()
  it("runs only when run_tests.sh auto-discovers test/*_spec.lua", function()
    assert.is_true(true)
  end)
end)
