-- Tests for #385 three-mode failover in render.nft.
--
-- The agent passes opts.poll_age_seconds = (now - last_successful_poll_ts).
-- When poll_age > 300, the per-profile failureMode decides what happens:
--   "block-all"       — profile's MACs get an additional drop chain.
--   "allow-all"       — profile's MACs are SUPPRESSED from every drop
--                       rule (blocked_macs, eb_pairs, bl_pairs) and the
--                       block-page DNAT. Cached enforcement is erased
--                       for these MACs during failover.
--   "last-known-good" — no change. The cached snapshot keeps enforcing
--                       exactly as-is.
--
-- Run with: cd openwrt && busted test/failover_spec.lua

local render = require("render")

local function empty_rules()
  return {
    blocked = false, blockReason = nil,
    extraBlocked = {}, extraAllowed = {}, blocklistIds = {}, blockIpOnly = false,
  }
end

-- Three-profile snapshot covering every mode. One device per profile.
local function snap_three()
  return {
    etag        = "sha256:f1",
    generatedAt = "2026-05-14T14:00:00Z",
    devices = {
      ["aa:aa:aa:00:00:01"] = { profileId = 1, name = "kid",      rules = nil },
      ["bb:bb:bb:00:00:02"] = { profileId = 2, name = "adult",    rules = nil },
      ["cc:cc:cc:00:00:03"] = { profileId = 3, name = "guest",    rules = nil },
    },
    profiles = {
      ["1"] = { name = "kids",   rules = empty_rules(), failureMode = "block-all" },
      ["2"] = { name = "adults", rules = empty_rules(), failureMode = "last-known-good" },
      ["3"] = { name = "guest",  rules = empty_rules(), failureMode = "allow-all" },
    },
    blocklists = {},
  }
end

describe("render.nft — #385 three-mode failover", function()

  -- ── BlockAll: existing failover_drop chain ────────────────────────────

  it("emits no failover artifacts when poll_age_seconds is nil (no signal yet)", function()
    local nft = render.nft(snap_three())
    assert.is_nil(nft:find("failover_drop", 1, true))
  end)

  it("emits no failover_drop set when poll_age_seconds <= 300 (within window)", function()
    local nft = render.nft(snap_three(), { poll_age_seconds = 300 })
    assert.is_nil(nft:find("failover_drop", 1, true))
  end)

  it("BlockAll: emits failover_drop containing only the block-all profile's MACs (>300s)", function()
    local nft = render.nft(snap_three(), { poll_age_seconds = 301 })
    assert.truthy(nft:find("set failover_drop", 1, true))
    -- Block-all profile device is in the set.
    assert.truthy(nft:find("aa:aa:aa:00:00:01", 1, true))
    assert.truthy(nft:find("@failover_drop", 1, true))
    -- Scope the membership check to the set body.
    local s = nft:find("set failover_drop", 1, true)
    local body_end = nft:find("}", s, true)
    local body = nft:sub(s, body_end)
    assert.is_nil(body:find("bb:bb:bb:00:00:02", 1, true),
      "LastKnownGood device must NOT be in failover_drop")
    assert.is_nil(body:find("cc:cc:cc:00:00:03", 1, true),
      "AllowAll device must NOT be in failover_drop")
  end)

  it("BlockAll: empty block-all profile produces no drop chain", function()
    local s = snap_three()
    s.devices = {
      ["bb:bb:bb:00:00:02"] = { profileId = 2, name = "adult", rules = nil },
    }
    local nft = render.nft(s, { poll_age_seconds = 9999 })
    assert.is_nil(nft:find("@failover_drop", 1, true))
  end)

  it("BlockAll: boundary — 300s in-window, 301s trips failover (strict > 300)", function()
    local nft_300 = render.nft(snap_three(), { poll_age_seconds = 300 })
    local nft_301 = render.nft(snap_three(), { poll_age_seconds = 301 })
    assert.is_nil(nft_300:find("@failover_drop", 1, true))
    assert.truthy(nft_301:find("@failover_drop", 1, true))
  end)

  -- ── LastKnownGood: cached snapshot keeps enforcing ───────────────────

  it("LastKnownGood: cached snapshot rules keep enforcing during failover", function()
    -- Adult profile has paused-by-API set to true in the cached snapshot.
    -- LastKnownGood means the cached @blocked_macs membership must persist.
    local s = snap_three()
    s.profiles["2"].rules.blocked = true
    s.profiles["2"].rules.blockReason = "Paused"
    local nft = render.nft(s, { poll_age_seconds = 9999 })
    assert.truthy(nft:find("bb:bb:bb:00:00:02", 1, true),
      "LastKnownGood adult MAC must remain in @blocked_macs")
    -- And LastKnownGood is NOT in the failover_drop set even though it's blocked.
    local s_off = nft:find("set failover_drop", 1, true)
    if s_off then
      local body_end = nft:find("}", s_off, true)
      local body = nft:sub(s_off, body_end)
      assert.is_nil(body:find("bb:bb:bb:00:00:02", 1, true))
    end
  end)

  -- ── AllowAll: cached enforcement erased for these MACs ───────────────

  it("AllowAll: profile's MAC is SUPPRESSED from @blocked_macs during failover", function()
    -- Cached snapshot had AllowAll guest blocked (e.g. via scheduled block);
    -- failover must clear that.
    local s = snap_three()
    s.profiles["3"].rules.blocked = true
    s.profiles["3"].rules.blockReason = "Schedule"
    local nft = render.nft(s, { poll_age_seconds = 9999 })
    -- Scope to the @blocked_macs set body.
    local s_off = nft:find("set blocked_macs", 1, true)
    assert.truthy(s_off)
    local body_end = nft:find("}", s_off, true)
    local body = nft:sub(s_off, body_end)
    assert.is_nil(body:find("cc:cc:cc:00:00:03", 1, true),
      "AllowAll MAC must be excluded from @blocked_macs during failover")
  end)

  it("AllowAll: profile's extraBlocked drops are SUPPRESSED during failover", function()
    local s = snap_three()
    s.profiles["3"].rules.extraBlocked = { "tiktok.com" }
    local nft = render.nft(s, { poll_age_seconds = 9999 })
    assert.is_nil(nft:find(
      "ether saddr cc:cc:cc:00:00:03 ip daddr @eb_tiktok_com drop", 1, true),
      "AllowAll MAC must NOT have per-(MAC, host) extraBlocked drops during failover")
  end)

  it("AllowAll: profile's blocklistIds drops are SUPPRESSED during failover", function()
    local s = snap_three()
    s.profiles["3"].rules.blocklistIds = { "ads" }
    s.blocklists = { ads = { version = "v1", url = "/api/blocklists/ads" } }
    local nft = render.nft(s, { poll_age_seconds = 9999 })
    assert.is_nil(nft:find(
      "ether saddr cc:cc:cc:00:00:03 ip daddr @bl_ads drop", 1, true),
      "AllowAll MAC must NOT have per-(MAC, blocklistId) drops during failover")
  end)

  it("AllowAll: same blocks still apply pre-failover (poll_age=0 or unset)", function()
    -- Sanity: the AllowAll suppression must be gated on in_failover. With
    -- no poll_age signal (or poll_age <= 300), the cached snapshot's
    -- extraBlocked entry for guest still produces its drop rule.
    local s = snap_three()
    s.profiles["3"].rules.extraBlocked = { "tiktok.com" }
    local nft_no_failover = render.nft(s)
    assert.truthy(nft_no_failover:find(
      "ether saddr cc:cc:cc:00:00:03 ip daddr @eb_tiktok_com drop", 1, true),
      "AllowAll must enforce normally outside the failover window")
    local nft_in_window = render.nft(s, { poll_age_seconds = 60 })
    assert.truthy(nft_in_window:find(
      "ether saddr cc:cc:cc:00:00:03 ip daddr @eb_tiktok_com drop", 1, true),
      "AllowAll must enforce normally before the 5-minute threshold")
  end)

  it("AllowAll: other profiles' drops are unaffected by AllowAll suppression", function()
    -- Block-all profile gets its failover_drop; LastKnownGood retains its
    -- cached drops. Only the AllowAll profile's MACs are excluded.
    local s = snap_three()
    s.profiles["1"].rules.extraBlocked = { "kidblocked.example" }
    s.profiles["2"].rules.extraBlocked = { "adultblocked.example" }
    s.profiles["3"].rules.extraBlocked = { "guestblocked.example" }
    local nft = render.nft(s, { poll_age_seconds = 9999 })
    -- Block-all kid keeps its extraBlocked drop.
    assert.truthy(nft:find(
      "ether saddr aa:aa:aa:00:00:01 ip daddr @eb_kidblocked_example drop",
      1, true))
    -- LastKnownGood adult keeps its extraBlocked drop.
    assert.truthy(nft:find(
      "ether saddr bb:bb:bb:00:00:02 ip daddr @eb_adultblocked_example drop",
      1, true))
    -- AllowAll guest does NOT have its extraBlocked drop.
    assert.is_nil(nft:find(
      "ether saddr cc:cc:cc:00:00:03 ip daddr @eb_guestblocked_example drop",
      1, true))
  end)

  it("AllowAll: profile's block-page DNAT is SUPPRESSED during failover", function()
    local s = snap_three()
    s.profiles["3"].rules.blocked = true
    s.profiles["3"].rules.blockReason = "Paused"
    local nft = render.nft(s, { poll_age_seconds = 9999 })
    assert.is_nil(nft:find(
      "ether saddr cc:cc:cc:00:00:03", 1, true),
      "no DNAT rule scoped to AllowAll MAC during failover")
  end)

  -- ── Regression: omitted opts ─────────────────────────────────────────

  it("normal (non-failover) snapshot rendering is unchanged when opts omitted", function()
    local nft = render.nft(snap_three())
    assert.truthy(nft:find("table inet familydns", 1, true))
    assert.truthy(nft:find("set profile1_macs", 1, true))
    assert.truthy(nft:find("set profile2_macs", 1, true))
    assert.truthy(nft:find("set profile3_macs", 1, true))
  end)

end)
