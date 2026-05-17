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

  it("skips IPv6 replies (we only track v4 conntrack flows)", function()
    local line =
      "Nov 12 10:00:01 dnsmasq[1234]: 7 192.168.1.42/54321 reply youtube.com is 2607:f8b0::1"
    assert.is_nil(dns_log.parse_reply_line(line))
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
