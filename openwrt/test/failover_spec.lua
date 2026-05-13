-- Tests for #311 fail-closed / fail-open failover in render.nft.
--
-- The agent passes opts.poll_age_seconds = (now - last_successful_poll_ts).
-- When poll_age > 300, profiles with failureMode == "closed" get an additional
-- drop rule for ALL their devices' MACs (regardless of snapshot.blockedMacs).
-- Profiles with failureMode == "open" keep enforcing the cached snapshot
-- exactly as-is.
--
-- Run with: cd openwrt && busted test/failover_spec.lua

local render = require("render")

-- Two-profile snapshot: a Closed-failover "kids" profile (devices A, B) and
-- an Open-failover "adults" profile (devices C, D).
local function snap_two()
  return {
    etag             = "sha256:f1",
    defaultProfileId = 1,
    devices = {
      { mac = "aa:aa:aa:00:00:01", profileId = 1, name = "kid-A" },
      { mac = "aa:aa:aa:00:00:02", profileId = 1, name = "kid-B" },
      { mac = "bb:bb:bb:00:00:03", profileId = 2, name = "adult-C" },
      { mac = "bb:bb:bb:00:00:04", profileId = 2, name = "adult-D" },
    },
    profiles = {
      {
        id = 1, name = "kids", paused = false, failureMode = "closed",
        blockedCategories = {}, extraBlocked = {}, extraAllowed = {},
        schedules = {}, dailyMinutes = 0, siteLimits = {},
        timeUsedToday = { totalMinutes = 0, byDomain = {} },
        extensionsTodayMinutes = 0,
      },
      {
        id = 2, name = "adults", paused = false, failureMode = "open",
        blockedCategories = {}, extraBlocked = {}, extraAllowed = {},
        schedules = {}, dailyMinutes = 0, siteLimits = {},
        timeUsedToday = { totalMinutes = 0, byDomain = {} },
        extensionsTodayMinutes = 0,
      },
    },
    blockedMacs = {},
  }
end

describe("render.nft — #311 failover", function()

  it("emits no failover drop set when poll_age_seconds is nil (no signal yet)", function()
    local nft = render.nft(snap_two())
    assert.is_nil(nft:find("failover_drop", 1, true))
  end)

  it("emits no failover drop set when poll_age_seconds <= 300 (within window)", function()
    local nft = render.nft(snap_two(), { poll_age_seconds = 300 })
    assert.is_nil(nft:find("failover_drop", 1, true))
  end)

  it("emits a failover drop set with Closed-profile MACs when poll_age > 300", function()
    local nft = render.nft(snap_two(), { poll_age_seconds = 301 })
    assert.truthy(nft:find("set failover_drop", 1, true))
    -- Closed profile devices land in the set.
    assert.truthy(nft:find("aa:aa:aa:00:00:01", 1, true))
    assert.truthy(nft:find("aa:aa:aa:00:00:02", 1, true))
    -- A drop rule on the set is emitted.
    assert.truthy(nft:find("@failover_drop", 1, true))
    assert.truthy(nft:find("drop", 1, true))
  end)

  it("excludes Open-profile MACs from the failover drop set", function()
    local nft = render.nft(snap_two(), { poll_age_seconds = 301 })
    -- Locate the failover_drop set body and check the open-profile MACs
    -- aren't inside it. (The MACs may still appear elsewhere — e.g. in
    -- profile2_macs — so we must scope this check.)
    local s = nft:find("set failover_drop", 1, true)
    assert.truthy(s)
    local body_end = nft:find("}", s, true)
    local body = nft:sub(s, body_end)
    assert.is_nil(body:find("bb:bb:bb:00:00:03", 1, true))
    assert.is_nil(body:find("bb:bb:bb:00:00:04", 1, true))
  end)

  it("emits no failover drop rule when a Closed profile has zero devices", function()
    -- Edge case called out in #311: an empty Closed profile must not produce
    -- a degenerate drop rule that matches nothing-but-costs-cycles.
    local s = snap_two()
    -- Strip the kids-profile devices; kids is still failureMode=closed.
    s.devices = {
      { mac = "bb:bb:bb:00:00:03", profileId = 2, name = "adult-C" },
    }
    local nft = render.nft(s, { poll_age_seconds = 9999 })
    -- No drop rule against a hypothetical empty set.
    assert.is_nil(nft:find("@failover_drop", 1, true))
  end)

  it("boundary: 300s is in-window, 301s triggers failover (strict > 300)", function()
    local nft_300 = render.nft(snap_two(), { poll_age_seconds = 300 })
    local nft_301 = render.nft(snap_two(), { poll_age_seconds = 301 })
    assert.is_nil(nft_300:find("@failover_drop", 1, true))
    assert.truthy(nft_301:find("@failover_drop", 1, true))
  end)

  it("normal (non-failover) snapshot rendering is unchanged when opts omitted", function()
    -- Regression: render.nft(snapshot) with no second arg must still work.
    local nft = render.nft(snap_two())
    assert.truthy(nft:find("table inet familydns", 1, true))
    assert.truthy(nft:find("set profile1_macs", 1, true))
    assert.truthy(nft:find("set profile2_macs", 1, true))
  end)

end)
