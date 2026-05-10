#!/usr/bin/env bash
# Container entrypoint: render application.conf from env, then start the API.
set -euo pipefail

# Required env (compose / GH Actions sets these):
: "${FAMILYDNS_DB_HOST:=postgres}"
: "${FAMILYDNS_DB_PORT:=5432}"
: "${FAMILYDNS_DB_NAME:=familydns}"
: "${FAMILYDNS_DB_USER:=familydns}"
: "${FAMILYDNS_DB_PASSWORD:=familydns}"
: "${FAMILYDNS_HTTP_HOST:=0.0.0.0}"
: "${FAMILYDNS_HTTP_PORT:=8080}"
: "${FAMILYDNS_STATIC_DIR:=/app/web}"
: "${FAMILYDNS_JWT_SECRET:=staging-jwt-secret-do-not-use-in-prod-32ch}"
: "${FAMILYDNS_JWT_HOURS:=24}"
: "${FAMILYDNS_DNS_REFRESH:=10}"
: "${FAMILYDNS_DNS_PORT:=5354}"
: "${FAMILYDNS_DNS_LOCATION:=staging}"
: "${FAMILYDNS_DNS_UPSTREAM_PRIMARY:=1.1.1.1}"
: "${FAMILYDNS_DNS_UPSTREAM_SECONDARY:=8.8.8.8}"
: "${FAMILYDNS_DNS_UPSTREAM_PORT:=53}"
: "${FAMILYDNS_DNS_LOG_BATCH:=500}"
: "${FAMILYDNS_DNS_LOG_FLUSH:=5}"

mkdir -p /app/config
cat > /app/config/application.conf <<EOF
familydns {
  db {
    host     = "${FAMILYDNS_DB_HOST}"
    port     = ${FAMILYDNS_DB_PORT}
    database = "${FAMILYDNS_DB_NAME}"
    user     = "${FAMILYDNS_DB_USER}"
    password = "${FAMILYDNS_DB_PASSWORD}"
    poolSize = 5
  }
  http {
    host      = "${FAMILYDNS_HTTP_HOST}"
    port      = ${FAMILYDNS_HTTP_PORT}
    staticDir = "${FAMILYDNS_STATIC_DIR}"
  }
  jwt {
    secret      = "${FAMILYDNS_JWT_SECRET}"
    expiryHours = ${FAMILYDNS_JWT_HOURS}
  }
  dns {
    cacheRefreshSeconds = ${FAMILYDNS_DNS_REFRESH}
    port                = ${FAMILYDNS_DNS_PORT}
    location            = "${FAMILYDNS_DNS_LOCATION}"
    upstreamPrimary     = "${FAMILYDNS_DNS_UPSTREAM_PRIMARY}"
    upstreamSecondary   = "${FAMILYDNS_DNS_UPSTREAM_SECONDARY}"
    upstreamPort        = ${FAMILYDNS_DNS_UPSTREAM_PORT}
    logBatchSize        = ${FAMILYDNS_DNS_LOG_BATCH}
    logFlushSeconds     = ${FAMILYDNS_DNS_LOG_FLUSH}
  }
}
EOF

# Wait for postgres if requested
if [ "${WAIT_FOR_POSTGRES:-1}" = "1" ]; then
  echo "[entrypoint] Waiting for postgres at ${FAMILYDNS_DB_HOST}:${FAMILYDNS_DB_PORT}..."
  for i in $(seq 1 60); do
    if (echo > "/dev/tcp/${FAMILYDNS_DB_HOST}/${FAMILYDNS_DB_PORT}") 2>/dev/null; then
      echo "[entrypoint] postgres reachable"
      break
    fi
    sleep 1
  done
fi

cd /app
exec java -Xms256m -Xmx512m -Dconfig.file=/app/config/application.conf -jar /app/api.jar
