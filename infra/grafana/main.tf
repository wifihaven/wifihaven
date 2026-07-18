# Grafana Cloud dashboard deploy for wifihaven (#1270, follow-up to #1209).
#
# Manages:
#   - One grafana_dashboard resource per JSON under
#     deploy/grafana/dashboards/, sourced directly from the in-repo file so
#     the committed JSON stays canonical (no copy, no drift).
#
# Single stack: this config is applied to one Grafana Cloud stack that serves
# every environment. Its own CD pipeline (master-grafana.yml, the
# `deploy-grafana` job) runs it on push to main whenever a dashboard or this
# Terraform changes, authenticated by the `grafana_url` / `grafana_auth`
# variables (fed from the GRAFANA_URL / GRAFANA_AUTH secrets). Dashboards are
# environment-agnostic — they select their data via the templated
# `${datasource}` variable at view time — so a per-environment dashboard copy
# is unnecessary.
#
# State: remote backend on HCP Terraform (the `cloud {}` block below), free
# tier, with native state locking. The dashboards themselves are upsert-by-uid
# idempotent and would survive empty state, but the alerting work this unblocks
# (contact points, the singleton notification policy, rule groups — #1403/#1404/
# #1405) is *stateful*: a create against fresh-every-run empty state either 409s
# on a fixed identifier or accumulates duplicates, and the root notification
# policy has no upsert-by-uid. So Terraform must own its prior state. Migrating
# to the remote backend now (#1406) is the hard prerequisite for that chain;
# the dashboards keep reconciling unchanged. State holds resource IDs but no
# secrets (the Grafana token comes from the grafana_auth var / GRAFANA_AUTH
# secret, not the state). Mirrors the infra/cloudflare backend (#1357); see
# README.md for the one-time backend setup + local-state migration runbook.
#
# A pre-existing folder can still be targeted via the optional `folder_uid`
# variable (the operator creates it once per stack, like the stack itself);
# when unset the dashboards land in the stack's General folder.
#
# Does NOT manage:
#   - The Grafana Cloud stack / instance itself (created once via the Grafana
#     Cloud dashboard).
#   - The datasource: dashboards reference a templated `${datasource}`
#     variable resolved at view time, so the same JSON loads against the
#     Grafana Cloud Prometheus datasource (cloud path) and a self-hosted
#     provisioned Prometheus (#1207) without edits.
#   - The service-account token (operator creates it in Grafana Cloud; value
#     supplied via `grafana_auth` / the GRAFANA_AUTH secret, never committed).

terraform {
  required_version = ">= 1.6"

  # Remote state on HCP Terraform (Terraform Cloud), free tier. Native state
  # locking means CI applies can't race or clobber each other, and a fresh CI
  # checkout reads the canonical state instead of starting empty — which is what
  # lets the stateful alerting resources (#1403/#1404/#1405) be idempotent
  # rather than 409ing or duplicating on every run (#1406).
  #
  # Org + workspace come from TF_CLOUD_ORGANIZATION / TF_WORKSPACE env vars
  # (set in .github/workflows/master-grafana.yml and documented in README.md),
  # so this block stays account- and creds-agnostic. Auth is the
  # TF_TOKEN_app_terraform_io env var. The workspace runs in *local* execution
  # mode: HCP only stores state + provides locking, while `terraform apply` runs
  # on the GitHub runner with the Grafana token from the GRAFANA_AUTH secret —
  # mirrors the infra/cloudflare pipeline (#1357).
  cloud {}

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
  dashboards = ["api-health", "api-self-metrics", "router-fleet", "rollup-health", "db-health", "data-quality-ingest", "enforcement", "router-ws-transport", "spa-ws", "beta-pipeline", "billing", "support", "press"]
  dashfiles  = { for name in local.dashboards : name => "${path.module}/../../deploy/grafana/dashboards/${name}.json" }
}

resource "grafana_dashboard" "wifihaven" {
  for_each    = local.dashfiles
  folder      = local.folder
  overwrite   = true
  config_json = file(each.value)
}
