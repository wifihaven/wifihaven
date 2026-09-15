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

-- Shaped like a real `io.popen` handle (read "*a", close) so the shipped
-- resolver needs no test-only branch. A missing entry is nil — what popen
-- returns when it cannot spawn at all.
local function popen_stub(by_qtype)
  return function(cmd)
    local text = by_qtype[cmd:match("%-type=(%u+)")]
    if text == nil then return nil end
    return { read = function() return text end, close = function() end }
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


describe("refresh empty-answer accounting (#2782 review)", function()
  -- Separating "could not run" from "no records" is the core of #2782. The
  -- remaining gap: a resolver that RUNS but whose output the parser does not
  -- understand also yields zero records, which is the same silent no-op in a
  -- new costume. An extraBlocked host resolving to nothing in BOTH families is
  -- therefore its own outcome, not an `ok`.
  it("counts an extraBlocked host with no addresses as empty, not ok", function()
    local _, exec = exec_recorder()
    local stats = eb_refresh.refresh({
      eb_hosts    = { "gone.example", "live.example" },
      bl_pairs    = {},
      nft_table   = "inet wifihaven",
      resolver    = function(h)
        if h == "gone.example" then return { v4 = {}, v6 = {} } end
        return { v4 = { "1.2.3.4" }, v6 = {} }
      end,
      exec_fn     = exec,
    })
    -- LIVENESS ANCHOR: the host that DOES resolve still counts ok and adds,
    -- so `empty == 1` is a real classification rather than a dead resolver.
    assert.equal(1, stats.resolves_ok)
    assert.equal(1, stats.adds)
    assert.equal(1, stats.resolves_empty)
    assert.equal(0, stats.resolves_err)
  end)
end)

describe("refresh — time-boxed, resumable sweep (#2785)", function()
  local function fake_clock(step)
    local t = 0
    return function()
      t = t + (step or 0)
      return t
    end
  end

  local function hosts(n)
    local out = {}
    for i = 1, n do out[i] = string.format("h%03d.example", i) end
    return out
  end

  local function ok_resolver(_) return { v4 = {}, v6 = {} } end

  it("still does the whole inventory in one pass when no budget is given", function()
    -- Back-compat: existing callers (and the boot path) pass no deadline.
    local stats = eb_refresh.refresh({
      eb_hosts = hosts(25), nft_table = "inet wifihaven",
      resolver = ok_resolver, exec_fn = function() end,
    })
    assert.are.equal(25, stats.hosts)
    assert.is_true(stats.done)
    assert.are.equal(0, stats.next_index)
  end)

  it("stops at the budget and reports where it got to", function()
    -- 1 simulated second per resolve, 5 s of budget.
    local stats = eb_refresh.refresh({
      eb_hosts = hosts(100), nft_table = "inet wifihaven",
      resolver = ok_resolver, exec_fn = function() end,
      deadline_seconds = 5, now_fn = fake_clock(1),
    })
    assert.is_false(stats.done)
    assert.is_true(stats.hosts > 0)
    assert.is_true(stats.hosts < 100)
    assert.are.equal(stats.hosts + 1, stats.next_index)
  end)

  it("resumes from the cursor rather than restarting at the top", function()
    local seen = {}
    local resolver = function(h) seen[#seen + 1] = h; return { v4 = {}, v6 = {} } end
    local first = eb_refresh.refresh({
      eb_hosts = hosts(20), nft_table = "inet wifihaven",
      resolver = resolver, exec_fn = function() end,
      deadline_seconds = 3, now_fn = fake_clock(1),
    })
    assert.is_false(first.done)
    local n1 = #seen
    eb_refresh.refresh({
      eb_hosts = hosts(20), nft_table = "inet wifihaven",
      resolver = resolver, exec_fn = function() end,
      deadline_seconds = 3, now_fn = fake_clock(1),
      start_index = first.next_index,
    })
    -- No host is revisited: the second slice picks up where the first stopped.
    assert.are.equal(hosts(20)[n1 + 1], seen[n1 + 1])
  end)

  it("completes the sweep across successive slices and then reports done", function()
    -- LIVENESS ANCHOR for the "stops early" assertions above: a rig that
    -- resolves nothing would satisfy every one of them for free. Drive the
    -- sweep to completion and prove every host was visited exactly once.
    local seen, idx = {}, 0
    local resolver = function(h) idx = idx + 1; seen[h] = (seen[h] or 0) + 1; return { v4 = {}, v6 = {} } end
    local all, cursor, slices = hosts(30), nil, 0
    repeat
      local st = eb_refresh.refresh({
        eb_hosts = all, nft_table = "inet wifihaven",
        resolver = resolver, exec_fn = function() end,
        deadline_seconds = 4, now_fn = fake_clock(1),
        start_index = cursor,
      })
      cursor = st.next_index
      slices = slices + 1
      assert.is_true(slices < 30, "sweep made no forward progress")
    until cursor == 0
    assert.are.equal(30, idx)
    for _, h in ipairs(all) do assert.are.equal(1, seen[h]) end
    assert.is_true(slices > 1, "the budget never actually forced a split")
  end)

  it("spans eb_hosts and bl_pairs as ONE cursor space", function()
    -- The blocklist half is the big half — it is where the 160k hosts live —
    -- so a cursor that only covered eb_hosts would leave the actual problem
    -- unbounded.
    local seen = {}
    local resolver = function(h) seen[#seen + 1] = h; return { v4 = {}, v6 = {} } end
    local st = eb_refresh.refresh({
      eb_hosts = { "a.example", "b.example" },
      bl_pairs = { { host = "c.example", id = "ads" }, { host = "d.example", id = "ads" } },
      nft_table = "inet wifihaven",
      resolver = resolver, exec_fn = function() end,
      deadline_seconds = 3, now_fn = fake_clock(1),
    })
    assert.is_false(st.done)
    assert.is_true(st.next_index >= 2 and st.next_index <= 4)
    local rest = eb_refresh.refresh({
      eb_hosts = { "a.example", "b.example" },
      bl_pairs = { { host = "c.example", id = "ads" }, { host = "d.example", id = "ads" } },
      nft_table = "inet wifihaven",
      resolver = resolver, exec_fn = function() end,
      start_index = st.next_index,
    })
    assert.is_true(rest.done)
    assert.are.equal(4, #seen)
    assert.are.equal("d.example", seen[4])
  end)

  it("counts the inventory size so the capacity problem is visible", function()
    local st = eb_refresh.refresh({
      eb_hosts = hosts(3),
      bl_pairs = { { host = "x.example", id = "ads" } },
      nft_table = "inet wifihaven",
      resolver = ok_resolver, exec_fn = function() end,
    })
    assert.are.equal(4, st.inventory)
  end)
end)
