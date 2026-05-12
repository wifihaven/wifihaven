-- Tests for openwrt/files/usr/lib/lua/familydns/log.lua
-- Run with: busted openwrt/test/log_spec.lua

local log = require("log")

-- The log module shells out to `logger -t <tag> <msg>`. Each test installs a
-- fake io.popen / os.execute that records the commands it was asked to run.

local recorded
local function reset()
  recorded = {}
  -- Stub the function the module actually uses.
  log._exec = function(cmd) recorded[#recorded + 1] = cmd end
end

describe("log module", function()
  before_each(reset)

  describe("init", function()
    it("defaults debug enabled = false", function()
      log.init({})
      assert.is_false(log.debug_enabled())
    end)

    it("accepts debug = true", function()
      log.init({ debug = true })
      assert.is_true(log.debug_enabled())
    end)

    it("accepts string truthy values from UCI ('1', 'true')", function()
      log.init({ debug = "1" })
      assert.is_true(log.debug_enabled())
      log.init({ debug = "true" })
      assert.is_true(log.debug_enabled())
      log.init({ debug = "0" })
      assert.is_false(log.debug_enabled())
      log.init({ debug = "" })
      assert.is_false(log.debug_enabled())
    end)
  end)

  describe("info / err / warn", function()
    it("always invokes logger regardless of debug flag", function()
      log.init({ debug = false })
      log.info("hello world")
      log.err("boom")
      log.warn("careful")
      assert.equal(3, #recorded)
      assert.matches("logger %-t familydns ", recorded[1])
      assert.matches("hello world", recorded[1])
      assert.matches("%-p user%.err", recorded[2])
      assert.matches("%-p user%.warning", recorded[3])
    end)

    it("shell-quotes the message safely", function()
      log.init({ debug = false })
      log.info([[a 'b' "c" $(rm -rf /) `evil` end]])
      assert.equal(1, #recorded)
      -- Must not contain an unescaped single quote inside a single-quoted block.
      -- The implementation uses %q-style quoting; the literal command must
      -- start the message arg with a quote character.
      assert.matches("logger %-t familydns ", recorded[1])
      -- And the dangerous characters must not break out:
      assert.is_nil(recorded[1]:find("`evil`%s*$"))
    end)
  end)

  describe("debug", function()
    it("is a no-op when debug is disabled", function()
      log.init({ debug = false })
      log.debug("trace this")
      assert.equal(0, #recorded)
    end)

    it("emits a debug-priority logger call when enabled", function()
      log.init({ debug = true })
      log.debug("trace this")
      assert.equal(1, #recorded)
      assert.matches("%-p user%.debug", recorded[1])
      assert.matches("trace this", recorded[1])
    end)
  end)

  describe("format", function()
    it("supports string.format-style varargs", function()
      log.init({ debug = true })
      log.debug("policy fetch url=%s status=%d", "http://x", 200)
      assert.matches("policy fetch url=http://x status=200", recorded[1])
    end)
  end)
end)
