-- metrics_spec.lua — unit tests for the in-process metrics registry (#1206).
--
-- Covers:
--   * cumulative counter accumulation + batch shape (design §3.2)
--   * gauge + histogram (cumulative buckets, sum, count, +Inf overflow)
--   * dnsmasq_restarts_total fires exactly once on a real restart and NOT on
--     a #414 short-circuited (nft-only) apply — via policy.apply's on_apply seam
--   * policy_apply_total result enum (ok / write_failed / nft_failed / smoke_warn)
--   * retain-on-failure: a failed POST never resets the registry; the next
--     batch carries the full cumulative total (the server re-bases)
--
-- The agent's *production* metrics push body is assembled by metrics.build_batch
-- — the SAME function the contract fixture generator calls — so this spec also
-- pins the wire shape that ContractGoldenSpec round-trips against.

local metrics = require("wifihaven.metrics")
local policy  = require("wifihaven.policy")

-- Find a series ({name, labels, value}) in a batch list by name + a single
-- label match (labels are small bounded enums here).
local function find_series(list, name, label_key, label_val)
  for _, s in ipairs(list or {}) do
    if s.name == name then
      if not label_key then return s end
      if s.labels and s.labels[label_key] == label_val then return s end
    end
  end
  return nil
end

local function new_reg()
  return metrics.new({
    router_id     = "11111111-2222-3333-4444-555555555555",
    agent_version = "0.3.1",
    started_at    = "2026-05-30T09:00:00Z",
  })
end

describe("metrics.new + build_batch", function()
  it("carries identity + restart-sentinel fields", function()
    local reg   = new_reg()
    local batch = metrics.build_batch(reg, "2026-05-30T14:01:00Z")
    assert.equal("11111111-2222-3333-4444-555555555555", batch.routerId)
    assert.equal("0.3.1", batch.agentVersion)
    assert.equal("2026-05-30T09:00:00Z", batch.agentStartedAt)
    assert.equal("2026-05-30T14:01:00Z", batch.sampledAt)
  end)

  it("agentStartedAt stays constant across batches (restart sentinel)", function()
    local reg = new_reg()
    local b1  = metrics.build_batch(reg, "2026-05-30T14:01:00Z")
    local b2  = metrics.build_batch(reg, "2026-05-30T14:02:00Z")
    assert.equal(b1.agentStartedAt, b2.agentStartedAt)
    assert.not_equal(b1.sampledAt, b2.sampledAt)
  end)
end)

describe("metrics counters", function()
  it("accumulate cumulatively and split by label", function()
    local reg = new_reg()
    metrics.inc_counter(reg, "dnsmasq_restarts_total", { reason = "boot" })
    metrics.inc_counter(reg, "dnsmasq_restarts_total", { reason = "policy_change" })
    metrics.inc_counter(reg, "dnsmasq_restarts_total", { reason = "policy_change" })

    local batch = metrics.build_batch(reg, "2026-05-30T14:01:00Z")
    local boot  = find_series(batch.counters, "dnsmasq_restarts_total", "reason", "boot")
    local pol   = find_series(batch.counters, "dnsmasq_restarts_total", "reason", "policy_change")
    assert.equal(1, boot.value)
    assert.equal(2, pol.value)
  end)

  it("keep climbing across successive batches (cumulative since start)", function()
    local reg = new_reg()
    metrics.inc_counter(reg, "snapshot_poll_total", { result = "304" })
    local b1 = metrics.build_batch(reg, "2026-05-30T14:01:00Z")
    assert.equal(1, find_series(b1.counters, "snapshot_poll_total", "result", "304").value)
    metrics.inc_counter(reg, "snapshot_poll_total", { result = "304" })
    local b2 = metrics.build_batch(reg, "2026-05-30T14:02:00Z")
    assert.equal(2, find_series(b2.counters, "snapshot_poll_total", "result", "304").value)
  end)

  it("emit a deterministic counter ordering (stable fixtures)", function()
    local reg = new_reg()
    metrics.inc_counter(reg, "snapshot_poll_total", { result = "304" })
    metrics.inc_counter(reg, "dnsmasq_restarts_total", { reason = "boot" })
    metrics.inc_counter(reg, "policy_apply_total", { result = "ok" })
    local b1 = metrics.build_batch(reg, "2026-05-30T14:01:00Z")
    local order1 = {}
    for i, c in ipairs(b1.counters) do
      order1[i] = c.name .. (c.labels.reason or c.labels.result or "")
    end
    -- Rebuild from a fresh registry inserting in a different order.
    local reg2 = new_reg()
    metrics.inc_counter(reg2, "policy_apply_total", { result = "ok" })
    metrics.inc_counter(reg2, "dnsmasq_restarts_total", { reason = "boot" })
    metrics.inc_counter(reg2, "snapshot_poll_total", { result = "304" })
    local b2 = metrics.build_batch(reg2, "2026-05-30T14:01:00Z")
    local order2 = {}
    for i, c in ipairs(b2.counters) do
      order2[i] = c.name .. (c.labels.reason or c.labels.result or "")
    end
    assert.same(order1, order2)
  end)
end)

describe("metrics gauges", function()
  it("hold the last value set (instantaneous)", function()
    local reg = new_reg()
    metrics.set_gauge(reg, "agent_uptime_seconds", {}, 60)
    metrics.set_gauge(reg, "agent_uptime_seconds", {}, 18060)
    local g = find_series(metrics.build_batch(reg, "t").gauges, "agent_uptime_seconds")
    assert.equal(18060, g.value)
  end)

  it("emit agent_version as an info gauge with a bounded version label", function()
    local reg = new_reg()
    metrics.set_gauge(reg, "agent_version", { version = "0.3.1" }, 1)
    local g = find_series(metrics.build_batch(reg, "t").gauges, "agent_version", "version", "0.3.1")
    assert.equal(1, g.value)
  end)
end)

describe("metrics histograms", function()
  it("accumulate cumulative buckets, +Inf overflow, sum and count", function()
    local reg = new_reg()
    metrics.observe(reg, "policy_apply_duration_seconds", {}, 0.02)
    metrics.observe(reg, "policy_apply_duration_seconds", {}, 0.2)
    metrics.observe(reg, "policy_apply_duration_seconds", {}, 7.0)

    local h = find_series(metrics.build_batch(reg, "t").histograms, "policy_apply_duration_seconds")
    assert.equal(3, h.count)
    assert.is_true(math.abs(h.sum - 7.22) < 1e-9)

    -- Buckets must be cumulative, ascending, with a trailing +Inf == count.
    local by_le = {}
    for _, b in ipairs(h.buckets) do by_le[b.le] = b.count end
    assert.equal(0, by_le["0.01"])
    assert.equal(1, by_le["0.05"])
    assert.equal(1, by_le["0.1"])
    assert.equal(2, by_le["0.5"])
    assert.equal(2, by_le["1"])
    assert.equal(2, by_le["5"])
    assert.equal(3, by_le["+Inf"])
    -- +Inf is the last bucket.
    assert.equal("+Inf", h.buckets[#h.buckets].le)
  end)

  it("uses the same boundaries the API server folds against", function()
    -- Server (api/src/metrics/Metrics.scala) folds on 0.01,0.05,0.1,0.5,1,5.
    assert.same({ 0.01, 0.05, 0.1, 0.5, 1.0, 5.0 }, metrics.DURATION_BUCKETS)
  end)
end)

-- ── #1365: label-less series must OMIT `labels`, never emit an empty table ──
--
-- Real luci.jsonc on the router serializes an empty Lua table as a JSON array
-- (`[]`), not an object (`{}`). A series carrying `labels = {}` therefore went
-- on the wire as `"labels":[]`, which the API's `Map[String,String]` decoder
-- rejects — failing the ENTIRE batch (every prod batch was counted in
-- router_metrics_batches_total{status="malformed"} and never re-exposed, so
-- the router-fleet dashboard stayed empty). build_batch must omit `labels`
-- entirely for a label-less series so the decoder defaults it to an empty map.
-- (The lua-cjson test shim hid this: cjson encodes an empty table as `{}`.)
describe("metrics build_batch — empty labels (#1365)", function()
  it("omits `labels` for a label-less gauge (never emits an empty table)", function()
    local reg = new_reg()
    metrics.set_gauge(reg, "agent_uptime_seconds", {}, 18060)
    local g = find_series(metrics.build_batch(reg, "t").gauges, "agent_uptime_seconds")
    assert.is_nil(g.labels)
  end)

  it("omits `labels` for a label-less histogram", function()
    local reg = new_reg()
    metrics.observe(reg, "policy_apply_duration_seconds", {}, 0.02)
    local h = find_series(metrics.build_batch(reg, "t").histograms, "policy_apply_duration_seconds")
    assert.is_nil(h.labels)
  end)

  it("still carries `labels` for a series WITH a bounded enum label", function()
    local reg = new_reg()
    metrics.inc_counter(reg, "snapshot_poll_total", { result = "304" }, 9300)
    local c = find_series(metrics.build_batch(reg, "t").counters, "snapshot_poll_total")
    assert.same({ result = "304" }, c.labels)
  end)
end)

describe("metrics.post — retain on failure (self-heal)", function()
  it("never resets the registry on a failed POST; next batch is the full total", function()
    local reg = new_reg()
    metrics.inc_counter(reg, "policy_apply_total", { result = "ok" })
    metrics.inc_counter(reg, "policy_apply_total", { result = "ok" })

    -- POST fails (500). Counters must be retained.
    local fail_post = function(_url, _body, _hdrs) return 500, "boom", nil end
    local ok = metrics.post("http://api", "tok", reg, "t1", fail_post,
      { warn = function() end, debug = function() end, err = function() end })
    assert.is_false(ok)

    -- More work accumulates on top of the retained total.
    metrics.inc_counter(reg, "policy_apply_total", { result = "ok" })

    -- Next POST succeeds; the body carries the FULL cumulative total (3),
    -- not just the delta since the failed attempt.
    local captured
    local ok_post = function(_url, body, _hdrs)
      captured = body
      return 200, "", nil
    end
    local ok2 = metrics.post("http://api", "tok", reg, "t2", ok_post,
      { warn = function() end, debug = function() end, err = function() end })
    assert.is_true(ok2)
    assert.truthy(captured:find('"value":3') or captured:find('"value": 3'))
  end)

  it("POSTs to /api/router/metrics with a bearer token", function()
    local reg = new_reg()
    metrics.set_gauge(reg, "agent_uptime_seconds", {}, 60)
    local seen = {}
    local post_fn = function(url, _body, hdrs)
      seen.url = url; seen.auth = hdrs["Authorization"]; seen.ct = hdrs["Content-Type"]
      return 200, "", nil
    end
    metrics.post("http://api.example", "rt_secret", reg, "t", post_fn,
      { warn = function() end, debug = function() end, err = function() end })
    assert.equal("http://api.example/api/router/metrics", seen.url)
    assert.equal("Bearer rt_secret", seen.auth)
    assert.equal("application/json", seen.ct)
  end)
end)

-- ── policy.apply on_apply seam (dnsmasq_restarts_total + policy_apply_total) ──
--
-- The motivating metric: dnsmasq_restarts_total must increment at the actual
-- restart call site, exactly once per real restart, and NOT on a #414
-- nft-only short-circuited apply. policy.apply exposes this via an optional
-- opts.on_apply callback so the agent (and these tests) observe the decision
-- without policy.lua depending on the metrics module.

local SNAP_JSON = [[{
  "etag": "sha256:x",
  "generatedAt": "2026-05-08T14:00:00Z",
  "devices": {},
  "profiles": {},
  "blocklists": {}
}]]

local function decode_snap()
  local jsonc = require("luci.jsonc")
  return jsonc.parse(SNAP_JSON)
end

describe("policy.apply on_apply seam (#1206)", function()
  it("reports dnsmasq_restarted=true when the conf changes (real restart)", function()
    local info
    -- read_fn returns nil → on-disk file absent → dnsmasq_changed=true.
    policy.apply(decode_snap(),
      function(_p, _c) return true, nil end,
      function(_cmd) return 0 end,
      nil,
      { read_fn = function(_p) return nil end,
        on_apply = function(i) info = i end })
    assert.truthy(info)
    assert.is_true(info.dnsmasq_restarted)
    assert.equal("ok", info.result)
  end)

  it("reports dnsmasq_restarted=false on a #414 short-circuited (nft-only) apply", function()
    -- Pre-seed read_fn with the exact rendered content so dnsmasq is unchanged.
    local render = require("wifihaven.render")
    local rendered = render.dnsmasq(decode_snap())
    local info
    local restart_issued = false
    policy.apply(decode_snap(),
      function(_p, _c) return true, nil end,
      function(cmd)
        if cmd:find("dnsmasq restart", 1, true) then restart_issued = true end
        return 0
      end,
      nil,
      { read_fn = function(_p) return rendered end,
        on_apply = function(i) info = i end })
    assert.truthy(info)
    assert.is_false(info.dnsmasq_restarted)
    assert.equal("ok", info.result)
    assert.is_false(restart_issued)
  end)

  it("reports result=write_failed when a write fails", function()
    local info
    policy.apply(decode_snap(),
      function(_p, _c) return nil, "io error" end,
      function(_cmd) return 0 end,
      nil,
      { on_apply = function(i) info = i end })
    assert.truthy(info)
    assert.equal("write_failed", info.result)
    assert.is_false(info.dnsmasq_restarted)
  end)

  it("reports result=nft_failed when the nft load exits non-zero", function()
    local info
    policy.apply(decode_snap(),
      function(_p, _c) return true, nil end,
      function(cmd)
        if cmd:find("nft -f", 1, true) then return 1 end
        return 0
      end,
      nil,
      { read_fn = function(_p) return nil end,
        on_apply = function(i) info = i end })
    assert.truthy(info)
    assert.equal("nft_failed", info.result)
  end)

  it("fires on_apply exactly once per apply", function()
    local n = 0
    policy.apply(decode_snap(),
      function(_p, _c) return true, nil end,
      function(_cmd) return 0 end,
      nil,
      { read_fn = function(_p) return nil end,
        on_apply = function(_i) n = n + 1 end })
    assert.equal(1, n)
  end)
end)
