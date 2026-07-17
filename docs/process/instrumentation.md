# Instrumentation — metrics and dashboards ship together

This was originally in AGENTS.md §"New functionality ships with metrics" and §"A new metric ships with its dashboard"; see AGENTS.md for the TOC.

## New functionality ships with metrics {#instrument-new-functionality}

**When you add a meaningful new code path — a route, a background job, a
periodic poller, an external call, an ingest/enforcement step — instrument it
with a metric in the same PR.** The architectural model puts all decision and
aggregation logic server-side in the API (the router is a dumb applier), so the
API process is the one place an operator can see what the system is doing. A
feature that emits no metric is invisible until it breaks.

What "meaningful" means — instrument it when at least one is true:

- It can **fail or be rejected** in a way an operator would want to rate-alert
  on (auth/validation failures, dropped/filtered records, ret-exhausted calls).
  Emit a `*_total{reason}` counter with a **bounded** reason enum.
- It does **work whose latency or volume matters** (a DB query on a
  growth table, a rollup, an external fetch, a request handler). Emit a
  `*_duration_seconds` histogram and/or a throughput counter.
- It reflects **fleet/system state** an operator would check first during an
  incident (connected routers, queue depth, last-success timestamp). Emit a
  gauge.

Rules of thumb:

- **Route the emission through `AppMetrics` / `MetricGuard`** (see
  `api/src/metrics/Metrics.scala`), not a bare `Metric.*` at the call site, so
  the §4 cardinality firewall and the name/label allowlist apply. Add the new
  `(name -> allowed keys)` entry to `MetricGuard.Allowed`.
- **Labels are a small, known enum** — `route` (templated), `op`, `reason`,
  `status`, `job`. **Never** a per-mac / per-domain / per-device / per-ip /
  per-user value; those are forbidden keys and will be rejected.
- **Don't over-instrument.** A pure helper, a trivial getter, or a path already
  covered by the HTTP/DB middleware doesn't need its own series. One good
  counter or histogram beats five redundant ones, and every series costs
  cardinality.
- **A feature gated on config must not go dark when that config is absent.** The
  whole point of instrumenting a new path is that it's *visible*; a path that
  silently no-ops when its secret is unset is invisible in exactly the way this
  rule fights. If the path depends on a secret, that config **fails loud** when
  it's missing (or is an explicit, logged, health-surfaced optional-off state)
  — not a no-op that emits nothing. See
  [`no-dark-by-default.md`](no-dark-by-default.md).
- A new metric then **ships with its dashboard panel** — see the next rule.

## A new metric ships with its dashboard {#metrics-need-a-dashboard}

**A PR that adds or changes an emitted metric series must also add or update
a Grafana panel that consumes it, in the same PR.** A metric nobody can see
is dead weight: it costs cardinality and registry space but never reaches an
operator's eyes until an incident, which is exactly when you don't want to be
authoring PromQL from scratch.

"Emitted metric series" means any new `Metric.counter` / `histogram` / `gauge`
(or `AppMetrics`/`MetricGuard` helper) whose name reaches the `/metrics`
exposition. Adding a label to an existing series counts too if it changes what
an operator would want to slice by.

In the same PR:

1. **Add the panel where it belongs.** Dashboards are checked-in JSON under
   [`deploy/grafana/dashboards/`](../../deploy/grafana/dashboards/), deployed by
   [`master-grafana.yml`](../../.github/workflows/master-grafana.yml) via the
   [`infra/grafana`](../../infra/grafana/) Terraform. Extend an existing dashboard
   when the metric fits its theme (process health vs. application
   self-metrics vs. rollup health); add a new `*.json` and register it in
   `infra/grafana/main.tf`'s `dashboards` list when it's a new concern.
2. **Target the series you actually emit — never a design-doc catalog.** Grep
   `api/src` for the exact metric name and labels and write the PromQL against
   that. Histograms render as `<name>_bucket{le=…}` / `_sum` / `_count` (use
   `histogram_quantile`); the zio-prometheus connector does **not** append
   `_total` to counters, so the name in code is the name in the query. Do not
   ship no-data panels for metrics that aren't emitted yet — defer those to
   the follow-up PR that instruments them.
3. **Keep labels low-cardinality in the query, too.** Slice only by bounded
   label keys (templated `route`, `op`, `reason`, `status`); never by a
   per-mac / per-domain / per-device / per-ip value. If the firewall would
   reject the label, the panel shouldn't group by it.
4. **The CI gate is `grafana-terraform`** ([`ci.yml`](../../.github/workflows/ci.yml)):
   `terraform fmt -check`, `terraform validate`, and `python3 -m json.tool`
   on every dashboard. Run all three locally before pushing.
