-- http_bounds_spec.lua — every agent curl carries a timeout (#2785).
--
-- The 2026-09-13 prod stall: the agent's cooperative on_tick loop runs three
-- synchronous curl calls (the metrics push, the block-page token fetch, the
-- blocklist fetch) and NONE of them passed a timeout flag. curl's built-in
-- default connect timeout is 300 s and there is no default total-transfer cap,
-- so one blackholed request wedges the single-fibered loop for minutes: pushed
-- policy is not applied and usage/events are not reported for the whole time.
-- These specs pin the argument builder that bounds every one of them.

local http_bounds = require("wifihaven.http_bounds")

describe("http_bounds.sanitize_seconds", function()
  it("passes a positive integer through", function()
    assert.are.equal(7, http_bounds.sanitize_seconds("7", 5))
  end)

  it("falls back to the default for a non-numeric UCI value", function()
    assert.are.equal(5, http_bounds.sanitize_seconds("banana", 5))
  end)

  it("falls back to the default for nil / empty", function()
    assert.are.equal(5, http_bounds.sanitize_seconds(nil, 5))
    assert.are.equal(5, http_bounds.sanitize_seconds("", 5))
  end)

  it("rejects zero and negatives — 0 means 'no timeout' to curl, which is the bug", function()
    assert.are.equal(5, http_bounds.sanitize_seconds("0", 5))
    assert.are.equal(5, http_bounds.sanitize_seconds("-1", 5))
  end)

  it("floors a fractional value (curl's flags take whole seconds here)", function()
    assert.are.equal(3, http_bounds.sanitize_seconds("3.9", 5))
  end)
end)

describe("http_bounds.curl_args", function()
  it("emits both --connect-timeout and --max-time", function()
    local args = http_bounds.curl_args(5, 20)
    assert.is_truthy(args:find("--connect-timeout 5", 1, true))
    assert.is_truthy(args:find("--max-time 20", 1, true))
  end)

  it("leads with a space so it can be spliced straight into the curl command", function()
    assert.are.equal(" ", http_bounds.curl_args(5, 20):sub(1, 1))
  end)

  it("never emits a max-time below the connect timeout", function()
    -- A max-time shorter than the connect timeout makes the connect budget
    -- unreachable; clamp rather than shipping a nonsensical pair.
    local args = http_bounds.curl_args(30, 5)
    assert.is_truthy(args:find("--max-time 30", 1, true))
  end)

  it("sanitizes bad inputs rather than emitting an unbounded curl", function()
    local args = http_bounds.curl_args("nonsense", 0)
    assert.is_truthy(args:find("--connect-timeout " .. http_bounds.DEFAULT_CONNECT_SECONDS, 1, true))
    assert.is_truthy(args:find("--max-time " .. http_bounds.DEFAULT_MAX_SECONDS, 1, true))
  end)
end)

describe("http_bounds defaults", function()
  it("bounds a hung request to well under a minute on the interactive path", function()
    -- The acceptance bar in #2785 is "a pushed snapshot applies within seconds".
    -- The worst case is one hung network step ahead of the apply, so the
    -- interactive default has to stay small.
    assert.is_true(http_bounds.DEFAULT_CONNECT_SECONDS <= 10)
    assert.is_true(http_bounds.DEFAULT_MAX_SECONDS <= 30)
  end)

  it("gives bulk bodies (blocklists) a longer, still-finite budget", function()
    assert.is_true(http_bounds.DEFAULT_BULK_MAX_SECONDS > http_bounds.DEFAULT_MAX_SECONDS)
    assert.is_true(http_bounds.DEFAULT_BULK_MAX_SECONDS < 600)
  end)
end)
