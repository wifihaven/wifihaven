#!/usr/bin/env bash
# Snapshot the running router VM. Uses QEMU 'savevm' over the monitor socket,
# which captures both disk (qcow2 internal snapshot in the overlay) and live
# RAM/device state. Restore with router-restore.sh.
#
# Usage: router-snapshot.sh <name>

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

name="${1:-}"
[[ -n "${name}" ]] || die "usage: $(basename "$0") <name>"
[[ "${name}" =~ ^[A-Za-z0-9_.-]+$ ]] || die "snapshot name must match [A-Za-z0-9_.-]+"

router_is_running || die "router VM is not running"
require_cmd socat
[[ -S "${ROUTER_MONITOR_SOCK}" ]] || die "monitor socket missing: ${ROUTER_MONITOR_SOCK}"

log "saving snapshot '${name}'"
# Send 'savevm' and close the connection without 'quit' (which would terminate
# the VM). socat -t1 keeps the socket open just long enough for QEMU to reply.
out="$(printf 'savevm %s\n' "${name}" | socat -t2 - "UNIX-CONNECT:${ROUTER_MONITOR_SOCK}" 2>&1 || true)"
echo "${out}" | sed 's/^/[qemu] /' >&2

if echo "${out}" | grep -qiE 'error|failed'; then
  die "savevm reported an error (see above)"
fi

log "snapshot '${name}' saved into ${ROUTER_OVERLAY}"
log "list with: qemu-img snapshot -l '${ROUTER_OVERLAY}'"
