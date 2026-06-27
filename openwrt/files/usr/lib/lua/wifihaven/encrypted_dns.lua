-- encrypted_dns.lua — curated lists + rendering for the network-wide
-- "block encrypted DNS & relays" toggle (#1911 / feature #1909, epic #1903).
--
-- The wire carries ONE additive top-level boolean, `snapshot.blockEncryptedDns`
-- (read by render.lua). The curated relay/DoH hostnames and public-resolver IPs
-- are BAKED INTO THE AGENT here (small, stable; keeps the wire to a single
-- bool — see AGENTS.md "minimal functional shape"). This module owns the
-- curated data and the two render fragments that consume it; render.lua wires
-- them into the dnsmasq + nft output when the flag is on, and emits nothing
-- when it is absent/false (default-off no-op; old agents ignore the field).
--
-- ── Two separable enforcement halves (operator requirement #1909) ───────────
--
--   1. NODATA half (dnsmasq, this module's dnsmasq_lines): a NEGATIVE DNS
--      answer for the curated relay/DoH HOSTNAMES. This is the one sanctioned,
--      narrow exception to Architectural Truth #1 ("DNS always resolves") —
--      scoped to bypass-disable *signaling*, NOT to enforcement. It exists
--      because Apple's documented clean way to disable iCloud Private Relay is
--      a negative answer for `mask.icloud.com` / `mask-h2.icloud.com`: iOS then
--      turns Private Relay off network-wide and falls back to direct,
--      filterable connections (a 0.0.0.0 sinkhole does NOT trigger the
--      disable; it leaves Private Relay "on but failing" — the #1891 hang).
--      `local=/<host>/` answers the domain locally with no record → NODATA.
--
--   2. Connection-layer half (nftables, this module's nft_rules): drops that
--      catch the bypasses a hostname NODATA can't:
--        - DoT: drop TCP/853 to ANY IP (Android Private DNS / generic DoT).
--        - DNS-over-IP: drop UDP+TCP port 53 to the curated public-resolver
--          IPs ONLY (catches "system DNS hardcoded to 8.8.8.8", forcing
--          fallback to the LAN resolver).
--      Deliberately PORT-SCOPED: we do NOT drop :443 or ICMP to the resolver
--      IPs. `1.1.1.1` / `8.8.8.8` double as the world's most common "am I
--      online?" connectivity-check targets (e.g. the household Skylight
--      Calendar) — a blanket drop makes such devices believe they are
--      permanently offline (#1909 port-scope refinement). DoH pinned to a raw
--      resolver IP (:443) is the only bypass this leaves open; it is rare and
--      handled per-device later if it ever appears.
--
-- The two halves are independent: dnsmasq_lines() is the low-collateral NODATA
-- half, nft_rules() is the heavier drop half. render.lua gates both on the same
-- flag today, but keeping them as separate fragments preserves the option to
-- enable the gentle half alone.

local M = {}

-- ── Curated relay / DoH hostnames → NODATA ───────────────────────────────────
-- Apple iCloud Private Relay ingress + the major public DoH endpoints reachable
-- by name. Keep apex/canonical hostnames; obvious siblings live alongside.
M.HOSTS = {
  -- Apple iCloud Private Relay ingress
  "mask.icloud.com",
  "mask-h2.icloud.com",
  -- Public DoH-by-name
  "cloudflare-dns.com",
  "dns.google",
  "dns.quad9.net",
  "dns.nextdns.io",
  "dns.adguard-dns.com",
  "doh.opendns.com",
}

-- ── Curated public-resolver IPs → drop DNS :53 only ──────────────────────────
-- Cloudflare, Google, Quad9. These are the canonical hardcoded DNS targets.
-- NOTE: dual-use as connectivity-check targets — see the port-scope rationale
-- above; nft_rules() qualifies every rule with `dport 53`.
M.RESOLVER_IPS_V4 = {
  "1.1.1.1", "1.0.0.1",          -- Cloudflare
  "8.8.8.8", "8.8.4.4",          -- Google
  "9.9.9.9", "149.112.112.112",  -- Quad9
}
M.RESOLVER_IPS_V6 = {
  "2606:4700:4700::1111", "2606:4700:4700::1001",  -- Cloudflare
  "2001:4860:4860::8888", "2001:4860:4860::8844",  -- Google
  "2620:fe::fe",                                    -- Quad9
}

-- ── Cloudflare IPv6 DoH-on-:443 subset → drop :443 (tcp+udp) ─────────────────
-- The ONE place we drop :443 to a resolver IP (#2005, epic #1903). Deliberately
-- IPv6-Cloudflare-ONLY — v4 :443 and Google/Quad9 :443 are intentionally
-- EXCLUDED, for the same dual-use reason the :53 rules are port-scoped above:
-- 1.1.1.1 / 8.8.8.8 / 9.9.9.9 are the world's most common "am I online?"
-- connectivity-check targets, and Cloudflare serves a real HTTPS site on
-- 1.1.1.1 — a :443 drop there would strand probing devices. A v6 :443
-- connection to a resolver literal carries none of that collateral: generic
-- connectivity checks never target the resolver's v6 literal, so it is
-- near-pure DoH signal (the observed prod leak: 2606:4700:4700::1001 = 1.0.0.1
-- as unattributed v6 traffic). Cloudflare only here per the operator decision
-- in #2005; v4 :443 is a possible follow-up, not in this subset.
M.CF_DOH_IPS_V6 = {
  "2606:4700:4700::1111", "2606:4700:4700::1001",  -- Cloudflare only
}

-- dnsmasq_lines() → { "local=/<host>/", ... }
-- `local=/<host>/` answers the domain authoritatively-empty (NODATA) instead of
-- forwarding it upstream — the negative answer Apple's Private-Relay disable
-- requires. NOT a 0.0.0.0/address= sinkhole (that does not disable Private
-- Relay). DNS still "resolves" (NODATA is a valid, non-error answer) — this is
-- the narrow, documented Truth-#1 exception, scoped to bypass-disable signaling.
function M.dnsmasq_lines()
  local lines = {}
  for _, h in ipairs(M.HOSTS) do
    lines[#lines + 1] = "local=/" .. h .. "/"
  end
  return lines
end

-- nft_rules() → { "<rule>", ... }  (chain-body lines, no leading indent)
-- Connection-layer half. Order is irrelevant (all terminal drops under a
-- `policy accept` chain; the predicate is a disjunction). Every rule is
-- port-scoped: DoT is TCP/853 (any IP); the resolver-IP rules are :53 only.
-- Each carries a non-`wh_drop:` comment so `nft list ruleset` is legible
-- without the per-MAC nft_drops.lua / nflog.lua attribution paths counting
-- these network-wide rules (they parse only `wh_drop:<mac>:<reason>` comments;
-- per-flow observability for this toggle is tracked separately in #1907).
function M.nft_rules()
  local rules = {}
  -- DoT: drop TCP/853 to any IP (matches both v4 and v6 — no family qualifier).
  rules[#rules + 1] =
    "tcp dport 853 counter drop comment \"wh_encrypted_dns:dot853\""
  -- DNS-over-IP to the curated resolver IPs, port 53 only (udp + tcp), v4.
  local v4 = table.concat(M.RESOLVER_IPS_V4, ", ")
  rules[#rules + 1] = string.format(
    "ip daddr { %s } udp dport 53 counter drop comment \"wh_encrypted_dns:resolver53\"", v4)
  rules[#rules + 1] = string.format(
    "ip daddr { %s } tcp dport 53 counter drop comment \"wh_encrypted_dns:resolver53\"", v4)
  -- v6 siblings.
  local v6 = table.concat(M.RESOLVER_IPS_V6, ", ")
  rules[#rules + 1] = string.format(
    "ip6 daddr { %s } udp dport 53 counter drop comment \"wh_encrypted_dns:resolver53\"", v6)
  rules[#rules + 1] = string.format(
    "ip6 daddr { %s } tcp dport 53 counter drop comment \"wh_encrypted_dns:resolver53\"", v6)
  -- Hardcoded-IP DoH on :443 to Cloudflare's v6 resolver literals (#2005). The
  -- one sanctioned :443 resolver drop — Cloudflare IPv6 ONLY (see CF_DOH_IPS_V6
  -- for why v4 :443 and Google/Quad9 :443 are deliberately excluded). BOTH
  -- tcp/443 and udp/443: Cloudflare serves DoH over HTTP/3, so udp/443 must be
  -- covered too. Non-`wh_drop:` comment so the per-MAC attribution parsers
  -- ignore it, consistent with the resolver53 rules above.
  local cfv6 = table.concat(M.CF_DOH_IPS_V6, ", ")
  rules[#rules + 1] = string.format(
    "ip6 daddr { %s } tcp dport 443 counter drop comment \"wh_encrypted_dns:cf_doh443_v6\"", cfv6)
  rules[#rules + 1] = string.format(
    "ip6 daddr { %s } udp dport 443 counter drop comment \"wh_encrypted_dns:cf_doh443_v6\"", cfv6)
  return rules
end

return M
