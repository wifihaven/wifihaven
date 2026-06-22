# Grafana Cloud stack

This was originally in AGENTS.md §"Grafana Cloud stack"; see AGENTS.md for the TOC.

## Grafana Cloud stack

The cloud metrics + dashboard stack lives at
**`https://wifihaven.grafana.net`** (free tier). It hosts app metrics
(pushed by `wifihaven-alloy`), Render infra metrics (native OTLP), and
deploy annotations (POSTed by `.github/workflows/master-api-ui.yml` via
`.github/actions/grafana-annotation`). Repo secrets driving the
annotation POSTs are `GRAFANA_CLOUD_URL` +
`GRAFANA_CLOUD_ANNOTATION_TOKEN`. Operator runbook in
[`docs/deploy-cloud.md`](../deploy-cloud.md) §11.

## App logs → Grafana Cloud Loki (#1873, epic #1831)

In **deployed environments only**, the API ships its application logs
straight to Grafana Cloud Loki's push API via the
[loki4j](https://loki4j.github.io/loki-logback-appender/) `loki-logback-appender`
configured in [`api/resources/logback.xml`](../../api/resources/logback.xml).
There is **no** table-tailing pipeline and **no** Alloy `loki.write` hop — the
JVM pushes directly.

- **Deployed-env gate.** The appender (and its root attachment) is wrapped in a
  logback `<if condition='isDefined("GRAFANA_CLOUD_LOKI_URL")'>` (janino-backed).
  Presence of the secret IS the gate: local dev and `mill __.test` never set it,
  so the appender is never instantiated there.
- **Fail-open.** Loki4jAppender is async by design (request thread only
  enqueues); a bounded `<sendQueueMaxBytes>` drops on backpressure and
  `<drainOnStop>false` keeps shutdown from blocking on Loki. Loki being
  slow/down can never wedge the request path. (Load-proof + a drop metric +
  panel are the #1831 follow-up, tracked in #1879.)
- **Label / cardinality model.** Loki **stream labels** are whitelisted to
  exactly `service` / `env` / `level`. Every other MDC key (`mac`, `route`,
  `op`, `status`, `etag`, …) rides Loki **structured metadata** via the
  `* = %%mdc` bulk pattern — same bounded-cardinality rule as metrics; `mac` as
  a label would be a per-device explosion and is forbidden.
- **Secrets.** `GRAFANA_CLOUD_LOKI_URL` (push API, `.../loki/api/v1/push`),
  `GRAFANA_CLOUD_LOKI_USER` (numeric instance id), `GRAFANA_CLOUD_LOKI_PASSWORD`
  (API token, logs-push scope) are Render-managed `sync:false` secrets on the
  staging + prod API web services in [`render.yaml`](../../render.yaml) —
  mirroring the `GRAFANA_CLOUD_PROM_*` pattern. `WIFIHAVEN_ENV` (`staging` /
  `production`) supplies the `env` label. Never committed.
