-- contract_gen.lua — router→API contract fixture generator (#634, follow-up to PR #635).
--
-- This file is the router-side authoritative producer for the
-- shared/contract/router-to-api/*.json fixtures. It calls the SAME production
-- POST-body-builder functions the live agent uses (conntrack.build_event,
-- conntrack.build_first_seen_mac_event, conntrack.build_dhcp_lease_event,
-- conntrack.drain_events payload assembly, usage.build_report) with
-- representative inputs, then serializes the result via luci.jsonc (also the
-- production encoder).
--
-- Why this exists: PR #635 originally generated the router→api fixtures from
-- the API's own codec, which catches API-side decoder drift but not
-- router-producer drift — if the lua agent silently changes its emitted
-- shape, the Scala round-trip still passes. By writing these fixtures from
-- the router's own production code, any agent-side change shows up as a diff
-- in shared/contract/router-to-api/*.json and either causes the Scala
-- round-trip decoder to fail (field rename, type change) or land as a
-- visible PR diff (added/removed field).
--
-- Determinism: luci.jsonc / lua-cjson don't guarantee object key order or
-- stable indentation, so we go through a tiny pretty-printer wrapper (sorted
-- keys, 2-space indent, " : " separator) to match the style of the
-- Scala-generated api-to-router fixtures and keep diffs noise-free. Crucially,
-- the wrapper only owns whitespace and key order — every VALUE (numbers,
-- strings, empty collections) is rendered by luci.jsonc.stringify itself
-- (#1367), so the committed bytes are exactly what the router emits. There are
-- no value fix-ups: an empty table renders as `[]` and a whole-number Double as
-- `N`, just like the real C module.
--
-- Invocation:
--   busted -o TAP test/contract_gen.lua          (run as a no-op spec)
--   lua test/contract_gen.lua [out_dir]          (write fixtures directly)
-- Both modes write the same files. scripts/regen-contract-fixtures.sh runs
-- the second.

local jsonc     = require("luci.jsonc")
local conntrack = require("wifihaven.conntrack")
local usage     = require("wifihaven.usage")
local metrics   = require("wifihaven.metrics")

-- ── Canonical pretty JSON wrapper ─────────────────────────────────────────
-- VALUE semantics (numbers, strings, booleans, empty collections) come from the
-- production encoder via luci.jsonc.stringify — the test shim faithfully
-- emulates the OpenWrt C module (test/shim/luci/jsonc.lua, #1367). This wrapper
-- ONLY owns whitespace and key order: 2-space indentation plus a deterministic
-- key order (builder-supplied __order, else sorted) so the committed fixtures
-- stay byte-stable and diff cleanly under the CI drift guard. It deliberately
-- does NOT decide how a number or an empty table renders — those must reflect
-- exactly what the router puts on the wire, which is why earlier hand fix-ups
-- (an EMPTY_OBJECT `{}` sentinel and a `jnum` N.0 forcer) were removed: they
-- masked real luci.jsonc behavior (empty → `[]`, whole double → `N`) and let
-- the #1365 empty-`labels` bug ship green.

-- Data keys of a table, ignoring the __order meta key we stamp on objects.
local function data_keys(t)
  local keys = {}
  for k in pairs(t) do
    if k ~= "__order" then keys[#keys + 1] = k end
  end
  return keys
end

local encode
encode = function(v, indent)
  indent = indent or ""
  local next_indent = indent .. "  "
  if type(v) ~= "table" then
    -- Scalar: value rendering is the production encoder's, not ours.
    return jsonc.stringify(v)
  end

  local keys = data_keys(v)
  if #keys == 0 then
    -- Empty collection: let the real encoder pick the shape (luci → `[]`).
    return jsonc.stringify({})
  end

  local numeric = true
  for _, k in ipairs(keys) do
    if type(k) ~= "number" then
      numeric = false
      break
    end
  end
  if numeric then
    local parts = {}
    for i = 1, #keys do
      parts[i] = next_indent .. encode(v[i], next_indent)
    end
    return "[\n" .. table.concat(parts, ",\n") .. "\n" .. indent .. "]"
  end

  -- Object. Preserve a builder-supplied __order list when present, otherwise
  -- sort alphabetically. Keys absent from __order are appended in sorted order —
  -- this matters so a producer that adds a new field still emits it (otherwise
  -- the drift wouldn't show up as a fixture diff).
  if v.__order then
    local seen = {}
    local ordered = {}
    for _, k in ipairs(v.__order) do
      if v[k] ~= nil and not seen[k] then
        ordered[#ordered + 1] = k
        seen[k] = true
      end
    end
    local extra = {}
    for _, k in ipairs(keys) do
      if not seen[k] then extra[#extra + 1] = k end
    end
    table.sort(extra, function(a, b) return tostring(a) < tostring(b) end)
    for _, k in ipairs(extra) do ordered[#ordered + 1] = k end
    keys = ordered
  else
    table.sort(keys, function(a, b) return tostring(a) < tostring(b) end)
  end
  local parts = {}
  for _, k in ipairs(keys) do
    parts[#parts + 1] = next_indent .. jsonc.stringify(tostring(k)) .. " : " .. encode(v[k], next_indent)
  end
  return "{\n" .. table.concat(parts, ",\n") .. "\n" .. indent .. "}"
end

local function canonical_pretty(v)
  return encode(v) .. "\n"
end

-- ── Helpers to stamp ordering on production-built event tables ────────────
-- The production builders return lua tables (no field order guarantees from
-- pairs()). We attach __order matching the source order so generated JSON is
-- stable AND mirrors the visual structure the API would emit.

local EVENT_FIELD_ORDER = {
  "type", "mac", "ip", "hostname", "host", "destIp",
  "allowed", "reason", "ts", "eventId",
}

local function stamp(tbl, order)
  tbl.__order = order
  return tbl
end

-- ── Deterministic seams for non-determinism in the production builders ────
-- conntrack.build_event() stamps a UUID via M.gen_event_id() which reads
-- /proc/sys/kernel/random/uuid. Pin it for reproducible fixtures, scoped to
-- the duration of a single fixture build (see with_fixed_event_ids).

local FIXED_EVENT_IDS = {
  "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  "11111111-2222-3333-4444-555555555556",
}

local function with_fixed_event_ids(fn)
  local saved = conntrack.gen_event_id
  local ix    = 0
  conntrack.gen_event_id = function()
    ix = ix + 1
    return FIXED_EVENT_IDS[ix]
        or string.format("99999999-9999-9999-9999-%012d", ix)
  end
  local ok, result = pcall(fn)
  conntrack.gen_event_id = saved
  if not ok then error(result, 0) end
  return result
end

-- ── Build router_events_request.json via the production conntrack builders
-- The agent's drain_events / batcher flushes assemble the POST body as
-- `{ routerId = ..., events = b.events }` (see conntrack.lua:427) so we
-- mirror that shape exactly here.

local function build_router_events_request()
  local events = {}

  -- connection_attempt with FQDN host (common case).
  events[#events + 1] = stamp(conntrack.build_event({
    mac      = "aa:bb:cc:11:22:33",
    hostname = "youtube.com",
    dest_ip  = "142.250.80.46",
    allowed  = false,
    reason   = "blocked",
    ts       = "2026-05-17T14:00:01Z",
  }), EVENT_FIELD_ORDER)

  -- connection_attempt with IPv6 destination (DNS-miss / direct-IP path).
  events[#events + 1] = stamp(conntrack.build_event({
    mac      = "aa:bb:cc:11:22:33",
    hostname = nil,
    dest_ip  = "2607:f8b0:4005:80a::200e",
    allowed  = true,
    reason   = "allow",
    ts       = "2026-05-17T14:00:02Z",
  }), EVENT_FIELD_ORDER)

  -- dhcp_lease — late hostname for previously-seen MAC (#249).
  events[#events + 1] = stamp(conntrack.build_dhcp_lease_event({
    mac      = "aa:bb:cc:11:22:33",
    ip       = "192.168.10.50",
    hostname = "kid-ipad",
    ts       = "2026-05-17T14:00:03Z",
  }), EVENT_FIELD_ORDER)

  -- first_seen_mac — initial flow, hostname may be absent.
  events[#events + 1] = stamp(conntrack.build_first_seen_mac_event({
    mac      = "76:2d:95:47:d1:8e",
    ip       = "192.168.10.51",
    hostname = nil,
    ts       = "2026-05-17T14:00:04Z",
  }), EVENT_FIELD_ORDER)

  return stamp({
    routerId = "11111111-2222-3333-4444-555555555555",
    events   = events,
  }, { "routerId", "events" })
end

-- ── Build usage_report.json via usage.build_report ────────────────────────
-- Mirrors what the agent assembles in usage.lua's build_report path. We
-- inject a hostname-lookup that returns an FQDN for the first counter and
-- nil for the second (so the second falls back to an IP-typed HostId), and
-- a leases table so the first record carries `ip` and the second omits it.

local USAGE_FIELD_ORDER = {
  "mac", "ip", "host", "activeSeconds", "bytesIn", "bytesOut",
}

local function build_usage_report()
  -- Mirror what the agent passes after merging the two per-direction nft
  -- sets (#717): bytes = tx (device→remote), bytes_out = rx (remote→device).
  local counters = {
    { mac = "aa:bb:cc:11:22:33", dst_ip = "151.101.65.69",
      bytes = 1048576, bytes_out = 8388608 },
    { mac = "76:2d:95:47:d1:8e", dst_ip = "192.168.10.99",
      bytes = 4096,    bytes_out = 16384 },
  }
  local lookup = function(ip)
    if ip == "151.101.65.69" then return "khanacademy.org" end
    return nil
  end
  local leases = { ["aa:bb:cc:11:22:33"] = "192.168.10.50" }
  -- usage.lua: active_seconds = sample_seconds (10) × samples, capped at bucket_seconds.
  -- Pick 24 → 240s for the first record; pin the second to 60s with 6 samples.
  local tracker = {
    last = {},
    active_samples = {
      ["aa:bb:cc:11:22:33|151.101.65.69"] = 24,
      ["76:2d:95:47:d1:8e|192.168.10.99"] = 6,
    },
  }
  local report = usage.build_report(
    counters,
    {},  -- nft_sets unused when lookup_hostname returns a hit
    "2026-05-17T13:55:00Z",
    "2026-05-17T14:00:00Z",
    "11111111-2222-3333-4444-555555555555",
    leases,
    lookup,
    tracker,
    10,   -- sample_seconds
    300   -- bucket_seconds
  )
  -- Stamp ordering on each record (production order matches usage.lua's
  -- table literal: mac, host, activeSeconds, bytesIn, bytesOut, ip).
  -- We use the canonical wire order here so the file mirrors what the API
  -- sees on the wire after JSON normalization.
  for _, rec in ipairs(report.records) do
    stamp(rec, USAGE_FIELD_ORDER)
  end
  return stamp(report, { "routerId", "periodStart", "periodEnd", "records" })
end

-- ── Build router_metrics_batch.json via metrics.build_batch ───────────────
-- #1206. The agent's production metrics push body is assembled by
-- metrics.build_batch (the SAME function the live 60 s push timer calls), so
-- this fixture is generated from real production code. We populate a registry
-- with one representative series per §5.1 emitted metric (cumulative counters,
-- the two info/uptime gauges, both duration histograms), then stamp field
-- ordering so the JSON key order is stable across regens. agentStartedAt is
-- pinned (the API's restart sentinel) and routerId matches the other fixtures.

local METRICS_BATCH_ORDER = {
  "routerId", "agentVersion", "agentStartedAt", "sampledAt",
  "counters", "gauges", "histograms",
}
local SERIES_ORDER = { "name", "labels", "value" }
local HISTOGRAM_ORDER = { "name", "labels", "buckets", "sum", "count" }
local BUCKET_ORDER = { "le", "count" }

local function build_metrics_batch()
  local reg = metrics.new({
    router_id     = "11111111-2222-3333-4444-555555555555",
    agent_version = "0.3.1",
    started_at    = "2026-05-30T09:00:00Z",
  })

  -- Counters — cumulative running totals since agent start (§3.2).
  metrics.inc_counter(reg, "dnsmasq_restarts_total", { reason = "boot" }, 1)
  metrics.inc_counter(reg, "dnsmasq_restarts_total", { reason = "policy_change" }, 12)
  metrics.inc_counter(reg, "policy_apply_total", { result = "ok" }, 880)
  metrics.inc_counter(reg, "policy_apply_total", { result = "nft_failed" }, 3)
  metrics.inc_counter(reg, "snapshot_poll_total", { result = "200" }, 140)
  metrics.inc_counter(reg, "snapshot_poll_total", { result = "304" }, 9300)
  metrics.inc_counter(reg, "snapshot_poll_total", { result = "error" }, 6)
  -- #1302 dns_queries_total{result}: dns-tail-sourced, folded by the agent.
  metrics.inc_counter(reg, "dns_queries_total", { result = "resolved" }, 41200)
  metrics.inc_counter(reg, "dns_queries_total", { result = "nxdomain" }, 318)
  metrics.inc_counter(reg, "dns_queries_total", { result = "servfail" }, 7)
  -- #1301 blocklist_fetch_failures_total{status}: bounded status enum.
  metrics.inc_counter(reg, "blocklist_fetch_failures_total", { status = "5xx" }, 4)
  metrics.inc_counter(reg, "blocklist_fetch_failures_total", { status = "error" }, 1)
  -- #1325 enforcement_drops_total{reason}: nftables forward-drop counters,
  -- reason ∈ {whole_mac, host_block, category_block, ip_only}.
  metrics.inc_counter(reg, "enforcement_drops_total", { reason = "whole_mac" }, 1820)
  metrics.inc_counter(reg, "enforcement_drops_total", { reason = "host_block" }, 540)
  metrics.inc_counter(reg, "enforcement_drops_total", { reason = "category_block" }, 12990)
  metrics.inc_counter(reg, "enforcement_drops_total", { reason = "ip_only" }, 63)

  -- Gauges — instantaneous; agent_version is an info gauge (value 1).
  metrics.set_gauge(reg, "agent_uptime_seconds", {}, 18060)
  metrics.set_gauge(reg, "agent_version", { version = "0.3.1" }, 1)

  -- Histograms — observe a few values so the cumulative buckets are non-trivial.
  metrics.observe(reg, "policy_apply_duration_seconds", {}, 0.02)
  metrics.observe(reg, "policy_apply_duration_seconds", {}, 0.2)
  metrics.observe(reg, "policy_apply_duration_seconds", {}, 0.6)
  metrics.observe(reg, "snapshot_poll_duration_seconds", {}, 0.008)
  metrics.observe(reg, "snapshot_poll_duration_seconds", {}, 0.02)
  metrics.observe(reg, "snapshot_poll_duration_seconds", {}, 0.3)

  local batch = metrics.build_batch(reg, "2026-05-30T14:01:00Z")

  -- No value fix-ups. build_batch OMITS `labels` entirely for a label-less
  -- series (#1365) — real luci.jsonc serializes an empty Lua table as a JSON
  -- array (`[]`), which the API's `Map[String,String]` decoder rejects — and
  -- the faithful encoder renders whole-number Doubles as `N` (not `N.0`). The
  -- fixture therefore carries the true wire bytes; the ContractGoldenSpec
  -- metrics case compares decoded VALUES (not re-encoded bytes) so that
  -- omitted-empty-labels and luci's full-precision Double formatting don't
  -- read as a false "defaulted field". We only stamp key order here.
  for _, c in ipairs(batch.counters or {}) do
    stamp(c, SERIES_ORDER)
  end
  for _, g in ipairs(batch.gauges or {}) do
    stamp(g, SERIES_ORDER)
  end
  for _, h in ipairs(batch.histograms or {}) do
    stamp(h, HISTOGRAM_ORDER)
    for _, b in ipairs(h.buckets or {}) do
      stamp(b, BUCKET_ORDER)
    end
  end
  return stamp(batch, METRICS_BATCH_ORDER)
end

-- ── register_router_request.json ─────────────────────────────────────────
-- The register POST is built in openwrt/install.sh via shell `printf`, not
-- lua, so there is no production lua function to call. We mirror the shell
-- format here and document the deviation in shared/contract/README.md.
-- A simple regex-level check on install.sh's printf format string is added
-- in contract_spec.lua so the shell format doesn't drift from this fixture
-- without someone noticing.

local function build_register_request()
  return stamp({
    enrollmentToken = "et_" .. string.rep("a", 32),
    platformVersion = "OpenWrt 23.05.5",
    agentVersion    = "wifihaven-agent 1.2.3",
  }, { "enrollmentToken", "platformVersion", "agentVersion" })
end

-- ── Driver ────────────────────────────────────────────────────────────────

local function locate_contract_dir(start)
  local cur = start or "."
  for _ = 1, 8 do
    local f = io.open(cur .. "/shared/contract/router-to-api/router_events_request.json", "r")
    if f then
      f:close()
      return cur .. "/shared/contract"
    end
    cur = cur .. "/.."
  end
  error("could not locate shared/contract from " .. tostring(start))
end

local M = {}

function M.fixtures()
  return with_fixed_event_ids(function()
    return {
      ["router-to-api/router_events_request.json"]   = canonical_pretty(build_router_events_request()),
      ["router-to-api/usage_report.json"]            = canonical_pretty(build_usage_report()),
      ["router-to-api/router_metrics_batch.json"]    = canonical_pretty(build_metrics_batch()),
      ["router-to-api/register_router_request.json"] = canonical_pretty(build_register_request()),
    }
  end)
end

function M.write(out_dir)
  local root = out_dir or locate_contract_dir()
  local n = 0
  for rel, body in pairs(M.fixtures()) do
    local path = root .. "/" .. rel
    -- Ensure parent dir exists (mkdir -p; lua has no built-in, shell out).
    os.execute("mkdir -p " .. path:gsub("/[^/]+$", ""))
    local f = assert(io.open(path, "w"), "could not open " .. path)
    f:write(body)
    f:close()
    n = n + 1
    print(string.format("  wrote %s", path))
  end
  return n
end

-- Direct invocation: `lua test/contract_gen.lua [out_dir]`
-- Detect "not loaded via require" by checking that arg[0] ends in our name.
if arg and arg[0] and arg[0]:match("contract_gen%.lua$") then
  local out = arg[1]
  local n = M.write(out)
  print(string.format("Wrote %d router→api fixture(s).", n))
end

return M
