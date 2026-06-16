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
