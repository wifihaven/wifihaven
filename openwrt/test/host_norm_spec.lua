-- host_norm_spec.lua — pin the wire-host normalization helper (#1761).
--
-- A handful of attribution paths (notably SNI / Host-header capture) feed the
-- ip→hostname cache with values that include a `:port` suffix. The API's
-- Hostname validation rejects those as "invalid hostname label 'com:443'", so
-- usage records and connection events that carry them 4xx at ingest. The
-- single-source fix is to strip a trailing `:<digits>` BEFORE the value
-- reaches the wire — every FQDN emit site routes its `value` through
-- host_norm.strip_port_suffix.

local host_norm = require("wifihaven.host_norm")

describe("host_norm.strip_port_suffix", function()
  it("strips a trailing :<port>", function()
    assert.equals("foo.example.com",            host_norm.strip_port_suffix("foo.example.com:443"))
    assert.equals("ws.nas.native-cloud.com",    host_norm.strip_port_suffix("ws.nas.native-cloud.com:443"))
    assert.equals("foo.example.com",            host_norm.strip_port_suffix("foo.example.com:8080"))
  end)

  it("is idempotent on a clean hostname", function()
    assert.equals("foo.example.com",            host_norm.strip_port_suffix("foo.example.com"))
    assert.equals("a",                          host_norm.strip_port_suffix("a"))
  end)

  it("leaves nil / empty alone", function()
    assert.is_nil(host_norm.strip_port_suffix(nil))
    assert.equals("",                           host_norm.strip_port_suffix(""))
  end)

  it("does NOT touch a bare IPv6 literal (multiple colons, no bracket form)", function()
    -- Bare IPv6 hostnames shouldn't reach this helper (they're emitted as
    -- type="ipv6"), but if one does, the heuristic must not strip the last
    -- group as if it were a port: "::1" -> "::1", not ":".
    assert.equals("::1",                        host_norm.strip_port_suffix("::1"))
    assert.equals("fe80::1234",                 host_norm.strip_port_suffix("fe80::1234"))
    assert.equals("2001:db8::beef",             host_norm.strip_port_suffix("2001:db8::beef"))
  end)

  it("strips port from a bracketed IPv6 host", function()
    -- "[::1]:443" -> "[::1]" (defensive — bracketed form is unambiguous).
    assert.equals("[::1]",                      host_norm.strip_port_suffix("[::1]:443"))
  end)
end)

-- canon_ip(ip) — single-source IPv6 cache-key canonicalization (#1793).
--
-- The dns→host attribution cache is keyed by the destination IP string. The two
-- code paths that produce that key disagree on v6 spelling: dnsmasq's query log
-- (and `conntrack -E/-L`) emit the COMPRESSED RFC 5952 form
-- (`2607:f8b0:400f:801::2002`), but the kernel netfilter LOG action that drives
-- the nflog drop-visibility path (#1122) emits the FULLY-EXPANDED zero-padded
-- form (`2607:f8b0:400f:0801:0000:0000:0000:2002`). An exact-string lookup
-- therefore misses for every *blocked* v6 flow and the connection_event records
-- a bare literal instead of the resolved FQDN (#1793). canon_ip collapses both
-- spellings to one key (the expanded form) so the lookup hits regardless of
-- which path produced the IP.
describe("host_norm.canon_ip (#1793)", function()
  it("expands compressed and expanded v6 to the SAME key", function()
    local compressed = "2607:f8b0:400f:801::2002"
    local expanded   = "2607:f8b0:400f:0801:0000:0000:0000:2002"
    assert.equals(expanded, host_norm.canon_ip(compressed))
    assert.equals(expanded, host_norm.canon_ip(expanded))
    -- The load-bearing property: the two spellings of one address are equal
    -- after canonicalization.
    assert.equals(host_norm.canon_ip(compressed), host_norm.canon_ip(expanded))
  end)

  it("expands :: in any position and lowercases hex", function()
    assert.equals("0000:0000:0000:0000:0000:0000:0000:0001", host_norm.canon_ip("::1"))
    assert.equals("0000:0000:0000:0000:0000:0000:0000:0000", host_norm.canon_ip("::"))
    assert.equals("fe80:0000:0000:0000:0000:0000:0000:1234", host_norm.canon_ip("fe80::1234"))
    assert.equals("2001:0db8:0000:0000:0000:0000:0000:beef", host_norm.canon_ip("2001:DB8::BEEF"))
    assert.equals("2606:4700:0020:0000:0000:0000:681a:0d92", host_norm.canon_ip("2606:4700:20::681a:d92"))
  end)

  it("is idempotent on an already-expanded address", function()
    local e = "2607:f8b0:400f:0801:0000:0000:0000:2002"
    assert.equals(e, host_norm.canon_ip(host_norm.canon_ip(e)))
  end)

  it("passes IPv4 and non-v6 input through unchanged", function()
    assert.equals("1.2.3.4",       host_norm.canon_ip("1.2.3.4"))
    assert.equals("142.250.80.46", host_norm.canon_ip("142.250.80.46"))
  end)

  it("leaves nil / empty alone", function()
    assert.is_nil(host_norm.canon_ip(nil))
    assert.equals("", host_norm.canon_ip(""))
  end)
end)
