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
- **Armed in prod, not yet exercisable.** Shipped **enabled** rather than
  `is_paused` (contrast W5) precisely so it would arm with the feature flag and
  not need a second flip to remember — there was nothing about the rule itself to
  fix. That worked: [#2537](https://github.com/wifihaven/wifihaven/pull/2537) set
  `WIFIHAVEN_SUPPORT_RESPONDER_ENABLED: "true"` on `wifihaven-api-prod`
  (`render.yaml`) and the rule armed with no change here. But the flag is
  necessary, not sufficient: `support_ai_draft_total` is produced only downstream
  of an inbound Plain webhook, and prod's Plain workspace is **not wired to the
  prod API yet** ([#2543](https://github.com/wifihaven/wifihaven/issues/2543) —
  the 2026-07-29 verification logged no support lines at all, where a configured
  webhook against a dark responder would still have logged `outcome=disabled`).
  W6 starts covering the moment that webhook is configured, again with no rule
  change.

**W7. Press responder permanently dead (config)**
([#2416](https://github.com/wifihaven/wifihaven/issues/2416))
- Query: `sum(rate(press_ai_draft_total{env="prod",outcome="error",reason="config"}[15m]))`
- Fire when `> 0` **for 15m**.
- Rationale: identical failure model to W6, on the press credentials/ids. Kept a
  **separate rule on a separate series** because the two audiences are graphed and
  alerted independently and the remediation differs. **Live in prod** since
  [#2537](https://github.com/wifihaven/wifihaven/pull/2537) set
  `WIFIHAVEN_PRESS_RESPONDER_ENABLED: "true"` on `wifihaven-api-prod` — and
  unlike W6/W8, exercisable today: the Cloudflare Email Worker already posts to
  the prod API, so a prod press dispatch failure pages now.

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
  dashboard tile, but a tile is only seen by whoever opens it. Armed in prod
  within the hour of merging — [#2537](https://github.com/wifihaven/wifihaven/pull/2537)
  set `WIFIHAVEN_SUPPORT_RESPONDER_ENABLED: "true"` immediately before W8 landed,
  with no rule change needed — but **not yet exercisable**, for the same reason as
  W6: prod's Plain webhook is unwired
  ([#2543](https://github.com/wifihaven/wifihaven/issues/2543)).
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

*(W9, "a household was skipped by a rollup tick"
([#2553](https://github.com/wifihaven/wifihaven/issues/2553)), ships in
`infra/grafana/alerting-rules-warning.tf` but has no write-up here yet.)*

**W10. Router stuck on an old agent version**
([#2646](https://github.com/wifihaven/wifihaven/issues/2646))
- Query:
  ```promql
  count by (router_id) (
    count by (router_id, version) (last_over_time(agent_version{env="prod"}[30d]))
      and on (version) count by (version) (agent_version{env="prod"})
  )
    < on() group_left()
  count(count by (version) (agent_version{env="prod"}))
  ```
- Fire when the comparison yields any series (`> 0`) **for 24h**.
- Rationale: a router that stops self-updating looks completely healthy — the
  agent runs, enforces, reports usage and keeps `last_seen_at` fresh, it just
  never installs another package. The failure is an *absence*, so nothing
  surfaced it before this rule. It is not only feature drift: the agent is how
  security fixes reach the fleet
  ([#2078](https://github.com/wifihaven/wifihaven/issues/2078)), so a stuck
  router never receives one. Found live during
  [#2527](https://github.com/wifihaven/wifihaven/issues/2527)'s prod
  validation: router `3498967e` sat four days on `0.3.26` while `f04dd490`,
  same fleet and same release channel, took `0.3.27` within the hour.
- **Update staleness, not version skew, and no pinned target version.** A
  hardcoded "current version" in the rule is stale-by-construction — exactly
  the forgotten-to-bump failure the rule exists to catch. The alternative
  shape (rank routers by version, alert on anything below the max) needs a
  semver **ordering**, which Prometheus cannot do on a label string; it would
  mean emitting a numeric companion gauge
  (`major*10000 + minor*100 + patch`). Rejected — the signal already exists,
  and adding a metric purely to encode an ordering the rule does not need is
  the wrong trade (§2).
- **How it reads.** `agent_version` is an info gauge (constant `1` carrying the
  version as a label), so a router that upgrades leaves *both* series in the
  lookback window: the version it left and the version it took. That history is
  the whole trick. The inner term is the set of versions this router reported
  in the last 30d intersected with the versions currently live anywhere in the
  fleet; the right-hand term is how many distinct versions are live fleet-wide.
  A router fires when some version a peer is running *right now* is one it has
  never run. That history is also what stands in for a version ordering: a
  rollout leader is not flagged because it ran the old version earlier in the
  window and still intersects it. Note precisely what that rests on — the
  leader's **remembered history**, not any property of the version numbers. A
  router with no pre-split history is not protected by it (limit 3 below). The
  intersection (rather than a plain count-vs-count) matters — a laggard that
  upgraded once early in the window and then froze has two versions in history,
  and a bare cardinality comparison would miss it.
- **`for: 24h`.** Updates run from an hourly jittered cron
  (`0 * * * * /usr/sbin/wifihaven-update --jitter`, `openwrt/Makefile:175`)
  whose jitter is capped at `WIFIHAVEN_UPDATE_JITTER_MAX`, default 600s
  (`openwrt/files/usr/sbin/wifihaven-update:63`), so worst-case rollout skew is
  ~70 min. A day of grace is an order of magnitude past that and still catches
  "stopped forever", which is the condition being detected. A rule that pages on
  every release trains the operator to ignore the one signal that matters.
  Verified against prod Prometheus: the expression went true ~1h after `0.3.27`
  shipped (release `createdAt` 2026-08-05T15:07:17Z) and stayed continuously
  true, with no gaps, for 3.5 days for `3498967e` only — until that router
  finally took `0.3.27` on 2026-08-09 and it went empty again. Both edges of the
  real incident, which is the validation.
- **Cardinality.** `agent_version` carries `router_id`, per-router and so
  unbounded in principle. It sits outside the bounded-label-enum rule in
  [`docs/process/instrumentation.md`](../process/instrumentation.md) because
  the label is agent-pushed rather than server-derived, and the metric predates
  this rule (`api/src/metrics/Metrics.scala` allowlists
  `version`/`router_id`/`installation_id`). Fine at the current fleet size; the
  note exists so it reads as a considered exception at 500 routers, not an
  oversight.
- **No-data is the healthy state**, so `no_data_state = OK` is *required* here,
  not merely inherited from the group template: a comparison filter returns
  nothing when no router is lagging, and any other setting would fire
  continuously. The cost is that W10 also reads healthy if `agent_version` stops
  being emitted fleet-wide, and that gap is real — **C7 and C4 do not cover it.**
  C7 (`agent_connected_routers < 1`) is computed from `routers.last_seen_at`,
  which the metrics-batch path never writes: `routerRepo.touch` is called from
  the snapshot poll (`api/src/routes/RouterRoutes.scala:85`), usage/event ingest
  (`api/src/routes/RouterIngestService.scala:92,154`) and the ws heartbeat
  (`api/src/routes/RouterWsRoutes.scala:306`), not from `RouterMetricsRoutes`, so
  an agent that keeps polling policy while its metrics push dies still reads
  connected. C4 (`router_metrics_batches_total` success ratio) is a ratio, and a
  *total* stop leaves nothing to divide — empty or NaN, either way it lands in
  no-data → OK; it catches a degraded ingest, not a silent one. That leaves "metrics stop while the agent looks alive"
  uncovered, with W10 itself silently off — the
  [#2546](https://github.com/wifihaven/wifihaven/issues/2546) shape. Fixing it
  wants a separate `absent(agent_version)` liveness rule, tracked in
  [#2654](https://github.com/wifihaven/wifihaven/issues/2654).
  **W14 below now covers most of that slice** — it compares the reporting-router
  count against the connected-router count, so a router that goes silent while
  holding a websocket fires there even though W10 is blind to it, and the
  `or vector(0)` arm catches the fleet-wide stop too. What W14 does *not* cover
  is a fleet whose routers all lose their sockets at the same moment they stop
  reporting, which is what leaves #2654 open.
- **Query cost — the one axis that does not scale**, and a different axis from
  the cardinality note above. The scrape interval is 30s
  (`deploy/alloy/config.alloy`), so a `[30d]` lookback fetches ~86,400 samples
  per series per evaluation on a 60s group interval; every other rule in the
  file uses `window_s` between 300 and 3600. Fine today, ~43M samples per
  evaluation at 500 routers, and a query rejected on a sample limit lands in
  `exec_err_state = "Error"` — visible but *not* notifying, so the detector goes
  dark exactly when it is most needed. The lookback is not free to shorten (it
  is what keeps the leader's memory of the superseded version alive), so the fix
  is a recording rule, tracked in
  [#2650](https://github.com/wifihaven/wifihaven/issues/2650).
- **Known limits, all three accepted.** (1) Fleet-relative, so it cannot detect
  a fleet where *every* router is stuck on the same old version — closing that
  needs a comparison against the published release rather than against peers.
  (2) If a split persists past the 30d lookback, the healthy router's memory of
  the old version ages out and it flags too; noisy, but only after a month of
  an unfixed W10, and it clears when the laggard catches up.
  (3) A router with **no pre-split history** flags while a laggard is
  outstanding: a newly enrolled household, or a box re-enrolled onto a fresh
  `router_id`, has only its current version in the window and so cannot
  intersect the laggard's. Structurally the same position as limit (2), just
  immediately rather than after 30d. Accepted because it is strictly downstream
  of an already-firing, already-unhandled W10 — the laggard must have been
  lagging 24h+ for the new router to have anything to miss — and it clears when
  that laggard is fixed. It does mean the first notification after onboarding a
  household during an unresolved lag names the *newest* box on the fleet, so the
  rule's summary tells the operator to check the version-distribution panel
  before running the runbook.
- Paired panels (per
  [`docs/process/instrumentation.md#metrics-need-a-dashboard`](../process/instrumentation.md#metrics-need-a-dashboard)):
  "Fleet agent-version distribution" and "Routers lagging the fleet
  (expect empty)", the latter running the rule's own expression, on the
  router-fleet dashboard.

**W11/W12/W13. The dispatch watchdog**
([#2477](https://github.com/wifihaven/wifihaven/issues/2477),
[#2517](https://github.com/wifihaven/wifihaven/issues/2517))

Three rules that only make sense together: W11 and W13 are the failure, one per
responder, and W12 is the proof that either was in a position to fire.

- **W11 — a support customer got no answer.**
  ```promql
  sum(increase(support_dispatch_total{env="prod",outcome="no_callback"}[1h]))
  ```
  `gt = 0`, `for = 15m`. Every sample is a cloud-agent session that accepted the
  trigger and died, so the customer got nothing and nothing retries
  ([#2472](https://github.com/wifihaven/wifihaven/issues/2472) declined
  auto-retry: a second billed session plus a duplicate-reply risk the
  [#2403](https://github.com/wifihaven/wifihaven/issues/2403) loop guard cannot
  suppress).
  **The threshold is not tuned, it is inherited.** `no_callback` is emitted only
  past the agent-token TTL (`support.agentTokenTtlMinutes`, 24h via
  `AgentTokenTtl.DefaultMinutes`), which is the first instant at which silence is
  unambiguous — before it, a `claude-code-cloud` run suspended on subscription
  usage limits can still resume and answer
  ([#2473](https://github.com/wifihaven/wifihaven/issues/2473) observed a
  resumed run posting 2.5h after mint, and an evening pause resuming the next
  morning); after it, that run's callback 401s and the answer can never land.
  That is also what makes the summary's "reply by hand" instruction safe: past
  the TTL a late agent reply cannot arrive on top of the operator's.
  `for = 15m` only debounces a scrape blip; the condition already waited a day.
  **Support only** — press has its own rule, W13 below, rather than this one
  being widened to a sum across both series. The RECOVERY differs (a Plain
  thread vs an email from the `/press` correspondence log), and an alert whose
  summary cannot name the recovery is one someone has to reason about at 2am.
- **W12 — the watchdog stopped reporting.**
  ```promql
  (min by (channel) (increase(agent_dispatch_sweeps_total{env="prod"}[15m])) == bool 0)
    or absent(agent_dispatch_sweeps_total{env="prod",channel="support"})
  ```
  `gt = 0`, `for = 30m` (30 × `DispatchTracker.SweepInterval`, so a deploy,
  restart or scrape gap cannot fire it).
  **Why a second rule at all.** `agent_dispatch_unreplied` is a gauge, and a
  gauge keeps exporting its last written value for the life of the process. A
  sweep fiber that died would therefore publish a stale, reassuring `0` forever
  while W11's counter simply stopped moving — absence and health back to being
  one picture. That is the
  [#2546](https://github.com/wifihaven/wifihaven/issues/2546) shape, where
  [#2469](https://github.com/wifihaven/wifihaven/issues/2469)'s prompt-drift
  detector has never emitted a sample in *any* environment and its silence has
  read as health since it shipped. W12 exists so #2477 is not the third.
  **Two arms, two different failures.** `== bool 0` catches a dead fiber inside
  a live process (the counter is still scraped but no longer advances) —
  `bool` is load-bearing, since a bare `== 0` returns the value `0`, which
  `gt = 0` reads as healthy. `absent(...)` catches the series being gone
  entirely; the group's `no_data_state = OK` must stay as it is for W10's sake,
  so absence has to become a *value* inside the expression rather than a no-data
  verdict. `by (channel)` gives each responder its own alert instance, so a
  stalled press sweep cannot be masked by a healthy support one, and `min`
  rather than `sum` keeps that true under a scale-out (at `numInstances: 1`
  they are identical, but a `sum` would let one live instance's increases hide
  a dead fiber on its sibling). The `absent` arm names `channel="support"`
  explicitly because it asserts which channels are *expected* to sweep — a
  claim only the code can make and PromQL cannot infer from an empty result.
  Since #2517 there are **two** `absent` arms, one per channel, ORed. Not a
  single `absent(...{channel=~"support|press"})`: the regex form goes quiet as
  soon as *either* channel reports, which is precisely the masking this rule
  exists to prevent.
- **W13 — a journalist got no answer.**
  ```promql
  sum(increase(press_dispatch_total{env="prod",outcome="no_callback"}[1h]))
  ```
  `gt = 0`, `for = 15m`. W11's twin, with the same inherited threshold for the
  same reason — `press.agentTokenTtlMinutes` shares the
  `AgentTokenTtl.DefaultMinutes` default, since the sizing constraint is a
  property of the shared cloud transport rather than of either audience.
  **One press-only wrinkle the summary has to carry:** since #2517 this bucket
  also holds a session that DID call back and was refused by the
  [#2437](https://github.com/wifihaven/wifihaven/issues/2437) escalation cap,
  which deliberately does not close the dispatch because nothing reached a human
  either way. Check `press_agent_action_total{op="escalate",outcome="rate_limited"}`
  before concluding the session died: there the agent is alive and the fix is to
  answer the escalation, not to hand-reply as though it never ran.
- Paired panels (per
  [`docs/process/instrumentation.md#metrics-need-a-dashboard`](../process/instrumentation.md#metrics-need-a-dashboard)):
  "Customers waiting on an unanswered dispatch" and "Watchdog heartbeat (10m)"
  on the support dashboard, and their press twins — "Journalists waiting on an
  unanswered dispatch" and "Press watchdog heartbeat (10m)" — on the press
  dashboard, shipped by #2517 in the same change that gave the press series a
  producer.

**W14. A connected router has stopped reporting metrics**
([#2646](https://github.com/wifihaven/wifihaven/issues/2646) follow-up)

W10's **absence arm**. Read the two together — they cover opposite doors into
the same failure and neither subsumes the other.

- Query:
  ```promql
  (
    (count(count by (router_id) (agent_version{env="prod"})) or vector(0))
      < bool
    max(router_ws_connections_active{env="prod"})
  )
  ```
- `gt = 0`, `for = 6h`.
- **The gap W10 cannot see.** Every term on both sides of W10's comparison is
  derived from `agent_version`, a series that exists only for a router that is
  *pushing*. A router that stops pushing does not become a laggard in W10's
  eyes — it drops out of the comparison entirely and W10 goes quiet, for
  precisely the box it was written to protect. Live on prod 2026-08-15:
  `router_ws_connections_active = 2` (both routers holding a socket, with
  `router ws: connected router=f04dd490-…` in Loki at 18:03:33Z) while
  `agent_version{env="prod"}` had a single series (`3498967e`, `0.3.29`).
- **Choosing the reference signal is the whole design.** Prometheus cannot
  alert on the absence of a series it has never seen — `absent()` needs a
  nameable label set, and router ids are not knowable in a static rule. So the
  rule needs some *other* series that enumerates who should be reporting. Three
  candidates, all measured against 14 days of real prod data:
  - `agent_connected_routers` — **rejected, it shares the blind spot.** It
    counts routers whose `routers.last_seen_at` falls inside a 10-minute window
    (`RouterPresenceMetrics.DefaultWindow`), and `last_seen_at` is written by
    the snapshot poll, usage/event ingest and the ws heartbeat — the same
    agent-liveness paths that die alongside the metrics push. It read **1** at
    the moment the failure was live, exactly equal to the reporting count, so it
    cannot distinguish the broken state from the healthy one. It is also the
    noisiest of the three: over 14d it flapped between 1 and 2 repeatedly while
    both routers were reporting normally.
  - **A new server-side enrolled-router gauge** off the `routers` table —
    rejected, and this is the close call. It would be authoritative about who
    *should* report, and unlabelled, so it costs no cardinality. But an
    enrollment row outlives the hardware: a decommissioned-but-undeleted router,
    or a household whose box is unplugged for a week, holds the rule firing
    forever with no action available, and an alert that cannot be resolved by
    fixing something is one the operator learns to close. It also fails the §2
    "alert on a series that already exists" bar — a new metric earns its place
    when nothing else can carry the meaning, and here something can.
  - `router_ws_connections_active` — **chosen.** A router holding an open
    websocket is one we have direct live evidence is up and talking to us; up,
    talking, and pushing nothing is exactly the failure. It self-clears with no
    bookkeeping — a decommissioned or unplugged router drops its socket and
    leaves the reference count on its own, the property the enrolled gauge
    lacks. Replayed over the full retained window (2026-08-01T19:01Z to
    08-15T18:56Z, 4032 samples at 300s — both integers below are phase-sensitive
    at this step, since a 30s dip lands on a 300s grid point only about a tenth
    of the time): 3966 at 2 and 66 at 1, the latter in
    only two stretches — a 5.3h run at the very start of retention (08-01
    19:01 to 08-02 00:21, which reads as the second router joining rather than
    a flap) and one single sample on 08-07 14:16. Both *lower* the reference,
    so both fail safe. Three transitions across the fortnight against
    `agent_connected_routers`' 48 over the same window — same phase caveat, the
    `48` moves if you re-query at a different step phase, the order-of-magnitude
    gap does not. The stablest of the three by an order of magnitude.
- **What it depends on**, stated plainly because it is the fragile part. The
  gauge is documented as a count of *channels*, not routers; it is a router
  count only because `RouterWsRegistry.register` **supersedes** — a reconnect
  evicts and shuts down the channel already held for that id
  ([#2561](https://github.com/wifihaven/wifihaven/issues/2561)), so a router
  holds at most one. Relax that invariant and the reference count inflates and
  this rule false-fires; anyone changing the registry's channel-per-router bound
  must revisit it. Second dependency: a router on the REST transport holds no
  channel, so it counts on the reporting side and not the reference side. That
  direction fails **safe** — the comparison cannot go true from it — but a
  REST-only router is not covered here. ws is the fleet default since
  [#2608](https://github.com/wifihaven/wifihaven/issues/2608), which is what
  makes that acceptable rather than a hole.
- **Counts, not identities — deliberately.** The rule fires without naming the
  silent router, because naming it would need a per-router *server-derived*
  series, which is out of bounds under the cardinality firewall in
  [`docs/process/instrumentation.md`](../process/instrumentation.md). (`agent_version`'s own `router_id` is the
  documented exception because it is agent-pushed; that exception does not
  extend to inventing a new server-side per-router gauge.) "One router is
  silent" is enough to act on, and the operator identifies which one in two
  clicks from the paired panel. A count comparison that fires beats an
  identity-precise rule that does not exist. The two clicks land on the
  router-fleet dashboard's **"Agent versions across the fleet"** panel, which
  is the one whose query carries `router_id` — *not* "Fleet agent-version
  distribution" beside it, which counts by version only and cannot identify a
  router.
- **How it reads.** `count by (router_id)` collapses the version label so an
  in-flight upgrade cannot double-count a router; the outer `count` is then the
  number of routers with a live `agent_version`. `or vector(0)` is load-bearing,
  not defensive padding: `count()` over an empty vector returns **empty, not
  0**, so without it the total-silence case (every router stops) produces no
  sample and reads as healthy — the
  [#2546](https://github.com/wifihaven/wifihaven/issues/2546) shape, and the
  same hole [#2654](https://github.com/wifihaven/wifihaven/issues/2654) is filed
  for. With it, `0 < 2` fires. `< bool` rather than a bare `<` for the same
  reason: a bare comparison returns the *left* value, which is 0 in exactly that
  total-silence case, and `gt = 0` would filter out the one sample that matters
  most. `bool` yields a clean 1/0 and makes `gt = 0` a true boolean test — the
  same reasoning as W12's `== bool`.
- **`max` over the reference gauge is a no-op today**, and that is worth
  stating exactly rather than dressing up as scale-out safety.
  `deploy/alloy/config.alloy:15-25` scrapes *one* target,
  `wifihaven-api-prod:8080`, with `instance` hard-coded to the literal
  `"wifihaven-api-prod"`, so there is exactly one series and `max == sum ==`
  the single sample. `max` is the conservative aggregator over a gauge
  documented as channels; it buys nothing else. **Do not read it as making W14
  scale-out-correct.** Raising `numInstances` (1 today, `render.yaml`) does not
  produce per-instance series: Render's internal address round-robins behind
  that single fixed-`instance` scrape target, so each 30s scrape returns
  whichever instance answered, and *both* operands are then sampled from that
  one arbitrary instance. No choice of aggregator fixes that. **Which way the
  comparison then breaks is deliberately not predicted here.** Earlier drafts of
  this section did predict a direction and could not support it from the scrape
  config; it is not determinable without running a two-instance deploy. What
  *is* certain is that
  the reference stops meaning "the fleet's connected routers", which is the
  property the rule rests on. So rework the scrape topology in `config.alloy`
  (per-instance targets, or a server-side aggregate) before raising
  `numInstances`, and re-derive this rule against whatever that produces rather
  than reasoning about it in advance. If the reference
  gauge is itself absent (the API is down) the comparison is empty and the rule
  lands in no-data → OK, which is right: an API outage is C-tier.
- **`for: 6h` — calibrated against 14 days of prod, not picked.** The
  expression was replayed over the full retained window at 5-minute resolution
  (4032 samples) and went true in **four** runs: one single sample on 08-08
  00:56, 0.83h on 08-10, 17.25h from 08-14 16:36 to 08-15 09:51, and 3.67h from
  08-15 15:06 to 18:46. The single sample is an API restart — the agent-pushed
  gauges repopulate only on the next push, `metrics_report_interval` 60s
  (`openwrt/files/etc/config/wifihaven`) — and it is present in the *shipping*
  expression, not an artifact of an earlier draft. None of the three short runs
  reaches 6h, which corroborates the threshold rather than qualifying it. 6h
  clears the largest benign run by 7.2× and the push interval by 360×, so no
  restart, reboot, agent upgrade or scrape gap reaches it, and it would have
  fired **once** in that fortnight, on the
  17.25h event — the genuine failure. Shorter pages on the 0.83h dip. Longer
  (W10's 24h) misses the 17.25h outage entirely, and that difference is the
  point: W10 detects "stopped updating forever", a days-scale fact, while this
  detects "stopped talking", where six hours of silence from a box holding a
  socket open is already anomalous.
- **Query cost.** Unlike W10 there is no range selector at all — two instant
  gauge reads per evaluation — so `window_s` takes the file minimum and
  [#2650](https://github.com/wifihaven/wifihaven/issues/2650)'s recording-rule
  concern does not apply.
- **Known limits, both accepted.** (1) A router that is entirely gone — powered
  off, socket dropped — leaves both sides of the comparison together and does
  not fire here. That is a different condition ("the fleet shrank") from the one
  this rule detects ("a connected router went quiet"), and covering it wants the
  enrolled-router reference rejected above, with the unresolvable-alert problem
  that comes with it. C7 (`agent_connected_routers < 1`) covers only the
  fleet-to-zero case, so one-of-N disappearing remains uncovered.
  (2) The rule cannot name the silent router (see above).
- Paired panel (per
  [`docs/process/instrumentation.md#metrics-need-a-dashboard`](../process/instrumentation.md#metrics-need-a-dashboard)):
  "Routers connected vs reporting metrics" on the router-ws-transport
  dashboard, plotting both sides of the comparison so the gap is the thing you
  see. Cross-reference the router-fleet dashboard's "Agent versions across the
  fleet" panel — the one that carries `router_id` — to identify which router is
  missing.

## 8. Gaps — metrics not yet emitted

These are alerts worth having whose metric does not (reliably) exist yet. They
get **no rule** now; each is bound to the instrumentation issue that unblocks
it (per the "alert only on emitted series" rule).

| Desired alert | Missing/unready series | Blocked on |
| --- | --- | --- |
| Deploy failure (pairs with #1245 annotations) | No alertable series. `deploy-webhook` writes **Grafana annotations only** — by design it "does not scaffold any notification/alerting transport." Annotations are not PromQL-alertable. | [#1405](https://github.com/wifihaven/wifihaven/issues/1405): have `deploy-webhook` *also* emit a Prometheus counter (e.g. `render_deploy_total{lifecycle}`) scraped by Alloy, then alert `increase(...{lifecycle="failed"}[10m]) > 0`. Or use Grafana Cloud's native Render-integration deploy events if/when available. |
| Blocklist fetch failures (W5) | `blocklist_fetch_failures_total` router-pushed but unreliable in prod | [#1382](https://github.com/wifihaven/wifihaven/issues/1382) + agent counters [#1301](https://github.com/wifihaven/wifihaven/issues/1301)/[#1302](https://github.com/wifihaven/wifihaven/issues/1302)/[#1325](https://github.com/wifihaven/wifihaven/issues/1325) |
| Agent restart / uptime regression | `agent_uptime_seconds`, `dnsmasq_restarts_total` router-pushed, unreliable in prod | same as above — fleet roll-forward + #1382 |
| Per-router liveness (which router dropped, not just "fleet → 0") | Would need a per-`router_id` **server-derived** series, which the bounded-label-enum rule forbids; `agent_connected_routers` is a single aggregate gauge. W14 detects that *some* router went quiet by comparing counts, and its summary routes the operator to the version-distribution panel to identify which — but the alert itself cannot name it. | acceptable for now (household = ~1 router); revisit if the fleet grows |
| A router that disappeared entirely (one of N powered off, not the whole fleet) | No reference series survives it: the router leaves `router_ws_connections_active` and `agent_version` together, so W14's comparison stays balanced and C7 only covers fleet → 0. Would need an enrolled-router gauge off the `routers` table, which fires unresolvably for a decommissioned-but-undeleted row (see W14's rejected candidates). | unfiled; wants the enrollment-lifecycle question answered first (what marks a router retired?) |

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
   [#2416](https://github.com/wifihaven/wifihaven/issues/2416) with W6–W7, by
   [#2488](https://github.com/wifihaven/wifihaven/issues/2488) with W8, by
   [#2553](https://github.com/wifihaven/wifihaven/issues/2553) with W9, by
   [#2646](https://github.com/wifihaven/wifihaven/issues/2646) with W10, and by
   [#2477](https://github.com/wifihaven/wifihaven/issues/2477) with W11–W12, by
   [#2517](https://github.com/wifihaven/wifihaven/issues/2517) with W13, and by
   [#2646](https://github.com/wifihaven/wifihaven/issues/2646)'s follow-up with
   W14 (W10's absence arm).
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
