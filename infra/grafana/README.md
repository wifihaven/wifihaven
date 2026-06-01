# Grafana Terraform — wifihaven

Declarative deploy of the in-repo Grafana dashboards to Grafana Cloud
(#1270, follow-up to #1209). The repo is the source of truth, `terraform
apply` reconciles a Grafana Cloud stack to match.

Manages:

- One `grafana_dashboard` per JSON under
  [`deploy/grafana/dashboards/`](../../deploy/grafana/dashboards/) —
  `api-health`, `router-fleet`, `dnsmasq-enforcement`,
  `data-quality-ingest`. Each is sourced via
  `config_json = file(".../deploy/grafana/dashboards/<name>.json")`, so the
  committed JSON is canonical and there is no second copy to drift.

## Per-environment, in the CD pipeline

This config is applied **once per Grafana Cloud stack**. It is wired into
the Master API/UI CD pipeline
([`master-api-ui.yml`](../../.github/workflows/master-api-ui.yml)),
mirroring the SPA's staging-then-prod split:

- `deploy-grafana-staging` — applies to the **staging** stack in parallel
  with `deploy-staging` / `deploy-spa-staging`, on the same test gate.
- `deploy-grafana-prod` — applies to the **prod** stack after the
  `approve-production` manual gate, alongside `deploy-prod-render` /
  `deploy-spa-prod`.

So staging dashboards ship with the staging deploy and prod dashboards ship
with the prod deploy. The target stack is selected purely by the
`grafana_url` / `grafana_auth` variables, fed from per-environment secrets.
Both jobs are **non-critical** (`continue-on-error`, design §6.2): a
dashboard-deploy failure is alert-worthy but never blocks the API/SPA
release.

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

**Not managed here**: the Grafana Cloud stacks themselves (created once per
environment in the dashboard), the Prometheus datasource (dashboards use a
templated `${datasource}` variable resolved at view time, so the same JSON
loads in both Grafana Cloud and a self-hosted provisioned Grafana, #1207),
and the GitHub Actions secrets.

## Prerequisites

1. A Grafana Cloud stack exists per environment (free tier is fine; see
   design §6.2) — one for staging, one for prod.
2. A **Grafana service-account token** per stack with dashboard write scope:
   Grafana Cloud → Administration → Service accounts → add token.
3. Terraform ≥ 1.6 installed (`brew install terraform`).

## CI / auto-deploy (the normal path)

Deploys run from the Master API/UI CD pipeline on push to `main` (see
[Per-environment, in the CD pipeline](#per-environment-in-the-cd-pipeline)
above). The `master-api-ui.yml` `paths:` filter already includes
`infra/**` and `deploy/**`, so a dashboard or Terraform change triggers the
pipeline. Authentication comes from four GitHub Actions secrets set
out-of-band (never committed):

```sh
gh secret set GRAFANA_URL_STAGING  -R wifihaven/wifihaven --body 'https://<staging-stack>.grafana.net'
gh secret set GRAFANA_AUTH_STAGING -R wifihaven/wifihaven --body '<staging service-account token>'
gh secret set GRAFANA_URL_PROD     -R wifihaven/wifihaven --body 'https://<prod-stack>.grafana.net'
gh secret set GRAFANA_AUTH_PROD    -R wifihaven/wifihaven --body '<prod service-account token>'
```

Until a given environment's pair is set, that environment's deploy job is a
no-op gate. The PR acceptance bar is `terraform fmt`/`validate`-clean config
plus `actionlint`-clean workflows, not a live deploy.

`terraform fmt -check` + `terraform validate` for this directory also run in
CI as the `grafana-terraform` lint job (mirrors the `render-blueprint`
precedent).

## Manual apply (one environment)

```sh
cd infra/grafana

export TF_VAR_grafana_url=https://<stack>.grafana.net
export TF_VAR_grafana_auth=<service-account token>
# optional: export TF_VAR_folder_uid=<pre-created folder uid>

terraform init
terraform plan
terraform apply
```

Or copy `terraform.tfvars.example` → `terraform.tfvars` (gitignored — both
values are secrets) and fill it in. Apply creates 4 dashboard resources.

## Subsequent changes

Edit a dashboard in Grafana → export JSON → overwrite the file under
`deploy/grafana/dashboards/` → commit. The push to `main` re-applies via the
CD pipeline. `overwrite = true` on each resource lets the apply replace a
hand-imported dashboard with the repo copy.
