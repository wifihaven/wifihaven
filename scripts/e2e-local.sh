#!/usr/bin/env bash
# One-shot local validation: bring up an isolated staging stack and run
# both e2e suites against it. Safe to run alongside a separately-deployed
# familydns stack — uses a distinct compose project + ports.
#
# Usage:
#   scripts/e2e-local.sh                # build (if needed), run, tear down
#   KEEP_STACK=1 scripts/e2e-local.sh   # leave the stack up after tests
#   NO_BUILD=1   scripts/e2e-local.sh   # skip image rebuild
#   E2E_PORT=18080 scripts/e2e-local.sh # override host port (default 18080)
set -euo pipefail

cd "$(dirname "$0")/.."

PORT="${E2E_PORT:-18080}"
PROJECT="${E2E_PROJECT:-familydns-e2e}"
COMPOSE=(docker compose -p "$PROJECT" -f docker/docker-compose.yml)

# Override the api service's host port via an inline compose override so we
# don't collide with anything already bound to 8080.
OVERRIDE=$(mktemp); trap 'rm -f "$OVERRIDE"' EXIT
cat >"$OVERRIDE" <<EOF
services:
  api:
    ports: !override
      - "127.0.0.1:${PORT}:8080"
  postgres:
    ports: !override []
EOF
COMPOSE+=(-f "$OVERRIDE")

cleanup() {
  if [ -z "${KEEP_STACK:-}" ]; then
    echo
    echo "▶ Tearing down stack ($PROJECT)"
    "${COMPOSE[@]}" down -v >/dev/null 2>&1 || true
  else
    echo
    echo "▶ KEEP_STACK=1 — leaving $PROJECT up on http://127.0.0.1:${PORT}"
  fi
}
trap cleanup EXIT

echo "▶ Bringing up stack (project=$PROJECT, port=$PORT)"
BUILD_FLAG=(--build)
[ -n "${NO_BUILD:-}" ] && BUILD_FLAG=()
"${COMPOSE[@]}" up -d "${BUILD_FLAG[@]}" --wait

export E2E_BASE_URL="http://127.0.0.1:${PORT}"
echo "▶ E2E_BASE_URL=$E2E_BASE_URL"

scripts/e2e-tests.sh
scripts/e2e-router.sh

echo
echo "✅ All local e2e validations passed."
