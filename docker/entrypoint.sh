#!/usr/bin/env bash
# Container entrypoint: render application.conf from env, then start the API.
set -euo pipefail

# Required env (compose / GH Actions sets these):
: "${WIFIHAVEN_DB_HOST:=postgres}"
: "${WIFIHAVEN_DB_PORT:=5432}"
: "${WIFIHAVEN_DB_NAME:=wifihaven}"
: "${WIFIHAVEN_DB_USER:=wifihaven}"
: "${WIFIHAVEN_DB_PASSWORD:=wifihaven}"
: "${WIFIHAVEN_DB_POOL_SIZE:=5}"
: "${WIFIHAVEN_HTTP_HOST:=0.0.0.0}"
: "${WIFIHAVEN_HTTP_PORT:=8080}"
: "${WIFIHAVEN_STATIC_DIR:=/app/web}"
: "${WIFIHAVEN_SERVE_SPA:=true}"
: "${WIFIHAVEN_JWT_SECRET:=staging-jwt-secret-do-not-use-in-prod-32ch}"
: "${WIFIHAVEN_JWT_HOURS:=720}"  # #1607: 30d default; see application.conf.example
: "${WIFIHAVEN_LOG_LEVEL:=INFO}"
: "${WIFIHAVEN_DEBUG:=}"
: "${WIFIHAVEN_ALLOWED_ORIGINS:=}"
: "${WIFIHAVEN_UI_ALLOWED_HOSTS:=}"
# #1969: SPA-websocket Origin allowlist (design §8). Comma-separated Origin HOSTS
# allowed to upgrade GET /api/ws. Empty = cross-origin check off (self-hosted
# same-origin). Cloud/staging set it so a cross-site upgrade is rejected pre-101.
: "${WIFIHAVEN_WS_ALLOWED_ORIGINS:=}"
: "${WIFIHAVEN_METRICS_ENABLED:=true}"
: "${WIFIHAVEN_METRICS_SCRAPE_TOKEN:=}"
# #2266: cloud sets this true so a missing scrape token FAILS BOOT rather than serving
# /metrics unauthenticated. Default false keeps the loopback-open self-hosted behaviour.
: "${WIFIHAVEN_METRICS_REQUIRE_TOKEN:=false}"
# #2250: single per-environment SPA origin — the one place a deployment names the app host.
# The beta-invite link, Stripe Checkout/Portal return, and the alert/email "review in dashboard"
# link all derive from this unless individually overridden below. Cloud sets it once per env in
# render.yaml (prod → app.wifihaven.net, staging → app-staging.wifihaven.net); self-hosted/unset
# keeps the prod default. Before #2250 the beta invite base had NO env binding and the email base
# had one but no staging value, so both fell through to the prod default even on staging.
: "${WIFIHAVEN_APP_BASE_URL:=https://app.wifihaven.net}"
# #2135: Stripe billing. Empty WIFIHAVEN_STRIPE_SECRET_KEY (the default) DISABLES billing entirely —
# the self-hosted single-install path never bills, so the /api/billing/* admin routes 404 and the
# webhook no-ops. Cloud/staging set the secret + webhook signing secret (Render sync:false secrets)
# plus the test/live-mode price ids + promo code. Secrets are NEVER committed (docs/process/security.md).
# appBaseUrl defaults to the shared WIFIHAVEN_APP_BASE_URL (#2250) but keeps its own override.
# #2266: EXPLICIT enable flag — billing is off unless set true, NOT inferred from the key. When
# true, an unset secretKey FAILS BOOT (AppConfig.validateRequired). Default false = self-hosted off.
: "${WIFIHAVEN_STRIPE_ENABLED:=false}"
: "${WIFIHAVEN_STRIPE_SECRET_KEY:=}"
: "${WIFIHAVEN_STRIPE_WEBHOOK_SECRET:=}"
: "${WIFIHAVEN_STRIPE_PRICE_MONTHLY:=}"
: "${WIFIHAVEN_STRIPE_PRICE_ANNUAL:=}"
: "${WIFIHAVEN_STRIPE_FOUNDING_PROMO:=}"
: "${WIFIHAVEN_STRIPE_APP_BASE_URL:=${WIFIHAVEN_APP_BASE_URL}}"
# #2132 / #2250: beta request → invite. inviteBaseUrl is the SPA origin the operator-issued
# "/welcome?token=…" link points at; before #2250 it had NO env binding, so staging invites went
# to the prod SPA. Now it derives from the shared WIFIHAVEN_APP_BASE_URL (own override still honored).
# inviteTtlHours is left to the BetaConfig case-class default (Config.scala) — single-sourced there,
# so it is intentionally NOT re-hardcoded here.
: "${WIFIHAVEN_BETA_INVITE_BASE_URL:=${WIFIHAVEN_APP_BASE_URL}}"
# #2137 (multi-tenant P5-6): the beta→paid flip lifecycle. Event-triggered — the
# cohort flip clock starts once THRESHOLD active beta households are seen, runs
# WINDOW_DAYS, then unconverted households lapse (enforcement stops permissively).
# Defaults match the design's v1 cohort values; tune without a code change.
: "${WIFIHAVEN_FLIP_THRESHOLD_HOUSEHOLDS:=25}"
: "${WIFIHAVEN_FLIP_WINDOW_DAYS:=60}"
: "${WIFIHAVEN_FLIP_ACTIVE_LOOKBACK_DAYS:=7}"
# #578: outbound email for admin notifications (block-page kid→parent requests).
# #2266: EXPLICIT enable flag — email is off unless set true, NOT inferred from the secrets. When
# true, an unset resendApiKey/fromAddress FAILS BOOT (AppConfig.validateRequired). Default false =
# self-hosted off (the Notifier logs only).
: "${WIFIHAVEN_EMAIL_ENABLED:=false}"
: "${WIFIHAVEN_EMAIL_RESEND_API_KEY:=}"
: "${WIFIHAVEN_EMAIL_FROM_ADDRESS:=}"
# #2250: derive the "review in dashboard" link host from the shared SPA origin (own override still
# honored). Before #2250 this had an env binding but no staging value, so staging alert/email links
# pointed at the prod SPA.
: "${WIFIHAVEN_EMAIL_APP_BASE_URL:=${WIFIHAVEN_APP_BASE_URL}}"
# #2199: Plain helpdesk integration (#2197). All DARK by default — empty keys render a `support {}`
# block whose SupportConfig gates are false, so the identified widget renders nothing and the write
# client is a no-op. Two independent gates: the WIDGET needs BOTH APP_ID + IDENTITY_SECRET; the
# WRITE API needs API_KEY. Secrets NEVER committed.
: "${WIFIHAVEN_SUPPORT_PLAIN_API_KEY:=}"
: "${WIFIHAVEN_SUPPORT_PLAIN_WEBHOOK_SECRET:=}"
: "${WIFIHAVEN_SUPPORT_PLAIN_IDENTITY_SECRET:=}"
: "${WIFIHAVEN_SUPPORT_PLAIN_APP_ID:=}"
# #2200: the Claude support responder (cloud-agent dispatch per UI-originated Plain message, #2241
# access model). DARK unless the WHOLE responder chain is set: WEBHOOK_SECRET (above) + the
# Anthropic session-create triple (API key + pre-provisioned agent id + environment id, see
# deploy/support-agent/) + the AGENT_TOKEN_SECRET (HMAC for the per-session thread-/household-bound
# token — generate ≥32 random chars). GITHUB token is the fine-grained Issues:write-only support-bot
# credential (issue filing dark without it). AGENT_API_BASE is the public URL the cloud agent calls
# back to.
# #2265: EXPLICIT enable flags — no dark-by-default. false (the default) = feature deliberately
# off, logged at boot + visible on /api/health. true REQUIRES the full config chain: boot fails
# loudly listing every missing key. Flip via render.yaml PR at go-live, AFTER the secrets are set
# (config precedes code).
: "${WIFIHAVEN_SUPPORT_RESPONDER_ENABLED:=false}"
: "${WIFIHAVEN_SUPPORT_ISSUE_FILING_ENABLED:=false}"
: "${WIFIHAVEN_SUPPORT_ANTHROPIC_API_KEY:=}"
: "${WIFIHAVEN_SUPPORT_CLAUDE_AGENT_ID:=}"
: "${WIFIHAVEN_SUPPORT_CLAUDE_ENVIRONMENT_ID:=}"
: "${WIFIHAVEN_SUPPORT_AGENT_TOKEN_SECRET:=}"
: "${WIFIHAVEN_SUPPORT_AGENT_API_BASE:=https://api.wifihaven.net}"
# Which deployment this API is (staging | prod — a per-service literal in render.yaml, empty on
# self-hosted). Rides in every agent kickoff so the session knows whether it serves a real
# customer (prod) or an operator test (staging).
: "${WIFIHAVEN_SUPPORT_DEPLOYMENT_ENV:=}"
: "${WIFIHAVEN_SUPPORT_GITHUB_BOT_TOKEN:=}"

export WIFIHAVEN_LOG_LEVEL WIFIHAVEN_DEBUG

if [ -n "${WIFIHAVEN_DEBUG}" ]; then
  echo "[entrypoint] WARNING: WIFIHAVEN_DEBUG is set — debug endpoints will be mounted (loopback only). Disable in production."
fi

mkdir -p /app/config
cat > /app/config/application.conf <<EOF
wifihaven {
  db {
    host     = "${WIFIHAVEN_DB_HOST}"
    port     = ${WIFIHAVEN_DB_PORT}
    database = "${WIFIHAVEN_DB_NAME}"
    user     = "${WIFIHAVEN_DB_USER}"
    password = "${WIFIHAVEN_DB_PASSWORD}"
    poolSize = ${WIFIHAVEN_DB_POOL_SIZE}
  }
  http {
    host      = "${WIFIHAVEN_HTTP_HOST}"
    port      = ${WIFIHAVEN_HTTP_PORT}
    staticDir = "${WIFIHAVEN_STATIC_DIR}"
    serveSpa  = ${WIFIHAVEN_SERVE_SPA}
  }
  jwt {
    secret      = "${WIFIHAVEN_JWT_SECRET}"
    expiryHours = ${WIFIHAVEN_JWT_HOURS}
  }
  cors {
    allowedOrigins = "${WIFIHAVEN_ALLOWED_ORIGINS}"
  }
  policy {
    uiAllowedHosts = "${WIFIHAVEN_UI_ALLOWED_HOSTS}"
  }
  ws {
    allowedOrigins = "${WIFIHAVEN_WS_ALLOWED_ORIGINS}"
  }
  metrics {
    enabled      = ${WIFIHAVEN_METRICS_ENABLED}
    scrapeToken  = "${WIFIHAVEN_METRICS_SCRAPE_TOKEN}"
    requireToken = ${WIFIHAVEN_METRICS_REQUIRE_TOKEN}
  }
  stripe {
    enabled           = ${WIFIHAVEN_STRIPE_ENABLED}
    secretKey         = "${WIFIHAVEN_STRIPE_SECRET_KEY}"
    webhookSecret     = "${WIFIHAVEN_STRIPE_WEBHOOK_SECRET}"
    priceMonthly      = "${WIFIHAVEN_STRIPE_PRICE_MONTHLY}"
    priceAnnual       = "${WIFIHAVEN_STRIPE_PRICE_ANNUAL}"
    foundingPromoCode = "${WIFIHAVEN_STRIPE_FOUNDING_PROMO}"
    appBaseUrl        = "${WIFIHAVEN_STRIPE_APP_BASE_URL}"
  }
  email {
    enabled      = ${WIFIHAVEN_EMAIL_ENABLED}
    resendApiKey = "${WIFIHAVEN_EMAIL_RESEND_API_KEY}"
    fromAddress  = "${WIFIHAVEN_EMAIL_FROM_ADDRESS}"
    appBaseUrl   = "${WIFIHAVEN_EMAIL_APP_BASE_URL}"
  }
  beta {
    inviteBaseUrl = "${WIFIHAVEN_BETA_INVITE_BASE_URL}"
  }
  flip {
    thresholdHouseholds = ${WIFIHAVEN_FLIP_THRESHOLD_HOUSEHOLDS}
    windowDays          = ${WIFIHAVEN_FLIP_WINDOW_DAYS}
    activeLookbackDays  = ${WIFIHAVEN_FLIP_ACTIVE_LOOKBACK_DAYS}
  }
  support {
    plainApiKey           = "${WIFIHAVEN_SUPPORT_PLAIN_API_KEY}"
    plainWebhookSecret    = "${WIFIHAVEN_SUPPORT_PLAIN_WEBHOOK_SECRET}"
    plainIdentitySecret   = "${WIFIHAVEN_SUPPORT_PLAIN_IDENTITY_SECRET}"
    plainAppId            = "${WIFIHAVEN_SUPPORT_PLAIN_APP_ID}"
    responderEnabled      = ${WIFIHAVEN_SUPPORT_RESPONDER_ENABLED}
    issueFilingEnabled    = ${WIFIHAVEN_SUPPORT_ISSUE_FILING_ENABLED}
    anthropicApiKey       = "${WIFIHAVEN_SUPPORT_ANTHROPIC_API_KEY}"
    claudeAgentId         = "${WIFIHAVEN_SUPPORT_CLAUDE_AGENT_ID}"
    claudeEnvironmentId   = "${WIFIHAVEN_SUPPORT_CLAUDE_ENVIRONMENT_ID}"
    agentTokenSecret      = "${WIFIHAVEN_SUPPORT_AGENT_TOKEN_SECRET}"
    agentApiBase          = "${WIFIHAVEN_SUPPORT_AGENT_API_BASE}"
    deploymentEnv         = "${WIFIHAVEN_SUPPORT_DEPLOYMENT_ENV}"
    githubSupportBotToken = "${WIFIHAVEN_SUPPORT_GITHUB_BOT_TOKEN}"
  }
}
EOF

# Wait for postgres if requested
if [ "${WAIT_FOR_POSTGRES:-1}" = "1" ]; then
  echo "[entrypoint] Waiting for postgres at ${WIFIHAVEN_DB_HOST}:${WIFIHAVEN_DB_PORT}..."
  for i in $(seq 1 60); do
    if (echo > "/dev/tcp/${WIFIHAVEN_DB_HOST}/${WIFIHAVEN_DB_PORT}") 2>/dev/null; then
      echo "[entrypoint] postgres reachable"
      break
    fi
    sleep 1
  done
fi

cd /app
# JVM_HEAP_OPTS lets the deploy env override heap sizing (e.g. Render Hobby's
# ~512 MB cap needs -Xmx384m for OS + JIT + native headroom). Defaults match
# the historical baseline for self-hosted compose installs.
exec java ${JVM_HEAP_OPTS:--Xms256m -Xmx512m} -Dconfig.file=/app/config/application.conf -jar /app/api.jar
