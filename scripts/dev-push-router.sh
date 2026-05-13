#!/usr/bin/env bash
# Iterative dev loop for the OpenWRT agent: tar the source tree to the
# router, restart the service, and (optionally) tail the system log.
#
# Usage:
#   scripts/dev-push-router.sh                  # push + restart
#   scripts/dev-push-router.sh tail             # push + restart + follow logread
#   scripts/dev-push-router.sh logs             # just tail logread (no push)
#   ROUTER=root@192.168.1.1 scripts/dev-push-router.sh
#
# Syncs the whole openwrt/files/ tree to the router. A file at
# openwrt/files/usr/sbin/familydns-agent lands at /usr/sbin/familydns-agent.
# Executables under usr/sbin and init scripts under etc/init.d are chmod +x'd.
#
# Caveats — these still require a full package rebuild + install:
#   - First install of init scripts in etc/init.d/ (enable/symlinks)
#   - UCI defaults under etc/uci-defaults/ (only run once at install)
#   - apk/ipk package layout / dependencies / C / nft components
# Once installed, restarting the service picks up new lua + sbin scripts.
#
# /etc/config/familydns is per-router state and is NOT synced (excluded).

set -euo pipefail

ROUTER="${ROUTER:-root@192.168.1.1}"
SRC="$(cd "$(dirname "$0")/.." && pwd)/openwrt/files/"

cmd="${1:-push}"

check_router() {
  if ! ssh -o ConnectTimeout=5 -o BatchMode=yes "$ROUTER" true 2>/dev/null; then
    echo "ERROR: cannot reach $ROUTER (ssh failed)" >&2
    exit 1
  fi
}

push() {
  check_router
  echo "==> tar | ssh $SRC -> $ROUTER:/"
  # BusyBox on OpenWRT has no rsync; pipe a tarball instead.
  # Exclude /etc/config/familydns (per-router UCI state).
  ( cd "$SRC" && tar -cf - \
      --exclude='*.swp' \
      --exclude='./etc/config/familydns' \
      . ) \
    | ssh "$ROUTER" "tar -xf - -C /"

  echo "==> fix permissions on executables"
  ssh "$ROUTER" '
    for f in /usr/sbin/familydns-agent /usr/sbin/familydns-update /usr/sbin/familydns-dns-tail; do
      [ -f "$f" ] && chmod +x "$f"
    done
    for f in /etc/init.d/familydns /etc/init.d/familydns-boot; do
      [ -f "$f" ] && chmod +x "$f"
    done
    true
  '

  echo "==> parity check"
  parity_check

  echo "==> restart familydns"
  ssh "$ROUTER" '/etc/init.d/familydns restart'
}

parity_check() {
  local files=(
    "usr/sbin/familydns-agent"
    "usr/lib/lua/familydns/policy.lua"
    "usr/lib/lua/familydns/render.lua"
  )
  local mismatch=0
  for rel in "${files[@]}"; do
    local local_sum remote_sum
    if command -v sha256sum >/dev/null 2>&1; then
      local_sum=$(sha256sum "$SRC$rel" | cut -d' ' -f1)
    else
      local_sum=$(shasum -a 256 "$SRC$rel" | cut -d' ' -f1)
    fi
    remote_sum=$(ssh "$ROUTER" "sha256sum '/$rel' 2>/dev/null | cut -d' ' -f1")
    if [ "$local_sum" != "$remote_sum" ]; then
      echo "  MISMATCH: /$rel (local=$local_sum remote=$remote_sum)" >&2
      mismatch=1
    fi
  done
  if [ "$mismatch" -ne 0 ]; then
    echo "ERROR: parity check failed — router does not match source" >&2
    exit 1
  fi
  echo "  ✓ agent + lua libs match source"
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
