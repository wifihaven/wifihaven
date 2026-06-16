-- contract_spec.lua — router-side half of the bidirectional contract test (#634).
--
-- Loads the golden API → router fixtures from contract/api-to-router/
-- via the production parser (luci.jsonc — shimmed to lua-cjson in tests, the
-- same shim the rest of openwrt/test/ uses) and asserts that render.lua and
-- the policy module can consume every field of every fixture.
--
-- A failed assertion means the API has changed the wire shape (renamed a
-- field, removed one, added one render.lua needs) without updating the
-- consumer. Re-run scripts/regen-contract-fixtures.sh AFTER updating
-- render.lua / Models.scala in tandem.

local jsonc  = require("luci.jsonc")
local render = require("render")

-- ── Fixture loader ─────────────────────────────────────────────────────────
-- Tests run from openwrt/ (see openwrt/test/run_tests.sh). The contract dir
-- lives at the repo root, so we walk up until we find it.
local function find_contract_dir()
  local cur = "."
  for _ = 1, 8 do
    local f = io.open(cur .. "/contract/api-to-router/policy_snapshot.json", "r")
    if f then
      f:close()
      return cur .. "/contract"
    end
    cur = cur .. "/.."
  end
  error("could not locate contract from cwd")
end

local CONTRACT_DIR = find_contract_dir()

local function read_fixture(rel)
  local path = CONTRACT_DIR .. "/" .. rel
  local f = assert(io.open(path, "r"), "fixture not found: " .. path)
  local body = f:read("*a")
  f:close()
  return body
end

local function load_snapshot(rel)
  local body = read_fixture(rel)
  local snap = jsonc.parse(body)
  assert(snap, "luci.jsonc could not parse " .. rel)
  return snap
end

-- ── Tests ──────────────────────────────────────────────────────────────────

describe("contract: API → router policy_snapshot (#634)", function()

  local snap = load_snapshot("api-to-router/policy_snapshot.json")

  it("has the top-level snapshot fields render.lua / policy.lua read", function()
    assert.is_string(snap.etag)
    assert.is_string(snap.generatedAt)
    assert.is_table(snap.devices)
    assert.is_table(snap.profiles)
    assert.is_table(snap.blocklists)
  end)

  it("every device entry carries name + (profileId XOR rules)", function()
    local seen = 0
    for mac, dev in pairs(snap.devices) do
      seen = seen + 1
      assert.is_string(mac)
      assert.is_string(dev.name)
      -- Either resolves through a profile or carries an inline rules override.
      assert.is_true(dev.profileId ~= nil or type(dev.rules) == "table",
        "device " .. mac .. " has neither profileId nor inline rules")
    end
    assert.is_true(seen > 0, "fixture must contain at least one device")
  end)

  it("every profile entry has name + rules + failureMode", function()
    local seen = 0
    for pid, prof in pairs(snap.profiles) do
      seen = seen + 1
      assert.is_string(pid)            -- JSON object keys are strings
      assert.is_string(prof.name)
      assert.is_table(prof.rules)
      assert.is_string(prof.failureMode)
      -- failureMode is one of three known string values (#385).
      local fm = prof.failureMode
      assert.is_true(fm == "block-all" or fm == "allow-all" or fm == "last-known-good",
        "unknown failureMode: " .. tostring(fm))
    end
    assert.is_true(seen > 0, "fixture must contain at least one profile")
  end)

  it("every BlockRules carries the fields render.lua reads", function()
    local function check_rules(where, r)
      assert.is_boolean(r.blocked, where .. ".blocked")
      -- blockReason is Option[MacBlockReason]; absent / nil is fine.
      assert.is_table(r.extraBlocked, where .. ".extraBlocked")
      assert.is_table(r.extraAllowed, where .. ".extraAllowed")
      assert.is_table(r.blocklistIds, where .. ".blocklistIds")
      assert.is_boolean(r.blockIpOnly, where .. ".blockIpOnly")
    end
    for pid, prof in pairs(snap.profiles) do
      check_rules("profile[" .. pid .. "].rules", prof.rules)
    end
    for mac, dev in pairs(snap.devices) do
      if type(dev.rules) == "table" then
        check_rules("device[" .. mac .. "].rules", dev.rules)
      end
    end
  end)

  it("blocklists entries have version + url", function()
    for id, bl in pairs(snap.blocklists) do
      assert.is_string(id)
      assert.is_string(bl.version)
      assert.is_string(bl.url)
    end
  end)

  -- The real consumer test: feed the fixture through render.lua and assert
  -- it produces non-empty config without raising. Anything render.lua reads
  -- (camelCase field names, nested shapes) is covered here.
  it("render.dnsmasq accepts the fixture and emits dhcp-host lines for every device", function()
    local conf = render.dnsmasq(snap)
    assert.is_string(conf)
    for mac, dev in pairs(snap.devices) do
      if dev.profileId ~= nil then
        local tag = "set:profile" .. string.format("%d", dev.profileId)
        assert.truthy(conf:find("dhcp-host=" .. mac .. "," .. tag, 1, true),
          "missing dhcp-host entry for " .. mac)
      end
    end
  end)

  it("render.nft accepts the fixture and emits an nft ruleset", function()
    local nft = render.nft(snap, {})
    assert.is_string(nft)
    assert.is_true(#nft > 0)
  end)

  -- #421: per-(MAC, host) extraAllowed sets only appear when the resolved
  -- rules include extraAllowed entries. The fixture has at least one (kids
  -- profile -> khanacademy.org, and the per-MAC blocked device with
  -- school.example.org), so render.nft must reference an ea_ set.
  it("emits at least one ea_ set name (extraAllowed present in fixture)", function()
    local nft = render.nft(snap, {})
    assert.truthy(nft:find("ea_", 1, true), "expected an ea_ set name in nft output")
  end)
end)
