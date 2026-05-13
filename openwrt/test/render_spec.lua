-- Tests for openwrt/files/usr/lib/lua/familydns/render.lua
-- Run with: cd openwrt && busted test/render_spec.lua

local render = require("render")

-- Minimal but complete one-device snapshot. Field names match the wire JSON
-- emitted by the API (camelCase — Scala case-class fields, zio-json
-- deriveCodec; see shared/src/Models.scala PolicySnapshot/PolicyProfile/
-- PolicyDevice). render.lua reads those keys directly; if a test uses
-- snake_case here the assertions will look correct but the real agent gets
-- nothing (#297 — entire enforcement chain was no-op because the test fixture
-- diverged from the wire contract).
local function snap_one()
  return {
    etag             = "sha256:abc123",
    defaultProfileId = 3,
    devices = {
      { mac = "aa:bb:cc:11:22:33", profileId = 3, name = "kid-ipad" },
    },
    profiles = {
      {
        id                     = 3,
        name                   = "kids",
        paused                 = false,
        blockedCategories      = { "ads", "adult" },
        extraBlocked           = { "tiktok.com" },
        extraAllowed           = { "khanacademy.org" },
        schedules              = {
          { days = {"MON","TUE","WED","THU","FRI"},
            blockFrom = "21:00", blockUntil = "07:00" }
        },
        dailyMinutes           = 120,
        siteLimits             = {
          { domain = "youtube.com", minutes = 30, label = "YouTube" }
        },
        timeUsedToday          = { totalMinutes = 47, byDomain = { ["youtube.com"] = 12 } },
        extensionsTodayMinutes = 15,
      },
    },
  }
end

-- ── dnsmasq config ────────────────────────────────────────────────────────

describe("render.dnsmasq", function()

  it("emits dhcp-host entry that tags each device MAC to its profile", function()
    local conf = render.dnsmasq(snap_one())
    assert.truthy(conf:find("dhcp-host=aa:bb:cc:11:22:33,set:profile3", 1, true))
  end)

  it("emits NXDOMAIN address= for each extra_blocked domain", function()
    local conf = render.dnsmasq(snap_one())
    assert.truthy(conf:find("address=/tiktok.com/#", 1, true))
  end)

  it("does NOT emit NXDOMAIN for extra_allowed domains", function()
    local conf = render.dnsmasq(snap_one())
    assert.falsy(conf:find("address=/khanacademy.org/#", 1, true))
  end)

  it("emits ipset= for each site_limits domain (dots in set name replaced by underscores)", function()
    local conf = render.dnsmasq(snap_one())
    assert.truthy(conf:find("ipset=/youtube.com/profile3_youtube_com", 1, true))
  end)

  it("sanitises multi-label siteLimits domains in the set name", function()
    local s = snap_one()
    s.profiles[1].siteLimits = { { domain = "cdn.example.co.uk", minutes = 10, label = "X" } }
    local conf = render.dnsmasq(s)
    assert.truthy(conf:find("ipset=/cdn.example.co.uk/profile3_cdn_example_co_uk", 1, true))
  end)

  it("handles multiple devices in different profiles", function()
    local s = snap_one()
    table.insert(s.devices, { mac = "de:ad:be:ef:00:01", profileId = 1, name = "parent-phone" })
    s.profiles[2] = {
      id = 1, name = "adults", paused = false,
      blockedCategories = {}, extraBlocked = {}, extraAllowed = {},
      schedules = {}, dailyMinutes = 0, siteLimits = {},
      timeUsedToday = { totalMinutes = 0, byDomain = {} },
      extensionsTodayMinutes = 0,
    }
    local conf = render.dnsmasq(s)
    assert.truthy(conf:find("dhcp-host=aa:bb:cc:11:22:33,set:profile3", 1, true))
    assert.truthy(conf:find("dhcp-host=de:ad:be:ef:00:01,set:profile1", 1, true))
  end)

  it("deduplicates extraBlocked domains that appear in multiple profiles", function()
    local s = snap_one()
    s.profiles[2] = {
      id = 1, name = "adults", paused = false,
      blockedCategories = {}, extraBlocked = { "tiktok.com" }, extraAllowed = {},
      schedules = {}, dailyMinutes = 0, siteLimits = {},
      timeUsedToday = { totalMinutes = 0, byDomain = {} },
      extensionsTodayMinutes = 0,
    }
    local conf = render.dnsmasq(s)
    -- "address=/tiktok.com/#" should appear exactly once
    local _, count = conf:gsub("address=/tiktok%.com/#", "")
    assert.equal(1, count)
  end)

  it("returns a non-empty string", function()
    local conf = render.dnsmasq(snap_one())
    assert.truthy(type(conf) == "string" and #conf > 0)
  end)

  -- Regression for #228: devices auto-created via /api/router/events
  -- first_seen_mac have profileId=nil until an admin assigns them in the UI.
  -- The earlier render.dnsmasq used string.format("%d", nil) on them and the
  -- whole agent crash-looped in policy.apply.
  it("skips devices with nil profileId (no string.format %d nil crash)", function()
    local s = snap_one()
    table.insert(s.devices,
      { mac = "be:89:10:82:c7:4c", profileId = nil, name = "iPhone" })
    local conf
    local ok = pcall(function() conf = render.dnsmasq(s) end)
    assert.is_true(ok)
    -- The unassigned device must not appear at all in the dhcp-host lines.
    assert.is_nil(conf:find("dhcp-host=be:89:10:82:c7:4c", 1, true))
    -- ... while the assigned device still does.
    assert.truthy(conf:find("dhcp-host=aa:bb:cc:11:22:33,set:profile3", 1, true))
  end)

  -- #287: query logging is configured via UCI (`option logqueries`,
  -- `option logfacility`) rather than emitted into familydns.conf — that
  -- way dnsmasq's init.d wires the log file into the ujail RW mount.
  -- Re-emitting them here would trip "illegal repeated keyword".
  it("does NOT re-emit log-queries / log-facility (set via UCI)", function()
    local conf = render.dnsmasq(snap_one())
    assert.is_nil(conf:find("log-queries=", 1, true))
    assert.is_nil(conf:find("log-facility=", 1, true))
  end)

end)

-- ── nftables config ───────────────────────────────────────────────────────

describe("render.nft", function()

  it("wraps output in 'table inet familydns { }'", function()
    local nft = render.nft(snap_one())
    assert.truthy(nft:find("table inet familydns", 1, true))
  end)

  it("declares a per-profile MAC set with type ether_addr", function()
    local nft = render.nft(snap_one())
    local pos = nft:find("set profile3_macs")
    assert.truthy(pos)
    local block = nft:sub(pos, pos + 300)
    assert.truthy(block:find("ether_addr", 1, true))
  end)

  it("includes the device MAC address in the profile MAC set elements", function()
    local nft = render.nft(snap_one())
    assert.truthy(nft:find("aa:bb:cc:11:22:33", 1, true))
  end)

  it("declares a per-domain IP set (type ipv4_addr) for each site_limits domain", function()
    local nft = render.nft(snap_one())
    local pos = nft:find("set profile3_youtube_com")
    assert.truthy(pos)
    local block = nft:sub(pos, pos + 300)
    assert.truthy(block:find("ipv4_addr", 1, true))
  end)

  it("domain IP sets carry the 'dynamic' flag (populated at runtime by dnsmasq --ipset=)", function()
    local nft = render.nft(snap_one())
    local pos = nft:find("set profile3_youtube_com")
    assert.truthy(pos)
    local block = nft:sub(pos, pos + 300)
    assert.truthy(block:find("dynamic", 1, true))
  end)

  it("declares the mac_ip_tracking set for per-flow byte accounting", function()
    local nft = render.nft(snap_one())
    assert.truthy(nft:find("mac_ip_tracking", 1, true))
  end)

  it("includes a drop rule when profile time limit is exhausted", function()
    local s = snap_one()
    -- remaining = 120 + 15 - 47 = 88 → NOT exhausted yet
    local nft_ok = render.nft(s)
    -- exhaust it: 136 >= 120 + 15 = 135
    s.profiles[1].timeUsedToday.totalMinutes = 136
    local nft_ex = render.nft(s)
    -- exhausted config must contain a drop referencing profile3_macs
    assert.truthy(nft_ex:find("profile3_macs", 1, true))
    assert.truthy(nft_ex:find("drop", 1, true))
  end)

  it("includes a drop rule when the profile is paused", function()
    local s = snap_one()
    s.profiles[1].paused = true
    local nft = render.nft(s)
    assert.truthy(nft:find("profile3_macs", 1, true))
    assert.truthy(nft:find("drop", 1, true))
  end)

  it("does NOT include a drop for profile3 when it still has remaining time", function()
    local s = snap_one()
    s.profiles[1].paused = false
    s.profiles[1].timeUsedToday.totalMinutes = 10  -- well within 120 + 15
    local nft = render.nft(s)
    -- There must be no 'ether saddr @profile3_macs drop' pattern
    assert.falsy(nft:find("@profile3_macs%s+drop"))
  end)

  -- #297: previous render emitted `tcp dport 80 dnat to 127.0.0.1:8081` to
  -- DNAT blocked HTTP to a block page, but `dnat` in an inet filter chain is
  -- rejected by `nft -f` (DNAT requires a nat-type chain), which silently
  -- failed the entire ruleset and disabled all blocking. Drop the redirect
  -- until block-page support is rebuilt on a prerouting nat chain.
  it("does NOT emit dnat in the block filter chain (inet filter rejects it)", function()
    local s = snap_one()
    s.profiles[1].paused = true
    local nft = render.nft(s)
    assert.is_nil(nft:find("dnat", 1, true))
  end)

  it("returns a non-empty string", function()
    local nft = render.nft(snap_one())
    assert.truthy(type(nft) == "string" and #nft > 0)
  end)

  -- Regression for #228: same nil-profileId crash that hit render.dnsmasq
  -- also hit render.nft at `profile_macs[nil][#profile_macs[nil] + 1]`.
  it("skips devices with nil profileId when building profile-mac sets", function()
    local s = snap_one()
    table.insert(s.devices,
      { mac = "be:89:10:82:c7:4c", profileId = nil, name = "iPhone" })
    local nft
    local ok = pcall(function() nft = render.nft(s) end)
    assert.is_true(ok)
    -- The unassigned MAC must not show up anywhere in the nft output.
    assert.is_nil(nft:find("be:89:10:82:c7:4c", 1, true))
    -- The assigned device still lands in its profile set.
    assert.truthy(nft:find("aa:bb:cc:11:22:33", 1, true))
  end)

end)

-- ── shared-state update ───────────────────────────────────────────────────

describe("render.update_shared", function()

  it("does not error on a well-formed snapshot", function()
    local nft_sets, blocked_macs, blocked_reason = {}, {}, {}
    assert.has_no.errors(function()
      render.update_shared(snap_one(), nft_sets, blocked_macs, blocked_reason)
    end)
  end)

  it("seeds nft_sets with an entry for each siteLimits domain", function()
    local nft_sets, blocked_macs, blocked_reason = {}, {}, {}
    render.update_shared(snap_one(), nft_sets, blocked_macs, blocked_reason)
    -- dnsmasq will fill in IPs at query time; render.update_shared just seeds the key
    assert.not_nil(nft_sets["youtube.com"])
    assert.equal("table", type(nft_sets["youtube.com"]))
  end)

  it("does not error on snapshot with zero devices", function()
    local s = snap_one()
    s.devices = {}
    local nft_sets, blocked_macs, blocked_reason = {}, {}, {}
    assert.has_no.errors(function()
      render.update_shared(s, nft_sets, blocked_macs, blocked_reason)
    end)
  end)

  -- #297: pause must populate blocked_macs so the conntrack classifier labels
  -- the paused device's flows as blocked (not "permitted"). nftables also
  -- drops the packets, but conntrack -E -e NEW still sees the SYN_SENT entry
  -- and needs an independent signal to classify it.
  it("marks devices of a paused profile in blocked_macs with reason=paused", function()
    local s = snap_one()
    s.profiles[1].paused = true
    local nft_sets, blocked_macs, blocked_reason = {}, {}, {}
    render.update_shared(s, nft_sets, blocked_macs, blocked_reason)
    assert.is_true(blocked_macs["aa:bb:cc:11:22:33"])
    assert.equal("paused", blocked_reason["aa:bb:cc:11:22:33"])
  end)

  it("marks devices of a time-exhausted profile with reason=time_limit", function()
    local s = snap_one()
    -- dailyMinutes=120, extensions=15 → exhaust at 135
    s.profiles[1].timeUsedToday.totalMinutes = 200
    local nft_sets, blocked_macs, blocked_reason = {}, {}, {}
    render.update_shared(s, nft_sets, blocked_macs, blocked_reason)
    assert.is_true(blocked_macs["aa:bb:cc:11:22:33"])
    assert.equal("time_limit", blocked_reason["aa:bb:cc:11:22:33"])
  end)

  it("clears stale blocked_macs entries when a profile un-pauses", function()
    -- Simulate a previous tick that flagged the device as paused.
    local nft_sets        = {}
    local blocked_macs    = { ["aa:bb:cc:11:22:33"] = true }
    local blocked_reason  = { ["aa:bb:cc:11:22:33"] = "paused" }
    -- Now apply a fresh snapshot where the profile is no longer paused.
    render.update_shared(snap_one(), nft_sets, blocked_macs, blocked_reason)
    assert.is_nil(blocked_macs["aa:bb:cc:11:22:33"])
    assert.is_nil(blocked_reason["aa:bb:cc:11:22:33"])
  end)

  it("does not mark devices whose profile is not paused or time-exhausted", function()
    local nft_sets, blocked_macs, blocked_reason = {}, {}, {}
    render.update_shared(snap_one(), nft_sets, blocked_macs, blocked_reason)
    assert.is_nil(blocked_macs["aa:bb:cc:11:22:33"])
  end)

  it("ignores devices with nil profileId (unassigned)", function()
    local s = snap_one()
    s.profiles[1].paused = true
    table.insert(s.devices,
      { mac = "be:89:10:82:c7:4c", profileId = nil, name = "iPhone" })
    local nft_sets, blocked_macs, blocked_reason = {}, {}, {}
    render.update_shared(s, nft_sets, blocked_macs, blocked_reason)
    assert.is_nil(blocked_macs["be:89:10:82:c7:4c"])
  end)

end)
