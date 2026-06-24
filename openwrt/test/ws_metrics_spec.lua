-- ws_metrics_spec.lua — unit tests for the #1848 ws sidecar metrics IPC.
--
-- The wifihaven-ws sidecar has no metrics registry of its own (like the dns/sni
-- tails), so it keeps a cumulative in-memory tally and serialises it to a tmpfs
-- file; the main agent parses + folds the deltas into the existing
-- /api/router/metrics push (design §7). serialize/parse are a symmetric pure
-- pair so the writer (sidecar) and reader (agent fold) can never drift.

local ws_metrics = require("wifihaven.ws_metrics")

describe("ws_metrics tally", function()
  it("accumulates labelled counters and a gauge", function()
    local s = ws_metrics.new()
    ws_metrics.inc(s, "ws_connect_total", "ok")
    ws_metrics.inc(s, "ws_connect_total", "ok")
    ws_metrics.inc(s, "ws_connect_total", "auth_fail")
    ws_metrics.inc(s, "ws_frames_sent_total", "usage", 3)
    ws_metrics.set(s, "ws_state", 1)

    assert.are.equal(2, s.counters.ws_connect_total.ok)
    assert.are.equal(1, s.counters.ws_connect_total.auth_fail)
    assert.are.equal(3, s.counters.ws_frames_sent_total.usage)
    assert.are.equal(1, s.gauges.ws_state)
  end)

  it("round-trips through serialize/parse", function()
    local s = ws_metrics.new()
    ws_metrics.inc(s, "ws_connect_total", "ok", 5)
    ws_metrics.inc(s, "ws_fallback_total", "to_http")
    ws_metrics.inc(s, "ws_frames_recv_total", "policy", 2)
    ws_metrics.set(s, "ws_state", 0)

    local parsed = ws_metrics.parse(ws_metrics.serialize(s))
    assert.are.equal(5, parsed.counters.ws_connect_total.ok)
    assert.are.equal(1, parsed.counters.ws_fallback_total.to_http)
    assert.are.equal(2, parsed.counters.ws_frames_recv_total.policy)
    assert.are.equal(0, parsed.gauges.ws_state)
  end)

  it("parses an empty/missing tally as empty tables", function()
    local parsed = ws_metrics.parse("")
    assert.are.same({}, parsed.counters)
    assert.are.same({}, parsed.gauges)
    local p2 = ws_metrics.parse(nil)
    assert.are.same({}, p2.counters)
    assert.are.same({}, p2.gauges)
  end)

  it("ignores malformed lines rather than erroring", function()
    local parsed = ws_metrics.parse("garbage line\nws_state\t1\n\n")
    assert.are.equal(1, parsed.gauges.ws_state)
  end)

  it("flush writes a parseable file via the injected opener", function()
    local store = {}
    local open_fn = function(path, mode)
      local h = {}
      if (mode or "r"):match("r") then
        if store[path] == nil then return nil end
        local data = store[path]
        function h:read(_) return data end
      else
        store[path] = ""
        function h:write(x) store[path] = store[path] .. x end
      end
      function h:close() return true end
      return h
    end
    local s = ws_metrics.new()
    ws_metrics.inc(s, "ws_connect_total", "ok")
    ws_metrics.flush(s, "/tmp/m", open_fn)
    local parsed = ws_metrics.parse(store["/tmp/m"])
    assert.are.equal(1, parsed.counters.ws_connect_total.ok)
  end)
end)
