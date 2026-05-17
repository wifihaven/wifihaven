#!/usr/bin/env bash
# Container entrypoint: render application.conf from env, then start the API.
set -euo pipefail

# Required env (compose / GH Actions sets these):
: "${FAMILYDNS_DB_HOST:=postgres}"
: "${FAMILYDNS_DB_PORT:=5432}"
: "${FAMILYDNS_DB_NAME:=wifihaven}"
: "${FAMILYDNS_DB_USER:=wifihaven}"
: "${FAMILYDNS_DB_PASSWORD:=wifihaven}"
: "${FAMILYDNS_HTTP_HOST:=0.0.0.0}"
: "${FAMILYDNS_HTTP_PORT:=8080}"
: "${FAMILYDNS_STATIC_DIR:=/app/web}"
: "${FAMILYDNS_JWT_SECRET:=staging-jwt-secret-do-not-use-in-prod-32ch}"
: "${FAMILYDNS_JWT_HOURS:=24}"
: "${FAMILYDNS_LOG_LEVEL:=INFO}"
: "${FAMILYDNS_DEBUG:=}"

export FAMILYDNS_LOG_LEVEL FAMILYDNS_DEBUG

if [ -n "${FAMILYDNS_DEBUG}" ]; then
  echo "[entrypoint] WARNING: FAMILYDNS_DEBUG is set — debug endpoints will be mounted (loopback only). Disable in production."
fi

mkdir -p /app/config
cat > /app/config/application.conf <<EOF
wifihaven {
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
