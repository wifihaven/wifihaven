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

### State backend — remote (HCP Terraform)

State lives on **HCP Terraform** (Terraform Cloud, free tier) via the
`cloud {}` block in `main.tf`, not a local file (#1406). The dashboards were
upsert-by-uid idempotent and survived empty state on their own, but the
**alerting** work this unblocks — contact points, the singleton notification
policy, and rule groups (#1403/#1404/#1405) — is stateful: a create against
fresh-every-run empty state either 409s on a fixed identifier or accumulates
duplicates, and the root notification policy has no upsert-by-uid. Remote
state gives Terraform a canonical prior state so those resources reconcile
idempotently. The state holds resource IDs but no secrets (the Grafana token
comes from `grafana_auth` / the `GRAFANA_AUTH` secret, not the state). This
mirrors the `infra/cloudflare` backend (#1357).

- **Org / workspace**: from the `TF_CLOUD_ORGANIZATION` and `TF_WORKSPACE` env
  vars (the workflow sets them to `wifihaven` / `grafana`; local operators
  export the same). The `cloud {}` block is intentionally empty so it stays
  account- and creds-agnostic.
- **Auth**: the `TF_TOKEN_app_terraform_io` env var (an HCP user/team API
  token). In CI it is the `TF_API_TOKEN` repo secret.
- **Execution mode**: the workspace runs in **Local** mode — HCP only stores
  state + provides locking; `terraform apply` runs on the GitHub runner, so
  the Grafana service-account token stays a GitHub secret and never moves into
  HCP. Same pattern as `infra/cloudflare`.

We still deliberately do **not** manage a `grafana_folder` resource here — to
organize the dashboards, pre-create a folder once per stack and pass its uid
via the optional `folder_uid` variable; when unset the dashboards land in the
stack's General folder.

> **Drift caveat** (`memory/cloudflare_tf_backend.md`): a local checkout
> *missing* the `cloud {}` block (or without the `TF_CLOUD_ORGANIZATION` /
> `TF_WORKSPACE` env exported) silently falls back to **local** state and can
> propose recreating live resources. After `terraform init`, confirm you are
> on the remote backend with `terraform state pull` before any `apply`.

**Not managed here**: the Grafana Cloud stack itself (created once in the
dashboard), the Prometheus datasource (dashboards use a
templated `${datasource}` variable resolved at view time, so the same JSON
loads in both Grafana Cloud and a self-hosted provisioned Grafana, #1207),
and the GitHub Actions secrets.

## Alerting

The alerting half of the design (`docs/design/alerting.md`) is managed here
too:

- **Contact points + notification policy** ([`alerting.tf`](alerting.tf), #1403)
  — three email contact points and the singleton severity/env routing tree.
- **A managed alerts folder** ([`alerting-rules.tf`](alerting-rules.tf)) that
  the rule groups live in. Unlike the dashboards, a Grafana managed alert rule
  group needs a concrete, non-empty folder, so this one is managed in-repo
  (idempotent now that the HCP backend owns its state).
- **Warning rule group** ([`alerting-rules-warning.tf`](alerting-rules-warning.tf),
  #1405, #2416, #2488) — W1–W8 (§7.2). W5 ships **disabled** (`is_paused`)
  because its series is router-pushed and not yet trustworthy in prod (§8,
  #1382). W6–W8 shipped enabled while the support/press responders were still
  flag-off in prod, so they armed themselves when #2537 flipped those flags — no
  second flip to forget. The flag is necessary but not sufficient, and the two
  halves differ: **W7 (press) is live and exercisable** (the Email Worker already
  posts to the prod API), while **W6/W8 (support) are armed but not yet
  exercisable** — their series need an inbound Plain webhook, and prod's is
  unwired (#2240).
- **Critical rule group** ([`alerting-rules-critical.tf`](alerting-rules-critical.tf),
  #1404) — C1–C7 (§7.1).

Unlike the dashboards, the alert rules reference a **concrete** Prometheus
datasource UID (managed rules cannot use a templated `${datasource}`). It
comes from the `prometheus_datasource_uid` variable, default `grafanacloud-prom`
(the built-in Grafana Cloud hosted Prometheus); override
`TF_VAR_prometheus_datasource_uid` only for a self-hosted stack. Not a secret.

### Verifying a critical rule actually pages (`docs/design/alerting.md` §10)

The #1368 acceptance bar is a confirmed **page**, not just a graphable series.
The safest critical rule to force end-to-end is **C4** — it self-heals the
moment you stop, and it's the exact #1365 shape:

```sh
# Drive status="malformed" up on STAGING so the success ratio drops below 0.95.
# Repeat for ~15m (the rule's `for`) so it transitions Pending → Firing.
while true; do
  curl -s -o /dev/null -X POST https://staging.wifihaven.net/api/router/metrics \
    -H 'Authorization: Bearer <router-token>' \
    -H 'Content-Type: application/json' --data '{"deliberately":"malformed"}'
  sleep 5
done
```

Then confirm, in order: the rule goes **Pending → Firing** after its `for`; the
notification policy routes it (`wifihaven-critical`, or `wifihaven-staging` for
the twin); the **email actually arrives** (a threshold crossing with no email is
a routing bug, not a pass); stop the loop and confirm it returns to **Normal**
with a resolved notification. C1/C2/C3 must be validated on a **prod-shaped
scratch DB**, never by manufacturing a prod saturation event; C5/C6/C7 on
staging by stopping the service / hitting a 500 route / stopping the agent. Use
the per-rule **Preview** and per-contact-point **Test** button during setup, but
the bar is a real forced firing reaching the inbox.

## Prerequisites

> **HARD PREREQUISITE — the HCP workspace must exist before the `cloud {}`
> block can be used.** The `cloud {}` block in `main.tf` points at HCP
> Terraform org `wifihaven` / workspace `grafana`. That workspace is **not**
> created by the CD pipeline — it is a one-time, operator-gated setup step
> (see [One-time backend setup](#one-time-backend-setup--state-adoption-operator-gated)
> below). If the workspace is missing, `terraform init` fails with
> `Invalid workspace selection — Terraform failed to find workspace "grafana"
> in organization wifihaven`, and **every** `master-grafana.yml` run on `main`
> goes red at init (this was [#1440](https://github.com/wifihaven/wifihaven/issues/1440),
> the gap left by the #1406 backend migration; #1357 did this for `cloudflare`).
> Merging a `cloud {}` block before its workspace exists is therefore a
> back-compat trap: create the workspace first, then merge the block.

1. A Grafana Cloud stack exists (free tier is fine; see design §6.2) — one
   stack serves every environment. Ours is
   [`wifihaven.grafana.net`](https://wifihaven.grafana.net).
2. A **Grafana service-account token** for the stack with dashboard write
   scope: Grafana Cloud → Administration → Service accounts → add token.
3. Terraform ≥ 1.6 installed (`brew install terraform`).
4. The **`grafana` HCP Terraform workspace** exists in org `wifihaven`,
   Execution Mode = Local, no VCS attachment — see
   [One-time backend setup](#one-time-backend-setup--state-adoption-operator-gated).

## CI / auto-deploy (the normal path)

Deploys run from the `master-grafana.yml` pipeline on push to `main` (see
[In the CD pipeline](#in-the-cd-pipeline) above). Its `paths:` filter is
scoped to `deploy/grafana/**` and `infra/grafana/**`, so a dashboard or
Terraform change triggers the pipeline — and nothing else does.
Authentication comes from four GitHub Actions secrets set out-of-band (never
committed):

```sh
gh secret set GRAFANA_URL            -R wifihaven/wifihaven --body 'https://wifihaven.grafana.net'
gh secret set GRAFANA_AUTH           -R wifihaven/wifihaven --body '<service-account token>'
gh secret set TF_API_TOKEN           -R wifihaven/wifihaven --body '<HCP user/team token>'
gh secret set GRAFANA_OPERATOR_EMAIL -R wifihaven/wifihaven --body '<operator email>'
```

| Secret | Purpose |
|--------|---------|
| `GRAFANA_URL` | Base URL of the Grafana Cloud stack. |
| `GRAFANA_AUTH` | Grafana service-account token with dashboard (and alert) write scope. |
| `TF_API_TOKEN` | HCP Terraform state-backend auth (`app.terraform.io` → Account settings → Tokens). |
| `GRAFANA_OPERATOR_EMAIL` | Address every alert contact point delivers to (#1403). Fed as `TF_VAR_operator_email`; never committed. |

Until all four are set, the deploy job is a no-op gate. The PR acceptance bar
is `terraform fmt`/`validate`-clean config plus `actionlint`-clean workflows,
not a live deploy.

`terraform fmt -check` + `terraform validate` for this directory also run in
CI as the `grafana-terraform` lint job (mirrors the `render-blueprint`
precedent).

## One-time backend setup + state adoption (operator-gated)

Do this **once**, locally, before merging anything that triggers the pipeline.
Unlike `infra/cloudflare` (which migrated a real local `terraform.tfstate`),
this directory was **stateless by design** — there is no persisted local state
to migrate. So adoption relies on the dashboards' `overwrite = true` /
stable-`uid` upsert: the very first apply against the empty HCP state **updates
the existing dashboards by uid** instead of creating duplicates, and after that
first apply the state tracks all 7 so the **second apply is a no-op**. That is
the structural reason there is no duplicate-dashboard churn.

1. **Create the HCP workspace.** In the existing `wifihaven` org (the same org
   as `infra/cloudflare`), create a workspace named `grafana` with **Execution
   Mode = Local** and **no VCS attachment** — mirror the `cloudflare` workspace
   exactly. Local mode matters: the apply must run on your machine / the runner
   where the Grafana provider plugin and `grafana_auth` live, not remotely on
   HCP. **The CD pipeline does not create this** — until it exists, every
   `master-grafana.yml` run on `main` fails at `terraform init` with `Invalid
   workspace selection` (#1440).

   Either via the **UI** (https://app.terraform.io → `wifihaven` → New workspace
   → API-driven / CLI-driven → name `grafana` → Settings → General → Execution
   Mode = Local), or via the **API** (mirrors `cloudflare`):

   ```sh
   curl -s -X POST \
     -H "Authorization: Bearer $TF_API_TOKEN" \
     -H "Content-Type: application/vnd.api+json" \
     https://app.terraform.io/api/v2/organizations/wifihaven/workspaces \
     -d '{"data":{"type":"workspaces","attributes":{"name":"grafana","execution-mode":"local","auto-apply":false}}}'
   ```

2. **Authenticate + point at the workspace:**

   ```sh
   cd infra/grafana
   export TF_TOKEN_app_terraform_io=<HCP user/team token>   # or run: terraform login
   export TF_CLOUD_ORGANIZATION=wifihaven
   export TF_WORKSPACE=grafana
   export TF_VAR_grafana_url=https://wifihaven.grafana.net
   export TF_VAR_grafana_auth=<service-account token>
   export TF_VAR_operator_email=<operator email>   # alert contact-point destination (#1403)
   ```

3. **Init against the cloud backend and confirm you're on it.** Per
   `memory/cloudflare_tf_backend.md`, a checkout that silently fell back to
   **local** state is the failure mode to guard against — so verify before any
   apply:

   ```sh
   terraform init
   terraform state pull | head        # must show the HCP-backed (cloud) state
   ```

   > If you do happen to have a leftover local `terraform.tfstate` from a past
   > manual apply, run `terraform init -migrate-state` instead and answer "yes"
   > to copy it up — but the clean case here has none.

4. **First apply adopts the dashboards; second apply proves the no-op.**

   ```sh
   terraform apply                     # adopts all 7 dashboards by uid (no dupes)
   terraform apply                     # Expect: "No changes." — the acceptance bar
   terraform state list                # the 7 dashboards below
   ```

   `terraform state list` should show exactly:

   ```
   grafana_dashboard.wifihaven["api-health"]
   grafana_dashboard.wifihaven["api-self-metrics"]
   grafana_dashboard.wifihaven["router-fleet"]
   grafana_dashboard.wifihaven["rollup-health"]
   grafana_dashboard.wifihaven["db-health"]
   grafana_dashboard.wifihaven["data-quality-ingest"]
   grafana_dashboard.wifihaven["enforcement"]
   ```

5. **Set the CI secrets** (`GRAFANA_URL`, `GRAFANA_AUTH`, `TF_API_TOKEN`) as
   above. From here, merges to `main` apply automatically.

## Manual apply (one environment)

```sh
cd infra/grafana

# HCP remote state (skip the org/workspace exports only if you've already run
# `terraform login` AND the cloud{} block's workspace is set some other way).
export TF_TOKEN_app_terraform_io=<HCP user/team token>
export TF_CLOUD_ORGANIZATION=wifihaven
export TF_WORKSPACE=grafana

export TF_VAR_grafana_url=https://wifihaven.grafana.net
export TF_VAR_grafana_auth=<service-account token>
export TF_VAR_operator_email=<operator email>   # alert contact-point destination (#1403)
# optional: export TF_VAR_folder_uid=<pre-created folder uid>

terraform init
terraform state pull | head   # confirm remote backend before planning
terraform plan
terraform apply
```

The dashboard secrets can also go in `terraform.tfvars` (gitignored — both
values are secrets); the HCP org/workspace must stay env vars (the `cloud {}`
block is intentionally empty). With migrated state, `apply` is a no-op on an
unchanged repo.

## Subsequent changes

Edit a dashboard in Grafana → export JSON → overwrite the file under
`deploy/grafana/dashboards/` → commit. The push to `main` re-applies via the
CD pipeline. `overwrite = true` on each resource lets the apply replace a
hand-imported dashboard with the repo copy.
