#!/usr/bin/env bash
# FamilyDNS deploy — Linux only.
#
# Pulls the latest main, builds the API assembly + web bundle, swaps the
# artifacts in /opt/familydns/, and restarts the systemd unit.
#
# Designed to be run by a system service or a cron job; can also be run by
# hand. Uses sudo where required, so configure passwordless sudo for the
# deploy user (see README).
#
# Layout:
#   /opt/familydns/repo/      ← git checkout (this script lives here)
#   /opt/familydns/api.jar    ← latest assembly (symlink to versioned file)
#   /opt/familydns/web/       ← latest static bundle
#   /etc/familydns/           ← config (application.conf, api.env)
#
# Environment:
#   FAMILYDNS_BRANCH   default: main
#   FAMILYDNS_PREFIX   default: /opt/familydns
#   FAMILYDNS_NO_WEB   set to 1 to skip frontend build
#   FAMILYDNS_NO_RESTART  set to 1 to build but not restart services

set -euo pipefail

BRANCH="${FAMILYDNS_BRANCH:-production}"
PREFIX="${FAMILYDNS_PREFIX:-/opt/familydns}"
REPO="$PREFIX/repo"
LOG_TAG="familydns-deploy"

log()  { echo "[$(date -u +%Y-%m-%dT%H:%M:%SZ)] $*" | tee >(logger -t "$LOG_TAG"); }
fail() { log "FAILED: $*"; exit 1; }

[ "$(uname -s)" = "Linux" ] || fail "deploy.sh only supports Linux"
[ -d "$REPO/.git" ] || fail "$REPO is not a git checkout — run scripts/bootstrap-host.sh first"

cd "$REPO"

log "Fetching origin..."
git fetch --quiet --prune origin

log "Checking out $BRANCH..."
git checkout --quiet "$BRANCH"
git reset --hard --quiet "origin/$BRANCH"

REV="$(git rev-parse --short HEAD)"
log "Now at $REV"

# ── Build API assembly ────────────────────────────────────────────────────
log "Building assembly (mill api.assembly)..."
command -v mill >/dev/null 2>&1 || fail "mill not on PATH — run scripts/bootstrap-host.sh"
mill api.assembly >/tmp/${LOG_TAG}-mill.log 2>&1 \
  || { tail -50 /tmp/${LOG_TAG}-mill.log; fail "mill assembly build failed"; }
JAR_SRC="$(ls -t out/api/assembly.dest/out.jar 2>/dev/null | head -1)"
[ -f "$JAR_SRC" ] || fail "assembly jar not found at out/api/assembly.dest/out.jar"

# ── Build frontend ────────────────────────────────────────────────────────
if [ "${FAMILYDNS_NO_WEB:-0}" != "1" ]; then
  log "Building frontend (npm ci && npm run build)..."
  (cd web && npm ci --silent && npm run --silent build) \
    || fail "frontend build failed"
fi

# ── Atomic swap of artifacts ──────────────────────────────────────────────
log "Swapping artifacts into $PREFIX..."
sudo install -d -o familydns -g familydns "$PREFIX"
sudo install -m 0644 -o familydns -g familydns "$JAR_SRC" "$PREFIX/api.jar.new"
sudo mv -f "$PREFIX/api.jar.new" "$PREFIX/api.jar"

if [ "${FAMILYDNS_NO_WEB:-0}" != "1" ]; then
  sudo rm -rf "$PREFIX/web.new"
  sudo cp -r web/dist "$PREFIX/web.new"
  sudo chown -R familydns:familydns "$PREFIX/web.new"
  sudo rm -rf "$PREFIX/web.old"
  if [ -d "$PREFIX/web" ]; then sudo mv "$PREFIX/web" "$PREFIX/web.old"; fi
  sudo mv "$PREFIX/web.new" "$PREFIX/web"
fi

# Record the deployed rev for ops visibility
echo "$REV  $(date -u +%Y-%m-%dT%H:%M:%SZ)" | sudo tee -a "$PREFIX/deploy.log" >/dev/null

# ── Restart service ───────────────────────────────────────────────────────
if [ "${FAMILYDNS_NO_RESTART:-0}" != "1" ]; then
  log "Restarting familydns-api.service..."
  sudo systemctl restart familydns-api.service
  sleep 2
  systemctl is-active --quiet familydns-api.service \
    || { sudo journalctl -u familydns-api -n 80 --no-pager; fail "api did not come up"; }
fi

log "Deploy OK ($REV)"
