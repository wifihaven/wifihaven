#!/usr/bin/env bash
# Boot the OpenWRT router VM:
#   - LAN NIC attached to ${WH_LAN_BRIDGE} (shared with the client VM from #146)
#   - WAN NIC on QEMU user-mode networking with NAT to the host's internet, plus
#     hostfwd of SSH (port 22) and LuCI HTTP (port 80) for orchestrator access.
# Idempotent: a no-op when the VM is already running.

set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
source "${HERE}/lib.sh"

require_cmd qemu-system-x86_64
require_cmd qemu-img

if router_is_running; then
  log "router VM already running (pid $(cat "${WH_ROUTER_PIDFILE}"))"
  log "  ssh:     ssh -p ${WH_ROUTER_SSH_PORT} root@127.0.0.1"
  log "  monitor: socat - UNIX-CONNECT:${WH_ROUTER_MONITOR_SOCK}"
  exit 0
fi

# Auto-pick a free bridge from the wh-lan* pool when one wasn't set explicitly
# (#891). On hosts without a pool, this is a no-op and we fall through to
# WH_LAN_BRIDGE=wh-lan0 (today's default). Holds a flock until this shell exits.
wh_pick_lan_bridge

"${HERE}/lan-bridge-up.sh"

ensure_openwrt_image
ensure_router_overlay

# OpenWRT 23.05 x86 board defaults: eth0 -> lan, eth1 -> wan. Match that ordering.
LAN_NETDEV="bridge,id=lan,br=${WH_LAN_BRIDGE}"
WAN_NETDEV="user,id=wan,hostfwd=tcp:127.0.0.1:${WH_ROUTER_SSH_PORT}-:22,hostfwd=tcp:127.0.0.1:${WH_ROUTER_HTTP_PORT}-:80"

ACCEL_ARGS=(-cpu max)
if [[ -e /dev/kvm ]]; then
  ACCEL_ARGS=(-enable-kvm -cpu host)
fi

rm -f "${WH_ROUTER_MONITOR_SOCK}" "${WH_ROUTER_PIDFILE}"

# When the boot image is the custom-built one (build-router-image.sh),
# versions.sh is the source of truth for the active OpenWRT version — the
# ipk/apk matrix flips between 23.05 and 25.12 there. WH_OPENWRT_VERSION
# from config.sh only describes the stock download path. (#907)
_router_log_version="${WH_OPENWRT_VERSION}"
if [[ -n "${WH_ROUTER_IMAGE_PATH:-}" && -f "${HERE}/versions.sh" ]]; then
  # shellcheck source=versions.sh
  source "${HERE}/versions.sh"
  _router_log_version="${OPENWRT_VERSION}"
fi
log "booting OpenWRT ${_router_log_version} router VM"
log "  LAN: ${LAN_NETDEV}"
log "  WAN: user-mode (ssh 127.0.0.1:${WH_ROUTER_SSH_PORT}, http 127.0.0.1:${WH_ROUTER_HTTP_PORT})"

qemu-system-x86_64 \
  -name "${WH_ROUTER_VM_NAME}" \
  -m "${WH_ROUTER_MEM_MB}" -smp 2 \
  "${ACCEL_ARGS[@]}" \
  -display none \
  -serial "file:${WH_ROUTER_SERIAL_LOG}" \
  -monitor "unix:${WH_ROUTER_MONITOR_SOCK},server,nowait" \
  -pidfile "${WH_ROUTER_PIDFILE}" \
  -drive "file=${WH_ROUTER_OVERLAY},if=virtio,format=qcow2" \
  -netdev "${LAN_NETDEV}" \
  -device "virtio-net-pci,netdev=lan,mac=${WH_ROUTER_MAC_LAN}" \
  -netdev "${WAN_NETDEV}" \
  -device "virtio-net-pci,netdev=wan,mac=${WH_ROUTER_MAC_WAN}" \
  -daemonize

# Update the bridge-pool reservation (#907) to qemu's PID so the marker stays
# live for the lifetime of the VM, not just the calling shell. Held flock is
# released when this script exits, so the reservation IS the in-flight signal
# the next pick sees.
if [[ -n "${WH_LAN_BRIDGE_PICKED:-}" ]]; then
  _qpid="$(cat "${WH_ROUTER_PIDFILE}" 2>/dev/null || echo)"
  if [[ -n "${_qpid}" ]]; then
    wh_write_bridge_reservation "${WH_LAN_BRIDGE}" "${_qpid}"
  fi
fi

log "router VM started (pid $(cat "${WH_ROUTER_PIDFILE}" 2>/dev/null || echo '?'))"
log ""
log "next steps:"
log "  - first boot takes ~30s; tail '${WH_ROUTER_SERIAL_LOG}' to watch."
log "  - SSH (WAN hostfwd, empty root password on stock image):"
log "      ssh -p ${WH_ROUTER_SSH_PORT} root@127.0.0.1"
log "  - LAN-side router IP (default OpenWRT): 192.168.1.1 (not in"
log "    ${WH_LAN_SUBNET}; see README 'Known quirks' — fixed by #150)."
log "  - QEMU monitor (savevm/loadvm/quit):"
log "      socat - UNIX-CONNECT:${WH_ROUTER_MONITOR_SOCK}"
