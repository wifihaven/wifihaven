# Warning (notify, look-today) alert rules — W1–W15
# (W1–W5: #1405, parent #1381. W6–W7: #2416. W8: #2488. W9: #2553. W10: #2646.
#  W11–W12: #2477. W13: #2517. W14: #2646 follow-up, W10's absence arm.
#  W15: #2736, the ws-cutover safety net — read its block before touching it.)
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
# W14 (#2646 follow-up) is W10's ABSENCE ARM and the two must be read together.
# W10 keys off the PRESENCE of an `agent_version` series per router, so it is
# structurally blind to a router that reports nothing at all — the laggard just
# vanishes from both sides of its comparison and the rule goes quiet. W14 covers
# exactly that door: it compares how many routers are REPORTING against how many
# are CONNECTED. Do not fold them into one rule; they need different reference
# signals and different `for` durations.
#
# All fifteen carry severity=warning + env=prod labels, which the notification
# policy in alerting.tf routes to the wifihaven-warning (email) contact point.
# None of these are ratio queries, so unlike the critical set (§7.1) they need
# no zero-traffic guard — a counter that never increments is simply absent
# (no_data_state = OK), which must not fire. W12 (#2477) and W14 are the TWO
# deliberate inversions of that reading: both are liveness rules, so for them
# absence is the FAILURE, and each therefore turns absence into a value inside
# its own expression (`absent(...)` for W12, `or vector(0)` + `< bool` for W14)
# rather than relying on a no_data verdict the group template does not — and for
# W10's sake must not — provide.
#
# W15 (#2736) is a liveness rule too, but it deliberately does NOT make that
# inversion, and the difference is worth being exact about. Its signal
# (`ws_health_age_seconds`) is per-router and agent-pushed, so absence means the
# whole agent stopped — a strictly larger failure than the one W15 detects, and
# one no `or vector(0)` could attribute to a router id anyway. So absence
# correctly lands in no_data → OK there, and the residual is written down in
# W15's own block rather than papered over.
#
# Threshold model: each rule is a two-node Grafana managed condition — an
# instant Prometheus query (ref A) feeding a threshold expression (ref C, the
# condition). The `gt` evaluator parameter is the design's threshold; `for`
# and the rate/increase window come straight from §7.2.

locals {
  # Keyed w1..w15 (stable resource addressing). `window_s` bounds the data fetch
  # and must cover the rate/increase window in `expr` (for W10, its
  # `last_over_time` lookback; W14 and W15 have no range selector at all and take
  # the file minimum). `paused` ships W5 off.
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
    # (router_metrics_batches_total success ratio) is a ratio, and a TOTAL stop leaves
    # nothing to divide — empty or NaN, either way it lands in no-data → OK; it catches a
    # degraded ingest, not a silent one. Which leaves
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
    # W11/W12 (#2477) — the dispatch WATCHDOG, the pair that makes "a customer wrote to
    # support and got nothing back" an alertable state rather than something noticed when
    # they complain again. Read the two together: W11 is the failure, W12 is the proof that
    # W11 could have fired at all.
    #
    # W11 — a cloud-agent session that accepted the trigger and DIED. The threshold is not
    # a tuned number: `outcome="no_callback"` is emitted by DispatchTracker.sweep only past
    # the agent-token TTL (`support.agentTokenTtlMinutes`, 24h by default via
    # AgentTokenTtl.DefaultMinutes), which is the first instant at which silence is
    # unambiguous. BEFORE it, a claude-code-cloud run suspended on subscription usage limits
    # can still resume and answer — #2473 observed a resumed run posting 2.5h after mint, and
    # AgentTokenTtl records that an evening pause resumes the next morning. AFTER it that
    # resumed run's callback 401s, so the answer can never reach the customer. So every
    # sample is a customer who got NOTHING, gt=0 is right, and the summary can safely tell
    # the operator to reply by hand: past the TTL a late agent reply cannot land, so a hand
    # reply cannot produce the duplicate that made #2472 decline auto-retry.
    # `for = 15m` matches W6-W8 and only debounces a scrape blip — the underlying condition
    # already waited 24h.
    # SUPPORT ONLY — because #2517 gave press its own rule (W13) rather than widening this
    # one. Two rules, not one `{outcome="no_callback"}` sum across both series, because the
    # ACTION differs: a dead support dispatch is recovered by replying in the Plain thread,
    # a dead press one by replying from the #2296 correspondence log at /press, and an alert
    # whose summary cannot name the recovery is an alert someone has to think about at 2am.
    # The two series are separate for the same reason (#2438).
    w11 = {
      title    = "W11 A support customer got NO answer (dispatch died)"
      expr     = "sum(increase(support_dispatch_total{env=\"prod\",outcome=\"no_callback\"}[1h]))"
      window_s = 3600
      gt       = 0
      for      = "15m"
      paused   = false
      summary  = "A cloud-agent session accepted the support trigger and then died — no /api/support/agent/{reply,escalate,request-consent} call ever arrived within the agent-token TTL (24h). THE CUSTOMER GOT NOTHING, and nothing retries: auto-retry was declined in #2472 (a second billed session plus a duplicate-reply risk the #2403 loop guard cannot suppress). ACTION FOR A HUMAN: find the thread in the API log line `support dispatch NEVER CALLED BACK`, which names the thread, household and transport, then read it in Plain and reply by hand. That is safe at this threshold and only at this threshold — the dead session's token is expired, so it cannot come back and answer on top of you. Cross-check the Support dashboard's unreplied-dispatch panel for whether more are queued behind it."
    }
    # W12 — the DETECTOR-LIVENESS rule, and the reason this pair is two rules rather than
    # one. `agent_dispatch_unreplied` is a gauge: a gauge keeps exporting its last written
    # value for as long as the process lives, so a sweep fiber that died would publish a
    # stale, reassuring 0 forever and W11's counter would simply stop moving. Absence and
    # health would again be the same picture — the #2546 shape, where #2469's prompt-drift
    # detector has never emitted a sample in ANY environment and its silence has read as
    # health since the day it shipped. This rule exists so #2477 does not become the third.
    #
    # THE EXPRESSION HAS TWO ARMS, because two different failures both mean "nothing is
    # looking" and neither is caught by the group's no_data_state = OK:
    #   1. `min by (channel)` of the increase `== bool 0` — the sweep counter is still being
    #      SCRAPED but has stopped advancing (a dead fiber inside a live process). `bool` is
    #      load-bearing: a bare `== 0` returns the value 0, which gt=0 would read as healthy;
    #      `== bool` turns the comparison itself into the 1 that fires. `min` and not `sum`:
    #      the API runs at numInstances: 1 today (render.yaml), where the two are identical,
    #      but under a scale-out one live instance's increases would carry a `sum` above zero
    #      and hide a dead fiber on its sibling — the exact silence this rule exists to catch.
    #      Grouped `by (channel)` so each responder gets its own alert instance and a stalled
    #      press sweep cannot be masked by a healthy support one.
    #   2. `absent(...)` — the series is gone entirely (the process is down, the responder
    #      was never enabled, or the metric was renamed/dropped in a refactor). no_data_state
    #      is OK for the whole group and must stay that way for W10, so absence has to be
    #      turned into a VALUE here rather than into a no-data verdict.
    # The absent arms name each channel explicitly: they assert which channels are EXPECTED
    # to be sweeping, which is a claim only the code can make and Prometheus cannot infer
    # from an empty result. #2517 added the `channel="press"` arm when it wired the
    # press-channel DispatchTracker — press now forks its own sweep (Main.scala, gated on
    # press.responderEnabled) and publishes its own {channel="press"} series, so a missing
    # press watchdog is a real, alertable state rather than an empty query.
    #
    # Both arms are `absent(...)`, ORed, and not a single `absent(...{channel=~"support|press"})`:
    # the regex form goes quiet the moment EITHER channel reports, which is exactly the
    # masking this rule exists to prevent.
    # `for = 30m` is 30 sweep intervals (DispatchTracker.SweepInterval = 60s) — long enough
    # that a deploy, a restart or a scrape gap cannot fire it, short enough that a dead
    # watchdog is caught the same hour rather than the next time someone opens the dashboard.
    #
    # EXPECT ONE FIRING AT ROLLOUT, and do not "fix" it. master-grafana.yml and
    # master-api-ui.yml are independent pipelines, so this rule applies before the API build
    # that first emits `agent_dispatch_sweeps_total` reaches prod. Until it does, the absent
    # arm is TRUE — correctly: nothing is sweeping yet. Verified against the live stack while
    # authoring (2026-08-09): the expression parses and returns 1 for
    # {channel="support",env="prod"}, which is both arms doing their job. It resolves on its
    # own once the API deploy lands, with no rule change. `for = 30m` keeps the gap quiet
    # unless the API deploy is genuinely stuck.
    # W13 (#2517) — the press twin of W11, and a separate rule rather than a widened one
    # because the RECOVERY differs and the summary has to be able to state it. Same
    # threshold logic and the same reason it is not a tuned number:
    # `press_dispatch_total{outcome="no_callback"}` is emitted by DispatchTracker.sweep only
    # past the press agent-token TTL (`press.agentTokenTtlMinutes`, 24h by default via the
    # shared AgentTokenTtl.DefaultMinutes), the first instant at which silence is
    # unambiguous — before it a usage-limit-suspended claude-code-cloud run can still resume
    # and answer (#2473), after it that resumed run's callback 401s.
    #
    # One press-specific note the support rule has no equivalent for: since #2517 this bucket
    # also holds a session that DID call back and was refused by the #2437 escalation cap
    # (3/hour/sender), which deliberately does not close the dispatch because nothing reached
    # a human either way. Check `press_agent_action_total{op="escalate",outcome="rate_limited"}`
    # before concluding the session died — the fix for that case is to answer the escalation,
    # not to hand-reply as though the agent never ran.
    w13 = {
      title    = "W13 A journalist got NO answer (press dispatch died)"
      expr     = "sum(increase(press_dispatch_total{env=\"prod\",outcome=\"no_callback\"}[1h]))"
      window_s = 3600
      gt       = 0
      for      = "15m"
      paused   = false
      summary  = "A cloud-agent session accepted the PRESS trigger and then never delivered — no /api/press/agent/{reply,escalate} call landed within the agent-token TTL (24h). A JOURNALIST EMAILED press@ AND GOT NOTHING, and nothing retries: auto-retry was declined for the same reasons as support (#2472) plus a worse duplicate-reply blast radius, since the press reply is emailed autonomously to a member of the public. ACTION FOR A HUMAN: find it in the API log line `press dispatch NEVER CALLED BACK`, which names the correlation key, the reply-to address and the transport, then open /press (the #2296 correspondence log), read the inquiry, and reply by hand. That is safe at this threshold and only at this threshold — the dead session's token is expired, so it cannot come back and answer on top of you. FIRST rule out the refused-escalation case: check press_agent_action_total{op=\"escalate\",outcome=\"rate_limited\"} over the same window, because since #2517 a callback the #2437 cap refused also lands in this bucket, and there the agent is alive and the right move is to handle the escalation. During launch coverage (#2233) treat this as time-critical: a journalist on deadline does not email twice."
    }
    w12 = {
      title    = "W12 The dispatch watchdog stopped reporting"
      expr     = "(min by (channel) (increase(agent_dispatch_sweeps_total{env=\"prod\"}[15m])) == bool 0) or absent(agent_dispatch_sweeps_total{env=\"prod\",channel=\"support\"}) or absent(agent_dispatch_sweeps_total{env=\"prod\",channel=\"press\"})"
      window_s = 900
      gt       = 0
      for      = "30m"
      paused   = false
      summary  = "The #2477 dispatch watchdog has stopped ticking, so NOTHING is watching for a customer left unanswered by a dead cloud-agent session — and because the unreplied count is a gauge, its dashboard panel is still showing you the last value it ever wrote, which is probably a reassuring 0. Treat W11 as unable to fire until this clears. Rule out the benign causes first: the support responder is flag-off in this environment (Main.scala forks the sweep only when support.responderEnabled is true), or the API is down (C5 would also be firing). Otherwise the sweep fiber died inside a live process — grep the API log for a failure in the DispatchTracker loop and redeploy. This rule covers BOTH responders since #2517 — the alert instance's channel label says which one stopped, and each channel's dashboard has its own heartbeat panel. A press instance firing while support is healthy usually means press.responderEnabled is false in this environment, which is a legitimate state to be in but NOT one you should discover from an alert: check render.yaml before hunting a dead fiber."
    }
    # W14 (#2646 follow-up) — W10's ABSENCE ARM. A router that is CONNECTED to us and
    # pushing nothing. Read this with W10's block above; the two cover opposite failure
    # doors and neither subsumes the other.
    #
    # THE GAP W10 CANNOT SEE. W10 compares a router's remembered version history against
    # the fleet's live version set, so every term on both sides is derived from
    # `agent_version` — a series only present for a router that is PUSHING. A router that
    # stops pushing does not become a laggard in W10's eyes; it disappears from the
    # comparison entirely and W10 goes quiet. That is not hypothetical. On 2026-08-15,
    # prod held `router_ws_connections_active = 2` (both routers with a live socket, and
    # `router ws: connected router=f04dd490-…` in Loki at 18:03:33Z) while
    # `agent_version{env="prod"}` had exactly ONE series (3498967e, 0.3.29). The router
    # W10 was written to protect was invisible to it.
    #
    # CHOOSING THE REFERENCE SIGNAL — this is the whole design, and it is not obvious.
    # Prometheus cannot alert on the absence of a series it has never seen: `absent()`
    # needs a nameable label set, and router ids are not knowable in a static rule. So the
    # rule needs some OTHER series that enumerates the routers which SHOULD be reporting.
    # Three candidates were measured against 14 days of real prod data:
    #   - `agent_connected_routers` — REJECTED, it shares the blind spot. It counts
    #     routers whose `routers.last_seen_at` is inside a 10-minute window
    #     (RouterPresenceMetrics.DefaultWindow, api/src/metrics/Metrics.scala), and
    #     `last_seen_at` is written by the snapshot poll, usage/event ingest and the ws
    #     heartbeat — the same agent-liveness paths that die together with the metrics
    #     push. It read 1, exactly equal to the reporting count, at the moment the failure
    #     was live: it CANNOT distinguish the broken state from the healthy one. It is
    #     also the noisiest of the three — over 14d it flapped between 1 and 2 repeatedly
    #     while both routers were reporting normally.
    #   - A new server-side ENROLLED-router gauge off the `routers` table — REJECTED, and
    #     this is the close call. It would be authoritative about who should report, and
    #     it is unlabelled so it costs no cardinality. But an enrollment row outlives the
    #     hardware: a decommissioned-but-undeleted router, or a household whose box is
    #     simply unplugged for a week, would hold this rule firing forever with no action
    #     available. An alert that cannot be resolved by fixing something is an alert the
    #     operator learns to close. It also fails the "express it with a signal that
    #     already exists" bar — a new metric is warranted when nothing else can carry the
    #     meaning, and here something can.
    #   - `router_ws_connections_active` — CHOSEN. A router holding an open websocket is a
    #     router we have direct, live evidence is up and talking to us; if it is up and
    #     talking and still pushing no metrics, that is precisely the failure. It
    #     self-clears with no bookkeeping: a decommissioned or unplugged router drops its
    #     socket and leaves the reference count on its own, which is the exact property
    #     the enrolled gauge lacks. Replayed over the full retained window
    #     (2026-08-01T19:01Z → 08-15T18:56Z, 4032 samples at 300s — note both integers
    #     below are phase-sensitive at this step, since a 30s dip lands on a 300s grid
    #     point only about a tenth of the time): 3966 at 2 and 66 at 1,
    #     the latter in just two stretches — a 5.3h run at the very start of retention
    #     (08-01 19:01 → 08-02 00:21, which reads as the second router joining rather than
    #     a flap) and one single sample on 08-07 14:16. Both LOWER the reference, so both
    #     fail safe. Three transitions across the fortnight against agent_connected_routers'
    #     48 over the same window (same phase caveat — re-query at a different step phase
    #     and the 48 moves; the order-of-magnitude gap does not): the stablest of the three
    #     by an order of magnitude.
    #
    # WHAT IT DEPENDS ON, stated plainly because it is the fragile part. The gauge is
    # documented as a count of CHANNELS, not routers; it is only a router count because
    # RouterWsRegistry.register SUPERSEDES — a reconnect evicts and shuts down the channel
    # already held for that id (#2561), so a router holds at most one. If that invariant
    # is ever relaxed the reference count inflates and this rule false-fires. Anyone
    # changing the registry's channel-per-router bound must revisit this rule.
    # Second dependency: a router on the REST transport holds no channel at all, so it
    # contributes to the reporting side and not the reference side. That direction fails
    # SAFE (the comparison cannot go true from it) but it does mean a REST-only router is
    # not covered here. ws is the fleet default since #2608, which is what makes this
    # acceptable rather than a hole.
    #
    # WHY COUNTS AND NOT IDENTITIES. The rule fires without naming the silent router. That
    # is deliberate: naming it would need a per-router server-derived series, which
    # docs/process/instrumentation.md forbids as an unbounded label dimension.
    # (`agent_version`'s own `router_id` is the documented exception because it is
    # AGENT-pushed, not server-derived — see W10's cardinality note; that exception does
    # not extend to inventing a new server-side per-router gauge.) "One router is silent"
    # is enough to act on, and the operator identifies which one in two clicks from the
    # paired panel on the router-ws-transport dashboard. A count-comparison that fires is
    # worth far more than an identity-precise rule that does not exist.
    #
    # HOW IT READS. `count by (router_id)` collapses a router's version label so an
    # in-flight upgrade cannot double-count it; the outer `count` is then the number of
    # routers with a live `agent_version`. `or vector(0)` is load-bearing and NOT
    # defensive padding: `count()` over an empty vector returns EMPTY, not 0, so without
    # it the total-silence case (every router stops) produces no sample and reads as
    # healthy — the #2546 shape, and the same hole #2654 is filed for. With it, 0 < 2
    # fires. `< bool` rather than a bare `<` for the same reason: a bare comparison
    # returns the LEFT value, which is 0 in exactly that total-silence case, and gt = 0
    # would then filter out the one sample that matters most. `bool` yields a clean 1/0
    # and gt = 0 is a true boolean test (the same `== bool` reasoning as W12).
    #
    # `max` OVER THE REFERENCE GAUGE IS A NO-OP TODAY, and it is worth being exact about
    # that rather than dressing it up as scale-out safety. deploy/alloy/config.alloy:15-25
    # scrapes ONE target, `wifihaven-api-prod:8080`, with `instance` hard-coded to the
    # literal "wifihaven-api-prod" — so there is exactly one series and max == sum ==
    # the single sample. `max` is chosen only as the conservative aggregator over a gauge
    # documented as channels; it buys nothing else.
    # DO NOT READ IT AS MAKING W14 SCALE-OUT-CORRECT. Raising numInstances (render.yaml,
    # 1 today) does NOT produce per-instance series: Render's internal address
    # round-robins behind that single fixed-`instance` scrape target, so each 30s scrape
    # returns whichever instance answered, and BOTH operands are then sampled from that
    # one arbitrary instance. No choice of aggregator fixes that.
    # WHICH WAY the comparison then breaks is deliberately NOT predicted here. Earlier
    # drafts of this comment did predict a direction and could not support it from the
    # scrape config; it is not determinable without actually running a two-instance
    # deploy. What IS certain is that
    # the reference stops meaning "the fleet's connected routers", which is the property
    # the rule rests on. So: rework the scrape topology in config.alloy (per-instance
    # targets, or a server-side aggregate) BEFORE raising numInstances, and re-derive this
    # rule against whatever that produces rather than reasoning about it in advance.
    # If the reference gauge itself is absent (the API is down), the comparison is empty
    # and the rule correctly lands in no_data → OK — an API outage is C-tier, not this.
    #
    # WHY for = 6h — CALIBRATED AGAINST 14 DAYS OF PROD, not picked. The expression was
    # replayed over the full retained window at 5-minute resolution (4032 samples). It went
    # true in FOUR runs: one single sample on 08-08 00:56, 0.83h on 08-10, 17.25h from
    # 08-14 16:36 to 08-15 09:51, and 3.67h from 08-15 15:06 to 18:46. The single sample is
    # an API restart — the agent-pushed gauges repopulate only on the next push,
    # `metrics_report_interval` 60s (openwrt/files/etc/config/wifihaven) — and it is present
    # in the SHIPPING expression, not an artifact of an earlier draft. Neither it, the 0.83h
    # run, nor the 3.67h one reaches 6h, which corroborates the threshold rather than
    # qualifying it. 6h clears
    # the largest benign run by 7.2x and the metrics push interval by 360x, so no restart,
    # reboot, agent upgrade or scrape gap can reach it; it would have fired ONCE in that
    # fortnight, on the 17.25h event, which is the genuine failure. Shorter would page on
    # the 0.83h dip. Longer (W10's 24h) would have missed the 17.25h outage entirely, and
    # that is the difference in condition: W10 detects "stopped updating forever", which
    # is a days-scale fact, while this detects "stopped talking", where six hours of
    # silence from a box that is holding a socket open is already anomalous.
    #
    # QUERY COST — unlike W10 this has no range selector at all: two instant gauge reads
    # per evaluation, so `window_s` takes the file minimum and the #2650 recording-rule
    # concern does not apply here.
    w14 = {
      title    = "W14 A connected router has stopped reporting metrics"
      expr     = "((count(count by (router_id) (agent_version{env=\"prod\"})) or vector(0)) < bool max(router_ws_connections_active{env=\"prod\"}))"
      window_s = 300
      # gt = 0 against a `< bool` comparison: the expression is 1 when fewer routers are
      # reporting than are connected and 0 otherwise, so this is a boolean test and there
      # is no knob to tune here — tune `for`.
      gt      = 0
      for     = "6h"
      paused  = false
      summary = "Fewer routers are pushing metrics than are holding a live websocket to us — at least one router is CONNECTED and silent, and has been for 6h. This is the door W10 cannot watch: W10 keys off the presence of an agent_version series, so a router that reports nothing vanishes from its comparison instead of being flagged by it, and the stuck-agent detector is silently off for that box. Observed live on prod 2026-08-15 (2 connected, 1 reporting). WHICH ROUTER: the rule cannot name it (a per-router server-derived label is out of bounds under the cardinality firewall in docs/process/instrumentation.md — see W10's cardinality note for why agent_version's own router_id is the documented exception) — open the router-ws-transport dashboard's \"Routers connected vs reporting metrics\" panel to confirm the gap, then the router-fleet dashboard's \"Agent versions across the fleet\" panel, which is the one that carries router_id (the \"Fleet agent-version distribution\" panel next to it counts by version only and CANNOT identify a router); the silent box is the enrolled router missing from that list. THEN: the box is up (it is holding a socket), so this is the metrics push specifically, not the agent. Check POST /api/router/metrics in the API log for that router, and on the box check the agent's metrics reporter and `logread | grep wifihaven`. If instead ALL routers went silent at once (the panel shows reporting at 0), suspect the ingest route or the metrics pipeline rather than any single router."
    }
    # W15 (#2736) — THE ws CUTOVER'S SAFETY NET. A router whose websocket has
    # gone stale or dropped, while the box itself is still alive and talking to
    # us over HTTP.
    #
    # WHY THIS RULE HAD TO EXIST BEFORE #2736 COULD SHIP. Until #2736 the agent
    # carried its own recovery: when the ws-health sentinel aged past
    # `ws.fallback_after` the HTTP snapshot poll woke back up and the router kept
    # receiving policy and reporting usage/events over REST. #2736 removes that
    # poll, so ws is the ONLY policy/telemetry transport. A router that loses the
    # socket now keeps ENFORCING its last on-disk snapshot (enforcement degrades
    # rather than stops) but receives no policy and reports no usage — and it
    # does so silently. Deleting a fallback is only acceptable if the state the
    # fallback used to absorb is loudly alerted instead; this is that alert.
    #
    # NOTHING ELSE COVERED IT — checked against the deployed rules, not just this
    # file, on 2026-08-23:
    #   - C7 keys on `agent_connected_routers < 1`. That is the WHOLE fleet going
    #     dark; one router of two losing ws leaves it at 1 and C7 stays silent.
    #   - W14 uses `router_ws_connections_active` as its REFERENCE (right-hand)
    #     operand. A router that loses its socket LOWERS that reference, so W14's
    #     comparison stays balanced and it goes quiet — the exact failure-mode
    #     inversion its own comment block documents, and the row already recorded
    #     as an uncovered gap in docs/design/alerting.md §8.
    #   - W10 keys off `agent_version`, a never-cleared info gauge; per #2646 it
    #     is not a health signal at all (both the 0.3.30 and 0.3.31 series report
    #     1 at the same time), and it says nothing about the socket.
    #
    # WHY `ws_health_age_seconds` IS THE RIGHT SIGNAL, and this is the crux: it
    # rides a transport that SURVIVES the failure it reports. #2736 removes the
    # REST snapshot poll and the REST usage/events fall-through, but the metrics
    # push deliberately stays on HTTP (openwrt/files/usr/lib/lua/wifihaven/
    # ws_outbound.lua tees only `usage` and `events`), precisely so the ws_*
    # observability series still reach the server while the socket is down. A
    # signal carried over the broken link would be the #2546 shape — silence
    # reading as health. This one is not.
    # It is also the ONE value the agent actually acts on: the same sentinel age
    # that #2731 found sitting 80 minutes stale under a live, heartbeating socket
    # while every other series said the link was fine.
    #
    # IT CAN NAME THE ROUTER, unlike W14. `router_id` here is AGENT-pushed, which
    # is the documented exception to the cardinality firewall in
    # docs/process/instrumentation.md (same exception W10 relies on) — not a
    # server-derived per-router label. So the alert instance carries the id and
    # the operator does not have to go cross-reference a dashboard panel.
    #
    # TWO ARMS, SUMMED, because they are the two ways the gate reads unhealthy
    # and a bare `> 300` would miss the worse one:
    #   - `> bool 300` — the socket is up but has gone cold (the #2731 shape).
    #   - `< bool 0`  — the gauge's `-1` sentinel: the health file is ABSENT.
    #     The sidecar clears it on disconnect, so this is the clean "the
    #     websocket is DOWN" case, and it is a negative number that no
    #     greater-than threshold can ever catch.
    # The arms are mutually exclusive, so the sum is 0 or 1 and `gt = 0` is a
    # true boolean test. Both operands carry the same `router_id` label set, so
    # the vector match is one-to-one per router.
    #
    # THRESHOLD 300s. Ten times the sidecar's 30s heartbeat/pong cadence, which
    # is what refreshes the sentinel (#2731). It is also the value
    # `ws.fallback_after` carried, so the number an operator already associates
    # with "the link is gone" does not move under them — even though the config
    # key itself is deleted by #2736 and the agent now derives its own staleness
    # bound from `ws.heartbeat_interval`.
    #
    # for = 30m, AND BE HONEST ABOUT THE CALIBRATION WINDOW. Unlike W14 there is
    # no fortnight of history to replay: `ws_health_age_seconds` only started
    # being emitted with #2731 (PR #2732), so retention holds 2.1 days —
    # 2026-08-21T16:04Z → 08-23T18:34Z, 1213 samples across both routers at 300s
    # step, no gaps. Over that whole window the gauge never left [0, 31] and the
    # shipping expression above evaluated to 0 on every single sample for both
    # routers: zero false positives, but also no benign excursion to calibrate
    # AGAINST. So 30m is chosen from the cadences rather than fitted to data:
    # 300s + 30m means the socket has been provably dead for ~35 minutes, which
    # is 70x the heartbeat and comfortably clears an agent upgrade or a router
    # reboot (both of which stop the metrics push too, landing in no_data → OK
    # rather than firing). Revisit once a few months of the series exist.
    #
    # WHAT IT STILL DOES NOT COVER, stated so absence is not read as health: a
    # router that dies COMPLETELY stops pushing metrics, so this series goes
    # absent and no_data_state = OK keeps the rule silent. That residual is
    # PRE-EXISTING and unchanged by #2736 (a dead agent never polled either), and
    # it is the same open row in §8 that W14 could not close. W15 covers the
    # state #2736 actually creates — box alive, socket gone — which is the one
    # the deleted fallback used to handle.
    #
    # QUERY COST: two instant gauge reads per evaluation, no range selector, so
    # `window_s` takes the file minimum (same as W14; #2650 does not apply).
    w15 = {
      title    = "W15 A router's websocket is stale or down"
      expr     = "((max by (router_id) (ws_health_age_seconds{env=\"prod\"}) > bool 300) + (max by (router_id) (ws_health_age_seconds{env=\"prod\"}) < bool 0))"
      window_s = 300
      # gt = 0 against a sum of two `bool` comparisons: 1 when the link is stale
      # or the sentinel is absent, 0 otherwise. Boolean test — tune `for`, not this.
      gt      = 0
      for     = "30m"
      paused  = false
      summary = "Router {{ $labels.router_id }} has had no live websocket to the API for ~35 minutes (its ws-health sentinel is stale past 300s, or absent entirely). Since #2736 the websocket is the ONLY policy and telemetry transport: this router is still ENFORCING its last on-disk snapshot, so blocking has not stopped, but it is receiving no policy updates and reporting no usage or connection events, and it will stay that way until the socket comes back. This alert exists because #2736 deleted the HTTP snapshot-poll fallback that used to absorb exactly this state. WHY IT CAN BE TRUSTED: the value rides the agent's metrics push, which deliberately stayed on HTTP, so it is still arriving while the socket is down. FIRST: check router_ws_connections_active and the 'ws health sentinel age' panel on the router-ws-transport dashboard to see whether the server ever had the channel. THEN on the box: logread | grep wifihaven-ws (TLS failure, connect timeout, a NAT/CGNAT rebind that killed the socket without a close handshake) and /etc/init.d/wifihaven status to confirm the sidecar is actually running. A -1 age means the sidecar cleared the sentinel on a clean disconnect; a large positive age means it holds the socket but the heartbeat pong is not landing (the #2731 shape). If instead EVERY router fires at once, suspect the API's /api/router/ws endpoint or the ingress, not the fleet."
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
