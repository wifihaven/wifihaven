-- Tests for openwrt/files/usr/lib/lua/wifihaven/dns_tail_sets.lua
--
-- dns_tail_sets holds the pure nft-set populator logic the wifihaven-dns-tail
-- sidecar runs out of band against the dnsmasq query log:
--   * resolved_<mac>  (#505, blockIpOnly)
--   * eb_<sanhost>    (#515, extraBlocked)
--   * ea_<sanhost> (#1346, extraAllowed carve-out; per host since #2900)
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

  it("indexes ea_/ea6_ sets by sanitized host (#2900)", function()
    local s = blank()
    sets.classify_set_line("\t\tset ea_kastatic_org {", s)
    sets.classify_set_line("\t\tset ea6_kastatic_org {", s)
    sets.classify_set_line("\t\tset ea_www_khanacademy_org {", s)
    assert.is_true(s.ea4["kastatic_org"])
    assert.is_true(s.ea6["kastatic_org"])
    assert.is_true(s.ea4["www_khanacademy_org"])
    -- ea6_ never lands in the v4 index and vice versa.
    assert.is_nil(s.ea4["6_kastatic_org"])
    assert.is_nil(s.ea6["www_khanacademy_org"])
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
-- ea_ / ea6_ populator (#1346; per host since #2900, the same walk as eb_)
-- ---------------------------------------------------------------------------

describe("maybe_populate_ea", function()
  local function deps(extra)
    local d = {
      ea4 = {}, ea6 = {},
      nft_table = "inet wifihaven",
    }
    for k, v in pairs(extra or {}) do d[k] = v end
    return d
  end

  it("adds the resolved IP to ea_<sanhost>", function()
    local cmds, exec = recorder()
    local r = { client_ip = "192.168.10.159", name = "static.kastatic.org",
                ip = "151.101.1.42", family = "v4" }
    local n = sets.maybe_populate_ea(r, deps({
      ea4 = { kastatic_org = true },
      exec_fn = exec,
    }))
    assert.equal(1, n)
    assert.is_true(added(cmds, "ea_kastatic_org", "151.101.1.42"))
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
      ea4 = { kastatic_org = true },
      exec_fn = exec, resolve_head = c.resolve_head,
    }))
    assert.equal(1, n)
    assert.is_true(added(cmds, "ea_kastatic_org", "151.101.65.42"))
  end)

  it("populates ea6_ for AAAA answers", function()
    local cmds, exec = recorder()
    local r = { client_ip = "192.168.10.159", name = "static.kastatic.org",
                ip = "2606:2800::5", family = "v6" }
    sets.maybe_populate_ea(r, deps({
      ea6 = { kastatic_org = true },
      exec_fn = exec,
    }))
    assert.is_true(added(cmds, "ea6_kastatic_org", "2606:2800::5"))
  end)

  -- #2900: like dnsmasq's own nftset= callback, any client's reply fills the
  -- set; which MACs it carves for is decided by the nft rules.
  it("populates from any client, including one with no DHCP lease", function()
    local cmds, exec = recorder()
    local r = { client_ip = "10.0.0.99", name = "static.kastatic.org",
                ip = "1.2.3.4", family = "v4" }
    local n = sets.maybe_populate_ea(r, deps({
      ea4 = { kastatic_org = true },
      exec_fn = exec,
    }))
    assert.equal(1, n)
    assert.is_true(added(cmds, "ea_kastatic_org", "1.2.3.4"))
  end)

  it("does nothing for a host with no declared ea_ set", function()
    local cmds, exec = recorder()
    local r = { client_ip = "192.168.10.159", name = "static.kastatic.org",
                ip = "1.2.3.4", family = "v4" }
    local n = sets.maybe_populate_ea(r, deps({
      ea4 = { khanacademy_org = true },
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
-- every ea_/ea6_ carve set is EMPTY right after a policy
-- apply and only refills when the device next RESOLVES a carved host over
-- the live query log (dns-tail tails with latency). A device holding a
-- long-cached CDN IP (KaTeX/MathJax on cdn.jsdelivr.net) can reconnect
-- before that refill and get caught by the whole-MAC drop even though the
-- host IS in extraAllowed (#2094 residual, #1929-class v6 drop). backfill_ea
-- closes the post-apply window by seeding the carve sets from the recent
-- ip→host resolutions dns-tail already persisted — for BOTH families.
-- ---------------------------------------------------------------------------
describe("backfill_ea", function()
  -- carve index: { [sanhost] = true } — render.extra_allowed_hosts after
  -- sanitization (#2900: the sets are per host).
  local function carve_jsdelivr()
    return { jsdelivr_net = true }
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
    assert.is_true(added(cmds, "ea_jsdelivr_net", "151.101.1.229"))
  end)

  it("seeds ea6_ from a cached v6 resolution — the #2095 headline case", function()
    local cmds, exec = recorder()
    local cache = { ["2606:4700::6811:d005"] = "cdn.jsdelivr.net" }
    local n = sets.backfill_ea(cache, carve_jsdelivr(), bdeps({ exec_fn = exec }))
    assert.equal(1, n)
    assert.is_true(added(cmds, "ea6_jsdelivr_net", "2606:4700::6811:d005"))
  end)

  it("seeds BOTH families so a carved host is reachable over v4 and v6", function()
    local cmds, exec = recorder()
    local cache = {
      ["151.101.1.229"]          = "cdn.jsdelivr.net",
      ["2606:4700::6811:d005"]   = "cdn.jsdelivr.net",
    }
    local n = sets.backfill_ea(cache, carve_jsdelivr(), bdeps({ exec_fn = exec }))
    assert.equal(2, n)
    assert.is_true(added(cmds, "ea_jsdelivr_net", "151.101.1.229"))
    assert.is_true(added(cmds, "ea6_jsdelivr_net", "2606:4700::6811:d005"))
  end)

  it("seeds one set per host, however many MACs carve it (#2900)", function()
    local cmds, exec = recorder()
    local cache = { ["151.101.1.229"] = "cdn.jsdelivr.net" }
    local n = sets.backfill_ea(cache, carve_jsdelivr(), bdeps({ exec_fn = exec }))
    assert.equal(1, n)
    assert.equal(1, #cmds)
    assert.is_true(added(cmds, "ea_jsdelivr_net", "151.101.1.229"))
  end)

  it("recovers the branded head for a directly-queried CDN target (#1346 parity)", function()
    -- Answered name is a bare CDN target that does NOT suffix-match the carved
    -- brand; only the recovered alias head does. backfill must honour the same
    -- resolve_head recovery the live populator uses.
    local cmds, exec = recorder()
    local cache = { ["199.232.65.42"] = "prod.khan.map.fastly.net" }
    local carve = { kastatic_org = true }
    local resolve_head = function(name)
      if name == "prod.khan.map.fastly.net" then return "cdn.kastatic.org" end
      return name
    end
    local n = sets.backfill_ea(cache, carve, bdeps({ exec_fn = exec, resolve_head = resolve_head }))
    assert.equal(1, n)
    assert.is_true(added(cmds, "ea_kastatic_org", "199.232.65.42"))
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

-- ---------------------------------------------------------------------------
-- #2208: batched ea_/ea6_ backfill script builder.
--
-- backfill_ea spawns one `nft add element` PROCESS per element — on a busy
-- prod cache with broad carve hosts that is thousands of ~6ms fork+exec's,
-- and it dominated policy-apply latency (the v0.3.19→v0.3.20 regression:
-- ~24–30s for a profile pause on prod). build_ea_backfill_script folds the
-- SAME walk/dedup semantics into a single `nft -f` ruleset string so
-- policy.apply pays O(1) process spawns regardless of element count.
-- ---------------------------------------------------------------------------
describe("build_ea_backfill_script (#2208)", function()
  local function carve_jsdelivr()
    return { jsdelivr_net = true }
  end
  local function bdeps(extra)
    local d = { nft_table = "inet wifihaven" }
    for k, v in pairs(extra or {}) do d[k] = v end
    return d
  end

  it("emits one `add element` line per set with all ips grouped", function()
    local cache = {
      ["151.101.1.229"]  = "cdn.jsdelivr.net",
      ["151.101.65.229"] = "fastly.jsdelivr.net",
    }
    local script, n = sets.build_ea_backfill_script(cache, carve_jsdelivr(), bdeps())
    assert.equal(2, n)
    -- Both v4 ips land in ONE line for the host's set, sorted.
    assert.equal(
      "add element inet wifihaven ea_jsdelivr_net "
        .. "{ 151.101.1.229, 151.101.65.229 }\n",
      script)
  end)

  it("splits families into ea_ vs ea6_ target sets", function()
    local cache = {
      ["151.101.1.229"]        = "cdn.jsdelivr.net",
      ["2606:4700::6811:d005"] = "cdn.jsdelivr.net",
    }
    local script, n = sets.build_ea_backfill_script(cache, carve_jsdelivr(), bdeps())
    assert.equal(2, n)
    assert.is_truthy(script:find(
      "add element inet wifihaven ea_jsdelivr_net { 151.101.1.229 }", 1, true))
    assert.is_truthy(script:find(
      "add element inet wifihaven ea6_jsdelivr_net { 2606:4700::6811:d005 }", 1, true))
  end)

  it("targets the one per-host set (backfill_ea parity, #2900)", function()
    local cache = { ["151.101.1.229"] = "cdn.jsdelivr.net" }
    local script, n = sets.build_ea_backfill_script(cache, carve_jsdelivr(), bdeps())
    assert.equal(1, n)
    assert.equal("add element inet wifihaven ea_jsdelivr_net { 151.101.1.229 }\n", script)
  end)

  it("recovers the branded head via resolve_head (#1346 parity)", function()
    local cache = { ["199.232.65.42"] = "prod.khan.map.fastly.net" }
    local carve = { kastatic_org = true }
    local resolve_head = function(name)
      if name == "prod.khan.map.fastly.net" then return "cdn.kastatic.org" end
      return name
    end
    local script, n = sets.build_ea_backfill_script(cache, carve,
      bdeps({ resolve_head = resolve_head }))
    assert.equal(1, n)
    assert.is_truthy(script:find(
      "add element inet wifihaven ea_kastatic_org { 199.232.65.42 }", 1, true))
  end)

  it("returns empty script + 0 for no matches / empty cache / empty carve", function()
    local s1, n1 = sets.build_ea_backfill_script(
      { ["93.184.216.34"] = "example.com" }, carve_jsdelivr(), bdeps())
    assert.equal(0, n1); assert.equal("", s1)
    local s2, n2 = sets.build_ea_backfill_script({}, carve_jsdelivr(), bdeps())
    assert.equal(0, n2); assert.equal("", s2)
    local s3, n3 = sets.build_ea_backfill_script(
      { ["1.2.3.4"] = "cdn.jsdelivr.net" }, {}, bdeps())
    assert.equal(0, n3); assert.equal("", s3)
  end)

  it("never emits an ip that fails safe_addr (injection guard)", function()
    local cache = {
      ["1.2.3.4; rm -rf /"] = "cdn.jsdelivr.net",
      ["151.101.1.229"]     = "cdn.jsdelivr.net",
    }
    local script, n = sets.build_ea_backfill_script(cache, carve_jsdelivr(), bdeps())
    assert.equal(1, n)
    assert.is_nil(script:find("rm -rf", 1, true), "unsafe ip must never reach the script")
    assert.is_truthy(script:find("{ 151.101.1.229 }", 1, true))
  end)
end)

-- ---------------------------------------------------------------------------
-- #2923: @global_allow / @global_allow6 populator + backfill
-- ---------------------------------------------------------------------------
--
-- global.extraAllowed carries the WifiHaven UI / API hosts, which a whole-MAC
-- blocked device (CheckedOut, Paused, ...) must still reach. Its two fleet-wide
-- sets were filled ONLY by dnsmasq's nftset= callback, which never fires on a
-- `cached` answer and fails for hours on the deployed 2.91 build. Every apply's
-- `nft -f` empties them, and since #2900 a check-out doesn't restart dnsmasq, so
-- a device that was using the app keeps getting cached answers and the sets stay
-- empty: the checked-out device can't reach WifiHaven to check in (prod,
-- ca:ef:a1:72:6a:a3, 2026-10-22). dns-tail and the apply-time backfill now fill
-- them the same way they fill ea_.

local APP_V4   = "104.21.73.230"
local APP_V6   = "2606:4700:3031::6815:49e6"
local OTHER_V4 = "93.184.215.14"

describe("parse_global_allow_index (#2923)", function()
  it("maps each listed host to its sanitized name", function()
    local ga = sets.parse_global_allow_index("api.wifihaven.net\napp.wifihaven.net\n")
    assert.same({ api_wifihaven_net = true, app_wifihaven_net = true }, ga)
  end)

  it("ignores blank lines and returns an empty index for empty/nil input", function()
    assert.same({ app_wifihaven_net = true }, sets.parse_global_allow_index("\n app.wifihaven.net \n\n"))
    assert.same({}, sets.parse_global_allow_index(""))
    assert.same({}, sets.parse_global_allow_index(nil))
  end)
end)

describe("maybe_populate_ga (#2923)", function()
  local GA = { api_wifihaven_net = true, app_wifihaven_net = true }
  local function gdeps(exec)
    return { ga = GA, nft_table = "inet wifihaven", exec_fn = exec }
  end

  it("adds a CACHED v4 answer for a global-allow host to @global_allow", function()
    -- The prod line: dnsmasq answered the checked-out device from its cache, so
    -- its own nftset= callback never ran.
    local cmds, exec = recorder()
    local r = dns_log.parse_resolved_reply(
      "Oct 22 00:13:08 dnsmasq[1]: 7191 192.168.10.239/46019 cached app.wifihaven.net is " .. APP_V4)
    assert.is_truthy(r, "fixture line must parse")
    assert.equal(1, sets.maybe_populate_ga(r, gdeps(exec)))
    assert.is_true(added(cmds, "global_allow ", APP_V4))
  end)

  it("adds a v6 answer to @global_allow6, not @global_allow", function()
    local cmds, exec = recorder()
    local r = dns_log.parse_resolved_reply(
      "Oct 22 00:13:08 dnsmasq[1]: 7190 192.168.10.239/13928 cached app.wifihaven.net is " .. APP_V6)
    assert.is_truthy(r, "fixture line must parse")
    assert.equal(1, sets.maybe_populate_ga(r, gdeps(exec)))
    assert.equal(1, #cmds)
    assert.is_truthy(cmds[1]:find("global_allow6 ", 1, true), cmds[1])
  end)

  it("adds the WifiHaven answer and nothing for any other host (anchored)", function()
    -- Liveness anchor and absence in the same run: the populator is shown live
    -- by the app host's add, so the other host's missing add is a real absence.
    local cmds, exec = recorder()
    local d = gdeps(exec)
    sets.maybe_populate_ga({ name = "app.wifihaven.net", ip = APP_V4, family = "v4" }, d)
    sets.maybe_populate_ga({ name = "example.org", ip = OTHER_V4, family = "v4" }, d)
    -- A name that merely ends in the same letters is not a subdomain.
    sets.maybe_populate_ga({ name = "evilapp.wifihaven.net.example", ip = "198.51.100.7", family = "v4" }, d)
    assert.is_true(added(cmds, "global_allow ", APP_V4))
    assert.equal(1, #cmds, table.concat(cmds, "\n"))
  end)

  it("recovers the head for a directly-queried CNAME target (api.wifihaven.net)", function()
    local cmds, exec = recorder()
    local d = gdeps(exec)
    d.resolve_head = function(name)
      if name == "gcp-us-west1-1.origin.onrender.com.cdn.cloudflare.net" then
        return "api.wifihaven.net"
      end
      return name
    end
    sets.maybe_populate_ga({ name = "gcp-us-west1-1.origin.onrender.com.cdn.cloudflare.net",
                             ip = "216.24.57.16", family = "v4" }, d)
    assert.is_true(added(cmds, "global_allow ", "216.24.57.16"))
  end)

  it("is a no-op with no index", function()
    local cmds, exec = recorder()
    assert.equal(0, sets.maybe_populate_ga({ name = "app.wifihaven.net", ip = APP_V4, family = "v4" },
      { nft_table = "inet wifihaven", exec_fn = exec }))
    assert.equal(0, #cmds)
  end)
end)

describe("build_ea_backfill_script with global allow (#2923)", function()
  local GA = { app_wifihaven_net = true }

  it("seeds @global_allow and @global_allow6 from the cache, and nothing else", function()
    local cache = {
      [APP_V4]   = "app.wifihaven.net",
      [APP_V6]   = "app.wifihaven.net",
      [OTHER_V4] = "example.org",
    }
    local script, n = sets.build_ea_backfill_script(cache, {}, { nft_table = "inet wifihaven", ga = GA })
    assert.equal(2, n)
    assert.equal(
      "add element inet wifihaven global_allow { " .. APP_V4 .. " }\n" ..
      "add element inet wifihaven global_allow6 { " .. APP_V6 .. " }\n",
      script)
  end)

  it("seeds global allow alongside the ea_ carve in one script", function()
    local cache = {
      [APP_V4]          = "app.wifihaven.net",
      ["151.101.1.229"] = "cdn.jsdelivr.net",
    }
    local script, n = sets.build_ea_backfill_script(cache, { jsdelivr_net = true },
      { nft_table = "inet wifihaven", ga = GA })
    assert.equal(2, n)
    assert.is_truthy(script:find("ea_jsdelivr_net { 151.101.1.229 }", 1, true), script)
    assert.is_truthy(script:find("global_allow { " .. APP_V4 .. " }", 1, true), script)
  end)

  it("backfill_ea (per-element fallback) seeds global allow too", function()
    local cmds, exec = recorder()
    local n = sets.backfill_ea({ [APP_V6] = "app.wifihaven.net", [OTHER_V4] = "example.org" }, {},
      { nft_table = "inet wifihaven", ga = GA, exec_fn = exec })
    assert.equal(1, n)
    assert.is_true(added(cmds, "global_allow6 ", APP_V6))
  end)
end)
