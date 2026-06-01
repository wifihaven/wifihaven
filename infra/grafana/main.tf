# Grafana Cloud resources for wifihaven (#1270, follow-up to #1209).
#
# Manages:
#   - One Grafana folder ("WifiHaven") to hold the dashboards.
#   - One grafana_dashboard resource per JSON under
#     deploy/grafana/dashboards/, sourced directly from the in-repo file so
#     the committed JSON stays canonical (no copy, no drift).
#
# Does NOT manage:
#   - The Grafana Cloud stack / instance itself (created once via the Grafana
#     Cloud dashboard).
#   - The datasource: dashboards reference a templated `${datasource}`
#     variable resolved at view time, so the same JSON loads against the
#     Grafana Cloud Prometheus datasource (cloud path) and a self-hosted
#     provisioned Prometheus (#1207) without edits.
#   - The service-account token (operator creates in Grafana Cloud; value is
#     supplied via the `grafana_auth` variable / GRAFANA_AUTH secret, never
#     committed).
#
# State: local backend. The state file holds resource IDs but no secrets
# (the token comes from the GRAFANA_AUTH env/var, not a committed tfvars).
# TODO(#1270): move to a remote backend before sharing infra ops.

terraform {
  required_version = ">= 1.6"
  required_providers {
    grafana = {
      source  = "grafana/grafana"
      version = "~> 3.0"
    }
  }
}

provider "grafana" {
  url  = var.grafana_url
  auth = var.grafana_auth
}

# ── Folder ──────────────────────────────────────────────────────────────────

resource "grafana_folder" "wifihaven" {
  title = "WifiHaven"
}

# ── Dashboards ──────────────────────────────────────────────────────────────
# config_json points at the canonical in-repo JSON. Editing a dashboard in
# Grafana, exporting, and committing the JSON is the source-of-truth workflow
# (docs/design/metrics-observability.md §8); `terraform apply` re-syncs it.
# overwrite=true lets an apply replace a dashboard imported by hand with the
# repo copy on the next sync.

resource "grafana_dashboard" "api_health" {
  folder      = grafana_folder.wifihaven.id
  overwrite   = true
  config_json = file("${path.module}/../../deploy/grafana/dashboards/api-health.json")
}

resource "grafana_dashboard" "router_fleet" {
  folder      = grafana_folder.wifihaven.id
  overwrite   = true
  config_json = file("${path.module}/../../deploy/grafana/dashboards/router-fleet.json")
}

resource "grafana_dashboard" "dnsmasq_enforcement" {
  folder      = grafana_folder.wifihaven.id
  overwrite   = true
  config_json = file("${path.module}/../../deploy/grafana/dashboards/dnsmasq-enforcement.json")
}

resource "grafana_dashboard" "data_quality_ingest" {
  folder      = grafana_folder.wifihaven.id
  overwrite   = true
  config_json = file("${path.module}/../../deploy/grafana/dashboards/data-quality-ingest.json")
}
