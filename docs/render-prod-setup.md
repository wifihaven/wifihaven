# Production + staging Render setup — operator guide

This guide covers provisioning the production environment added in #587 and
wiring up custom domains for all four Render services (two APIs + two Static
Site SPAs).

---

## 1. Apply the Blueprint

1. Go to the [Render dashboard](https://dashboard.render.com/) → **Blueprints**.
2. Connect the `wifihaven/wifihaven` GitHub repo if not already connected.
3. Click **New Blueprint Instance** and select this repo. Render reads
   `render.yaml` from the default branch.
4. Render will show a preview of all six resources to be created:
   - `wifihaven-api-staging` (Web Service)
   - `wifihaven-api-prod` (Web Service)
   - `wifihaven-spa-staging` (Static Site)
   - `wifihaven-spa-prod` (Static Site)
   - `wifihaven-pg-staging` (Postgres, free)
   - `wifihaven-pg-prod` (Postgres, paid — `basic-256mb`)
5. Click **Apply** and wait for all services to reach **Live** / **Available**.

> **If you already have the staging services from #585**: Render will detect
> the existing `wifihaven-api-staging` and `wifihaven-pg-staging` resources
> and update them in-place rather than recreating them. The prod services and
> both Static Sites will be created fresh.

---

## 2. DNS CNAME records

Once Render finishes provisioning, add these CNAMEs at your DNS provider
(wherever `wifihaven.net` is registered):

| Subdomain / apex        | CNAME target                                          |
|-------------------------|-------------------------------------------------------|
| `api-staging`           | shown in Render → `wifihaven-api-staging` → Settings → Custom Domains |
| `api`                   | shown in Render → `wifihaven-api-prod` → Settings → Custom Domains    |
| `staging`               | shown in Render → `wifihaven-spa-staging` → Settings → Custom Domains  |
| `@` (apex / root)       | shown in Render → `wifihaven-spa-prod` → Settings → Custom Domains     |
| `www`                   | same target as apex — `wifihaven-spa-prod` accepts both `wifihaven.net` and `www.wifihaven.net` |

The prod SPA Static Site is configured with both `wifihaven.net` and
`www.wifihaven.net` as custom domains (see `render.yaml`). Render issues
Let's Encrypt certificates for both and serves the same bundle from either
hostname; it does not auto-redirect between apex and www, so users who land
on `www.` stay on `www.` (and vice versa). If a canonical redirect is
desired later, configure it as a Render redirect rule or in the SPA itself.

**Note on apex domains**: many DNS providers do not support a CNAME at the
zone apex (`@`). If yours does not, use an **ALIAS** or **ANAME** record
instead (Route 53 calls it "Alias", Cloudflare calls it a "CNAME flattening"
record). Render's UI will show the exact target hostname to point at.

After adding CNAMEs, Render provisions Let's Encrypt certificates
automatically — usually within a few minutes of DNS propagating. You can
monitor cert status in each service's **Custom Domains** tab.

---

## 3. Verify all four custom domains

Run these checks from your laptop once DNS and certs are live:

```sh
# Prod API
curl -sS https://api.wifihaven.net/api/health

# Staging API
curl -sS https://api-staging.wifihaven.net/api/health

# Prod SPA (look for HTML with <title>WifiHaven</title> or similar)
curl -sS -o /dev/null -w "%{http_code}" https://wifihaven.net/

# Staging SPA
curl -sS -o /dev/null -w "%{http_code}" https://staging.wifihaven.net/
```

Both API health endpoints should return HTTP 200 with a JSON body.
Both SPA URLs should return HTTP 200.

---

## 4. Production Postgres — daily backup verification

`wifihaven-pg-prod` is on the `basic-256mb` paid plan which includes daily
backups. Verify after first apply:

1. Render dashboard → **Databases** → `wifihaven-pg-prod`.
2. Click the **Backups** tab.
3. Confirm a backup is shown (Render runs the first one within 24 h of
   creation). The backup schedule should show **Daily**.

If the Backups tab shows no schedule, contact Render support — the `basic-256mb`
plan should include daily backups by default, but plan names and features can
change. Do not run prod on the free tier (it expires after ~30 days).

---

## 5. Passwords — 1Password

Store the following credentials in the shared 1Password vault alongside the
existing staging entries:

| Item name                              | What to store                                 |
|----------------------------------------|-----------------------------------------------|
| `WifiHaven — Prod Admin Password`      | The admin password set via `POST /auth/change-password` on first login to `https://api.wifihaven.net` |
| `WifiHaven — Prod RO Postgres URL`     | The `wifihaven_ro` connection string for `wifihaven-pg-prod` (see `docs/render-readonly-role.md`) |
| `WifiHaven — Staging Admin Password`   | (already stored from #586; rotate if needed)   |
| `WifiHaven — Staging RO Postgres URL`  | (already stored from #586; rotate if needed)   |

**Admin password first-login flow**: the prod API ships with a default
`admin/changeme` credential that is force-expired on first login (#586).
Open `https://wifihaven.net/` in a browser, log in, and the UI will redirect
you to `/account` to set a new password before any other action. Store the
new password in 1Password immediately.

---

## 6. Set the `wifihaven_ro` role password (prod)

Follow the same steps as `docs/render-readonly-role.md`, using
`wifihaven-pg-prod` instead of `wifihaven-pg-staging`:

1. Render dashboard → `wifihaven-pg-prod` → **PSQL Command**.
2. Run:
   ```sql
   ALTER ROLE wifihaven_ro PASSWORD '<strong-random-password>';
   ```
3. Build the connection string (replace `user` with `wifihaven_ro`).
4. Store in 1Password as `WifiHaven — Prod RO Postgres URL`.

---

## 7. SPA → API wiring (how it works)

The Render Static Site builds bake in the API URL at build time via
`VITE_API_BASE_URL`:

- `wifihaven-spa-prod` sets `VITE_API_BASE_URL=https://api.wifihaven.net`
- `wifihaven-spa-staging` sets `VITE_API_BASE_URL=https://api-staging.wifihaven.net`

The JVM Docker image still bundles the SPA as a fallback while the CDN path
is being validated (see TODO(#601) in `docker/Dockerfile`). The JVM-bundled
SPA uses relative API paths (`/api/...`), which is correct when accessed via
the Render service URL (e.g. `wifihaven-api-prod.onrender.com`). Once the
CDN Static Sites are confirmed healthy, the JVM bundling will be removed in
#601.

---

## 8. Deferred items

- **CI deploy hook** (#588): the Static Site and API services both need a
  deploy hook wired to CI so pushes to `main` trigger automatic redeploys.
  Until then, trigger redeploys manually from the Render dashboard.
- **JVM SPA cleanup** (#601): remove the web-build stage from
  `docker/Dockerfile` once CDN serving is confirmed.
- **Region tuning** (#590): `oregon` is a placeholder — confirm the region
  closest to the operator's physical location before traffic goes live.
