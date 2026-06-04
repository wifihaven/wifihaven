-- metrics.lua — in-process metric registry + push builder for the agent (#1206).
--
-- Design: docs/design/metrics-observability.md §3.2 (wire shape), §3.3
-- (cadence / retain-on-failure), §5.1 (metric catalogue).
--
-- The agent keeps cumulative running totals since process start and ships them
-- to `POST /api/router/metrics` on a dedicated 60 s timer (see
-- wifihaven-agent). Counters are NEVER reset on the agent — the server
-- re-bases off `agentStartedAt` (the restart sentinel) and stores the delta,
-- so a failed POST self-heals on the next success with no double-count and no
-- data loss. That makes the agent side strictly simpler than the usage path's
-- reset-on-ack model: build a batch, POST it, keep the registry as-is.
--
-- Metric NAMES and bounded LABEL keys here must match the API's allowlist
-- (api/src/metrics/Metrics.scala `MetricGuard.Allowed`) exactly — anything
-- off-allowlist is silently dropped server-side into metrics_rejected_total.
-- `router_id` is attached by the server, so series here carry only their own
-- bounded enum label (reason / result / status / version).
--
-- Public API:
--   metrics.new({ router_id, agent_version, started_at, buckets? }) → reg
--   metrics.inc_counter(reg, name, labels, by?)         -- by defaults to 1
--   metrics.set_gauge(reg, name, labels, value)
--   metrics.observe(reg, name, labels, value)           -- histogram observation
--   metrics.build_batch(reg, sampled_at) → batch table  -- §3.2 shape
--   metrics.post(api_url, token, reg, sampled_at, post_fn, log) → bool
--
-- build_batch is the single production builder; the contract fixture generator
-- (openwrt/test/contract_gen.lua) calls it too so the golden fixture is
-- generated from real production code, never a test-only mirror.

local M = {}

-- Histogram boundaries. MUST match the API server's
-- PolicyApplyDurationBoundaries / SnapshotPollDurationBoundaries
-- (api/src/metrics/Metrics.scala) so the server folds our cumulative bucket
-- counts back into the matching registry buckets.
M.DURATION_BUCKETS = { 0.01, 0.05, 0.1, 0.5, 1.0, 5.0 }

local function default_log()
  local ok, l = pcall(require, "wifihaven.log")
  if ok then return l end
  return {
    info  = function() end,
    err   = function() end,
    warn  = function() end,
    debug = function() end,
  }
end

-- Stable per-series identity: name + sorted "k=v" label pairs. Used both to
-- key the registry maps and to give build_batch a deterministic array order
-- (so the contract fixture is byte-stable across regens).
local function series_key(name, labels)
  local parts = {}
  for k, v in pairs(labels or {}) do
    parts[#parts + 1] = tostring(k) .. "=" .. tostring(v)
  end
  table.sort(parts)
  return name .. "{" .. table.concat(parts, ",") .. "}"
end

-- Copy labels so the caller can reuse its table without aliasing into the
-- registry (and so build_batch can't be mutated by a later inc with the same
-- table reference).
local function copy_labels(labels)
  local out = {}
  for k, v in pairs(labels or {}) do out[k] = v end
  return out
end

-- Format a bucket upper bound to a stable string the server's leValue()
-- parses: integral bounds drop the ".0" (1.0 → "1"), fractional bounds keep
-- their minimal decimal form (0.05 → "0.05").
local function fmt_le(b)
  return string.format("%.10g", b)
end

function M.new(opts)
  opts = opts or {}
  return {
    router_id     = opts.router_id,
    agent_version = opts.agent_version or "",
    started_at    = opts.started_at,
    buckets       = opts.buckets or M.DURATION_BUCKETS,
    counters      = {}, -- key -> { name, labels, value }
    gauges        = {}, -- key -> { name, labels, value }
    histos        = {}, -- key -> { name, labels, counts = {bound -> n}, sum, count }
  }
end

function M.inc_counter(reg, name, labels, by)
  by = by or 1
  local key = series_key(name, labels)
  local c   = reg.counters[key]
  if not c then
    c = { name = name, labels = copy_labels(labels), value = 0 }
    reg.counters[key] = c
  end
  c.value = c.value + by
end

function M.set_gauge(reg, name, labels, value)
  local key = series_key(name, labels)
  reg.gauges[key] = { name = name, labels = copy_labels(labels), value = value }
end

function M.observe(reg, name, labels, value)
  local key = series_key(name, labels)
  local h   = reg.histos[key]
  if not h then
    h = { name = name, labels = copy_labels(labels), counts = {}, sum = 0, count = 0 }
    for _, b in ipairs(reg.buckets) do h.counts[b] = 0 end
    reg.histos[key] = h
  end
  h.sum   = h.sum + value
  h.count = h.count + 1
  -- Cumulative buckets: an observation lands in every bucket whose upper
  -- bound is ≥ the value. The +Inf overflow is derived from `count` at build.
  for _, b in ipairs(reg.buckets) do
    if value <= b then h.counts[b] = h.counts[b] + 1 end
  end
end

-- Collect a name->series map into a list ordered by series_key, so the wire
-- array order is deterministic (stable contract fixtures + readable diffs).
local function ordered_list(map)
  local keys = {}
  for k in pairs(map) do keys[#keys + 1] = k end
  table.sort(keys)
  local out = {}
  for _, k in ipairs(keys) do out[#out + 1] = map[k] end
  return out
end

-- Whether a labels table has any entries. An empty table is the dangerous
-- case: real luci.jsonc on the router serializes an empty Lua table as a JSON
-- *array* (`[]`), not an object (`{}`), so emitting `labels = {}` produces
-- `"labels":[]` on the wire — which the API's `Map[String,String]` decoder
-- rejects, failing the WHOLE batch (router_metrics_batches_total{status=
-- "malformed"}). We therefore OMIT `labels` entirely for a label-less series
-- and let the API decoder fall back to its `Map.empty` default (#1365). The
-- lua-cjson test shim masked this: cjson encodes an empty table as `{}`, so
-- the failure only ever appeared against real luci.jsonc in production.
local function attach_labels(series, labels)
  if labels and next(labels) ~= nil then series.labels = labels end
  return series
end

-- Build the §3.2 push body from the registry. Pure serializer of current
-- registry state + the caller-supplied sampledAt — no clock reads, so the
-- contract generator can drive it deterministically. Empty series lists are
-- omitted entirely (the decoder defaults them to empty), and so is an empty
-- `labels` map (see attach_labels above) — never emit an empty Lua table,
-- since luci.jsonc renders it as `[]` and the decoder then rejects the batch.
function M.build_batch(reg, sampled_at)
  local batch = {
    routerId       = reg.router_id,
    agentVersion   = reg.agent_version,
    agentStartedAt = reg.started_at,
    sampledAt      = sampled_at,
  }

  local counters = {}
  for _, c in ipairs(ordered_list(reg.counters)) do
    counters[#counters + 1] = attach_labels({ name = c.name, value = c.value }, c.labels)
  end
  if #counters > 0 then batch.counters = counters end

  local gauges = {}
  for _, g in ipairs(ordered_list(reg.gauges)) do
    gauges[#gauges + 1] = attach_labels({ name = g.name, value = g.value }, g.labels)
  end
  if #gauges > 0 then batch.gauges = gauges end

  local histograms = {}
  for _, h in ipairs(ordered_list(reg.histos)) do
    local buckets = {}
    for _, b in ipairs(reg.buckets) do
      buckets[#buckets + 1] = { le = fmt_le(b), count = h.counts[b] or 0 }
    end
    -- +Inf overflow == total observation count.
    buckets[#buckets + 1] = { le = "+Inf", count = h.count }
    histograms[#histograms + 1] = attach_labels({
      name    = h.name,
      buckets = buckets,
      sum     = h.sum,
      count   = h.count,
    }, h.labels)
  end
  if #histograms > 0 then batch.histograms = histograms end

  return batch
end

-- POST the current batch. Best-effort: on failure we log and return false but
-- DO NOT touch the registry — the cumulative counters are retained and the
-- next successful push carries the full total (the server re-bases). No retry
-- queue (unlike usage): a missed observability batch self-heals on its own.
function M.post(api_url, token, reg, sampled_at, post_fn, log)
  log = log or default_log()
  local jsonc = require("luci.jsonc")
  local batch = M.build_batch(reg, sampled_at)
  local body  = jsonc.stringify(batch)
  local url   = api_url .. "/api/router/metrics"
  local hdrs  = {
    ["Authorization"] = "Bearer " .. token,
    ["Content-Type"]  = "application/json",
  }

  log.debug("metrics.post: POST url=%s counters=%d gauges=%d histograms=%d",
            url,
            batch.counters and #batch.counters or 0,
            batch.gauges and #batch.gauges or 0,
            batch.histograms and #batch.histograms or 0)
  local status, resp_body, err = post_fn(url, body, hdrs)
  if status and status >= 200 and status < 300 then
    log.debug("metrics.post: success status=%d", status)
    return true
  end
  local body_str = resp_body and tostring(resp_body) or ""
  if #body_str > 200 then body_str = body_str:sub(1, 200) .. "...(truncated)" end
  log.warn("metrics.post: POST failed (status=%s body=%q) err=%s — retaining cumulative counters",
           tostring(status), body_str, tostring(err))
  return false
end

return M
