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
