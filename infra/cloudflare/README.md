# Cloudflare Terraform — wifihaven

Declarative config for everything Cloudflare-side (#613):

- Pages projects `wifihaven` (SPA prod), `wifihaven-staging` (SPA staging), and
  `wifihaven-www` (marketing site, #1842) — all Direct Upload.
- Pages custom domains: `app` + apex + `www` (apex/www front `wifihaven-www`),
  `app-staging` + `staging`. There is **no** zone-level redirect ruleset — apex
  and www both serve the marketing site, staging keeps serving the staging SPA,
  and there is no apex `/blocked` shim (dropped 2026-06, see `main.tf`).
- DNS-only CNAMEs: `api.wifihaven.net`, `api-staging.wifihaven.net` → Render.
- SPF TXT record + the `e2e-brand` / `e2e-mid` / `e2e-edge` test-fixture
  CNAME chain (#1351).
- Resend sending records on `send.wifihaven.net` (DKIM/MX/SPF, #578/#2196).
- **Plain outbound sending** records (#2247/#2206): per-domain Postmark DKIM
  (`20260716…pm._domainkey` on the apex and on `staging`) + custom Return-Path
  CNAMEs (`plain-bounces` and `plain-bounces.staging` → `pm.mtasv.net`) for
  `support@wifihaven.net` and `support@staging.wifihaven.net`. The per-domain
  DKIM strict-aligns `d=` under our `adkim=s` DMARC; the custom Return-Path means
  **no apex SPF include for Postmark is needed** (do not add `spf.mtasv.net`).
  Apply + let DNS propagate **before** clicking Plain's "Verify DNS and continue".
- **Email Routing** (#2198): forwards `support@wifihaven.net` **and**
  `support@staging.wifihaven.net` into WifiHaven's Plain inbox (each to its own
  Postmark inbound address — prod vs. staging channel). Terraform manages only
  the parts a scoped CI token can create: two `cloudflare_email_routing_address`
  (prod + staging Plain destinations) and two `cloudflare_email_routing_rule`
  (the `to` → forward rules). **Enabling Email Routing on the zone is an
  operator/dashboard step, not Terraform** — a scoped API token cannot perform
  the zone "enable" onboarding action even with `Zone → Email Routing Rules →
  Edit` (it returns `Authentication error (10000)`; verified on #2243). Enabling
  in the dash also provisions the apex `route1/2/3.mx.cloudflare.net` MX with
  zone-assigned priorities, which are likewise **not** authored here (same class
  as the enable; they live outside Terraform state, so no drift). Operator steps:
  (1) **enable Email Routing on the zone** (Email → Email Routing → Enable), and
  for the **staging** address also add the `staging` subdomain under Settings →
  Subdomains — do both **before** the apply, else the rule creates fail;
  (2) ensure the CI Cloudflare token carries `Account → Email Routing Addresses →
  Edit` and `Zone → Email Routing Rules → Edit` (needed for the addresses + rules
  Terraform *does* manage); (3) click the destination-verification link
  Cloudflare emails to each Plain inbox (prod and staging).
- **Personal forwarding alias** (#2204): forwards `sameer@wifihaven.net` straight
  to the operator's real external inbox `sameer@creativedestruction.com` — a plain
  Cloudflare Email Routing forward (no Plain, no Email Worker, no app/AI). Managed
  as one `cloudflare_email_routing_address` (the external destination) + one
  `cloudflare_email_routing_rule` (`to` → forward). Uses the same already-enabled
  zone Email Routing and the same apex SPF as support@ — **no SPF change**, and it
  coexists with support@/press@ (distinct local-part). Operator step: click the
  one-time Cloudflare destination-verification link emailed to
  `sameer@creativedestruction.com` (Terraform cannot complete it).

**Not managed here**: the zone itself (added once via the dash; NS flip at the
registrar is a one-shot manual step), and the GitHub repo secrets.

## State backend — remote (HCP Terraform)

State lives on **HCP Terraform** (Terraform Cloud, free tier) via the `cloud {}`
block in `main.tf`, not a local file. This is what makes the CI apply pipeline
viable: native state locking serializes applies, and a fresh CI checkout reads
the canonical state instead of an empty local one — so it never tries to
recreate the ~10 already-live resources (#1357 — closes the old `#613-followup`
local-state TODO).

- **Org / workspace**: from the `TF_CLOUD_ORGANIZATION` and `TF_WORKSPACE` env
  vars (the workflow sets them to `wifihaven` / `cloudflare`; local operators
  export the same). The `cloud {}` block is intentionally empty so it stays
  account- and creds-agnostic.
- **Auth**: the `TF_TOKEN_app_terraform_io` env var (an HCP user/team API
  token). In CI it is the `TF_API_TOKEN` repo secret.
- **Execution mode**: the workspace runs in **Local** mode — HCP only stores
  state + provides locking; `terraform apply` runs on the GitHub runner (like
  the infra/grafana pipeline), so the Cloudflare token stays a GitHub secret
  and never moves into HCP.

## Applies are CI-driven

A merge to `main` that touches `infra/cloudflare/**` triggers
[`.github/workflows/master-cloudflare.yml`](../../.github/workflows/master-cloudflare.yml),
which runs `terraform init && terraform apply -auto-approve` against the live
zone. **You no longer run `terraform apply` by hand** for routine changes —
edit the HCL, open a PR, and the apply happens on merge.

- The PR-time gate is the `cloudflare-terraform` job in `ci.yml` (fmt +
  `validate -backend=false`). It needs no creds and does not apply.
- The pipeline no-ops (skips with a notice) until `TF_API_TOKEN` and a
  DNS-scoped Cloudflare token are set — see below.

## Prerequisites

1. Zone `wifihaven.net` active on Cloudflare (NS flipped, "Active" in the dash).
2. Render services exist with CNAME targets in **Settings → Custom Domains**.
3. Cloudflare API token scopes:
   - `Account / Cloudflare Pages / Edit`
   - `Zone / DNS / Edit` (scoped to the wifihaven.net zone)
4. Terraform ≥ 1.6 installed (`brew install terraform`).

## Required secrets / token scope

The CI apply needs two repo secrets:

| Secret | Purpose | Notes |
|--------|---------|-------|
| `TF_API_TOKEN` | HCP Terraform state-backend auth | User/team token from app.terraform.io → Account settings → Tokens. |
| `CLOUDFLARE_DNS_API_TOKEN` (preferred) or `CLOUDFLARE_API_TOKEN` | Cloudflare provider auth | Needs **both** `Account / Cloudflare Pages / Edit` **and** `Zone / DNS / Edit` on wifihaven.net. |

> ⚠️ **Token-scope caveat.** The existing `CLOUDFLARE_API_TOKEN` secret was
> created for the Pages `wrangler` deploys (`master-api-ui.yml`) and may carry
> only `Account / Cloudflare Pages / Edit`. The DNS records here additionally
> need `Zone / DNS / Edit`. **Verify the token's scopes** in the Cloudflare dash
> before the first apply. Either widen the existing token, or mint a new token
> with both scopes and store it as `CLOUDFLARE_DNS_API_TOKEN` (the workflow
> prefers it and falls back to `CLOUDFLARE_API_TOKEN`).

```sh
gh secret set TF_API_TOKEN --repo wifihaven/wifihaven
gh secret set CLOUDFLARE_DNS_API_TOKEN --repo wifihaven/wifihaven
```

## One-time backend setup + state migration (operator-gated)

Do this **once**, locally, with your existing local state present. It moves the
already-live resources into HCP so CI does not try to recreate them. **Run this
before merging anything that would trigger the pipeline.**

1. **Create the HCP org + workspace.** On https://app.terraform.io create an org
   named `wifihaven` (or adjust `TF_CLOUD_ORGANIZATION` in the workflow + below)
   and a workspace named `cloudflare`. Set the workspace **Execution Mode =
   Local** (workspace → Settings → General).

2. **Authenticate + point at the workspace:**

   ```sh
   cd infra/cloudflare
   export TF_TOKEN_app_terraform_io=<HCP user/team token>   # or run: terraform login
   export TF_CLOUD_ORGANIZATION=wifihaven
   export TF_WORKSPACE=cloudflare
   export CLOUDFLARE_API_TOKEN=<token with Pages + Zone:DNS Edit>
   ```

3. **Migrate the existing local state into HCP:**

   ```sh
   terraform init -migrate-state
   # Terraform detects the backend change (local → cloud) and offers to copy the
   # existing terraform.tfstate into the new HCP workspace. Answer "yes".
   ```

4. **Verify nothing will be recreated.** A clean plan must report **No
   changes** — that proves all live resources are tracked:

   ```sh
   terraform plan
   # Expect: "No changes. Your infrastructure matches the configuration."
   ```

   `terraform state list` should show at least these resources (the original
   #1357 migration snapshot; the live set has since grown — the `app` /
   `app-staging` domains + `spa_app*` records (#1832), the `e2e_edge` AAAA
   (#1677), and the `marketing` project (#1842)):

   ```
   cloudflare_pages_project.prod
   cloudflare_pages_project.staging
   cloudflare_pages_domain.apex
   cloudflare_pages_domain.www
   cloudflare_pages_domain.staging
   cloudflare_record.spf
   cloudflare_record.spa_apex
   cloudflare_record.spa_www
   cloudflare_record.spa_staging
   cloudflare_record.api_prod
   cloudflare_record.api_staging
   cloudflare_record.e2e_brand
   cloudflare_record.e2e_mid
   cloudflare_record.e2e_edge
   ```

   **If the local state was lost** (plan wants to *create* resources that
   already exist), do NOT apply — `terraform import` each one, then re-run
   `terraform plan` until it is clean:

   ```sh
   terraform import cloudflare_pages_project.prod    wifihaven/wifihaven
   terraform import cloudflare_pages_project.staging wifihaven/wifihaven-staging
   terraform import cloudflare_pages_domain.apex     wifihaven/wifihaven/wifihaven.net
   terraform import cloudflare_pages_domain.www      wifihaven/wifihaven/www.wifihaven.net
   terraform import cloudflare_pages_domain.staging  wifihaven/wifihaven-staging/staging.wifihaven.net
   terraform import cloudflare_record.spf           <zone_id>/<record_id>
   terraform import cloudflare_record.spa_apex      <zone_id>/<record_id>
   terraform import cloudflare_record.spa_www       <zone_id>/<record_id>
   terraform import cloudflare_record.spa_staging   <zone_id>/<record_id>
   terraform import cloudflare_record.api_prod      <zone_id>/<record_id>
   terraform import cloudflare_record.api_staging   <zone_id>/<record_id>
   terraform import cloudflare_record.e2e_brand     <zone_id>/<record_id>
   terraform import cloudflare_record.e2e_mid       <zone_id>/<record_id>
   terraform import cloudflare_record.e2e_edge      <zone_id>/<record_id>
   ```

   (DNS record IDs: Cloudflare dash → DNS → each record, or the API
   `GET /zones/<zone_id>/dns_records`. Pages import IDs use `<account>/<project>`
   and `<account>/<project>/<domain>`. `zone_id` is in `terraform.tfvars`.)

5. **Set the CI secrets** (`TF_API_TOKEN`, `CLOUDFLARE_DNS_API_TOKEN`) as above.
   From here, merges to `main` apply automatically.

## Subsequent changes

Edit `main.tf` → open a PR (the `cloudflare-terraform` lint job runs) → merge.
`master-cloudflare.yml` applies on merge. To preview locally, with the env vars
from step 2 exported: `terraform plan`.

## Drift

`terraform plan` with no local edits should report "No changes". If it proposes
deletions you didn't intend (e.g. somebody added a record via the dash),
reconcile by adding the resource here, not by applying around it.
