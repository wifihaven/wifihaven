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

-- ---------------------------------------------------------------------------
-- #2785 — the sweep must be time-boxed and resumable
-- ---------------------------------------------------------------------------
--
-- ROOT CAUSE of the 2026-09-13 prod stall, measured rather than reasoned:
-- usage_window_stall_total on the family router incremented at 17:13, 17:43,
-- 18:13, 18:44, 19:14 — every 30 minutes, 48/day, dead flat for the full 14
-- days of retention. Nothing else in on_tick runs on a 30-minute cadence;
-- eb_refresh_interval defaults to 1800 s. The 19:14 increment is the incident.
--
-- The sweep walks the WHOLE inventory in one pass with two `dig` forks per
-- host, serially, inside the agent's single-fibered on_tick. On that router the
-- inventory is every member host of ten subscribed blocklists (~160k distinct
-- hosts in /etc/wifihaven/blocklists), and the pass took 558 s — the exact
-- window the agent reported. For those 558 s the loop applied no pushed policy
-- and reported no usage or events, which is both halves of the incident.
--
-- The fix keeps the coverage #1658 needs (every entry re-resolved ahead of the
-- 1h nft set timeout) and gives up only the "one pass, one tick" property: a
-- pass spends at most its budget, returns where it got to, and the next tick
-- resumes there.

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
