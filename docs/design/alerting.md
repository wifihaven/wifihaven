# Alerting strategy for WifiHaven

> Status: **design** (issue [#1381](https://github.com/wifihaven/wifihaven/issues/1381)).
> This is the alerting half explicitly split out of
> [#1368](https://github.com/wifihaven/wifihaven/issues/1368); the dashboard
> half shipped in [#1373](https://github.com/wifihaven/wifihaven/pull/1373).
> No alert rules are created by this PR — it is the plan plus the concrete
> rule definitions to file as implementation sub-issues.

## 1. Why this exists

WifiHaven has **dashboards but no alerting**. `infra/grafana/` provisions only
`grafana_dashboard` resources — there is no `grafana_rule_group`, no
`grafana_contact_point`, no `grafana_notification_policy`. A failure-mode
metric that only feeds an unwatched panel is not observability: it costs
cardinality but never reaches an operator's eyes until an incident.

Two incidents define the bar:

- **2026-05-31 DB-saturation crash loop.** The hourly/daily byte-rollup
  cadence pegged managed-Postgres CPU; the HikariCP pool's
  *threads-awaiting-connection* climbed; the pool exhausted and the API
  crash-looped. The leading indicators (`render_service_cpu_time_seconds`,
  `wifihaven_db_pool_threads_awaiting_connection`) were all read **by hand
  off Render dashboards mid-incident**. This is the
  [#1331](https://github.com/wifihaven/wifihaven/issues/1331) DB-CPU ⇆
  HikariCP-pending correlation.
- **The silent ingest failure ([#1365](https://github.com/wifihaven/wifihaven/issues/1365)).**
  100% of router-metrics ingests failed for a long window
  (`router_metrics_batches_total{status="malformed"}` climbing, zero
  `status="ok"`) and **nobody noticed** until the empty router-fleet
  dashboard was hand-investigated. The dashboard fix (an at-a-glance
  success-ratio stat panel) shipped in
  [#1373](https://github.com/wifihaven/wifihaven/pull/1373); alerting was
  pulled out to be designed here rather than bolted on one metric at a time.

The durable rule this work establishes: **failure-mode metrics ship with an
alert**, the same way "new functionality ships with metrics + a dashboard"
(AGENTS.md) already requires — but only now that there is a coherent strategy
for *where rules live, how they route, and who they reach*.

## 2. Ground rule — alert only on series that exist

Per the AGENTS.md "dashboards match emitted metrics" rule: every alert below
targets a series **actually emitted today** (grepped from `api/src` and
cross-checked against the panels in
[`deploy/grafana/dashboards/`](../../deploy/grafana/dashboards/)). Where a
desired alert needs a metric that is **not yet reliably emitted**, it is
listed in [§8](#8-gaps--metrics-not-yet-emitted) with the instrumentation
issue it blocks on — it does **not** get a rule against a phantom series.

The emitted surface, by producer:

| Producer | Series | Label keys | Has `env`? |
| --- | --- | --- | --- |
| API self (`AppMetrics`) | `http_requests_total`, `http_request_duration_seconds` | `route`,`method`,`status` | yes |
| | `db_query_duration_seconds` | `op` | yes |
| | `auth_failures_total` | `reason` | yes |
| | `agent_connected_routers` | — | yes |
| | `traffic_reports_filtered_zero_bytes_total` | — | yes |
| | `metrics_rejected_total` | `reason` | yes |
| | `router_metrics_batches_total` | `status` | yes |
| | `wifihaven_db_pool_*` (incl. `…threads_awaiting_connection`) | — | yes |
| | `wifihaven_rollup_runs_total` | `rollup_job`,`status` | yes |
| Router-pushed (via `POST /api/router/metrics`, [#1205](https://github.com/wifihaven/wifihaven/issues/1205)) | `dnsmasq_restarts_total`, `policy_apply_*`, `snapshot_poll_*`, `agent_uptime_seconds`, `agent_version`, `dns_queries_total`, `blocklist_fetch_failures_total` | various + `router_id`,`installation_id` | yes |
| Render OTLP stream ([#1244](https://github.com/wifihaven/wifihaven/issues/1244)) | `render_service_cpu_time_seconds`, `render_postgres_connections`, `render_postgres_connection_limit` | `service_name` | **no** — `service_name="wifihaven-pg-prod"` (prod only) |

> **App metrics carry `env` (`prod`/`staging`)** attached by the Alloy scrape.
> **Render infra metrics carry `service_name`, not `env`** — and only the
> prod datastore is streamed, so infra alerts are inherently prod-only
> ([§6](#6-per-env-handling)).

> **Caveat — router-pushed series are not yet trustworthy in prod.** Per
> [`memory`/#1382](https://github.com/wifihaven/wifihaven/issues/1382), pre-#1365
> agents still POST malformed batches, so the router-pushed family
> (`blocklist_fetch_failures_total`, `agent_uptime_seconds`, …) is partial
> until the fleet rolls forward. Fleet-liveness alerting below therefore uses
> **`agent_connected_routers`**, which is **server-computed from
> `last_seen_at` in the DB** (not router-pushed) and is solid today.

## 3. Where the rules live

**Decision: Grafana-managed alert rules as `grafana_rule_group` resources,
declared in `infra/grafana/`, applied by the existing
[`master-grafana.yml`](../../.github/workflows/master-grafana.yml) pipeline.**
This keeps alerting under the same "declarative config over dashboard toggles"
discipline (AGENTS.md) as the dashboards: the repo is the source of truth, the
Grafana UI is not.

### 3.1 Hard prerequisite — remote Terraform state

`infra/grafana` is **stateless by design today**: the CD job runs on an
ephemeral runner with no persisted state, and each `grafana_dashboard` is
upserted by its stable `uid` with `overwrite = true`. That trick works *only*
for dashboards. Alerting resources have no equivalent escape hatch:

- `grafana_folder`, `grafana_contact_point`, `grafana_notification_policy`,
  and `grafana_rule_group` are stateful — a *create* against fresh-every-run
  empty state either **409s** on a fixed identifier or **accumulates
  duplicates** across runs (exactly the reason `main.tf` already refuses to
  manage a `grafana_folder`).
- The notification policy is a **singleton** root tree; there is no "upsert by
  uid" — Terraform must own its prior state to edit it idempotently.

So the first thing that must happen is migrating `infra/grafana` off local
state onto a **remote backend on HCP Terraform**, mirroring what
[#1357](https://github.com/wifihaven/wifihaven/issues/1357) did for
`infra/cloudflare` (org `wifihaven` on HCP Terraform, Local execution mode,
CI applies on merge to main). Once
state persists, the dashboards keep working unchanged and the alerting
resources become idempotent. **This is filed as its own sub-issue and blocks
all the others** ([§9](#9-implementation-sub-issues-to-file)).

The `master-grafana.yml` `paths:` filter (`deploy/grafana/**`,
`infra/grafana/**`, the workflow file) already covers the new `.tf`, so no
pipeline change is needed beyond wiring the backend `init`.

## 4. Routing & contact points

**Reality check (no invented transports):**
there is no PagerDuty, no Slack consumer, no SMS gateway wired to this
project, and WifiHaven is operated by a **single household operator**. So the
strategy does **not** scaffold those transports. The minimal *real* channel is
**email**, which Grafana Cloud can send natively (built-in email sender — no
SMTP secret to provision on the free/Pro stack).

The design still puts a **severity-routed notification policy** in front of
the contact points, so swapping email for a real pager later is a one-resource
edit, not a re-architecture:

```
                       ┌─ notification policy (root) ─┐
  alert fires ───────► │  match: env="staging"  ──────┼──► contact: "staging"   (notify only)
   (carries labels     │  match: severity="critical" ─┼──► contact: "critical"  (page channel)
    severity, env)     │  match: severity="warning"  ─┼──► contact: "warning"   (notify channel)
                       │  default ────────────────────┼──► contact: "warning"
                       └──────────────────────────────┘
```

- Two contact points to start, **both email to the operator**:
  `wifihaven-critical` and `wifihaven-warning`. They are distinct resources
  (not one) precisely so the *critical* one can later be re-pointed at a pager
  integration without touching warning routing or any rule.
- A third, `wifihaven-staging`, also email — staging never pages
  ([§6](#6-per-env-handling)).
- The operator's email address is **not committed**: supply it via a TF
  variable (`operator_email`, fed from `terraform.tfvars` / a CI variable),
  the same handling as `grafana_auth`.
- **Grouping / throttling** on the policy: `group_by = ["alertname","env"]`,
  `group_wait = 30s`, `group_interval = 5m`, `repeat_interval = 4h`. A single
  household operator does not want the same firing alert re-mailed every few
  minutes; 4h repeat is enough to keep an unacknowledged page visible without
  becoming noise.

> **Why not Grafana OnCall / a pager now?** It would be invented transport with
> no consumer. Email is the honest minimal channel; the policy tree is the
> seam that lets a future "Alerting & Paging" issue drop in OnCall/PagerDuty
> behind the `critical` contact point with zero rule churn.

## 5. Severity model

Three levels, attached to every rule as a `severity` label and mapped to
routing by [§4](#4-routing--contact-points):

| Severity | Meaning | Routes to | Operator action |
| --- | --- | --- | --- |
| `critical` | Service is down or degrading toward an outage **now**. | `wifihaven-critical` (page channel) | Drop what you're doing. |
| `warning` | A failure mode is active or trending; not yet user-visible. | `wifihaven-warning` (notify) | Look today. |
| `info` | Worth recording; not actionable on its own. | *(not routed — dashboard annotation only)* | None. |

We do **not** create `info`-routed rules in the first set — `info`-grade
signals stay on dashboards. Everything below is `critical` or `warning`.

## 6. Per-`env` handling

- **App metrics** carry `env`. Every rule expression below is scoped
  `{env="prod"}` for the paging copy.
- **`critical` rules also get a `warning`-severity staging twin** *only where
  cheap and useful* — but to keep noise down, staging is routed to the
  `wifihaven-staging` contact point (notify, never page) by the **first match**
  in the policy tree (`env="staging"` short-circuits before the severity
  matches). In practice: author each rule once with `{env="prod"}` for paging;
  add a parallel `{env="staging"}` rule at `severity=warning` **only** for the
  ingest-failure and 5xx alerts (the two staging actually exercises). The rest
  are prod-only.
- **Render infra metrics** (`render_*`) have no `env` and stream only for the
  prod datastore (`service_name="wifihaven-pg-prod"`). DB-CPU and
  connection-ceiling alerts are therefore **prod-only by construction** — there
  is no staging Postgres in the metrics stream to alert on.

## 7. The first alert set

Concrete rules, grounded in §2's emitted series. Each lists the PromQL, the
`for` duration, the threshold rationale, and where relevant the **zero-traffic
guard** (a ratio over an idle environment must not false-positive — the
`0/0 → NaN, dropped` pattern from #1373's panel).

Conventions: `[Bx]` = condition expression; thresholds reference the **B**
condition over **A** (the query). Grafana managed rules evaluate a query then a
threshold expression; the PromQL given is the query, and the threshold is
stated alongside.

### 7.1 `critical` — page now

**C1. DB CPU saturation** (the #1331 leading indicator, half 1)
- Query: `rate(render_service_cpu_time_seconds{service_name="wifihaven-pg-prod"}[5m])`
- Fire when `> 0.8` **for 5m**.
- Rationale: `render_service_cpu_time_seconds` is cumulative CPU-seconds; its
  per-second rate is **cores consumed**. `> 0.8` ≈ 80% of one core on the
  current single-core managed-PG plan — the regime the 2026-05-31 rollup
  cadence drove it into. `for: 5m` rides out a single rollup burst (a healthy
  hourly rollup spikes CPU briefly) and only fires on a sustained peg.
- **Plan-coupled threshold:** if the PG plan changes core count, revisit this
  number. Render does not stream a CPU-limit series to divide by, so the
  capacity is hardcoded against the known plan and documented here.

**C2. HikariCP pending connections** (the #1331 leading indicator, half 2)
- Query: `wifihaven_db_pool_threads_awaiting_connection{env="prod"}`
- Fire when `> 0` **for 2m**.
- Rationale: a sustained nonzero *threads-awaiting* is the exact precursor of
  the pool-exhaustion crash loop — under healthy load it is 0. `for: 2m`
  rejects momentary contention but catches the build-up well before the pool
  is fully starved.
- C1 + C2 together reconstruct the #1331 correlation as two independent pages
  on one DB-saturation event; either alone is actionable, and firing together
  is the unambiguous "it's 2026-05-31 again" signal.

**C3. Postgres connection ceiling**
- Query: `render_postgres_connections{service_name="wifihaven-pg-prod"} / render_postgres_connection_limit{service_name="wifihaven-pg-prod"}`
- Fire when `> 0.9` **for 5m**.
- Rationale: the managed-PG connection cap is a hard wall; crossing 90% means
  the next traffic bump starts refusing connections. Distinct from C2 (which is
  the *app's* HikariCP view) — this is the *server's* total across all clients.

**C4. Router-metrics ingest failure** (the #1365 / #1368 trigger)
- Query (reuses the #1373 success-ratio panel expression, with a guard):
  - success ratio: `(sum(rate(router_metrics_batches_total{env="prod",status="ok"}[10m])) or vector(0)) / sum(rate(router_metrics_batches_total{env="prod"}[10m]))`
  - **traffic guard:** `sum(rate(router_metrics_batches_total{env="prod"}[10m])) > 0`
- Fire when **ratio < 0.95 AND guard true**, **for 15m**.
- Rationale: this is the literal #1365 condition. The guard is essential — an
  idle env produces `0/0 = NaN`, and without `... > 0` the rule would either
  flap or fire perpetually against no traffic. `for: 15m` matches the 10m rate
  window plus margin so a single bad batch during a deploy doesn't page.
- **Staging twin** at `severity=warning` (`{env="staging"}`) — staging
  exercises this path, but a staging ingest blip should not page.

**C5. API down / no traffic served**
- Query: `absent(http_requests_total{env="prod"})`  *(see note)*
- Fire when present (i.e. the series vanished) **for 2m**.
- Rationale: if the API process is dead or its scrape target is gone, the 5xx
  ratio (C6) can't be computed — the series simply stops. `absent()` catches
  the "metrics went silent" case. **Needs verification at implementation:** if
  the Alloy scrape emits an `up{job="wifihaven-api",env="prod"}` target-health
  series, prefer `up == 0 for 2m` (cleaner, distinguishes "scrape failed" from
  "no requests"). Confirm the scrape job/label before choosing; do not ship a
  rule against an unconfirmed `up` series.

**C6. API 5xx error ratio**
- Query: `sum(rate(http_requests_total{status=~"5..",env="prod"}[5m])) / sum(rate(http_requests_total{env="prod"}[5m]))`
- **traffic guard:** `sum(rate(http_requests_total{env="prod"}[5m])) > 0.05` (≈ a request every 20s; below this the ratio is statistically meaningless at household scale)
- Fire when **ratio > 0.05 AND guard true**, **for 5m**.
- Rationale: 5% sustained 5xx is real user-facing breakage. The guard prevents
  one error during an otherwise-idle minute from reading as "100% error rate."
- **Staging twin** at `severity=warning`.

**C7. Router fleet liveness — total outage**
- Query: `agent_connected_routers{env="prod"}`
- Fire when `== 0` **for 10m**.
- Rationale: this gauge counts routers seen within a 10-minute `last_seen_at`
  window (server-computed, DB-backed — solid today, see §2 caveat). At
  household scale `0` means the (single) gateway agent has stopped reporting
  entirely: no policy polls, no usage, no events — enforcement is flying blind.
  `for: 10m` aligns with the gauge's own window so it fires once the window has
  genuinely emptied, not on a single missed poll.

### 7.2 `warning` — notify, look today

**W1. Rollup failures**
- Query: `increase(wifihaven_rollup_runs_total{status="error",env="prod"}[1h])`
- Fire when `> 0` **for 10m**.
- Rationale: rollups run hourly/daily, so a `rate[5m]` is near-zero noise;
  `increase[1h]` is the right window for an infrequent job. Any error in the
  last hour is worth a look — a failing rollup silently rots the analytics
  tables. Not `critical` because it does not affect live enforcement.

**W2. Cardinality firewall rejections**
- Query: `sum(rate(metrics_rejected_total{env="prod"}[10m]))`
- Fire when `> 0` **for 15m**.
- Rationale: a nonzero reject rate means code is trying to emit a series the
  `MetricGuard` allowlist forbids — a **bug in the emitting code** (wrong name
  or a forbidden label), not an attack. Normally flat zero, so `> 0` sustained
  is a clean signal. Warning: it degrades observability, not the service.

**W3. Zero-byte traffic rows filtered (#864 regression sentinel)**
- Query: `rate(traffic_reports_filtered_zero_bytes_total{env="prod"}[15m])`
- Fire when `> 0` **for 30m**.
- Rationale: a rising rate means the #858 agent regression (emitting empty
  rows) has returned. It is slow-burning and self-documenting, so a long
  `for: 30m` avoids paging on a brief blip while still surfacing a real
  regression the same day.

**W4. Auth-failure spike**
- Query: `sum(rate(auth_failures_total{env="prod"}[5m]))`
- Fire when `> 0.5` (≈ 30/min) **for 10m**.
- Rationale: a sustained burst of `bad_password` / `bad_router_token` is the
  household's only brute-force signal. The threshold is deliberately well above
  a fat-fingered login (a few failures) so routine operator typos never alert.
  Warning, not critical — tune up if it proves noisy.

**W5. Blocklist fetch failures** *(deferred — see §8)*
- Intended query: `sum(rate(blocklist_fetch_failures_total{env="prod"}[15m])) > 0 for 30m`.
- This is a **router-pushed** series; per §2's caveat and #1382 it is not yet
  trustworthy in prod. **Author the rule but keep it disabled / file behind the
  router-counter readiness issue** so it activates when the fleet rolls forward.

**W6. Support responder permanently dead (config)**
([#2416](https://github.com/wifihaven/wifihaven/issues/2416))
- Query: `sum(rate(support_ai_draft_total{env="prod",outcome="error",reason="config"}[15m]))`
- Fire when `> 0` **for 15m**.
- Rationale: `reason="config"` is emitted **only** for a non-self-healing 4xx at
  the Anthropic boundary — a revoked/wrong `anthropicApiKey` (401), a wrong
  `claudeAgentId` / `claudeEnvironmentId` / `claudeCodeRoutineId` (404), or a
  stale hard-coded `anthropic-beta` header (400). By construction none of those
  recover without a human, so unlike the sibling `reason="transient"` bucket
  (transport / timeout / 5xx / 408 / 429) **any** sustained rate is actionable —
  hence the same `> 0` threshold the other never-should-happen counters use.
  Dispatch is fail-open by design (the inbound webhook still returns 200), so
  this counter and the ERROR log line are the only signals that customers are
  getting no AI reply. Warning, not critical: support degrades, enforcement does
  not.
- **Live in prod since the #2335 go-live.** Shipped **enabled** rather than
  `is_paused` (contrast W5) precisely so it would arm with the feature flag and
  not need a second flip to remember — there was nothing about the rule itself to
  fix. That worked: [#2537](https://github.com/wifihaven/wifihaven/pull/2537) set
  `WIFIHAVEN_SUPPORT_RESPONDER_ENABLED: "true"` on `wifihaven-api-prod`
  (`render.yaml`) and the rule armed with no change here. A firing W6 is a real
  prod page now, not a latent rule.

**W7. Press responder permanently dead (config)**
([#2416](https://github.com/wifihaven/wifihaven/issues/2416))
- Query: `sum(rate(press_ai_draft_total{env="prod",outcome="error",reason="config"}[15m]))`
- Fire when `> 0` **for 15m**.
- Rationale: identical failure model to W6, on the press credentials/ids. Kept a
  **separate rule on a separate series** because the two audiences are graphed and
  alerted independently and the remediation differs. Also live in prod since
  #2537 set `WIFIHAVEN_PRESS_RESPONDER_ENABLED: "true"` at the #2337 go-live.

**W8. Plain REFUSED to send a support reject**
([#2488](https://github.com/wifihaven/wifihaven/issues/2488))
- Query: `sum(rate(support_ai_draft_total{env="prod",outcome="email_reject_send_failed"}[15m]))`
- Fire when `> 0` **for 15m**.
- Rationale: the same never-self-heals class as W6, one seam earlier — the
  **Plain** boundary rather than the Anthropic one.
  `outcome="email_reject_send_failed"`
  ([#2471](https://github.com/wifihaven/wifihaven/issues/2471)) is emitted only
  when Plain *accepted* the unregistered-sender reject write and refused to send
  it, so the customer got nothing. The likely cause is a workspace with email
  sending switched off — Settings → Channels → Email, section 3 "Sending emails"
  left unverified or section 4 "Enable email" never clicked
  ([`docs/ops/plain-setup.md` §3.1](../ops/plain-setup.md#31-email-sending--a-required-go-live-gate-per-workspace)),
  which stays broken until a human finishes provisioning.
  A deliberate off-state is **not** this:
  `plain.writeEnabled=false` labels `disabled`, so our own flag cannot light the
  rule. Deliberately does **not** constrain `reason`: `WebhookOutcome.reason`
  returns `none` for this outcome on purpose (`PlainClient` collapses every send
  failure into one causeless `PlainOutcome.Error`), so a second selector would
  add nothing and break silently if attribution is added later.
  [#2485](https://github.com/wifihaven/wifihaven/pull/2485) added an expect-0
  dashboard tile, but a tile is only seen by whoever opens it. Also live in prod
  since #2537 set `WIFIHAVEN_SUPPORT_RESPONDER_ENABLED: "true"` at the #2335
  go-live — W8 armed the same day it merged.
- **Known half-coverage — the dropped AI reply.** The same Plain refusal also
  drops the reply to a *registered* customer, which is the higher-value half (a
  reject goes to a non-customer; a dropped reply goes to an onboarded household
  that wrote in). That half meters on a **different** series,
  `support_agent_action_total{op="reply",outcome="error"}`, which W8 does not
  cover and which has no rule. Deferred rather than folded in: that series
  carries no `reason` label, so a send refusal is indistinguishable there from
  any other reply-path error, and alerting the whole `outcome="error"` bucket at
  `> 0` needs a threshold tuned first — the same problem *class* as
  [#2443](https://github.com/wifihaven/wifihaven/issues/2443) (a noisy bucket
  needs a tuned threshold, not `> 0`), though that issue is about the
  `reason="transient"` bucket on the draft series, not this one. Tracked in
  [#2539](https://github.com/wifihaven/wifihaven/issues/2539).
- Not covered here: the sibling expect-0 tile "Escalated threads NOT marked in
  Plain" ([#2437](https://github.com/wifihaven/wifihaven/issues/2437)) is a
  different series with a different remediation, and stays panel-only for now.

## 8. Gaps — metrics not yet emitted

These are alerts worth having whose metric does not (reliably) exist yet. They
get **no rule** now; each is bound to the instrumentation issue that unblocks
it (per the "alert only on emitted series" rule).

| Desired alert | Missing/unready series | Blocked on |
| --- | --- | --- |
| Deploy failure (pairs with #1245 annotations) | No alertable series. `deploy-webhook` writes **Grafana annotations only** — by design it "does not scaffold any notification/alerting transport." Annotations are not PromQL-alertable. | [#1405](https://github.com/wifihaven/wifihaven/issues/1405): have `deploy-webhook` *also* emit a Prometheus counter (e.g. `render_deploy_total{lifecycle}`) scraped by Alloy, then alert `increase(...{lifecycle="failed"}[10m]) > 0`. Or use Grafana Cloud's native Render-integration deploy events if/when available. |
| Blocklist fetch failures (W5) | `blocklist_fetch_failures_total` router-pushed but unreliable in prod | [#1382](https://github.com/wifihaven/wifihaven/issues/1382) + agent counters [#1301](https://github.com/wifihaven/wifihaven/issues/1301)/[#1302](https://github.com/wifihaven/wifihaven/issues/1302)/[#1325](https://github.com/wifihaven/wifihaven/issues/1325) |
| Agent restart / uptime regression | `agent_uptime_seconds`, `dnsmasq_restarts_total` router-pushed, unreliable in prod | same as above — fleet roll-forward + #1382 |
| Per-router liveness (which router dropped, not just "fleet → 0") | Would need a per-`router_id` series; `agent_connected_routers` is a single aggregate gauge | acceptable for now (household = ~1 router); revisit if the fleet grows |

## 9. Implementation sub-issues

Filed under the **Alerting & Paging** epic, one per coherent chunk:

1. **[#1406](https://github.com/wifihaven/wifihaven/issues/1406) —
   `infra/grafana` → HCP Terraform remote backend** *(prerequisite; blocks
   2–4)*. Mirror [#1357](https://github.com/wifihaven/wifihaven/issues/1357):
   add the `cloud {}` block (org `wifihaven`, a `grafana` workspace, Local
   execution), wire `master-grafana.yml`'s `init` to it, confirm the existing
   dashboards reconcile cleanly under managed state. Validation: a second
   `apply` is a no-op (no duplicate-dashboard churn).
2. **[#1402](https://github.com/wifihaven/wifihaven/issues/1402) — Contact
   points + notification policy** ([§4](#4-routing--contact-points)).
   `grafana_contact_point` ×3 (`wifihaven-critical`, `wifihaven-warning`,
   `wifihaven-staging`, all email; address via `operator_email` TF var) +
   the singleton `grafana_notification_policy` tree with the severity/env
   routing and grouping/throttle settings. Validation: a hand-fired test alert
   reaches the operator's inbox.
3. **[#1403](https://github.com/wifihaven/wifihaven/issues/1403) — First
   critical rule set** ([§7.1](#71-critical--page-now)) as one or two
   `grafana_rule_group`s: C1–C7. Each ships with its threshold + `for` + guard
   as specified. Validation per [§10](#10-validation).
4. **[#1404](https://github.com/wifihaven/wifihaven/issues/1404) — First
   warning rule set** ([§7.2](#72-warning--notify-look-today)): W1–W4
   (+ W5 disabled, bound to its readiness issue). Extended by
   [#2416](https://github.com/wifihaven/wifihaven/issues/2416) with W6–W7, and
   by [#2488](https://github.com/wifihaven/wifihaven/issues/2488) with W8.
5. **[#1405](https://github.com/wifihaven/wifihaven/issues/1405) —
   Deploy-failure signal** ([§8](#8-gaps--metrics-not-yet-emitted)): extend
   `deploy-webhook` to emit `render_deploy_total{lifecycle}` (or adopt native
   Render deploy events) + the alert. Separate because it needs new
   instrumentation, not just a rule.

Each rule-shipping issue follows the AGENTS.md `grafana-terraform` gate:
`terraform fmt -check`, `terraform validate`, and (for any touched dashboard
JSON) `python3 -m json.tool` — all run locally before push and in CI.

## 10. Validation — proving a page actually fires

The #1368 acceptance bar is "an operator can confirm a **page**, not just a
graphable series." For each rule:

1. **Unit-check the expression** against live data in Grafana Explore (or
   `EXPLAIN`-style: paste the PromQL, confirm it returns the expected value
   under current conditions — typically below threshold).
2. **Force the condition.** Realistic per alert without harming prod:
   - C4 ingest-failure: point a throwaway agent (or `curl`) at
     `POST /api/router/metrics` with a deliberately malformed batch so
     `status="malformed"` climbs and the ratio drops below 0.95. Confirmed-safe
     — it's the exact #1365 shape, and it self-heals when you stop.
   - C2 HikariCP pending / C1 DB CPU: validate on a **prod-shaped scratch DB**,
     not prod — drive concurrent load past the pool size. Never manufacture a
     prod saturation event to test an alert.
   - C5/C6 API down/5xx: on staging, stop the service (C5) or hit a route that
     500s (C6).
   - C7 fleet liveness: on staging, stop the agent and wait out the 10m window.
   - W2 cardinality: emit a forbidden-label series on staging.
3. **Confirm the notification lands.** The alert transitions
   `Pending → Firing` after its `for`, the notification policy routes it to the
   right contact point, and the **email actually arrives**. A series that
   crosses threshold but produces no email is a routing bug, not a pass.
4. **Confirm it resolves.** Stop forcing the condition; the alert returns to
   `Normal` and a resolved notification is sent.

Grafana managed alerts support a **"Test" button** per contact point and a
rule **preview** — use both during step 1/3, but the acceptance bar is a real
forced-condition firing reaching the inbox, not just the test button.

## 11. How this closes the alerting half of #1368

[#1368](https://github.com/wifihaven/wifihaven/issues/1368) had two halves:

- **Dashboard half — shipped** in [#1373](https://github.com/wifihaven/wifihaven/pull/1373):
  the at-a-glance router-metrics ingest **success-ratio** stat panel (and its
  total-outage-vs-no-data fix in [#1383](https://github.com/wifihaven/wifihaven/issues/1383)/[#1384](https://github.com/wifihaven/wifihaven/pull/1384)).
- **Alerting half — this document.** Alert **C4** ([§7.1](#71-critical--page-now))
  is the direct successor to that panel: it reuses #1373's success-ratio
  expression, adds the zero-traffic guard, sets the `< 0.95 for 15m` condition,
  and routes it `critical`. With C4 in place, the #1365 silent-failure scenario
  pages the operator instead of waiting to be hand-discovered — which was the
  whole point of #1368.

This design doc + the §9 sub-issues constitute the deliverable; #1368's
alerting half is considered designed once this lands, and *implemented* once
sub-issues 1–3 ship C4 (plus its siblings) to the live stack.
