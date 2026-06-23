-- encrypted_dns.lua — curated relay / DoH / DoT lists for the network-wide
-- `blockEncryptedDns` toggle (#1909 / #1911; epic #1903).
--
-- The wire carries a SINGLE top-level boolean (`snapshot.blockEncryptedDns`);
-- the lists below are deliberately NOT shipped on the wire. They are small,
-- stable, and apply identically to every router, so baking them into the agent
-- keeps the snapshot to one flag (the "snapshot is a minimal functional shape"
-- rule — a new policy concept rides an existing/parsimonious wire shape, never a
-- new data channel). When the lists need to grow, edit this module and ship a
-- new agent build; no API/wire change is required.
--
-- Two enforcement halves (see render.lua):
--   1. HOSTS              — answered NEGATIVELY by dnsmasq (`local=/<host>/` →
--                           NXDOMAIN). This is Apple's documented *clean* signal
--                           to disable iCloud Private Relay: a negative answer
--                           for mask.icloud.com / mask-h2.icloud.com makes iOS
--                           fall back to direct, filterable connections WITHOUT
--                           the client-side hang that a silent connection-layer
--                           drop of the relay ingress causes (the #1891 symptom).
--                           A positive 0.0.0.0 sinkhole does NOT disable it —
--                           the answer must be negative.
--   2. RESOLVER_IPS_*/    — dropped at the nftables forward chain (no DNS query
--      DOT_PORT             to negative-answer here: the device reached a public
--                           resolver by hardcoded IP or DoT on a fixed port,
--                           bypassing our resolver entirely).
--
-- NOTE — deliberate Truth-#1 exception. Architectural Truth #1 is "DNS is never
-- the enforcement plane; DNS always resolves." The HOSTS half breaks that, on
-- purpose and narrowly: a negative DNS answer here is *bypass-disable
-- signalling* (it tells the client to stop tunnelling so our real,
-- connection-layer enforcement can see the traffic), not content blocking. It
-- is scoped to this curated relay/DoH list and gated behind the household
-- toggle. See docs/architecture.md ("Truth #1 — the blockEncryptedDns
-- exception").

local M = {}

-- Relay ingress + DoH-by-name hostnames. `local=/<host>/` scopes to the host
-- and everything under it (e.g. *.mask.icloud.com), never the parent apex
-- (icloud.com keeps resolving). Keep these as bare DNS names — no scheme, no
-- `#`, no `/` — render.dnsmasq wraps each as `local=/<host>/`.
M.HOSTS = {
  -- Apple iCloud Private Relay ingress (the #1891 root cause).
  "mask.icloud.com",
  "mask-h2.icloud.com",
  -- Public DoH endpoints addressable by name.
  "cloudflare-dns.com",
  "dns.google",
  "dns.quad9.net",
  "dns.nextdns.io",
  "dns.adguard-dns.com",
  "doh.opendns.com",
}

-- Curated public-resolver v4 IPs. Dropped at the forward chain for every
-- managed MAC so a device pinning DoH/Do53 to a literal IP can't bypass the
-- LAN resolver. Cloudflare / Google / Quad9 anycast.
M.RESOLVER_IPS_V4 = {
  "1.1.1.1",         -- Cloudflare
  "1.0.0.1",         -- Cloudflare
  "8.8.8.8",         -- Google
  "8.8.4.4",         -- Google
  "9.9.9.9",         -- Quad9
  "149.112.112.112", -- Quad9 secondary
}

-- Curated public-resolver v6 IPs (siblings of the v4 set above).
M.RESOLVER_IPS_V6 = {
  "2606:4700:4700::1111", -- Cloudflare
  "2606:4700:4700::1001", -- Cloudflare
  "2001:4860:4860::8888", -- Google
  "2001:4860:4860::8844", -- Google
  "2620:fe::fe",          -- Quad9
}

-- DoT (DNS-over-TLS) fixed port. Dropped to ANY IP (a DoT resolver can live on
-- any address, so this is port-based, not IP-based).
M.DOT_PORT = 853

return M
