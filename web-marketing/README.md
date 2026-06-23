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

The Pages **project**, its **custom domains** (apex + www), the apex/www DNS
CNAMEs, and the redirect rules are all declared in
[`infra/cloudflare/main.tf`](../infra/cloudflare/main.tf) and applied by
`master-cloudflare.yml`. On the very first rollout, that Terraform apply must
land before this deploy job runs (the project must exist); subsequent deploys
are independent.

## Redirects — NOT here

The `www → app` and `staging → app-staging` redirects are a zone-level
**dynamic-redirect ruleset in Terraform** (`cloudflare_ruleset.redirects` in
`infra/cloudflare/main.tf`), not a Pages `_redirects` file — they are
**host-level**, which a path-based Pages `_redirects` file can't express.
Keeping them in one Terraform ruleset is the single source of truth.

There is intentionally **no** apex `/blocked` router-compat shim: existing
routers were already re-pointed to `app.wifihaven.net` for their block page (per
#1842), and new cloud installs default to it (`openwrt/install.sh`), so no
router DNATs its block page at the apex. The apex therefore just serves this
marketing site for every path.
