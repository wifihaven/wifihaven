-- encrypted_dns_spec.lua — tests for the network-wide "block encrypted DNS &
-- relays" toggle (#1911 / feature #1909, epic #1903).
--
-- Run with: cd openwrt && busted test/encrypted_dns_spec.lua
--
-- Covers two layers:
--   1. The baked-in curated-list module (wifihaven.encrypted_dns): the relay/
--      DoH hostnames, the public-resolver IPs (v4+v6), and the rendered
--      dnsmasq `local=/host/` (NODATA) directives + nftables drop rules.
--   2. render.lua's reading of the additive top-level `snapshot.blockEncryptedDns`
--      flag: when true, render.dnsmasq emits NODATA directives and render.nft
--      emits the wifihaven_encrypted_dns drop chain; when absent/false the
--      output is byte-identical to today (no-op).
--
-- Architectural Truth #1 (DNS-never-enforcement) carve-out: the NODATA answers
-- are a deliberate, narrow exception, scoped to bypass-disable signaling only —
-- Apple's documented clean way to turn iCloud Private Relay off network-wide.
-- See docs/architecture.md.

local render        = require("render")
local encrypted_dns = require("encrypted_dns")

-- A minimal snapshot with one managed device. blockEncryptedDns defaults absent.
local function snap()
  return {
    etag        = "sha256:abc123",
    generatedAt = "2026-06-23T14:00:00Z",
    devices = {
      ["aa:bb:cc:11:22:33"] = { profileId = 3, name = "kid-ipad", rules = nil },
    },
    profiles = {
      ["3"] = {
        name = "kids",
        rules = {
          blocked = false, blockReason = nil,
          extraBlocked = {}, extraAllowed = {}, blocklistIds = {}, blockIpOnly = false,
        },
        failureMode = "last-known-good",
      },
    },
    blocklists = {},
  }
end

-- ── Curated-list module ──────────────────────────────────────────────────────

describe("encrypted_dns module — curated lists", function()
  it("includes the iCloud Private Relay ingress hostnames", function()
    local set = {}
    for _, h in ipairs(encrypted_dns.HOSTS) do set[h] = true end
    assert.is_true(set["mask.icloud.com"])
    assert.is_true(set["mask-h2.icloud.com"])
  end)

  it("includes the major public DoH hostnames", function()
    local set = {}
    for _, h in ipairs(encrypted_dns.HOSTS) do set[h] = true end
    assert.is_true(set["cloudflare-dns.com"])
    assert.is_true(set["dns.google"])
    assert.is_true(set["dns.quad9.net"])
    assert.is_true(set["dns.nextdns.io"])
    assert.is_true(set["dns.adguard-dns.com"])
    assert.is_true(set["doh.opendns.com"])
  end)

  it("includes the curated public-resolver IPv4 addresses", function()
    local set = {}
    for _, ip in ipairs(encrypted_dns.RESOLVER_IPS_V4) do set[ip] = true end
    assert.is_true(set["1.1.1.1"])
    assert.is_true(set["1.0.0.1"])
    assert.is_true(set["8.8.8.8"])
    assert.is_true(set["8.8.4.4"])
    assert.is_true(set["9.9.9.9"])
    assert.is_true(set["149.112.112.112"])
  end)

  it("includes the curated public-resolver IPv6 addresses", function()
    local set = {}
    for _, ip in ipairs(encrypted_dns.RESOLVER_IPS_V6) do set[ip] = true end
    assert.is_true(set["2606:4700:4700::1111"])
    assert.is_true(set["2606:4700:4700::1001"])
    assert.is_true(set["2001:4860:4860::8888"])
    assert.is_true(set["2001:4860:4860::8844"])
    assert.is_true(set["2620:fe::fe"])
  end)
end)

-- ── dnsmasq NODATA directives (module) ───────────────────────────────────────

describe("encrypted_dns.dnsmasq_lines", function()
  it("emits a NODATA local=/host/ directive for every curated hostname", function()
    local joined = table.concat(encrypted_dns.dnsmasq_lines(), "\n")
    for _, h in ipairs(encrypted_dns.HOSTS) do
      assert.truthy(joined:find("local=/" .. h .. "/", 1, true),
        "missing local=/ directive for " .. h)
    end
  end)

  it("never sinkholes to 0.0.0.0 (Apple's disable requires a NEGATIVE answer)", function()
    local joined = table.concat(encrypted_dns.dnsmasq_lines(), "\n")
    assert.is_nil(joined:find("0.0.0.0", 1, true))
    assert.is_nil(joined:find("address=/", 1, true))
  end)
end)

-- ── nft drop rules (module) ──────────────────────────────────────────────────

describe("encrypted_dns.nft_rules", function()
  it("drops DoT (TCP/853) to any IP", function()
    local joined = table.concat(encrypted_dns.nft_rules(), "\n")
    assert.truthy(joined:find("tcp dport 853", 1, true))
  end)

  it("drops DNS port 53 (udp AND tcp) to the curated resolver IPs", function()
    local joined = table.concat(encrypted_dns.nft_rules(), "\n")
    assert.truthy(joined:find("udp dport 53", 1, true))
    assert.truthy(joined:find("tcp dport 53", 1, true))
    assert.truthy(joined:find("8.8.8.8", 1, true))
    assert.truthy(joined:find("1.1.1.1", 1, true))
    -- v6 resolver IPs scoped to :53 too
    assert.truthy(joined:find("2606:4700:4700::1111", 1, true))
  end)

  it("does NOT drop :443 or ICMP to the resolver IPs (connectivity probes survive)", function()
    local joined = table.concat(encrypted_dns.nft_rules(), "\n")
    assert.is_nil(joined:find("443", 1, true))
    assert.is_nil(joined:find("icmp", 1, true))
  end)

  it("does NOT emit a blanket all-traffic drop to the resolver IPs", function()
    -- every drop rule must be port-qualified (53 or 853); none may drop a
    -- resolver IP unconditionally.
    for _, rule in ipairs(encrypted_dns.nft_rules()) do
      if rule:find("8.8.8.8", 1, true) or rule:find("1.1.1.1", 1, true) then
        assert.truthy(rule:find("dport 53", 1, true),
          "resolver-IP rule not scoped to :53 — " .. rule)
      end
    end
  end)
end)

-- ── render integration: flag on ──────────────────────────────────────────────

describe("render with blockEncryptedDns = true", function()
  it("render.block_encrypted_dns reads the additive top-level flag", function()
    local s = snap(); s.blockEncryptedDns = true
    assert.is_true(render.block_encrypted_dns(s))
    assert.is_false(render.block_encrypted_dns(snap()))
  end)

  it("dnsmasq emits NODATA directives for the curated hostnames", function()
    local s = snap(); s.blockEncryptedDns = true
    local conf = render.dnsmasq(s)
    assert.truthy(conf:find("local=/mask.icloud.com/", 1, true))
    assert.truthy(conf:find("local=/dns.google/", 1, true))
  end)

  it("nft emits the wifihaven_encrypted_dns drop chain", function()
    local s = snap(); s.blockEncryptedDns = true
    local nft = render.nft(s)
    assert.truthy(nft:find("chain wifihaven_encrypted_dns", 1, true))
    assert.truthy(nft:find("tcp dport 853", 1, true))
    assert.truthy(nft:find("udp dport 53", 1, true))
    assert.truthy(nft:find("8.8.8.8", 1, true))
  end)
end)

-- ── render integration: flag off (no-op) ─────────────────────────────────────

describe("render with blockEncryptedDns absent/false (no-op)", function()
  it("dnsmasq emits no NODATA directives", function()
    assert.is_nil(render.dnsmasq(snap()):find("local=/mask.icloud.com/", 1, true))
    local s = snap(); s.blockEncryptedDns = false
    assert.is_nil(render.dnsmasq(s):find("local=/mask.icloud.com/", 1, true))
  end)

  it("nft emits no encrypted-dns chain", function()
    assert.is_nil(render.nft(snap()):find("wifihaven_encrypted_dns", 1, true))
    local s = snap(); s.blockEncryptedDns = false
    assert.is_nil(render.nft(s):find("wifihaven_encrypted_dns", 1, true))
  end)

  it("output is byte-identical to a snapshot with no flag at all", function()
    local s = snap(); s.blockEncryptedDns = false
    assert.are.equal(render.dnsmasq(snap()), render.dnsmasq(s))
    assert.are.equal(render.nft(snap()), render.nft(s))
  end)
end)
