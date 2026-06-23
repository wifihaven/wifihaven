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

The router-compat `/blocked` shim and the `www → app` / `staging → app-staging`
redirects are a zone-level **dynamic-redirect ruleset in Terraform**
(`cloudflare_ruleset.redirects` in `infra/cloudflare/main.tf`), not a Pages
`_redirects` file. Two reasons:

1. The `/blocked` shim is **merge-gating router back-compat**: routers enrolled
   before the rename still DNAT blocked HTTP/80 to the apex (`block_page_url` is
   a per-install UCI key that can't be pushed over the wire). The edge ruleset
   redirects `wifihaven.net/blocked*` → `app.wifihaven.net/blocked*` **302,
   query-preserving** (`?mac=&host=`), so those routers' block pages keep
   rendering. Because the ruleset runs at the edge *before* Pages serves
   anything, this static site's content can never shadow it.
2. The `www → app` and `staging → app-staging` redirects are **host-level**,
   which a path-based Pages `_redirects` file can't express.

Keeping all redirects in one Terraform ruleset is the single source of truth.
