-- Tests for openwrt/files/usr/lib/lua/wifihaven/render.lua
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
          extraAllowed = {},
          blocklistIds = { "ads", "adult" },
          blockIpOnly  = false,
        },
        failureMode = "block-all",
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

  -- #351: extraBlocked is enforced at the connection layer via per-MAC nft
  -- drops on an ipset populated by dnsmasq's `--ipset=` callback. DNS itself
  -- resolves normally — there must NEVER be an `address=/host/#` line.
  it("does NOT emit address=/.../# for extraBlocked (Truth 1: DNS is not the enforcement plane)", function()
    local conf = render.dnsmasq(snap_one())
    assert.is_nil(conf:find("address=/tiktok.com/#", 1, true))
    assert.is_nil(conf:find("address=/khanacademy.org/#", 1, true))
  end)

  -- Post-#394 (e2e-vm): dnsmasq on OpenWRT 23.05+ uses native nftables sets
  -- via `nftset=`, not the legacy `ipset=` framework. Syntax encodes the
  -- address family (4# / 6#) so A → v4 set, AAAA → v6 set (#392).
  it("emits combined v4+v6 nftset=/host/... for each extraBlocked host", function()
    local conf = render.dnsmasq(snap_one())
    assert.truthy(conf:find(
      "nftset=/tiktok.com/4#inet#wifihaven#eb_tiktok_com,6#inet#wifihaven#eb6_tiktok_com",
      1, true))
  end)

  it("sanitises multi-label extraBlocked hosts in both v4 and v6 set names", function()
    local s = snap_one()
    s.profiles["3"].rules.extraBlocked = { "cdn.example.co.uk" }
    local conf = render.dnsmasq(s)
    assert.truthy(conf:find(
      "nftset=/cdn.example.co.uk/4#inet#wifihaven#eb_cdn_example_co_uk,6#inet#wifihaven#eb6_cdn_example_co_uk",
      1, true))
  end)

  it("does NOT emit nftset= for extraAllowed hosts when extraAllowed is empty", function()
    -- snap_one has extraAllowed = {} so no ea_ nftsets / eatag should appear.
    local conf = render.dnsmasq(snap_one())
    assert.is_nil(conf:find("eatag_", 1, true))
    assert.is_nil(conf:find("#wifihaven#ea_", 1, true))
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
      failureMode = "last-known-good",
    }
    local conf = render.dnsmasq(s)
    assert.truthy(conf:find("dhcp-host=aa:bb:cc:11:22:33,set:profile3", 1, true))
    assert.truthy(conf:find("dhcp-host=de:ad:be:ef:00:01,set:profile1", 1, true))
  end)

  it("deduplicates extraBlocked nftset= lines across profiles", function()
    local s = snap_one()
    s.devices["de:ad:be:ef:00:01"] = { profileId = 1, name = "parent-phone", rules = nil }
    s.profiles["1"] = {
      name = "adults",
      rules = {
        blocked = false, blockReason = nil,
        extraBlocked = { "tiktok.com" }, extraAllowed = {}, blocklistIds = {},
        blockIpOnly = false,
      },
      failureMode = "last-known-good",
    }
    local conf = render.dnsmasq(s)
    local _, count = conf:gsub("nftset=/tiktok%.com/", "")
    assert.equal(1, count)
  end)

  it("skips nftset= emission when an extraBlocked host has no device referencing it", function()
    -- Profile with extraBlocked but no devices assigned → nothing to populate.
    local s = snap_one()
    s.profiles["7"] = {
      name = "ghost",
      rules = {
        blocked = false, blockReason = nil,
        extraBlocked = { "ghosthost.example" }, extraAllowed = {}, blocklistIds = {},
        blockIpOnly = false,
      },
      failureMode = "last-known-good",
    }
    local conf = render.dnsmasq(s)
    assert.is_nil(conf:find("/ghosthost.example/", 1, true))
  end)

  it("emits combined v4+v6 nftset= for device-override extraBlocked", function()
    local s = snap_one()
    s.devices["aa:bb:cc:11:22:33"].rules = {
      blocked = false, blockReason = nil,
      extraBlocked = { "override.example" },
      extraAllowed = {}, blocklistIds = {}, blockIpOnly = false,
    }
    local conf = render.dnsmasq(s)
    assert.truthy(conf:find(
      "nftset=/override.example/4#inet#wifihaven#eb_override_example,6#inet#wifihaven#eb6_override_example",
      1, true))
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

  it("wraps output in 'table inet wifihaven { }'", function()
    local nft = render.nft(snap_one())
    assert.truthy(nft:find("table inet wifihaven", 1, true))
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

  -- #879/#897: download bytes used to be keyed on `ether daddr (mac) . ip
  -- saddr (remote)`, but at the forward hook a WAN→LAN routed packet still
  -- has the router's WAN-interface MAC as its L2 daddr (the rewrite happens
  -- at egress via the neighbor cache) — every rx byte got attributed to the
  -- router itself (#879). The interim fix keyed only on `ip daddr` which lost
  -- the remote-host axis (#897). The set now records (lan_device_ip,
  -- remote_ip) pairs and the agent resolves the lan_ip back to a MAC via
  -- the dnsmasq lease table.
  it("declares the ip_pair_tracking_rx set keyed on (ipv4_addr . ipv4_addr) (#897)", function()
    local nft = render.nft(snap_one())
    local pos = nft:find("set ip_pair_tracking_rx", 1, true)
    assert.truthy(pos)
    local block = nft:sub(pos, pos + 200)
    assert.truthy(block:find("type ipv4_addr %. ipv4_addr"))
    -- Guards against regressing to ether_addr keying (#879) or single-key
    -- ipv4_addr keying (#897).
    assert.is_nil(block:find("ether_addr"))
  end)

  it("does NOT declare the old mac_ip_tracking_rx or ip_tracking_rx sets (#897 cleanup)", function()
    local nft = render.nft(snap_one())
    assert.is_nil(nft:find("mac_ip_tracking_rx", 1, true))
    -- Old single-keyed set name from #879; superseded by ip_pair_tracking_rx.
    assert.is_nil(nft:find("set ip_tracking_rx", 1, true))
  end)

  -- #897: tx and rx live in separate chains, each scoped to one direction
  -- by an interface predicate. Without the predicate, the tx chain absorbs
  -- WAN→LAN packets too and writes ghost rows keyed on the upstream-gateway
  -- MAC + LAN device IP (live evidence in #897 showed these mirror the rx
  -- rows byte-for-byte, double-counting every download).
  -- Slice helper: return the body of a `chain <name> { ... }` block, exclusive
  -- of the braces. Lets each assertion check exactly one chain.
  local function chain_body(nft, name)
    local start = nft:find("chain " .. name .. " {", 1, true)
    if not start then return nil end
    local open  = nft:find("{", start, true)
    local close = nft:find("\n  }", open, true)
    return nft:sub(open + 1, close - 1)
  end

  it("declares wifihaven_account_tx scoped to iifname \"br-lan\" updating @mac_ip_tracking (#897)", function()
    local nft  = render.nft(snap_one())
    local body = chain_body(nft, "wifihaven_account_tx")
    assert.truthy(body)
    assert.truthy(body:find("iifname \"br%-lan\""))
    assert.truthy(body:find("update @mac_ip_tracking%s+{%s*ether saddr %. ip daddr%s*}%s+counter"))
    -- The rx set must NOT be updated from the tx chain — that would
    -- re-introduce the double-counting #897 fixes.
    assert.is_nil(body:find("ip_pair_tracking_rx"))
    assert.is_nil(body:find("oifname"))
  end)

  it("declares wifihaven_account_rx scoped to oifname \"br-lan\" updating @ip_pair_tracking_rx (#897)", function()
    local nft  = render.nft(snap_one())
    local body = chain_body(nft, "wifihaven_account_rx")
    assert.truthy(body)
    assert.truthy(body:find("oifname \"br%-lan\""))
    assert.truthy(body:find("update @ip_pair_tracking_rx%s+{%s*ip daddr %. ip saddr%s*}%s+counter"))
    -- Symmetric guard: the tx set must NOT be updated from the rx chain.
    assert.is_nil(body:find("mac_ip_tracking"))
    assert.is_nil(body:find("iifname"))
  end)

  it("does NOT declare the old combined wifihaven_account chain (#897)", function()
    local nft = render.nft(snap_one())
    -- We split into wifihaven_account_tx and wifihaven_account_rx; the
    -- bare `wifihaven_account ` chain (with trailing space, distinguishing
    -- from the new `_tx`/`_rx` suffixes) must be gone.
    assert.is_nil(nft:find("chain wifihaven_account ", 1, true))
    assert.is_nil(nft:find("chain wifihaven_account\n", 1, true))
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
  it("does NOT emit dnat inside the wifihaven_block filter chain", function()
    local s = snap_one()
    s.profiles["3"].rules.blocked = true
    s.profiles["3"].rules.blockReason = "Paused"
    local nft = render.nft(s)
    local block_start = nft:find("chain wifihaven_block {", 1, true)
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
    local add_boot_pos = nft:find("add table inet wifihaven_boot", 1, true)
    local del_boot_pos = nft:find("delete table inet wifihaven_boot", 1, true)
    assert.truthy(add_boot_pos)
    assert.truthy(del_boot_pos)
    assert.is_true(add_boot_pos < del_boot_pos)
  end)

  it("uses idempotent add+delete for the runtime table before the body (#308)", function()
    local nft = render.nft(snap_one())
    local add_pos  = nft:find("add table inet wifihaven\n", 1, true)
    local del_pos  = nft:find("delete table inet wifihaven\n", 1, true)
    local body_pos = nft:find("table inet wifihaven {", 1, true)
    assert.truthy(add_pos)
    assert.truthy(del_pos)
    assert.truthy(body_pos)
    assert.is_true(add_pos < del_pos)
    assert.is_true(del_pos < body_pos)
  end)

  it("removes the boot skeleton before installing the runtime body (#308)", function()
    local nft = render.nft(snap_one())
    local del_boot_pos = nft:find("delete table inet wifihaven_boot", 1, true)
    local body_pos     = nft:find("table inet wifihaven {", 1, true)
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

-- ── extraBlocked per-(MAC, host) enforcement (#351) ──────────────────────
--
-- Truth 1 (docs/architecture.md §0.1): DNS is never the enforcement plane.
-- extraBlocked must be enforced at the connection layer via nft drops on
-- per-host ipsets populated by dnsmasq `--ipset=`. The old `address=/host/#`
-- approach was both global (leaked across MACs) and DNS-layer (bypassable by
-- DoH / hard-coded IPs). These tests pin the new behaviour.

describe("render.nft extraBlocked enforcement", function()

  it("declares set eb_<sanitized-host> with dynamic ipv4 elements + timeout for each unique extraBlocked host", function()
    local nft = render.nft(snap_one())
    local pos = nft:find("set eb_tiktok_com", 1, true)
    assert.truthy(pos)
    local block = nft:sub(pos, pos + 200)
    assert.truthy(block:find("type ipv4_addr", 1, true))
    assert.truthy(block:find("flags dynamic,timeout", 1, true))
  end)

  -- #392: v6 sibling set must be declared alongside every eb_ set so the
  -- nftset=/host/6#inet#wifihaven#eb6_<host> AAAA callback has somewhere
  -- to populate.
  it("declares set eb6_<host> with dynamic ipv6 elements + 1h timeout (#392)", function()
    local nft = render.nft(snap_one())
    local pos = nft:find("set eb6_tiktok_com", 1, true)
    assert.truthy(pos)
    local block = nft:sub(pos, pos + 200)
    assert.truthy(block:find("type ipv6_addr", 1, true))
    assert.truthy(block:find("flags dynamic,timeout", 1, true))
    assert.truthy(block:find("timeout 1h", 1, true))
  end)

  it("emits a drop rule `ether saddr <mac> ip daddr @eb_<host> drop` for each (mac, host) pair", function()
    local nft = render.nft(snap_one())
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr @eb_tiktok_com log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:host\"", 1, true))
  end)

  -- #392: v6 drop rule mirrors the v4 one. Without it a dual-stack host
  -- whose AAAA resolves under a Kids profile slips through the v4 drop.
  it("emits a v6 drop rule `ether saddr <mac> ip6 daddr @eb6_<host> drop` (#392)", function()
    local nft = render.nft(snap_one())
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip6 daddr @eb6_tiktok_com log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:host\"", 1, true))
  end)

  it("does NOT drop traffic from MACs in OTHER profiles (no global leakage — the bug #351 fixes)", function()
    -- Kids blocks tiktok.com; Adults does not.
    local s = snap_one()
    s.devices["de:ad:be:ef:00:01"] = { profileId = 1, name = "parent-phone", rules = nil }
    s.profiles["1"] = {
      name = "adults",
      rules = {
        blocked = false, blockReason = nil,
        extraBlocked = {}, extraAllowed = {}, blocklistIds = {}, blockIpOnly = false,
      },
      failureMode = "last-known-good",
    }
    local nft = render.nft(s)
    -- Kids MAC still has the drop.
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr @eb_tiktok_com log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:host\"", 1, true))
    -- Adults MAC must NOT appear paired with eb_tiktok_com.
    assert.is_nil(nft:find(
      "ether saddr de:ad:be:ef:00:01 ip daddr @eb_tiktok_com log group 1 counter drop comment \"wh_drop:de:ad:be:ef:00:01:host\"", 1, true))
  end)

  it("two MACs sharing the same extraBlocked host produce ONE ipset and TWO MAC-scoped drops", function()
    local s = snap_one()
    s.devices["11:22:33:44:55:66"] = { profileId = 4, name = "kid-phone", rules = nil }
    s.profiles["4"] = {
      name = "kids2",
      rules = {
        blocked = false, blockReason = nil,
        extraBlocked = { "tiktok.com" }, extraAllowed = {}, blocklistIds = {},
        blockIpOnly = false,
      },
      failureMode = "block-all",
    }
    local nft = render.nft(s)
    local _, set_count = nft:gsub("set eb_tiktok_com {", "")
    assert.equal(1, set_count)
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr @eb_tiktok_com log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:host\"", 1, true))
    assert.truthy(nft:find(
      "ether saddr 11:22:33:44:55:66 ip daddr @eb_tiktok_com log group 1 counter drop comment \"wh_drop:11:22:33:44:55:66:host\"", 1, true))
  end)

  it("emits no extraBlocked sets or drop rules when no device has extraBlocked", function()
    local s = snap_one()
    s.profiles["3"].rules.extraBlocked = {}
    local nft = render.nft(s)
    assert.is_nil(nft:find("set eb_", 1, true))
    assert.is_nil(nft:find("@eb_", 1, true))
    assert.is_nil(nft:find("set eb6_", 1, true))
    assert.is_nil(nft:find("@eb6_", 1, true))
  end)

  it("does NOT emit any address=/.../# style DNS sinkhole regardless of extraBlocked", function()
    -- Pin Truth 1 from the render side too — render.nft never emits dnsmasq
    -- directives, but render.dnsmasq must not either. (Cross-checked above;
    -- this test pins absence in the *combined* output people grep.)
    local conf = render.dnsmasq(snap_one())
    local nft  = render.nft(snap_one())
    assert.is_nil(conf:find("/#", 1, true))
    assert.is_nil(nft:find("/#", 1, true))
  end)

  it("respects device-override extraBlocked (host applies only to that MAC, not its profile siblings)", function()
    local s = snap_one()
    -- Profile has no extraBlocked.
    s.profiles["3"].rules.extraBlocked = {}
    -- Sibling device under same profile (no override).
    s.devices["11:22:33:44:55:66"] = { profileId = 3, name = "kid-phone", rules = nil }
    -- Our device gets a per-device extraBlocked override.
    s.devices["aa:bb:cc:11:22:33"].rules = {
      blocked = false, blockReason = nil,
      extraBlocked = { "override.example" },
      extraAllowed = {}, blocklistIds = {}, blockIpOnly = false,
    }
    local nft = render.nft(s)
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr @eb_override_example log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:host\"", 1, true))
    -- Sibling under the same profile must NOT inherit the override.
    assert.is_nil(nft:find(
      "ether saddr 11:22:33:44:55:66 ip daddr @eb_override_example log group 1 counter drop comment \"wh_drop:11:22:33:44:55:66:host\"", 1, true))
  end)

end)

-- ── nat chain (block-page DNAT) ───────────────────────────────────────────

describe("render.nft nat chain", function()

  it("emits chain wifihaven_block_nat with nat hook prerouting when something is blocked", function()
    local s = snap_one()
    s.profiles["3"].rules.blocked = true
    s.profiles["3"].rules.blockReason = "Paused"
    local nft = render.nft(s)
    assert.truthy(nft:find("chain wifihaven_block_nat", 1, true))
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
    local s = snap_one()
    -- snap_one has extraBlocked which now also DNATs HTTP/80, so clear it
    -- to verify the "nothing blocked at all" path.
    s.profiles["3"].rules.extraBlocked = {}
    local nft = render.nft(s)
    assert.is_nil(nft:find("wifihaven_block_nat", 1, true))
    assert.is_nil(nft:find("hook prerouting", 1, true))
  end)

  it("filter chain still drops blocked traffic (HTTPS / non-port-80 path)", function()
    -- #1122: family-agnostic @blocked_macs drop converted to per-MAC rules so
    -- each carries a per-MAC `log group` + comment. The set still exists, but
    -- the drop is now per-MAC.
    local s = snap_one()
    s.profiles["3"].rules.blocked = true
    s.profiles["3"].rules.blockReason = "Paused"
    local nft = render.nft(s)
    assert.truthy(nft:find("ether saddr aa:bb:cc:11:22:33 log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:Paused\"", 1, true))
  end)

  -- #351: a connection-layer block to an extraBlocked host on HTTP/80 should
  -- still land on the block page — same UX as MAC-wide block, just scoped.
  it("DNATs HTTP/80 from a MAC to the block page when daddr ∈ extraBlocked ipset", function()
    local nft = render.nft(snap_one())
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr @eb_tiktok_com tcp dport 80 dnat ip to 127.0.0.1:8081",
      1, true))
  end)

  it("emits the block-page nat chain even when only extraBlocked applies (no MAC-wide block)", function()
    -- snap_one has extraBlocked but no MAC-wide block. The nat chain still
    -- needs to exist so the HTTP/80 dnat rule has a hook to live in.
    local nft = render.nft(snap_one())
    assert.truthy(nft:find("chain wifihaven_block_nat", 1, true))
    assert.truthy(nft:find("type nat hook prerouting priority dstnat", 1, true))
  end)

  it("does NOT emit the nat chain when neither MAC-wide nor extraBlocked applies", function()
    local s = snap_one()
    s.profiles["3"].rules.extraBlocked = {}
    local nft = render.nft(s)
    assert.is_nil(nft:find("wifihaven_block_nat", 1, true))
  end)

  -- #411: v6 block-page DNAT mirrors v4. uhttpd also binds [::1]:8081 so the
  -- redirect lands on a live listener (see install.sh). Each v4 DNAT line
  -- gets a parallel v6 sibling targeting the eb6_/bl6_ sets and ::1:8081.
  it("emits a v6 DNAT rule scoped to @blocked_macs (#411)", function()
    local s = snap_one()
    s.profiles["3"].rules.blocked = true
    s.profiles["3"].rules.blockReason = "Paused"
    local nft = render.nft(s)
    assert.truthy(nft:find(
      "ether saddr @blocked_macs ip6 daddr != ::1 tcp dport 80 dnat ip6 to ::1:8081",
      1, true))
  end)

  it("DNATs v6 HTTP/80 from a MAC to the block page when daddr ∈ eb6_<host> (#411)", function()
    local nft = render.nft(snap_one())
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip6 daddr @eb6_tiktok_com tcp dport 80 dnat ip6 to ::1:8081",
      1, true))
  end)

  -- HTTPS (port 443) is explicitly NOT redirected in either family — DNATing
  -- TLS to a local listener would produce certificate errors, not a block
  -- page. The filter chain drops it; the user sees a fast connection error,
  -- same UX as v4.
  it("does NOT emit a DNAT rule for HTTPS/443 in either family (#411)", function()
    local s = snap_one()
    s.profiles["3"].rules.blocked = true
    s.profiles["3"].rules.blockReason = "Paused"
    local nft = render.nft(s)
    assert.is_nil(nft:find("dport 443", 1, true))
  end)

end)

-- ── nflog drop-rule shape (#1122) ─────────────────────────────────────────
--
-- Per #1122, every drop rule in the filter chain must carry both
--   `log group 1` (so the agent's nflog tail receives the dropped packet)
--   `counter` (so `nft list ruleset` still shows packet counts for ops)
--   `drop`
--   `comment "wh_drop:<mac>:<reason>"` (so the agent can attribute the drop
--                                       back to a MAC and reason without a
--                                       second policy lookup)
--
-- The reason in the comment matches MacBlockReason.asString for the whole-MAC
-- block path (Paused/Schedule/TimeLimit/Manual/Unmanaged), or one of
-- `host` / `category:<id>` / `ip_only` for the per-host / per-category /
-- blockIpOnly paths.

describe("render.nft drop rules carry log group + counter + comment (#1122)", function()

  local function filter_chain_body(nft)
    local start = nft:find("chain wifihaven_block {", 1, true)
    if not start then return nil end
    local open  = nft:find("{", start, true)
    local close = nft:find("\n  }", open, true)
    return nft:sub(open + 1, close - 1)
  end

  it("per-MAC blocked rule emits log group + comment with MacBlockReason", function()
    local s = snap_one()
    s.profiles["3"].rules.blocked = true
    s.profiles["3"].rules.blockReason = "TimeLimit"
    local nft = render.nft(s)
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:TimeLimit\"",
      1, true))
    -- The family-agnostic @blocked_macs drop is gone — per-MAC rules supersede.
    assert.is_nil(nft:find("@blocked_macs drop", 1, true))
  end)

  it("blocked-with-extraAllowed per-MAC v4/v6 rules carry log group + comment", function()
    local s = snap_one()
    s.profiles["3"].rules.blocked      = true
    s.profiles["3"].rules.blockReason  = "Paused"
    s.profiles["3"].rules.extraAllowed = { "school.example" }
    local nft = render.nft(s)
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr != @ea_aa_bb_cc_11_22_33_school_example log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:Paused\"",
      1, true))
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip6 daddr != @ea6_aa_bb_cc_11_22_33_school_example log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:Paused\"",
      1, true))
  end)

  it("#1413: Paused MAC carves out an allowed app host but still drops other traffic", function()
    -- Prod miss (Kids/Math Academy, 2026-06): an allowed-mode app appeared
    -- blocked while the profile was paused. The whole-MAC drop for a Paused MAC
    -- with extraAllowed must carry `ip daddr != @ea_<m>_<allowed-host>` so the
    -- allowed app stays reachable, while general traffic (no ea_ carve-out)
    -- still drops. Reason-agnostic carve-out, locked here for the Paused reason.
    local s = snap_one()
    s.profiles["3"].rules.blocked      = true
    s.profiles["3"].rules.blockReason  = "Paused"
    s.profiles["3"].rules.extraAllowed = { "mathacademy.com" }
    local nft = render.nft(s)
    -- The per-MAC drop spares the allowed host via the ea_ exception (allow wins).
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr != @ea_aa_bb_cc_11_22_33_mathacademy_com log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:Paused\"",
      1, true))
    -- The MAC is pulled out of the family-agnostic @blocked_macs set (it has a
    -- per-MAC carve-out rule instead), so general traffic drops via that rule.
    assert.is_nil(nft:find("@blocked_macs drop", 1, true))
    -- No ea_ set is declared for an unrelated host, so non-allowed traffic has
    -- no carve-out and is dropped by the rule above.
    assert.is_nil(nft:find("@ea_aa_bb_cc_11_22_33_example_com", 1, true))
  end)

  it("MacBlockReason wire strings: Paused / Schedule / TimeLimit / Manual / Unmanaged", function()
    -- Pin the contract between MacBlockReason.asString in PolicyService and
    -- the strings render.lua emits. The agent's nflog parser depends on this.
    for _, reason in ipairs({ "Paused", "Schedule", "TimeLimit", "Manual", "Unmanaged" }) do
      local s = snap_one()
      s.profiles["3"].rules.blocked = true
      s.profiles["3"].rules.blockReason = reason
      local nft = render.nft(s)
      assert.truthy(
        nft:find("comment \"wh_drop:aa:bb:cc:11:22:33:" .. reason .. "\"", 1, true),
        "expected comment for reason=" .. reason)
    end
  end)

  it("every drop rule in wifihaven_block is preceded by `log group`", function()
    local s = snap_one()
    -- Force every kind of drop to appear.
    s.profiles["3"].rules.blocked      = true
    s.profiles["3"].rules.blockReason  = "Schedule"
    s.profiles["3"].rules.blockIpOnly  = true
    s.profiles["3"].rules.extraBlocked = { "tiktok.com" }
    s.profiles["3"].rules.blocklistIds = { "ads" }
    s.blocklists = { ads = { version = "v1", url = "/api/blocklists/ads" } }
    local nft  = render.nft(s)
    local body = filter_chain_body(nft)
    assert.truthy(body)
    -- For every `drop` token in the body, the surrounding line must contain
    -- `log group 1 counter drop`. We scan line-by-line so a missing `log
    -- group` on any drop fails the assertion.
    for line in body:gmatch("[^\n]+") do
      if line:find(" drop", 1, true) and not line:find("counter drop", 1, true) then
        assert(false, "drop without log group / counter: " .. line)
      end
    end
  end)

  it("eb_ drop comment is wh_drop:<mac>:host", function()
    local nft = render.nft(snap_one())
    assert.truthy(nft:find(
      "ip daddr @eb_tiktok_com log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:host\"",
      1, true))
  end)

  it("bl_ drop comment is wh_drop:<mac>:category:<id>", function()
    local s = snap_one()
    s.profiles["3"].rules.blocklistIds = { "ads" }
    s.blocklists = { ads = { version = "v1", url = "/api/blocklists/ads" } }
    local nft = render.nft(s)
    assert.truthy(nft:find(
      "ip daddr @bl_ads log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:category:ads\"",
      1, true))
  end)

  it("blockIpOnly drop comment is wh_drop:<mac>:ip_only", function()
    local s = snap_one()
    s.profiles["3"].rules.blockIpOnly = true
    local nft = render.nft(s)
    assert.truthy(nft:find(
      "ip daddr != @resolved_aa_bb_cc_11_22_33 log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:ip_only\"",
      1, true))
  end)
end)

-- ── nft syntax validation (skipped if `nft` binary unavailable) ───────────

describe("render.nft syntax validation", function()

  -- `nft -c` is not a pure parse: even in check mode it opens a netlink
  -- socket to validate against the live kernel, so it needs CAP_NET_ADMIN.
  -- A bare "is the binary on PATH?" guard therefore passes on an
  -- unprivileged host (e.g. the non-root `runner` user on GitHub Actions)
  -- where every `nft -c` then fails with EPERM — turning this into a false
  -- red. Probe with a trivial valid ruleset so we only assert when `nft -c`
  -- can actually check; otherwise skip, exactly as we do when nft is absent.
  -- os.execute returns a numeric status on lua5.1 and (boolean, "exit", code)
  -- on lua5.2+ — normalise both to a success boolean.
  local function shell_ok(cmd)
    local a = os.execute(cmd)
    return a == 0 or a == true
  end

  local function nft_checkable()
    if not shell_ok("command -v nft >/dev/null 2>&1") then
      return false, "nft binary not available on this host"
    end
    if not shell_ok(
      "printf 'table inet wh_probe_check {}\\n' | nft -c -f - >/dev/null 2>&1") then
      return false, "nft present but `nft -c` is not usable here (needs CAP_NET_ADMIN)"
    end
    return true
  end

  it("rendered output loads cleanly via `nft -c -f -` when a profile is blocked", function()
    local ok, why = nft_checkable()
    if not ok then
      pending(why)
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
      failureMode = "block-all",
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

  -- ── eb_hosts_by_mac / ea_hosts_by_mac population ──────────────────────────

  it("populates eb_hosts_by_mac from a profile's extraBlocked list", function()
    local s = snap_one()  -- profile 3 has extraBlocked = { "tiktok.com" }
    local nft_sets, blocked_macs, blocked_reason = {}, {}, {}
    local eb_hosts_by_mac, ea_hosts_by_mac = {}, {}
    render.update_shared(s, nft_sets, blocked_macs, blocked_reason,
                         eb_hosts_by_mac, ea_hosts_by_mac)
    assert.is_not_nil(eb_hosts_by_mac["aa:bb:cc:11:22:33"])
    assert.is_true(eb_hosts_by_mac["aa:bb:cc:11:22:33"]["tiktok.com"])
  end)

  it("populates bl_hosts_by_mac from blocklistIds when _blocklist_hosts is present (#594)", function()
    local s = snap_one()  -- profile 3 has blocklistIds = { "ads", "adult" }
    s._blocklist_hosts = {
      ads   = { "ad.doubleclick.net", "googleads.g.doubleclick.net" },
      adult = { "pornhub.com" },
    }
    local nft_sets, blocked_macs, blocked_reason = {}, {}, {}
    local eb_hosts_by_mac, ea_hosts_by_mac, bl_hosts_by_mac = {}, {}, {}
    render.update_shared(s, nft_sets, blocked_macs, blocked_reason,
                         eb_hosts_by_mac, ea_hosts_by_mac, bl_hosts_by_mac)
    -- Category hosts go into bl_hosts_by_mac tagged with the matching id.
    local bl = bl_hosts_by_mac["aa:bb:cc:11:22:33"]
    assert.is_not_nil(bl)
    assert.equal("ads",   bl["ad.doubleclick.net"])
    assert.equal("ads",   bl["googleads.g.doubleclick.net"])
    assert.equal("adult", bl["pornhub.com"])
    -- extraBlocked host from profile is in eb_hosts_by_mac, NOT bl_.
    local eb = eb_hosts_by_mac["aa:bb:cc:11:22:33"]
    assert.is_not_nil(eb)
    assert.is_true(eb["tiktok.com"])
    assert.is_nil(eb["ad.doubleclick.net"])
    assert.is_nil(bl["tiktok.com"])
  end)

  it("extraBlocked beats category when the same host appears in both (#594)", function()
    local s = snap_one()
    s.profiles["3"].rules.extraBlocked = { "shared.example" }
    s._blocklist_hosts = { ads = { "shared.example" } }
    local nft_sets, blocked_macs, blocked_reason = {}, {}, {}
    local eb_hosts_by_mac, ea_hosts_by_mac, bl_hosts_by_mac = {}, {}, {}
    render.update_shared(s, nft_sets, blocked_macs, blocked_reason,
                         eb_hosts_by_mac, ea_hosts_by_mac, bl_hosts_by_mac)
    assert.is_true(eb_hosts_by_mac["aa:bb:cc:11:22:33"]["shared.example"])
    -- bl_hosts_by_mac must NOT also contain this host: eb wins for classification.
    local bl = bl_hosts_by_mac["aa:bb:cc:11:22:33"] or {}
    assert.is_nil(bl["shared.example"])
  end)

  it("chooses a deterministic blocklist id when a host is in multiple lists (#594)", function()
    local s = snap_one()
    s.profiles["3"].rules.extraBlocked = {}
    s.profiles["3"].rules.blocklistIds = { "zz", "aa" }
    s._blocklist_hosts = { aa = { "dup.example" }, zz = { "dup.example" } }
    local nft_sets, blocked_macs, blocked_reason = {}, {}, {}
    local eb_hosts_by_mac, ea_hosts_by_mac, bl_hosts_by_mac = {}, {}, {}
    render.update_shared(s, nft_sets, blocked_macs, blocked_reason,
                         eb_hosts_by_mac, ea_hosts_by_mac, bl_hosts_by_mac)
    -- Lower id sorts first, so "aa" wins.
    assert.equal("aa", bl_hosts_by_mac["aa:bb:cc:11:22:33"]["dup.example"])
  end)

  it("populates ea_hosts_by_mac from a profile's extraAllowed list", function()
    local s = snap_one()
    s.profiles["3"].rules.extraAllowed = { "netflix.com", "youtube.com" }
    local nft_sets, blocked_macs, blocked_reason = {}, {}, {}
    local eb_hosts_by_mac, ea_hosts_by_mac = {}, {}
    render.update_shared(s, nft_sets, blocked_macs, blocked_reason,
                         eb_hosts_by_mac, ea_hosts_by_mac)
    local ea = ea_hosts_by_mac["aa:bb:cc:11:22:33"]
    assert.is_not_nil(ea)
    assert.is_true(ea["netflix.com"])
    assert.is_true(ea["youtube.com"])
  end)

  it("clears stale eb_hosts_by_mac entries on rebuild", function()
    local s = snap_one()
    local nft_sets, blocked_macs, blocked_reason = {}, {}, {}
    local eb_hosts_by_mac = { ["aa:bb:cc:11:22:33"] = { ["old.com"] = true } }
    local ea_hosts_by_mac = {}
    render.update_shared(s, nft_sets, blocked_macs, blocked_reason,
                         eb_hosts_by_mac, ea_hosts_by_mac)
    -- "old.com" should be gone; "tiktok.com" (from snap_one profile) should be present
    local eb = eb_hosts_by_mac["aa:bb:cc:11:22:33"]
    assert.is_nil(eb and eb["old.com"])
    assert.is_true(eb and eb["tiktok.com"])
  end)

  it("does not populate eb_hosts_by_mac for a MAC that has no extraBlocked or blocklistIds", function()
    local s = snap_one()
    s.profiles["3"].rules.extraBlocked = {}
    s.profiles["3"].rules.blocklistIds = {}
    local nft_sets, blocked_macs, blocked_reason = {}, {}, {}
    local eb_hosts_by_mac, ea_hosts_by_mac = {}, {}
    render.update_shared(s, nft_sets, blocked_macs, blocked_reason,
                         eb_hosts_by_mac, ea_hosts_by_mac)
    assert.is_nil(eb_hosts_by_mac["aa:bb:cc:11:22:33"])
  end)

  it("accepts nil eb_hosts_by_mac / ea_hosts_by_mac without error (backwards compat)", function()
    assert.has_no.errors(function()
      render.update_shared(snap_one(), {}, {}, {}, nil, nil)
    end)
  end)

end)

-- ── blocklist enforcement (#352) ─────────────────────────────────────────────
--
-- Category blocklists are enforced at the connection layer (not DNS) via
-- nftables ipsets. dnsmasq populates bl_<id> ipsets at resolve time via
-- ipset= directives; per-(MAC, blocklistId) drop rules gate on those sets.

describe("render blocklist enforcement (#352)", function()

  -- Snapshot with one blocklist and two profiles:
  --   profile 3 (kids) has blocklistIds = {"test_ads"}
  --   profile 1 (adults) has blocklistIds = {}
  local function snap_bl()
    return {
      etag        = "sha256:abc123",
      generatedAt = "2026-05-14T14:00:00Z",
      devices = {
        ["aa:bb:cc:11:22:33"] = { profileId = 3, name = "kid-ipad",    rules = nil },
        ["de:ad:be:ef:00:01"] = { profileId = 1, name = "parent-phone", rules = nil },
      },
      profiles = {
        ["3"] = {
          name = "kids",
          rules = {
            blocked      = false,
            blockReason  = nil,
            extraBlocked = {},
            extraAllowed = {},
            blocklistIds = { "test_ads" },
            blockIpOnly  = false,
          },
          failureMode = "block-all",
        },
        ["1"] = {
          name = "adults",
          rules = {
            blocked      = false,
            blockReason  = nil,
            extraBlocked = {},
            extraAllowed = {},
            blocklistIds = {},
            blockIpOnly  = false,
          },
          failureMode = "last-known-good",
        },
      },
      blocklists = {
        test_ads = { version = "abc123", url = "http://api/api/blocklists/test_ads" },
      },
      _blocklist_hosts = {
        test_ads = { "adserver.example.com", "doubleclick.net" },
      },
    }
  end

  -- ── nft set declarations ─────────────────────────────────────────────────

  it("declares set bl_<id> with type ipv4_addr + dynamic,timeout for each id in blocklists", function()
    local nft  = render.nft(snap_bl())
    local pos  = nft:find("set bl_test_ads", 1, true)
    assert.truthy(pos, "expected 'set bl_test_ads' in nft output")
    local blk  = nft:sub(pos, pos + 200)
    assert.truthy(blk:find("type ipv4_addr", 1, true))
    assert.truthy(blk:find("flags dynamic,timeout", 1, true))
    assert.truthy(blk:find("timeout 1h", 1, true))
  end)

  -- #392: v6 sibling for category blocklists.
  it("declares set bl6_<id> with type ipv6_addr + dynamic,timeout (#392)", function()
    local nft  = render.nft(snap_bl())
    local pos  = nft:find("set bl6_test_ads", 1, true)
    assert.truthy(pos, "expected 'set bl6_test_ads' in nft output")
    local blk  = nft:sub(pos, pos + 200)
    assert.truthy(blk:find("type ipv6_addr", 1, true))
    assert.truthy(blk:find("flags dynamic,timeout", 1, true))
    assert.truthy(blk:find("timeout 1h", 1, true))
  end)

  it("emits set declaration even when no device references the blocklist id", function()
    -- Profile 1 (adults) has no blocklistIds, but the set must still be
    -- declared because dnsmasq ipset= callbacks need the set to exist.
    local s = snap_bl()
    -- Remove kid device so only adults (who don't reference test_ads) remain.
    s.devices = { ["de:ad:be:ef:00:01"] = { profileId = 1, name = "parent-phone", rules = nil } }
    local nft = render.nft(s)
    assert.truthy(nft:find("set bl_test_ads", 1, true),
      "set must be declared even when no profile references it")
  end)

  it("declares exactly one set per id (no duplicates) when multiple MACs share the id", function()
    local s  = snap_bl()
    -- Add a second kid device under same profile.
    s.devices["11:22:33:44:55:66"] = { profileId = 3, name = "kid-phone", rules = nil }
    local nft = render.nft(s)
    local _, cnt = nft:gsub("set bl_test_ads {", "")
    assert.equal(1, cnt, "set declaration must appear exactly once")
  end)

  -- ── dnsmasq nftset= directives (post-#394 nftset/ipset migration) ───────

  it("emits combined v4+v6 nftset=/<host>/... for each host in _blocklist_hosts[id] (#392)", function()
    local conf = render.dnsmasq(snap_bl())
    assert.truthy(conf:find(
      "nftset=/adserver.example.com/4#inet#wifihaven#bl_test_ads,6#inet#wifihaven#bl6_test_ads",
      1, true))
    assert.truthy(conf:find(
      "nftset=/doubleclick.net/4#inet#wifihaven#bl_test_ads,6#inet#wifihaven#bl6_test_ads",
      1, true))
  end)

  it("emits one nftset= line per (host, id) when the same host appears in two blocklists", function()
    local s = snap_bl()
    s.blocklists["test_social"] = { version = "v1", url = "http://api/api/blocklists/test_social" }
    s._blocklist_hosts["test_social"] = { "doubleclick.net", "facebook.com" }
    local conf = render.dnsmasq(s)
    assert.truthy(conf:find(
      "nftset=/doubleclick.net/4#inet#wifihaven#bl_test_ads,6#inet#wifihaven#bl6_test_ads",
      1, true))
    assert.truthy(conf:find(
      "nftset=/doubleclick.net/4#inet#wifihaven#bl_test_social,6#inet#wifihaven#bl6_test_social",
      1, true))
  end)

  it("emits no nftset= lines when _blocklist_hosts is absent or empty", function()
    local s = snap_bl()
    s._blocklist_hosts = nil
    local conf = render.dnsmasq(s)
    assert.is_nil(conf:find("bl_test_ads", 1, true))
    assert.is_nil(conf:find("bl6_test_ads", 1, true))
  end)

  -- ── nft drop rules ───────────────────────────────────────────────────────

  it("emits 'ether saddr <mac> ip daddr @bl_<id> drop' for each (mac, blocklistId) pair", function()
    local nft = render.nft(snap_bl())
    -- Only kid mac references test_ads; parent does not.
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr @bl_test_ads log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:category:test_ads\"", 1, true))
  end)

  -- #392: v6 drop rule mirrors the v4 one for blocklists.
  it("emits v6 drop 'ether saddr <mac> ip6 daddr @bl6_<id> drop' (#392)", function()
    local nft = render.nft(snap_bl())
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip6 daddr @bl6_test_ads log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:category:test_ads\"", 1, true))
  end)

  it("does NOT emit v6 drop for MACs whose profile has no blocklistIds (#392)", function()
    local nft = render.nft(snap_bl())
    assert.is_nil(nft:find(
      "ether saddr de:ad:be:ef:00:01 ip6 daddr @bl6_test_ads", 1, true))
  end)

  it("does NOT emit drop for MACs whose profile has blocklistIds = {}", function()
    local nft = render.nft(snap_bl())
    assert.is_nil(nft:find(
      "ether saddr de:ad:be:ef:00:01 ip daddr @bl_test_ads log group 1 counter drop comment \"wh_drop:de:ad:be:ef:00:01:category:test_ads\"", 1, true),
      "adults MAC must not get a bl_test_ads drop rule")
  end)

  it("two MACs sharing the same blocklistId produce ONE set, TWO MAC-scoped drops", function()
    local s = snap_bl()
    s.devices["11:22:33:44:55:66"] = { profileId = 3, name = "kid-phone", rules = nil }
    local nft = render.nft(s)
    local _, set_count = nft:gsub("set bl_test_ads {", "")
    assert.equal(1, set_count, "exactly one set declaration")
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr @bl_test_ads log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:category:test_ads\"", 1, true))
    assert.truthy(nft:find(
      "ether saddr 11:22:33:44:55:66 ip daddr @bl_test_ads log group 1 counter drop comment \"wh_drop:11:22:33:44:55:66:category:test_ads\"", 1, true))
  end)

  it("blocklistId referenced by profile but absent from snapshot.blocklists → silently skipped", function()
    local s = snap_bl()
    -- Profile references "missing_id" which is not in snapshot.blocklists.
    s.profiles["3"].rules.blocklistIds = { "test_ads", "missing_id" }
    -- No entry for missing_id in blocklists.
    local ok, nft = pcall(render.nft, s)
    assert.is_true(ok, "render must not error on missing blocklistId")
    -- No set or drop for missing_id.
    assert.is_nil(nft:find("bl_missing_id", 1, true))
  end)

  it("emits no bl_ or bl6_ sets or drop rules when snapshot.blocklists is empty", function()
    local s = snap_one()  -- snap_one has blocklists = {}
    local nft = render.nft(s)
    assert.is_nil(nft:find("set bl_", 1, true))
    assert.is_nil(nft:find("@bl_", 1, true))
    assert.is_nil(nft:find("set bl6_", 1, true))
    assert.is_nil(nft:find("@bl6_", 1, true))
  end)

  -- ── DNAT HTTP/80 for blocklist hits (mirrors #351 extraBlocked pattern) ──

  it("DNATs HTTP/80 from a MAC to the block page when daddr ∈ bl_<id>", function()
    local nft = render.nft(snap_bl())
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr @bl_test_ads tcp dport 80 dnat ip to 127.0.0.1:8081",
      1, true))
  end)

  it("DNATs v6 HTTP/80 from a MAC to the block page when daddr ∈ bl6_<id> (#411)", function()
    local nft = render.nft(snap_bl())
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip6 daddr @bl6_test_ads tcp dport 80 dnat ip6 to ::1:8081",
      1, true))
  end)

  it("emits the nat chain when only blocklist rules apply (no MAC-wide block, no extraBlocked)", function()
    local nft = render.nft(snap_bl())
    assert.truthy(nft:find("chain wifihaven_block_nat", 1, true))
    assert.truthy(nft:find("type nat hook prerouting priority dstnat", 1, true))
  end)

  it("does NOT emit DNAT for adults MAC (adults have no blocklistIds)", function()
    local nft = render.nft(snap_bl())
    assert.is_nil(nft:find(
      "ether saddr de:ad:be:ef:00:01 ip daddr @bl_test_ads tcp dport 80", 1, true))
  end)

  it("sanitizes id containing dots/dashes in set name", function()
    local s = snap_bl()
    s.blocklists["test.cat-1"] = { version = "v1", url = "http://api/api/blocklists/test.cat-1" }
    s._blocklist_hosts["test.cat-1"] = { "badhost.com" }
    s.profiles["3"].rules.blocklistIds = { "test_ads", "test.cat-1" }
    local nft = render.nft(s)
    assert.truthy(nft:find("set bl_test_cat_1", 1, true),
      "dots and dashes in id must be sanitized to underscores")
  end)

end)

-- ── blockIpOnly enforcement (#353) ───────────────────────────────────────────
--
-- Per-MAC "must use DNS" enforcement: when a device has BlockRules.blockIpOnly
-- true, forward-chain drops any v4/v6 daddr that is NOT in this MAC's
-- resolved_<mac> / resolved6_<mac> set. The wifihaven-dns-tail sidecar
-- populates the sets at A/AAAA response time by tailing dnsmasq's query log
-- and mapping the per-reply client IP back to a MAC via /tmp/dhcp.leases —
-- every hostname the device resolves through our resolver lands in its set;
-- everything else (DoH, DoT, hard-coded IPs) drops. dnsmasq's `nftset=`
-- directive is NOT used for this set: dnsmasq 2.91 rejects the `tag:` prefix
-- needed for per-MAC scoping (#496/#505), and cross-MAC pollination would
-- defeat the feature.
--
-- The drop is order-independent w.r.t. extraBlocked / blocklist drops: all
-- three are terminal `drop` rules under a `policy accept` chain, so the
-- packet is dropped iff *any* matches. A host in extraBlocked still drops
-- even though its IP also lands in resolved_<mac>.

-- ---------------------------------------------------------------------------
-- render.blocklist_member_index (#1348)
--
-- bl_<id>/bl6_<id> sets are populated at DNS resolve time by dnsmasq's
-- nftset=/<member>/...#bl_<id> directive, which misses a directly-queried CNAME
-- target landing on a fresh CDN-anycast IP (silent filter bypass). dns-tail
-- closes that gap, but it only knows which bl_ sets EXIST — not their
-- MEMBERSHIP. This index exports host → bl_set so the dns-tail populator can
-- match a member resolved through the #1344 CNAME-alias map. It is derived from
-- the same snapshot._blocklist_hosts that drives the nftset= directives, so the
-- set names line up exactly.
-- ---------------------------------------------------------------------------

describe("render.blocklist_member_index (#1348)", function()
  it("emits <host>\\t<bl_set>\\t<bl6_set> for each member of each blocklist", function()
    local s = {
      blocklists = {
        test_ads = { version = "v1", url = "/api/blocklists/test_ads" },
      },
      _blocklist_hosts = {
        test_ads = { "adserver.example.com", "doubleclick.net" },
      },
    }
    local idx = render.blocklist_member_index(s)
    assert.truthy(idx:find("adserver.example.com\tbl_test_ads\tbl6_test_ads", 1, true))
    assert.truthy(idx:find("doubleclick.net\tbl_test_ads\tbl6_test_ads", 1, true))
  end)

  it("sanitizes the id into the set name the same way render.nft declares it", function()
    local s = {
      blocklists       = { ["test.cat-1"] = { version = "v1", url = "/x" } },
      _blocklist_hosts = { ["test.cat-1"] = { "badhost.com" } },
    }
    local idx = render.blocklist_member_index(s)
    -- bl_sanitize replaces ., :, -, whitespace with _.
    assert.truthy(idx:find("badhost.com\tbl_test_cat_1\tbl6_test_cat_1", 1, true))
  end)

  it("returns an empty string when there are no blocklist members", function()
    assert.equal("", render.blocklist_member_index({ blocklists = {} }))
    assert.equal("", render.blocklist_member_index({}))
  end)
end)

describe("render blockIpOnly enforcement (#353)", function()

  local function snap_bio()
    return {
      etag        = "sha256:abc123",
      generatedAt = "2026-05-15T14:00:00Z",
      devices = {
        ["aa:bb:cc:11:22:33"] = { profileId = 3, name = "kid-ipad", rules = nil },
      },
      profiles = {
        ["3"] = {
          name = "kids-strict",
          rules = {
            blocked      = false,
            blockReason  = nil,
            extraBlocked = {},
            extraAllowed = {},
            blocklistIds = {},
            blockIpOnly  = true,
          },
          failureMode = "block-all",
        },
      },
      blocklists = {},
    }
  end

  -- ── dnsmasq side ────────────────────────────────────────────────────────

  it("does NOT add a resolvetag tag to the dhcp-host line (#505: dns-tail populates)", function()
    local conf = render.dnsmasq(snap_bio())
    -- dhcp-host carries only the profile tag; resolvetag_ is never emitted.
    assert.truthy(conf:find("dhcp-host=aa:bb:cc:11:22:33,set:profile3\n", 1, true)
               or conf:match("dhcp-host=aa:bb:cc:11:22:33,set:profile3$"))
    assert.is_nil(conf:find("resolvetag_", 1, true))
  end)

  it("does NOT emit any nftset= directive for resolved_/resolved6_ (#505)", function()
    local conf = render.dnsmasq(snap_bio())
    -- The populator is dns-tail, not dnsmasq. dnsmasq 2.91 rejects the
    -- `tag:` prefix needed for per-MAC scoping (#496), so the directive is
    -- omitted entirely — any reference to resolved_/resolved6_ here would
    -- regress the bug.
    assert.is_nil(conf:find("nftset=tag:", 1, true))
    assert.is_nil(conf:find("resolved_aa_bb_cc_11_22_33", 1, true))
    assert.is_nil(conf:find("resolved6_aa_bb_cc_11_22_33", 1, true))
  end)

  it("does NOT emit resolvetag / resolved nftset directives when blockIpOnly=false", function()
    local s = snap_bio()
    s.profiles["3"].rules.blockIpOnly = false
    local conf = render.dnsmasq(s)
    assert.is_nil(conf:find("resolvetag_", 1, true))
    assert.is_nil(conf:find("resolved_", 1, true))
    assert.is_nil(conf:find("resolved6_", 1, true))
    -- And the dhcp-host line carries only the profile tag.
    assert.truthy(conf:find("dhcp-host=aa:bb:cc:11:22:33,set:profile3\n", 1, true)
               or conf:match("dhcp-host=aa:bb:cc:11:22:33,set:profile3$"))
  end)

  it("device-override blockIpOnly=true takes effect even when profile is false", function()
    local s = snap_bio()
    s.profiles["3"].rules.blockIpOnly = false
    s.devices["aa:bb:cc:11:22:33"].rules = {
      blocked = false, blockReason = nil,
      extraBlocked = {}, extraAllowed = {}, blocklistIds = {}, blockIpOnly = true,
    }
    local conf = render.dnsmasq(s)
    -- dhcp-host still carries only the profile tag; population still comes
    -- from dns-tail, not dnsmasq. The nft side (declared sets + drop rule)
    -- is what actually enforces blockIpOnly — see the nft-side tests below.
    assert.truthy(conf:find("dhcp-host=aa:bb:cc:11:22:33,set:profile3\n", 1, true)
               or conf:match("dhcp-host=aa:bb:cc:11:22:33,set:profile3$"))
    assert.is_nil(conf:find("resolvetag_", 1, true))
    assert.is_nil(conf:find("nftset=tag:", 1, true))
  end)

  -- ── nft side: per-MAC sets ──────────────────────────────────────────────

  it("declares set resolved_<sanmac> (ipv4_addr, dynamic, 5m timeout)", function()
    local nft = render.nft(snap_bio())
    local pos = nft:find("set resolved_aa_bb_cc_11_22_33", 1, true)
    assert.truthy(pos)
    local blk = nft:sub(pos, pos + 200)
    assert.truthy(blk:find("type ipv4_addr", 1, true))
    assert.truthy(blk:find("flags dynamic,timeout", 1, true))
    assert.truthy(blk:find("timeout 5m", 1, true))
  end)

  it("declares set resolved6_<sanmac> (ipv6_addr, dynamic, 5m timeout)", function()
    local nft = render.nft(snap_bio())
    local pos = nft:find("set resolved6_aa_bb_cc_11_22_33", 1, true)
    assert.truthy(pos)
    local blk = nft:sub(pos, pos + 200)
    assert.truthy(blk:find("type ipv6_addr", 1, true))
    assert.truthy(blk:find("flags dynamic,timeout", 1, true))
    assert.truthy(blk:find("timeout 5m", 1, true))
  end)

  -- ── nft side: drop rules ────────────────────────────────────────────────

  it("emits `ether saddr <mac> ip daddr != @resolved_<mac> drop` in wifihaven_block", function()
    local nft = render.nft(snap_bio())
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr != @resolved_aa_bb_cc_11_22_33 log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:ip_only\"",
      1, true))
  end)

  it("emits the v6 sibling drop `ip6 daddr != @resolved6_<mac> drop`", function()
    local nft = render.nft(snap_bio())
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip6 daddr != @resolved6_aa_bb_cc_11_22_33 log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:ip_only\"",
      1, true))
  end)

  -- ── nft side: block-page DNAT for un-resolved daddrs ────────────────────

  it("DNATs v4 HTTP/80 to the block page for daddrs NOT in resolved_<mac>", function()
    local nft = render.nft(snap_bio())
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr != @resolved_aa_bb_cc_11_22_33 tcp dport 80 dnat ip to 127.0.0.1:8081",
      1, true))
  end)

  it("DNATs v6 HTTP/80 to the block page for daddrs NOT in resolved6_<mac> (#411)", function()
    local nft = render.nft(snap_bio())
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip6 daddr != ::1 ip6 daddr != @resolved6_aa_bb_cc_11_22_33 tcp dport 80 dnat ip6 to ::1:8081",
      1, true))
  end)

  -- ── nft side: absence when blockIpOnly=false ────────────────────────────

  it("emits no resolved_/resolved6_ sets, drops, or DNAT when no device has blockIpOnly", function()
    local s = snap_bio()
    s.profiles["3"].rules.blockIpOnly = false
    local nft = render.nft(s)
    assert.is_nil(nft:find("set resolved_", 1, true))
    assert.is_nil(nft:find("set resolved6_", 1, true))
    assert.is_nil(nft:find("@resolved_", 1, true))
    assert.is_nil(nft:find("@resolved6_", 1, true))
  end)

  -- ── scoping: per-MAC, not profile-wide-leak ─────────────────────────────

  it("device-override blockIpOnly=true scopes the rules to that MAC only", function()
    local s = snap_bio()
    -- Profile flips OFF so the only thing enabling blockIpOnly is the override.
    s.profiles["3"].rules.blockIpOnly = false
    s.devices["11:22:33:44:55:66"] = { profileId = 3, name = "kid-phone", rules = nil }
    s.devices["aa:bb:cc:11:22:33"].rules = {
      blocked = false, blockReason = nil,
      extraBlocked = {}, extraAllowed = {}, blocklistIds = {}, blockIpOnly = true,
    }
    local nft = render.nft(s)
    -- Override MAC has the rule.
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr != @resolved_aa_bb_cc_11_22_33 log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:ip_only\"",
      1, true))
    -- Sibling under same profile must NOT inherit.
    assert.is_nil(nft:find(
      "ether saddr 11:22:33:44:55:66 ip daddr != @resolved_11_22_33_44_55_66 log group 1 counter drop comment \"wh_drop:11:22:33:44:55:66:ip_only\"",
      1, true))
    assert.is_nil(nft:find("set resolved_11_22_33_44_55_66", 1, true))
  end)

  it("two devices both blockIpOnly=true → two separate resolved_<mac> sets and drop pairs", function()
    local s = snap_bio()
    s.devices["11:22:33:44:55:66"] = { profileId = 3, name = "kid-phone", rules = nil }
    local nft = render.nft(s)
    assert.truthy(nft:find("set resolved_aa_bb_cc_11_22_33",  1, true))
    assert.truthy(nft:find("set resolved_11_22_33_44_55_66",  1, true))
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr != @resolved_aa_bb_cc_11_22_33 log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:ip_only\"", 1, true))
    assert.truthy(nft:find(
      "ether saddr 11:22:33:44:55:66 ip daddr != @resolved_11_22_33_44_55_66 log group 1 counter drop comment \"wh_drop:11:22:33:44:55:66:ip_only\"", 1, true))
  end)

  -- ── failover allow-all suppression (mirrors eb_/bl_ pattern) ────────────

  it("suppresses blockIpOnly drops + DNAT for allow-all-failover MACs (poll_failed=true)", function()
    local s = snap_bio()
    s.profiles["3"].failureMode = "allow-all"
    local nft = render.nft(s, { poll_failed = true })
    -- Set declarations are still emitted (cheap; population is a no-op since
    -- the dnsmasq tag is still wired). But forward-chain drops and DNAT for
    -- these MACs must be gone.
    assert.is_nil(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr != @resolved_aa_bb_cc_11_22_33 log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:ip_only\"",
      1, true))
    assert.is_nil(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip6 daddr != @resolved6_aa_bb_cc_11_22_33 log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:ip_only\"",
      1, true))
    assert.is_nil(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr != @resolved_aa_bb_cc_11_22_33 tcp dport 80",
      1, true))
  end)

  -- ── composition with extraBlocked: independent drops, no rescue ─────────

  it("extraBlocked drop still fires for a host the device resolved via DNS (blockIpOnly does not 'rescue')", function()
    local s = snap_bio()
    s.profiles["3"].rules.extraBlocked = { "tiktok.com" }
    local nft = render.nft(s)
    -- Both drops emitted; nft chain policy is accept with independent drop
    -- rules, so a packet matching the eb_ rule is dropped regardless of the
    -- blockIpOnly rule's predicate.
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr @eb_tiktok_com log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:host\"", 1, true))
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr != @resolved_aa_bb_cc_11_22_33 log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:ip_only\"",
      1, true))
  end)

end)

-- ── extraAllowed per-(MAC, host) exception (#421) ───────────────────────────
--
-- extraAllowed beats extraBlocked beats blocklists. Each eb_/bl_ drop rule
-- for MAC m carries `ip daddr != @ea_<m>_<a>` clauses (one per a ∈
-- extraAllowed(m)) so a hit in any ea_ set for that MAC suppresses the drop.
-- Scoping is per-(MAC, host): a host can be allowed for MAC A and blocked
-- for MAC B independently. blockIpOnly is NOT suppressed by ea sets — its
-- composition is via DNS resolution landing the IP in resolved_<m>.

describe("render extraAllowed enforcement (#421)", function()

  -- Snapshot: kid (profile 3) has extraBlocked={"tiktok.com"} and
  -- extraAllowed={"music.tiktok.com"} so the blocked-domain drop has an
  -- in-tree carve-out. Adult (profile 1) has nothing.
  local function snap_ea()
    return {
      etag        = "sha256:abc123",
      generatedAt = "2026-05-15T14:00:00Z",
      devices = {
        ["aa:bb:cc:11:22:33"] = { profileId = 3, name = "kid-ipad",     rules = nil },
        ["de:ad:be:ef:00:01"] = { profileId = 1, name = "parent-phone", rules = nil },
      },
      profiles = {
        ["3"] = {
          name = "kids",
          rules = {
            blocked      = false,
            blockReason  = nil,
            extraBlocked = { "tiktok.com" },
            extraAllowed = { "music.tiktok.com" },
            blocklistIds = { "test_ads" },
            blockIpOnly  = false,
          },
          failureMode = "block-all",
        },
        ["1"] = {
          name = "adults",
          rules = {
            blocked = false, blockReason = nil,
            extraBlocked = { "tiktok.com" },
            extraAllowed = {},
            blocklistIds = {},
            blockIpOnly  = false,
          },
          failureMode = "last-known-good",
        },
      },
      blocklists = {
        test_ads = { version = "v1", url = "http://api/api/blocklists/test_ads" },
      },
      _blocklist_hosts = { test_ads = { "adserver.example.com" } },
    }
  end

  -- ── dnsmasq side ────────────────────────────────────────────────────────

  -- #496: dnsmasq 2.91's nftset= parser rejects a `tag:` prefix, so the
  -- ea_ populator must NOT be tag-scoped. The MAC scoping lives in the
  -- nft rule (`ether saddr <mac> ... != @ea_<mac>_<host>`) instead.
  it("does NOT append eatag_ to any dhcp-host line", function()
    local conf = render.dnsmasq(snap_ea())
    assert.is_nil(conf:find("eatag_", 1, true))
    -- The MAC with extraAllowed still gets its profile tag, just not an eatag.
    assert.truthy(conf:find("dhcp-host=aa:bb:cc:11:22:33,set:profile3\n", 1, true)
               or conf:match("dhcp-host=aa:bb:cc:11:22:33,set:profile3$"))
  end)

  it("does NOT append eatag_ to MACs with empty extraAllowed", function()
    local conf = render.dnsmasq(snap_ea())
    assert.truthy(conf:find("dhcp-host=de:ad:be:ef:00:01,set:profile1\n", 1, true)
               or conf:match("dhcp-host=de:ad:be:ef:00:01,set:profile1$"))
  end)

  it("emits untagged nftset= populator for the per-(MAC, host) ea_ / ea6_ sets", function()
    local conf = render.dnsmasq(snap_ea())
    assert.truthy(conf:find(
      "nftset=/music.tiktok.com/4#inet#wifihaven#ea_aa_bb_cc_11_22_33_music_tiktok_com,6#inet#wifihaven#ea6_aa_bb_cc_11_22_33_music_tiktok_com",
      1, true))
    -- Regression guard: never emit the broken tag:-scoped form (#496).
    assert.is_nil(conf:find("nftset=tag:", 1, true))
  end)

  it("emits one nftset= populator per (mac, host) when a MAC has multiple extraAllowed hosts", function()
    local s = snap_ea()
    s.profiles["3"].rules.extraAllowed = { "music.tiktok.com", "khanacademy.org" }
    local conf = render.dnsmasq(s)
    assert.truthy(conf:find(
      "nftset=/music.tiktok.com/4#inet#wifihaven#ea_aa_bb_cc_11_22_33_music_tiktok_com",
      1, true))
    assert.truthy(conf:find(
      "nftset=/khanacademy.org/4#inet#wifihaven#ea_aa_bb_cc_11_22_33_khanacademy_org",
      1, true))
  end)

  it("does NOT emit ea_ nftsets when no device has extraAllowed", function()
    local s = snap_ea()
    s.profiles["3"].rules.extraAllowed = {}
    local conf = render.dnsmasq(s)
    assert.is_nil(conf:find("eatag_", 1, true))
    assert.is_nil(conf:find("#wifihaven#ea_", 1, true))
  end)

  it("device-override extraAllowed scopes the ea populator to that MAC only", function()
    local s = snap_ea()
    s.profiles["3"].rules.extraAllowed = {}
    s.devices["11:22:33:44:55:66"] = { profileId = 3, name = "kid-phone", rules = nil }
    s.devices["aa:bb:cc:11:22:33"].rules = {
      blocked = false, blockReason = nil,
      extraBlocked = { "tiktok.com" }, extraAllowed = { "music.tiktok.com" },
      blocklistIds = {}, blockIpOnly = false,
    }
    local conf = render.dnsmasq(s)
    -- Override MAC gets the populator under its own per-(MAC, host) set name.
    assert.truthy(conf:find(
      "nftset=/music.tiktok.com/4#inet#wifihaven#ea_aa_bb_cc_11_22_33_music_tiktok_com",
      1, true))
    -- Sibling under same profile must NOT have a populator with its own set.
    assert.is_nil(conf:find("ea_11_22_33_44_55_66", 1, true))
  end)

  -- ── nft side: set declarations ──────────────────────────────────────────

  it("declares set ea_<sanmac>_<sanhost> (ipv4_addr, dynamic, 1h timeout)", function()
    local nft = render.nft(snap_ea())
    local pos = nft:find("set ea_aa_bb_cc_11_22_33_music_tiktok_com", 1, true)
    assert.truthy(pos)
    local blk = nft:sub(pos, pos + 200)
    assert.truthy(blk:find("type ipv4_addr", 1, true))
    assert.truthy(blk:find("flags dynamic,timeout", 1, true))
    assert.truthy(blk:find("timeout 1h", 1, true))
  end)

  it("declares set ea6_<sanmac>_<sanhost> (ipv6_addr, dynamic, 1h timeout)", function()
    local nft = render.nft(snap_ea())
    local pos = nft:find("set ea6_aa_bb_cc_11_22_33_music_tiktok_com", 1, true)
    assert.truthy(pos)
    local blk = nft:sub(pos, pos + 200)
    assert.truthy(blk:find("type ipv6_addr", 1, true))
    assert.truthy(blk:find("flags dynamic,timeout", 1, true))
    assert.truthy(blk:find("timeout 1h", 1, true))
  end)

  it("declares no ea_/ea6_ sets when no device has extraAllowed", function()
    local s = snap_ea()
    s.profiles["3"].rules.extraAllowed = {}
    local nft = render.nft(s)
    assert.is_nil(nft:find("set ea_", 1, true))
    assert.is_nil(nft:find("set ea6_", 1, true))
  end)

  -- ── nft side: drop predicate gets `ip daddr != @ea_...` suffix ──────────

  it("appends `ip daddr != @ea_<m>_<a>` to the eb_<host> drop for MAC m", function()
    local nft = render.nft(snap_ea())
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr @eb_tiktok_com ip daddr != @ea_aa_bb_cc_11_22_33_music_tiktok_com log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:host\"",
      1, true))
  end)

  it("appends `ip6 daddr != @ea6_<m>_<a>` to the eb6_<host> drop for MAC m", function()
    local nft = render.nft(snap_ea())
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip6 daddr @eb6_tiktok_com ip6 daddr != @ea6_aa_bb_cc_11_22_33_music_tiktok_com log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:host\"",
      1, true))
  end)

  it("appends `ip daddr != @ea_<m>_<a>` to the bl_<id> drop for MAC m", function()
    local nft = render.nft(snap_ea())
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr @bl_test_ads ip daddr != @ea_aa_bb_cc_11_22_33_music_tiktok_com log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:category:test_ads\"",
      1, true))
  end)

  it("appends `ip6 daddr != @ea6_<m>_<a>` to the bl6_<id> drop for MAC m", function()
    local nft = render.nft(snap_ea())
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip6 daddr @bl6_test_ads ip6 daddr != @ea6_aa_bb_cc_11_22_33_music_tiktok_com log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:category:test_ads\"",
      1, true))
  end)

  it("appends one `ip daddr != @ea_...` clause per extraAllowed host (multiple hosts)", function()
    local s = snap_ea()
    s.profiles["3"].rules.extraAllowed = { "music.tiktok.com", "khanacademy.org" }
    local nft = render.nft(s)
    -- Both clauses present in the same eb_ drop rule, in sorted order.
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr @eb_tiktok_com ip daddr != @ea_aa_bb_cc_11_22_33_khanacademy_org ip daddr != @ea_aa_bb_cc_11_22_33_music_tiktok_com log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:host\"",
      1, true))
  end)

  -- Multi-MAC scoping: extraAllowed for MAC A must NOT carve out MAC B's drops.
  it("does NOT append ea suffix to a different MAC's drop rule (per-MAC scoping)", function()
    local nft = render.nft(snap_ea())
    -- Adult MAC has the same eb_tiktok_com drop but extraAllowed={}, so no
    -- ea_ suffix on its rule.
    assert.truthy(nft:find(
      "ether saddr de:ad:be:ef:00:01 ip daddr @eb_tiktok_com log group 1 counter drop comment \"wh_drop:de:ad:be:ef:00:01:host\"", 1, true))
    -- And specifically not carrying the kid's ea set.
    assert.is_nil(nft:find(
      "ether saddr de:ad:be:ef:00:01 ip daddr @eb_tiktok_com ip daddr != @ea_aa_bb_cc_11_22_33_music_tiktok_com",
      1, true))
  end)

  it("leaves drops without ea suffix when MAC has empty extraAllowed", function()
    local s = snap_ea()
    s.profiles["3"].rules.extraAllowed = {}
    local nft = render.nft(s)
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr @eb_tiktok_com log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:host\"", 1, true))
  end)

  -- ── DNAT chain: same suffix applied ─────────────────────────────────────

  it("appends `ip daddr != @ea_<m>_<a>` to the eb_<host> DNAT for MAC m", function()
    local nft = render.nft(snap_ea())
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr @eb_tiktok_com ip daddr != @ea_aa_bb_cc_11_22_33_music_tiktok_com tcp dport 80 dnat ip to 127.0.0.1:8081",
      1, true))
  end)

  it("appends `ip6 daddr != @ea6_<m>_<a>` to the eb6_<host> DNAT for MAC m", function()
    local nft = render.nft(snap_ea())
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip6 daddr @eb6_tiktok_com ip6 daddr != @ea6_aa_bb_cc_11_22_33_music_tiktok_com tcp dport 80 dnat ip6 to ::1:8081",
      1, true))
  end)

  it("appends `ip daddr != @ea_<m>_<a>` to the bl_<id> DNAT for MAC m", function()
    local nft = render.nft(snap_ea())
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr @bl_test_ads ip daddr != @ea_aa_bb_cc_11_22_33_music_tiktok_com tcp dport 80 dnat ip to 127.0.0.1:8081",
      1, true))
  end)

  -- ── blockIpOnly composition (#353): ea sets do NOT modify the bio drop ──
  --
  -- The issue's design: extraAllowed composes with blockIpOnly naturally —
  -- a resolved-allowed host lands in resolved_<m> via the same DNS path,
  -- so the `ip daddr != @resolved_<m>` predicate is already false for it.
  -- No `!= @ea_...` clause needs to (or should) be added to the bio drop.

  it("does NOT add ea exception clauses to the blockIpOnly drop rule (#353 composition)", function()
    local s = snap_ea()
    s.profiles["3"].rules.blockIpOnly = true
    local nft = render.nft(s)
    -- The bio drop is unchanged — no `!= @ea_...` clause appended.
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr != @resolved_aa_bb_cc_11_22_33 log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:ip_only\"",
      1, true))
    -- And no spurious mixed rule like
    -- "ip daddr != @resolved_... ip daddr != @ea_..." either.
    assert.is_nil(nft:find(
      "ip daddr != @resolved_aa_bb_cc_11_22_33 ip daddr != @ea_", 1, true))
  end)

  it("blockIpOnly + extraAllowed: eb_ drop still has the ea exception, bio drop does not", function()
    local s = snap_ea()
    s.profiles["3"].rules.blockIpOnly = true
    local nft = render.nft(s)
    -- eb drop has ea suffix:
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr @eb_tiktok_com ip daddr != @ea_aa_bb_cc_11_22_33_music_tiktok_com log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:host\"",
      1, true))
    -- bio drop has no ea suffix:
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr != @resolved_aa_bb_cc_11_22_33 log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:ip_only\"",
      1, true))
  end)

  -- ── failover allow-all suppression ──────────────────────────────────────
  --
  -- For an allow-all-failover MAC, the eb_/bl_ drops are suppressed
  -- entirely (existing behaviour). The ea suffix becomes moot because
  -- there's no drop to suffix. ea sets may still be declared (cheap,
  -- harmless) and the dnsmasq populator still runs — mirrors the
  -- bio_macs declaration policy.

  it("suppresses eb_/bl_ drops (including their ea exception) under allow-all failover", function()
    local s = snap_ea()
    s.profiles["3"].failureMode = "allow-all"
    local nft = render.nft(s, { poll_failed = true })
    -- No eb / bl drops at all for the kid MAC.
    assert.is_nil(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr @eb_tiktok_com", 1, true))
    assert.is_nil(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr @bl_test_ads", 1, true))
  end)

  -- ── extraAllowed beats `blocked` (the @blocked_macs path too) ───────────
  --
  -- A fully-blocked device (pause / schedule / time-limit) must still be
  -- able to reach hosts in its extraAllowed list. The @blocked_macs drop
  -- is family-agnostic and can't carry an `ip daddr` predicate, so MACs
  -- that are blocked AND have extraAllowed are pulled out of the set
  -- into per-MAC, per-family rules with the ea exception.

  it("pulls a blocked-AND-has-extraAllowed MAC out of @blocked_macs", function()
    local s = snap_ea()
    s.profiles["3"].rules.blocked = true
    s.profiles["3"].rules.blockReason = "Paused"
    local nft = render.nft(s)
    -- @blocked_macs elements line either absent (set empty) or does not
    -- include the kid MAC. Adult is not blocked here so the set is empty.
    local pos = nft:find("set blocked_macs", 1, true)
    assert.truthy(pos)
    local blk_end = nft:find("\n  }", pos, true)
    local blk = nft:sub(pos, blk_end)
    assert.is_nil(blk:find("aa:bb:cc:11:22:33", 1, true))
  end)

  it("emits per-MAC v4 + v6 drops with ea exception for blocked-AND-has-extraAllowed", function()
    local s = snap_ea()
    s.profiles["3"].rules.blocked = true
    s.profiles["3"].rules.blockReason = "Paused"
    local nft = render.nft(s)
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr != @ea_aa_bb_cc_11_22_33_music_tiktok_com log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:Paused\"",
      1, true))
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip6 daddr != @ea6_aa_bb_cc_11_22_33_music_tiktok_com log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:Paused\"",
      1, true))
  end)

  it("emits per-MAC v4 + v6 DNAT with ea exception for blocked-AND-has-extraAllowed", function()
    local s = snap_ea()
    s.profiles["3"].rules.blocked = true
    s.profiles["3"].rules.blockReason = "Paused"
    local nft = render.nft(s)
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr != @ea_aa_bb_cc_11_22_33_music_tiktok_com tcp dport 80 dnat ip to 127.0.0.1:8081",
      1, true))
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip6 daddr != ::1 ip6 daddr != @ea6_aa_bb_cc_11_22_33_music_tiktok_com tcp dport 80 dnat ip6 to ::1:8081",
      1, true))
  end)

  it("leaves blocked MACs *without* extraAllowed in @blocked_macs (per-MAC drop)", function()
    -- #1122: the family-agnostic `ether saddr @blocked_macs drop` is replaced
    -- by a per-MAC drop carrying `log group 1` + comment. The @blocked_macs
    -- set still exists (the DNAT chain reads from it), but there's no longer
    -- a single set-driven drop rule.
    local s = snap_ea()
    -- Make adult both blocked AND keep extraAllowed={} → still in @blocked_macs.
    s.profiles["1"].rules.blocked = true
    s.profiles["1"].rules.blockReason = "Paused"
    local nft = render.nft(s)
    -- Adult MAC must appear in @blocked_macs elements (set drives DNAT).
    local pos = nft:find("set blocked_macs", 1, true)
    local blk_end = nft:find("\n  }", pos, true)
    local blk = nft:sub(pos, blk_end)
    assert.truthy(blk:find("de:ad:be:ef:00:01", 1, true))
    -- And the per-MAC drop with logging fires for adult.
    assert.truthy(nft:find(
      "ether saddr de:ad:be:ef:00:01 log group 1 counter drop comment \"wh_drop:de:ad:be:ef:00:01:Paused\"",
      1, true))
  end)

  it("emits the @blocked_macs DNAT only when the set is non-empty", function()
    local s = snap_ea()
    -- ONLY the kid is blocked, and the kid has extraAllowed → set is empty.
    s.profiles["3"].rules.blocked = true
    s.profiles["3"].rules.blockReason = "Paused"
    local nft = render.nft(s)
    -- No bare @blocked_macs DNAT (the set is empty).
    assert.is_nil(nft:find(
      "ether saddr @blocked_macs tcp dport 80", 1, true))
    assert.is_nil(nft:find(
      "ether saddr @blocked_macs ip6 daddr != ::1 tcp dport 80", 1, true))
    -- But the per-MAC ea-gated DNAT is present.
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr != @ea_aa_bb_cc_11_22_33_music_tiktok_com tcp dport 80 dnat ip to 127.0.0.1:8081",
      1, true))
  end)

  it("emits the block-page nat chain when only a blocked-with-ea MAC exists (no eb/bl/bio)", function()
    local s = snap_ea()
    s.profiles["3"].rules.blocked = true
    s.profiles["3"].rules.blockReason = "Paused"
    s.profiles["3"].rules.extraBlocked = {}
    s.profiles["3"].rules.blocklistIds = {}
    s.profiles["1"].rules.extraBlocked = {}
    local nft = render.nft(s)
    assert.truthy(nft:find("chain wifihaven_block_nat", 1, true))
  end)

  it("composes multiple extraAllowed hosts in the per-MAC blocked drop", function()
    local s = snap_ea()
    s.profiles["3"].rules.blocked = true
    s.profiles["3"].rules.blockReason = "Paused"
    s.profiles["3"].rules.extraAllowed = { "music.tiktok.com", "khanacademy.org" }
    local nft = render.nft(s)
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr != @ea_aa_bb_cc_11_22_33_khanacademy_org ip daddr != @ea_aa_bb_cc_11_22_33_music_tiktok_com log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:Paused\"",
      1, true))
  end)

  -- #1307: the carve-out must apply for *every* MacBlockReason, not just
  -- Paused. The prod miss was an allowed app (Math Academy) dropped when the
  -- profile's daily limit ran out (reason TimeLimit). Lock that the
  -- blocked-AND-has-extraAllowed MAC is pulled out of @blocked_macs and gets
  -- the `ip daddr != @ea_...` exception with the TimeLimit reason comment.
  it("#1307: carves extraAllowed out of the TimeLimit @blocked_macs drop", function()
    local s = snap_ea()
    s.profiles["3"].rules.blocked = true
    s.profiles["3"].rules.blockReason = "TimeLimit"
    s.profiles["3"].rules.extraAllowed = { "mathacademy.com" }
    local nft = render.nft(s)
    -- MAC pulled out of the @blocked_macs set (it has extraAllowed).
    local pos = nft:find("set blocked_macs", 1, true)
    local blk_end = nft:find("\n  }", pos, true)
    local blk = nft:sub(pos, blk_end)
    assert.is_nil(blk:find("aa:bb:cc:11:22:33", 1, true))
    -- Per-MAC v4 + v6 drops carry the ea exception and the TimeLimit reason.
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr != @ea_aa_bb_cc_11_22_33_mathacademy_com log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:TimeLimit\"",
      1, true))
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip6 daddr != @ea6_aa_bb_cc_11_22_33_mathacademy_com log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:TimeLimit\"",
      1, true))
    -- And the block-page DNAT for the allowed host is likewise carved out.
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr != @ea_aa_bb_cc_11_22_33_mathacademy_com tcp dport 80 dnat ip to 127.0.0.1:8081",
      1, true))
  end)

end)

-- ── render.write_blocked_reasons (#437) ──────────────────────────────────────
--
-- The agent calls this after every render.update_shared so the block-page
-- uhttpd handler can map REMOTE_ADDR → MAC → reason instead of falling back
-- to generic "blocked" copy.
describe("render.write_blocked_reasons", function()
  local function tmp_path()
    local p = os.tmpname()
    os.remove(p)  -- only want the path; we'll write to it
    return p
  end

  local function read_all(path)
    local f = io.open(path, "r")
    if not f then return nil end
    local c = f:read("*a"); f:close(); return c
  end

  it("writes one '<mac>\\t<reason>' line per blocked MAC, sorted", function()
    local p = tmp_path()
    local ok = render.write_blocked_reasons({
      ["de:ad:be:ef:00:01"] = "TimeLimit",
      ["aa:bb:cc:11:22:33"] = "Paused",
    }, p)
    assert.is_true(ok)
    assert.equals(
      "aa:bb:cc:11:22:33\tPaused\nde:ad:be:ef:00:01\tTimeLimit\n",
      read_all(p))
    os.remove(p)
  end)

  it("writes an empty file when nothing is blocked", function()
    local p = tmp_path()
    local ok = render.write_blocked_reasons({}, p)
    assert.is_true(ok)
    assert.equals("", read_all(p))
    os.remove(p)
  end)

  it("treats a nil map as empty (no crash, empty output)", function()
    local p = tmp_path()
    local ok = render.write_blocked_reasons(nil, p)
    assert.is_true(ok)
    assert.equals("", read_all(p))
    os.remove(p)
  end)
end)

-- ── render.write_blocked_hosts (#594) ────────────────────────────────────────
--
-- Persists per-(MAC, host) block source so the block-page handler can name a
-- category-blocklist hit instead of mis-labelling it as ExtraBlocked.
describe("render.write_blocked_hosts", function()
  local function tmp_path()
    local p = os.tmpname(); os.remove(p); return p
  end
  local function read_all(path)
    local f = io.open(path, "r"); if not f then return nil end
    local c = f:read("*a"); f:close(); return c
  end

  it("writes one '<mac>\\t<host>\\t<source>' line per entry, sorted", function()
    local p = tmp_path()
    local eb = { ["aa:bb:cc:11:22:33"] = { ["tiktok.com"] = true } }
    local bl = {
      ["aa:bb:cc:11:22:33"] = { ["ad.doubleclick.net"] = "ads" },
      ["de:ad:be:ef:00:01"] = { ["pornhub.com"] = "adult" },
    }
    local ok = render.write_blocked_hosts(eb, bl, p)
    assert.is_true(ok)
    assert.equals(
      "aa:bb:cc:11:22:33\tad.doubleclick.net\tcategory:ads\n"
      .. "aa:bb:cc:11:22:33\ttiktok.com\textra_blocked\n"
      .. "de:ad:be:ef:00:01\tpornhub.com\tcategory:adult\n",
      read_all(p))
    os.remove(p)
  end)

  it("writes an empty file when nothing is blocked", function()
    local p = tmp_path()
    local ok = render.write_blocked_hosts({}, {}, p)
    assert.is_true(ok)
    assert.equals("", read_all(p))
    os.remove(p)
  end)

  it("treats nil tables as empty", function()
    local p = tmp_path()
    local ok = render.write_blocked_hosts(nil, nil, p)
    assert.is_true(ok)
    assert.equals("", read_all(p))
    os.remove(p)
  end)
end)

-- ── Global policy composition (#1319) ─────────────────────────────────────
--
-- The snapshot gains a top-level `global` BlockRules (#1316) applied FLAT to
-- every MAC alongside the per-MAC rules, with fixed precedence (design
-- docs/design/global-policy-layer.md §5.2):
--
--   ga(d)       ⇔ d ∈ G.extraAllowed          -- @global_allow
--   gblock(d)   ⇔ G.blocked ∨ d ∈ G.extraBlocked ∨ d ∈ ⋃ipset(G.blocklistIds)
--   rblock(m,d) ⇔ R.blocked ∨ d ∈ R.extraBlocked ∨ d ∈ ⋃ipset(R.blocklistIds)
--   drop(m,d)   ⇔ ¬ga(d) ∧ ( gblock(d) ∨ (¬ra(m,d) ∧ rblock(m,d)) )
--                 ∨ (G.blockIpOnly ∨ R.blockIpOnly) ∧ d ∉ resolved_<m>
--
-- Key asymmetry: per-MAC extraAllowed (ra) suppresses only per-MAC drops;
-- ONLY @global_allow suppresses a @global_block / global lockdown.

-- snap_one() with a `global` BlockRules attached. Caller mutates as needed.
local function snap_global()
  local s = snap_one()
  s.global = {
    blocked      = false,
    blockReason  = nil,
    extraBlocked = {},
    extraAllowed = {},
    blocklistIds = {},
    blockIpOnly  = false,
  }
  return s
end

describe("render.dnsmasq global section (#1319)", function()
  it("emits combined v4+v6 nftset= for each global.extraAllowed host → @global_allow", function()
    local s = snap_global()
    s.global.extraAllowed = { "wifihaven.local" }
    local conf = render.dnsmasq(s)
    assert.truthy(conf:find(
      "nftset=/wifihaven.local/4#inet#wifihaven#global_allow,6#inet#wifihaven#global_allow6",
      1, true))
  end)

  it("emits combined v4+v6 nftset= for each global.extraBlocked host → @global_block", function()
    local s = snap_global()
    s.global.extraBlocked = { "evil.example" }
    local conf = render.dnsmasq(s)
    assert.truthy(conf:find(
      "nftset=/evil.example/4#inet#wifihaven#global_block,6#inet#wifihaven#global_block6",
      1, true))
  end)

  it("expands global.blocklistIds members into @global_block at resolve time", function()
    local s = snap_global()
    s.global.blocklistIds = { "ads" }
    s.blocklists = { ads = { version = "v1", url = "/api/blocklists/ads" } }
    s._blocklist_hosts = { ads = { "ad.doubleclick.net" } }
    local conf = render.dnsmasq(s)
    assert.truthy(conf:find(
      "nftset=/ad.doubleclick.net/4#inet#wifihaven#global_block,6#inet#wifihaven#global_block6",
      1, true))
  end)

  it("emits no global nftset= lines when the global section is empty", function()
    local conf = render.dnsmasq(snap_global())
    assert.is_nil(conf:find("global_allow", 1, true))
    assert.is_nil(conf:find("global_block", 1, true))
  end)

  it("is byte-identical to a snapshot with no global key when global is empty", function()
    assert.equals(render.dnsmasq(snap_one()), render.dnsmasq(snap_global()))
  end)
end)

describe("render.nft global composition (#1319)", function()
  it("declares @global_allow / @global_allow6 sets when global.extraAllowed is set", function()
    local s = snap_global()
    s.global.extraAllowed = { "wifihaven.local" }
    local nft = render.nft(s)
    assert.truthy(nft:find("set global_allow {", 1, true))
    assert.truthy(nft:find("set global_allow6 {", 1, true))
  end)

  it("declares @global_block / @global_block6 sets when global blocks are set", function()
    local s = snap_global()
    s.global.extraBlocked = { "evil.example" }
    local nft = render.nft(s)
    assert.truthy(nft:find("set global_block {", 1, true))
    assert.truthy(nft:find("set global_block6 {", 1, true))
  end)

  it("declares no global sets when the global section is empty", function()
    local nft = render.nft(snap_global())
    assert.is_nil(nft:find("global_allow", 1, true))
    assert.is_nil(nft:find("global_block", 1, true))
  end)

  it("is byte-identical to a snapshot with no global key when global is empty", function()
    assert.equals(render.nft(snap_one()), render.nft(snap_global()))
  end)

  -- ga carves out EVERY drop, including a whole-MAC block (#421-style, but
  -- fleet-wide): a global-allowed host stays reachable for a blocked MAC.
  it("global.extraAllowed carves out a whole-MAC block (per-MAC drop gets `!= @global_allow`)", function()
    local s = snap_global()
    s.global.extraAllowed = { "wifihaven.local" }
    s.profiles["3"].rules.blocked     = true
    s.profiles["3"].rules.blockReason = "Paused"
    local nft = render.nft(s)
    -- The blocked-MAC drop must carry the global-allow exception on both families.
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr != @global_allow log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:Paused\"",
      1, true))
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip6 daddr != @global_allow6 log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:Paused\"",
      1, true))
  end)

  -- ga is appended AFTER the per-MAC ea exception on eb_/bl_ drops.
  it("appends `!= @global_allow` to eb_ drops when global.extraAllowed is set", function()
    local s = snap_global()
    s.global.extraAllowed = { "wifihaven.local" }
    local nft = render.nft(s) -- snap_one has extraBlocked = { "tiktok.com" }
    assert.truthy(nft:find(
      "ip daddr @eb_tiktok_com ip daddr != @global_allow log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:host\"",
      1, true))
  end)

  -- A global block drops for every MAC; only @global_allow saves it.
  it("emits a per-MAC @global_block drop for every managed MAC", function()
    local s = snap_global()
    s.global.extraBlocked = { "evil.example" }
    s.devices["de:ad:be:ef:00:01"] = { profileId = 1, name = "parent-phone", rules = nil }
    s.profiles["1"] = {
      name = "adults",
      rules = { blocked = false, blockReason = nil, extraBlocked = {},
                extraAllowed = {}, blocklistIds = {}, blockIpOnly = false },
      failureMode = "block-all",
    }
    local nft = render.nft(s)
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr @global_block log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:global_block\"",
      1, true))
    assert.truthy(nft:find(
      "ether saddr de:ad:be:ef:00:01 ip daddr @global_block log group 1 counter drop comment \"wh_drop:de:ad:be:ef:00:01:global_block\"",
      1, true))
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip6 daddr @global_block6 log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:global_block\"",
      1, true))
  end)

  -- Asymmetry: a per-MAC extraAllowed does NOT suppress a global block (only
  -- @global_allow does), so the @global_block drop must NOT carry an ea_
  -- exception for the per-MAC allow.
  it("per-MAC extraAllowed does NOT carve out @global_block (only @global_allow does)", function()
    local s = snap_global()
    s.global.extraBlocked = { "evil.example" }
    s.profiles["3"].rules.extraAllowed = { "school.example" }
    local nft = render.nft(s)
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr @global_block log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:global_block\"",
      1, true))
    -- No ea exception spliced into the global-block drop line.
    assert.is_nil(nft:find(
      "ip daddr @global_block ip daddr != @ea_aa_bb_cc_11_22_33_school_example",
      1, true))
  end)

  -- Global lockdown: blocked = true on the global section drops everything for
  -- every MAC except @global_allow.
  it("global.blocked drops all traffic for every MAC except @global_allow", function()
    local s = snap_global()
    s.global.blocked      = true
    s.global.blockReason  = "DefaultDeny"
    s.global.extraAllowed = { "wifihaven.local" }
    local nft = render.nft(s)
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr != @global_allow log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:DefaultDeny\"",
      1, true))
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip6 daddr != @global_allow6 log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:DefaultDeny\"",
      1, true))
  end)

  -- Global strict-IP applies blockIpOnly to every MAC.
  it("global.blockIpOnly forces a resolved_<mac> drop for every MAC", function()
    local s = snap_global()
    s.global.blockIpOnly = true
    local nft = render.nft(s)
    assert.truthy(nft:find("set resolved_aa_bb_cc_11_22_33 {", 1, true))
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr != @resolved_aa_bb_cc_11_22_33 log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:ip_only\"",
      1, true))
  end)

  -- Block-page DNAT must redirect global blocks too.
  it("DNATs port 80 to the block page for a @global_block hit", function()
    local s = snap_global()
    s.global.extraBlocked = { "evil.example" }
    local nft = render.nft(s)
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr @global_block ip daddr != @global_allow tcp dport 80 dnat ip to 127.0.0.1:8081",
      1, true)
      or nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr @global_block tcp dport 80 dnat ip to 127.0.0.1:8081",
      1, true))
  end)

  -- Every drop in wifihaven_block — including the new global drops — must
  -- carry the nflog attribution prefix (the #1122 invariant).
  it("every global drop carries `log group 1 counter drop`", function()
    local s = snap_global()
    s.global.blocked      = true
    s.global.blockReason  = "DefaultDeny"
    s.global.extraBlocked = { "evil.example" }
    s.global.extraAllowed = { "wifihaven.local" }
    s.global.blockIpOnly  = true
    local nft = render.nft(s)
    local block_start = nft:find("chain wifihaven_block {", 1, true)
    local next_chain  = nft:find("\n%s*chain ", block_start + 1)
    local body = nft:sub(block_start, (next_chain or #nft + 1) - 1)
    for line in body:gmatch("[^\n]+") do
      if line:find(" drop", 1, true) and not line:find("counter drop", 1, true) then
        assert(false, "drop without log group / counter: " .. line)
      end
    end
  end)
end)

-- ── #1322: the tricky precedence COMPOSITIONS the design (§5.2/§5.3) calls out ──
-- These pin the interactions the #1319 base tests don't: a host on BOTH global
-- lists (global.extraAllowed beating global.extraBlocked), default-deny composed
-- with blockIpOnly, the device-override-replaces-profile path composed with the
-- global layer, and the §5.2 invariant that blockIpOnly carries NO allow
-- carve-out (allowed hosts pass via resolved_<m> naturally).
describe("render.nft global composition — precedence compositions (#1322)", function()
  -- global.extraAllowed is the TOP of the ladder: it beats a global block too.
  -- A host on both @global_block and @global_allow must stay reachable, so the
  -- per-MAC @global_block drop carries the `!= @global_allow` carve-out.
  it("global.extraAllowed beats global.extraBlocked (host in both → @global_block drop carves out @global_allow)", function()
    local s = snap_global()
    s.global.extraBlocked = { "evil.example" }
    s.global.extraAllowed = { "evil.example" } -- same host on both lists
    local nft = render.nft(s)
    -- The drop matches @global_block but is suppressed for any IP also in
    -- @global_allow — i.e. evil.example's resolved IPs survive.
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr @global_block ip daddr != @global_allow log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:global_block\"",
      1, true))
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip6 daddr @global_block6 ip6 daddr != @global_allow6 log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:global_block\"",
      1, true))
  end)

  -- default-deny × blockIpOnly is the strictest combination (design §4): the
  -- profile collapses to blocked=true with only its extraAllowed reachable AND
  -- a resolved_<m> strict-IP drop fires independently. A packet survives only if
  -- it is explicitly allowed AND was locally resolved.
  it("default-deny + blockIpOnly: per-MAC block carves out @ea_ AND a separate resolved_<m> drop fires", function()
    local s = snap_global()
    -- the server-collapsed default-deny wire shape: blocked + DefaultDeny +
    -- only extraAllowed, extraBlocked/blocklistIds omitted, blockIpOnly on.
    s.profiles["3"].rules = {
      blocked      = true,
      blockReason  = "DefaultDeny",
      extraBlocked = {},
      extraAllowed = { "pbskids.org" },
      blocklistIds = {},
      blockIpOnly  = true,
    }
    local nft = render.nft(s)
    -- 1) whole-MAC block, carved out by the per-MAC ea exception, DefaultDeny reason
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr != @ea_aa_bb_cc_11_22_33_pbskids_org log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:DefaultDeny\"",
      1, true))
    -- 2) the independent strict-IP drop on resolved_<m>
    assert.truthy(nft:find("set resolved_aa_bb_cc_11_22_33 {", 1, true))
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr != @resolved_aa_bb_cc_11_22_33 log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:ip_only\"",
      1, true))
  end)

  -- §5.2 invariant: blockIpOnly has NO allowlist carve-out. Allowed hosts land
  -- in resolved_<m> at DNS time and so pass the IP-only test naturally; splicing
  -- a `!= @global_allow` / `!= @ea_` clause into the resolved_ drop would be
  -- wrong. Even with a global allow list present, the resolved_ drop must be the
  -- bare strict-IP predicate.
  it("blockIpOnly drop never carries an allow carve-out, even with global.extraAllowed set", function()
    local s = snap_global()
    s.global.blockIpOnly  = true
    s.global.extraAllowed = { "ok.example" }
    local nft = render.nft(s)
    -- the resolved_ drop line is exactly the bare strict-IP predicate
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr != @resolved_aa_bb_cc_11_22_33 log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:ip_only\"",
      1, true))
    -- no `@global_allow` spliced onto the resolved_ line
    assert.is_nil(nft:find(
      "@resolved_aa_bb_cc_11_22_33 ip daddr != @global_allow", 1, true))
  end)

  -- global-allow vs profile-deny vs device-allow conflict (§5.1 replace + §5.2):
  -- a device override that runs allow-by-default REPLACES its default-deny / blocked
  -- profile, so the device escapes the whole-MAC profile block — but a GLOBAL block
  -- still applies to it (only @global_allow saves a global block).
  it("device-allow override escapes a blocked profile but a global block still applies", function()
    local s = snap_global()
    -- profile is whole-MAC blocked (e.g. default-deny / paused)…
    s.profiles["3"].rules.blocked     = true
    s.profiles["3"].rules.blockReason = "Paused"
    -- …but THIS device overrides to allow-by-default (replace semantics)…
    s.devices["aa:bb:cc:11:22:33"].rules = {
      blocked      = false,
      blockReason  = nil,
      extraBlocked = {},
      extraAllowed = {},
      blocklistIds = {},
      blockIpOnly  = false,
    }
    -- …and there is a fleet-wide global block + global allow.
    s.global.extraBlocked = { "evil.example" }
    s.global.extraAllowed = { "ok.example" }
    local nft = render.nft(s)
    -- The device is NOT whole-MAC blocked — no Paused drop for it (override won).
    assert.is_nil(nft:find("wh_drop:aa:bb:cc:11:22:33:Paused", 1, true))
    -- But the global block still drops for this managed MAC, carved out only by
    -- @global_allow (its own empty extraAllowed cannot loosen a global block).
    assert.truthy(nft:find(
      "ether saddr aa:bb:cc:11:22:33 ip daddr @global_block ip daddr != @global_allow log group 1 counter drop comment \"wh_drop:aa:bb:cc:11:22:33:global_block\"",
      1, true))
  end)
end)
