#!/usr/bin/env bash
# Start the FamilyDNS stack (idempotent — safe to re-run).
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"
exec docker compose -f docker-compose.prod.yml --env-file .env up -d "$@"
