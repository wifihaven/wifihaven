#!/usr/bin/env bash
# Show container status (containers, ports, health).
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"
exec docker compose -f docker-compose.prod.yml --env-file .env ps "$@"
