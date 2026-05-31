# Metrics & observability architecture (Prometheus / Grafana)

Status: **plan accepted, implementation pending.** This document is the
output of the planning work in
[#471](https://github.com/wifihaven/wifihaven/issues/471). It resolves the
open questions raised there and is the contract the implementation
sub-issues build against. Implementation lands under those sub-issues, not
under #471 (which stays open as the umbrella).

The goal is **operational** observability: surface performance and
behaviour regressions automatically (latency creep, error-rate spikes,
dnsmasq restart storms, routers going dark) instead of discovering them
through a manual UI audit. This is not product analytics and not
distributed tracing (the latter is explicitly deferred).

---

## 1. Architecture at a glance

```
 OpenWRT / OPNsense                    API server (Scala 3 / ZIO)              Collector + storage            Dashboards
 router agent                          single JVM, :8080
 ┌───────────────────┐                 ┌────────────────────────────┐
 │ in-process meters │  POST           │  /api/router/metrics       │
 │ (counters/gauges) │ ───────────────►│  (agent bearer auth)       │
 │ batched ~60 s     │  agent bearer   │      │                     │
 └───────────────────┘                 │      ▼                     │
                                        │  RouterMetricsService      │
                                        │  → ZIO Metric API          │
                                        │                            │
                                        │  API self-metrics ─────────┤
                                        │  (http / db / auth / jvm)  │
                                        │      │                     │
                                        │      ▼                     │     scrape /metrics
                                        │  GET /metrics  ◄───────────┼──────────────────────┐
                                        │  (scrape-token auth,       │                       │
                                        │   Prometheus text format)  │              ┌────────┴────────┐         ┌──────────┐
                                        └────────────────────────────┘              │   Prometheus    │ ──────► │ Grafana  │
                                                                                     │  (self-hosted)  │         │ (dashbd) │
                                                                                     │       or        │         └──────────┘
                                                                                     │  Alloy → Grafana│
                                                                                     │  Cloud (cloud)  │
                                                                                     └─────────────────┘
```

Two flows converge on the API's `/metrics` endpoint:

1. **Router-sourced metrics** — routers sit behind NAT and cannot be
   scraped, so they **push**. The agent maintains in-process counters and
   posts a small batch to a dedicated endpoint. The API folds each batch
   into ZIO `Metric` instruments labelled by `router_id` (+
   `installation_id` once that concept lands). This re-exposes the
   router-sourced numbers on the same `/metrics` surface the scraper
   already reads — the scraper never talks to a router.
2. **API self-metrics** — recorded directly in-process from HTTP
   middleware, the DB layer, the auth path, and the JVM/ZIO runtime.

A single Prometheus-format `/metrics` endpoint exposes both. How that
endpoint gets scraped differs by deployment (§6).

This matches the architectural invariant in `AGENTS.md`: **the router is a
dumb applier and the API server is the brain.** Routers emit raw event
counters; all aggregation, labelling, and storage decisions are made
server-side. No router ever knows what a Prometheus series is.

---

## 2. Client library decision — `zio-metrics-connectors`

**Decision: use ZIO's `zio-metrics-connectors` with the Prometheus
backend.** Reject Micrometer / Prometheus `simpleclient`.

The API is **Scala 3 + ZIO 2** (ZIO HTTP, Doobie, Mill) — not a Go or
Node runtime as the original #471 sketch assumed. The client choice must
be JVM- and ZIO-native.

| | `zio-metrics-connectors` (Prometheus backend) | Micrometer / `simpleclient` |
|---|---|---|
| Integration | Records through the built-in ZIO `Metric` API; instruments are effectful values that compose with `ZIO` | Foreign registry; must bridge every measurement out of the effect world by hand |
| Boilerplate | Low — `Metric.counter(...).fromConst`, `@@` aspects on effects | Higher — manual registry wiring, manual label set construction, threading concerns |
| Exposition | Connector renders the ZIO metric registry to Prometheus text format; mount as a normal zio-http route | Mature exposition, but needs a servlet/handler adapter into zio-http |
| Maturity | Younger, smaller surface, but covers counter/gauge/histogram/summary — everything we need | Very mature, huge ecosystem of pre-built binders (JVM, Hikari, etc.) |
| JVM/runtime metrics | `zio.metrics.jvm.DefaultJvmMetrics` ships JVM collectors (heap, GC, threads, classloading) wired as a `ZLayer` | Micrometer's `JvmMemoryMetrics` etc. are richer/more battle-tested |

**Trade-off:** Micrometer is the more mature, more featureful library with
a larger binder ecosystem (notably first-class Hikari connection-pool
binders, which we'd otherwise instrument by hand). Its cost is that every
measurement has to be marshalled out of `ZIO[...]` into a foreign mutable
registry, which fights the codebase's "always `ZIO[R, E, A]`, no global
mutable state, wire via `ZLayer`" conventions and adds threading
boilerplate at every call site.

`zio-metrics-connectors` keeps measurement inside the effect system: an
instrument is a value, recording is an effect, and the registry is
provided as a layer like everything else. For a single-JVM service whose
metric set is small and well-bounded (§5), the idiomatic-fit win
outweighs Micrometer's maturity. We accept hand-instrumenting the Hikari
pool (a handful of gauges) as the price.

Add to `build.mill` under the `api` module:

```scala
mvn"dev.zio::zio-metrics-connectors:<latest-2.x>",
mvn"dev.zio::zio-metrics-connectors-prometheus:<latest-2.x>",
```

JVM/ZIO runtime metrics come from `zio.metrics.jvm.DefaultJvmMetrics.live`
provided into `serverEnv` in `api/src/Main.scala`.

---

## 3. Router → API push transport

### 3.1 Endpoint shape — dedicated `POST /api/router/metrics`

**Decision: a dedicated `POST /api/router/metrics` endpoint, not
piggybacking on the policy-poll response or the usage POST.**

Rationale:

- **Separation of concerns / blast radius.** Usage and events bodies
  already brush against zio-http's request-size ceiling — `POST
  /api/router/usage` blew past the old 100 KiB cap in
  [#1017](https://github.com/wifihaven/wifihaven/issues/1017) and the same
  ceiling is starting to bite `POST /api/router/events`. Folding metrics
  into those bodies couples an observability concern to an already-strained
  data path; a metrics batch that fails to parse should never risk a usage
  bucket. Keep them independent.
- **The policy poll is a GET.** Smuggling a metrics body into the
  ETag-conditional `GET /api/router/policy` would turn a cache-friendly
  conditional GET into a side-effecting request — exactly the kind of
  thing the 304-fast-path ([#414](https://github.com/wifihaven/wifihaven/issues/414))
  exists to keep cheap.
- **Cadence independence.** Metrics want their own batching window (§3.3)
  decoupled from both the ~5 s policy poll and the ~60 s usage report.

The endpoint lives under `/api/router/*` alongside the existing agent
surface and reuses `RouterAuth` (§3.4).

### 3.2 Wire schema

Counters are reported as **monotonic cumulative values** (the agent's
in-process running total since agent start), gauges as **instantaneous
values**, and histograms as **bucketed observation counts**. The server
translates cumulative counters into the ZIO metric registry idempotently
(see §3.5), so duplicate or retried batches do not double-count.

```jsonc
POST /api/router/metrics
Authorization: Bearer rt_a7d12b...
Content-Type: application/json

{
  "routerId": "9c1f2e8a-...",
  "agentVersion": "0.3.1",
  "agentStartedAt": "2026-05-30T09:00:00Z",   // lets server detect agent restart → counter reset
  "sampledAt": "2026-05-30T14:01:00Z",
  "counters": [
    { "name": "dnsmasq_restarts_total", "labels": {"reason": "policy_change"}, "value": 12 },
    { "name": "dnsmasq_restarts_total", "labels": {"reason": "boot"},          "value": 1  },
    { "name": "policy_apply_total",     "labels": {"result": "ok"},            "value": 880 },
    { "name": "policy_apply_total",     "labels": {"result": "nft_failed"},    "value": 3  },
    { "name": "snapshot_poll_total",    "labels": {"result": "304"},           "value": 9300 },
    { "name": "snapshot_poll_total",    "labels": {"result": "200"},           "value": 140 },
    { "name": "snapshot_poll_total",    "labels": {"result": "error"},         "value": 6  }
  ],
  "gauges": [
    { "name": "agent_uptime_seconds", "labels": {}, "value": 18060 }
  ],
  "histograms": [
    { "name": "policy_apply_duration_seconds", "labels": {},
      "buckets": [ {"le": "0.05", "count": 700}, {"le": "0.1", "count": 850},
                   {"le": "0.5", "count": 880}, {"le": "+Inf", "count": 883} ],
      "sum": 41.2, "count": 883 },
    { "name": "snapshot_poll_duration_seconds", "labels": {},
      "buckets": [ {"le": "0.05", "count": 9100}, {"le": "0.1", "count": 9300},
                   {"le": "0.5", "count": 9440}, {"le": "+Inf", "count": 9446} ],
      "sum": 612.5, "count": 9446 }
  ]
}
```

Response `200` with empty body. `400` on malformed batch (logged, not
retried — a bad metrics batch is not worth a retry storm). `401` on bad
token.

Notes:

- The `routerId` in the body is cross-checked against the token's router
  (same pattern as the other ingest endpoints).
- `agentStartedAt` is the counter-reset sentinel: if it changes between
  batches, the server knows the agent restarted and the cumulative
  counters reset to zero, so it re-bases rather than recording a negative
  delta.
- Metric **names** are an allowlisted enum on the server (§5). An unknown
  metric name in a batch is dropped with a counted warning, never
  auto-registered — this is the cardinality firewall (§4).

### 3.3 Batching cadence

**Every 60 s**, on its own timer — not coupled to the ~5 s policy poll.

- The policy poll is intentionally hot (5 s) for low policy-apply latency;
  shipping a metrics body 12×/minute is pure waste for data that is
  graphed at minute resolution.
- 60 s matches the existing usage-report cadence operators already reason
  about, and keeps the batch small (a few dozen counter lines).
- On POST failure the agent **retains** its in-process counters (they are
  cumulative, not reset-on-send) and folds the next interval's numbers in;
  a missed batch self-heals on the next success with no data loss, because
  the server re-bases off the cumulative value. This is strictly simpler
  than the usage path's reset-on-ack model and is the right call for
  best-effort observability data.

Make the interval a UCI config knob (`metrics_report_interval`, default
`60`), mirroring `usage_report_interval`.

### 3.4 Auth

**Reuse the existing per-router bearer token** (`RouterAuth`). No new
credential. The metrics endpoint sits behind the same middleware as
`/api/router/usage` and `/api/router/events`; an unauthenticated or
wrong-router token gets `401`. This is the obvious choice — the agent
already holds exactly the right credential and the metrics channel has the
same trust boundary as the data channel.

### 3.5 Forward-compatibility with the websocket migration (#1023)

[#1023](https://github.com/wifihaven/wifihaven/issues/1023) migrates the
router↔API transport from three HTTP endpoints to one persistent
websocket, where each frame carries an `op` discriminator naming the REST
payload it replaces (`{"op":"usage", ...}`, `{"op":"events", ...}`,
`{"op":"policy", ...}`). Its explicit design principle is **the JSON
payloads do not change — only the carrier does.**

**Sequencing: the REST `POST /api/router/metrics` endpoint lands FIRST,
before #1023.** Reasons:

- #1023 is a large, multi-stage transport migration; observability is
  needed *now* to watch the fleet (and, usefully, to watch the #1023
  rollout itself).
- The metrics payload in §3.2 is deliberately shaped as a standalone JSON
  document with no dependence on HTTP framing, so when #1023 lands it
  rehomes verbatim as a new `{"op":"metrics", "payload": <§3.2 body>}`
  frame. The server demuxes `op:"metrics"` into the *same*
  `RouterMetricsService` handler the REST route calls. No payload change,
  no re-design — exactly the migration story #1023 already commits to for
  usage/events/policy.

So: build the REST endpoint and the `RouterMetricsService` handler such
that the handler is carrier-agnostic (takes a parsed batch, not a
`Request`). The REST route is a thin adapter today; the WS frame
dispatcher becomes a second thin adapter when #1023 lands. **Explicitly
add `op:"metrics"` to #1023's frame enum scope** so the two efforts stay
aligned.

---

## 4. Cardinality budget

Prometheus cost is dominated by **active series count**, which is the
product of every label's value-set size. An unbounded label (one whose
values come from user/device/domain identity) makes series count grow
without limit and will eventually OOM Prometheus. This is the single most
important rule in the whole plan.

### 4.1 Hard rule

> **A label may only be added to a metric if its set of possible values is
> bounded, small, and known at code-write time.** If a label's cardinality
> grows with the number of users, devices, domains, flows, or requests
> seen, it is forbidden as a metric label. That data belongs in structured
> logs, `traffic_reports`, or `connection_events` — never in a metric
> dimension.**

Enforced in two places:

1. **Server-side allowlist.** Metric names *and their permitted label
   keys* are an enum in the API. The `/api/router/metrics` handler and the
   self-metric call sites can only emit allowlisted (name, label-keys)
   pairs; anything else is dropped and counted
   (`metrics_rejected_total{reason="unknown_name"|"forbidden_label"}`).
2. **Review gate.** The cardinality + retention review sub-issue is a
   required gate before the first prod scrape (§9).

### 4.2 Safe labels (allowed)

| Label | Bound | Used on |
|-------|-------|---------|
| `router_id` | fleet size (tens, maybe low hundreds long-term) | all router-sourced metrics |
| `installation_id` | number of installs (small) | all metrics, once the concept lands |
| `reason` | fixed enum (`policy_change`, `boot`, `manual`) | `dnsmasq_restarts_total` |
| `result` | fixed enum (`ok`, `write_failed`, `nft_failed`, `smoke_warn`; or `200`/`304`/`error`) | `policy_apply_total`, `snapshot_poll_total`, `dns_queries_total` |
| `route` | fixed set of API route templates (~40), **templated** (`/api/devices/:id`, never the concrete id) | `http_requests_total`, `http_request_duration_seconds` |
| `method` | HTTP verbs (~5) | `http_requests_total` |
| `status` | HTTP status classes/codes (~15) | `http_requests_total` |
| `op` | fixed enum of DB operation names (~30, hand-named) | `db_query_duration_seconds` |
| `version` | agent version strings (small, slow-moving) | `agent_version` info gauge |

### 4.3 Forbidden labels (never)

| Label | Why |
|-------|-----|
| `mac` / `device_id` | one series per device — unbounded; identity data |
| `domain` / `host` / `hostname` | one series per domain visited — explosively unbounded |
| `ip` / `dst_ip` | unbounded |
| `user_id` / `profile_id` | grows with households/profiles; identity data |
| raw `path` (concrete URL with ids) | unbounded — always template to `route` instead |
| `query` / free-text reasons | unbounded |

Per-device and per-domain questions are answered from the database and the
admin UI, **not** from Prometheus. This is a non-goal of the metrics
stack, consistent with #471's stated non-goals.

---

## 5. Finalized initial metric & label catalog

This supersedes the starter list in #471. Histogram bucket boundaries are
suggestions to be tuned during implementation.

### 5.1 Router-sourced (pushed via §3, re-exposed server-side, all labelled `router_id` + `installation_id`)

| Metric | Type | Extra labels | Notes |
|--------|------|--------------|-------|
| `dnsmasq_restarts_total` | counter | `reason` ∈ {`policy_change`, `boot`, `manual`} | The motivating metric. dnsmasq is **restarted, not reloaded**, on every conf-fragment change because SIGHUP doesn't re-read `conf-dir` ([#341](https://github.com/wifihaven/wifihaven/issues/341), fixes #328). #341's discussion suspected restarts fire more often than the PR claimed; #414 added the byte-for-byte short-circuit so most applies are nft-only. This counter is how we *measure* the real-world restart cadence and confirm #414 is doing its job. |
| `policy_apply_total` | counter | `result` ∈ {`ok`, `write_failed`, `nft_failed`, `smoke_warn`} | One increment per policy timer apply. `smoke_warn` ties to #341's post-restart `dig` smoke probe. |
| `policy_apply_duration_seconds` | histogram | — | Snapshot-fetch-to-ruleset-loaded wall time. Buckets ~ `0.01,0.05,0.1,0.5,1,5`. |
| `snapshot_poll_total` | counter | `result` ∈ {`200`, `304`, `error`} | Per policy poll. `304` should dominate (ETag fast-path); a `200` spike means policy churn, an `error` spike means the link is degraded. |
| `snapshot_poll_duration_seconds` | histogram | — | Round-trip incl. the 304-not-modified path. |
| `agent_uptime_seconds` | gauge | — | Resets to ~0 on agent restart; pairs with `agentStartedAt` reset detection. |
| `agent_version` | gauge (info, value `1`) | `version` | One series per (router, version); flips when an agent upgrades. Drives a fleet-version panel. |
| `dns_queries_total` | counter | `result` ∈ {`resolved`, `nxdomain`, `blocked_at_nft`, `served_local`} | Folded from the existing `dns_log.lua` event stream. **Cardinality-gated:** `result` only — never `domain`. Include only if the agent can emit it cheaply; otherwise defer. |
| `blocklist_fetch_failures_total` | counter | `status` (HTTP code, bounded enum) | Candidate counter from [#705](https://github.com/wifihaven/wifihaven/issues/705): blocklist fetches were failing `401` (Render→Cloudflare) but the agent logged an empty `status=`. A bounded `status`-labelled counter turns that silent class of failure into a graph. Land #705's status-capture fix and this counter together. |

### 5.2 API self-metrics

| Metric | Type | Labels | Notes |
|--------|------|--------|-------|
| `http_requests_total` | counter | `route` (templated), `method`, `status` | From a zio-http middleware wrapping `allRoutes`. |
| `http_request_duration_seconds` | histogram | `route`, `method` | Latency SLO tracking; p50/p95/p99 derived in Grafana. Buckets ~ `0.005,0.01,0.025,0.05,0.1,0.25,0.5,1,2.5,5`. |
| `agent_connected_routers` | gauge | — | Count of routers with a metrics/usage push (or WS connection post-#1023) in the last N minutes. The single "is the fleet alive?" number whose absence #1023 calls out as a current gap. |
| `db_query_duration_seconds` | histogram | `op` (hand-named, ~30) | Wrap the Doobie transact layer; `op` is an explicit constant per repo method, never the SQL text. |
| `auth_failures_total` | counter | `reason` ∈ {`bad_password`, `expired_token`, `bad_router_token`, `forbidden_role`} | Security signal. |
| `traffic_reports_filtered_zero_bytes_total` | counter | — | Directly closes the metric ask in [#864](https://github.com/wifihaven/wifihaven/issues/864): count `traffic_reports` rows dropped as `bytesIn=bytesOut=activeSeconds=0` in `UsageTraffic.cleanRows`. Replaces the per-request warn-log + TODO marker added for #846. Lets a silent return of the #858 agent regression show up as a rising rate. |
| JVM / ZIO runtime | gauges/counters | (collector-defined) | From `DefaultJvmMetrics.live`: heap/non-heap, GC count+time, thread count, classes loaded, plus ZIO fiber/executor metrics. Standard process collectors. |

### 5.3 Derived (Grafana panels, no new series)

- Per-endpoint success rate (`5xx` ratio) and p50/p95/p99 latency.
- Router fleet last-seen heatmap (from `agent_uptime_seconds` /
  `agent_connected_routers`).
- **dnsmasq restart rate per router** sparkline — the direct callback to
  the #341 motivating discussion.
- `snapshot_poll_total` 304-vs-200 ratio (validates #414).
- Agent-version distribution across the fleet.

---

## 6. Where Prometheus + Grafana run

The deployment must respect the self-hosted-vs-cloud split (`AGENTS.md`,
[spa-hosting](deploy-cloud.md)). The API's job is identical in both: expose
one authenticated `/metrics` endpoint. **What scrapes it differs.**

### 6.1 Self-hosted — compose overlay

**Decision: a `deploy/docker-compose.metrics.yml` overlay** layered on top
of `docker-compose.prod.yml`:

```
docker compose \
  -f deploy/docker-compose.prod.yml \
  -f deploy/docker-compose.metrics.yml \
  --env-file deploy/.env up -d
```

The overlay adds two services on the existing internal compose network:

- **`prometheus`** (`prom/prometheus`) — scrapes `http://api:8080/metrics`
  over the internal network (no published host port for `/metrics`),
  persistent volume `promdata`, 90-day local retention (§7), config
  checked into `deploy/prometheus/prometheus.yml`.
- **`grafana`** (`grafana/grafana`) — Prometheus pre-provisioned as a
  datasource and dashboards loaded from
  `deploy/grafana/provisioning/` + `deploy/grafana/dashboards/*.json`
  (versioned in-repo, §8). Published on a host port (operator-local LAN
  only); admin password from `.env`.

Opt-in: an operator who doesn't want the stack simply doesn't add the
overlay. All config is declarative in-repo, consistent with the project's
"repo is the source of truth, not dashboard clicks" preference.

### 6.2 Cloud / Render — Grafana Alloy → Grafana Cloud

Render image-runtime services can't host a co-located persistent
Prometheus cleanly, and `/metrics` must **not** be exposed on the public
custom domain. The cloud answer is a hosted backend reached by a small
collector.

**Decision:**

- **Backend: Grafana Cloud free tier** (hosted Prometheus + hosted
  Grafana; the free tier's series/retention limits comfortably fit our
  ~4k-series budget, §7). No Prometheus server to operate on Render.
- **Collector: a Grafana Alloy (Prometheus agent-mode) service on
  Render**, declared in `render.yaml`, that scrapes the API's
  **internal** Render address (`http://wifihaven-api-prod:8080/metrics`,
  not the public `api.wifihaven.net`) and `remote_write`s to the Grafana
  Cloud Prometheus endpoint. The Grafana Cloud credentials are Render env
  vars / secrets (declared, value set in dashboard — same pattern as the
  existing secrets).

Why a collector rather than the API push-ing `remote_write` itself:
`zio-metrics-connectors` exposes a pull `/metrics` endpoint, not a
`remote_write` client. A 30-line Alloy config is far less code than
building a `remote_write` pusher into the JVM, and it keeps the API's only
metrics responsibility "expose `/metrics`" — identical to self-hosted.
This is the portability win: **the API binary is environment-agnostic; only
the collector layer differs** (local Prometheus vs. Alloy→Grafana Cloud).

This work is declarative: `render.yaml` gains the Alloy service,
`deploy/alloy/config.alloy` is checked in. Dashboards are the same JSON as
self-hosted, imported into Grafana Cloud.

> Note: the API and SPA already roll back independently in cloud (Render
> vs. Cloudflare). The metrics collector is a third independent surface —
> a metrics outage must never affect API availability, so the Alloy
> service is non-critical and its failure is alert-worthy but not
> deploy-blocking.

---

## 7. `/metrics` auth, retention, and sizing

### 7.1 `/metrics` authentication — scrape bearer token

**Decision: a static scrape bearer token from config**
(`WIFIHAVEN_METRICS_TOKEN`), checked by middleware on `GET /metrics`.

Rejected alternatives:

- **Behind the admin JWT gate** — Prometheus/Alloy can't perform an
  interactive JWT login flow; they send a static credential. JWT is the
  wrong shape for a scraper. Rejected.
- **IP allowlist only** — Render egress/internal IPs aren't a stable,
  documentable allowlist, and self-hosted is already network-isolated.
  Brittle as the *primary* control. Rejected as sole mechanism.

The chosen scheme works identically in both environments: Prometheus
(self-hosted) and Alloy (cloud) send `Authorization: Bearer
<WIFIHAVEN_METRICS_TOKEN>`. Defense in depth: self-hosted also keeps
`/metrics` off any published host port (internal compose network only);
cloud scrapes the internal Render address, never the public domain. So
even though the token is the gate, the endpoint isn't casually reachable
from the public internet in either deployment.

The token is distinct from the JWT secret and from router tokens; set per
environment, never committed (same handling as other secrets, §
`docs/deploy-cloud.md`).

### 7.2 Retention & storage sizing

Sizing from the §5 catalog and a conservative fleet assumption.

**Active series estimate (generous):**

- Router-sourced: ~45 series/router × ~50 routers ≈ **2,300**.
- API self: http (route×method×status) + duration histograms + db
  histograms + auth + jvm/zio ≈ **1,300**.
- **Total ≈ 3,500–4,000 active series.** Small.

**Disk (self-hosted Prometheus):** Prometheus stores ≈1.5–2 bytes per
sample compressed. At 15 s scrape interval (4 samples/series/min):

```
4,000 series × 4 samples/min × 60 min × 24 h ≈ 23 M samples/day
23 M × ~2 bytes ≈ ~46 MB/day  →  ~90-day retention ≈ ~4–5 GB
```

**Decisions:**

- **Scrape interval: 15 s** (self-hosted Prometheus; Alloy on cloud).
- **Self-hosted local retention: 90 days** (`--storage.tsdb.retention.time=90d`),
  ≈5 GB on the `promdata` volume — provision 10 GB for headroom.
- **Cloud: Grafana Cloud free tier retention** (currently ~14 days on the
  free tier; sufficient for operational regression-spotting). If longer
  history is wanted later, that's a paid-tier or self-hosted-remote-write
  decision, out of scope here.
- No downsampling/recording rules at this volume; revisit only if series
  count grows an order of magnitude (e.g. if `installation_id` fans out to
  hundreds of installs).

---

## 8. Dashboards

Grafana dashboards are **versioned JSON in-repo** under
`deploy/grafana/dashboards/`, provisioned automatically self-hosted and
imported to Grafana Cloud. Initial set:

1. **API health** — request rate, error ratio, p50/p95/p99 latency per
   route, JVM heap + GC.
2. **Router fleet** — last-seen heatmap, agent-version distribution,
   per-router `snapshot_poll` 304/200/error mix, `agent_connected_routers`.
3. **dnsmasq / enforcement** — `dnsmasq_restarts_total` rate per router
   (the #341 sparkline), `policy_apply` result mix + duration,
   `blocklist_fetch_failures_total` by status.
4. **Data quality / ingest** — `traffic_reports_filtered_zero_bytes_total`
   rate (#864), usage/events ingest health.

Dashboards are edited in Grafana, then exported and committed — the repo
copy is canonical (dashboard clicks are reproducible from JSON, matching
the declarative-config preference).

---

## 9. Cardinality + retention review gate

**Before the first prod scrape**, a review sub-issue (§10) gates go-live:

- Confirm every emitted metric's label set against the §4 allowlist;
  confirm no `mac`/`domain`/`device_id`/`ip` leaked in as a label.
- Confirm `route` is templated (no raw ids) and `op` is a bounded enum.
- Confirm the server-side allowlist drops + counts unknown
  names/forbidden labels (`metrics_rejected_total`).
- Confirm retention settings (§7) are applied and disk is provisioned.
- Sanity-check actual active-series count in a staging scrape against the
  §7 estimate before pointing prod at it.

---

## 10. Implementation sub-issues

Filed under #471 (linked back as the umbrella). Each is independently
implementable; dependencies noted. TDD per `AGENTS.md` applies to every
code-bearing issue (feature tests first; for Scala, the embedded-Postgres
feature-test stack; for the Lua agent, `busted` unit tests with injected
`get_fn`/`exec_fn`).

1. **API `/metrics` endpoint + `zio-metrics-connectors` wiring + API
   self-metrics.** Library deps, registry layer, `DefaultJvmMetrics`,
   `GET /metrics` (Prometheus text format) behind the scrape-token
   middleware, http/db/auth instrumentation, `traffic_reports_filtered_zero_bytes_total`
   (#864). _Depends on: nothing. Gate for: everything else._
2. **Router → API metrics push transport.** `POST /api/router/metrics`
   (schema §3.2), `RouterAuth` reuse, carrier-agnostic
   `RouterMetricsService` that folds cumulative batches into the registry
   with restart re-basing. _Depends on: #1. Forward-compat: #1023._
3. **Router agent counters.** In-process counters + 60 s push timer in the
   Lua agent: `dnsmasq_restarts_total` (#341), `policy_apply_*`,
   `snapshot_poll_*`, `agent_uptime_seconds`, `agent_version`; fold
   `blocklist_fetch_failures_total` together with the #705 status-capture
   fix; `dns_queries_total` if cheap. _Depends on: #2._
4. **Prometheus + Grafana — self-hosted compose overlay.**
   `deploy/docker-compose.metrics.yml`, `deploy/prometheus/prometheus.yml`,
   Grafana provisioning. _Depends on: #1._
5. **Cloud/Render metrics path.** Grafana Alloy service in `render.yaml`
   scraping the internal API address → Grafana Cloud `remote_write`;
   `deploy/alloy/config.alloy`; Grafana Cloud secrets. _Depends on: #1._
6. **Initial Grafana dashboards (versioned JSON).** The four dashboards in
   §8 under `deploy/grafana/dashboards/`. _Depends on: #1 (and #3 for
   router panels to populate)._
7. **Cardinality + retention review gate.** §9 checklist; required before
   first prod scrape. _Depends on: #1–#6._
```
