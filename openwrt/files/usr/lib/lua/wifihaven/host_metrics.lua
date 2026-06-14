-- host_metrics.lua — collect native OpenWRT OS-level metrics (#1718).
--
-- Reads the standard kernel surfaces — /proc/loadavg, /proc/stat,
-- /proc/meminfo, /proc/sys/net/netfilter/nf_conntrack_count,
-- /sys/class/net/<iface>/statistics/{rx,tx}_bytes — and the `iw dev <iface>
-- station dump` output, and folds them into the existing metrics registry
-- under the names the API allowlisted in #1718 PR 1:
--
--   gauges
--     router_host_load_m1 / _m5 / _m15
--     router_host_cpu_pct                 (delta-derived; needs two samples)
--     router_host_mem_total_kb / _free_kb / _buffers_kb / _cached_kb
--     router_host_conntrack_count
--     router_host_wifi_clients{iface, ssid}
--   counters (cumulative, fold-external; server re-bases on reset)
--     router_host_iface_rx_bytes_total{iface}
--     router_host_iface_tx_bytes_total{iface}
--
-- All I/O is injected via the `deps` table so the spec drives this with
-- fixture strings and the agent driver wires in the real read / exec
-- functions. Missing sources are silently skipped (a router without
-- nf_conntrack loaded just omits that gauge) — host_metrics never panics on
-- a missing /proc node.
--
-- Cardinality-firewall reminder (#1718, AGENTS.md §instrument-new-functionality):
-- `iface` and `ssid` are bounded by router hardware/config (handful per
-- device). The per-MAC wireless client list is explicitly NOT a label — the
-- gauge ships an aggregate count per (iface, ssid) only.

local metrics = require("wifihaven.metrics")

local M = {}

-- ── /proc/loadavg ──────────────────────────────────────────────────────────
local function emit_loadavg(reg, body)
  if not body then return end
  local m1, m5, m15 = body:match("([%d%.]+)%s+([%d%.]+)%s+([%d%.]+)")
  if not m1 then return end
  metrics.set_gauge(reg, "router_host_load_m1", {}, tonumber(m1))
  metrics.set_gauge(reg, "router_host_load_m5", {}, tonumber(m5))
  metrics.set_gauge(reg, "router_host_load_m15", {}, tonumber(m15))
end

-- ── /proc/stat → CPU% (delta between two snapshots) ────────────────────────
-- Parses the aggregate `cpu` line (sum of all cores). Returns nil if the
-- file is missing or malformed.
--
-- Lua's standard pattern matcher doesn't accept '+' or alternations, so we
-- iterate per-field and tolerate a variable number of trailing columns
-- (Linux has added new fields over time: irq, softirq, steal, guest,
-- guest_nice). All we need is `total = sum(all fields)` and `idle = field 4`.
local function parse_cpu_stat(body)
  if not body then return nil end
  local line = body:match("cpu%s+([^\n]+)")
  if not line then return nil end
  local fields = {}
  for n in line:gmatch("(%d+)") do fields[#fields + 1] = tonumber(n) end
  if #fields < 4 then return nil end
  local total = 0
  for _, v in ipairs(fields) do total = total + v end
  return { total = total, idle = fields[4] }
end

local function emit_cpu_pct(reg, body, state)
  if not state then return end
  local cur = parse_cpu_stat(body)
  if not cur then return end
  local prev = state.prev
  state.prev = cur
  if not prev then return end -- first call: no delta yet
  local total_delta = cur.total - prev.total
  local idle_delta  = cur.idle - prev.idle
  if total_delta <= 0 then return end          -- counter reset or no progress
  local busy_delta  = total_delta - idle_delta
  if busy_delta < 0 then busy_delta = 0 end
  local pct = (busy_delta / total_delta) * 100
  metrics.set_gauge(reg, "router_host_cpu_pct", {}, pct)
end

-- ── /proc/meminfo ──────────────────────────────────────────────────────────
local function emit_meminfo(reg, body)
  if not body then return end
  local fields = {
    { gauge = "router_host_mem_total_kb",   key = "MemTotal" },
    { gauge = "router_host_mem_free_kb",    key = "MemFree" },
    { gauge = "router_host_mem_buffers_kb", key = "Buffers" },
    { gauge = "router_host_mem_cached_kb",  key = "Cached" },
  }
  for _, f in ipairs(fields) do
    local v = body:match(f.key .. ":%s*(%d+)%s*kB")
    if v then metrics.set_gauge(reg, f.gauge, {}, tonumber(v)) end
  end
end

-- ── /proc/sys/net/netfilter/nf_conntrack_count ────────────────────────────
local function emit_conntrack(reg, body)
  if not body then return end
  local n = body:match("(%d+)")
  if n then metrics.set_gauge(reg, "router_host_conntrack_count", {}, tonumber(n)) end
end

-- ── /sys/class/net/<iface>/statistics/{rx,tx}_bytes ────────────────────────
-- The kernel exposes cumulative counters; we fold via metrics.fold_external
-- so a reboot / NIC re-init (counter reset) re-bases the registry instead of
-- pushing a negative delta.
local function emit_iface_bytes(reg, read_file, ifaces, baseline)
  if not ifaces or #ifaces == 0 then return end
  baseline = baseline or {}
  baseline.rx = baseline.rx or {}
  baseline.tx = baseline.tx or {}
  local rx_cur, tx_cur = {}, {}
  for _, iface in ipairs(ifaces) do
    local rx = read_file("/sys/class/net/" .. iface .. "/statistics/rx_bytes")
    local tx = read_file("/sys/class/net/" .. iface .. "/statistics/tx_bytes")
    if rx then
      local n = tonumber(rx:match("%d+"))
      if n then rx_cur[iface] = n end
    end
    if tx then
      local n = tonumber(tx:match("%d+"))
      if n then tx_cur[iface] = n end
    end
  end
  metrics.fold_external(reg, "router_host_iface_rx_bytes_total", "iface", rx_cur, baseline.rx)
  metrics.fold_external(reg, "router_host_iface_tx_bytes_total", "iface", tx_cur, baseline.tx)
end

-- ── `iw dev <iface> station dump` ──────────────────────────────────────────
-- Each connected station appears as one `Station <mac> (on <iface>)` line.
-- We count lines; per-MAC details are deliberately NOT emitted to Prometheus
-- (cardinality firewall — see header).
local function count_stations(dump)
  if not dump or dump == "" then return 0 end
  local n = 0
  for _ in dump:gmatch("\nStation ") do n = n + 1 end
  -- Count a leading "Station " too (no preceding newline).
  if dump:sub(1, 8) == "Station " then n = n + 1 end
  return n
end

local function emit_wifi_clients(reg, iw_dump, wifi_ifaces)
  if not wifi_ifaces or not iw_dump then return end
  for _, w in ipairs(wifi_ifaces) do
    local dump  = iw_dump(w.iface)
    local count = count_stations(dump)
    metrics.set_gauge(
      reg,
      "router_host_wifi_clients",
      { iface = w.iface, ssid = w.ssid },
      count
    )
  end
end

-- ── Public entry point ────────────────────────────────────────────────────
-- Called from the agent's metrics-push timer, once per tick, BEFORE
-- metrics.post. `deps` carries:
--
--   read_file(path)      : returns file contents string, or nil if missing
--   iw_dump(iface)       : returns `iw dev <iface> station dump` stdout, or ""
--   cpu_state            : caller-owned table; host_metrics stores prev snapshot
--   ifaces               : list of iface names for bandwidth (e.g. {"br-lan","wan"})
--   iface_baseline       : { rx = {}, tx = {} } — caller-owned per-iface baselines
--   wifi_ifaces          : list of { iface=..., ssid=... } pairs to sample
--
-- Sample order is fixed-cost and bounded: a handful of small file reads + at
-- most one `iw` shell-out per wifi iface. Worst case on the smallest router
-- is well under 50 ms; the agent driver should verify on the test router
-- before declaring done.
function M.sample(reg, deps)
  deps = deps or {}
  local read_file = deps.read_file or function(_) return nil end

  emit_loadavg(reg, read_file("/proc/loadavg"))
  emit_cpu_pct(reg, read_file("/proc/stat"), deps.cpu_state)
  emit_meminfo(reg, read_file("/proc/meminfo"))
  emit_conntrack(reg, read_file("/proc/sys/net/netfilter/nf_conntrack_count"))
  emit_iface_bytes(reg, read_file, deps.ifaces, deps.iface_baseline)
  emit_wifi_clients(reg, deps.iw_dump, deps.wifi_ifaces)
end

-- Exported for tests / contract fixtures.
M._parse_cpu_stat  = parse_cpu_stat
M._count_stations  = count_stations

return M
