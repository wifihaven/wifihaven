#!/usr/bin/env bash
# Pull the latest images from ghcr.io and restart the stack with them.
# Safe to re-run; data is preserved across the restart.
#
# `compose up -d` only recreates containers whose image changed, so
# postgres stays put unless its image was bumped, and the api restarts
# only when there's actually a new build to roll out.
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"
docker compose -f docker-compose.prod.yml --env-file .env pull
docker compose -f docker-compose.prod.yml --env-file .env up -d
