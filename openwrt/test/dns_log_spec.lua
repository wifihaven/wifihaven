-- Tests for openwrt/files/usr/lib/lua/wifihaven/dns_log.lua
--
-- dns_log is the forward-lookup hostname cache the agent uses to attribute
-- connection_attempt events to the domain the client actually resolved
-- (architecture.md §7.1). It tails dnsmasq's `--log-queries=extra` output
-- and maintains an in-memory map `dst_ip → original_qname`.
--
-- Run with: busted openwrt/test/dns_log_spec.lua

local dns_log = require("dns_log")

-- ---------------------------------------------------------------------------
-- 1. Line parsing
-- ---------------------------------------------------------------------------

describe("parse_query_line", function()
  it("parses a `query[A]` line with syslog prefix", function()
    local line =
      "Nov 12 10:00:01 dnsmasq[1234]: 7 192.168.1.42/54321 query[A] youtube.com from 192.168.1.42"
    local q = dns_log.parse_query_line(line)
    assert.is_not_nil(q)
    assert.equal("7",            q.qid)
    assert.equal("youtube.com",  q.qname)
  end)

  it("parses a `query[AAAA]` line", function()
    local line =
      "Nov 12 10:00:01 dnsmasq[1234]: 8 192.168.1.42/54321 query[AAAA] news.example.org from 192.168.1.42"
    local q = dns_log.parse_query_line(line)
    assert.is_not_nil(q)
    assert.equal("8",                 q.qid)
    assert.equal("news.example.org",  q.qname)
  end)

  it("parses a `query[A]` line written without syslog prefix (log-facility=file)", function()
    local line = "1234 192.168.1.42/54321 query[A] youtube.com from 192.168.1.42"
    local q = dns_log.parse_query_line(line)
    assert.is_not_nil(q)
    assert.equal("1234",        q.qid)
    assert.equal("youtube.com", q.qname)
  end)

  it("returns nil for non-query lines", function()
    assert.is_nil(dns_log.parse_query_line(
      "Nov 12 10:00:01 dnsmasq[1234]: started, version 2.86"))
    assert.is_nil(dns_log.parse_query_line(
      "Nov 12 10:00:01 dnsmasq[1234]: 7 192.168.1.42/54321 forwarded youtube.com to 1.1.1.1"))
    assert.is_nil(dns_log.parse_query_line(""))
  end)
end)

describe("parse_reply_line", function()
  it("parses a `reply X is <ipv4>` line", function()
    local line =
      "Nov 12 10:00:01 dnsmasq[1234]: 7 192.168.1.42/54321 reply youtube-ui.l.google.com is 142.250.80.46"
    local r = dns_log.parse_reply_line(line)
    assert.is_not_nil(r)
    assert.equal("7",                          r.qid)
    assert.equal("youtube-ui.l.google.com",    r.name)
    assert.equal("142.250.80.46",              r.ip)
  end)

  it("returns ip=nil for a CNAME reply (still surfaces the qid/name)", function()
    local line =
      "Nov 12 10:00:01 dnsmasq[1234]: 7 192.168.1.42/54321 reply youtube.com is <CNAME>"
    local r = dns_log.parse_reply_line(line)
    assert.is_not_nil(r)
    assert.equal("7",            r.qid)
    assert.equal("youtube.com",  r.name)
    assert.is_nil(r.ip)
  end)

  it("returns nil for NXDOMAIN / NODATA / non-reply lines", function()
    assert.is_nil(dns_log.parse_reply_line(
      "Nov 12 10:00:01 dnsmasq[1234]: 7 192.168.1.42/54321 reply youtube.com is NXDOMAIN"))
    assert.is_nil(dns_log.parse_reply_line(
      "Nov 12 10:00:01 dnsmasq[1234]: 7 192.168.1.42/54321 reply youtube.com is NODATA-IPv6"))
    assert.is_nil(dns_log.parse_reply_line(
      "Nov 12 10:00:01 dnsmasq[1234]: started, version 2.86"))
    assert.is_nil(dns_log.parse_reply_line(""))
  end)

  -- #1668: conntrack flows are NOT v4-only — the agent already maintains
  -- parallel eb6_/bl6_/resolved6_ nft sets for v6 destinations, and
  -- connection_events fire for v6 dst_ip exactly as they do for v4. The
  -- attribution cache must therefore ingest AAAA answers too, otherwise
  -- every v6 destination (blocked or allowed) shows up as a bare v6
  -- literal in connection_events instead of an FQDN.
  it("#1668: parses a `reply X is <ipv6>` line (AAAA answers must feed the cache)", function()
    local line =
      "Nov 12 10:00:01 dnsmasq[1234]: 7 192.168.1.42/54321 reply youtube.com is 2607:f8b0::1"
    local r = dns_log.parse_reply_line(line)
    assert.is_not_nil(r)
    assert.equal("7",            r.qid)
    assert.equal("youtube.com",  r.name)
    assert.equal("2607:f8b0::1", r.ip)
  end)

  it("#1668: parses a `cached X is <ipv6>` line identically", function()
    local line =
      "Nov 12 10:00:01 dnsmasq[1234]: 8 192.168.1.42/54321 cached example.com is 2606:4700::6810:84e5"
    local r = dns_log.parse_reply_line(line)
    assert.is_not_nil(r)
    assert.equal("8",                       r.qid)
    assert.equal("example.com",             r.name)
    assert.equal("2606:4700::6810:84e5",    r.ip)
  end)

  -- dnsmasq emits `cached <name> is <ip>` instead of `reply ...` when answering
  -- from its own in-memory cache. dns-tail must parse those identically — see
  -- the verb-set comment in dns_log.lua (#480).
  it("parses a `cached X is <ipv4>` line the same as a reply", function()
    local line =
      "Nov 12 10:00:01 dnsmasq[1234]: 8 192.168.1.42/54321 cached example.com is 93.184.216.34"
    local r = dns_log.parse_reply_line(line)
    assert.is_not_nil(r)
    assert.equal("8",           r.qid)
    assert.equal("example.com", r.name)
    assert.equal("93.184.216.34", r.ip)
  end)

  it("ignores other verbs like `forwarded` and `config`", function()
    assert.is_nil(dns_log.parse_reply_line(
      "Nov 12 10:00:01 dnsmasq[1]: 9 192.168.1.42/54321 forwarded example.com to 1.1.1.1"))
    assert.is_nil(dns_log.parse_reply_line(
      "Nov 12 10:00:01 dnsmasq[1]: 9 127.0.0.1/54321 config error is REFUSED"))
  end)
end)

-- parse_resolved_reply: feeds the dns-tail-driven blockIpOnly populator
-- (#505). Unlike parse_reply_line it must extract the *client* IP from the
-- log line (we use it to map back to a MAC via /tmp/dhcp.leases) and must
-- return v6 answers too (resolved6_<mac> drops v6 daddrs the same way
-- resolved_<mac> drops v4 daddrs).
describe("parse_resolved_reply (#505)", function()
  it("returns client_ip + v4 ip + family=v4 for an A reply", function()
    local r = dns_log.parse_resolved_reply(
      "Nov 12 10:00:01 dnsmasq[1234]: 7 192.168.1.42/54321 reply youtube.com is 142.250.80.46")
    assert.is_not_nil(r)
    assert.equal("192.168.1.42",   r.client_ip)
    assert.equal("youtube.com",    r.name)
    assert.equal("142.250.80.46",  r.ip)
    assert.equal("v4",             r.family)
  end)

  -- #515: the per-host eb_<sanhost> populator keys on r.name, so make sure
  -- the parser is actually surfacing the qname (was previously discarded as
  -- `_name` when only the bio populator needed the line).
  it("surfaces the answered name on v6 replies too (#515)", function()
    local r = dns_log.parse_resolved_reply(
      "Nov 12 10:00:01 dnsmasq[1234]: 8 192.168.1.42/54321 reply example.com is 2606:2800:220:1::248")
    assert.is_not_nil(r)
    assert.equal("example.com",          r.name)
    assert.equal("2606:2800:220:1::248", r.ip)
    assert.equal("v6",                   r.family)
  end)

  it("returns client_ip + v6 ip + family=v6 for an AAAA reply", function()
    local r = dns_log.parse_resolved_reply(
      "Nov 12 10:00:01 dnsmasq[1234]: 8 192.168.1.42/54321 reply youtube.com is 2607:f8b0:4005:80a::200e")
    assert.is_not_nil(r)
    assert.equal("192.168.1.42",              r.client_ip)
    assert.equal("2607:f8b0:4005:80a::200e",  r.ip)
    assert.equal("v6",                        r.family)
  end)

  it("parses `cached` lines the same as `reply` lines", function()
    local r = dns_log.parse_resolved_reply(
      "Nov 12 10:00:01 dnsmasq[1234]: 9 192.168.1.42/54321 cached example.com is 93.184.216.34")
    assert.is_not_nil(r)
    assert.equal("192.168.1.42",   r.client_ip)
    assert.equal("93.184.216.34",  r.ip)
    assert.equal("v4",             r.family)
  end)

  it("skips CNAME / NXDOMAIN / NODATA answers", function()
    assert.is_nil(dns_log.parse_resolved_reply(
      "Nov 12 10:00:01 dnsmasq[1]: 7 192.168.1.42/54321 reply youtube.com is <CNAME>"))
    assert.is_nil(dns_log.parse_resolved_reply(
      "Nov 12 10:00:01 dnsmasq[1]: 7 192.168.1.42/54321 reply youtube.com is NXDOMAIN"))
    assert.is_nil(dns_log.parse_resolved_reply(
      "Nov 12 10:00:01 dnsmasq[1]: 7 192.168.1.42/54321 reply youtube.com is NODATA-IPv6"))
  end)

  it("rejects lines whose client IP isn't a v4 lease (no MAC lookup possible)", function()
    -- A query from the router itself (loopback) — we'd have no way to scope
    -- the populator to a MAC, so skip the line.
    local r = dns_log.parse_resolved_reply(
      "Nov 12 10:00:01 dnsmasq[1]: 7 ::1/54321 reply youtube.com is 142.250.80.46")
    assert.is_nil(r)
  end)

  it("ignores non-reply verbs", function()
    assert.is_nil(dns_log.parse_resolved_reply(
      "Nov 12 10:00:01 dnsmasq[1]: 9 192.168.1.42/54321 forwarded example.com to 1.1.1.1"))
    assert.is_nil(dns_log.parse_resolved_reply(""))
    assert.is_nil(dns_log.parse_resolved_reply(nil))
  end)
end)

-- ---------------------------------------------------------------------------
-- 2. Cache: ingest + lookup
-- ---------------------------------------------------------------------------

describe("cache.ingest_line + lookup", function()
  local function fake_clock()
    local t = 1000000
    return {
      now  = function() return t end,
      advance = function(secs) t = t + secs end,
    }
  end

  it("records ip → original qname after a query+reply pair", function()
    local clk = fake_clock()
    local c = dns_log.new({ ttl_seconds = 3600, now_fn = clk.now })

    c.ingest_line(
      "Nov 12 10:00:01 dnsmasq[1]: 7 192.168.1.42/54321 query[A] youtube.com from 192.168.1.42")
    c.ingest_line(
      "Nov 12 10:00:01 dnsmasq[1]: 7 192.168.1.42/54321 reply youtube.com is 142.250.80.46")

    assert.equal("youtube.com", c.lookup("142.250.80.46"))
  end)

  it("uses the original qname across a CNAME chain (not the final CNAME target)", function()
    local clk = fake_clock()
    local c = dns_log.new({ ttl_seconds = 3600, now_fn = clk.now })

    c.ingest_line("7 192.168.1.42/54321 query[A] youtube.com from 192.168.1.42")
    c.ingest_line("7 192.168.1.42/54321 reply youtube.com is <CNAME>")
    c.ingest_line("7 192.168.1.42/54321 reply youtube-ui.l.google.com is <CNAME>")
    c.ingest_line("7 192.168.1.42/54321 reply www.l.google.com is 142.250.80.46")

    assert.equal("youtube.com", c.lookup("142.250.80.46"))
  end)

  it("supports multiple A-record IPs for one qname", function()
    local clk = fake_clock()
    local c = dns_log.new({ ttl_seconds = 3600, now_fn = clk.now })

    c.ingest_line("7 192.168.1.42/54321 query[A] youtube.com from 192.168.1.42")
    c.ingest_line("7 192.168.1.42/54321 reply youtube.com is 142.250.80.46")
    c.ingest_line("7 192.168.1.42/54321 reply youtube.com is 142.250.80.47")

    assert.equal("youtube.com", c.lookup("142.250.80.46"))
    assert.equal("youtube.com", c.lookup("142.250.80.47"))
  end)

  it("returns nil for an unknown ip", function()
    local clk = fake_clock()
    local c = dns_log.new({ ttl_seconds = 3600, now_fn = clk.now })
    assert.is_nil(c.lookup("9.9.9.9"))
  end)

  it("ignores lines that match no parser", function()
    local clk = fake_clock()
    local c = dns_log.new({ ttl_seconds = 3600, now_fn = clk.now })
    c.ingest_line("garbage line")
    c.ingest_line("Nov 12 10:00:01 dnsmasq[1]: started, version 2.86")
    assert.equal(0, c.size())
  end)

  it("falls back to the reply name when there is no preceding query line for the qid", function()
    -- E.g. agent started mid-conversation and missed the query[A] line.
    local clk = fake_clock()
    local c = dns_log.new({ ttl_seconds = 3600, now_fn = clk.now })
    c.ingest_line("9 192.168.1.42/54321 reply news.example.org is 203.0.113.7")
    assert.equal("news.example.org", c.lookup("203.0.113.7"))
  end)

  it("most-recent ingest wins when two qnames resolve to the same IP", function()
    local clk = fake_clock()
    local c = dns_log.new({ ttl_seconds = 3600, now_fn = clk.now })

    c.ingest_line("1 192.168.1.42/54321 query[A] a.example from 192.168.1.42")
    c.ingest_line("1 192.168.1.42/54321 reply a.example is 1.2.3.4")

    clk.advance(10)
    c.ingest_line("2 192.168.1.42/54322 query[A] b.example from 192.168.1.42")
    c.ingest_line("2 192.168.1.42/54322 reply b.example is 1.2.3.4")

    assert.equal("b.example", c.lookup("1.2.3.4"))
  end)
end)

-- ---------------------------------------------------------------------------
-- 2b. CNAME-alias attribution (#1344)
--
-- Some clients (notably Apple devices) resolve a branded host, then re-query
-- the FINAL CNAME target *directly* as a separate query. On that direct query
-- the qname on the wire IS the CDN target (e.g. `prod.khan.map.fastly.net`),
-- so qid correlation — which works fine — still attributes the flow to the
-- CDN target. That target does not suffix-match the app's `extraAllowed`
-- entry (`kastatic.org` / `khanacademy.org`), so the flow is mislabelled and,
-- during a whole-MAC block, mis-classified as blocked.
--
-- The branded chain WAS observed earlier (the client did resolve the branded
-- host), so the cache can learn `<cdn target> → <branded chain head>` and
-- attribute the later direct-target query back to the branded ancestor.
--
-- These cases use the real prod dnsmasq 2.91 `--log-queries=extra` line shapes
-- captured on router.lan (issue #1344): a shared qid across the chain, the
-- intervening `forwarded`/`nftset` lines (ignored by the parsers), and a
-- separate direct query for the CNAME target.
-- ---------------------------------------------------------------------------

describe("CNAME-alias attribution (#1344)", function()
  local function fake_clock()
    local t = 1000000
    return {
      now  = function() return t end,
      advance = function(secs) t = t + secs end,
    }
  end

  it("attributes a directly-queried CNAME target back to the branded chain head", function()
    local clk = fake_clock()
    local c = dns_log.new({ ttl_seconds = 3600, now_fn = clk.now })

    -- 1) Branded resolution observed first (real captured chain, qid 38053).
    c.ingest_line("38053 127.0.0.1/36172 query[A] cdn.kastatic.org from 127.0.0.1")
    c.ingest_line("38053 127.0.0.1/36172 forwarded cdn.kastatic.org to 2001:558:feed::1")
    c.ingest_line("38053 127.0.0.1/36172 reply cdn.kastatic.org is <CNAME>")
    c.ingest_line("38053 127.0.0.1/36172 reply fastly.kastatic.org is <CNAME>")
    c.ingest_line("38053 127.0.0.1/36172 reply prod.khan.map.fastly.net is 199.232.65.42")

    -- The branded chain itself attributes to the queried brand (works today).
    assert.equal("cdn.kastatic.org", c.lookup("199.232.65.42"))

    -- 2) The device later re-queries the CNAME target DIRECTLY, landing on a
    --    different Fastly anycast IP (real: 151.101.65.42 to 192.168.10.159).
    clk.advance(5)
    c.ingest_line("40000 192.168.10.159/61484 query[A] prod.khan.map.fastly.net from 192.168.10.159")
    c.ingest_line("40000 192.168.10.159/61484 reply prod.khan.map.fastly.net is 151.101.65.42")

    -- The direct-target flow must be attributed to the branded ancestor, NOT
    -- the CDN target — otherwise it can't suffix-match `kastatic.org`.
    assert.equal("cdn.kastatic.org", c.lookup("151.101.65.42"))
  end)

  it("recovers the brand for www.khanacademy.org → prod.khan.map.fastly.net", function()
    local clk = fake_clock()
    local c = dns_log.new({ ttl_seconds = 3600, now_fn = clk.now })

    c.ingest_line("20800 192.168.10.159/49808 query[A] www.khanacademy.org from 192.168.10.159")
    c.ingest_line("20800 192.168.10.159/49808 reply www.khanacademy.org is <CNAME>")
    c.ingest_line("20800 192.168.10.159/49808 reply prod.khan.map.fastly.net is 151.101.1.42")

    clk.advance(3)
    c.ingest_line("20817 192.168.10.159/61484 query[A] prod.khan.map.fastly.net from 192.168.10.159")
    c.ingest_line("20817 192.168.10.159/61484 reply prod.khan.map.fastly.net is 151.101.129.42")

    assert.equal("www.khanacademy.org", c.lookup("151.101.129.42"))
  end)

  it("resolves a directly-queried INTERMEDIATE CNAME hop back to the head too", function()
    local clk = fake_clock()
    local c = dns_log.new({ ttl_seconds = 3600, now_fn = clk.now })

    -- Brand chains through an intermediate to a final target.
    c.ingest_line("100 192.168.10.159/1000 query[A] cdn.kastatic.org from 192.168.10.159")
    c.ingest_line("100 192.168.10.159/1000 reply cdn.kastatic.org is <CNAME>")
    c.ingest_line("100 192.168.10.159/1000 reply fastly.kastatic.org is <CNAME>")
    c.ingest_line("100 192.168.10.159/1000 reply prod.khan.map.fastly.net is 1.2.3.4")

    -- Device re-queries the INTERMEDIATE hop directly.
    clk.advance(2)
    c.ingest_line("101 192.168.10.159/1001 query[A] fastly.kastatic.org from 192.168.10.159")
    c.ingest_line("101 192.168.10.159/1001 reply fastly.kastatic.org is <CNAME>")
    c.ingest_line("101 192.168.10.159/1001 reply prod.khan.map.fastly.net is 5.6.7.8")

    assert.equal("cdn.kastatic.org", c.lookup("5.6.7.8"))
  end)

  it("falls back to the target name when the brand chain was never observed (safe degradation)", function()
    -- No prior branded chain — the very first direct query for a CDN target
    -- can only be attributed to the target itself. Never WORSE than today.
    local clk = fake_clock()
    local c = dns_log.new({ ttl_seconds = 3600, now_fn = clk.now })

    c.ingest_line("9 192.168.10.159/1 query[A] prod.khan.map.fastly.net from 192.168.10.159")
    c.ingest_line("9 192.168.10.159/1 reply prod.khan.map.fastly.net is 199.232.65.42")

    assert.equal("prod.khan.map.fastly.net", c.lookup("199.232.65.42"))
  end)

  it("does not rewrite a normal (non-CNAME) qname", function()
    local clk = fake_clock()
    local c = dns_log.new({ ttl_seconds = 3600, now_fn = clk.now })

    c.ingest_line("1 192.168.1.42/54321 query[A] example.com from 192.168.1.42")
    c.ingest_line("1 192.168.1.42/54321 reply example.com is 93.184.216.34")

    assert.equal("example.com", c.lookup("93.184.216.34"))
  end)

  -- #1346: the kernel ea_/eb_ set populators in wifihaven-dns-tail need to
  -- recover the branded chain head for a directly-queried CDN target so they
  -- can suffix-match it against a declared ea_/eb_ set. #1345 learns the
  -- target → head map but kept resolve_head local; expose it publicly so the
  -- sidecar can reuse the same memory.
  describe("self.resolve_head (public alias resolution, #1346)", function()
    it("returns the branded head for a learned CNAME target", function()
      local clk = fake_clock()
      local c = dns_log.new({ ttl_seconds = 3600, now_fn = clk.now })

      c.ingest_line("38053 127.0.0.1/36172 query[A] cdn.kastatic.org from 127.0.0.1")
      c.ingest_line("38053 127.0.0.1/36172 reply cdn.kastatic.org is <CNAME>")
      c.ingest_line("38053 127.0.0.1/36172 reply fastly.kastatic.org is <CNAME>")
      c.ingest_line("38053 127.0.0.1/36172 reply prod.khan.map.fastly.net is 199.232.65.42")

      assert.is_function(c.resolve_head)
      assert.equal("cdn.kastatic.org", c.resolve_head("prod.khan.map.fastly.net"))
      assert.equal("cdn.kastatic.org", c.resolve_head("fastly.kastatic.org"))
    end)

    it("returns the name unchanged when it is not a known alias", function()
      local clk = fake_clock()
      local c = dns_log.new({ ttl_seconds = 3600, now_fn = clk.now })
      assert.equal("unknown.example", c.resolve_head("unknown.example"))
    end)
  end)
end)

-- ---------------------------------------------------------------------------
-- 3. TTL + size eviction
-- ---------------------------------------------------------------------------

describe("cache eviction", function()
  local function fake_clock()
    local t = 1000000
    return {
      now  = function() return t end,
      advance = function(secs) t = t + secs end,
    }
  end

  it("expires entries past ttl_seconds", function()
    local clk = fake_clock()
    local c = dns_log.new({ ttl_seconds = 60, now_fn = clk.now })

    c.ingest_line("1 192.168.1.42/54321 query[A] x.example from 192.168.1.42")
    c.ingest_line("1 192.168.1.42/54321 reply x.example is 1.2.3.4")
    assert.equal("x.example", c.lookup("1.2.3.4"))

    clk.advance(61)
    assert.is_nil(c.lookup("1.2.3.4"))
  end)

  it("tick(now) drops expired entries from internal storage", function()
    local clk = fake_clock()
    local c = dns_log.new({ ttl_seconds = 60, now_fn = clk.now })

    c.ingest_line("1 192.168.1.42/54321 query[A] x.example from 192.168.1.42")
    c.ingest_line("1 192.168.1.42/54321 reply x.example is 1.2.3.4")
    assert.equal(1, c.size())

    clk.advance(61)
    c.tick()
    assert.equal(0, c.size())
  end)

  it("evicts oldest entries when max_entries is reached", function()
    local clk = fake_clock()
    local c = dns_log.new({ ttl_seconds = 3600, max_entries = 2, now_fn = clk.now })

    c.ingest_line("1 1.1.1.1/1 query[A] a.example from 1.1.1.1")
    c.ingest_line("1 1.1.1.1/1 reply a.example is 10.0.0.1")
    clk.advance(1)
    c.ingest_line("2 1.1.1.1/2 query[A] b.example from 1.1.1.1")
    c.ingest_line("2 1.1.1.1/2 reply b.example is 10.0.0.2")
    clk.advance(1)
    c.ingest_line("3 1.1.1.1/3 query[A] c.example from 1.1.1.1")
    c.ingest_line("3 1.1.1.1/3 reply c.example is 10.0.0.3")

    -- a.example should have been evicted (oldest).
    assert.is_nil(c.lookup("10.0.0.1"))
    assert.equal("b.example", c.lookup("10.0.0.2"))
    assert.equal("c.example", c.lookup("10.0.0.3"))
    assert.equal(2, c.size())
  end)

  it("evicts old pending-query state too (memory leak prevention)", function()
    local clk = fake_clock()
    local c = dns_log.new({ ttl_seconds = 60, max_pending = 2, now_fn = clk.now })

    -- Three queries that never get replies — without bound, qid map grows
    -- forever. With max_pending=2, the oldest should be dropped.
    c.ingest_line("1 1.1.1.1/1 query[A] a.example from 1.1.1.1")
    c.ingest_line("2 1.1.1.1/2 query[A] b.example from 1.1.1.1")
    c.ingest_line("3 1.1.1.1/3 query[A] c.example from 1.1.1.1")

    -- A late reply for qid=1 must not produce an entry (we forgot the qname).
    c.ingest_line("1 1.1.1.1/1 reply somecname.example is 10.0.0.1")
    -- We still store it under the reply name (fallback path).
    assert.equal("somecname.example", c.lookup("10.0.0.1"))

    -- qid=3 still pending → reply correctly resolves to original qname.
    c.ingest_line("3 1.1.1.1/3 reply c.example is 10.0.0.3")
    assert.equal("c.example", c.lookup("10.0.0.3"))
  end)
end)

-- ---------------------------------------------------------------------------
-- 4. Persistence (tailer-process → agent-process handoff via /tmp file)
-- ---------------------------------------------------------------------------

describe("dump_text + load_table", function()
  local function fake_clock()
    local t = 1000000
    return {
      now  = function() return t end,
      advance = function(secs) t = t + secs end,
    }
  end

  it("dumps the cache to a single string with one entry per line", function()
    local clk = fake_clock()
    local c = dns_log.new({ ttl_seconds = 3600, now_fn = clk.now })
    c.ingest_line("1 1.1.1.1/1 query[A] a.example from 1.1.1.1")
    c.ingest_line("1 1.1.1.1/1 reply a.example is 10.0.0.1")
    c.ingest_line("2 1.1.1.1/2 query[A] b.example from 1.1.1.1")
    c.ingest_line("2 1.1.1.1/2 reply b.example is 10.0.0.2")

    local text = c.dump_text()
    -- Each line is "<ip>\t<hostname>\t<ts>"
    assert.truthy(text:find("10.0.0.1\ta.example\t1000000", 1, true))
    assert.truthy(text:find("10.0.0.2\tb.example\t1000000", 1, true))
  end)

  it("load_table parses the dump and skips expired entries", function()
    local clk = fake_clock()
    local c = dns_log.new({ ttl_seconds = 3600, now_fn = clk.now })
    c.ingest_line("1 1.1.1.1/1 query[A] fresh.example from 1.1.1.1")
    c.ingest_line("1 1.1.1.1/1 reply fresh.example is 10.0.0.1")
    clk.advance(7200)  -- past ttl
    c.ingest_line("2 1.1.1.1/2 query[A] new.example from 1.1.1.1")
    c.ingest_line("2 1.1.1.1/2 reply new.example is 10.0.0.2")

    local text = c.dump_text()
    -- Load with ttl=3600 and "now" = clk.now (so the first entry is expired).
    local lookup = dns_log.load_table(text, 3600, clk.now())
    assert.is_nil(lookup["10.0.0.1"])
    assert.equal("new.example", lookup["10.0.0.2"])
  end)

  it("load_table returns an empty table for nil / empty / malformed input", function()
    assert.same({}, dns_log.load_table(nil, 3600, 0))
    assert.same({}, dns_log.load_table("", 3600, 0))
    assert.same({}, dns_log.load_table("garbage\nnot a real entry\n", 3600, 0))
  end)
end)

-- ---------------------------------------------------------------------------
-- 4b. CNAME-alias persistence across restarts (#1572)
--
-- #1344's CNAME→brand alias map is in-memory only, so any restart of
-- wifihaven-dns-tail loses every learned edge. If a client then queries the
-- CDN target DIRECTLY before the branded chain is re-observed (the common
-- Apple-device pattern), resolve_head returns the opaque CDN name verbatim
-- and traffic/usage records are attributed to the CDN target instead of the
-- queried brand — the very pollution #1344 set out to fix.
--
-- The "best-effort alias recovery" is to dump the alias map to /tmp on each
-- cache flush and reload it on startup, mirroring the existing
-- dump_text/load_table pattern for entries.
-- ---------------------------------------------------------------------------

describe("alias-map persistence (#1572)", function()
  local function fake_clock()
    local t = 1000000
    return { now = function() return t end, advance = function(s) t = t + s end }
  end

  it("dumps learned aliases as <target>\\t<head>\\t<ts> lines", function()
    local clk = fake_clock()
    local c = dns_log.new({ ttl_seconds = 3600, now_fn = clk.now })
    c.ingest_line("1 192.168.1.10/100 query[A] brand.com from 192.168.1.10")
    c.ingest_line("1 192.168.1.10/100 reply brand.com is <CNAME>")
    c.ingest_line("1 192.168.1.10/100 reply edge.akamai.net is 1.2.3.4")

    local text = c.dump_aliases_text()
    assert.truthy(text:find("edge.akamai.net\tbrand.com\t1000000", 1, true))
  end)

  it("seed_aliases restores the alias map so a later direct query attributes to the brand", function()
    local clk = fake_clock()

    -- Process A: learns brand.com -> CNAME edge.akamai.net -> 1.2.3.4
    local a = dns_log.new({ ttl_seconds = 3600, now_fn = clk.now })
    a.ingest_line("1 192.168.1.10/100 query[A] brand.com from 192.168.1.10")
    a.ingest_line("1 192.168.1.10/100 reply brand.com is <CNAME>")
    a.ingest_line("1 192.168.1.10/100 reply edge.akamai.net is 1.2.3.4")
    local alias_dump = a.dump_aliases_text()

    -- Process B: a fresh sidecar instance (post-restart). Without seeding,
    -- a DIRECT query for the CDN target attributes to the CDN target itself.
    clk.advance(60)
    local b = dns_log.new({ ttl_seconds = 3600, now_fn = clk.now })
    b.seed_aliases(dns_log.load_aliases_table(alias_dump, 3600, clk.now()))
    b.ingest_line("2 192.168.1.10/200 query[A] edge.akamai.net from 192.168.1.10")
    b.ingest_line("2 192.168.1.10/200 reply edge.akamai.net is 5.6.7.8")

    -- With seeded aliases, the direct-query IP attributes back to brand.com.
    assert.equal("brand.com", b.lookup("5.6.7.8"))
    -- resolve_head() also recovers the brand directly.
    assert.equal("brand.com", b.resolve_head("edge.akamai.net"))
  end)

  it("load_aliases_table skips expired alias edges", function()
    local clk = fake_clock()
    local c = dns_log.new({ ttl_seconds = 3600, now_fn = clk.now })
    c.ingest_line("1 1.1.1.1/1 query[A] brand.com from 1.1.1.1")
    c.ingest_line("1 1.1.1.1/1 reply brand.com is <CNAME>")
    c.ingest_line("1 1.1.1.1/1 reply edge.akamai.net is 1.2.3.4")
    clk.advance(7200)  -- past ttl
    c.ingest_line("2 1.1.1.1/2 query[A] fresh.example from 1.1.1.1")
    c.ingest_line("2 1.1.1.1/2 reply fresh.example is <CNAME>")
    c.ingest_line("2 1.1.1.1/2 reply other.cdn.example is 2.2.2.2")

    local table_out = dns_log.load_aliases_table(c.dump_aliases_text(), 3600, clk.now())
    assert.is_nil(table_out["edge.akamai.net"])
    assert.equal("fresh.example", table_out["other.cdn.example"])
  end)

  it("load_aliases_table tolerates nil / empty / malformed input", function()
    assert.same({}, dns_log.load_aliases_table(nil, 3600, 0))
    assert.same({}, dns_log.load_aliases_table("", 3600, 0))
    assert.same({}, dns_log.load_aliases_table("garbage\nnot a real edge\n", 3600, 0))
  end)
end)

-- ---------------------------------------------------------------------------
-- 2c. resolve_head public accessor (#1346/#1348)
--
-- The CNAME-alias resolution (#1344) was previously an internal closure used
-- only by ingest_line. The dns-tail eb_/bl_ populators need to attribute a
-- directly-queried CDN target back to the branded chain head BEFORE matching
-- against set membership, so the live cache exposes it as a method (sharing the
-- learned alias edges — no second map).
-- ---------------------------------------------------------------------------

describe("resolve_head accessor (#1346/#1348)", function()
  local function fake_clock()
    local t = 1000000
    return { now = function() return t end, advance = function(s) t = t + s end }
  end

  it("returns the branded chain head for a learned CDN alias", function()
    local clk = fake_clock()
    local c = dns_log.new({ ttl_seconds = 3600, now_fn = clk.now })
    c.ingest_line("1 192.168.1.10/100 query[A] cdn.kastatic.org from 192.168.1.10")
    c.ingest_line("1 192.168.1.10/100 reply cdn.kastatic.org is <CNAME>")
    c.ingest_line("1 192.168.1.10/100 reply prod.khan.map.fastly.net is 199.232.65.42")

    assert.equal("cdn.kastatic.org", c.resolve_head("prod.khan.map.fastly.net"))
  end)

  it("returns the name unchanged when it is not a known alias", function()
    local clk = fake_clock()
    local c = dns_log.new({ ttl_seconds = 3600, now_fn = clk.now })
    assert.equal("example.com", c.resolve_head("example.com"))
  end)
end)

-- ---------------------------------------------------------------------------
-- 2d. Category-blocklist member index + bl_ populator (#1348)
--
-- bl_<id> / bl6_<id> category drop-sets are declared per blocklist id and
-- populated at DNS resolve time by dnsmasq's `nftset=/<member>/...#bl_<id>`
-- directive — which fires only for queries whose name suffix-matches a member
-- host. When a client re-queries a member's CNAME target DIRECTLY it lands on a
-- CDN-anycast IP dnsmasq never added to bl_<id>, so the category drop misses
-- and blocked content becomes reachable (a silent filter bypass, #1348).
--
-- dns-tail closes that gap the same way it does for eb_ (#515/#1346): on each
-- `reply <name> is <ip>` it resolves <name> through the #1344 CNAME-alias map
-- and, if the recovered brand is a blocklist member, adds the IP to that
-- member's bl_ set. dns-tail knows which bl_ sets EXIST but not their
-- MEMBERSHIP — `parse_blocklist_member_index` supplies host → bl_set so the
-- populator can match, and `populate_bl` performs the alias-resolve + walk.
-- ---------------------------------------------------------------------------

describe("parse_blocklist_member_index (#1348)", function()
  it("parses host → {v4,v6} set rows, grouping multiple ids per host", function()
    local text = table.concat({
      "tiktok.com\tbl_social\tbl6_social",
      "tiktok.com\tbl_china\tbl6_china",
      "youtube.com\tbl_video\tbl6_video",
    }, "\n") .. "\n"
    local idx = dns_log.parse_blocklist_member_index(text)

    assert.equal(2, #idx["tiktok.com"])
    assert.equal("bl_social", idx["tiktok.com"][1].v4)
    assert.equal("bl6_social", idx["tiktok.com"][1].v6)
    assert.equal("bl_china",  idx["tiktok.com"][2].v4)
    assert.equal("bl_video",  idx["youtube.com"][1].v4)
  end)

  it("returns an empty table for nil / empty / malformed input", function()
    assert.same({}, dns_log.parse_blocklist_member_index(nil))
    assert.same({}, dns_log.parse_blocklist_member_index(""))
    assert.same({}, dns_log.parse_blocklist_member_index("garbage no tabs here\n"))
  end)
end)

describe("populate_bl (#1348)", function()
  local function fake_clock()
    local t = 1000000
    return { now = function() return t end, advance = function(s) t = t + s end }
  end

  -- Collector standing in for dns-tail's injected nft_add_element.
  local function collector()
    local adds = {}
    return adds, function(set, ip) adds[#adds + 1] = { set = set, ip = ip } end
  end

  it("adds a directly-queried CNAME target's IP to the member's bl_ set", function()
    local clk = fake_clock()
    local c = dns_log.new({ ttl_seconds = 3600, now_fn = clk.now })

    -- 1) The branded member tiktok.com was resolved earlier; the agent-side
    --    population added IP A (1.2.3.4) to bl_social via dnsmasq. The chain
    --    head → CDN target alias edge is learned here.
    c.ingest_line("10 192.168.1.50/100 query[A] tiktok.com from 192.168.1.50")
    c.ingest_line("10 192.168.1.50/100 reply tiktok.com is <CNAME>")
    c.ingest_line("10 192.168.1.50/100 reply e35058.api12.akamaiedge.net is 1.2.3.4")

    local idx = dns_log.parse_blocklist_member_index(
      "tiktok.com\tbl_social\tbl6_social\n")

    -- 2) The device re-queries the CDN target DIRECTLY and lands on a DIFFERENT
    --    Akamai anycast IP B (5.6.7.8) that bl_social never got — the bypass.
    clk.advance(5)
    local reply = { name = "e35058.api12.akamaiedge.net", ip = "5.6.7.8", family = "v4" }

    local adds, add_fn = collector()
    dns_log.populate_bl(reply, idx, c.resolve_head, add_fn)

    -- B must be added to the correct bl_<id> via the alias map + member index.
    assert.equal(1, #adds)
    assert.equal("bl_social", adds[1].set)
    assert.equal("5.6.7.8",   adds[1].ip)
  end)

  it("uses the v6 set name for an AAAA answer", function()
    local clk = fake_clock()
    local c = dns_log.new({ ttl_seconds = 3600, now_fn = clk.now })
    -- The alias edge cdn.akamai.net → tiktok.com is learned from the branded
    -- A-record chain (the cache's alias map is fed by v4 reply lines, since
    -- conntrack flows are v4); it's then reused for the AAAA direct re-query.
    c.ingest_line("11 192.168.1.50/101 query[A] tiktok.com from 192.168.1.50")
    c.ingest_line("11 192.168.1.50/101 reply tiktok.com is <CNAME>")
    c.ingest_line("11 192.168.1.50/101 reply cdn.akamai.net is 1.2.3.4")

    local idx = dns_log.parse_blocklist_member_index(
      "tiktok.com\tbl_social\tbl6_social\n")
    local reply = { name = "cdn.akamai.net", ip = "2606:2800::2", family = "v6" }

    local adds, add_fn = collector()
    dns_log.populate_bl(reply, idx, c.resolve_head, add_fn)

    assert.equal(1, #adds)
    assert.equal("bl6_social", adds[1].set)
  end)

  it("matches a member via dnsmasq subdomain semantics (suffix walk)", function()
    local c = dns_log.new({ ttl_seconds = 3600 })
    local idx = dns_log.parse_blocklist_member_index("tiktok.com\tbl_social\tbl6_social\n")
    -- A subdomain the alias map never saw; resolve_head returns it unchanged,
    -- but it still suffix-matches the member tiktok.com.
    local reply = { name = "www.tiktok.com", ip = "9.9.9.9", family = "v4" }

    local adds, add_fn = collector()
    dns_log.populate_bl(reply, idx, c.resolve_head, add_fn)

    assert.equal(1, #adds)
    assert.equal("bl_social", adds[1].set)
  end)

  it("adds to every blocklist a member belongs to", function()
    local c = dns_log.new({ ttl_seconds = 3600 })
    local idx = dns_log.parse_blocklist_member_index(table.concat({
      "tiktok.com\tbl_social\tbl6_social",
      "tiktok.com\tbl_china\tbl6_china",
    }, "\n") .. "\n")
    local reply = { name = "tiktok.com", ip = "1.1.1.1", family = "v4" }

    local adds, add_fn = collector()
    dns_log.populate_bl(reply, idx, c.resolve_head, add_fn)

    assert.equal(2, #adds)
    local sets = { [adds[1].set] = true, [adds[2].set] = true }
    assert.is_true(sets["bl_social"])
    assert.is_true(sets["bl_china"])
  end)

  it("does nothing when the resolved head is not a blocklist member (safe)", function()
    local c = dns_log.new({ ttl_seconds = 3600 })
    local idx = dns_log.parse_blocklist_member_index("tiktok.com\tbl_social\tbl6_social\n")
    -- No prior brand chain observed for this target → resolve_head returns it
    -- unchanged, and it matches no member. Never adds spuriously.
    local reply = { name = "unrelated.example.net", ip = "8.8.8.8", family = "v4" }

    local adds, add_fn = collector()
    dns_log.populate_bl(reply, idx, c.resolve_head, add_fn)

    assert.equal(0, #adds)
  end)
end)

-- ---------------------------------------------------------------------------
-- classify_query_result + query-result tally serialization (#1302)
-- ---------------------------------------------------------------------------

describe("classify_query_result (#1302)", function()
  local PFX = "Nov 12 10:00:01 dnsmasq[1234]: "

  it("classifies an A-record reply as resolved", function()
    assert.equal("resolved",
      dns_log.classify_query_result(PFX .. "7 192.168.1.42/54321 reply youtube.com is 142.250.72.206"))
  end)

  it("classifies a cached answer as resolved (terminal outcome)", function()
    assert.equal("resolved",
      dns_log.classify_query_result(PFX .. "7 192.168.1.42/54321 cached youtube.com is 142.250.72.206"))
  end)

  it("classifies an AAAA answer as resolved", function()
    assert.equal("resolved",
      dns_log.classify_query_result(PFX .. "7 192.168.1.42/53 reply example.com is 2606:2800:220:1:248:1893:25c8:1946"))
  end)

  it("classifies NXDOMAIN / SERVFAIL / REFUSED / NODATA", function()
    assert.equal("nxdomain",
      dns_log.classify_query_result(PFX .. "8 192.168.1.42/53 reply nope.invalid is NXDOMAIN"))
    assert.equal("servfail",
      dns_log.classify_query_result(PFX .. "9 192.168.1.42/53 reply broken.test is SERVFAIL"))
    assert.equal("refused",
      dns_log.classify_query_result(PFX .. "10 192.168.1.42/53 reply blocked.test is REFUSED"))
    assert.equal("nodata",
      dns_log.classify_query_result(PFX .. "11 192.168.1.42/53 reply x.test is NODATA-IPv4"))
  end)

  it("does NOT count a non-terminal CNAME hop", function()
    assert.is_nil(
      dns_log.classify_query_result(PFX .. "7 192.168.1.42/53 reply www.example.com is <CNAME>"))
  end)

  it("returns nil for a query line and unrecognised lines", function()
    assert.is_nil(
      dns_log.classify_query_result(PFX .. "7 192.168.1.42/54321 query[A] youtube.com from 192.168.1.42"))
    assert.is_nil(dns_log.classify_query_result("garbage"))
    assert.is_nil(dns_log.classify_query_result(nil))
  end)
end)

describe("format_query_results / parse_query_results (#1302)", function()
  it("round-trips a tally through the IPC file format", function()
    local tally = { resolved = 41200, nxdomain = 318, servfail = 7 }
    local text  = dns_log.format_query_results(tally)
    assert.same(tally, dns_log.parse_query_results(text))
  end)

  it("emits a stable (sorted) order so the file is byte-stable across flushes", function()
    local a = dns_log.format_query_results({ resolved = 1, nxdomain = 2 })
    local b = dns_log.format_query_results({ nxdomain = 2, resolved = 1 })
    assert.equal(a, b)
  end)

  it("parses an empty / absent file as an empty tally", function()
    assert.same({}, dns_log.parse_query_results(""))
    assert.same({}, dns_log.parse_query_results(nil))
  end)
end)

-- ---------------------------------------------------------------------------
-- 5. cache:insert_sni (#573) — SNI-derived hostname injection.
--
-- The wifihaven-sni-tail sidecar parses TLS ClientHellos off the LAN bridge
-- and the dns-tail loop calls cache.insert_sni(dst_ip, sni, now). insert_sni
-- runs the SNI through the same resolve_head alias map dns ingest uses, then
-- stores (ip → head) so cache.lookup attributes the flow back to the branded
-- host. This closes the DoH/hard-coded-IP attribution gap (#573).
-- ---------------------------------------------------------------------------

describe("cache:insert_sni (#573)", function()
  it("stores (ip → sni) so lookup returns the SNI hostname", function()
    local t = 1000
    local c = dns_log.new({ now_fn = function() return t end })
    c.insert_sni("1.2.3.4", "example.com", t)
    assert.equal("example.com", c.lookup("1.2.3.4"))
  end)

  it("runs the SNI through the CNAME-alias map (resolve_head)", function()
    local t = 1000
    local c = dns_log.new({ now_fn = function() return t end })
    -- Seed alias edge via a normal DNS reply chain:
    --   query[A] cdn.kastatic.org
    --   reply    cdn.kastatic.org is <CNAME>
    --   reply    prod.khan.map.fastly.net is 1.2.3.4
    -- (#1344) — after which prod.khan.map.fastly.net is known to alias back to
    -- cdn.kastatic.org.
    c.ingest_line(
      "1 192.168.1.42/54321 query[A] cdn.kastatic.org from 192.168.1.42")
    c.ingest_line(
      "1 192.168.1.42/54321 reply cdn.kastatic.org is <CNAME>")
    c.ingest_line(
      "1 192.168.1.42/54321 reply prod.khan.map.fastly.net is 1.2.3.4")
    -- An SNI to the leaf CDN target attributes back to the branded head.
    c.insert_sni("5.6.7.8", "prod.khan.map.fastly.net", t)
    assert.equal("cdn.kastatic.org", c.lookup("5.6.7.8"))
  end)

  it("is a no-op on nil/empty input", function()
    local c = dns_log.new()
    c.insert_sni(nil, "example.com", 1000)
    c.insert_sni("1.2.3.4", nil, 1000)
    c.insert_sni("", "", 1000)
    assert.equal(0, c.size())
  end)
end)

-- v6 cache-key canonicalization (#1793).
--
-- dnsmasq logs AAAA answers in COMPRESSED form (`2607:f8b0:400f:801::2002`), so
-- that is what the cache key is stored as. But the kernel netfilter LOG action
-- behind the nflog drop-visibility path (#1122) hands the agent the
-- FULLY-EXPANDED form (`2607:f8b0:400f:0801:0000:0000:0000:2002`). Before #1793
-- the exact-string lookup missed and every blocked v6 flow recorded a bare
-- literal as its host. The cache now canonicalizes the key at every store/lookup
-- site (via host_norm.canon_ip) so an expanded query hits a compressed insert.
describe("v6 cache-key canonicalization (#1793)", function()
  local COMPRESSED = "2607:f8b0:400f:801::2002"               -- dnsmasq / conntrack
  local EXPANDED   = "2607:f8b0:400f:0801:0000:0000:0000:2002" -- kernel netfilter LOG

  it("ingest (compressed AAAA) → lookup by expanded form hits", function()
    local c = dns_log.new({ now_fn = function() return 1000 end })
    c.ingest_line(
      "7 192.168.1.42/54321 query[AAAA] pagead2.googlesyndication.com from 192.168.1.42")
    c.ingest_line(
      "7 192.168.1.42/54321 reply pagead2.googlesyndication.com is " .. COMPRESSED)
    -- Sanity: the compressed key the writer saw still resolves.
    assert.equal("pagead2.googlesyndication.com", c.lookup(COMPRESSED))
    -- The bug: the nflog path queries with the EXPANDED form. It must hit.
    assert.equal("pagead2.googlesyndication.com", c.lookup(EXPANDED))
  end)

  it("load_table snapshot (compressed key) is queryable by expanded form", function()
    -- The production read path: dns-tail dump_text writes the cache file, the
    -- agent loads it via load_table and indexes tbl[canon_ip(dst_ip)].
    local host_norm = require("host_norm")
    local text = COMPRESSED .. "\tpagead2.googlesyndication.com\t1000\n"
    local tbl  = dns_log.load_table(text, 3600, 1000)
    assert.equal("pagead2.googlesyndication.com", tbl[host_norm.canon_ip(EXPANDED)])
    assert.equal("pagead2.googlesyndication.com", tbl[host_norm.canon_ip(COMPRESSED)])
  end)

  it("expired-entry eviction via lookup keeps entry_count consistent across v6 spellings", function()
    -- The expiry path inside lookup must delete the entry under the SAME
    -- canonical key it read, even when queried with a different (compressed)
    -- v6 spelling than the one that was stored. Deleting under the raw query
    -- key would leave the entry behind while still decrementing entry_count,
    -- corrupting the max_entries bound.
    local t = 1000
    local c = dns_log.new({ ttl_seconds = 60, now_fn = function() return t end })
    c.ingest_line("9 192.168.1.42/54321 query[AAAA] ads.example from 192.168.1.42")
    c.ingest_line("9 192.168.1.42/54321 reply ads.example is " .. EXPANDED)
    assert.equal(1, c.size())

    t = t + 61  -- past ttl
    -- Look up by the COMPRESSED spelling (canonicalizes to the stored key).
    assert.is_nil(c.lookup(COMPRESSED))
    assert.equal(0, c.size())
    -- A second expired lookup must not double-decrement (would go negative if
    -- the entry were never actually removed).
    assert.is_nil(c.lookup(COMPRESSED))
    assert.equal(0, c.size())
  end)
end)
