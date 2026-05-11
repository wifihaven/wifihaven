#!/usr/bin/env bash
# Create (idempotently) the LAN bridge that the router VM and client VM (#146)
# attach to.
#
# This script is intentionally idempotent so it can be called from both the
# router and client up scripts without coordination.
#
# Linux: creates a real bridge interface named ${LAN_BRIDGE} (default
# fdns-lan0), assigns ${LAN_HOST_IP}/${LAN_HOST_CIDR} to it so the host can
# reach the VMs on the LAN side, and ensures /etc/qemu/bridge.conf allows
# qemu-bridge-helper to attach taps to it.
#
# macOS: there is no real "bridge" to create — QEMU on macOS uses the vmnet
# framework which manages an internal switch. This script validates that the
# QEMU build supports vmnet and prints the bridge identifier that router-up.sh
# / client-up.sh will pass to -netdev vmnet-shared. Real bridging on macOS
# requires QEMU with vmnet entitlements and root.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

case "${HOST_OS}" in
  Linux)
    require_cmd ip

    if ip link show "${LAN_BRIDGE}" >/dev/null 2>&1; then
      log "bridge ${LAN_BRIDGE} already exists"
    else
      log "creating bridge ${LAN_BRIDGE}"
      sudo ip link add name "${LAN_BRIDGE}" type bridge
    fi

    sudo ip link set "${LAN_BRIDGE}" up

    if ! ip -4 addr show dev "${LAN_BRIDGE}" | grep -q "${LAN_HOST_IP}/"; then
      log "assigning ${LAN_HOST_IP}/${LAN_HOST_CIDR} to ${LAN_BRIDGE}"
      sudo ip addr add "${LAN_HOST_IP}/${LAN_HOST_CIDR}" dev "${LAN_BRIDGE}"
    fi

    # Allow qemu-bridge-helper to attach taps to this bridge without it being
    # listed in /etc/qemu/bridge.conf root would have to edit, we just check.
    if [[ -f /etc/qemu/bridge.conf ]]; then
      if ! grep -qE "^allow ${LAN_BRIDGE}\b" /etc/qemu/bridge.conf; then
        log "warning: /etc/qemu/bridge.conf does not allow ${LAN_BRIDGE};"
        log "         add 'allow ${LAN_BRIDGE}' as root if router-up.sh fails to attach."
      fi
    else
      log "warning: /etc/qemu/bridge.conf missing — qemu-bridge-helper will refuse"
      log "         to attach. Create it with 'allow ${LAN_BRIDGE}' as root."
    fi

    log "LAN bridge ready: ${LAN_BRIDGE} (host ip ${LAN_HOST_IP})"
    ;;

  Darwin)
    # vmnet-shared is supported in QEMU >= 7.1 with the vmnet entitlement.
    # We can't "create" the bridge — vmnet manages it. We just sanity-check
    # the binary supports it.
    if ! qemu-system-x86_64 -netdev help 2>&1 | grep -q vmnet-shared; then
      die "qemu-system-x86_64 on this host has no vmnet-shared support. \
Install QEMU 7.1+ (e.g. brew install qemu). LAN-side bridging on macOS \
requires the vmnet framework."
    fi
    log "macOS host: LAN bridge is provided by vmnet-shared (no setup needed)."
    log "Contract for #146/#148: attach LAN NIC with '-netdev vmnet-shared,id=lan'."
    ;;

  *)
    die "unsupported host OS: ${HOST_OS}"
    ;;
esac
