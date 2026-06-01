# Grafana Terraform — wifihaven

Declarative deploy of the in-repo Grafana dashboards to Grafana Cloud
(#1270, follow-up to #1209). Mirrors the `infra/cloudflare/` pattern: the
repo is the source of truth, `terraform apply` reconciles Grafana Cloud to
match.

Manages:

- One Grafana folder, **WifiHaven**.
- One `grafana_dashboard` per JSON under
  [`deploy/grafana/dashboards/`](../../deploy/grafana/dashboards/) —
  `api-health`, `router-fleet`, `dnsmasq-enforcement`,
  `data-quality-ingest`. Each is sourced via
  `config_json = file(".../deploy/grafana/dashboards/<name>.json")`, so the
  committed JSON is canonical and there is no second copy to drift.

**Not managed here**: the Grafana Cloud stack itself (created once in the
dashboard), the Prometheus datasource (dashboards use a templated
`${datasource}` variable resolved at view time, so the same JSON loads in
both Grafana Cloud and a self-hosted provisioned Grafana, #1207), and the
GitHub Actions secrets (`GRAFANA_URL`, `GRAFANA_AUTH`).

## Prerequisites

1. A Grafana Cloud stack exists (free tier is fine; see design §6.2).
2. A **Grafana service-account token** with folder + dashboard write scope:
   Grafana Cloud → Administration → Service accounts → add token.
3. Terraform ≥ 1.6 installed (`brew install terraform`).

## First apply

```sh
cd infra/grafana

cp terraform.tfvars.example terraform.tfvars
$EDITOR terraform.tfvars   # paste the stack URL + service-account token

terraform init
terraform plan
terraform apply
```

`terraform.tfvars` is **gitignored** — unlike `infra/cloudflare`, both
values here (stack URL and token) are secrets and must never be committed.
You can instead export `TF_VAR_grafana_url` / `TF_VAR_grafana_auth`.

Apply creates 5 resources (1 folder + 4 dashboards).

## CI / auto-deploy

[`.github/workflows/deploy-grafana.yml`](../../.github/workflows/deploy-grafana.yml)
runs `terraform init` + `terraform apply -auto-approve` on every push to
`main` that touches `infra/grafana/**` or `deploy/grafana/dashboards/**`,
authenticated from the `GRAFANA_URL` and `GRAFANA_AUTH` GitHub Actions
secrets. Set them out-of-band:

```sh
gh secret set GRAFANA_URL  -R wifihaven/wifihaven --body 'https://<stack>.grafana.net'
gh secret set GRAFANA_AUTH -R wifihaven/wifihaven --body '<service-account token>'
```

Until both secrets are set, the live apply is a no-op gate — the PR's
acceptance bar is `terraform fmt`/`validate`-clean config plus an
`actionlint`-clean workflow, not a live deploy.

`terraform fmt -check` + `terraform validate` for this directory also run in
CI as the `grafana-terraform` lint job (mirrors the `render-blueprint`
precedent).

## Subsequent changes

Edit a dashboard in Grafana → export JSON → overwrite the file under
`deploy/grafana/dashboards/` → commit. The push triggers
`deploy-grafana.yml`, which re-applies. `overwrite = true` on each resource
lets the apply replace a hand-imported dashboard with the repo copy.

## State

State is local (`terraform.tfstate`, gitignored); back it up out-of-repo and
migrate to a remote backend before sharing infra ops. TODO(#1270).
