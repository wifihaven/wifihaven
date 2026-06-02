#!/usr/bin/env bash
# Tail logs. Defaults to the api container; pass a service name to switch:
#   ./logs.sh             # api
#   ./logs.sh postgres
#   ./logs.sh api postgres
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"
# compose.env (written by install.sh when the metrics overlay is enabled)
# defines COMPOSE_FILE_ARGS. Absent = prod stack only, as before.
COMPOSE_FILE_ARGS=(-f docker-compose.prod.yml)
[ -f compose.env ] && source compose.env
if [ "$#" -eq 0 ]; then
  set -- api
fi
exec docker compose "${COMPOSE_FILE_ARGS[@]}" --env-file .env logs -f "$@"
