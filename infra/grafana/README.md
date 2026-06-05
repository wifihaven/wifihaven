# Grafana Terraform — wifihaven

Declarative deploy of the in-repo Grafana dashboards to Grafana Cloud
(#1270, follow-up to #1209). The repo is the source of truth, `terraform
apply` reconciles a Grafana Cloud stack to match.

Manages:

- One `grafana_dashboard` per JSON under
  [`deploy/grafana/dashboards/`](../../deploy/grafana/dashboards/) —
  `api-health` and `rollup-health`. Each is sourced via
  `config_json = file(".../deploy/grafana/dashboards/<name>.json")`, so the
  committed JSON is canonical and there is no second copy to drift.

  These two cover **only the metrics actually emitted today** (#1242/#1243):
  the `zio.metrics.jvm.DefaultJvmMetrics` JVM/process series, the
  `wifihaven_db_pool_*` HikariCP gauges, and the `wifihaven_rollup_*` series.
  The remaining design-doc §5 dashboards are deferred until their metrics
  are instrumented — each ships in its own follow-up PR alongside the
  emitting code, so no dashboard is deployed against a series that does not
  exist:
  - router-fleet → [#1279](https://github.com/wifihaven/wifihaven/issues/1279)
    (blocked on [#1206](https://github.com/wifihaven/wifihaven/issues/1206) /
    [#1205](https://github.com/wifihaven/wifihaven/issues/1205))
  - dnsmasq-enforcement → [#1280](https://github.com/wifihaven/wifihaven/issues/1280)
    (blocked on [#1206](https://github.com/wifihaven/wifihaven/issues/1206) /
    [#705](https://github.com/wifihaven/wifihaven/issues/705))
  - data-quality-ingest → [#1281](https://github.com/wifihaven/wifihaven/issues/1281)
    (blocked on [#864](https://github.com/wifihaven/wifihaven/issues/864) /
    [#1204](https://github.com/wifihaven/wifihaven/issues/1204))
  - api-health HTTP request-rate/latency panels → folded back in with
    [#1204](https://github.com/wifihaven/wifihaven/issues/1204) (tracked in [#1281](https://github.com/wifihaven/wifihaven/issues/1281))

## In the CD pipeline

This config is applied to **one Grafana Cloud stack that serves every
environment**. It has its own deployment pipeline,
[`master-grafana.yml`](../../.github/workflows/master-grafana.yml) (the
`deploy-grafana` job), with a tight `paths:` scope — it fires **only** when a
dashboard JSON or its Terraform changes (`deploy/grafana/**`,
`infra/grafana/**`). It is deliberately *not* part of the API/SPA pipeline:
a dashboard change is independent of the API release cadence, so it neither
re-runs on every API/web push nor sits behind the `approve-production` gate.
A push to `main` that touches those paths applies straight through (the
change already passed PR review + the `grafana-terraform` lint job).

There is no staging/prod split because the dashboards are
environment-agnostic: each selects its data via the templated
`${datasource}` variable at view time, so the same JSON renders against
whichever Prometheus the viewer points it at. The target stack is selected
purely by the `grafana_url` / `grafana_auth` variables.

### Stateless by design

The CD job runs on an ephemeral runner with no persisted Terraform state, so
every apply starts empty. That is safe because each `grafana_dashboard` is
upserted by its stable `uid` (`overwrite = true`) — applying against an
empty state updates the existing dashboard rather than erroring on a
duplicate. We deliberately do **not** manage a `grafana_folder` resource (a
folder create would 409 on a fixed uid, or accumulate duplicate folders
across stateless runs). To organize the dashboards, pre-create a folder once
per stack and pass its uid via the optional `folder_uid` variable; when
unset the dashboards land in the stack's General folder. A consequence of
statelessness: a dashboard removed from the repo is not auto-deleted from
the stack — delete it by hand.

**Not managed here**: the Grafana Cloud stack itself (created once in the
dashboard), the Prometheus datasource (dashboards use a
templated `${datasource}` variable resolved at view time, so the same JSON
loads in both Grafana Cloud and a self-hosted provisioned Grafana, #1207),
and the GitHub Actions secrets.

## Alerting (#1368 — first rule of the #1381 strategy)

[`alerting.tf`](alerting.tf) adds the first **Grafana-managed alert**: a
router-metrics ingest failure (alert **C4** in
[`docs/design/alerting.md`](../../docs/design/alerting.md) §7.1) now pages the
operator instead of only being graphable. It reuses the exact success-ratio
PromQL from the `api-self-metrics` ingest panel (#1373/#1384), adds a
zero-traffic guard, fires `< 0.95 for 15m`, and routes it `critical`. It ships
with the shared plumbing the rest of the §7 catalog extends:

- `grafana_folder.alerts` — holds the rule group (a rule group requires a
  `folder_uid`).
- `grafana_contact_point` ×3 — `wifihaven-critical`, `wifihaven-warning`,
  `wifihaven-staging`, all **email** to the single household operator. Distinct
  resources so `critical` can later be re-pointed at a real pager with zero rule
  churn (§4 — no invented transport).
- `grafana_notification_policy` — the singleton root tree routing by
  `severity` / `env` (staging matched first → notify, never page).
- `grafana_rule_group.router_metrics_ingest` — C4 (prod, critical) + its
  staging twin (warning).

### Hard prerequisite: remote Terraform state (#1406)

**These alerting resources cannot be applied by the current stateless CD.**
Unlike `grafana_dashboard` (upserted by uid with `overwrite = true`), contact
points, the notification policy, the folder, and the rule group are stateful
with no upsert escape hatch — against the empty-every-run CD they 409 or
accumulate duplicates (the same reason `main.tf` refuses to manage a folder for
dashboards). So `infra/grafana` must first migrate onto an **HCP Terraform
remote backend** — filed as the blocking prerequisite
[#1406](https://github.com/wifihaven/wifihaven/issues/1406) (mirrors #1357 for
`infra/cloudflare`). See [`docs/design/alerting.md`](../../docs/design/alerting.md)
§3.1 and §9.

This PR is therefore **stacked on #1406**:

- It is CI-lint-clean today (`terraform fmt -check` + `validate -backend=false`
  in the `grafana-terraform` job), which gates the PR.
- The live `terraform apply` in `master-grafana.yml` must not run these rules
  until #1406 lands the `cloud {}` backend, the workflow wiring, and the
  operator's one-time HCP `grafana` workspace.

### New variables

- `operator_email` (**required**, no default) — recipient for all three contact
  points. In CD fed from an `OPERATOR_EMAIL` secret (wired with #1406); locally
  via `terraform.tfvars` / `TF_VAR_operator_email`. Marked `sensitive` to keep
  PII out of plan/apply logs.
- `prometheus_datasource_uid` (default `grafanacloud-prom`) — managed alert
  rules evaluate server-side and need a concrete datasource uid (dashboards
  resolve a templated variable at view time and don't). Override for a
  self-hosted stack.

## Prerequisites

1. A Grafana Cloud stack exists (free tier is fine; see design §6.2) — one
   stack serves every environment. Ours is
   [`wifihaven.grafana.net`](https://wifihaven.grafana.net).
2. A **Grafana service-account token** for the stack with dashboard write
   scope: Grafana Cloud → Administration → Service accounts → add token.
3. Terraform ≥ 1.6 installed (`brew install terraform`).

## CI / auto-deploy (the normal path)

Deploys run from the `master-grafana.yml` pipeline on push to `main` (see
[In the CD pipeline](#in-the-cd-pipeline) above). Its `paths:` filter is
scoped to `deploy/grafana/**` and `infra/grafana/**`, so a dashboard or
Terraform change triggers the pipeline — and nothing else does.
Authentication comes from two GitHub Actions secrets set out-of-band (never
committed):

```sh
gh secret set GRAFANA_URL  -R wifihaven/wifihaven --body 'https://wifihaven.grafana.net'
gh secret set GRAFANA_AUTH -R wifihaven/wifihaven --body '<service-account token>'
```

Until both are set, the deploy job is a no-op gate. The PR acceptance bar is
`terraform fmt`/`validate`-clean config plus `actionlint`-clean workflows,
not a live deploy.

`terraform fmt -check` + `terraform validate` for this directory also run in
CI as the `grafana-terraform` lint job (mirrors the `render-blueprint`
precedent).

## Manual apply (one environment)

```sh
cd infra/grafana

export TF_VAR_grafana_url=https://wifihaven.grafana.net
export TF_VAR_grafana_auth=<service-account token>
# optional: export TF_VAR_folder_uid=<pre-created folder uid>

terraform init
terraform plan
terraform apply
```

Or copy `terraform.tfvars.example` → `terraform.tfvars` (gitignored — both
values are secrets) and fill it in. Apply creates 2 dashboard resources.

## Subsequent changes

Edit a dashboard in Grafana → export JSON → overwrite the file under
`deploy/grafana/dashboards/` → commit. The push to `main` re-applies via the
CD pipeline. `overwrite = true` on each resource lets the apply replace a
hand-imported dashboard with the repo copy.
