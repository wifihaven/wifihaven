#!/usr/bin/env bash
# Stop the FamilyDNS stack. Containers are removed; the postgres data
# volume is preserved (use `docker volume rm` to wipe it explicitly).
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"
exec docker compose -f docker-compose.prod.yml --env-file .env down "$@"
