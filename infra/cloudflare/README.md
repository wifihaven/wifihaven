# Cloudflare Terraform — wifihaven

Declarative config for everything Cloudflare-side (#613):

- Pages projects `wifihaven` (prod) and `wifihaven-staging` (Direct Upload).
- Pages custom domains: `wifihaven.net`, `www.wifihaven.net`, `staging.wifihaven.net`.
- DNS-only CNAMEs: `api.wifihaven.net`, `api-staging.wifihaven.net` → Render.

**Not managed here**: the zone itself (added once via the dash; NS flip at
the registrar is a one-shot manual step), and the GitHub repo secrets
(`CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`).

## Prerequisites

1. The zone `wifihaven.net` is active on Cloudflare (NS flipped, status
   shows "Active" in the dash).
2. The four Render services from `render.yaml` exist and have CNAME targets
   shown in their **Settings → Custom Domains** tabs.
3. Cloudflare API token with these scopes:
   - `Account / Cloudflare Pages / Edit`
   - `Zone / DNS / Edit` (scoped to the wifihaven.net zone)
4. Terraform ≥ 1.6 installed (`brew install terraform`).

## First apply

```sh
cd infra/cloudflare

cp terraform.tfvars.example terraform.tfvars
$EDITOR terraform.tfvars   # fill in the four values

export CLOUDFLARE_API_TOKEN=<paste the token>

terraform init
terraform plan
terraform apply
```

Apply should create 7 resources (2 Pages projects + 3 Pages domains +
2 CNAMEs). Pages cert provisioning takes a minute or two — check status
in the Cloudflare dash → Workers & Pages → project → Custom domains.

## Subsequent changes

Edit `main.tf` → `terraform plan` → `terraform apply`. State file is
local (`terraform.tfstate`); back it up out-of-repo (1Password, encrypted
drive) and migrate to a remote backend before sharing infra ops.
TODO(#613-followup).

## Drift

`terraform plan` with no local edits should report "No changes". If it
proposes deletions you didn't intend (e.g. somebody added a custom domain
via the dash), reconcile by adding the resource here, not by applying.
