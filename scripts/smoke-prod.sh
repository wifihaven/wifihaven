#!/usr/bin/env bash
# Smoke test for deploy/docker-compose.prod.yml.
#
# Brings the stack up with throwaway secrets, verifies:
#   1. api becomes healthy
#   2. postgres is NOT reachable from the host (no published port)
#   3. api responds on its published port
# Then tears everything down. Used in CI and as a sanity check before deploys.

set -euo pipefail

cd "$(dirname "$0")/.."

ENV_FILE="$(mktemp)"
trap 'rm -f "$ENV_FILE"; docker compose -f deploy/docker-compose.prod.yml --env-file "$ENV_FILE" down -v --remove-orphans >/dev/null 2>&1 || true' EXIT

cat > "$ENV_FILE" <<'EOF'
WIFIHAVEN_DB_NAME=familydns
WIFIHAVEN_DB_USER=familydns
WIFIHAVEN_DB_PASSWORD=smoke-test-password
WIFIHAVEN_JWT_SECRET=smoke-test-jwt-secret-min-32-chars-long-yes
WIFIHAVEN_JWT_HOURS=24
WIFIHAVEN_API_BIND=127.0.0.1
WIFIHAVEN_API_PORT=18080
EOF

COMPOSE=(docker compose -f deploy/docker-compose.prod.yml --env-file "$ENV_FILE")

echo "[smoke] starting stack..."
"${COMPOSE[@]}" up -d --build --quiet-pull

echo "[smoke] waiting for api to become healthy..."
for i in $(seq 1 60); do
  status=$("${COMPOSE[@]}" ps --format '{{.Service}} {{.Health}}' | awk '$1=="api"{print $2}')
  if [ "$status" = "healthy" ]; then
    echo "[smoke] api healthy"
    break
  fi
  if [ "$i" -eq 60 ]; then
    echo "[smoke] api never became healthy" >&2
    "${COMPOSE[@]}" logs --tail=80 api postgres >&2
    exit 1
  fi
  sleep 2
done

echo "[smoke] checking api responds on host port 18080..."
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'content-type: application/json' \
  -d '{}' http://127.0.0.1:18080/api/auth/login)
case "$code" in
  400|401) echo "[smoke] api responded with $code (expected)";;
  *)       echo "[smoke] api returned unexpected $code" >&2; exit 1;;
esac

echo "[smoke] confirming postgres is NOT exposed on the host..."
# 5432 (default) and 5433 (dev compose port) must both be unreachable from
# the host — postgres has no published port in the prod stack.
for port in 5432 5433; do
  if (echo > "/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
    echo "[smoke] postgres is reachable on 127.0.0.1:$port — leaked publish?" >&2
    exit 1
  fi
done
echo "[smoke] postgres is internal-only — good"

echo "[smoke] OK"
