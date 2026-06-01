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
environment**. It is wired into the Master API/UI CD pipeline
([`master-api-ui.yml`](../../.github/workflows/master-api-ui.yml)) as a
single job:

- `deploy-grafana` — applies the dashboards after the `approve-production`
  manual gate, alongside `deploy-prod-render` / `deploy-spa-prod`.

There is no staging/prod split because the dashboards are
environment-agnostic: each selects its data via the templated
`${datasource}` variable at view time, so the same JSON renders against
whichever Prometheus the viewer points it at. The target stack is selected
purely by the `grafana_url` / `grafana_auth` variables. The job is
**non-critical** (`continue-on-error`, design §6.2): a dashboard-deploy
failure is alert-worthy but never blocks the API/SPA release.

### Stateless by design

The CD jobs run on ephemeral runners with no persisted Terraform state, so
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

## Prerequisites

1. A Grafana Cloud stack exists (free tier is fine; see design §6.2) — one
   stack serves every environment. Ours is
   [`wifihaven.grafana.net`](https://wifihaven.grafana.net).
2. A **Grafana service-account token** for the stack with dashboard write
   scope: Grafana Cloud → Administration → Service accounts → add token.
3. Terraform ≥ 1.6 installed (`brew install terraform`).

## CI / auto-deploy (the normal path)

Deploys run from the Master API/UI CD pipeline on push to `main` (see
[In the CD pipeline](#in-the-cd-pipeline) above). The `master-api-ui.yml`
`paths:` filter already includes `infra/**` and `deploy/**`, so a dashboard
or Terraform change triggers the pipeline. Authentication comes from two
GitHub Actions secrets set out-of-band (never committed):

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
