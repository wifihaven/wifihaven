# Warning (notify, look-today) alert rules — W1–W10
# (W1–W5: #1405, parent #1381. W6–W7: #2416. W8: #2488. W9: #2553. W10: #2646.)
# Implements docs/design/alerting.md §7.2.
#
# Every expression is grounded in a series emitted today (§2 "alert only on
# series that exist"; grep api/src) — except W5, which is shipped DISABLED
# (is_paused = true) because its series is router-pushed and not yet
# trustworthy in prod (§8 + #1382). It is authored now so it activates with a
# one-line flip once the fleet rolls forward.
#
# W6–W8 (#2416, #2488) were a third case while the responders were dark: their
# series and labels existed and were emitted, but the FEATURE behind them was
# flag-off in prod, so they shipped UNPAUSED as coverage that would arm ITSELF
# rather than needing a second flip to remember (unlike W5, which is genuinely
# paused). #2537 flipped `WIFIHAVEN_{SUPPORT,PRESS}_RESPONDER_ENABLED` to "true"
# on wifihaven-api-prod (render.yaml) and all three armed with no rule change —
# the design worked. But the flag is necessary, NOT sufficient, and the two
# halves are at different stages; do not read "flag on" as "exercisable":
#   - W7 (press) is LIVE. The Cloudflare Email Worker already posts to the prod
#     API, so a prod press dispatch failure pages today.
#   - W6/W8 (support) are ARMED BUT NOT YET EXERCISABLE. Both series are produced
#     only downstream of an inbound Plain webhook, and prod's Plain workspace is
#     not wired to the prod API yet (#2543 — the 2026-07-29 verification logged
#     NO support lines at all, where a configured webhook against a dark
#     responder would still have logged outcome=disabled).
#     They begin covering the moment that webhook is configured, again with no
#     rule change — which is the point of shipping them unpaused.
#
# W10 (#2646) is a fourth case and the only rule here that is NOT a counter: it
# is a set comparison over an info gauge, and it alerts on an ABSENCE (a router
# that stopped updating) rather than on an error being counted. Read its own
# comment block below before changing it.
#
# All ten carry severity=warning + env=prod labels, which the notification
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
  # Keyed w1..w10 (stable resource addressing). `window_s` bounds the data fetch
  # and must cover the rate/increase window in `expr` (for W10, its
  # `last_over_time` lookback). `paused` ships W5 off.
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
    # W6/W7 (#2416) — a cloud-agent responder that is PERMANENTLY dead. Both flags are on
    # in prod since #2537, but only W7 (press) is exercisable today; W6 (support) waits on
    # the prod Plain webhook (#2543) — see the header note.
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
    # with no `reason` label to narrow on — so alerting it needs a tuned threshold first, the same
    # problem CLASS as #2443 (which is about the draft series' transient bucket, not this series).
    # Tracked in #2539, not silently unnoticed.
    w8 = {
      title    = "W8 Plain REFUSED to send a support reject"
      expr     = "sum(rate(support_ai_draft_total{env=\"prod\",outcome=\"email_reject_send_failed\"}[15m]))"
      window_s = 900
      gt       = 0
      for      = "15m"
      paused   = false
      summary  = "Plain is REFUSING to send — the unregistered-sender reject was decided correctly and never delivered, so the customer got nothing. LIKELY FIX: the Plain workspace has email sending switched off — Settings → Channels → Email, section 3 \"Sending emails\" left unverified or section 4 \"Enable email\" never clicked. See docs/ops/plain-setup.md §3.1. This NEVER self-heals: every reject (and every AI reply) is dropped until a human completes that provisioning. The API logs each occurrence at ERROR with the same fix named inline, and the preceding `plain replyToThread failed` line carries Plain's own message."
    }
    w9 = {
      title    = "W9 A household was skipped by a rollup tick"
      expr     = "sum(increase(wifihaven_rollup_household_skipped_total{env=\"prod\"}[1h]))"
      window_s = 3600
      gt       = 0
      for      = "15m"
      paused   = false
      summary  = "One tenant's slice of an all-tenant rollup tick was skipped (#2553), so that household's screen time has stopped updating while the run itself still records status=ok — W1 CANNOT catch this. Never self-heals for reason=settings_read, which is almost always a household with no household_settings row (#2386, a provisioning bug): every tick will skip it again. Find the household id in the ERROR log (`... tick skipped household N`) — it is deliberately not a metric label. See the rollup-health dashboard."
    }
    # W10 (#2646) — a router that silently STOPPED SELF-UPDATING. The agent keeps
    # running, enforcing, reporting usage and refreshing last_seen_at; it just never
    # installs another package. Nothing today surfaces that, because the failure is an
    # absence. It matters beyond feature drift: the agent is how security fixes reach
    # the fleet (#2078 package signing), so a stuck router never receives one. Found
    # live during #2527's prod validation — router 3498967e sat four days on 0.3.26
    # while f04dd490, same fleet and same release channel, took 0.3.27 within the hour.
    #
    # SHAPE — update staleness, not version skew, and deliberately NOT a pinned target
    # version. A hardcoded "current version" in an alert rule is stale-by-construction:
    # it is exactly the class of forgotten-to-bump failure this rule exists to catch.
    # The other candidate (#2646's option (a), rank routers by version and alert on
    # anything below max) needs a semver ORDERING, which Prometheus cannot do on a
    # label string — it would require emitting a new numeric companion gauge
    # (major*10000+minor*100+patch). Rejected: the signal already exists, and adding a
    # metric to encode an ordering the alert does not actually need is the wrong trade.
    #
    # HOW IT READS — `agent_version` is an info gauge (constant 1 carrying the version
    # as a label), so a router that upgrades leaves BOTH series in the lookback window:
    # the version it left and the version it took. That history is the whole trick.
    #   inner  = the set of versions this router reported at any point in the last 30d,
    #            intersected (`and on (version)`) with the versions CURRENTLY live
    #            anywhere in the fleet.
    #   right  = how many distinct versions are currently live fleet-wide.
    # Fire when a router's intersection is SMALLER than the fleet's current version set
    # — i.e. some version a peer is running right now is one this router has never run.
    # A router that took the upgrade has both in its history and does not fire; a router
    # that missed it has only the old one and does. That is also what stands in for a
    # version ordering: a rollout leader is not flagged, because it ran the old version
    # earlier in the window and so still intersects it. Note precisely what that rests
    # on — the leader's REMEMBERED history, not any property of the version numbers. A
    # router with no pre-split history (newly enrolled, or re-enrolled onto a fresh
    # router_id) has only its current version and WILL be flagged while a laggard is
    # outstanding; see known limit (3). The rule cannot order versions, and does not
    # pretend to.
    # The intersection (rather than a plain count-vs-count) matters: a laggard that
    # upgraded once early in the window and then froze has TWO versions in history, and
    # a bare cardinality comparison would miss it.
    #
    # WHY for = 24h — updates run from an hourly jittered cron
    # (`0 * * * * /usr/sbin/wifihaven-update --jitter`, openwrt/Makefile:175) whose jitter
    # is capped at WIFIHAVEN_UPDATE_JITTER_MAX, default 600s
    # (openwrt/files/usr/sbin/wifihaven-update:63), so worst-case rollout skew is ~70min.
    # A day of grace is an order of magnitude past that and still catches
    # "stopped forever", which is the actual condition. A rule that pages during every
    # release trains the operator to ignore the one signal that matters. Verified against
    # prod: the expression went true ~1h after 0.3.27 shipped (release createdAt
    # 2026-08-05T15:07:17Z) and stayed continuously true, no gaps, for 3.5 days for
    # 3498967e only — until that router finally took 0.3.27 on 2026-08-09, at which point
    # it went empty again. Both edges of the real incident, which is the validation.
    #
    # CARDINALITY — `agent_version` carries `router_id`, which is per-router and so
    # unbounded in principle. This sits OUTSIDE the bounded-label-enum rule in
    # docs/process/instrumentation.md because the label is agent-pushed, not
    # server-derived, and the metric predates this rule (api/src/metrics/Metrics.scala
    # allowlists version/router_id/installation_id). Fine at current fleet size; this
    # note exists so whoever reads it at 500 routers knows it was weighed, not missed.
    #
    # NO-DATA IS THE HEALTHY STATE, and that is why no_data_state = OK below is required
    # rather than merely inherited from the group template. A comparison filter returns
    # NOTHING when no router is lagging, so the steady state of a healthy fleet is an
    # empty vector; any other no_data_state would fire continuously. The cost is that
    # W10 also reads healthy if `agent_version` stops being emitted fleet-wide, and that
    # gap is REAL — do not assume C7/C4 cover it. C7 (agent_connected_routers < 1) is
    # computed from routers.last_seen_at, which the metrics-batch path never writes:
    # routerRepo.touch is called from the snapshot poll (RouterRoutes.scala:85), usage/
    # event ingest (RouterIngestService.scala:92,154) and the ws heartbeat
    # (RouterWsRoutes.scala:306), not from RouterMetricsRoutes — so an agent that keeps
    # polling policy while its metrics push dies still reads connected. C4
    # (router_metrics_batches_total success ratio) is a ratio, so a TOTAL stop is 0/0 →
    # NaN → no-data → OK; it catches a degraded ingest, not a silent one. Which leaves
    # "metrics stop while the agent looks alive" uncovered, with W10 itself silently off
    # — the #2546 shape. Fixing it wants a separate absent(agent_version) liveness rule,
    # tracked in #2654 rather than folded in here.
    #
    # QUERY COST — this is the one dimension that does not scale, and it is a DIFFERENT
    # axis from the cardinality note above. The scrape interval is 30s (deploy/alloy/
    # config.alloy), so a [30d] lookback fetches ~86,400 samples per series per
    # evaluation, on a 60s group interval; every other rule in this file uses window_s
    # between 300 and 3600. Fine at today's fleet, ~43M samples/eval at 500 routers, and
    # a query rejected on a sample limit lands in exec_err_state = "Error" — visible but
    # NOT notifying, i.e. the detector goes dark exactly when it is most needed. The
    # lookback is not free to shorten (it is what keeps the leader's memory of the
    # superseded version alive), so the fix is a recording rule, tracked in #2650.
    #
    # KNOWN LIMITS, all three accepted:
    #   - Fleet-relative, so it CANNOT detect a fleet where every router is stuck on the
    #     same old version. Closing that needs a comparison against the published
    #     release (a GitHub-release-derived series), not a peer comparison.
    #   - If a split persists past the 30d lookback, the healthy router's memory of the
    #     old version ages out and it flags too — noisy, but only after a month of an
    #     unfixed W10, and it clears the moment the laggard catches up.
    #   - A router with NO PRE-SPLIT HISTORY flags while a laggard is outstanding: a
    #     newly enrolled household, or a box re-enrolled onto a fresh router_id, has only
    #     its current version in the window, so it cannot intersect the laggard's. It is
    #     structurally in the same position as a router whose lookback aged out, just
    #     immediately rather than after 30d. Accepted because it is strictly downstream
    #     of an already-firing, already-unhandled W10 — the laggard has to have been
    #     lagging 24h+ for the new router to have anything to miss — and it clears when
    #     that laggard is fixed. It does mean the FIRST notification after onboarding a
    #     household during an unresolved lag names the newest box on the fleet, so read
    #     the router_id against the version-distribution panel before running the
    #     runbook in the summary.
    w10 = {
      title    = "W10 Router stuck on an old agent version"
      expr     = "count by (router_id) (count by (router_id, version) (last_over_time(agent_version{env=\"prod\"}[30d])) and on (version) count by (version) (agent_version{env=\"prod\"})) < on() group_left() count(count by (version) (agent_version{env=\"prod\"}))"
      window_s = 2592000
      # gt = 0 is a PRESENCE test, not a tunable threshold: the comparison filter returns
      # the left-hand value, which is a count of intersected versions and is therefore >= 1
      # whenever the series exists at all (a router always intersects its own live version).
      # The rule fires on the series existing. There is no knob here to tune — tune `for`.
      gt      = 0
      for     = "24h"
      paused  = false
      summary = "A router has not taken an agent update that the rest of the fleet already has, for a full day — it is running a version no peer is on and has never reported the version they are on. The agent otherwise looks healthy (last_seen_at fresh, policy applied, usage reported), which is why nothing else catches this. It will never receive a security fix until someone intervenes. FIRST, confirm which router is actually the odd one out on the router-fleet dashboard's version-distribution panel: a household enrolled while another router was already lagging has no pre-split history and gets named here too (known limit 3 on the rule) — the box to fix is the one on the OLDER version. Then on that box, run `wifihaven-update` by hand and read the failure. A common cause is a missing /etc/wifihaven/keys/release.pub (signature verification fails closed, #2078) after a pre-#2559 uninstall/reinstall (#2554)."
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
