-- Tests for openwrt/files/usr/lib/lua/wifihaven/version.lua (#771).
-- Run as part of openwrt/test/run_tests.sh.

local version = require("version")

local function fake_open(contents)
  return function(_path, _mode)
    if contents == nil then return nil end
    local pos = 1
    return {
      read = function(_self, _fmt)
        local out = contents:sub(pos)
        pos = #contents + 1
        return out
      end,
      close = function() end,
    }
  end
end

describe("version.read", function()

  it("returns the trimmed contents of the VERSION file", function()
    assert.equal("0.1.0", version.read("/x", fake_open("0.1.0\n")))
  end)

  it("strips leading and trailing whitespace", function()
    assert.equal("1.2.3", version.read("/x", fake_open("  1.2.3  \n")))
  end)

  it("returns nil when the file is missing", function()
    assert.is_nil(version.read("/x", fake_open(nil)))
  end)

  it("returns nil when the file is empty / whitespace only", function()
    assert.is_nil(version.read("/x", fake_open("")))
    assert.is_nil(version.read("/x", fake_open("   \n")))
  end)

end)
