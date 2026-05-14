-- Tests for openwrt/files/usr/lib/lua/familydns/render.lua
-- Run with: cd openwrt && busted test/render_spec.lua

local render = require("render")

-- Snapshot shape per #354 / docs/architecture.md §0.2. Field names match the
-- wire JSON emitted by the API (camelCase). devices and profiles are objects
-- (Maps), keyed by mac and stringified profileId respectively. Per-flow
-- decisions (schedule / time-limit / pause) have already been collapsed
-- server-side into the profile's BlockRules; the agent never re-evaluates.
local function snap_one()
  return {
    etag        = "sha256:abc123",
    generatedAt = "2026-05-14T14:00:00Z",
    devices = {
      ["aa:bb:cc:11:22:33"] = {
        profileId = 3, name = "kid-ipad", rules = nil,
      },
    },
    profiles = {
      ["3"] = {
        name = "kids",
        rules = {
          blocked      = false,
          blockReason  = nil,
          extraBlocked = { "tiktok.com" },
          extraAllowed = { "khanacademy.org" },
          blocklistIds = { "ads", "adult" },
          blockIpOnly  = false,
        },
        failureMode = "closed",
      },
    },
    blocklists = {},
  }
end

-- ── dnsmasq config ────────────────────────────────────────────────────────

describe("render.dnsmasq", function()

  it("emits dhcp-host entry that tags each device MAC to its profile", function()
    local conf = render.dnsmasq(snap_one())
    assert.truthy(conf:find("dhcp-host=aa:bb:cc:11:22:33,set:profile3", 1, true))
  end)

  it("emits NXDOMAIN address= for each extraBlocked domain", function()
    local conf = render.dnsmasq(snap_one())
    assert.truthy(conf:find("address=/tiktok.com/#", 1, true))
  end)

  it("does NOT emit NXDOMAIN for extraAllowed domains", function()
    local conf = render.dnsmasq(snap_one())
    assert.falsy(conf:find("address=/khanacademy.org/#", 1, true))
  end)

  it("handles multiple devices in different profiles", function()
    local s = snap_one()
    s.devices["de:ad:be:ef:00:01"] = { profileId = 1, name = "parent-phone", rules = nil }
    s.profiles["1"] = {
      name = "adults",
      rules = {
        blocked = false, blockReason = nil,
        extraBlocked = {}, extraAllowed = {}, blocklistIds = {}, blockIpOnly = false,
      },
      failureMode = "open",
    }
    local conf = render.dnsmasq(s)
    assert.truthy(conf:find("dhcp-host=aa:bb:cc:11:22:33,set:profile3", 1, true))
    assert.truthy(conf:find("dhcp-host=de:ad:be:ef:00:01,set:profile1", 1, true))
  end)

  it("deduplicates extraBlocked domains across profiles", function()
    local s = snap_one()
    s.profiles["1"] = {
      name = "adults",
      rules = {
        blocked = false, blockReason = nil,
        extraBlocked = { "tiktok.com" }, extraAllowed = {}, blocklistIds = {},
        blockIpOnly = false,
      },
      failureMode = "open",
    }
    local conf = render.dnsmasq(s)
    local _, count = conf:gsub("address=/tiktok%.com/#", "")
    assert.equal(1, count)
  end)

  it("returns a non-empty string", function()
    local conf = render.dnsmasq(snap_one())
    assert.truthy(type(conf) == "string" and #conf > 0)
  end)

  -- Regression for #228: devices auto-created via /api/router/events
  -- first_seen_mac have profileId=nil until an admin assigns them in the UI.
  it("skips devices with nil profileId (no string.format %d nil crash)", function()
    local s = snap_one()
    s.devices["be:89:10:82:c7:4c"] = { profileId = nil, name = "iPhone", rules = nil }
    local conf
    local ok = pcall(function() conf = render.dnsmasq(s) end)
    assert.is_true(ok)
    assert.is_nil(conf:find("dhcp-host=be:89:10:82:c7:4c", 1, true))
    assert.truthy(conf:find("dhcp-host=aa:bb:cc:11:22:33,set:profile3", 1, true))
  end)

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

  it("declares the mac_ip_tracking set for per-flow byte accounting", function()
    local nft = render.nft(snap_one())
    assert.truthy(nft:find("mac_ip_tracking", 1, true))
  end)

  -- #354: blocked_macs is derived per-device from effective BlockRules.blocked.
  -- The API server precomputes pause / time-limit / schedule into that flag.

  it("declares a blocked_macs set (type ether_addr)", function()
    local nft = render.nft(snap_one())
    local pos = nft:find("set blocked_macs")
    assert.truthy(pos)
    local block = nft:sub(pos, pos + 200)
    assert.truthy(block:find("ether_addr", 1, true))
  end)

  it("emits a drop rule on @blocked_macs when a profile is blocked", function()
    local s = snap_one()
    s.profiles["3"].rules.blocked = true
    s.profiles["3"].rules.blockReason = "Paused"
    local nft = render.nft(s)
    assert.truthy(nft:find("aa:bb:cc:11:22:33", 1, true))
    assert.truthy(nft:find("@blocked_macs", 1, true))
    assert.truthy(nft:find("drop", 1, true))
  end)

  it("does NOT emit an @blocked_macs drop rule when nothing is blocked", function()
    local nft = render.nft(snap_one())
    assert.falsy(nft:find("@blocked_macs%s+drop"))
  end)

  it("includes every device of a blocked profile in @blocked_macs", function()
    local s = snap_one()
    s.devices["11:22:33:44:55:66"] = { profileId = 3, name = "kid-phone", rules = nil }
    s.profiles["3"].rules.blocked = true
    s.profiles["3"].rules.blockReason = "Schedule"
    local nft = render.nft(s)
    assert.truthy(nft:find("aa:bb:cc:11:22:33", 1, true))
    assert.truthy(nft:find("11:22:33:44:55:66", 1, true))
  end)

  -- #297/#303: dnat must not live inside the filter chain.
  it("does NOT emit dnat inside the familydns_block filter chain", function()
    local s = snap_one()
    s.profiles["3"].rules.blocked = true
    s.profiles["3"].rules.blockReason = "Paused"
    local nft = render.nft(s)
    local block_start = nft:find("chain familydns_block {", 1, true)
    assert.truthy(block_start)
    local next_chain = nft:find("\n%s*chain ", block_start + 1)
    local block_body = nft:sub(block_start, (next_chain or #nft + 1) - 1)
    assert.is_nil(block_body:find("dnat", 1, true))
  end)

  it("returns a non-empty string", function()
    local nft = render.nft(snap_one())
    assert.truthy(type(nft) == "string" and #nft > 0)
  end)

  -- #308: atomic-swap prelude swapping the boot table for the runtime table.

  it("emits the atomic-swap prelude removing the boot skeleton (#308)", function()
    local nft = render.nft(snap_one())
    local add_boot_pos = nft:find("add table inet familydns_boot", 1, true)
    local del_boot_pos = nft:find("delete table inet familydns_boot", 1, true)
    assert.truthy(add_boot_pos)
    assert.truthy(del_boot_pos)
    assert.is_true(add_boot_pos < del_boot_pos)
  end)

  it("uses idempotent add+delete for the runtime table before the body (#308)", function()
    local nft = render.nft(snap_one())
    local add_pos  = nft:find("add table inet familydns\n", 1, true)
    local del_pos  = nft:find("delete table inet familydns\n", 1, true)
    local body_pos = nft:find("table inet familydns {", 1, true)
    assert.truthy(add_pos)
    assert.truthy(del_pos)
    assert.truthy(body_pos)
    assert.is_true(add_pos < del_pos)
    assert.is_true(del_pos < body_pos)
  end)

  it("removes the boot skeleton before installing the runtime body (#308)", function()
    local nft = render.nft(snap_one())
    local del_boot_pos = nft:find("delete table inet familydns_boot", 1, true)
    local body_pos     = nft:find("table inet familydns {", 1, true)
    assert.truthy(del_boot_pos)
    assert.truthy(body_pos)
    assert.is_true(del_boot_pos < body_pos)
  end)

  -- Regression for #228 (nil profileId crash).
  it("skips devices with nil profileId when building profile-mac sets", function()
    local s = snap_one()
    s.devices["be:89:10:82:c7:4c"] = { profileId = nil, name = "iPhone", rules = nil }
    local nft
    local ok = pcall(function() nft = render.nft(s) end)
    assert.is_true(ok)
    assert.is_nil(nft:find("be:89:10:82:c7:4c", 1, true))
    assert.truthy(nft:find("aa:bb:cc:11:22:33", 1, true))
  end)

end)

-- ── nat chain (block-page DNAT) ───────────────────────────────────────────

describe("render.nft nat chain", function()

  it("emits chain familydns_block_nat with nat hook prerouting when something is blocked", function()
    local s = snap_one()
    s.profiles["3"].rules.blocked = true
    s.profiles["3"].rules.blockReason = "Paused"
    local nft = render.nft(s)
    assert.truthy(nft:find("chain familydns_block_nat", 1, true))
    assert.truthy(nft:find("type nat hook prerouting priority dstnat", 1, true))
  end)

  it("uses the inet family qualifier (`dnat ip to`, not bare `dnat to`)", function()
    local s = snap_one()
    s.profiles["3"].rules.blocked = true
    s.profiles["3"].rules.blockReason = "Paused"
    local nft = render.nft(s)
    assert.truthy(nft:find("dnat ip to 127.0.0.1:8081", 1, true))
    assert.is_nil(nft:find("dnat to 127.0.0.1", 1, true))
  end)

  it("emits a DNAT rule scoped to the @blocked_macs set", function()
    local s = snap_one()
    s.profiles["3"].rules.blocked = true
    s.profiles["3"].rules.blockReason = "Paused"
    local nft = render.nft(s)
    assert.truthy(nft:find(
      "ether saddr @blocked_macs tcp dport 80 dnat ip to 127.0.0.1:8081",
      1, true))
  end)

  it("emits the same DNAT rule regardless of block reason", function()
    local s = snap_one()
    s.profiles["3"].rules.blocked = true
    s.profiles["3"].rules.blockReason = "TimeLimit"
    local nft = render.nft(s)
    assert.truthy(nft:find(
      "ether saddr @blocked_macs tcp dport 80 dnat ip to 127.0.0.1:8081",
      1, true))
  end)

  it("does NOT emit the nat chain when nothing is blocked", function()
    local nft = render.nft(snap_one())
    assert.is_nil(nft:find("familydns_block_nat", 1, true))
    assert.is_nil(nft:find("hook prerouting", 1, true))
  end)

  it("filter chain still drops blocked traffic (HTTPS / non-port-80 path)", function()
    local s = snap_one()
    s.profiles["3"].rules.blocked = true
    s.profiles["3"].rules.blockReason = "Paused"
    local nft = render.nft(s)
    assert.truthy(nft:find("ether saddr @blocked_macs drop", 1, true))
  end)

end)

-- ── nft syntax validation (skipped if `nft` binary unavailable) ───────────

describe("render.nft syntax validation", function()

  local function nft_available()
    local ok = os.execute("command -v nft >/dev/null 2>&1")
    return ok == 0 or ok == true
  end

  it("rendered output loads cleanly via `nft -c -f -` when a profile is blocked", function()
    if not nft_available() then
      pending("nft binary not available on this host")
      return
    end
    local s = snap_one()
    s.profiles["3"].rules.blocked = true
    s.profiles["3"].rules.blockReason = "Paused"
    local nft_text = render.nft(s)
    local tmp = os.tmpname()
    local f = io.open(tmp, "w")
    f:write(nft_text)
    f:close()
    local ok = os.execute("nft -c -f " .. tmp .. " >/dev/null 2>&1")
    os.remove(tmp)
    assert.is_true(ok == 0 or ok == true)
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

  it("does not error on snapshot with zero devices", function()
    local s = snap_one()
    s.devices = {}
    local nft_sets, blocked_macs, blocked_reason = {}, {}, {}
    assert.has_no.errors(function()
      render.update_shared(s, nft_sets, blocked_macs, blocked_reason)
    end)
  end)

  -- #354: blocked_macs / blocked_reason are now populated from each device's
  -- effective BlockRules (profile or per-device override). conntrack uses
  -- this to label connection_attempt events.

  it("populates blocked_macs / blocked_reason when a device's profile is blocked", function()
    local s = snap_one()
    s.profiles["3"].rules.blocked = true
    s.profiles["3"].rules.blockReason = "Paused"
    local nft_sets, blocked_macs, blocked_reason = {}, {}, {}
    render.update_shared(s, nft_sets, blocked_macs, blocked_reason)
    assert.is_true(blocked_macs["aa:bb:cc:11:22:33"])
    assert.equal("Paused", blocked_reason["aa:bb:cc:11:22:33"])
  end)

  it("preserves the reason verbatim (Schedule / TimeLimit / Paused)", function()
    -- Two profiles, each blocked for a different reason, one device each.
    local s = snap_one()
    s.profiles["3"].rules.blocked = true
    s.profiles["3"].rules.blockReason = "Schedule"
    s.devices["11:22:33:44:55:66"] = { profileId = 4, name = "kid-phone", rules = nil }
    s.profiles["4"] = {
      name = "another",
      rules = {
        blocked = true, blockReason = "TimeLimit",
        extraBlocked = {}, extraAllowed = {}, blocklistIds = {}, blockIpOnly = false,
      },
      failureMode = "closed",
    }
    local nft_sets, blocked_macs, blocked_reason = {}, {}, {}
    render.update_shared(s, nft_sets, blocked_macs, blocked_reason)
    assert.equal("Schedule",  blocked_reason["aa:bb:cc:11:22:33"])
    assert.equal("TimeLimit", blocked_reason["11:22:33:44:55:66"])
  end)

  it("clears stale entries when no profile is blocked anymore", function()
    local nft_sets        = {}
    local blocked_macs    = { ["aa:bb:cc:11:22:33"] = true }
    local blocked_reason  = { ["aa:bb:cc:11:22:33"] = "Paused" }
    render.update_shared(snap_one(), nft_sets, blocked_macs, blocked_reason)
    assert.is_nil(blocked_macs["aa:bb:cc:11:22:33"])
    assert.is_nil(blocked_reason["aa:bb:cc:11:22:33"])
  end)

  it("leaves blocked_macs empty when no profile is blocked", function()
    local nft_sets, blocked_macs, blocked_reason = {}, {}, {}
    render.update_shared(snap_one(), nft_sets, blocked_macs, blocked_reason)
    assert.is_nil(blocked_macs["aa:bb:cc:11:22:33"])
  end)

  it("a device-level rules override replaces the profile rules entirely (#354)", function()
    local s = snap_one()
    -- Profile is unblocked; device override blocks.
    s.devices["aa:bb:cc:11:22:33"].rules = {
      blocked = true, blockReason = "Manual",
      extraBlocked = {}, extraAllowed = {}, blocklistIds = {}, blockIpOnly = false,
    }
    local nft_sets, blocked_macs, blocked_reason = {}, {}, {}
    render.update_shared(s, nft_sets, blocked_macs, blocked_reason)
    assert.is_true(blocked_macs["aa:bb:cc:11:22:33"])
    assert.equal("Manual", blocked_reason["aa:bb:cc:11:22:33"])
  end)

end)
