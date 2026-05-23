-- Tests for openwrt/files/usr/lib/lua/wifihaven/selfheal.lua
-- Run with: cd openwrt && busted test/selfheal_spec.lua

local selfheal = require("selfheal")

local function tmp_path()
  return os.tmpname()
end

local function write_file(path, content)
  local f = assert(io.open(path, "w"))
  f:write(content)
  f:close()
end

local function read_file(path)
  local f = io.open(path, "r")
  if not f then return nil end
  local s = f:read("*a")
  f:close()
  return s
end

local function fake_run_cmd_recorder()
  local calls = {}
  return calls, function(cmd) calls[#calls+1] = cmd; return true end
end

describe("selfheal.selfheal_cron", function()

  it("creates a missing crontab with the canonical line", function()
    local p = tmp_path()
    os.remove(p)
    local calls, run = fake_run_cmd_recorder()
    local healed = selfheal.selfheal_cron({ path = p, run_cmd = run })
    assert.is_true(healed)
    assert.equals("0 4 * * * /usr/sbin/wifihaven-update\n", read_file(p))
    -- Should have invoked mkdir and the cron enable/restart triple.
    local joined = table.concat(calls, "|")
    assert.is_truthy(joined:find("mkdir %-p"))
    assert.is_truthy(joined:find("/etc/init.d/cron enable"))
    assert.is_truthy(joined:find("/etc/init.d/cron restart"))
    os.remove(p)
  end)

  it("is a no-op when the canonical line is already present", function()
    local p = tmp_path()
    write_file(p, "# header\n0 4 * * * /usr/sbin/wifihaven-update\n0 * * * * /usr/sbin/other-thing\n")
    local calls, run = fake_run_cmd_recorder()
    local healed = selfheal.selfheal_cron({ path = p, run_cmd = run })
    assert.is_false(healed)
    -- File untouched (still has all three lines in the original order).
    assert.equals(
      "# header\n0 4 * * * /usr/sbin/wifihaven-update\n0 * * * * /usr/sbin/other-thing\n",
      read_file(p))
    -- No cron enable/restart on the happy path.
    local joined = table.concat(calls, "|")
    assert.is_nil(joined:find("/etc/init.d/cron enable"))
    assert.is_nil(joined:find("/etc/init.d/cron restart"))
    os.remove(p)
  end)

  it("rewrites a stale cadence to the canonical line", function()
    local p = tmp_path()
    write_file(p, "0 */6 * * * /usr/sbin/wifihaven-update\n# keepme\n")
    local _, run = fake_run_cmd_recorder()
    local healed = selfheal.selfheal_cron({ path = p, run_cmd = run })
    assert.is_true(healed)
    local out = read_file(p)
    assert.is_nil(out:find("*/6"))
    assert.is_truthy(out:find("0 4 %* %* %* /usr/sbin/wifihaven%-update"))
    -- Preserves unrelated lines.
    assert.is_truthy(out:find("# keepme"))
    os.remove(p)
  end)

  it("preserves unrelated lines on heal", function()
    local p = tmp_path()
    write_file(p, "30 2 * * * /usr/bin/something\n")
    local _, run = fake_run_cmd_recorder()
    local healed = selfheal.selfheal_cron({ path = p, run_cmd = run })
    assert.is_true(healed)
    local out = read_file(p)
    assert.is_truthy(out:find("30 2 %* %* %* /usr/bin/something"))
    assert.is_truthy(out:find("0 4 %* %* %* /usr/sbin/wifihaven%-update"))
    os.remove(p)
  end)

  it("collapses duplicate wifihaven-update lines to a single canonical entry", function()
    local p = tmp_path()
    write_file(p, "0 4 * * * /usr/sbin/wifihaven-update\n0 4 * * * /usr/sbin/wifihaven-update\n")
    local _, run = fake_run_cmd_recorder()
    local healed = selfheal.selfheal_cron({ path = p, run_cmd = run })
    assert.is_true(healed)
    local out = read_file(p)
    local _, n = out:gsub("wifihaven%-update", "")
    assert.equals(1, n)
    os.remove(p)
  end)

end)
