# Cloud deploy — operator guide (Render + Cloudflare)

This guide covers the split-stack cloud deployment:

- **API + Postgres on Render** (defined in `render.yaml` — Blueprint apply)
- **SPA on Cloudflare Pages** (built + pushed by CI via Wrangler from
  the `deploy-spa-staging` / `deploy-spa-prod` jobs in
  `.github/workflows/master-api-ui.yml`; gated on the same staging-smoke
  chain as the API deploy from #588)
- **DNS on Cloudflare** (NS for `wifihaven.net` points at Cloudflare;
  Cloudflare manages records and edge certs for every hostname)

The SPA moved off Render Static Sites in #613 — Render's catch-all SPA
rewrite kept eating `/.well-known/acme-challenge/*` (#609) which broke
Let's Encrypt issuance. Cloudflare Pages uses DNS-01 challenges, so the
class of bug doesn't exist. CI is also already building the SPA, so
having Render rebuild it was wasted work.

Custom domain layout (unchanged from the pre-#613 setup):

| Hostname                       | Where it lives                    |
|--------------------------------|-----------------------------------|
| `wifihaven.net` (apex)         | Cloudflare Pages: `wifihaven`     |
| `www.wifihaven.net`            | Cloudflare Pages: `wifihaven`     |
| `staging.wifihaven.net`        | Cloudflare Pages: `wifihaven-staging` |
| `api.wifihaven.net`            | Render: `wifihaven-api-prod`      |
| `api-staging.wifihaven.net`    | Render: `wifihaven-api-staging`   |

---

## 1. DNS: move NS for `wifihaven.net` to Cloudflare

Cloudflare Pages works best when the apex zone is hosted on Cloudflare —
Pages can then manage custom-domain hostnames and certs end-to-end without
manual CNAMEs, and the API hostnames pick up Cloudflare's DDoS protection
for free.

1. Create a free Cloudflare account at https://dash.cloudflare.com/sign-up.
2. **Add site** → enter `wifihaven.net` → choose the Free plan.
3. Cloudflare scans existing DNS records. Verify the scan picked up any
   records you care about (none should exist yet for a fresh setup).
4. Cloudflare assigns two nameservers (e.g.
   `xxx.ns.cloudflare.com` / `yyy.ns.cloudflare.com`).
5. At Google Domains (current registrar), edit `wifihaven.net` → DNS →
   **Use custom name servers** → paste both Cloudflare NS values → save.
6. Propagation: typically minutes; allow up to 48 h. Cloudflare emails when
   it sees the change.

Once the zone is active on Cloudflare, add these records in the Cloudflare
DNS UI (proxy / orange-cloud status as noted):

| Type  | Name           | Target                                    | Proxy           |
|-------|----------------|-------------------------------------------|-----------------|
| CNAME | `api`          | shown in Render → `wifihaven-api-prod`    | DNS-only (grey) |
| CNAME | `api-staging`  | shown in Render → `wifihaven-api-staging` | DNS-only (grey) |

The Pages hostnames (apex, `www`, `staging`) are added through the Pages
project's **Custom domains** tab (see §3) — Cloudflare wires the DNS
records automatically when the apex zone is on the same account.

API CNAMEs are intentionally **DNS-only** (grey cloud): proxying through
Cloudflare's edge would terminate TLS at Cloudflare and require additional
config for the API to read the real client IP. Out of scope for now.

---

## 2. Apply the Render Blueprint (API + Postgres)

`render.yaml` defines four resources (no Static Sites — those moved to
Cloudflare):

- `wifihaven-api-staging` (Web Service, free)
- `wifihaven-api-prod` (Web Service, paid — `standard`; bumped from free in #786)
- `wifihaven-pg-staging` (Postgres, free)
- `wifihaven-pg-prod` (Postgres, paid — `basic-256mb`)

1. https://dashboard.render.com/ → **Blueprints** → connect the
   `wifihaven/wifihaven` repo if not already connected.
2. **New Blueprint Instance** → select this repo → Render reads
   `render.yaml` from the default branch.
3. Review the preview, then **Apply**. Wait for all four resources to
   reach **Live** / **Available**.

> If staging resources from #585 already exist, Render updates them
> in-place rather than recreating them.

Add Render's CNAME targets to Cloudflare DNS for `api` and `api-staging`
(see §1). Render's **Custom Domains** tab for each service shows the
exact target. Render issues Let's Encrypt certs automatically once DNS
resolves.

> The default `wifihaven-api-{staging,prod}.onrender.com` URLs are
> **not** a supported endpoint and are being disabled at the Render
> level (Settings → Custom Domains → *Block public access to onrender
> subdomain*). The custom domain is the only entry point — clients,
> health checks, and ops tooling should hit `api.wifihaven.net` /
> `api-staging.wifihaven.net`. The CNAME targets in
> `infra/cloudflare/terraform.tfvars` still reference the onrender
> hostname because Render routes by Host header; blocking direct
> public access to `*.onrender.com` does not affect CNAME routing.

---

## 3. Apply the Cloudflare Terraform

All Cloudflare resources (Pages projects, custom domains, API DNS
records) are declarative in `infra/cloudflare/`. The Terraform module's
own README has the step-by-step; the gist:

```sh
cd infra/cloudflare
cp terraform.tfvars.example terraform.tfvars
$EDITOR terraform.tfvars            # account_id, zone_id, Render CNAMEs
export CLOUDFLARE_API_TOKEN=<token>  # Pages:Edit + Zone:DNS:Edit scopes
terraform init
terraform plan
terraform apply
```

This creates two Pages projects (`wifihaven`, `wifihaven-staging`,
Direct Upload — CI pushes via Wrangler), three Pages custom domains
(apex, `www`, `staging`), and the two API CNAMEs.

Cloudflare auto-issues edge certs (DNS-01 challenge — no path
interception). Cert status flips to **Active** in the dash within a
minute or two.

Apex + `www` behavior: both serve the same bundle. If you want
`www → apex` canonical redirect, add a Cloudflare Page Rule or a Bulk
Redirect later — out of scope of the Terraform module for now.

> The §1 table above lists the two API CNAME records for reference; both
> are managed by Terraform now and should NOT be added manually in the
> Cloudflare DNS UI (Terraform will report drift).

---

## 4. Repo secrets

CI needs two secrets to push to Pages:

| Secret name              | Value                                                |
|--------------------------|------------------------------------------------------|
| `CLOUDFLARE_API_TOKEN`   | Cloudflare → My Profile → API Tokens → **Create Token** → custom token. Scopes: `Account / Cloudflare Pages / Edit` (needed by both CI and Terraform) AND `Zone / DNS / Edit` scoped to the wifihaven.net zone (needed by Terraform only). One token can hold both — same token works for CI and `terraform apply`. |
| `CLOUDFLARE_ACCOUNT_ID`  | Cloudflare dash → any zone → right sidebar → Account ID. |

Set both at https://github.com/wifihaven/wifihaven/settings/secrets/actions.

---

## 5. First deploy walkthrough

After §1-§4 are done, the first SPA deploy fires on the next push to
`main` (or trigger it manually via the **Deploy SPA (Cloudflare Pages)**
workflow → **Run workflow**).

1. CI runs `npm ci && VITE_API_BASE_URL=... npm run build` for each env.
2. CI runs `wrangler pages deploy`. Wrangler 4 forbids `--config` for
   Pages, so the staging job first copies `wrangler.staging.toml` over
   `wrangler.toml`; prod uses the file as-is. Project name (`wifihaven`
   or `wifihaven-staging`) and publish dir (`./dist`) come from the
   wrangler config file — keep the Pages project name in the dashboard
   and the `name =` field in sync.
3. Cloudflare assigns a deployment URL like
   `https://<hash>.wifihaven.pages.dev`, then aliases the configured
   custom domains to the new deployment.

Verify:

```sh
# Prod API + SPA
curl -sS https://api.wifihaven.net/api/health
curl -sS -o /dev/null -w "%{http_code}\n" https://wifihaven.net/
curl -sS -o /dev/null -w "%{http_code}\n" https://www.wifihaven.net/

# Staging API + SPA
curl -sS https://api-staging.wifihaven.net/api/health
curl -sS -o /dev/null -w "%{http_code}\n" https://staging.wifihaven.net/
```

All five should return 200. Inspect the prod SPA HTML (`view-source`) and
search for `api.wifihaven.net` to confirm the right `VITE_API_BASE_URL`
got baked in.

Login flow end-to-end on staging exercises the CORS allowlist from #612.

---

## 6. SPA → API wiring (how it works)

The Vite build bakes the API origin into the bundle at build time via
`VITE_API_BASE_URL`, set per env by the CI workflow:

- prod build  → `VITE_API_BASE_URL=https://api.wifihaven.net`
- staging build → `VITE_API_BASE_URL=https://api-staging.wifihaven.net`

The browser then makes cross-origin XHRs from the Cloudflare-served SPA
to the Render-served API. CORS is required on the API for this to work —
see `WIFIHAVEN_ALLOWED_ORIGINS` in `render.yaml` and the work in #612.

`web/public/_redirects` ships with the bundle and tells Cloudflare Pages
to rewrite any non-file path to `/index.html` (200, not 302), so React
Router can take over client-side. Same Netlify-style syntax that Render
used; works as the canonical config on Cloudflare instead of as a
workaround.

---

## 7. Ongoing operations

**Deploys.** A push to `main` that touches API/UI paths triggers
`.github/workflows/master-api-ui.yml`, which runs `deploy-spa-staging` in
parallel with `deploy-staging`, then gates `deploy-spa-prod` behind
`smoke-staging` (same chain as the API deploy from #588). Router-side
pushes go to `master-router.yml` instead; the two pipelines are
independent (split in #871). Top-of-file comments in each workflow have
the full dependency graphs.

**Cert renewal.** Cloudflare auto-renews edge certs for all three Pages
hostnames (DNS-01 — no path-interception class of bug). Render
auto-renews Let's Encrypt for the two API hostnames. No operator action
needed.

**Rollback.** Cloudflare Pages → project → **Deployments** → pick a
prior deployment → **Rollback to this deployment**. The custom domain
aliases swap instantly.

---

## 8. Production Postgres — daily backup verification

`wifihaven-pg-prod` is on the `basic-256mb` paid plan which includes
daily backups. Verify after first apply:

1. Render dashboard → **Databases** → `wifihaven-pg-prod`.
2. **Backups** tab. Confirm a backup is shown (the first runs within
   24 h of creation) and the schedule reads **Daily**.

If the Backups tab shows no schedule, contact Render support — the
`basic-256mb` plan should include daily backups by default. Do not run
prod on the free PG tier (it expires after ~30 days).

---

## 9. Passwords — 1Password

| Item name                              | What to store                                 |
|----------------------------------------|-----------------------------------------------|
| `WifiHaven — Prod Admin Password`      | Admin password set via `POST /auth/change-password` on first login to `https://api.wifihaven.net` |
| `WifiHaven — Prod RO Postgres URL`     | `wifihaven_ro` connection string for `wifihaven-pg-prod` (see `docs/render-readonly-role.md`) |
| `WifiHaven — Staging Admin Password`   | (already stored from #586; rotate if needed)   |
| `WifiHaven — Staging RO Postgres URL`  | (already stored from #586; rotate if needed)   |
| `WifiHaven — Cloudflare API Token`     | The token written to `CLOUDFLARE_API_TOKEN` (Pages:Edit scope) |

**First admin login**: the prod API ships with default `admin/changeme`,
force-expired on first login (#586). Open `https://wifihaven.net/` in a
browser, log in, the UI redirects to `/account` to set a new password.
Store it in 1Password immediately.

---

## 10. Set the `wifihaven_ro` role password (prod)

Same as `docs/render-readonly-role.md`, but for `wifihaven-pg-prod`:

1. Render dashboard → `wifihaven-pg-prod` → **PSQL Command**.
2. Run:
   ```sql
   ALTER ROLE wifihaven_ro PASSWORD '<strong-random-password>';
   ```
3. Build the connection string (replace `user` with `wifihaven_ro`).
4. Store in 1Password as `WifiHaven — Prod RO Postgres URL`.

---

## 11. Observability — Grafana Cloud stack

The cloud metrics + dashboard stack lives at **`https://wifihaven.grafana.net`**
(Grafana Cloud free tier). It receives:

- **Application metrics** — pushed by `wifihaven-alloy` (Render worker,
  `deploy/alloy/config.alloy`) which scrapes the internal `/metrics` on
  each API and `remote_write`s to Grafana Cloud Prometheus. See
  [`docs/design/metrics-observability.md`](design/metrics-observability.md)
  §6.2.
- **Render infrastructure metrics** — managed-Postgres CPU/RAM/conns +
  per-service CPU/RAM, streamed natively via Render → Grafana Cloud OTLP
  (no collector). Design doc §6.3.
- **Deploy annotations** — POSTed directly from
  `.github/workflows/master-api-ui.yml` via the
  [`.github/actions/grafana-annotation`](../.github/actions/grafana-annotation)
  composite action. Tagged
  `[deploy, <surface>, <env>, <lifecycle>]` where surface is `render`
  (API on Render) or `pages` (SPA on Cloudflare Pages); env is
  `staging` or `prod`; lifecycle is `started` / `live` / `failed`. The
  standalone `wifihaven-deploy-webhook` Render service was retired in
  #1390 — saves ~$7/mo at the `starter` worker floor.

### Repo secrets (GitHub Actions)

| Secret | Value |
| --- | --- |
| `GRAFANA_CLOUD_URL` | `https://wifihaven.grafana.net` |
| `GRAFANA_CLOUD_ANNOTATION_TOKEN` | Grafana Cloud API token, `annotations:write` scope |

Create the token under **Administration → Service accounts** (or
**Users → Access tokens**) in the stack UI. Set both with:

```sh
gh secret set GRAFANA_CLOUD_URL --repo wifihaven/wifihaven \
  --body 'https://wifihaven.grafana.net'
gh secret set GRAFANA_CLOUD_ANNOTATION_TOKEN --repo wifihaven/wifihaven
# paste token, Ctrl-D
```

### Render-side secrets (Alloy)

The Alloy collector uses three Render-managed (`sync: false`) secrets
declared in `render.yaml` and set in the Render dashboard:
`GRAFANA_CLOUD_PROM_URL`, `GRAFANA_CLOUD_PROM_USER`,
`GRAFANA_CLOUD_PROM_PASSWORD`. The PROM_URL is the Grafana Cloud
Prometheus `remote_write` endpoint (distinct from the stack URL above —
read it from **Connections → Prometheus** in the stack UI), PROM_USER
is the numeric instance id, PROM_PASSWORD is a Grafana Cloud API token
with `metrics-push` scope.

---

## 12. Deferred items

- **CI deploy hook for API** (#588): API redeploys still trigger from the
  Render dashboard (or Render's git connection) until #588 lands. After
  #588, prod SPA deploy gates on the same staging-smoke chain — see the
  TODO in `.github/workflows/deploy-spa.yml`.
- **Disable SPA serving on Render API** (#614): now that the SPA is on
  Cloudflare, the JVM image's bundled SPA fallback is dead weight. Land
  #614 to remove it.
- **Region tuning** (#590): `oregon` is a placeholder — confirm the
  Render region closest to the operator's physical location before
  traffic goes live.
