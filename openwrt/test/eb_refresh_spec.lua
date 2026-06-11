-- Tests for openwrt/files/usr/lib/lua/wifihaven/eb_refresh.lua
--
-- eb_refresh runs a periodic re-resolve of every extraBlocked host (and every
-- category blocklist host) the agent has applied, so the per-host `eb_<host>`
-- / `eb6_<host>` and per-blocklist `bl_<id>` / `bl6_<id>` nftables sets stay
-- populated ahead of their 1h `flags dynamic,timeout` aging out.
--
-- #1658 — the leak this closes: the kernel set ages an IP out after 1h, but a
-- client (iPad / iOS app DNS cache) keeps reusing the cached IP for the
-- lifetime of a connection pool. When the IP expires from `eb_<host>` the
-- drop predicate misses, traffic flows, and the agent's `lookup_hostname`
-- correctly attributes the bytes back to the FQDN at usage-report time —
-- surfacing as real engagement against an explicitly-blocked host (#1649).
--
-- The module is pure logic with injected resolver + nft executor + clock, so
-- the timer behaviour and the (host → ipset name → IP) wiring are
-- unit-testable. Same DI shape as dns_tail_sets / policy.
--
-- Run with: busted openwrt/test/eb_refresh_spec.lua

local eb_refresh = require("eb_refresh")

-- ---------------------------------------------------------------------------
-- helpers
-- ---------------------------------------------------------------------------

local function exec_recorder()
  local cmds = {}
  return cmds, function(cmd) cmds[#cmds + 1] = cmd end
end

local function fake_resolver(answers)
  -- answers : { ["host"] = { v4 = {...}, v6 = {...} } }
  return function(host)
    local a = answers[host]
    if not a then return { v4 = {}, v6 = {} } end
    return { v4 = a.v4 or {}, v6 = a.v6 or {} }
  end
end

local function added(cmds, set_name, ip)
  for _, c in ipairs(cmds) do
    if c:find(set_name, 1, true) and c:find("{ " .. ip .. " }", 1, true) then
      return true
    end
  end
  return false
end

-- ---------------------------------------------------------------------------
-- collect_inventory
-- ---------------------------------------------------------------------------

describe("collect_inventory", function()
  it("dedups extraBlocked hosts across MACs and returns sorted list", function()
    local eb = {
      ["ca:ef:a1:72:6a:a3"] = { ["play.google.com"] = true, ["roblox.com"] = true },
      ["04:72:ef:d6:e4:5a"] = { ["play.google.com"] = true },
    }
    local inv = eb_refresh.collect_inventory(eb, {})
    assert.same({ "play.google.com", "roblox.com" }, inv.eb_hosts)
    assert.same({}, inv.bl_pairs)
  end)

  it("collects blocklist (host,id) pairs, deduped across MACs", function()
    local bl = {
      ["aa:bb:cc:dd:ee:ff"] = { ["doubleclick.net"] = "ads", ["bad.example"] = "malware" },
      ["11:22:33:44:55:66"] = { ["doubleclick.net"] = "ads" },
    }
    local inv = eb_refresh.collect_inventory({}, bl)
    table.sort(inv.bl_pairs, function(a, b)
      if a.host == b.host then return a.id < b.id end
      return a.host < b.host
    end)
    assert.same({
      { host = "bad.example",     id = "malware" },
      { host = "doubleclick.net", id = "ads" },
    }, inv.bl_pairs)
  end)

  it("returns empty inventory when both maps are empty", function()
    local inv = eb_refresh.collect_inventory({}, {})
    assert.same({}, inv.eb_hosts)
    assert.same({}, inv.bl_pairs)
  end)

  it("tolerates nil inputs", function()
    local inv = eb_refresh.collect_inventory(nil, nil)
    assert.same({}, inv.eb_hosts)
    assert.same({}, inv.bl_pairs)
  end)
end)

-- ---------------------------------------------------------------------------
-- refresh — the headline behaviour
-- ---------------------------------------------------------------------------

describe("refresh", function()
  it("re-resolves extraBlocked hosts and adds v4/v6 to eb_<host> / eb6_<host>", function()
    local cmds, exec = exec_recorder()
    local resolver = fake_resolver({
      ["play.google.com"] = {
        v4 = { "142.251.46.142", "142.251.35.142" },
        v6 = { "2607:f8b0::200e" },
      },
    })
    local stats = eb_refresh.refresh({
      eb_hosts  = { "play.google.com" },
      bl_pairs  = {},
      nft_table = "inet wifihaven",
      resolver  = resolver,
      exec_fn   = exec,
    })

    assert.is_true(added(cmds, "eb_play_google_com",  "142.251.46.142"))
    assert.is_true(added(cmds, "eb_play_google_com",  "142.251.35.142"))
    assert.is_true(added(cmds, "eb6_play_google_com", "2607:f8b0::200e"))
    assert.equal(1, stats.hosts)
    assert.equal(1, stats.resolves_ok)
    assert.equal(0, stats.resolves_err)
    assert.equal(3, stats.adds)
  end)

  it("re-resolves blocklist hosts and adds v4/v6 to bl_<id> / bl6_<id>", function()
    local cmds, exec = exec_recorder()
    local resolver = fake_resolver({
      ["doubleclick.net"] = { v4 = { "8.8.8.8" }, v6 = { "::1" } },
    })
    local stats = eb_refresh.refresh({
      eb_hosts  = {},
      bl_pairs  = { { host = "doubleclick.net", id = "ads" } },
      nft_table = "inet wifihaven",
      resolver  = resolver,
      exec_fn   = exec,
    })

    assert.is_true(added(cmds, "bl_ads",  "8.8.8.8"))
    assert.is_true(added(cmds, "bl6_ads", "::1"))
    assert.equal(1, stats.hosts)
    assert.equal(2, stats.adds)
  end)

  it("counts resolver failures as resolves_err and does not call nft for them", function()
    local cmds, exec = exec_recorder()
    local resolver = function(host)
      if host == "broken.example" then return nil end
      return { v4 = { "1.2.3.4" }, v6 = {} }
    end
    local stats = eb_refresh.refresh({
      eb_hosts  = { "broken.example", "ok.example" },
      bl_pairs  = {},
      nft_table = "inet wifihaven",
      resolver  = resolver,
      exec_fn   = exec,
    })
    assert.equal(2, stats.hosts)
    assert.equal(1, stats.resolves_ok)
    assert.equal(1, stats.resolves_err)
    assert.equal(1, stats.adds)
    assert.is_true(added(cmds, "eb_ok_example", "1.2.3.4"))
    -- nothing for broken.example
    for _, c in ipairs(cmds) do
      assert.is_nil(c:find("eb_broken_example", 1, true))
    end
  end)

  it("rejects shell-metacharacters in resolved IPs (reuses dns_tail_sets.safe_addr)", function()
    local cmds, exec = exec_recorder()
    local resolver = fake_resolver({
      ["x.example"] = { v4 = { "1.2.3.4; rm -rf /" }, v6 = {} },
    })
    local stats = eb_refresh.refresh({
      eb_hosts  = { "x.example" },
      bl_pairs  = {},
      nft_table = "inet wifihaven",
      resolver  = resolver,
      exec_fn   = exec,
    })
    assert.equal(0, stats.adds)
    assert.equal(0, #cmds)
  end)

  it("is idempotent: a second run issues add-element commands again (nft tolerates duplicates)", function()
    local cmds, exec = exec_recorder()
    local resolver = fake_resolver({
      ["play.google.com"] = { v4 = { "1.2.3.4" }, v6 = {} },
    })
    local opts = {
      eb_hosts  = { "play.google.com" },
      bl_pairs  = {},
      nft_table = "inet wifihaven",
      resolver  = resolver,
      exec_fn   = exec,
    }
    local s1 = eb_refresh.refresh(opts)
    local s2 = eb_refresh.refresh(opts)
    assert.equal(1, s1.adds)
    assert.equal(1, s2.adds)
    -- Both runs issued the same command; idempotence is the kernel's job.
    assert.equal(2, #cmds)
  end)

  it("returns a zero-cost stats record when inventory is empty", function()
    local cmds, exec = exec_recorder()
    local called = 0
    local stats = eb_refresh.refresh({
      eb_hosts  = {},
      bl_pairs  = {},
      nft_table = "inet wifihaven",
      resolver  = function() called = called + 1; return { v4 = {}, v6 = {} } end,
      exec_fn   = exec,
    })
    assert.equal(0, stats.hosts)
    assert.equal(0, stats.adds)
    assert.equal(0, called)
    assert.equal(0, #cmds)
  end)
end)

-- ---------------------------------------------------------------------------
-- default_resolve — parses `dig @127.0.0.1 +short` output
-- ---------------------------------------------------------------------------

describe("parse_dig_output", function()
  it("yields a sorted list of v4 addresses", function()
    local out = "142.251.46.142\n142.251.35.142\n"
    assert.same({ "142.251.35.142", "142.251.46.142" }, eb_refresh.parse_dig_output(out, "v4"))
  end)

  it("yields a sorted list of v6 addresses", function()
    local out = "2607:f8b0::200e\n2607:f8b0::100a\n"
    assert.same({ "2607:f8b0::100a", "2607:f8b0::200e" }, eb_refresh.parse_dig_output(out, "v6"))
  end)

  it("skips CNAME-shaped lines that dig +short prints first", function()
    local out = "youtube-ui.l.google.com.\n142.251.46.142\n"
    assert.same({ "142.251.46.142" }, eb_refresh.parse_dig_output(out, "v4"))
  end)

  it("returns an empty list when dig returned no answer", function()
    assert.same({}, eb_refresh.parse_dig_output("", "v4"))
    assert.same({}, eb_refresh.parse_dig_output(nil, "v4"))
  end)
end)
