# wifihaven marketing site

Static MVP landing page served at the apex (`wifihaven.net`) and `www`. It is
deliberately **not** the admin SPA — the app lives on its own host,
[`app.wifihaven.net`](https://app.wifihaven.net) (epic #1832). This split lets
the public-facing site evolve without touching the app, and is what frees the
apex from double-duty.

## Layout

- `site/` — the publish directory (`pages_build_output_dir`). Plain static
  HTML/CSS, no build step, no toolchain. `site/index.html` is the whole page;
  `site/brand/` holds a self-contained copy of the brand assets it references
  (favicon, lockup, mascot, OG card).
- `wrangler.toml` — Cloudflare Pages config; targets the `wifihaven-www`
  project.

## Deploy

CI-driven: a push to `main` touching `web-marketing/**` triggers
[`.github/workflows/master-marketing.yml`](../.github/workflows/master-marketing.yml),
which runs `wrangler pages deploy` against the `wifihaven-www` project. There is
no build — the contents of `site/` are uploaded as-is.

The Pages **project**, its **custom domains** (apex + www), and the apex/www DNS
CNAMEs are declared in
[`infra/cloudflare/main.tf`](../infra/cloudflare/main.tf) and applied by
`master-cloudflare.yml`. On the very first rollout, that Terraform apply must
land before this deploy job runs (the project must exist); subsequent deploys
are independent.

## No redirects

There is no zone-level redirect ruleset. Apex and `www` both simply front this
marketing site, and `staging` keeps serving the staging SPA. (A `www → app` /
`staging → app-staging` ruleset shipped briefly but was dropped 2026-06 — it
needed a Cloudflare token scope the deploy token doesn't carry, and the
redirects are low-value; see `infra/cloudflare/main.tf`.)

There is also no apex `/blocked` router-compat shim: existing routers were
re-pointed to `app.wifihaven.net` for their block page (per #1842), and new
cloud installs default to it (`openwrt/install.sh`), so no router DNATs its
block page at the apex.
