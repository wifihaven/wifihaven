#!/usr/bin/env bash
# Create (idempotently) the LAN bridge that the router VM and client VM (#146)
# attach to. router-up.sh calls this automatically; expose it as its own
# script so the client harness can also call it without coordination.

set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
source "${HERE}/lib.sh"

require_cmd ip

if ip link show "${WH_LAN_BRIDGE}" >/dev/null 2>&1; then
  log "bridge ${WH_LAN_BRIDGE} already exists"
else
  log "creating bridge ${WH_LAN_BRIDGE}"
  sudo ip link add name "${WH_LAN_BRIDGE}" type bridge
fi

# Bring the bridge up only if IFF_UP isn't already set. Pool bridges are
# pre-set-up by lan-bridge-pool-bootstrap.sh, and on many hosts only wh-lan0
# is NOPASSWD-allowed in sudoers, so an unconditional `sudo ip link set` here
# would fail under nohup/non-tty sessions when wh-lan<N> (N>0) is picked
# (#907). Use the netdev flags bitmap (IFF_UP=0x1) instead of operstate —
# operstate of an admin-up bridge with no carrier still reads "down".
flags="$(cat "/sys/class/net/${WH_LAN_BRIDGE}/flags" 2>/dev/null || echo 0)"
if (( (flags & 0x1) == 0 )); then
  sudo ip link set "${WH_LAN_BRIDGE}" up
fi

# qemu-bridge-helper refuses to attach taps unless the bridge is in
# /etc/qemu/bridge.conf. We don't auto-edit it (root-owned), just warn.
if [[ -f /etc/qemu/bridge.conf ]]; then
  if ! grep -qE "^allow ${WH_LAN_BRIDGE}\b" /etc/qemu/bridge.conf; then
    log "warning: /etc/qemu/bridge.conf does not allow ${WH_LAN_BRIDGE};"
    log "         add 'allow ${WH_LAN_BRIDGE}' as root, e.g.:"
    log "           echo 'allow ${WH_LAN_BRIDGE}' | sudo tee -a /etc/qemu/bridge.conf"
  fi
else
  log "warning: /etc/qemu/bridge.conf missing — qemu-bridge-helper will refuse"
  log "         to attach. Create it as root with 'allow ${WH_LAN_BRIDGE}'."
fi

log "LAN bridge ${WH_LAN_BRIDGE} ready"
