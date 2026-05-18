-- Tests for openwrt/files/usr/lib/lua/wifihaven/version.lua (#597)
-- Run with: busted openwrt/test/version_spec.lua

local version = require("version")

local function make_log()
  local recorded = { info = {}, warn = {}, err = {}, debug = {} }
  return {
    info  = function(fmt, ...) recorded.info [#recorded.info  + 1] = string.format(fmt, ...) end,
    warn  = function(fmt, ...) recorded.warn [#recorded.warn  + 1] = string.format(fmt, ...) end,
    err   = function(fmt, ...) recorded.err  [#recorded.err   + 1] = string.format(fmt, ...) end,
    debug = function(fmt, ...) recorded.debug[#recorded.debug + 1] = string.format(fmt, ...) end,
  }, recorded
end

describe("wifihaven.version", function()
  describe("WIRE_SCHEMA constant", function()
    it("is a positive integer", function()
      assert.is_number(version.WIRE_SCHEMA)
      assert.is_true(version.WIRE_SCHEMA >= 1)
      assert.equal(math.floor(version.WIRE_SCHEMA), version.WIRE_SCHEMA)
    end)
  end)

  describe("compare_wire_schema", function()
    it("logs info on match and returns true", function()
      local log, recorded = make_log()
      local ok = version.compare_wire_schema(version.WIRE_SCHEMA, log)
      assert.is_true(ok)
      assert.equal(1, #recorded.info)
      assert.equal(0, #recorded.warn)
      assert.matches("wireSchema match: " .. tostring(version.WIRE_SCHEMA), recorded.info[1])
    end)

    it("logs warn loudly on mismatch and returns false", function()
      local log, recorded = make_log()
      local ok = version.compare_wire_schema(version.WIRE_SCHEMA + 1, log)
      assert.is_false(ok)
      assert.equal(0, #recorded.info)
      assert.equal(1, #recorded.warn)
      assert.matches("MISMATCH", recorded.warn[1])
      assert.matches("agent=" .. tostring(version.WIRE_SCHEMA), recorded.warn[1])
      assert.matches("api=" .. tostring(version.WIRE_SCHEMA + 1), recorded.warn[1])
    end)

    it("warns when the remote value is nil (missing field)", function()
      local log, recorded = make_log()
      local ok = version.compare_wire_schema(nil, log)
      assert.is_false(ok)
      assert.equal(1, #recorded.warn)
      assert.matches("MISMATCH", recorded.warn[1])
      assert.matches("api=nil", recorded.warn[1])
    end)
  end)
end)
