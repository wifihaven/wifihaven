-- host_metrics_spec.lua — unit tests for the native OpenWRT OS-level metrics
-- collector (#1718). Reads /proc/loadavg, /proc/stat, /proc/meminfo,
-- /proc/sys/net/netfilter/nf_conntrack_count, /sys/class/net/*/statistics/*,
-- and `iw dev <iface> station dump`, then folds them into the existing
-- metrics registry via the same names the API allowlisted in #1718 PR 1.
--
-- All I/O is injected (`read_file`, `iw_dump`) so this spec drives the parser
-- with fixture strings — no /proc, no `iw`, no kernel needed in CI.

local metrics      = require("wifihaven.metrics")
local host_metrics = require("wifihaven.host_metrics")

local function find_gauge(batch, name, label_key, label_val)
  for _, g in ipairs(batch.gauges or {}) do
    if g.name == name then
      if not label_key then return g end
      if g.labels and g.labels[label_key] == label_val then return g end
    end
  end
  return nil
end

local function find_counter(batch, name, label_key, label_val)
  for _, c in ipairs(batch.counters or {}) do
    if c.name == name then
      if not label_key then return c end
      if c.labels and c.labels[label_key] == label_val then return c end
    end
  end
  return nil
end

local function new_reg()
  return metrics.new({
    router_id     = "11111111-2222-3333-4444-555555555555",
    agent_version = "0.3.1",
    started_at    = "2026-06-14T09:00:00Z",
  })
end

-- A small in-memory filesystem the deps.read_file callback dispatches against.
-- Returns nil for any unknown path so missing-file paths in host_metrics flow
-- the same way they would on a router that's missing the source (no panic).
local function fs(files)
  return function(path) return files[path] end
end

-- A realistic /proc/loadavg sample: 3 float fields, then sched + last PID.
local LOADAVG_NORMAL = "0.43 0.27 0.19 1/72 1234\n"

-- /proc/stat with the leading aggregate `cpu` line. host_metrics derives CPU%
-- from the delta of (sum - idle) / sum between two snapshots. The first
-- snapshot can't compute a % (no prior), so cpu_pct gauge appears only on the
-- second call onward.
local STAT_T0 = [[
cpu  100 0 50 1000 10 0 0 0 0 0
cpu0 100 0 50 1000 10 0 0 0 0 0
intr 12345
ctxt 67890
]]
local STAT_T1 = [[
cpu  200 0 100 1500 10 0 0 0 0 0
cpu0 200 0 100 1500 10 0 0 0 0 0
intr 12345
ctxt 67890
]]

local MEMINFO = [[
MemTotal:         244000 kB
MemFree:           38000 kB
MemAvailable:      89000 kB
Buffers:            4200 kB
Cached:            41000 kB
SwapCached:            0 kB
]]

local CONNTRACK_COUNT = "1432\n"

-- `iw dev <iface> station dump` lines. Each `Station <mac> (on <iface>)`
-- starts a new station record; host_metrics just counts records.
local IW_DUMP_PHY0 = [[
Station aa:bb:cc:dd:ee:01 (on phy0-ap0)
	signal:  	-52 dBm
	tx bitrate:	866.7 MBit/s
Station aa:bb:cc:dd:ee:02 (on phy0-ap0)
	signal:  	-61 dBm
	tx bitrate:	433.3 MBit/s
Station aa:bb:cc:dd:ee:03 (on phy0-ap0)
	signal:  	-70 dBm
	tx bitrate:	144.4 MBit/s
]]

describe("host_metrics.sample", function()
  it("emits load avg gauges from /proc/loadavg", function()
    local reg = new_reg()
    host_metrics.sample(reg, {
      read_file = fs({ ["/proc/loadavg"] = LOADAVG_NORMAL }),
    })
    local batch = metrics.build_batch(reg, "2026-06-14T14:01:00Z")
    assert.equal(0.43, find_gauge(batch, "router_host_load_m1").value)
    assert.equal(0.27, find_gauge(batch, "router_host_load_m5").value)
    assert.equal(0.19, find_gauge(batch, "router_host_load_m15").value)
  end)

  it("derives CPU% from /proc/stat delta — skips first call (no prior)", function()
    local reg = new_reg()
    local state = {}
    host_metrics.sample(reg, {
      read_file = fs({ ["/proc/stat"] = STAT_T0 }),
      cpu_state = state,
    })
    local b1 = metrics.build_batch(reg, "2026-06-14T14:01:00Z")
    assert.is_nil(find_gauge(b1, "router_host_cpu_pct"))

    host_metrics.sample(reg, {
      read_file = fs({ ["/proc/stat"] = STAT_T1 }),
      cpu_state = state,
    })
    local b2 = metrics.build_batch(reg, "2026-06-14T14:02:00Z")
    -- T0 totals: 100+0+50+1000+10 = 1160; idle = 1000
    -- T1 totals: 200+0+100+1500+10 = 1810; idle = 1500
    -- busy_delta = (1810-1500) - (1160-1000) = 310-160 = 150
    -- total_delta = 1810-1160 = 650
    -- cpu_pct = 150/650 * 100 ≈ 23.0769
    local pct = find_gauge(b2, "router_host_cpu_pct")
    assert.is_not_nil(pct)
    assert.is_true(pct.value > 23.0 and pct.value < 23.2)
  end)

  it("parses /proc/meminfo into four mem_kb gauges", function()
    local reg = new_reg()
    host_metrics.sample(reg, {
      read_file = fs({ ["/proc/meminfo"] = MEMINFO }),
    })
    local batch = metrics.build_batch(reg, "2026-06-14T14:01:00Z")
    assert.equal(244000, find_gauge(batch, "router_host_mem_total_kb").value)
    assert.equal(38000, find_gauge(batch, "router_host_mem_free_kb").value)
    assert.equal(4200, find_gauge(batch, "router_host_mem_buffers_kb").value)
    assert.equal(41000, find_gauge(batch, "router_host_mem_cached_kb").value)
  end)

  it("emits conntrack count from nf_conntrack_count", function()
    local reg = new_reg()
    host_metrics.sample(reg, {
      read_file = fs({
        ["/proc/sys/net/netfilter/nf_conntrack_count"] = CONNTRACK_COUNT,
      }),
    })
    local batch = metrics.build_batch(reg, "2026-06-14T14:01:00Z")
    assert.equal(1432, find_gauge(batch, "router_host_conntrack_count").value)
  end)

  it("folds per-iface byte counters as cumulative counters with iface label", function()
    local reg = new_reg()
    local iface_baseline = { rx = {}, tx = {} }
    host_metrics.sample(reg, {
      read_file = fs({
        ["/sys/class/net/br-lan/statistics/rx_bytes"] = "1000\n",
        ["/sys/class/net/br-lan/statistics/tx_bytes"] = "2000\n",
        ["/sys/class/net/wan/statistics/rx_bytes"]    = "5000\n",
        ["/sys/class/net/wan/statistics/tx_bytes"]    = "7000\n",
      }),
      ifaces         = { "br-lan", "wan" },
      iface_baseline = iface_baseline,
    })
    -- Second sample to verify delta-folding: br-lan grew by 234, wan was reset.
    host_metrics.sample(reg, {
      read_file = fs({
        ["/sys/class/net/br-lan/statistics/rx_bytes"] = "1234\n",
        ["/sys/class/net/br-lan/statistics/tx_bytes"] = "2500\n",
        ["/sys/class/net/wan/statistics/rx_bytes"]    = "100\n", -- reset
        ["/sys/class/net/wan/statistics/tx_bytes"]    = "200\n", -- reset
      }),
      ifaces         = { "br-lan", "wan" },
      iface_baseline = iface_baseline,
    })
    local batch = metrics.build_batch(reg, "2026-06-14T14:01:00Z")
    -- Cumulative since registry start: br-lan rx = 1000 (first snapshot) + 234 (delta) = 1234.
    -- wan rx had a reset, so the second sample re-bases from 0 → folded value is 100, total 5100.
    assert.equal(1234, find_counter(batch, "router_host_iface_rx_bytes_total", "iface", "br-lan").value)
    assert.equal(2500, find_counter(batch, "router_host_iface_tx_bytes_total", "iface", "br-lan").value)
    assert.equal(5100, find_counter(batch, "router_host_iface_rx_bytes_total", "iface", "wan").value)
    assert.equal(7200, find_counter(batch, "router_host_iface_tx_bytes_total", "iface", "wan").value)
  end)

  it("counts wireless stations per (iface, ssid) from iw dev station dump", function()
    local reg = new_reg()
    local called_iface
    host_metrics.sample(reg, {
      wifi_ifaces = { { iface = "phy0-ap0", ssid = "Home" } },
      iw_dump     = function(iface)
        called_iface = iface
        return IW_DUMP_PHY0
      end,
    })
    assert.equal("phy0-ap0", called_iface)
    local batch = metrics.build_batch(reg, "2026-06-14T14:01:00Z")
    local g     = find_gauge(batch, "router_host_wifi_clients", "iface", "phy0-ap0")
    assert.is_not_nil(g)
    assert.equal("Home", g.labels.ssid)
    assert.equal(3, g.value)
  end)

  it("emits zero wireless clients when iw dump is empty", function()
    local reg = new_reg()
    host_metrics.sample(reg, {
      wifi_ifaces = { { iface = "phy0-ap0", ssid = "Home" } },
      iw_dump     = function() return "" end,
    })
    local batch = metrics.build_batch(reg, "2026-06-14T14:01:00Z")
    local g     = find_gauge(batch, "router_host_wifi_clients", "iface", "phy0-ap0")
    assert.is_not_nil(g)
    assert.equal(0, g.value)
  end)

  it("does not panic when /proc files are missing — emits nothing for that source", function()
    local reg = new_reg()
    -- read_file returns nil for everything → no series emitted at all, no error.
    host_metrics.sample(reg, {
      read_file = fs({}),
      ifaces    = { "br-lan" },
    })
    local batch = metrics.build_batch(reg, "2026-06-14T14:01:00Z")
    assert.is_nil(batch.gauges)
    assert.is_nil(batch.counters)
  end)
end)
