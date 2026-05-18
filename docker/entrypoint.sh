#!/usr/bin/env bash
# Container entrypoint: render application.conf from env, then start the API.
set -euo pipefail

# Required env (compose / GH Actions sets these):
: "${WIFIHAVEN_DB_HOST:=postgres}"
: "${WIFIHAVEN_DB_PORT:=5432}"
: "${WIFIHAVEN_DB_NAME:=wifihaven}"
: "${WIFIHAVEN_DB_USER:=wifihaven}"
: "${WIFIHAVEN_DB_PASSWORD:=wifihaven}"
: "${WIFIHAVEN_HTTP_HOST:=0.0.0.0}"
: "${WIFIHAVEN_HTTP_PORT:=8080}"
: "${WIFIHAVEN_STATIC_DIR:=/app/web}"
: "${WIFIHAVEN_JWT_SECRET:=staging-jwt-secret-do-not-use-in-prod-32ch}"
: "${WIFIHAVEN_JWT_HOURS:=24}"
: "${WIFIHAVEN_LOG_LEVEL:=INFO}"
: "${WIFIHAVEN_DEBUG:=}"
: "${WIFIHAVEN_ALLOWED_ORIGINS:=}"

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
    poolSize = 5
  }
  http {
    host      = "${WIFIHAVEN_HTTP_HOST}"
    port      = ${WIFIHAVEN_HTTP_PORT}
    staticDir = "${WIFIHAVEN_STATIC_DIR}"
  }
  jwt {
    secret      = "${WIFIHAVEN_JWT_SECRET}"
    expiryHours = ${WIFIHAVEN_JWT_HOURS}
  }
  cors {
    allowedOrigins = "${WIFIHAVEN_ALLOWED_ORIGINS}"
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
exec java -Xms256m -Xmx512m -Dconfig.file=/app/config/application.conf -jar /app/api.jar
