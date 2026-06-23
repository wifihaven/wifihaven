-- encrypted_dns_spec.lua — curated relay/DoH/DoT lists baked into the agent for
-- the network-wide blockEncryptedDns toggle (#1909 / #1911).
--
-- These lists are intentionally NOT shipped on the wire (the snapshot carries a
-- single `blockEncryptedDns` bool); they live in-agent so the wire stays a
-- single flag. This spec pins the curated contents the issue enumerates so a
-- careless edit can't silently drop a bypass channel.

local edns = require("encrypted_dns")

describe("encrypted_dns curated lists", function()
  it("includes the Apple iCloud Private Relay ingress hostnames", function()
    local set = {}
    for _, h in ipairs(edns.HOSTS) do set[h] = true end
    assert.is_true(set["mask.icloud.com"])
    assert.is_true(set["mask-h2.icloud.com"])
  end)

  it("includes the public DoH-by-name hostnames the issue enumerates", function()
    local set = {}
    for _, h in ipairs(edns.HOSTS) do set[h] = true end
    for _, h in ipairs({
      "cloudflare-dns.com", "dns.google", "dns.quad9.net",
      "dns.nextdns.io", "dns.adguard-dns.com", "doh.opendns.com",
    }) do
      assert.is_true(set[h], "expected curated DoH host " .. h)
    end
  end)

  it("includes the curated public-resolver v4 IPs", function()
    local set = {}
    for _, ip in ipairs(edns.RESOLVER_IPS_V4) do set[ip] = true end
    for _, ip in ipairs({
      "1.1.1.1", "1.0.0.1", "8.8.8.8", "8.8.4.4", "9.9.9.9", "149.112.112.112",
    }) do
      assert.is_true(set[ip], "expected curated v4 resolver IP " .. ip)
    end
  end)

  it("includes the curated public-resolver v6 IPs", function()
    local set = {}
    for _, ip in ipairs(edns.RESOLVER_IPS_V6) do set[ip] = true end
    for _, ip in ipairs({
      "2606:4700:4700::1111", "2606:4700:4700::1001",
      "2001:4860:4860::8888", "2001:4860:4860::8844", "2620:fe::fe",
    }) do
      assert.is_true(set[ip], "expected curated v6 resolver IP " .. ip)
    end
  end)

  it("exposes the DoT port (853)", function()
    assert.equal(853, edns.DOT_PORT)
  end)

  it("hostnames are bare apex/host names — never an address=/host/# sinkhole token", function()
    -- Truth-1 guard: the curated entries are HOSTNAMES the dnsmasq layer answers
    -- with a NEGATIVE record (local=/host/), never a 0.0.0.0 sinkhole. A list
    -- entry that accidentally carried a `#`/`/` would mean someone reached for a
    -- sinkhole; keep them clean DNS names.
    for _, h in ipairs(edns.HOSTS) do
      assert.is_nil(h:find("#", 1, true))
      assert.is_nil(h:find("/", 1, true))
    end
  end)
end)
