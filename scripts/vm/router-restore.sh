#!/usr/bin/env bash
# Restore the router VM to a previously saved snapshot.
#
# Usage: router-restore.sh <name>
#
# If the VM is running, sends 'loadvm <name>' over the monitor (live restore).
# If the VM is not running, applies the snapshot to the qcow2 overlay offline
# via 'qemu-img snapshot -a' so the next router-up.sh boots from it.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

name="${1:-}"
[[ -n "${name}" ]] || die "usage: $(basename "$0") <name>"
[[ "${name}" =~ ^[A-Za-z0-9_.-]+$ ]] || die "snapshot name must match [A-Za-z0-9_.-]+"

if router_is_running; then
  require_cmd socat
  log "loading snapshot '${name}' into running VM"
  out="$(printf 'loadvm %s\n' "${name}" | socat -t2 - "UNIX-CONNECT:${ROUTER_MONITOR_SOCK}" 2>&1 || true)"
  echo "${out}" | sed 's/^/[qemu] /' >&2
  if echo "${out}" | grep -qiE 'error|failed'; then
    die "loadvm reported an error (see above)"
  fi
else
  require_cmd qemu-img
  [[ -f "${ROUTER_OVERLAY}" ]] || die "overlay missing: ${ROUTER_OVERLAY}"
  log "applying snapshot '${name}' to ${ROUTER_OVERLAY} (offline)"
  qemu-img snapshot -a "${name}" "${ROUTER_OVERLAY}"
fi

log "restored to snapshot '${name}'"
