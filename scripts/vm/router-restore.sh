#!/usr/bin/env bash
# Restore the router VM to a previously saved snapshot.
#
# Usage: router-restore.sh <name>
#
# Live restore (VM running): 'loadvm' over the monitor socket.
# Offline restore (VM stopped): 'qemu-img snapshot -a' so the next router-up.sh
# boots from the snapshot.

set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
source "${HERE}/lib.sh"

name="${1:-}"
[[ -n "${name}" ]] || die "usage: $(basename "$0") <name>"
[[ "${name}" =~ ^[A-Za-z0-9_.-]+$ ]] || die "snapshot name must match [A-Za-z0-9_.-]+"

if router_is_running; then
  require_cmd socat
  log "loading snapshot '${name}' into running VM"
  out="$(printf 'loadvm %s\n' "${name}" | socat -t2 - "UNIX-CONNECT:${FDNS_ROUTER_MONITOR_SOCK}" 2>&1 || true)"
  echo "${out}" | sed 's/^/[qemu] /' >&2
  if echo "${out}" | grep -qiE 'error|failed'; then
    die "loadvm reported an error (see above)"
  fi
else
  require_cmd qemu-img
  [[ -f "${FDNS_ROUTER_OVERLAY}" ]] || die "overlay missing: ${FDNS_ROUTER_OVERLAY}"
  log "applying snapshot '${name}' to ${FDNS_ROUTER_OVERLAY} (offline)"
  qemu-img snapshot -a "${name}" "${FDNS_ROUTER_OVERLAY}"
fi

log "restored to snapshot '${name}'"
