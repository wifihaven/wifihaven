-- Tests for openwrt/files/usr/lib/lua/wifihaven/resolver.lua
--
-- #2782 — the agent's two DNS shell-outs (eb_refresh's periodic re-resolve and
-- policy.apply's post-apply smoke probe) both ran `dig`, which OpenWRT does not
-- ship and `openwrt/Makefile` never depended on. Every resolve produced an
-- empty string, `default_resolver`'s `if not v4_out and not v6_out` guard let it
-- through (an empty Lua string is truthy), and the eb_/bl_ ipsets that keep a
-- blocked host blocked past its 1h kernel timeout were never topped up. Prod
-- had counted 179,349,241 "successful" resolves and zero failures while adding
-- nothing at all.
--
-- This module is the single resolver both callers now use. Two properties are
-- load-bearing and pinned below:
--   1. It parses BusyBox `nslookup`, which every OpenWRT image ships.
--   2. "the resolver could not run" is distinguishable from "the resolver
--      answered, there are no records". BusyBox nslookup always prints its
--      `Server:` header, even on NXDOMAIN; a missing binary prints nothing.
--
-- Run with: busted openwrt/test/resolver_spec.lua

local resolver = require("resolver")

-- Real BusyBox nslookup output, captured from the prod router 2026-09-13.
local A_WWW_AMAZON = table.concat({
  "Server:\t\t127.0.0.1",
  "Address:\t127.0.0.1:53",
  "",
  "Non-authoritative answer:",
  "www.amazon.com\tcanonical name = tp.47cf2c8c9-frontier.amazon.com",
  "tp.47cf2c8c9-frontier.amazon.com\tcanonical name = www.amazon.com.edgekey.net",
  "www.amazon.com.edgekey.net\tcanonical name = e15316.dsca.akamaiedge.net",
  "Name:\te15316.dsca.akamaiedge.net",
  "Address: 23.47.202.67",
  "",
}, "\n")

local AAAA_WWW_AMAZON = table.concat({
  "Server:\t\t127.0.0.1",
  "Address:\t127.0.0.1:53",
  "",
  "Non-authoritative answer:",
  "www.amazon.com\tcanonical name = tp.47cf2c8c9-frontier.amazon.com",
  "Name:\tcf.47cf2c8c9-frontier.amazon.com",
  "Address: 2600:9000:2162:1c00:7:49a5:5fd6:da1",
  "Name:\tcf.47cf2c8c9-frontier.amazon.com",
  "Address: 2600:9000:2162:8800:7:49a5:5fd6:da1",
  "",
}, "\n")

local NXDOMAIN = table.concat({
  "Server:\t\t127.0.0.1",
  "Address:\t127.0.0.1:53",
  "",
  "** server can't find nonexistent-wh-test-2782.invalid: NXDOMAIN",
  "",
}, "\n")

local NO_AAAA_RECORD = table.concat({
  "Server:\t\t127.0.0.1",
  "Address:\t127.0.0.1:53",
  "",
  "Non-authoritative answer:",
  "",
}, "\n")

-- What `io.popen("nslookup … 2>/dev/null")` yields when the binary is absent:
-- the shell's "not found" goes to stderr, stdout is empty.
local BINARY_MISSING = ""

-- A minimal `io.popen` handle over a captured stdout string. The module reads
-- `*a` then closes, so that is the whole surface; keeping the stub shaped like
-- a real handle means the shipped code needs no test-only branch.
local function handle_for(text)
  if text == nil then return nil end
  return { read = function() return text end, close = function() end }
end

local function fake_popen(by_qtype)
  return function(cmd)
    return handle_for(by_qtype[cmd:match("%-type=(%u+)")])
  end
end

describe("resolver.parse_nslookup", function()
  it("extracts A records from the answer section", function()
    assert.are.same({ "23.47.202.67" }, resolver.parse_nslookup(A_WWW_AMAZON, "v4"))
  end)

  it("extracts every AAAA record", function()
    assert.are.same({
      "2600:9000:2162:1c00:7:49a5:5fd6:da1",
      "2600:9000:2162:8800:7:49a5:5fd6:da1",
    }, resolver.parse_nslookup(AAAA_WWW_AMAZON, "v6"))
  end)

  -- The server block at the top carries `Address:\t127.0.0.1:53`. Taking it for
  -- an answer would put the ROUTER into a blocked host's drop set.
  it("never mistakes the Server: header block for an answer", function()
    assert.are.same({}, resolver.parse_nslookup(NXDOMAIN, "v4"))
    assert.are.same({}, resolver.parse_nslookup(NO_AAAA_RECORD, "v6"))
    for _, ip in ipairs(resolver.parse_nslookup(A_WWW_AMAZON, "v4")) do
      assert.are_not.equal("127.0.0.1", ip)
    end
  end)

  it("keeps the families apart", function()
    assert.are.same({}, resolver.parse_nslookup(A_WWW_AMAZON, "v6"))
    assert.are.same({}, resolver.parse_nslookup(AAAA_WWW_AMAZON, "v4"))
  end)
end)

-- Older BusyBox numbers its answer lines, and may append the resolved name
-- after the address. An image printing either form must not land the whole
-- fleet in `empty_answer` (#2782 review).
local A_OLD_BUSYBOX = table.concat({
  "Server:\t\t127.0.0.1",
  "Address:\t127.0.0.1:53",
  "",
  "Name:      example.test",
  "Address 1: 93.184.216.34",
  "Address 2: 93.184.216.35 alias.example.test",
  "",
}, "\n")

describe("resolver.parse_nslookup — older BusyBox output forms", function()
  it("accepts numbered Address lines, with or without a trailing name", function()
    assert.are.same({ "93.184.216.34", "93.184.216.35" },
      resolver.parse_nslookup(A_OLD_BUSYBOX, "v4"))
  end)

  it("still excludes the server block on the numbered form", function()
    for _, ip in ipairs(resolver.parse_nslookup(A_OLD_BUSYBOX, "v4")) do
      assert.are_not.equal("127.0.0.1", ip)
    end
  end)
end)

describe("resolver.parse_nslookup — ordering", function()
  -- Parity with the `parse_dig_output` this replaces, whose "yields a sorted
  -- list" test went out with it. nft does not care about add order; the specs
  -- and the debug log do.
  it("returns addresses sorted", function()
    local out = table.concat({
      "Server:\t\t127.0.0.1",
      "Address:\t127.0.0.1:53",
      "",
      "Non-authoritative answer:",
      "Name:\tx.example",
      "Address: 10.0.0.9",
      "Name:\tx.example",
      "Address: 10.0.0.10",
      "Name:\tx.example",
      "Address: 10.0.0.1",
      "",
    }, "\n")
    assert.are.same({ "10.0.0.1", "10.0.0.10", "10.0.0.9" },
      resolver.parse_nslookup(out, "v4"))
  end)
end)

describe("resolver.answered", function()
  it("is true whenever the resolver ran, records or not", function()
    assert.is_true(resolver.answered(A_WWW_AMAZON))
    assert.is_true(resolver.answered(NXDOMAIN))
    assert.is_true(resolver.answered(NO_AAAA_RECORD))
  end)

  it("is false when the resolver could not run", function()
    assert.is_false(resolver.answered(BINARY_MISSING))
    assert.is_false(resolver.answered(nil))
  end)
end)

describe("resolver.resolve", function()
  it("returns both families for a host that has both", function()
    local r = resolver.resolve("www.amazon.com", fake_popen({
      A = A_WWW_AMAZON, AAAA = AAAA_WWW_AMAZON,
    }))
    assert.are.same({ "23.47.202.67" }, r.v4)
    assert.are.same(2, #r.v6)
  end)

  it("returns empty lists — not nil — for NXDOMAIN", function()
    local r = resolver.resolve("nonexistent-wh-test-2782.invalid", fake_popen({
      A = NXDOMAIN, AAAA = NXDOMAIN,
    }))
    assert.is_not_nil(r)
    assert.are.same({}, r.v4)
    assert.are.same({}, r.v6)
  end)

  -- The #2782 regression in one assertion. The old `dig` path returned "" here
  -- and `if not v4_out and not v6_out` let it through as a successful resolve.
  it("returns nil when the resolver binary is missing", function()
    local r = resolver.resolve("www.amazon.com", fake_popen({
      A = BINARY_MISSING, AAAA = BINARY_MISSING,
    }))
    assert.is_nil(r)
  end)

  it("rejects a hostname that could escape the shell", function()
    local called = false
    local r = resolver.resolve("evil.com; rm -rf /", function()
      called = true
      return handle_for(A_WWW_AMAZON)
    end)
    assert.is_nil(r)
    assert.is_false(called)
  end)
end)

describe("resolver.first_address", function()
  it("returns the first A record", function()
    assert.are.equal("23.47.202.67",
      resolver.first_address("www.amazon.com", fake_popen({ A = A_WWW_AMAZON })))
  end)

  it("returns nil for NXDOMAIN and for a missing binary alike", function()
    assert.is_nil(resolver.first_address("x.invalid", fake_popen({ A = NXDOMAIN })))
    assert.is_nil(resolver.first_address("x.invalid", fake_popen({ A = BINARY_MISSING })))
  end)
end)

describe("resolver.safe_host", function()
  it("accepts real hostnames, including dnsmasq aliases with underscores", function()
    assert.are.equal("www.amazon.com", resolver.safe_host("www.amazon.com"))
    assert.are.equal("_dmarc.example.com", resolver.safe_host("_dmarc.example.com"))
  end)

  -- This is the single vetting seam for both callers, so it also has to stop a
  -- host that would arrive as an OPTION rather than as an argument.
  it("rejects a leading hyphen, which nslookup would read as a flag", function()
    assert.is_nil(resolver.safe_host("-type=any"))
    assert.is_nil(resolver.safe_host("-foo.example"))
  end)

  it("rejects shell metacharacters, whitespace and the empty string", function()
    for _, bad in ipairs({ "evil.com; id", "a b.com", "x`id`.com", "$(id).com", "" }) do
      assert.is_nil(resolver.safe_host(bad))
    end
    assert.is_nil(resolver.safe_host(nil))
  end)
end)
