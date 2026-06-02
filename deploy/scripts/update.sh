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
#
# When the metrics overlay is enabled (compose.env layers in
# docker-compose.metrics.yml), this also refreshes the overlay file,
# prometheus config, and the Grafana dashboards from main so dashboard
# updates reach existing installs.
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"

REPO_RAW="${WIFIHAVEN_REPO_RAW:-https://raw.githubusercontent.com/wifihaven/wifihaven/main}"
COMPOSE_URL="${REPO_RAW}/deploy/docker-compose.prod.yml"
REPO_REF="${WIFIHAVEN_REPO_REF:-main}"
REPO_TARBALL_URL="${WIFIHAVEN_REPO_TARBALL_URL:-https://github.com/wifihaven/wifihaven/archive/refs/heads/${REPO_REF}.tar.gz}"

# compose.env (written by install.sh when the metrics overlay is enabled)
# defines COMPOSE_FILE_ARGS. Absent = prod stack only, as before.
COMPOSE_FILE_ARGS=(-f docker-compose.prod.yml)
[ -f compose.env ] && source compose.env

metrics_enabled() {
  local f
  for f in "${COMPOSE_FILE_ARGS[@]}"; do
    [ "$f" = "docker-compose.metrics.yml" ] && return 0
  done
  return 1
}

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

# Refresh the metrics overlay + dashboards when enabled. The
# grafana/dashboards/ directory isn't enumerable over raw URLs, so we pull a
# repo tarball and extract the deploy/ subtree (same mechanism install.sh
# uses). On any failure we keep the existing files rather than break a
# working install.
if metrics_enabled; then
  tmp="$(mktemp -d)"
  if curl -fsSL "$REPO_TARBALL_URL" -o "${tmp}/repo.tar.gz" \
     && tar -xzf "${tmp}/repo.tar.gz" -C "$tmp"; then
    src="$(find "$tmp" -maxdepth 2 -type d -name deploy | head -1)"
    if [ -n "$src" ] && [ -f "${src}/docker-compose.metrics.yml" ]; then
      cp "${src}/docker-compose.metrics.yml" ./
      rm -rf ./prometheus ./grafana
      cp -R "${src}/prometheus" ./
      cp -R "${src}/grafana"    ./
    else
      echo "warn: repo tarball missing deploy/metrics overlay; keeping existing files" >&2
    fi
  else
    echo "warn: failed to fetch $REPO_TARBALL_URL; keeping existing metrics overlay" >&2
  fi
  rm -rf "$tmp"
fi

docker compose "${COMPOSE_FILE_ARGS[@]}" --env-file .env pull
docker compose "${COMPOSE_FILE_ARGS[@]}" --env-file .env up -d
