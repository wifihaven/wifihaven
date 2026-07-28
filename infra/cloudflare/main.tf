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
#   - Web Analytics on the SPA Pages projects (wifihaven / wifihaven-staging).
#     It is ON — observed on app-staging, where Pages injected the beacon
#     (#2468); prod is assumed to match but was not checked. Dashboard state
#     only: the cloudflare ~> 4.40 provider pinned below does expose
#     web_analytics_tag / web_analytics_token, but only on
#     cloudflare_pages_project's `build_config` — the Pages *build* process,
#     which these Direct Upload projects (no `source` block; CI pushes built
#     assets via wrangler) never run — and cloudflare_web_analytics_site is
#     zone/host-scoped RUM (auto_install + zone_tag/host), a different
#     mechanism from the Pages project toggle. Neither was tested with an
#     apply. It is load-bearing on the SPA's CSP: with it on, Pages injects
#     https://static.cloudflareinsights.com/beacon.min.js, which
#     web/public/_headers must allowlist in script-src or
#     the beacon is blocked and collects nothing (#2468 — it had been blocked
#     since #2082). If Web Analytics is ever turned off, drop that host from
#     the script-src allowlist and its pin in web/src/security-headers.test.ts.
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
  content = "\"v=spf1 include:_spf.mx.cloudflare.net ~all\""
  proxied = false
  ttl     = 1
  # Apex SPF authorizes Cloudflare Email Routing to forward mail on our behalf
  # (#2198). Email Routing's forwarding return-path is under wifihaven.net, so
  # the destination (Postmark/Plain) evaluates THIS apex SPF; a strict `-all`
  # would make forwarded mail fail SPF, which is why Cloudflare flags `-all` as
  # conflicting and its docs say to MERGE existing SPF into
  # `include:_spf.mx.cloudflare.net ~all`. Resend still sends via
  # send.wifihaven.net (its own SPF on that subdomain, cloudflare_record.send_spf
  # below), unaffected by this apex value. Exactly ONE apex `v=spf1` TXT — do not
  # add a second (permerror). Superseded the earlier apex `-all` (#613/#2202),
  # which predated inbound Email Routing.
  comment = "SPF: authorize Cloudflare Email Routing forwarding; Resend via send. (#2198)"
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

# ── Plain OUTBOUND sending DNS (#2247, epic #2206) ──────────────────────────
# Plain sends outbound support mail (replies + notifications) via Postmark. Each
# sending domain gets its OWN per-domain DKIM selector so the DKIM `d=` strict-
# aligns with the From domain under our `adkim=s` DMARC:
#   support@wifihaven.net          → d=wifihaven.net          (prod selector)
#   support@staging.wifihaven.net  → d=staging.wifihaven.net  (staging selector)
# and a custom Postmark Return-Path (plain-bounces.* → pm.mtasv.net) so bounces
# route to Postmark with SPF resolved on the bounce subdomain (not the apex) —
# which is why NO apex SPF include for Postmark (spf.mtasv.net) is needed. DMARC
# passes on DKIM strict-alignment alone; SPF need not align (under our `aspf=s`
# the bounce subdomain plain-bounces.wifihaven.net would not strict-align with
# the apex From domain anyway, and DMARC is an OR of DKIM/SPF alignment). Do NOT
# add spf.mtasv.net to cloudflare_record.spf.
#
# Selectors (20260716…pm) are Postmark-issued and differ from the inbound
# cf2024-1 and the Resend `resend` DKIM, so no conflict. Public key material —
# safe to commit. Values copied verbatim from Plain's domain-setup screen; each
# `k=rsa; p=…` is <255 chars → single quoted TXT string (no chunking), same as
# cloudflare_record.resend_dkim.
#
# DNS must be applied + propagated BEFORE the operator clicks Plain's "Verify DNS
# and continue" per domain (Plain checks the records live).

# prod — DKIM for support@wifihaven.net (d=wifihaven.net).
resource "cloudflare_record" "plain_dkim_prod" {
  zone_id = var.zone_id
  name    = "20260716020234pm._domainkey"
  type    = "TXT"
  content = "\"k=rsa; p=MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQC1v+5pm0FRVoJTkwRYx/RmJo0QKL0qtC3vtBjq1ryosJbwLFhhMbw1QVjX9HGD1H5QLUKK16ThSv6wDIDhtR71+0lwHLa5Hw8AkckRKwicQMBFi558//uazuXHMa+GKJfJrZNtbH4WjVdqZNOmnXf06daTFIhz4qeulphfQO/RlQIDAQAB\""
  proxied = false
  ttl     = 1
  comment = "Plain outbound DKIM for wifihaven.net (Postmark, #2247, #2206)"
}

# prod — Postmark custom Return-Path (bounce domain) for wifihaven.net.
resource "cloudflare_record" "plain_bounces_prod" {
  zone_id = var.zone_id
  name    = "plain-bounces"
  type    = "CNAME"
  content = "pm.mtasv.net"
  proxied = false
  ttl     = 1
  comment = "Plain outbound Return-Path for wifihaven.net (Postmark, #2247, #2206)"
}

# staging — DKIM for support@staging.wifihaven.net (d=staging.wifihaven.net).
resource "cloudflare_record" "plain_dkim_staging" {
  zone_id = var.zone_id
  name    = "20260716163303pm._domainkey.staging"
  type    = "TXT"
  content = "\"k=rsa; p=MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCFthRPOhHmWGH5JfBsifjUdlTjGMJTdcqzO9LX4Hyc2nuKs5Jb6+6b3MreCFu2iPSfpk8xwJJh5Fa377dgcYjXhMNvxHavx9GTImndT3S6KxcLqyHcaFgHdCxN+p42unJkuvIsIw8/Uo2AyuntHrdfM+Y5PvFMw80/+H8beE78pQIDAQAB\""
  proxied = false
  ttl     = 1
  comment = "Plain outbound DKIM for staging.wifihaven.net (Postmark, #2247, #2206)"
}

# staging — Postmark custom Return-Path (bounce domain) for staging.wifihaven.net.
resource "cloudflare_record" "plain_bounces_staging" {
  zone_id = var.zone_id
  name    = "plain-bounces.staging"
  type    = "CNAME"
  content = "pm.mtasv.net"
  proxied = false
  ttl     = 1
  comment = "Plain outbound Return-Path for staging.wifihaven.net (Postmark, #2247, #2206)"
}

# ── Email Routing: support@wifihaven.net → Plain inbox (#2198) ──────────────
# Inbound support mail is forwarded into WifiHaven's Plain workspace via its
# Postmark inbound address. This is the DNS/MX half of #2198 (epic #2197 /
# integration umbrella #2206). Cloudflare Email Routing receives on the apex and
# forwards on our behalf, so the apex SPF must authorize Cloudflare — see
# cloudflare_record.spf above, now `v=spf1 include:_spf.mx.cloudflare.net ~all`.
# Keep exactly ONE apex `v=spf1` TXT (a second is a permerror); the earlier
# `-all` was wrong for forwarding (the return-path is under our domain, so the
# destination evaluates our apex SPF).

# ENABLING EMAIL ROUTING ON THE ZONE IS AN OPERATOR/DASHBOARD STEP — NOT managed
# by Terraform here. A *scoped* Cloudflare API token cannot perform the zone
# "enable Email Routing" onboarding action even with `Zone → Email Routing Rules
# → Edit`: the CI token creates addresses and rules fine, but the enable call
# returns `Authentication error (10000)` (verified on PR #2243's apply). This is
# the same class of Cloudflare-managed action as the MX records below, so we
# treat it identically: the operator enables Email Routing once in the dash
# (Email → Email Routing → Enable), which also provisions the apex
# route1/2/3.mx.cloudflare.net MX with Cloudflare-assigned (zone-specific)
# priorities. We do NOT author those MX as cloudflare_record resources (guessed
# priorities would be wrong; they live outside Terraform state, so no drift).
# There is deliberately no `cloudflare_email_routing_settings` resource — it only
# ever failed to apply. The rules below just require routing to already be on.

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

  # Routing must already be enabled on the zone (operator/dashboard step above)
  # for this rule to apply.
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
# are not fixed, so — exactly as with the apex MX and the zone enable above — we
# do NOT author those subdomain MX as cloudflare_record resources here (guessed
# priorities would be wrong). The operator must enable Email Routing on the zone
# AND add the `staging` subdomain in the dash BEFORE this rule's apply can
# succeed (the API rejects a rule for a not-yet-enabled subdomain), then confirm
# the three subdomain MX records appear.
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

  # Routing must already be enabled on the zone and the `staging` subdomain added
  # in the dash (see the block comment above) before this rule's apply can succeed.
}

# ── Email Routing: press@wifihaven.net → the press Email Worker (#2203) ─────────
# Unlike support (which FORWARDS to Plain), press is handled IN Cloudflare by an
# Email Worker (deploy/press-worker/) that signs + POSTs the message to the API's
# /api/press/inbound; the API dispatches a Claude press session and emails the
# reply back. So the routing action is `worker`, not `forward`.
#
# The Worker script itself is deployed declaratively by
# .github/workflows/master-press-worker.yml (cloudflare/wrangler-action, mirroring
# the SPA Pages deploy) — Terraform only BINDS the address to it here. Ordering:
# the Worker must exist before this rule applies; the worker workflow (on
# deploy/press-worker/** change) and this apply (on infra/cloudflare/** change)
# are independent, so a first apply before the worker's first deploy will fail and
# succeed on the next run once the worker exists. Both are automated on merge — no
# dashboard step. Routing must already be enabled on the zone (as for support).
resource "cloudflare_email_routing_rule" "press_to_worker" {
  zone_id = var.zone_id
  name    = "press@wifihaven.net -> press Email Worker (#2203)"
  enabled = true

  matcher {
    type  = "literal"
    field = "to"
    value = "press@wifihaven.net"
  }

  action {
    type  = "worker"
    value = ["wifihaven-press-worker"]
  }
}

# Staging: press@staging.wifihaven.net → the staging press Worker. Requires the
# `staging` subdomain to be enabled under Email Routing (the same operator step
# support's staging rule documents above); the staging worker is deployed by the
# same workflow (`--env staging`, name `wifihaven-press-worker-staging`).
resource "cloudflare_email_routing_rule" "press_staging_to_worker" {
  zone_id = var.zone_id
  name    = "press@staging.wifihaven.net -> press Email Worker (#2203)"
  enabled = true

  matcher {
    type  = "literal"
    field = "to"
    value = "press@staging.wifihaven.net"
  }

  action {
    type  = "worker"
    value = ["wifihaven-press-worker-staging"]
  }
}

# ── Email Routing: sameer@wifihaven.net → sameerbrenn@gmail.com (#2204) ──
# A personal alias the operator can hand out selectively (partners, escalations,
# "email me directly"). Unlike support (forwards to Plain) and press (Email
# Worker), this is a plain forward straight to a real, monitored external inbox —
# no app, no AI. Same Cloudflare Email Routing mechanism as support@ above; the
# apex SPF (cloudflare_record.spf, `include:_spf.mx.cloudflare.net ~all`) already
# authorizes Cloudflare to forward on our behalf, so this alias is covered without
# any SPF change. Local-part `sameer` is distinct from support@/press@ — no clash.
#
# Requires Email Routing to already be enabled on the zone (the operator/dashboard
# step documented in the support@ block above) for this rule to apply.

# Destination address: the operator's real external inbox. Not a secret.
#
# One-time MANUAL verification required: Cloudflare emails a confirmation link to
# sameerbrenn@gmail.com before it will forward to it. The operator must
# click that link from that inbox. Terraform CANNOT complete this step — the
# `verified` attribute stays null until confirmed, and forwarding does not work
# until then.
resource "cloudflare_email_routing_address" "operator_personal" {
  account_id = var.account_id
  email      = "sameerbrenn@gmail.com"
}

# Forward sameer@wifihaven.net → the operator's personal inbox above.
resource "cloudflare_email_routing_rule" "operator_personal_forward" {
  zone_id = var.zone_id
  name    = "sameer@wifihaven.net -> sameerbrenn@gmail.com (#2204)"
  enabled = true

  matcher {
    type  = "literal"
    field = "to"
    value = "sameer@wifihaven.net"
  }

  action {
    type  = "forward"
    value = [cloudflare_email_routing_address.operator_personal.email]
  }

  # Routing must already be enabled on the zone (operator/dashboard step in the
  # support@ block above) for this rule to apply.
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
