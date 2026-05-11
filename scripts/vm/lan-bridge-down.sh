#!/usr/bin/env bash
# Tear down the LAN bridge created by lan-bridge-up.sh.
# Safe to call when the bridge does not exist. Refuses to remove the bridge
# if VMs are still attached.

set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
source "${HERE}/lib.sh"

require_cmd ip

if ! ip link show "${FDNS_LAN_BRIDGE}" >/dev/null 2>&1; then
  log "bridge ${FDNS_LAN_BRIDGE} not present"
  exit 0
fi

# bail if anything is still attached so we don't yank the bridge out from
# under a running VM.
attached="$(ls "/sys/class/net/${FDNS_LAN_BRIDGE}/brif" 2>/dev/null || true)"
if [[ -n "${attached}" ]]; then
  die "bridge ${FDNS_LAN_BRIDGE} still has interfaces attached: ${attached}. \
Stop the router/client VMs first."
fi

log "removing bridge ${FDNS_LAN_BRIDGE}"
sudo ip link set "${FDNS_LAN_BRIDGE}" down
sudo ip link delete "${FDNS_LAN_BRIDGE}" type bridge
