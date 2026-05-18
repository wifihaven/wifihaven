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
