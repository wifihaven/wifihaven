-- jsonc_shim_spec.lua — the test shim for luci-lib-jsonc (luci.jsonc) must
-- FAITHFULLY emulate the real C module's observed encode behavior (#1367).
--
-- Production code (metrics.post, conntrack/usage POST paths, policy snapshot
-- persistence, and the router→API contract fixture generator) serializes with
-- `luci.jsonc.stringify`. That C module isn't on luarocks, so local/CI tests
-- use `test/shim/luci/jsonc.lua`. If the shim diverges from real luci.jsonc,
-- bytes generated here (especially the committed golden fixtures under
-- shared/contract/router-to-api/) no longer match what the router actually
-- emits — which is exactly how the #1365 empty-`labels` bug shipped green: the
-- old cjson-backed shim encoded an empty Lua table as `{}` (object), masking
-- that real luci.jsonc encodes it as `[]` (array) — a shape the API's
-- `Map[String,String]` decoder rejects.
--
-- The expectations below are the luci.jsonc behaviors captured directly from
-- the real module on an OpenWrt target (`lua -e 'print(require("luci.jsonc")
-- .stringify(<x>))'`). They are the contract the shim must meet.
local jsonc = require("luci.jsonc")

describe("luci.jsonc shim fidelity (#1367)", function()
  it("encodes an empty table as a JSON array [], not an object {} (#1365)", function()
    -- The divergence that let #1365 ship. cjson encodes {} as `{}`.
    assert.equal("[]", jsonc.stringify({}))
  end)

  it("encodes an empty nested map as [] so labels={} surfaces as the bug it is", function()
    -- A series that mistakenly kept `labels = {}` must serialize to the
    -- decoder-breaking `"labels":[]`, NOT a benign `"labels":{}` — so the
    -- contract fixture / round-trip catches the regression instead of masking it.
    assert.equal('{"labels":[]}', jsonc.stringify({ labels = {} }))
  end)

  it("renders whole-number doubles without a trailing .0 (880, not 880.0)", function()
    assert.equal("880", jsonc.stringify(880))
    assert.equal('{"v":1}', jsonc.stringify({ v = 1.0 }))
    assert.equal('{"v":8388608}', jsonc.stringify({ v = 8388608 }))
  end)

  it("renders fractional doubles at full IEEE-754 round-trip precision", function()
    -- luci.jsonc uses %.17g; cjson caps at 16 sig figs and would emit "0.82".
    assert.equal("0.81999999999999995", jsonc.stringify(0.82))
    assert.equal("0.32800000000000001", jsonc.stringify(0.328))
  end)

  it("escapes forward slashes as \\/ (json-c default), like luci.jsonc", function()
    assert.equal('"a\\/b"', jsonc.stringify("a/b"))
  end)

  it("still parses JSON back into lua tables (parse stays cjson-backed)", function()
    local v = jsonc.parse('{"a":[1,2],"b":"x"}')
    assert.same({ 1, 2 }, v.a)
    assert.equal("x", v.b)
  end)
end)
