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
