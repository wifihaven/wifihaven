#!/usr/bin/env bash
# Iterative dev loop for the OpenWRT agent: rsync the lua sources to the
# router, restart the service, and (optionally) tail the system log.
#
# Usage:
#   scripts/dev-push-router.sh                  # push + restart
#   scripts/dev-push-router.sh tail             # push + restart + follow logread
#   scripts/dev-push-router.sh logs             # just tail logread (no push)
#   ROUTER=root@192.168.1.1 scripts/dev-push-router.sh
#
# This only syncs the Lua files under openwrt/files/usr/lib/lua/familydns/.
# If you change init scripts, UCI defaults, or the C/nft side, do a full
# package rebuild + install instead.

set -euo pipefail

ROUTER="${ROUTER:-root@192.168.1.1}"
SRC="$(cd "$(dirname "$0")/.." && pwd)/openwrt/files/usr/lib/lua/familydns/"
DST="/usr/lib/lua/familydns/"

cmd="${1:-push}"

push() {
  echo "==> tar | ssh $SRC -> $ROUTER:$DST"
  # BusyBox on OpenWRT has no rsync; pipe a tarball instead.
  ( cd "$SRC" && tar -cf - --exclude='*.swp' . ) \
    | ssh "$ROUTER" "mkdir -p '$DST' && tar -xf - -C '$DST'"
  echo "==> restart familydns"
  ssh "$ROUTER" '/etc/init.d/familydns restart'
}

tail_logs() {
  echo "==> logread -f (Ctrl-C to stop)"
  ssh -t "$ROUTER" 'logread -f | grep --line-buffered familydns'
}

case "$cmd" in
  push)        push ;;
  tail|watch)  push; tail_logs ;;
  logs)        tail_logs ;;
  *)           echo "unknown: $cmd (use: push | tail | logs)" >&2; exit 2 ;;
esac
