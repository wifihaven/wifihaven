#!/usr/bin/env bash
# Restart the WifiHaven stack in place (without recreating containers).
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"
# compose.env (written by install.sh when the metrics overlay is enabled)
# defines COMPOSE_FILE_ARGS. Absent = prod stack only, as before.
COMPOSE_FILE_ARGS=(-f docker-compose.prod.yml)
[ -f compose.env ] && source compose.env
exec docker compose "${COMPOSE_FILE_ARGS[@]}" --env-file .env restart "$@"
