# Cloudflare resources for wifihaven (#613).
#
# Manages:
#   - Three Cloudflare Pages projects (Direct Upload — CI pushes via wrangler):
#     wifihaven (SPA), wifihaven-staging (SPA), wifihaven-www (marketing).
#   - Five Pages custom domains (apex, www, staging, app, app-staging). apex/www
#     front the marketing project; app/app-staging front the SPA (#1842).
#   - A zone-level dynamic-redirect ruleset: the router-compat /blocked shim
#     plus www/staging → app redirects (#1842).
#   - Two DNS-only CNAMEs pointing api / api-staging at Render.
#
# Does NOT manage:
#   - The wifihaven.net zone itself (added once via the Cloudflare dash; NS
#     flip at the registrar is a one-shot manual step).
#   - The Cloudflare API token (operator creates in the dash; secret stored
#     in GitHub repo + 1Password).
#
# State: remote backend on HCP Terraform (the `cloud {}` block below), free
# tier, with native state locking. The state holds resource IDs but no secrets
# (the API token comes from CLOUDFLARE_API_TOKEN env, not a tfvars file).
# Applies are CI-driven on merge to main via
# .github/workflows/master-cloudflare.yml; see README.md for the one-time
# backend setup + local-state migration runbook (#1357, formerly the
# #613-followup remote-state TODO).

terraform {
  required_version = ">= 1.6"

  # Remote state on HCP Terraform (Terraform Cloud), free tier. Native state
  # locking means CI applies can't race or clobber the operator's state, and a
  # fresh CI checkout reads the canonical state instead of seeing an empty
  # local one and trying to recreate the ~10 live resources (#1357; closes the
  # old #613-followup local-state marker).
  #
  # Org + workspace come from TF_CLOUD_ORGANIZATION / TF_WORKSPACE env vars
  # (set in .github/workflows/master-cloudflare.yml and documented in
  # README.md), so this block stays account- and creds-agnostic. Auth is the
  # TF_TOKEN_app_terraform_io env var. The workspace runs in *local* execution
  # mode: HCP only stores state + provides locking, while `terraform apply`
  # runs on the GitHub runner (mirrors the infra/grafana pipeline) with the
  # Cloudflare token from GitHub secrets.
  cloud {}

  required_providers {
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 4.40"
    }
  }
}

provider "cloudflare" {
  # Reads CLOUDFLARE_API_TOKEN from the environment.
}

# ── Pages projects ──────────────────────────────────────────────────────────

resource "cloudflare_pages_project" "prod" {
  account_id        = var.account_id
  name              = "wifihaven"
  production_branch = "main"
  # No `source` block → Direct Upload project. CI pushes via wrangler.
}

resource "cloudflare_pages_project" "staging" {
  account_id        = var.account_id
  name              = "wifihaven-staging"
  production_branch = "main"
}

# Marketing site project (#1842 / epic #1832). Fronts the apex + www, distinct
# from the SPA (which now lives on the `wifihaven` project at app.wifihaven.net).
# Direct Upload — CI pushes the static web-marketing/site/ via wrangler from
# .github/workflows/master-marketing.yml. On first rollout this apply must land
# before that deploy job runs (the project must exist for wrangler to target).
resource "cloudflare_pages_project" "marketing" {
  account_id        = var.account_id
  name              = "wifihaven-www"
  production_branch = "main"
}

# ── Pages custom domains ────────────────────────────────────────────────────
# When the apex zone is on the same Cloudflare account, the provider wires
# the underlying DNS records automatically. Cert issuance is DNS-01 — no
# ACME path-interception class of bug (#609).

# Apex + www now front the MARKETING project (#1842). The SPA moved to its own
# host (app.wifihaven.net, below); the apex serves the static landing page and
# www 301-redirects to the app (redirect ruleset below). Repointing project_name
# replaces the domain attachment — a brief gap on the apex's Pages backing, but
# the /blocked router-compat shim is an edge redirect rule that fires before
# Pages, so blocked-device block pages keep working throughout.
resource "cloudflare_pages_domain" "apex" {
  account_id   = var.account_id
  project_name = cloudflare_pages_project.marketing.name # "wifihaven-www"
  domain       = "wifihaven.net"
}

resource "cloudflare_pages_domain" "www" {
  account_id   = var.account_id
  project_name = cloudflare_pages_project.marketing.name # "wifihaven-www"
  domain       = "www.wifihaven.net"
}

resource "cloudflare_pages_domain" "staging" {
  account_id   = var.account_id
  project_name = cloudflare_pages_project.staging.name
  domain       = "staging.wifihaven.net"
}

# App host on the existing prod/staging Pages projects (#1832, sub-issue #1839).
# ADDITIVE: the SPA also answers on app.* alongside the existing apex/www so
# nothing breaks while we verify. Cut-over of the canonical host, CORS/JWT, and
# the staging→app redirect are later sub-issues (#1840/#1841); not done here.
resource "cloudflare_pages_domain" "app" {
  account_id   = var.account_id
  project_name = cloudflare_pages_project.prod.name # "wifihaven"
  domain       = "app.wifihaven.net"
}

resource "cloudflare_pages_domain" "app_staging" {
  account_id   = var.account_id
  project_name = cloudflare_pages_project.staging.name # "wifihaven-staging"
  domain       = "app-staging.wifihaven.net"
}

# ── DNS: API CNAMEs to Render ───────────────────────────────────────────────
# DNS-only (grey cloud). Proxying through Cloudflare's edge would terminate
# TLS at Cloudflare and require additional work for the API to read the real
# client IP — out of scope.

resource "cloudflare_record" "spf" {
  zone_id = var.zone_id
  name    = "wifihaven.net"
  type    = "TXT"
  content = "\"v=spf1 -all\""
  proxied = false
  ttl     = 1
  comment = "SPF: no mail from this domain (#613)"
}

# Apex + www CNAME to the MARKETING project's .pages.dev (#1842). Stays proxied
# (orange cloud) so the zone-level redirect ruleset below can fire at the edge.
resource "cloudflare_record" "spa_apex" {
  zone_id = var.zone_id
  name    = "wifihaven.net"
  type    = "CNAME"
  content = "wifihaven-www.pages.dev"
  proxied = true
  ttl     = 1
  comment = "Cloudflare Pages wifihaven-www — marketing (#1842)"
}

resource "cloudflare_record" "spa_www" {
  zone_id = var.zone_id
  name    = "www"
  type    = "CNAME"
  content = "wifihaven-www.pages.dev"
  proxied = true
  ttl     = 1
  comment = "Cloudflare Pages wifihaven-www — marketing (#1842)"
}

resource "cloudflare_record" "spa_staging" {
  zone_id = var.zone_id
  name    = "staging"
  type    = "CNAME"
  content = "wifihaven-staging.pages.dev"
  proxied = true
  ttl     = 1
  comment = "Cloudflare Pages wifihaven-staging (#613)"
}

resource "cloudflare_record" "spa_app" {
  zone_id = var.zone_id
  name    = "app"
  type    = "CNAME"
  content = "wifihaven.pages.dev"
  proxied = true
  ttl     = 1
  comment = "Cloudflare Pages wifihaven — app host (#1832)"
}

resource "cloudflare_record" "spa_app_staging" {
  zone_id = var.zone_id
  name    = "app-staging"
  type    = "CNAME"
  content = "wifihaven-staging.pages.dev"
  proxied = true
  ttl     = 1
  comment = "Cloudflare Pages wifihaven-staging — app host (#1832)"
}

# ── Redirects — marketing split + router-compat /blocked shim (#1842) ───────
#
# A single zone-level dynamic-redirect ruleset. Rules are evaluated in the order
# listed; the first match wins. These run at the edge BEFORE Cloudflare Pages
# serves any content, so the marketing site's static responses can never shadow
# the /blocked shim.
#
# The /blocked shim is the merge-gating back-compat net for the host rename:
# routers enrolled before the rename DNAT blocked HTTP/80 to the apex
# (block_page_url is a per-install UCI key that can't be pushed over the wire),
# so the apex MUST keep answering /blocked* by redirecting to the app host —
# preserving ?mac=&host= so the block page still resolves its reason. 302 (not
# 301) keeps it retargetable without fighting browser caches; this is about
# retargetability, not lifetime (the shim lives as long as pre-rename routers do).
resource "cloudflare_ruleset" "redirects" {
  zone_id     = var.zone_id
  name        = "wifihaven redirects"
  description = "Marketing split: /blocked router-compat shim + www/staging → app (#1842 / #1832)"
  kind        = "zone"
  phase       = "http_request_dynamic_redirect"

  # 1. Router-compat /blocked shim — MUST be first and query-preserving.
  #    wifihaven.net/blocked* → app.wifihaven.net/blocked* (302, keep ?mac=&host=).
  rules {
    ref         = "blocked_compat_shim"
    description = "Router-compat: pre-rename routers DNAT blocked HTTP/80 to the apex; keep /blocked working on the app host, preserving ?mac=&host= (#1842)"
    expression  = "(http.host eq \"wifihaven.net\" and starts_with(http.request.uri.path, \"/blocked\"))"
    action      = "redirect"
    enabled     = true
    action_parameters {
      from_value {
        status_code = 302
        target_url {
          expression = "concat(\"https://app.wifihaven.net\", http.request.uri.path)"
        }
        preserve_query_string = true
      }
    }
  }

  # 2. Old SPA bookmarks on www → app (301, query-preserving).
  rules {
    ref         = "www_to_app"
    description = "www.wifihaven.net/* → app.wifihaven.net/:splat (#1842)"
    expression  = "(http.host eq \"www.wifihaven.net\")"
    action      = "redirect"
    enabled     = true
    action_parameters {
      from_value {
        status_code = 301
        target_url {
          expression = "concat(\"https://app.wifihaven.net\", http.request.uri.path)"
        }
        preserve_query_string = true
      }
    }
  }

  # 3. Staging mirror: staging → app-staging (301, query-preserving).
  rules {
    ref         = "staging_to_app_staging"
    description = "staging.wifihaven.net/* → app-staging.wifihaven.net/:splat (#1842)"
    expression  = "(http.host eq \"staging.wifihaven.net\")"
    action      = "redirect"
    enabled     = true
    action_parameters {
      from_value {
        status_code = 301
        target_url {
          expression = "concat(\"https://app-staging.wifihaven.net\", http.request.uri.path)"
        }
        preserve_query_string = true
      }
    }
  }
}

resource "cloudflare_record" "api_prod" {
  zone_id = var.zone_id
  name    = "api"
  type    = "CNAME"
  content = var.api_prod_cname_target
  proxied = false
  ttl     = 1 # auto
  comment = "Render wifihaven-api-prod (#613)"
}

resource "cloudflare_record" "api_staging" {
  zone_id = var.zone_id
  name    = "api-staging"
  type    = "CNAME"
  content = var.api_staging_cname_target
  proxied = false
  ttl     = 1
  comment = "Render wifihaven-api-staging (#613)"
}

# ── e2e CNAME chain — controllable test fixture for #1351 ──────────────────
#
# A three-hop chain used by scripts/e2e/scenarios_fake/test_cname_direct_requery.py
# to prove the dns-tail ea_/eb_ populator handles directly-queried CNAME targets
# (#1346/#1349) end-to-end on a real router VM.
#
# Records are DNS-only (proxied=false) so the chain resolves as authored and
# the leaf A record is never hidden behind Cloudflare's edge. The leaf IP
# defaults to 192.0.2.10 (var.e2e_edge_ip) — RFC 5737 TEST-NET-1, reserved-for-
# documentation and GUARANTEED never globally routed or reassigned (#1360). The
# previous value 93.184.216.34 (legacy IANA example.com) was decommissioned
# ~2025 and its HTTP/80 went dead, silently red-gating router releases. The
# leaf is intentionally unroutable: the block-path e2e tests (G1 eb_, G4 bl_)
# DNAT port 80 at prerouting before the dest is routed, so reachability is
# irrelevant to them; the allow-path tests (G2/G3) xfail when the leaf is
# unreachable rather than depending on a live third-party origin (see
# scripts/e2e/scenarios_fake/test_cname_direct_requery.py).
#
# These records are applied by the CI pipeline (master-cloudflare.yml) on merge
# to main — no manual `terraform apply` is needed going forward (#1357/#1358).
# The ci.yml cloudflare-terraform job still validates fmt + validate on every PR
# but does NOT apply.

resource "cloudflare_record" "e2e_brand" {
  zone_id = var.zone_id
  name    = "e2e-brand"
  type    = "CNAME"
  content = "e2e-mid.wifihaven.net"
  proxied = false
  ttl     = 300
  comment = "e2e test fixture: branded host for direct-requery CNAME test (#1351)"
}

resource "cloudflare_record" "e2e_mid" {
  zone_id = var.zone_id
  name    = "e2e-mid"
  type    = "CNAME"
  content = "e2e-edge.wifihaven.net"
  proxied = false
  ttl     = 300
  comment = "e2e test fixture: mid-hop for direct-requery CNAME test (#1351)"
}

resource "cloudflare_record" "e2e_edge" {
  zone_id = var.zone_id
  name    = "e2e-edge"
  type    = "A"
  content = var.e2e_edge_ip
  proxied = false
  ttl     = 300
  comment = "e2e test fixture: leaf A record for direct-requery CNAME test (#1351)"
}

# AAAA sibling for the v6 hostname-attribution Gate 2 scenario (#1677,
# CD-tier follow-up to PR #1673 / #1668). Pairing the AAAA with the
# existing A above produces the realistic dual-stack shape of a production
# host: one name returning both families. The address default is RFC 3849
# documentation space (2001:db8::/32) — GUARANTEED never globally routed,
# same safety guarantee as the v4 leaf's RFC 5737 TEST-NET-1. The
# attribution scenario needs only the LAN-side conntrack NEW event on the
# v6 SYN; the WAN-side drop is irrelevant (qemu SLIRP is v4-only).
resource "cloudflare_record" "e2e_edge_aaaa" {
  zone_id = var.zone_id
  name    = "e2e-edge"
  type    = "AAAA"
  content = var.e2e_edge_ip6
  proxied = false
  ttl     = 300
  comment = "e2e test fixture: leaf AAAA record for v6 attribution Gate 2 scenario (#1677)"
}
