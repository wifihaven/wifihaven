#!/usr/bin/env bash
# Pull the latest images from ghcr.io and restart the stack with them.
# Safe to re-run; data is preserved across the restart.
#
# `compose up -d` only recreates containers whose image changed, so
# postgres stays put unless its image was bumped, and the api restarts
# only when there's actually a new build to roll out.
#
# Also refreshes docker-compose.prod.yml from the repo before pulling, so
# new env vars / service additions in a release reach the container instead
# of silently no-opping against a stale local compose file.
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"

REPO_RAW="${WIFIHAVEN_REPO_RAW:-https://raw.githubusercontent.com/wifihaven/wifihaven/main}"
COMPOSE_URL="${REPO_RAW}/deploy/docker-compose.prod.yml"

# Refresh docker-compose.prod.yml from main. Preserve the old file as .bak
# so a network blip doesn't wipe out a working install.
if [ -f docker-compose.prod.yml ]; then
  cp docker-compose.prod.yml docker-compose.prod.yml.bak
fi
if curl -fsSL "$COMPOSE_URL" -o docker-compose.prod.yml.new; then
  mv docker-compose.prod.yml.new docker-compose.prod.yml
else
  echo "warn: failed to fetch $COMPOSE_URL; keeping existing docker-compose.prod.yml" >&2
  rm -f docker-compose.prod.yml.new
fi

docker compose -f docker-compose.prod.yml --env-file .env pull
docker compose -f docker-compose.prod.yml --env-file .env up -d
