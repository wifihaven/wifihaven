# Warning (notify, look-today) alert rules — W1–W5
# (#1405, parent #1381). Implements docs/design/alerting.md §7.2.
#
# Every expression is grounded in a series emitted today (§2 "alert only on
# series that exist"; grep api/src) — except W5, which is shipped DISABLED
# (is_paused = true) because its series is router-pushed and not yet
# trustworthy in prod (§8 + #1382). It is authored now so it activates with a
# one-line flip once the fleet rolls forward.
#
# All five carry severity=warning + env=prod labels, which the notification
# policy in alerting.tf routes to the wifihaven-warning (email) contact point.
# None of these are ratio queries, so unlike the critical set (§7.1) they need
# no zero-traffic guard — a counter that never increments is simply absent
# (no_data_state = OK), which must not fire.
#
# Threshold model: each rule is a two-node Grafana managed condition — an
# instant Prometheus query (ref A) feeding a threshold expression (ref C, the
# condition). The `gt` evaluator parameter is the design's threshold; `for`
# and the rate/increase window come straight from §7.2.

locals {
  # Keyed w1..w5 (stable resource addressing). `window_s` bounds the data fetch
  # and must cover the rate/increase window in `expr`. `paused` ships W5 off.
  warning_rules = {
    w1 = {
      title    = "W1 Rollup failures"
      expr     = "increase(wifihaven_rollup_runs_total{status=\"error\",env=\"prod\"}[1h])"
      window_s = 3600
      gt       = 0
      for      = "10m"
      paused   = false
      summary  = "A prod rollup run errored in the last hour — analytics tables silently rot. Does not affect live enforcement. Check /api/admin/rollup-status and the rollup-health dashboard."
    }
    w2 = {
      title    = "W2 Cardinality firewall rejections"
      expr     = "sum(rate(metrics_rejected_total{env=\"prod\"}[10m]))"
      window_s = 600
      gt       = 0
      for      = "15m"
      paused   = false
      summary  = "Code is emitting a metric series the MetricGuard allowlist forbids (wrong name or a forbidden label) — a bug in the emitting code, not an attack. Degrades observability, not the service."
    }
    w3 = {
      title    = "W3 Zero-byte traffic rows filtered"
      expr     = "rate(traffic_reports_filtered_zero_bytes_total{env=\"prod\"}[15m])"
      window_s = 900
      gt       = 0
      for      = "30m"
      paused   = false
      summary  = "Rising rate of filtered zero-byte traffic rows — the #858 agent regression (empty rows) may have returned (#864 sentinel). Slow-burning; long for: avoids paging on a blip."
    }
    w4 = {
      title    = "W4 Auth-failure spike"
      expr     = "sum(rate(auth_failures_total{env=\"prod\"}[5m]))"
      window_s = 300
      gt       = 0.5
      for      = "10m"
      paused   = false
      summary  = "Sustained burst of bad_password / bad_router_token (>~30/min) — the household's only brute-force signal. Threshold sits well above a fat-fingered login; tune up if noisy."
    }
    w5 = {
      title    = "W5 Blocklist fetch failures"
      expr     = "sum(rate(blocklist_fetch_failures_total{env=\"prod\"}[15m]))"
      window_s = 900
      gt       = 0
      for      = "30m"
      # DISABLED: blocklist_fetch_failures_total is router-pushed and not yet
      # trustworthy in prod (§8, #1382). Flip to false once the fleet rolls
      # forward and the counter is reliable.
      paused  = true
      summary = "Blocklist fetches are failing on the router (category enforcement degrades). DISABLED until the router-pushed counter is trustworthy in prod (#1382)."
    }
  }
}

resource "grafana_rule_group" "warning" {
  name             = "wifihaven-warning"
  folder_uid       = grafana_folder.alerts.uid
  interval_seconds = 60

  dynamic "rule" {
    for_each = local.warning_rules
    content {
      name      = rule.value.title
      condition = "C"
      for       = rule.value.for
      is_paused = rule.value.paused

      # A never-incremented counter is absent, not zero — absence is healthy,
      # so no-data must resolve OK rather than fire. Genuine eval errors surface
      # as Error in Grafana (visible) without notifying as the alert.
      no_data_state  = "OK"
      exec_err_state = "Error"

      labels = {
        severity = "warning"
        env      = "prod"
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
      # last value of A is greater than the design threshold.
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
            evaluator = { type = "gt", params = [rule.value.gt] }
            operator  = { type = "and" }
            query     = { params = ["C"] }
            reducer   = { type = "last", params = [] }
          }]
        })
      }
    }
  }
}
