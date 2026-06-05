# Critical (page-now) alert rules — C1–C7
# (#1404, parent #1381). Implements docs/design/alerting.md §7.1.
#
# Stacks on the warning set (#1405, alerting-rules-warning.tf): shares the
# managed grafana_folder.alerts and var.prometheus_datasource_uid from
# alerting-rules.tf, and uses the same two-node Grafana managed condition shape
# (instant Prometheus query ref A → threshold expression ref C). The only
# structural additions the critical set needs over the warning set:
#
#   - a per-rule `op` (gt OR lt) — the warning rules are all `gt`, but C4/C7 are
#     "below threshold" conditions;
#   - per-rule `severity`/`env` labels — C4 and C6 each carry a severity=warning
#     env=staging TWIN (§6: staging exercises these paths but must never page;
#     the notification policy short-circuits env=staging to the notify-only
#     staging contact point);
#   - ratio expressions with a built-in zero-traffic guard. A ratio over an idle
#     environment is 0/0 → NaN/empty, which must NOT fire. C4 reuses the #1373
#     success-ratio panel expression including the load-bearing `or vector(0)`
#     (#1383/#1384): when EVERY batch is malformed there is no status="ok"
#     series, so without it a total outage reads empty (no-data, green) instead
#     of 0 (fires red). An idle env leaves the denominator empty → no-data → OK
#     (idle ≠ broken; total agent silence is C7's job). C6 guards with a
#     trailing `and (<total rate> > 0.05)` so an idle minute with one error
#     can't read as 100%.
#
# Every expression targets a series emitted today (§2 "alert only on series that
# exist"). None of C1–C7 falls in the §8 gaps list, so none ships paused — the
# deferred alerts (W5 blocklist-fetch, deploy-failure) live in the warning set /
# their own readiness issues. C4 closes the alerting half of #1368 (the silent
# 100%-malformed router-metrics ingest, #1365).
#
# C1/C3 query the Render OTLP infra stream (render_*), which carries
# service_name (not env) and exists only for the prod datastore — so they are
# prod-only by construction (§6). C5 uses absent() rather than an unconfirmed
# up{} target-health series (§7.1 C5): while the API is up the series is present
# and absent() yields nothing → no-data → OK; when it vanishes absent() returns
# 1 and the rule fires.

locals {
  # Keyed for stable resource addressing. `window_s` bounds the data fetch and
  # must cover the rate window in `expr`. `op`/`threshold` map to the Grafana
  # threshold evaluator (gt/lt — there is no `eq`, so C7's "== 0" is `lt 1`
  # over the integer router-count gauge).
  critical_rules = {
    c1 = {
      title     = "C1 Postgres CPU saturation"
      expr      = "rate(render_service_cpu_time_seconds{service_name=\"wifihaven-pg-prod\"}[5m])"
      window_s  = 300
      op        = "gt"
      threshold = 0.8
      for       = "5m"
      severity  = "critical"
      env       = "prod"
      summary   = "Postgres CPU > 0.8 cores (per-second rate of render_service_cpu_time_seconds) on wifihaven-pg-prod for 5m — the regime the 2026-05-31 rollup cadence drove the single-core managed-PG plan into (#1331). Plan-coupled threshold: revisit if the PG core count changes. Pairs with C2."
    }
    c2 = {
      title     = "C2 HikariCP pending connections"
      expr      = "wifihaven_db_pool_threads_awaiting_connection{env=\"prod\"}"
      window_s  = 300
      op        = "gt"
      threshold = 0
      for       = "2m"
      severity  = "critical"
      env       = "prod"
      summary   = "Threads awaiting a DB connection > 0 for 2m. Healthy load holds this at 0; a sustained nonzero value is the exact precursor of the pool-exhaustion crash loop (#1331), caught before the pool fully starves. Pairs with C1 — both firing is the unambiguous 'it's 2026-05-31 again' signal."
    }
    c3 = {
      title     = "C3 Postgres connection ceiling"
      expr      = "render_postgres_connections{service_name=\"wifihaven-pg-prod\"} / render_postgres_connection_limit{service_name=\"wifihaven-pg-prod\"}"
      window_s  = 300
      op        = "gt"
      threshold = 0.9
      for       = "5m"
      severity  = "critical"
      env       = "prod"
      summary   = "Postgres using > 90% of its connection limit for 5m. The managed-PG cap is a hard wall — crossing 90% means the next traffic bump starts refusing connections. Distinct from C2: the server's total across all clients, not just the app's HikariCP view."
    }
    c4 = {
      title     = "C4 Router-metrics ingest failure"
      expr      = "(sum(rate(router_metrics_batches_total{env=\"prod\",status=\"ok\"}[10m])) or vector(0)) / sum(rate(router_metrics_batches_total{env=\"prod\"}[10m]))"
      window_s  = 600
      op        = "lt"
      threshold = 0.95
      for       = "15m"
      severity  = "critical"
      env       = "prod"
      summary   = "POST /api/router/metrics success ratio < 0.95 for 15m — the literal #1365 silent-ingest-failure condition (closes the alerting half of #1368). Same expression as the #1373 success-ratio panel; the `or vector(0)` makes a 100%-malformed total outage read 0 (fires red) instead of empty (no-data), per #1383/#1384. To force-test: POST a deliberately malformed batch (alerting.md §10)."
    }
    c4_staging = {
      title     = "C4 Router-metrics ingest failure (staging)"
      expr      = "(sum(rate(router_metrics_batches_total{env=\"staging\",status=\"ok\"}[10m])) or vector(0)) / sum(rate(router_metrics_batches_total{env=\"staging\"}[10m]))"
      window_s  = 600
      op        = "lt"
      threshold = 0.95
      for       = "15m"
      severity  = "warning"
      env       = "staging"
      summary   = "Staging twin of C4 (§6). Staging exercises the ingest path, but a blip notifies (warning) rather than pages — the notification policy short-circuits env=staging to the staging contact point."
    }
    c5 = {
      title     = "C5 API down / metrics silent"
      expr      = "absent(http_requests_total{env=\"prod\"})"
      window_s  = 300
      op        = "gt"
      threshold = 0
      for       = "2m"
      severity  = "critical"
      env       = "prod"
      summary   = "http_requests_total{env=\"prod\"} absent for 2m — the API process is dead or its scrape target is gone (the C6 5xx ratio can't even be computed). While the API is up the series is present and absent() yields nothing → no-data → OK; when it vanishes absent() returns 1 and this fires. If the Alloy scrape later exposes up{job=\"wifihaven-api\",env=\"prod\"}, prefer up == 0 (distinguishes scrape-failed from no-requests) — switch only after confirming that series exists (§7.1 C5)."
    }
    c6 = {
      title     = "C6 API 5xx error ratio"
      expr      = "(sum(rate(http_requests_total{status=~\"5..\",env=\"prod\"}[5m])) / sum(rate(http_requests_total{env=\"prod\"}[5m]))) and (sum(rate(http_requests_total{env=\"prod\"}[5m])) > 0.05)"
      window_s  = 300
      op        = "gt"
      threshold = 0.05
      for       = "5m"
      severity  = "critical"
      env       = "prod"
      summary   = "5xx-response ratio > 0.05 for 5m — real user-facing breakage. The trailing `and (... > 0.05)` is the zero-traffic guard (≈ a request every 20s): below that rate the ratio is statistically meaningless at household scale, so an idle minute with one error can't read as 100%. Idle → guard drops the series → no-data → OK."
    }
    c6_staging = {
      title     = "C6 API 5xx error ratio (staging)"
      expr      = "(sum(rate(http_requests_total{status=~\"5..\",env=\"staging\"}[5m])) / sum(rate(http_requests_total{env=\"staging\"}[5m]))) and (sum(rate(http_requests_total{env=\"staging\"}[5m])) > 0.05)"
      window_s  = 300
      op        = "gt"
      threshold = 0.05
      for       = "5m"
      severity  = "warning"
      env       = "staging"
      summary   = "Staging twin of C6 (§6) — notifies, never pages. Routed to the staging contact point by the env=staging branch of the notification policy."
    }
    c7 = {
      title     = "C7 Router fleet liveness: total outage"
      expr      = "agent_connected_routers{env=\"prod\"}"
      window_s  = 600
      op        = "lt"
      threshold = 1
      for       = "10m"
      severity  = "critical"
      env       = "prod"
      summary   = "agent_connected_routers{env=\"prod\"} == 0 for 10m (expressed as lt 1 over the integer gauge — Grafana evaluators have no eq). Server-computed from last_seen_at over a 10m window (DB-backed, trustworthy today — unlike the router-pushed family, §2 caveat). At household scale 0 means the single gateway agent stopped reporting entirely: no policy polls, no usage, no events. `for: 10m` aligns with the gauge's own window so it fires once the window has genuinely emptied, not on one missed poll."
    }
  }
}

# One group holds C1–C7 (prod, critical) plus the C4/C6 staging twins (warning).
# Group membership is organizational only; the notification policy in
# alerting.tf routes each rule by its severity/env labels.
resource "grafana_rule_group" "critical" {
  name             = "wifihaven-critical"
  folder_uid       = grafana_folder.alerts.uid
  interval_seconds = 60

  dynamic "rule" {
    for_each = local.critical_rules
    content {
      name      = rule.value.title
      condition = "C"
      for       = rule.value.for
      is_paused = false

      # For these expressions, no-data means healthy/idle (an absent counter, an
      # idle ratio's empty denominator, C5's present series making absent() yield
      # nothing) — it must resolve OK, not fire. Genuine eval errors surface as
      # Error in Grafana (visible) without notifying.
      no_data_state  = "OK"
      exec_err_state = "Error"

      labels = {
        severity = rule.value.severity
        env      = rule.value.env
      }

      annotations = {
        summary = rule.value.summary
      }

      # Node A: instant Prometheus query.
      data {
        ref_id = "A"
        relative_time_range {
          from = rule.value.window_s
          to   = 0
        }
        datasource_uid = var.prometheus_datasource_uid
        model = jsonencode({
          refId         = "A"
          datasource    = { type = "prometheus", uid = var.prometheus_datasource_uid }
          editorMode    = "code"
          expr          = rule.value.expr
          instant       = true
          range         = false
          intervalMs    = 1000
          maxDataPoints = 43200
        })
      }

      # Node C: threshold expression over A (the rule condition). Fires when the
      # last value of A is gt/lt the design threshold.
      data {
        ref_id = "C"
        relative_time_range {
          from = rule.value.window_s
          to   = 0
        }
        datasource_uid = "__expr__"
        model = jsonencode({
          refId      = "C"
          datasource = { type = "__expr__", uid = "__expr__" }
          type       = "threshold"
          expression = "A"
          conditions = [{
            type      = "query"
            evaluator = { type = rule.value.op, params = [rule.value.threshold] }
            operator  = { type = "and" }
            query     = { params = ["C"] }
            reducer   = { type = "last", params = [] }
          }]
        })
      }
    }
  }
}
