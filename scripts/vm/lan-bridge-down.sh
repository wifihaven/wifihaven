#!/usr/bin/env bash
# Tear down the LAN bridge created by lan-bridge-up.sh.
# Safe to call when the bridge does not exist. Refuses to remove the bridge
# if VMs are still attached.

set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
source "${HERE}/lib.sh"

require_cmd ip

if ! ip link show "${WH_LAN_BRIDGE}" >/dev/null 2>&1; then
  log "bridge ${WH_LAN_BRIDGE} not present"
  exit 0
fi

# bail if anything is still attached so we don't yank the bridge out from
# under a running VM.
attached="$(ls "/sys/class/net/${WH_LAN_BRIDGE}/brif" 2>/dev/null || true)"
if [[ -n "${attached}" ]]; then
  die "bridge ${WH_LAN_BRIDGE} still has interfaces attached: ${attached}. \
Stop the router/client VMs first."
fi

# Pool bridges (wh-lan0, wh-lan1, ...) are owned by lan-bridge-pool-bootstrap.sh
# and meant to outlive any single run — deleting one here would force the next
# run to re-create it (needs sudo) and would race with another in-flight
# concurrent run picking the same pool slot (#891). Only delete bridges we
# would also create on-the-fly via lan-bridge-up.sh — i.e. bridges *not* in
# the pool.
if [[ "${WH_LAN_BRIDGE}" =~ ^wh-lan[0-9]+$ ]] && [[ -f /etc/qemu/bridge.conf ]] \
    && grep -qE "^[[:space:]]*allow[[:space:]]+${WH_LAN_BRIDGE}\b" /etc/qemu/bridge.conf; then
  # Clear the bridge-pool reservation (#907) so the next picker can recycle
  # this slot immediately rather than waiting for the reaper.
  wh_clear_bridge_reservation "${WH_LAN_BRIDGE}"
  log "leaving pool bridge ${WH_LAN_BRIDGE} in place (managed by lan-bridge-pool-bootstrap.sh)"
  exit 0
fi

log "removing bridge ${WH_LAN_BRIDGE}"
sudo ip link set "${WH_LAN_BRIDGE}" down
sudo ip link delete "${WH_LAN_BRIDGE}" type bridge
