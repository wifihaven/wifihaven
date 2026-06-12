variable "account_id" {
  type        = string
  description = "Cloudflare account ID. Find in any zone's right sidebar."
}

variable "zone_id" {
  type        = string
  description = "Zone ID for wifihaven.net. Find in the zone overview right sidebar."
}

variable "api_prod_cname_target" {
  type        = string
  description = "CNAME target for api.wifihaven.net — Render → wifihaven-api-prod → Settings → Custom Domains shows the exact value."
}

variable "api_staging_cname_target" {
  type        = string
  description = "CNAME target for api-staging.wifihaven.net — Render → wifihaven-api-staging → Settings → Custom Domains shows the exact value."
}

variable "e2e_edge_ip" {
  type        = string
  description = "Leaf A record IP for e2e-edge.wifihaven.net — used by the direct-requery CNAME e2e fixture (#1351). Default 192.0.2.10 is RFC 5737 TEST-NET-1, reserved-for-documentation and GUARANTEED never globally routed or reassigned (#1360). The previous value 93.184.216.34 (legacy IANA example.com) was decommissioned ~2025 and its HTTP/80 went dead, silently red-gating router releases. The block-path e2e tests (G1/G4) DNAT port 80 at prerouting before the dest is routed, so an unroutable leaf is fine; the allow-path tests (G2/G3) xfail when the leaf is unreachable rather than depending on a live third-party origin (no public IP reliably returns 2xx for an arbitrary Host header anymore). See scripts/e2e/scenarios_fake/test_cname_direct_requery.py."
  default     = "192.0.2.10"
}

variable "e2e_edge_ip6" {
  type        = string
  description = "Leaf AAAA record IP for e2e-edge.wifihaven.net — used by the v6 attribution Gate 2 scenario (#1677, CD-tier follow-up to PR #1673 / #1668). Default 2001:db8::10 is RFC 3849 documentation space (2001:db8::/32), reserved-for-documentation and GUARANTEED never globally routed. The attribution scenario only needs the LAN-side conntrack NEW event on the v6 SYN, which fires as the packet crosses the router's FORWARD chain — drops downstream of the WAN are irrelevant (qemu SLIRP is v4-only anyway). Pairing this AAAA with the existing A on e2e-edge produces the realistic dual-stack shape of a production host: one name returning both families, exercising dns_log's v6 path the same way the v4 fixture exercises its v4 path. See scripts/e2e/scenarios_fake/test_v6_attribution.py. NOTE: pass the value in canonical form (lowercase hex, no leading zeros, e.g. `2001:db8::10`) — Cloudflare's API normalizes on round-trip, so a non-canonical override produces a perpetual `terraform plan` diff."
  default     = "2001:db8::10"
}
