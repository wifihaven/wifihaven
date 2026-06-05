# Grafana Cloud alerting: contact points + the singleton notification policy
# (#1403, parent #1381). Implements docs/design/alerting.md §4 (routing &
# contact points), §5 (severity model), §6 (per-env handling).
#
# This is the stateful half of the alerting work that the HCP remote backend
# (#1406, the `cloud {}` block in main.tf) exists to support: the root
# notification policy is a singleton with no upsert-by-uid, so Terraform must
# own its prior state for the apply to be idempotent rather than 409 / clobber.
#
# Contact points (all email — see §4 "no invented transports": a single
# household operator, email is the only real channel Grafana Cloud sends
# natively). They are three *distinct* resources, not one, precisely so the
# `critical` channel can later be re-pointed at a pager integration without
# touching warning/staging routing or any rule.
#
# The operator's address is NOT committed: it comes from the operator_email
# variable (terraform.tfvars / TF_VAR_operator_email locally,
# GRAFANA_OPERATOR_EMAIL secret in CD), the same handling as grafana_auth.
#
# Unblocks the rule sets: #1404 (critical C1–C7, incl. the #1368 ingest-failure
# alert) and #1405 (warning rules) attach `severity` / `env` labels that this
# policy routes.

# ── Contact points ──────────────────────────────────────────────────────────

resource "grafana_contact_point" "critical" {
  name = "wifihaven-critical"

  email {
    addresses = [var.operator_email]
    subject   = "[wifihaven CRITICAL] {{ .GroupLabels.alertname }}"
  }
}

resource "grafana_contact_point" "warning" {
  name = "wifihaven-warning"

  email {
    addresses = [var.operator_email]
    subject   = "[wifihaven warning] {{ .GroupLabels.alertname }}"
  }
}

resource "grafana_contact_point" "staging" {
  name = "wifihaven-staging"

  email {
    addresses = [var.operator_email]
    subject   = "[wifihaven staging] {{ .GroupLabels.alertname }}"
  }
}

# ── Notification policy (singleton root) ─────────────────────────────────────
# Grouping / throttling per §4: group by (alertname, env); a 4h repeat keeps an
# unacknowledged page visible without re-mailing a single household operator
# every few minutes. Children inherit these settings.
#
# Child policy ORDER MATTERS — Grafana evaluates top-to-bottom and stops at the
# first match (continue defaults to false). The env="staging" branch is first
# so staging short-circuits to the notify-only `staging` contact point before
# any severity match can route it to the page channel (§6: staging never pages).
#
# The root default contact point is `warning`, matching the design's
# "default ──► contact: warning".

resource "grafana_notification_policy" "wifihaven" {
  group_by      = ["alertname", "env"]
  contact_point = grafana_contact_point.warning.name

  group_wait      = "30s"
  group_interval  = "5m"
  repeat_interval = "4h"

  # 1. staging (any severity) → staging contact, notify only. First match wins,
  #    so this short-circuits before the severity branches below.
  policy {
    matcher {
      label = "env"
      match = "="
      value = "staging"
    }
    contact_point = grafana_contact_point.staging.name
    group_by      = ["alertname", "env"]
  }

  # 2. severity=critical → critical contact (the page channel).
  policy {
    matcher {
      label = "severity"
      match = "="
      value = "critical"
    }
    contact_point = grafana_contact_point.critical.name
    group_by      = ["alertname", "env"]
  }

  # 3. severity=warning → warning contact (notify).
  policy {
    matcher {
      label = "severity"
      match = "="
      value = "warning"
    }
    contact_point = grafana_contact_point.warning.name
    group_by      = ["alertname", "env"]
  }
}
