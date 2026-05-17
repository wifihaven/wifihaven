#!/usr/bin/env bash
# Deploy the API to the dev server by building the docker image on the
# server itself from a chosen branch. No registry push, no image transfer.
#
# Server layout:
#   ~/familydns/   git checkout (source)
#   ~/.wifihaven/  runtime: docker-compose.prod.yml, .env, helper scripts
#
# The runtime compose file pins ghcr.io/sameerparekh/familydns-api:latest.
# We build locally on the server and tag with that exact reference; the
# next `start.sh` (compose up -d) picks up the new image id and recreates
# the api container.
#
# Usage:
#   scripts/deploy-api-from-branch.sh                  # deploy main
#   scripts/deploy-api-from-branch.sh some-branch      # deploy a branch
#   API_HOST=user@host scripts/deploy-api-from-branch.sh some-branch
#
# Caveat: ~/.wifihaven/update.sh runs `docker compose pull` and will
# clobber this locally-built image with whatever is on ghcr. After the
# branch is merged and ghcr publishes a new :latest, run update.sh to
# swap back.

set -euo pipefail

API_HOST="${API_HOST:-192.168.10.43}"
SRC_DIR="${SRC_DIR:-\$HOME/familydns}"
RUN_DIR="${RUN_DIR:-\$HOME/.wifihaven}"
IMAGE="ghcr.io/sameerparekh/familydns-api:latest"
BRANCH="${1:-main}"

echo "==> deploying branch '$BRANCH' to $API_HOST"

ssh "$API_HOST" bash -s <<EOF
set -euo pipefail
cd $SRC_DIR
echo "--> fetch + checkout $BRANCH"
git fetch --prune origin
git checkout "$BRANCH"
git reset --hard "origin/$BRANCH"
echo "--> building $IMAGE"
docker build -f docker/Dockerfile -t "$IMAGE" .
echo "--> recreating api container"
bash $RUN_DIR/start.sh
echo "--> waiting for healthcheck"
cd $RUN_DIR
cid=\$(docker compose -f docker-compose.prod.yml --env-file .env ps -q api)
for i in \$(seq 1 30); do
  status=\$(docker inspect -f '{{.State.Health.Status}}' "\$cid" 2>/dev/null || echo unknown)
  if [ "\$status" = "healthy" ]; then echo "api healthy"; exit 0; fi
  sleep 2
done
echo "api did not reach healthy state; recent logs:" >&2
docker compose -f docker-compose.prod.yml --env-file .env logs --tail=50 api >&2
exit 1
EOF
