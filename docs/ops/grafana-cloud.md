# Grafana Cloud stack

This was originally in AGENTS.md §"Grafana Cloud stack"; see AGENTS.md for the TOC.

## Grafana Cloud stack

The cloud metrics + dashboard stack lives at
**`https://wifihaven.grafana.net`** (free tier). It hosts app metrics
(pushed by `wifihaven-alloy`), Render infra metrics (native OTLP),
deploy annotations (POSTed by `.github/workflows/master-api-ui.yml` via
`.github/actions/grafana-annotation`), and — as of #1852 — a Grafana
Cloud Logs (Loki) sink wired into the same `wifihaven-alloy` worker
(`loki.write` in `deploy/alloy/config.alloy`) for the log-export epic
(#1831). The Loki sink is the destination only; the table-tailer that
feeds it ships in #1854. Repo secrets driving the annotation POSTs are
`GRAFANA_CLOUD_URL` + `GRAFANA_CLOUD_ANNOTATION_TOKEN`; the Alloy
Render-side secrets (Prometheus `GRAFANA_CLOUD_PROM_*` and Loki
`GRAFANA_CLOUD_LOKI_*`) are documented in the operator runbook in
[`docs/deploy-cloud.md`](../deploy-cloud.md) §11.
