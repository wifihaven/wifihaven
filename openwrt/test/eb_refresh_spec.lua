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
-- default_resolver — parses BusyBox `nslookup` output (#2782)
-- ---------------------------------------------------------------------------

-- #2782: `default_resolver` is the seam where the whole module went dark. It
-- shelled out to `dig`, which OpenWRT does not ship, and the empty stdout that
-- came back read as a successful resolve with no records — so prod counted
-- 179,349,241 resolves_ok, zero resolves_err, and added nothing for months.
-- These tests run the REAL default_resolver over captured BusyBox `nslookup`
-- output with the popen injected, and end-to-end through `refresh` so the
-- stats and the nft adds are pinned together.

local NSLOOKUP_A = table.concat({
  "Server:\t\t127.0.0.1",
  "Address:\t127.0.0.1:53",
  "",
  "Non-authoritative answer:",
  "www.amazon.com\tcanonical name = e15316.dsca.akamaiedge.net",
  "Name:\te15316.dsca.akamaiedge.net",
  "Address: 23.47.202.67",
  "",
}, "\n")

local NSLOOKUP_AAAA = table.concat({
  "Server:\t\t127.0.0.1",
  "Address:\t127.0.0.1:53",
  "",
  "Non-authoritative answer:",
  "Name:\tcf.47cf2c8c9-frontier.amazon.com",
  "Address: 2600:9000:2162:1c00:7:49a5:5fd6:da1",
  "",
}, "\n")

local function popen_stub(by_qtype)
  return function(cmd)
    return by_qtype[cmd:match("%-type=(%u+)")]
  end
end

describe("default_resolver", function()
  it("parses BusyBox nslookup output into both families", function()
    local r = eb_refresh.default_resolver("www.amazon.com", popen_stub({
      A = NSLOOKUP_A, AAAA = NSLOOKUP_AAAA,
    }))
    assert.same({ "23.47.202.67" }, r.v4)
    assert.same({ "2600:9000:2162:1c00:7:49a5:5fd6:da1" }, r.v6)
  end)

  it("returns nil — not an empty answer — when the resolver cannot run", function()
    assert.is_nil(eb_refresh.default_resolver("www.amazon.com", popen_stub({})))
  end)
end)

describe("refresh with the real default_resolver (#2782)", function()
  -- LIVENESS ANCHOR. The failure test below asserts an ABSENCE (no nft adds),
  -- and an absence passes for free on a rig that resolves nothing. This case
  -- proves the same rig DOES add elements when the resolver works, so the
  -- absence below means "the resolver failed", not "the rig is inert".
  it("adds elements when the resolver works", function()
    local cmds, exec = exec_recorder()
    local stats = eb_refresh.refresh({
      eb_hosts  = { "www.amazon.com" },
      bl_pairs  = {},
      nft_table = "inet wifihaven",
      resolver  = function(h)
        return eb_refresh.default_resolver(h, popen_stub({
          A = NSLOOKUP_A, AAAA = NSLOOKUP_AAAA,
        }))
      end,
      exec_fn   = exec,
    })
    assert.equal(1, stats.resolves_ok)
    assert.equal(0, stats.resolves_err)
    assert.equal(2, stats.adds)
    assert.is_true(added(cmds, "eb_www_amazon_com",  "23.47.202.67"))
    assert.is_true(added(cmds, "eb6_www_amazon_com", "2600:9000:2162:1c00:7:49a5:5fd6:da1"))
  end)

  it("counts a missing resolver binary as resolves_err instead of success", function()
    local cmds, exec = exec_recorder()
    local stats = eb_refresh.refresh({
      eb_hosts  = { "www.amazon.com" },
      bl_pairs  = {},
      nft_table = "inet wifihaven",
      resolver  = function(h) return eb_refresh.default_resolver(h, popen_stub({})) end,
      exec_fn   = exec,
    })
    assert.equal(0, stats.resolves_ok)
    assert.equal(1, stats.resolves_err)
    assert.equal(0, stats.adds)
    assert.equal(0, #cmds)
  end)
end)


-- ---------------------------------------------------------------------------
-- Per-cycle budget (#2782)
-- ---------------------------------------------------------------------------
--
-- The resolver being dead is what made the work look free. Prod's blocklist
-- membership is 161,523 hosts, so the cycle was queueing ~328,000 resolves an
-- hour and completing them in no time at all because `dig` did not exist. With
-- a resolver that works, an unbounded cycle would run ~328k sequential
-- shell-outs into dnsmasq every 30 minutes and starve it (#1864). So the cycle
-- carries an explicit budget: extraBlocked hosts first — authored, few, and the
-- #2782 case — then blocklist members round-robin from a persisted cursor.

describe("refresh budget", function()
  local function pairs_for(n)
    local out = {}
    for i = 1, n do out[i] = { host = "h" .. i .. ".example", id = "ads" } end
    return out
  end

  local function always_resolves()
    return function() return { v4 = { "1.2.3.4" }, v6 = {} } end
  end

  it("stops at max_hosts and reports the skipped remainder", function()
    local cmds, exec = exec_recorder()
    local stats = eb_refresh.refresh({
      eb_hosts  = {},
      bl_pairs  = pairs_for(10),
      max_hosts = 4,
      nft_table = "inet wifihaven",
      resolver  = always_resolves(),
      exec_fn   = exec,
    })
    assert.equal(4, stats.hosts)
    assert.equal(4, stats.resolves_ok)
    assert.equal(6, stats.skipped)
  end)

  -- LIVENESS ANCHOR for the budget tests: the same rig with a budget wide
  -- enough for the work does all of it and skips nothing, so "stats.hosts == 4"
  -- above means the budget bit, not that the rig resolves nothing.
  it("does the whole cycle when the budget is not binding", function()
    local cmds, exec = exec_recorder()
    local stats = eb_refresh.refresh({
      eb_hosts  = {},
      bl_pairs  = pairs_for(10),
      max_hosts = 100,
      nft_table = "inet wifihaven",
      resolver  = always_resolves(),
      exec_fn   = exec,
    })
    assert.equal(10, stats.hosts)
    assert.equal(10, stats.resolves_ok)
    assert.equal(0, stats.skipped)
  end)

  -- extraBlocked is what #2782 is about: an authored, per-profile host that
  -- MUST be re-armed every cycle. A blocklist backlog can never crowd it out.
  it("always spends the budget on extraBlocked hosts first", function()
    local cmds, exec = exec_recorder()
    local stats = eb_refresh.refresh({
      eb_hosts  = { "www.amazon.com" },
      bl_pairs  = pairs_for(10),
      max_hosts = 3,
      nft_table = "inet wifihaven",
      resolver  = always_resolves(),
      exec_fn   = exec,
    })
    assert.equal(3, stats.hosts)
    assert.is_true(added(cmds, "eb_www_amazon_com", "1.2.3.4"))
  end)

  it("round-robins blocklist hosts from the returned cursor", function()
    local seen = {}
    local function record_resolver(host)
      seen[#seen + 1] = host
      return { v4 = { "1.2.3.4" }, v6 = {} }
    end
    local _, exec = exec_recorder()
    local base = {
      eb_hosts  = {},
      bl_pairs  = pairs_for(5),
      max_hosts = 2,
      nft_table = "inet wifihaven",
      resolver  = record_resolver,
      exec_fn   = exec,
    }
    local first = eb_refresh.refresh(base)
    base.bl_cursor = first.bl_cursor
    local second = eb_refresh.refresh(base)
    base.bl_cursor = second.bl_cursor
    eb_refresh.refresh(base)

    assert.same({
      "h1.example", "h2.example",
      "h3.example", "h4.example",
      "h5.example", "h1.example",
    }, seen)
  end)

  it("treats an absent max_hosts as the module default, not as unbounded", function()
    local _, exec = exec_recorder()
    local stats = eb_refresh.refresh({
      eb_hosts  = {},
      bl_pairs  = pairs_for(eb_refresh.DEFAULT_MAX_HOSTS + 25),
      nft_table = "inet wifihaven",
      resolver  = always_resolves(),
      exec_fn   = exec,
    })
    assert.equal(eb_refresh.DEFAULT_MAX_HOSTS, stats.hosts)
    assert.equal(25, stats.skipped)
  end)
end)
