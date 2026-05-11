#!/usr/bin/env bash
# Tail logs. Defaults to the api container; pass a service name to switch:
#   ./logs.sh             # api
#   ./logs.sh postgres
#   ./logs.sh api postgres
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"
if [ "$#" -eq 0 ]; then
  set -- api
fi
exec docker compose -f docker-compose.prod.yml --env-file .env logs -f "$@"
