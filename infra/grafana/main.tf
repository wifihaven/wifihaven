# Grafana Cloud dashboard deploy for wifihaven (#1270, follow-up to #1209).
#
# Manages:
#   - One grafana_dashboard resource per JSON under
#     deploy/grafana/dashboards/, sourced directly from the in-repo file so
#     the committed JSON stays canonical (no copy, no drift).
#
# Per-environment: this same config is applied once per Grafana Cloud stack.
# In CD (master-api-ui.yml) it runs twice — against the staging stack on the
# staging deploy, and against the prod stack after the production-approval
# gate — authenticated by per-environment service-account tokens. The
# `grafana_url` / `grafana_auth` variables select the target stack.
#
# Stateless by design. The CD jobs run on ephemeral runners with no
# persisted state, so every apply starts from an empty state. That is safe
# here because each grafana_dashboard is upserted by its stable `uid`
# (overwrite = true) — applying against an empty state updates the existing
# dashboard rather than erroring on a duplicate. We deliberately do NOT
# manage a grafana_folder resource: a folder create would either 409 on a
# fixed uid or accumulate duplicate folders across stateless runs. Instead a
# pre-existing folder can be targeted via the optional `folder_uid` variable
# (the operator creates it once per stack, like the stack itself); when unset
# the dashboards land in the stack's General folder.
#
# Does NOT manage:
#   - The Grafana Cloud stack / instance itself (created once per environment
#     via the Grafana Cloud dashboard).
#   - The datasource: dashboards reference a templated `${datasource}`
#     variable resolved at view time, so the same JSON loads against the
#     Grafana Cloud Prometheus datasource (cloud path) and a self-hosted
#     provisioned Prometheus (#1207) without edits.
#   - The service-account token (operator creates it per stack in Grafana
#     Cloud; value supplied via `grafana_auth` / the GRAFANA_AUTH_* secret,
#     never committed).

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

# ── Dashboards ──────────────────────────────────────────────────────────────
# config_json points at the canonical in-repo JSON. Editing a dashboard in
# Grafana, exporting, and committing the JSON is the source-of-truth workflow
# (docs/design/metrics-observability.md §8); the next push re-applies it.
# overwrite = true makes the apply idempotent by uid and lets it replace a
# dashboard imported by hand with the repo copy.

locals {
  folder     = var.folder_uid != "" ? var.folder_uid : null
  dashboards = ["api-health", "router-fleet", "dnsmasq-enforcement", "data-quality-ingest"]
  dashfiles  = { for name in local.dashboards : name => "${path.module}/../../deploy/grafana/dashboards/${name}.json" }
}

resource "grafana_dashboard" "wifihaven" {
  for_each    = local.dashfiles
  folder      = local.folder
  overwrite   = true
  config_json = file(each.value)
}
