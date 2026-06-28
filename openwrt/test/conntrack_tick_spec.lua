-- conntrack_tick_spec.lua — #2024 idle-heartbeat cadence for watch().
--
-- Root cause of the #2016 over-count: the agent's cooperative timers (usage
-- flush + 10 s activity sampler + policy poll + nflog drain + metrics) all run
-- inside `cfg.on_tick`, which watch() previously called ONLY after a
-- `conntrack -E -e NEW` line arrived. On a quiet LAN (one device on a
-- long-lived websocket / stream) no NEW events arrive, so on_tick never fired:
-- the usage window ballooned to span the whole un-monitored gap and the
-- activity sampler starved.
--
-- The fix multiplexes a wall-clock heartbeat sentinel line into the conntrack
-- popen stream (a shell heartbeat that echoes `M.TICK_SENTINEL` every
-- `tick_interval` seconds alongside conntrack's own output). watch() treats a
-- sentinel line as an inert tick: it skips flow parsing but STILL drives
-- on_tick, so the cooperative timers fire on a wall-clock cadence regardless
-- of conntrack traffic.
--
-- These specs inject a mock reader (via the `cfg.open_reader` seam) that yields
-- heartbeat sentinels and zero conntrack flow lines, and assert on_tick fires
-- once per heartbeat — i.e. the cadence survives total conntrack silence.

local conntrack = require("conntrack")

describe("watch() idle heartbeat cadence (#2024)", function()
  -- A mock popen handle that returns each element of `lines` from successive
  -- read("*l") calls, then nil (EOF) to break watch()'s loop.
  local function mock_reader(lines)
    local i = 0
    return {
      read = function(_, fmt)
        assert.are.equal("*l", fmt)
        i = i + 1
        return lines[i]
      end,
      close = function() end,
    }
  end

  local function base_cfg(overrides)
    local cfg = {
      api_url      = "http://test.invalid",
      router_id    = "r1",
      router_token = "tok",
      -- never actually posts in these specs (no events synthesized), but the
      -- batcher flush callback needs it present.
      http_post    = function() return true, 200, "" end,
    }
    for k, v in pairs(overrides) do cfg[k] = v end
    return cfg
  end

  it("exposes a stable TICK_SENTINEL distinct from any conntrack line", function()
    assert.is_string(conntrack.TICK_SENTINEL)
    -- A real conntrack -E NEW line begins with optional spaces then a protocol
    -- token ("[NEW]"/"tcp"/"udp"); parse_conntrack_line must reject the
    -- sentinel so it can never be mistaken for a flow.
    assert.is_nil(conntrack.parse_conntrack_line(conntrack.TICK_SENTINEL))
  end)

  it("fires on_tick on every heartbeat line with zero conntrack flows", function()
    local ticks = 0
    conntrack.watch(base_cfg({
      open_reader = function()
        return mock_reader({
          conntrack.TICK_SENTINEL,
          conntrack.TICK_SENTINEL,
          conntrack.TICK_SENTINEL,
          nil, -- EOF
        })
      end,
      on_tick = function() ticks = ticks + 1 end,
    }))
    assert.are.equal(3, ticks)
  end)

  it("does not synthesize/post any event for a heartbeat sentinel", function()
    local posted = 0
    conntrack.watch(base_cfg({
      http_post   = function() posted = posted + 1; return true, 200, "" end,
      open_reader = function()
        return mock_reader({ conntrack.TICK_SENTINEL, conntrack.TICK_SENTINEL, nil })
      end,
      on_tick = function() end,
    }))
    assert.are.equal(0, posted)
  end)

  it("still drives on_tick once per real conntrack line (regression)", function()
    -- The pre-#2024 contract — on_tick per conntrack line — must be preserved
    -- so busy LANs keep their per-line cadence in addition to the heartbeat.
    local ticks = 0
    conntrack.watch(base_cfg({
      open_reader = function()
        -- WAN-bound lines would normally be parsed; use lines that parse to nil
        -- (non-flow) so we exercise the tick path without DHCP/ARP stubs. The
        -- point is purely that a non-sentinel line still reaches on_tick.
        return mock_reader({ "garbage-not-a-flow", "garbage-not-a-flow", nil })
      end,
      on_tick = function() ticks = ticks + 1 end,
    }))
    assert.are.equal(2, ticks)
  end)
end)
