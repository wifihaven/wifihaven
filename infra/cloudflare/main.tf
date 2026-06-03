# Cloudflare resources for wifihaven (#613).
#
# Manages:
#   - Two Cloudflare Pages projects (Direct Upload — CI pushes via wrangler).
#   - Three Pages custom domains (apex, www, staging).
#   - Two DNS-only CNAMEs pointing api / api-staging at Render.
#
# Does NOT manage:
#   - The wifihaven.net zone itself (added once via the Cloudflare dash; NS
#     flip at the registrar is a one-shot manual step).
#   - The Cloudflare API token (operator creates in the dash; secret stored
#     in GitHub repo + 1Password).
#
# State: local backend. The state file holds resource IDs but no secrets
# (the API token comes from CLOUDFLARE_API_TOKEN env, not a tfvars file).
# Commit terraform.tfstate to a private store or move to a remote backend
# before sharing infra ops with another operator. TODO(#613-followup):
# move to a remote backend.

terraform {
  required_version = ">= 1.6"
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

# ── Pages custom domains ────────────────────────────────────────────────────
# When the apex zone is on the same Cloudflare account, the provider wires
# the underlying DNS records automatically. Cert issuance is DNS-01 — no
# ACME path-interception class of bug (#609).

resource "cloudflare_pages_domain" "apex" {
  account_id   = var.account_id
  project_name = cloudflare_pages_project.prod.name
  domain       = "wifihaven.net"
}

resource "cloudflare_pages_domain" "www" {
  account_id   = var.account_id
  project_name = cloudflare_pages_project.prod.name
  domain       = "www.wifihaven.net"
}

resource "cloudflare_pages_domain" "staging" {
  account_id   = var.account_id
  project_name = cloudflare_pages_project.staging.name
  domain       = "staging.wifihaven.net"
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

resource "cloudflare_record" "spa_apex" {
  zone_id = var.zone_id
  name    = "wifihaven.net"
  type    = "CNAME"
  content = "wifihaven.pages.dev"
  proxied = true
  ttl     = 1
  comment = "Cloudflare Pages wifihaven (#613)"
}

resource "cloudflare_record" "spa_www" {
  zone_id = var.zone_id
  name    = "www"
  type    = "CNAME"
  content = "wifihaven.pages.dev"
  proxied = true
  ttl     = 1
  comment = "Cloudflare Pages wifihaven (#613)"
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
# the leaf A record is never hidden behind Cloudflare's edge. The leaf IP is
# 93.184.216.34 (IANA's example.com — stable, responds to HTTP/80 with a
# non-block-page body, and is already used as the harness's reference
# "internet-reachable" origin in scripts/e2e/lib/wait.py).
#
# Prerequisite: `terraform apply` against the wifihaven.net zone must run
# (operator-applied; see PR #1351 description) before the e2e gate can resolve
# this chain from the router VM. CI validates fmt + validate only; it does NOT
# apply. See .github/workflows/ci.yml cloudflare-terraform job.

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
