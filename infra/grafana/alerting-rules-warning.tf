# Warning (notify, look-today) alert rules — W1–W8
# (W1–W5: #1405, parent #1381. W6–W7: #2416. W8: #2488.) Implements
# docs/design/alerting.md §7.2.
#
# Every expression is grounded in a series emitted today (§2 "alert only on
# series that exist"; grep api/src) — except W5, which is shipped DISABLED
# (is_paused = true) because its series is router-pushed and not yet
# trustworthy in prod (§8 + #1382). It is authored now so it activates with a
# one-line flip once the fleet rolls forward.
#
# W6–W8 (#2416, #2488) are a third case, distinct from both: their series and
# labels exist and are emitted, but the FEATURE behind them is switched off in
# prod (`WIFIHAVEN_{SUPPORT,PRESS}_RESPONDER_ENABLED: "false"` on
# wifihaven-api-prod, render.yaml) — it is live on staging only. So they are
# INERT in prod today and cannot fire until the prod go-live flips those flags
# (#2335 support / #2337 press). They ship UNPAUSED deliberately (unlike W5):
# there is nothing about the rules themselves to fix, so activating with the
# feature flag — no second flip to forget — is the safer default. Read them as
# coverage that arms itself at go-live, NOT as live prod coverage today.
#
# All eight carry severity=warning + env=prod labels, which the notification
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
  # Keyed w1..w8 (stable resource addressing). `window_s` bounds the data fetch
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
    # W6/W7 (#2416) — a cloud-agent responder that is PERMANENTLY dead. INERT IN PROD
    # TODAY: both responders are flag-off in prod and live on staging only, so these
    # cannot fire until go-live flips them (#2335 / #2337) — see the header note.
    # `reason="config"` is only ever emitted for a non-self-healing 4xx at the Anthropic
    # boundary (a revoked key, a wrong agent-or-routine id, a stale anthropic-beta
    # header), none of which recover without a human — so unlike the transient bucket,
    # ANY sustained rate is actionable, and the threshold is the same gt=0 the other
    # never-should-happen counters use. Deliberately does NOT alert on
    # `reason="transient"`: a 5xx/timeout blip is expected noise. A SUSTAINED transient
    # rate (a long Anthropic outage, or a 429 that is really an exhausted quota rather
    # than a burst ceiling) is a real remaining hole, but it needs a threshold tuned
    # against prod traffic — tracked separately in #2443. Support and press are separate
    # rules because the series are separate (the two audiences alert independently) and
    # the remediation differs.
    w6 = {
      title    = "W6 Support responder permanently dead (config)"
      expr     = "sum(rate(support_ai_draft_total{env=\"prod\",outcome=\"error\",reason=\"config\"}[15m]))"
      window_s = 900
      gt       = 0
      for      = "15m"
      paused   = false
      summary  = "Support cloud-agent dispatch is failing with a 4xx at the Anthropic boundary — a revoked/wrong anthropicApiKey, a wrong claudeAgentId/claudeEnvironmentId/claudeCodeRoutineId, or a stale anthropic-beta header. This NEVER self-heals: customers get no AI reply until a human rotates the key, fixes the id, or bumps the header. The API logs each occurrence at ERROR with the likely fix named inline."
    }
    w7 = {
      title    = "W7 Press responder permanently dead (config)"
      expr     = "sum(rate(press_ai_draft_total{env=\"prod\",outcome=\"error\",reason=\"config\"}[15m]))"
      window_s = 900
      gt       = 0
      for      = "15m"
      paused   = false
      summary  = "Press cloud-agent dispatch is failing with a 4xx at the Anthropic boundary (same causes as W6, press credentials/ids). Journalists get no reply until a human fixes the config; the inbound webhook still returns 200 (fail-open by design), so this counter is the only signal. The API logs each occurrence at ERROR with the fix named inline."
    }
    # W8 (#2488) — the same never-self-heals class as W6, one seam earlier: not the Anthropic
    # boundary but the PLAIN one. `outcome="email_reject_send_failed"` is emitted only when Plain
    # ACCEPTED the reject write and refused to send it
    # (SupportResponder.scala `PlainOutcome.Error` on the unregistered-sender reject →
    # `WebhookOutcome.EmailRejectSendFailed`, whose label is `email_reject_send_failed`) — the
    # 2026-07-26 staging failure, where a workspace with email sending switched off dropped every
    # reject while the panel read healthy under the old shared success label. A deliberate off-state
    # is NOT this: `plain.writeEnabled=false` labels `disabled`, so our own flag can never light this
    # rule. Deliberately does NOT constrain `reason`: `WebhookOutcome.reason` returns `none` for this
    # outcome on purpose (PlainClient collapses every send failure into one causeless
    # `PlainOutcome.Error`), so pinning a second selector here would add nothing and break silently if
    # attribution is added later. Expect a flat zero — #2488's whole point is that the dashboard tile
    # #2485 added is only seen by someone looking at it. Same gt=0 / for=15m shape as W6/W7.
    # HALF-COVERAGE, deliberately: the same refusal also drops the AI reply to a REGISTERED customer,
    # which meters on a different series (`support_agent_action_total{op="reply",outcome="error"}`)
    # with no `reason` label to narrow on — so alerting it needs a tuned threshold first (#2443's
    # problem). Tracked in #2539, not silently unnoticed.
    w8 = {
      title    = "W8 Plain REFUSED to send a support reject"
      expr     = "sum(rate(support_ai_draft_total{env=\"prod\",outcome=\"email_reject_send_failed\"}[15m]))"
      window_s = 900
      gt       = 0
      for      = "15m"
      paused   = false
      summary  = "Plain is REFUSING to send — the unregistered-sender reject was decided correctly and never delivered, so the customer got nothing. LIKELY FIX: the Plain workspace has email sending switched off — Settings → Channels → Email, section 3 \"Sending emails\" left unverified or section 4 \"Enable email\" never clicked. See docs/ops/plain-setup.md §3.1. This NEVER self-heals: every reject (and every AI reply) is dropped until a human completes that provisioning. The API logs each occurrence at ERROR with the same fix named inline, and the preceding `plain replyToThread failed` line carries Plain's own message."
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
