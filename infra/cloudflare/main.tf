# Cloudflare resources for wifihaven (#613).
#
# Manages:
#   - Three Cloudflare Pages projects (Direct Upload — CI pushes via wrangler):
#     wifihaven (SPA), wifihaven-staging (SPA), wifihaven-www (marketing).
#   - Five Pages custom domains (apex, www, staging, app, app-staging). apex/www
#     front the marketing project; app/app-staging front the SPA (#1842).
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
# host (app.wifihaven.net, below); apex and www both serve the static landing
# page. Repointing project_name replaces the domain attachment — a brief gap on
# the apex's Pages backing, which is acceptable for the marketing landing page.
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
  # Apex sends no *direct* mail: Resend's envelope sender is send.wifihaven.net
  # (see cloudflare_record.send_spf below), so SPF is evaluated on that
  # subdomain, and the resend._domainkey DKIM (aligned to the apex) carries
  # DMARC alignment for From: …@wifihaven.net. Apex stays -all (#613, #2202).
  comment = "SPF: no direct mail from apex; Resend sends via send. (#613, #2202)"
}

# ── Resend sending DNS records (send.wifihaven.net) ─────────────────────────
# Codify the Resend "sending" records for the #578 email transport (activation
# #2196) so DKIM/SPF/DMARC pass. Region us-east-1; Resend domain
# 129d9ba0-f9a2-495a-9650-47f8ff8e0c50. Plain create — the records did not
# exist before this change; Resend verifies the domain once these apply.

# DKIM: domain-unique 1024-bit public key from the Resend dashboard. Public
# key material — safe to commit.
resource "cloudflare_record" "resend_dkim" {
  zone_id = var.zone_id
  name    = "resend._domainkey"
  type    = "TXT"
  content = "\"p=MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCqrTo2ry2vqofKJLFJicNAjmpwmNJvlbDwtHZ8CGDXf1dfe3qEo0spNVSuWRyYWtWEccmEYuXHR8HVhdabPca0YXLsu4lS4tTbMx9k1Q8ArtaT8jbWI8PD7I4b3ZdHgkoULCCGbr3jw1qVz76TovFaxkIvRwQwlyVqX+DZQpuHEwIDAQAB\""
  proxied = false
  ttl     = 1
  comment = "Resend DKIM for wifihaven.net (#578, #2202)"
}

# Bounce / return-path MX for the send. subdomain (Resend/us-east-1).
resource "cloudflare_record" "send_mx" {
  zone_id  = var.zone_id
  name     = "send"
  type     = "MX"
  priority = 10
  content  = "feedback-smtp.us-east-1.amazonses.com"
  proxied  = false
  ttl      = 1
  comment  = "Resend bounce/return-path MX (#578, #2202)"
}

# SPF for the send. subdomain — the envelope sender Resend uses.
resource "cloudflare_record" "send_spf" {
  zone_id = var.zone_id
  name    = "send"
  type    = "TXT"
  content = "\"v=spf1 include:amazonses.com ~all\""
  proxied = false
  ttl     = 1
  comment = "Resend SPF for send.wifihaven.net (#578, #2202)"
}

# ── Email Routing: support@wifihaven.net → Plain inbox (#2198) ──────────────
# Inbound support mail is forwarded into WifiHaven's Plain workspace via its
# Postmark inbound address. This is the DNS/MX half of #2198 (epic #2197 /
# integration umbrella #2206). Cloudflare Email Routing receives on the apex
# and forwards with its own SRS return-path, so the apex SPF stays `-all`
# (cloudflare_record.spf above) — do NOT add a second apex `v=spf1` TXT; a
# second apex SPF record is a permerror, and forwarded mail is not evaluated
# against the apex SPF anyway.

# Enable Email Routing on the zone. On a Cloudflare-hosted zone this also
# provisions the route1/2/3.mx.cloudflare.net MX records automatically, with
# per-zone priorities that Cloudflare assigns itself. We intentionally do NOT
# author those MX as explicit cloudflare_record resources: their priorities are
# zone-specific (not fixed), so hardcoding guessed values would be wrong, and
# Email Routing manages them out-of-band (they will not appear in Terraform
# state, so they produce no plan drift). After the first apply the operator
# should confirm in the Cloudflare dash (Email → Email Routing) that the three
# MX records are present and routing status is "Enabled".
resource "cloudflare_email_routing_settings" "wifihaven" {
  zone_id = var.zone_id
  enabled = true
}

# Destination address: WifiHaven's live Plain workspace inbound address (Plain
# is backed by Postmark). Not a secret — safe to commit.
#
# One-time MANUAL verification required: Cloudflare emails a confirmation link
# to this destination address before it will forward to it. That mail lands in
# the Plain inbox; the operator must click the confirm link from inside Plain.
# Terraform CANNOT complete this step — the `verified` attribute stays null
# until the operator confirms, and forwarding does not work until then.
resource "cloudflare_email_routing_address" "plain_inbound" {
  account_id = var.account_id
  email      = "eb043c4cc4638221df2dcad8c30d772c@inbound.postmarkapp.com"
}

# Forward support@wifihaven.net → the Plain inbound address above.
resource "cloudflare_email_routing_rule" "support_to_plain" {
  zone_id = var.zone_id
  name    = "support@wifihaven.net -> Plain (#2198)"
  enabled = true

  matcher {
    type  = "literal"
    field = "to"
    value = "support@wifihaven.net"
  }

  action {
    type  = "forward"
    value = [cloudflare_email_routing_address.plain_inbound.email]
  }

  # Routing must be enabled on the zone before the rule can be created.
  depends_on = [cloudflare_email_routing_settings.wifihaven]
}

# ── Email Routing: support@staging.wifihaven.net → Plain inbox (#2198) ──────
# Same forward, but for the staging subdomain address, into a SEPARATE Plain
# inbound address (cfc4be…@inbound.postmarkapp.com — the staging channel).
#
# SUBDOMAIN ENABLEMENT IS AN OPERATOR STEP. Cloudflare Email Routing handles a
# subdomain only after that subdomain is added under Email Routing → Settings →
# Subdomains, which provisions its OWN route1/2/3.mx.cloudflare.net MX records
# on `staging.wifihaven.net` with Cloudflare-assigned (zone-specific) priorities.
# The v4 provider has no "email routing subdomain" resource, and the priorities
# are not fixed, so — exactly as with the apex MX above — we do NOT author those
# subdomain MX as cloudflare_record resources here (guessed priorities would be
# wrong). The operator must add the `staging` subdomain in the dash BEFORE this
# rule's apply can succeed (the API rejects a rule for a not-yet-enabled
# subdomain), then confirm the three subdomain MX records appear.
#
# DNS coexistence note: `staging.wifihaven.net` already carries a PROXIED CNAME
# (cloudflare_record.spa_staging → wifihaven-staging.pages.dev). Cloudflare
# flattens a proxied CNAME to A/AAAA at the edge and permits MX to coexist with
# it, so the subdomain MX and the existing Pages CNAME do not conflict — but the
# operator should verify staging.wifihaven.net still resolves for the SPA after
# enabling the subdomain.

# Staging destination address. Distinct from the prod inbound address so staging
# support mail lands in its own Plain channel. Not a secret. Same one-time
# manual verification: Cloudflare emails a confirm link that lands in this Plain
# inbox; the operator must click it. Terraform cannot complete verification.
resource "cloudflare_email_routing_address" "plain_inbound_staging" {
  account_id = var.account_id
  email      = "cfc4be868a29b61b0c6605edd081c18c@inbound.postmarkapp.com"
}

# Forward support@staging.wifihaven.net → the staging Plain inbound address.
resource "cloudflare_email_routing_rule" "support_staging_to_plain" {
  zone_id = var.zone_id
  name    = "support@staging.wifihaven.net -> Plain (#2198)"
  enabled = true

  matcher {
    type  = "literal"
    field = "to"
    value = "support@staging.wifihaven.net"
  }

  action {
    type  = "forward"
    value = [cloudflare_email_routing_address.plain_inbound_staging.email]
  }

  # Routing must be enabled on the zone (and the `staging` subdomain added in the
  # dash — see the block comment above) before this rule's apply can succeed.
  depends_on = [cloudflare_email_routing_settings.wifihaven]
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

# NOTE: there is no zone-level redirect ruleset. The marketing split (#1842)
# briefly shipped a `cloudflare_ruleset.redirects` with www→app / staging→
# app-staging redirects, but it was dropped (2026-06): creating a dynamic-
# redirect ruleset needs a Cloudflare token scope (Zone → Dynamic Redirect →
# Edit) that the deploy token doesn't carry, and the redirects are low-value —
# apex and www both simply front the marketing site, and staging keeps serving
# the staging SPA. (The apex `/blocked` router-compat shim was likewise dropped:
# existing routers were re-pointed to app.wifihaven.net; see openwrt/install.sh.)

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
