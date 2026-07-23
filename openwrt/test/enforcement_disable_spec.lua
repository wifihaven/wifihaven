-- Tests for the #2381 router-level enforcement escape hatch.
-- Run with: cd openwrt && busted test/enforcement_disable_spec.lua
--
-- The hatch is a LOCAL, on-router override (UCI wifihaven.settings.
-- enforcement_disabled) that short-circuits the agent's apply pipeline at the
-- top: when set, render.nft / render.dnsmasq emit a PERMISSIVE ruleset with NO
-- enforcement plane (no forward-drops, no block-page DNAT), so all forwarded
-- traffic passes — regardless of snapshot freshness (it must work with a stale
-- or absent snapshot, i.e. when the API is unreachable). It is a sanctioned
-- narrow exception to "the router is a dumb applier" (AGENTS.md Truth #2), like
-- the #1911 blockEncryptedDns agent-side exception. See docs/escape-hatch.md.

local render = require("render")
local policy = require("policy")

-- A snapshot that would, WITHOUT the hatch, install a full enforcement plane:
-- a paused (whole-MAC blocked) device, a per-host extraBlocked, a category
-- blocklist, a global block, and blockIpOnly. If ANY drop or DNAT for these
-- survives the short-circuit, the corresponding assertion below fails.
local function heavy_snap()
  return {
    etag        = "sha256:heavy",
    generatedAt = "2026-07-23T14:00:00Z",
    blockEncryptedDns = true,
    devices = {
      ["aa:bb:cc:11:22:33"] = { profileId = 3, name = "kid-ipad", rules = nil },
    },
    profiles = {
      ["3"] = {
        name = "kids",
        rules = {
          blocked      = true,
          blockReason  = "Paused",
          extraBlocked = { "tiktok.com" },
          extraAllowed = { "khanacademy.org" },
          blocklistIds = { "ads" },
          blockIpOnly  = true,
        },
        failureMode = "block-all",
      },
    },
    global = {
      rules = {
        blocked      = false,
        extraBlocked = { "doubleclick.net" },
        extraAllowed = {},
        blocklistIds = {},
        blockIpOnly  = false,
      },
    },
    blocklists = { ads = { version = "1", url = "http://x/ads" } },
  }
end

describe("render.nft with enforcement_disabled", function()

  it("emits NO forward-drop rules (no wifihaven_block chain drops)", function()
    local nft = render.nft(heavy_snap(), { enforcement_disabled = true })
    assert.is_nil(nft:find("drop", 1, true))
    assert.is_nil(nft:find("chain wifihaven_block ", 1, true))
  end)

  it("emits NO block-page DNAT/redirect (no wifihaven_block_nat chain)", function()
    local nft = render.nft(heavy_snap(), { enforcement_disabled = true })
    assert.is_nil(nft:find("wifihaven_block_nat", 1, true))
    assert.is_nil(nft:find("dnat", 1, true))
    assert.is_nil(nft:find("redirect to", 1, true))
  end)

  it("declares NONE of the enforcement sets (blocked_macs / eb_ / bl_ / resolved_ / global)", function()
    local nft = render.nft(heavy_snap(), { enforcement_disabled = true })
    assert.is_nil(nft:find("set blocked_macs", 1, true))
    assert.is_nil(nft:find("eb_", 1, true))
    assert.is_nil(nft:find("bl_", 1, true))
    assert.is_nil(nft:find("resolved_", 1, true))
    assert.is_nil(nft:find("global_block", 1, true))
  end)

  it("still installs the accounting chains so usage keeps accruing", function()
    local nft = render.nft(heavy_snap(), { enforcement_disabled = true })
    assert.truthy(nft:find("chain wifihaven_account_tx", 1, true))
    assert.truthy(nft:find("chain wifihaven_account_rx", 1, true))
    assert.truthy(nft:find("mac_ip_tracking", 1, true))
  end)

  it("still performs the atomic boot->runtime table handover prelude", function()
    local nft = render.nft(heavy_snap(), { enforcement_disabled = true })
    assert.truthy(nft:find("delete table inet wifihaven_boot", 1, true))
    assert.truthy(nft:find("table inet wifihaven {", 1, true))
  end)

  it("produces a permissive ruleset even for an EMPTY/absent snapshot (API down)", function()
    local nft = render.nft({ devices = {}, profiles = {} }, { enforcement_disabled = true })
    assert.is_nil(nft:find("drop", 1, true))
    assert.truthy(nft:find("chain wifihaven_account_tx", 1, true))
  end)

  it("still emits the full enforcement plane when the flag is off (control)", function()
    local nft = render.nft(heavy_snap())
    assert.truthy(nft:find("drop", 1, true))
    assert.truthy(nft:find("wifihaven_block_nat", 1, true))
  end)
end)

describe("render.dnsmasq with enforcement_disabled", function()

  it("emits NO nftset= populators and NO dhcp-host= tags", function()
    local conf = render.dnsmasq(heavy_snap(), { enforcement_disabled = true })
    assert.is_nil(conf:find("nftset=", 1, true))
    assert.is_nil(conf:find("dhcp-host=", 1, true))
  end)

  it("emits NO blockEncryptedDns NODATA lines (encrypted DNS is enforcement too)", function()
    local conf = render.dnsmasq(heavy_snap(), { enforcement_disabled = true })
    assert.is_nil(conf:find("mask.icloud.com", 1, true))
    assert.is_nil(conf:find("NODATA", 1, true))
  end)

  it("still emits a valid (header-only) conf-dir file", function()
    local conf = render.dnsmasq(heavy_snap(), { enforcement_disabled = true })
    assert.truthy(conf:find("# wifihaven", 1, true))
  end)
end)

describe("policy.apply with enforcement_disabled", function()

  it("writes a permissive nft (no drops) and permissive dnsmasq (no nftset)", function()
    local writes = {}
    policy.apply(heavy_snap(),
      function(path, content) writes[path] = content; return true, nil end,
      function(_cmd) return 0 end,
      nil,
      { enforcement_disabled = true, read_fn = function(_p) return nil end })
    assert.is_nil(writes["/tmp/nftables.d/wifihaven.nft"]:find("drop", 1, true))
    assert.is_nil(writes["/tmp/dnsmasq.d/wifihaven.conf"]:find("nftset=", 1, true))
  end)

  it("loads the permissive ruleset via nft and returns true", function()
    local reloads = {}
    local ok = policy.apply(heavy_snap(),
      function(_p, _c) return true, nil end,
      function(cmd) table.insert(reloads, cmd); return 0 end,
      nil,
      { enforcement_disabled = true, read_fn = function(_p) return nil end })
    assert.is_true(ok)
    local found_nft = false
    for _, cmd in ipairs(reloads) do if cmd:find("nft -f", 1, true) then found_nft = true end end
    assert.is_true(found_nft, "expected an `nft -f` load of the permissive ruleset")
  end)

  it("does not attempt an ea_ carve backfill when enforcement is disabled", function()
    -- The permissive ruleset declares no ea_ sets, so no `nft add element ea_`
    -- (or ea_backfill batch) may be issued — it would fail against absent sets.
    local reloads = {}
    policy.apply(heavy_snap(),
      function(_p, _c) return true, nil end,
      function(cmd) table.insert(reloads, cmd); return 0 end,
      nil,
      { enforcement_disabled = true, read_fn = function(_p) return nil end })
    for _, cmd in ipairs(reloads) do
      assert.is_nil(cmd:find("ea_", 1, true),
        "must not touch ea_ sets when enforcement disabled; got: " .. cmd)
    end
  end)
end)
