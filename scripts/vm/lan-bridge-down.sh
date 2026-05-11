#!/usr/bin/env bash
# Tear down the LAN bridge created by lan-bridge-up.sh.
# Safe to call when the bridge does not exist.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

case "${HOST_OS}" in
  Linux)
    if ip link show "${LAN_BRIDGE}" >/dev/null 2>&1; then
      log "removing bridge ${LAN_BRIDGE}"
      sudo ip link set "${LAN_BRIDGE}" down
      sudo ip link delete "${LAN_BRIDGE}" type bridge
    else
      log "bridge ${LAN_BRIDGE} not present"
    fi
    ;;
  Darwin)
    log "macOS: nothing to tear down — vmnet manages the LAN switch."
    ;;
  *)
    die "unsupported host OS: ${HOST_OS}"
    ;;
esac
