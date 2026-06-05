# Grafana-managed alerting for wifihaven (#1368, first rule of the #1381
# alerting strategy — docs/design/alerting.md).
#
# This is the concrete alert that starts the alerting thread: a router-metrics
# ingest failure now PAGES the operator instead of only being graphable. It is
# alert **C4** in docs/design/alerting.md §7.1 — the direct successor to the
# at-a-glance success-ratio stat panel that shipped on the api-self-metrics
# dashboard (#1373/#1384). It reuses that panel's exact PromQL, adds the
# zero-traffic guard, and routes it `critical`.
#
# ── Hard prerequisite: remote Terraform state (#1406) ────────────────────────
# Everything in THIS file is stateful — grafana_contact_point,
# grafana_notification_policy (a singleton root tree), grafana_folder, and
# grafana_rule_group have no "upsert by uid / overwrite=true" escape hatch the
# way grafana_dashboard does (main.tf). Applied against the stateless,
# empty-every-run CD that infra/grafana uses today they would 409 on a fixed
# identifier or accumulate duplicates across runs.
#
# So these resources MUST NOT be applied until infra/grafana is migrated onto
# an HCP Terraform remote backend — filed as the blocking prerequisite #1406
# (mirrors #1357 for infra/cloudflare). This PR therefore STACKS ON #1406:
# - The grafana-terraform CI lint (`terraform fmt -check` + `validate
#   -backend=false`) passes today and gates this PR.
# - The live `terraform apply` in master-grafana.yml must not run this until
#   #1406 lands (the `cloud {}` backend + workflow wiring + the operator's
#   one-time HCP `grafana` workspace + `operator_email` plumbing).
# See docs/design/alerting.md §3.1 and §9 for the full sequencing.
#
# The catalog in docs/design/alerting.md §7 (C1–C7 critical, W1–W5 warning)
# extends THIS plumbing — same contact points, same notification policy, more
# grafana_rule_group resources — rather than replacing it (#1402/#1403/#1404).

# ── Folder ──────────────────────────────────────────────────────────────────
# Alert rules must live in a folder (folder_uid is required on a rule group).
# Unlike the dashboards (main.tf deliberately does not manage a folder, to stay
# stateless-idempotent), this is safe to manage here precisely because the
# alerting resources already require remote state (#1406) — under managed state
# a folder is upserted by uid, not recreated every run.
resource "grafana_folder" "alerts" {
  title = "WifiHaven Alerts"
  uid   = "wifihaven-alerts"
}

# ── Contact points ───────────────────────────────────────────────────────────
# Three points, all email to the single household operator (no invented pager /
# Slack transport with no consumer — docs/design/alerting.md §4). They are
# distinct resources, not one, so the `critical` point can later be re-pointed
# at a real pager integration without touching warning routing or any rule.
resource "grafana_contact_point" "critical" {
  name = "wifihaven-critical"

  email {
    addresses = [var.operator_email]
    subject   = "[wifihaven CRITICAL] {{ .CommonLabels.alertname }}"
    message   = "{{ range .Alerts }}{{ .Annotations.summary }}\n{{ .Annotations.description }}\n{{ end }}"
  }
}

resource "grafana_contact_point" "warning" {
  name = "wifihaven-warning"

  email {
    addresses = [var.operator_email]
    subject   = "[wifihaven warning] {{ .CommonLabels.alertname }}"
    message   = "{{ range .Alerts }}{{ .Annotations.summary }}\n{{ .Annotations.description }}\n{{ end }}"
  }
}

resource "grafana_contact_point" "staging" {
  name = "wifihaven-staging"

  email {
    addresses = [var.operator_email]
    subject   = "[wifihaven staging] {{ .CommonLabels.alertname }}"
    message   = "{{ range .Alerts }}{{ .Annotations.summary }}\n{{ .Annotations.description }}\n{{ end }}"
  }
}

# ── Notification policy (singleton root tree) ────────────────────────────────
# Severity/env routing in front of the contact points so swapping email for a
# real pager later is a one-resource edit, not a re-architecture
# (docs/design/alerting.md §4). Order matters: env="staging" is matched FIRST
# so a staging alert is only ever notified (never paged), short-circuiting
# before the severity matches. Grouping/throttle keeps a single firing alert
# from re-mailing the household operator every few minutes.
resource "grafana_notification_policy" "root" {
  group_by      = ["alertname", "env"]
  contact_point = grafana_contact_point.warning.name # default for anything unmatched

  group_wait      = "30s"
  group_interval  = "5m"
  repeat_interval = "4h"

  # First match wins: staging never pages.
  policy {
    matcher {
      label = "env"
      match = "="
      value = "staging"
    }
    contact_point = grafana_contact_point.staging.name
    group_by      = ["alertname", "env"]
  }

  policy {
    matcher {
      label = "severity"
      match = "="
      value = "critical"
    }
    contact_point = grafana_contact_point.critical.name
    group_by      = ["alertname", "env"]
  }

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

# ── Rule group: router-metrics ingest health ─────────────────────────────────
# Alert C4 (docs/design/alerting.md §7.1) + its staging twin at severity
# `warning` (§6 — staging exercises this path but a staging blip must not
# page). Evaluated every 60s.
#
# Each rule is the standard Grafana-managed three-stage shape:
#   A — success ratio (instant PromQL; the EXACT #1373/#1384 panel expression)
#   B — traffic guard  (instant PromQL; batch rate over the window)
#   C — math: fire when ratio < 0.95 AND there is traffic
# Condition = C. A math result of 1 (true) is the firing condition.
#
# Threshold + `for` rationale (docs/design/alerting.md §7.1 C4):
#   - ratio < 0.95: the literal #1365 condition — `ok=0, malformed>0` drives the
#     ratio to 0 (the `or vector(0)` coercion makes ok-absent read as 0, not
#     empty, exactly as the dashboard panel does), which is < 0.95.
#   - guard `> 0`: an idle env yields 0/0 = empty; without the guard the rule
#     would flap or fire perpetually against no traffic. no_data_state = "OK"
#     belts-and-braces this so a genuinely quiet window stays Normal.
#   - for: 15m — matches the 10m rate window plus margin, so a single bad batch
#     during a deploy does not page.
resource "grafana_rule_group" "router_metrics_ingest" {
  name             = "wifihaven-router-metrics-ingest"
  folder_uid       = grafana_folder.alerts.uid
  interval_seconds = 60

  # C4 — prod, critical (pages).
  rule {
    name      = "Router metrics ingest failure"
    condition = "C"
    for       = "15m"

    no_data_state  = "OK"
    exec_err_state = "Error"

    labels = {
      severity = "critical"
      env      = "prod"
    }

    annotations = {
      summary     = "Router-metrics ingest success ratio < 95% (prod)"
      description = "POST /api/router/metrics is rejecting batches: the success ratio (status=ok / all) over 10m is below 0.95 with traffic present. This is the #1365 silent-failure shape (ok=0, malformed climbing). Check router_metrics_batches_total{status} on the api-self-metrics dashboard and the agent fleet's metrics push."
      runbook_url = "https://github.com/wifihaven/wifihaven/blob/main/docs/design/alerting.md#71-critical--page-now"
    }

    data {
      ref_id         = "A"
      datasource_uid = var.prometheus_datasource_uid
      relative_time_range {
        from = 600
        to   = 0
      }
      model = jsonencode({
        refId         = "A"
        editorMode    = "code"
        expr          = "(sum(rate(router_metrics_batches_total{env=\"prod\",status=\"ok\"}[10m])) or vector(0)) / sum(rate(router_metrics_batches_total{env=\"prod\"}[10m]))"
        instant       = true
        range         = false
        intervalMs    = 1000
        maxDataPoints = 43200
      })
    }

    data {
      ref_id         = "B"
      datasource_uid = var.prometheus_datasource_uid
      relative_time_range {
        from = 600
        to   = 0
      }
      model = jsonencode({
        refId         = "B"
        editorMode    = "code"
        expr          = "sum(rate(router_metrics_batches_total{env=\"prod\"}[10m]))"
        instant       = true
        range         = false
        intervalMs    = 1000
        maxDataPoints = 43200
      })
    }

    data {
      ref_id         = "C"
      datasource_uid = "__expr__"
      relative_time_range {
        from = 600
        to   = 0
      }
      model = jsonencode({
        refId      = "C"
        type       = "math"
        expression = "$A < 0.95 && $B > 0"
        datasource = {
          type = "__expr__"
          uid  = "__expr__"
        }
      })
    }
  }

  # C4 staging twin — warning (notifies, never pages; routed by env="staging").
  rule {
    name      = "Router metrics ingest failure (staging)"
    condition = "C"
    for       = "15m"

    no_data_state  = "OK"
    exec_err_state = "Error"

    labels = {
      severity = "warning"
      env      = "staging"
    }

    annotations = {
      summary     = "Router-metrics ingest success ratio < 95% (staging)"
      description = "Staging POST /api/router/metrics success ratio over 10m is below 0.95 with traffic present. Notify-only (staging never pages); investigate before it reaches prod."
      runbook_url = "https://github.com/wifihaven/wifihaven/blob/main/docs/design/alerting.md#71-critical--page-now"
    }

    data {
      ref_id         = "A"
      datasource_uid = var.prometheus_datasource_uid
      relative_time_range {
        from = 600
        to   = 0
      }
      model = jsonencode({
        refId         = "A"
        editorMode    = "code"
        expr          = "(sum(rate(router_metrics_batches_total{env=\"staging\",status=\"ok\"}[10m])) or vector(0)) / sum(rate(router_metrics_batches_total{env=\"staging\"}[10m]))"
        instant       = true
        range         = false
        intervalMs    = 1000
        maxDataPoints = 43200
      })
    }

    data {
      ref_id         = "B"
      datasource_uid = var.prometheus_datasource_uid
      relative_time_range {
        from = 600
        to   = 0
      }
      model = jsonencode({
        refId         = "B"
        editorMode    = "code"
        expr          = "sum(rate(router_metrics_batches_total{env=\"staging\"}[10m]))"
        instant       = true
        range         = false
        intervalMs    = 1000
        maxDataPoints = 43200
      })
    }

    data {
      ref_id         = "C"
      datasource_uid = "__expr__"
      relative_time_range {
        from = 600
        to   = 0
      }
      model = jsonencode({
        refId      = "C"
        type       = "math"
        expression = "$A < 0.95 && $B > 0"
        datasource = {
          type = "__expr__"
          uid  = "__expr__"
        }
      })
    }
  }
}
