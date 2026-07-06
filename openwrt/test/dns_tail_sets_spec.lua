-- Tests for openwrt/files/usr/lib/lua/wifihaven/dns_tail_sets.lua
--
-- dns_tail_sets holds the pure nft-set populator logic the wifihaven-dns-tail
-- sidecar runs out of band against the dnsmasq query log:
--   * resolved_<mac>  (#505, blockIpOnly)
--   * eb_<sanhost>    (#515, extraBlocked)
--   * ea_<sanmac>_<sanhost> (#1346, extraAllowed carve-out)
--
-- It was extracted from the sidecar script so the suffix-walk + #1344 alias
-- recovery is unit-testable with an injected exec function (mirrors the
-- get_fn/write_fn/exec_fn injection pattern in policy.lua / conntrack.lua).
--
-- The headline #1346 behaviour: when a client re-queries the FINAL CNAME
-- target directly (Apple devices do this), the answered name is the CDN
-- target and never suffix-matches the declared ea_/eb_<brand> set. dnsmasq's
-- own nftset= callback misses it for the same reason. We recover the branded
-- chain head from the dns_log alias map (resolve_head) and walk THAT too.
--
-- Run with: busted openwrt/test/dns_tail_sets_spec.lua

local sets    = require("dns_tail_sets")
local dns_log = require("dns_log")

-- Capture nft commands instead of executing them.
local function recorder()
  local cmds = {}
  return cmds, function(cmd) cmds[#cmds + 1] = cmd end
end

-- Did any recorded command add `ip` to a set whose name matches `set_pat`?
local function added(cmds, set_pat, ip)
  for _, c in ipairs(cmds) do
    if c:find(set_pat, 1, true) and c:find("{ " .. ip .. " }", 1, true) then
      return true
    end
  end
  return false
end

-- ---------------------------------------------------------------------------
-- helpers
-- ---------------------------------------------------------------------------

describe("sanitize / safe_addr", function()
  it("replaces dots and colons with underscores", function()
    assert.equal("kastatic_org", sets.sanitize("kastatic.org"))
    assert.equal("04_72_ef_d6_e4_5a", sets.sanitize("04:72:ef:d6:e4:5a"))
  end)

  it("safe_addr passes v4/v6 literals and rejects shell metacharacters", function()
    assert.equal("1.2.3.4", sets.safe_addr("1.2.3.4"))
    assert.equal("2606:2800::1", sets.safe_addr("2606:2800::1"))
    assert.is_nil(sets.safe_addr("1.2.3.4; rm -rf /"))
    assert.is_nil(sets.safe_addr(nil))
  end)
end)

-- ---------------------------------------------------------------------------
-- set discovery (parse `nft -a list table` output)
-- ---------------------------------------------------------------------------

describe("classify_set_line", function()
  local function blank()
    return { bio = {}, eb4 = {}, eb6 = {}, ea4 = {}, ea6 = {} }
  end

  it("indexes resolved_/eb_/eb6_ sets by sanitized host (existing #505/#515)", function()
    local s = blank()
    sets.classify_set_line("\t\tset resolved_aa_bb_cc_dd_ee_ff {", s)
    sets.classify_set_line("\t\tset eb_example_com {", s)
    sets.classify_set_line("\t\tset eb6_example_com {", s)
    assert.is_true(s.bio["aa_bb_cc_dd_ee_ff"])
    assert.is_true(s.eb4["example_com"])
    assert.is_true(s.eb6["example_com"])
  end)

  it("indexes ea_/ea6_ sets by sanmac → {sanhost} splitting the fixed MAC prefix", function()
    local s = blank()
    -- real prod example: ea_04_72_ef_d6_e4_5a_kastatic_org
    sets.classify_set_line("\t\tset ea_04_72_ef_d6_e4_5a_kastatic_org {", s)
    sets.classify_set_line("\t\tset ea6_04_72_ef_d6_e4_5a_kastatic_org {", s)
    assert.is_true(s.ea4["04_72_ef_d6_e4_5a"]["kastatic_org"])
    assert.is_true(s.ea6["04_72_ef_d6_e4_5a"]["kastatic_org"])
  end)

  it("keeps a multi-label host intact after the MAC prefix", function()
    local s = blank()
    sets.classify_set_line("\t\tset ea_04_72_ef_d6_e4_5a_www_khanacademy_org {", s)
    assert.is_true(s.ea4["04_72_ef_d6_e4_5a"]["www_khanacademy_org"])
  end)
end)

-- ---------------------------------------------------------------------------
-- eb_ / eb6_ populator (#515 + #1346 alias recovery)
-- ---------------------------------------------------------------------------

describe("maybe_populate_eb", function()
  it("adds the answered name's IP to a declared eb_ set (existing #515 path)", function()
    local cmds, exec = recorder()
    local r = { name = "ads.example.com", ip = "1.2.3.4", family = "v4" }
    local n = sets.maybe_populate_eb(r, {
      eb4 = { example_com = true }, eb6 = {},
      nft_table = "inet wifihaven", exec_fn = exec,
    })
    assert.equal(1, n)
    assert.is_true(added(cmds, "eb_example_com", "1.2.3.4"))
  end)

  it("recovers the branded head for a DIRECTLY-queried CDN target (#1346)", function()
    -- Drive a real dns_log cache: branded chain first, then a direct re-query
    -- of the CNAME target on a different anycast IP.
    local t = 1000000
    local c = dns_log.new({ ttl_seconds = 3600, now_fn = function() return t end })
    c.ingest_line("1 192.168.1.9/5 query[A] tiktok.com from 192.168.1.9")
    c.ingest_line("1 192.168.1.9/5 reply tiktok.com is <CNAME>")
    c.ingest_line("1 192.168.1.9/5 reply e35058.api12.akamaiedge.net is 23.45.67.89")

    -- Now the direct re-query: the answered name is the CDN target, which does
    -- NOT label-match eb_tiktok_com — only the recovered head does.
    local cmds, exec = recorder()
    local r = { name = "e35058.api12.akamaiedge.net", ip = "23.0.0.1", family = "v4" }
    local n = sets.maybe_populate_eb(r, {
      eb4 = { tiktok_com = true }, eb6 = {},
      nft_table = "inet wifihaven", exec_fn = exec,
      resolve_head = c.resolve_head,
    })
    assert.equal(1, n)
    assert.is_true(added(cmds, "eb_tiktok_com", "23.0.0.1"))
  end)

  it("populates eb6_ for AAAA answers", function()
    local cmds, exec = recorder()
    local r = { name = "example.com", ip = "2606:2800::1", family = "v6" }
    sets.maybe_populate_eb(r, {
      eb4 = {}, eb6 = { example_com = true },
      nft_table = "inet wifihaven", exec_fn = exec,
    })
    assert.is_true(added(cmds, "eb6_example_com", "2606:2800::1"))
  end)

  it("adds nothing when neither the name nor its head matches a declared set", function()
    local cmds, exec = recorder()
    local r = { name = "unrelated.example", ip = "9.9.9.9", family = "v4" }
    local n = sets.maybe_populate_eb(r, {
      eb4 = { tiktok_com = true }, eb6 = {},
      nft_table = "inet wifihaven", exec_fn = exec,
      resolve_head = function(x) return x end,
    })
    assert.equal(0, n)
    assert.equal(0, #cmds)
  end)
end)

-- ---------------------------------------------------------------------------
-- ea_ / ea6_ populator (#1346 — new; mirrors eb_ but keyed by (mac, host))
-- ---------------------------------------------------------------------------

describe("maybe_populate_ea", function()
  local function deps(extra)
    local d = {
      ea4 = {}, ea6 = {},
      ip_to_mac = { ["192.168.10.159"] = "04:72:ef:d6:e4:5a" },
      nft_table = "inet wifihaven",
    }
    for k, v in pairs(extra or {}) do d[k] = v end
    return d
  end

  it("adds the resolved IP to ea_<sanmac>_<sanhost> for the answering MAC", function()
    local cmds, exec = recorder()
    local r = { client_ip = "192.168.10.159", name = "static.kastatic.org",
                ip = "151.101.1.42", family = "v4" }
    local n = sets.maybe_populate_ea(r, deps({
      ea4 = { ["04_72_ef_d6_e4_5a"] = { kastatic_org = true } },
      exec_fn = exec,
    }))
    assert.equal(1, n)
    assert.is_true(added(cmds, "ea_04_72_ef_d6_e4_5a_kastatic_org", "151.101.1.42"))
  end)

  it("recovers the brand for a DIRECTLY-queried CDN target (#1346 headline case)", function()
    -- Kid device resolves the branded host, then re-queries the Fastly target
    -- directly on a fresh anycast IP — the exact prod scenario from #1344.
    local t = 1000000
    local c = dns_log.new({ ttl_seconds = 3600, now_fn = function() return t end })
    c.ingest_line("38053 192.168.10.159/36172 query[A] cdn.kastatic.org from 192.168.10.159")
    c.ingest_line("38053 192.168.10.159/36172 reply cdn.kastatic.org is <CNAME>")
    c.ingest_line("38053 192.168.10.159/36172 reply fastly.kastatic.org is <CNAME>")
    c.ingest_line("38053 192.168.10.159/36172 reply prod.khan.map.fastly.net is 199.232.65.42")

    local cmds, exec = recorder()
    -- Direct re-query: answered name is the CDN target on a NEW IP.
    local r = { client_ip = "192.168.10.159", name = "prod.khan.map.fastly.net",
                ip = "151.101.65.42", family = "v4" }
    local n = sets.maybe_populate_ea(r, deps({
      ea4 = { ["04_72_ef_d6_e4_5a"] = { kastatic_org = true } },
      exec_fn = exec, resolve_head = c.resolve_head,
    }))
    assert.equal(1, n)
    assert.is_true(added(cmds, "ea_04_72_ef_d6_e4_5a_kastatic_org", "151.101.65.42"))
  end)

  it("populates ea6_ for AAAA answers", function()
    local cmds, exec = recorder()
    local r = { client_ip = "192.168.10.159", name = "static.kastatic.org",
                ip = "2606:2800::5", family = "v6" }
    sets.maybe_populate_ea(r, deps({
      ea6 = { ["04_72_ef_d6_e4_5a"] = { kastatic_org = true } },
      exec_fn = exec,
    }))
    assert.is_true(added(cmds, "ea6_04_72_ef_d6_e4_5a_kastatic_org", "2606:2800::5"))
  end)

  it("does nothing when the client IP maps to no MAC", function()
    local cmds, exec = recorder()
    local r = { client_ip = "10.0.0.99", name = "static.kastatic.org",
                ip = "1.2.3.4", family = "v4" }
    local n = sets.maybe_populate_ea(r, deps({
      ea4 = { ["04_72_ef_d6_e4_5a"] = { kastatic_org = true } },
      exec_fn = exec,
    }))
    assert.equal(0, n)
    assert.equal(0, #cmds)
  end)

  it("does not add to another MAC's ea_ set", function()
    -- The answering MAC has no ea_ set declared; a DIFFERENT MAC does. The
    -- per-(mac,host) scoping must not leak the carve-out across devices.
    local cmds, exec = recorder()
    local r = { client_ip = "192.168.10.159", name = "static.kastatic.org",
                ip = "1.2.3.4", family = "v4" }
    local n = sets.maybe_populate_ea(r, deps({
      ea4 = { ["aa_bb_cc_dd_ee_ff"] = { kastatic_org = true } },
      exec_fn = exec,
    }))
    assert.equal(0, n)
    assert.equal(0, #cmds)
  end)
end)

-- ---------------------------------------------------------------------------
-- #2095: apply-time ea_/ea6_ backfill from the persisted ip→host cache.
--
-- The `nft -f` in policy.apply deletes+recreates `table inet wifihaven`, so
-- every per-(mac,host) ea_/ea6_ carve set is EMPTY right after a policy
-- apply and only refills when the device next RESOLVES a carved host over
-- the live query log (dns-tail tails with latency). A device holding a
-- long-cached CDN IP (KaTeX/MathJax on cdn.jsdelivr.net) can reconnect
-- before that refill and get caught by the whole-MAC drop even though the
-- host IS in extraAllowed (#2094 residual, #1929-class v6 drop). backfill_ea
-- closes the post-apply window by seeding the carve sets from the recent
-- ip→host resolutions dns-tail already persisted — for BOTH families.
-- ---------------------------------------------------------------------------
describe("backfill_ea", function()
  -- carve index: { [sanhost] = { [sanmac]=true, ... } } — the MACs that carve
  -- each host, mirroring render.effective_extra_allowed_by_mac's output after
  -- sanitization. jsdelivr.net carved for one MAC ca:ef:a1:72:6a:a3.
  local KID = "ca_ef_a1_72_6a_a3"
  local function carve_jsdelivr()
    return { jsdelivr_net = { [KID] = true } }
  end
  local function bdeps(extra)
    local d = { nft_table = "inet wifihaven" }
    for k, v in pairs(extra or {}) do d[k] = v end
    return d
  end

  it("seeds ea_ from a cached v4 resolution of a subdomain of the carved host", function()
    local cmds, exec = recorder()
    -- cdn.jsdelivr.net is a subdomain of the carved jsdelivr.net → suffix hit.
    local cache = { ["151.101.1.229"] = "cdn.jsdelivr.net" }
    local n = sets.backfill_ea(cache, carve_jsdelivr(), bdeps({ exec_fn = exec }))
    assert.equal(1, n)
    assert.is_true(added(cmds, "ea_" .. KID .. "_jsdelivr_net", "151.101.1.229"))
  end)

  it("seeds ea6_ from a cached v6 resolution — the #2095 headline case", function()
    local cmds, exec = recorder()
    local cache = { ["2606:4700::6811:d005"] = "cdn.jsdelivr.net" }
    local n = sets.backfill_ea(cache, carve_jsdelivr(), bdeps({ exec_fn = exec }))
    assert.equal(1, n)
    assert.is_true(added(cmds, "ea6_" .. KID .. "_jsdelivr_net", "2606:4700::6811:d005"))
  end)

  it("seeds BOTH families so a carved host is reachable over v4 and v6", function()
    local cmds, exec = recorder()
    local cache = {
      ["151.101.1.229"]          = "cdn.jsdelivr.net",
      ["2606:4700::6811:d005"]   = "cdn.jsdelivr.net",
    }
    local n = sets.backfill_ea(cache, carve_jsdelivr(), bdeps({ exec_fn = exec }))
    assert.equal(2, n)
    assert.is_true(added(cmds, "ea_"  .. KID .. "_jsdelivr_net", "151.101.1.229"))
    assert.is_true(added(cmds, "ea6_" .. KID .. "_jsdelivr_net", "2606:4700::6811:d005"))
  end)

  it("seeds every MAC that carves the host (per-(mac,host) fan-out)", function()
    local cmds, exec = recorder()
    local OTHER = "76_2d_95_47_d1_8e"
    local carve = { jsdelivr_net = { [KID] = true, [OTHER] = true } }
    local cache = { ["151.101.1.229"] = "cdn.jsdelivr.net" }
    local n = sets.backfill_ea(cache, carve, bdeps({ exec_fn = exec }))
    assert.equal(2, n)
    assert.is_true(added(cmds, "ea_" .. KID   .. "_jsdelivr_net", "151.101.1.229"))
    assert.is_true(added(cmds, "ea_" .. OTHER .. "_jsdelivr_net", "151.101.1.229"))
  end)

  it("recovers the branded head for a directly-queried CDN target (#1346 parity)", function()
    -- Answered name is a bare CDN target that does NOT suffix-match the carved
    -- brand; only the recovered alias head does. backfill must honour the same
    -- resolve_head recovery the live populator uses.
    local cmds, exec = recorder()
    local cache = { ["199.232.65.42"] = "prod.khan.map.fastly.net" }
    local carve = { kastatic_org = { [KID] = true } }
    local resolve_head = function(name)
      if name == "prod.khan.map.fastly.net" then return "cdn.kastatic.org" end
      return name
    end
    local n = sets.backfill_ea(cache, carve, bdeps({ exec_fn = exec, resolve_head = resolve_head }))
    assert.equal(1, n)
    assert.is_true(added(cmds, "ea_" .. KID .. "_kastatic_org", "199.232.65.42"))
  end)

  it("adds nothing for a cached host that is not carved", function()
    local cmds, exec = recorder()
    local cache = { ["93.184.216.34"] = "example.com" }
    local n = sets.backfill_ea(cache, carve_jsdelivr(), bdeps({ exec_fn = exec }))
    assert.equal(0, n)
    assert.equal(0, #cmds)
  end)

  it("is a no-op for an empty cache or empty carve index", function()
    local cmds, exec = recorder()
    assert.equal(0, sets.backfill_ea({}, carve_jsdelivr(), bdeps({ exec_fn = exec })))
    assert.equal(0, sets.backfill_ea({ ["1.2.3.4"] = "cdn.jsdelivr.net" }, {}, bdeps({ exec_fn = exec })))
    assert.equal(0, #cmds)
  end)

  it("rejects a cache ip carrying shell metacharacters", function()
    local cmds, exec = recorder()
    local cache = { ["1.2.3.4; rm -rf /"] = "cdn.jsdelivr.net" }
    -- host suffix-matches, but safe_addr rejects the ip → no command issued.
    sets.backfill_ea(cache, carve_jsdelivr(), bdeps({ exec_fn = exec }))
    for _, c in ipairs(cmds) do
      assert.is_nil(c:find("rm -rf", 1, true), "unsafe ip must never reach the shell")
    end
  end)
end)
