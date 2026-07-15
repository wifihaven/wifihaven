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
# #2135: Stripe billing. Empty WIFIHAVEN_STRIPE_SECRET_KEY (the default) DISABLES billing entirely —
# the self-hosted single-install path never bills, so the /api/billing/* admin routes 404 and the
# webhook no-ops. Cloud/staging set the secret + webhook signing secret (Render sync:false secrets)
# plus the test/live-mode price ids + promo code. Secrets are NEVER committed (docs/process/security.md).
: "${WIFIHAVEN_STRIPE_SECRET_KEY:=}"
: "${WIFIHAVEN_STRIPE_WEBHOOK_SECRET:=}"
: "${WIFIHAVEN_STRIPE_PRICE_MONTHLY:=}"
: "${WIFIHAVEN_STRIPE_PRICE_ANNUAL:=}"
: "${WIFIHAVEN_STRIPE_FOUNDING_PROMO:=}"
: "${WIFIHAVEN_STRIPE_APP_BASE_URL:=https://app.wifihaven.net}"
# #2137 (multi-tenant P5-6): the beta→paid flip lifecycle. Event-triggered — the
# cohort flip clock starts once THRESHOLD active beta households are seen, runs
# WINDOW_DAYS, then unconverted households lapse (enforcement stops permissively).
# Defaults match the design's v1 cohort values; tune without a code change.
: "${WIFIHAVEN_FLIP_THRESHOLD_HOUSEHOLDS:=25}"
: "${WIFIHAVEN_FLIP_WINDOW_DAYS:=60}"
: "${WIFIHAVEN_FLIP_ACTIVE_LOOKBACK_DAYS:=7}"
# #578: outbound email for admin notifications (block-page kid→parent requests).
# OFF unless BOTH the API key and from-address are set — empty defaults render an
# `email {}` block whose EmailConfig.enabled is false, so the Notifier logs only.
: "${WIFIHAVEN_EMAIL_RESEND_API_KEY:=}"
: "${WIFIHAVEN_EMAIL_FROM_ADDRESS:=}"
: "${WIFIHAVEN_EMAIL_APP_BASE_URL:=https://app.wifihaven.net}"
# #2199: Plain helpdesk integration (#2197). All DARK by default — empty keys render a `support {}`
# block whose SupportConfig gates are false, so the identified widget renders nothing and the write
# client is a no-op. Two independent gates: the WIDGET needs BOTH APP_ID + IDENTITY_SECRET; the
# WRITE API needs API_KEY. WEBHOOK_SECRET is declared now (config-complete) but consumed by #2200.
# The Claude API key for the #2200 responder is NOT here. Secrets NEVER committed.
: "${WIFIHAVEN_SUPPORT_PLAIN_API_KEY:=}"
: "${WIFIHAVEN_SUPPORT_PLAIN_WEBHOOK_SECRET:=}"
: "${WIFIHAVEN_SUPPORT_PLAIN_IDENTITY_SECRET:=}"
: "${WIFIHAVEN_SUPPORT_PLAIN_APP_ID:=}"

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
    enabled     = ${WIFIHAVEN_METRICS_ENABLED}
    scrapeToken = "${WIFIHAVEN_METRICS_SCRAPE_TOKEN}"
  }
  stripe {
    secretKey         = "${WIFIHAVEN_STRIPE_SECRET_KEY}"
    webhookSecret     = "${WIFIHAVEN_STRIPE_WEBHOOK_SECRET}"
    priceMonthly      = "${WIFIHAVEN_STRIPE_PRICE_MONTHLY}"
    priceAnnual       = "${WIFIHAVEN_STRIPE_PRICE_ANNUAL}"
    foundingPromoCode = "${WIFIHAVEN_STRIPE_FOUNDING_PROMO}"
    appBaseUrl        = "${WIFIHAVEN_STRIPE_APP_BASE_URL}"
  }
  email {
    resendApiKey = "${WIFIHAVEN_EMAIL_RESEND_API_KEY}"
    fromAddress  = "${WIFIHAVEN_EMAIL_FROM_ADDRESS}"
    appBaseUrl   = "${WIFIHAVEN_EMAIL_APP_BASE_URL}"
  }
  flip {
    thresholdHouseholds = ${WIFIHAVEN_FLIP_THRESHOLD_HOUSEHOLDS}
    windowDays          = ${WIFIHAVEN_FLIP_WINDOW_DAYS}
    activeLookbackDays  = ${WIFIHAVEN_FLIP_ACTIVE_LOOKBACK_DAYS}
  }
  support {
    plainApiKey         = "${WIFIHAVEN_SUPPORT_PLAIN_API_KEY}"
    plainWebhookSecret  = "${WIFIHAVEN_SUPPORT_PLAIN_WEBHOOK_SECRET}"
    plainIdentitySecret = "${WIFIHAVEN_SUPPORT_PLAIN_IDENTITY_SECRET}"
    plainAppId          = "${WIFIHAVEN_SUPPORT_PLAIN_APP_ID}"
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
