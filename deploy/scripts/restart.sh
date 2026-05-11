#!/usr/bin/env bash
# Restart the FamilyDNS stack in place (without recreating containers).
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"
exec docker compose -f docker-compose.prod.yml --env-file .env restart "$@"
