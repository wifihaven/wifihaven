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
  description = "Leaf A record IP for e2e-edge.wifihaven.net — used by the direct-requery CNAME e2e fixture (#1351). Must be an IP that responds to HTTP/80 with a non-block-page body so the harness can distinguish 'reached real origin' from 'got DNAT block page'. Default: 93.184.216.34 (IANA example.com — stable, used by the harness as its reference internet-reachable origin)."
  default     = "93.184.216.34"
}
