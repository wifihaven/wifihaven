#!/usr/bin/env bash
# Snapshot the running router VM via QEMU 'savevm' — captures disk + RAM in
# the qcow2 overlay. Restore with router-restore.sh.
#
# Usage: router-snapshot.sh <name>

set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
source "${HERE}/lib.sh"

name="${1:-}"
[[ -n "${name}" ]] || die "usage: $(basename "$0") <name>"
[[ "${name}" =~ ^[A-Za-z0-9_.-]+$ ]] || die "snapshot name must match [A-Za-z0-9_.-]+"

router_is_running || die "router VM is not running"
require_cmd socat
[[ -S "${FDNS_ROUTER_MONITOR_SOCK}" ]] || die "monitor socket missing: ${FDNS_ROUTER_MONITOR_SOCK}"

log "saving snapshot '${name}'"
# Send 'savevm' and let the connection close after the reply — never send
# 'quit' (it would terminate the VM). socat -t2 holds the socket open ~2s
# for QEMU to reply.
out="$(printf 'savevm %s\n' "${name}" | socat -t2 - "UNIX-CONNECT:${FDNS_ROUTER_MONITOR_SOCK}" 2>&1 || true)"
echo "${out}" | sed 's/^/[qemu] /' >&2

if echo "${out}" | grep -qiE 'error|failed'; then
  die "savevm reported an error (see above)"
fi

log "snapshot '${name}' saved into ${FDNS_ROUTER_OVERLAY}"
log "list with: qemu-img snapshot -l '${FDNS_ROUTER_OVERLAY}'"
