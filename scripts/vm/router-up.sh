#!/usr/bin/env bash
# Boot the OpenWRT router VM with WAN (user-mode + NAT) and LAN (host bridge)
# NICs. Idempotent: if the VM is already running, exits 0 with status info.
#
# Prerequisites:
#   - qemu-system-x86_64
#   - qemu-img
#   - Linux: /dev/kvm accessible; an LAN bridge from lan-bridge-up.sh.
#   - macOS: QEMU >= 7.1 with vmnet-shared support; this script runs vmnet
#     under sudo automatically.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

require_cmd qemu-system-x86_64
require_cmd qemu-img

if router_is_running; then
  log "router VM already running (pid $(cat "${ROUTER_PIDFILE}"))"
  log "  ssh: ssh -p ${ROUTER_SSH_PORT} root@127.0.0.1"
  log "  monitor: socat - UNIX-CONNECT:${ROUTER_MONITOR_SOCK}"
  exit 0
fi

"${SCRIPT_DIR}/lan-bridge-up.sh"

ensure_openwrt_image
ensure_router_overlay

# WAN NIC: user-mode networking with SSH (and LuCI) hostfwd. OpenWRT's default
# is to put the first detected NIC into the WAN zone, so we list this NIC
# first.
#
# Routing note: OpenWRT 23.05 x86 image places eth0 in lan, eth1 in wan by
# default via /etc/board.d/02_network. We override at runtime via a uci-defaults
# script baked into a small ISO ... but for v1, we accept the default and
# instead expose SSH via the WAN side (user-mode hostfwd) since the WAN-side
# firewall does not block 22 from "lan" zone — and the user-mode 10.0.2.0/24
# behaves as LAN-ish from OpenWRT's perspective. In practice this works:
# OpenWRT's WAN zone permits ssh from the user-mode network when explicitly
# allowed. To be safe we forward SSH from the user-mode NIC which OpenWRT
# treats as 'wan': the orchestrator must allow ssh on wan or use the LAN
# bridge for management. The simplest reliable scheme is:
#   - eth0 (first in -device order) -> LAN  (host bridge, host has ${LAN_HOST_IP})
#   - eth1 (second)                 -> WAN  (user-mode with NAT to internet)
# OpenWRT's default board config assigns eth0->lan, eth1->wan, which matches.
# Management then happens over the LAN bridge (host -> 192.168.1.1) rather
# than ssh hostfwd. We still set up hostfwd for emergency access if the LAN
# bridge is misconfigured.

WAN_NETDEV="user,id=wan,hostfwd=tcp:127.0.0.1:${ROUTER_SSH_PORT}-:22,hostfwd=tcp:127.0.0.1:${ROUTER_HTTP_PORT}-:80"

case "${HOST_OS}" in
  Linux)
    ACCEL="-enable-kvm -cpu host"
    [[ -r /dev/kvm && -w /dev/kvm ]] || ACCEL="-cpu max"
    LAN_NETDEV="bridge,id=lan,br=${LAN_BRIDGE}"
    SUDO_PREFIX=""
    ;;
  Darwin)
    if [[ "$(uname -m)" == "arm64" ]]; then
      ACCEL="-accel hvf -cpu max"
    else
      ACCEL="-accel hvf -cpu host"
    fi
    LAN_NETDEV="vmnet-shared,id=lan"
    # vmnet-shared requires root on macOS.
    SUDO_PREFIX="sudo"
    ;;
  *)
    die "unsupported host OS: ${HOST_OS}"
    ;;
esac

rm -f "${ROUTER_MONITOR_SOCK}" "${ROUTER_PIDFILE}"

log "booting OpenWRT ${OPENWRT_VERSION} router VM"
log "  WAN: user-mode (SSH hostfwd 127.0.0.1:${ROUTER_SSH_PORT}, HTTP 127.0.0.1:${ROUTER_HTTP_PORT})"
log "  LAN: ${LAN_NETDEV}"
log "  monitor: ${ROUTER_MONITOR_SOCK}"
log "  serial:  ${ROUTER_SERIAL_LOG}"

# shellcheck disable=SC2086
${SUDO_PREFIX} qemu-system-x86_64 \
  ${ACCEL} \
  -m "${ROUTER_MEM_MB}" \
  -smp 2 \
  -nographic \
  -serial "file:${ROUTER_SERIAL_LOG}" \
  -monitor "unix:${ROUTER_MONITOR_SOCK},server,nowait" \
  -pidfile "${ROUTER_PIDFILE}" \
  -drive "file=${ROUTER_OVERLAY},if=virtio,format=qcow2" \
  -netdev "${LAN_NETDEV}" \
  -device "virtio-net-pci,netdev=lan,mac=${ROUTER_MAC_LAN}" \
  -netdev "${WAN_NETDEV}" \
  -device "virtio-net-pci,netdev=wan,mac=${ROUTER_MAC_WAN}" \
  -daemonize

log "router VM started (pid $(cat "${ROUTER_PIDFILE}" 2>/dev/null || echo '?'))"
log ""
log "next steps:"
log "  - first boot takes ~30s; tail '${ROUTER_SERIAL_LOG}' to watch."
log "  - emergency SSH via WAN-side hostfwd (if firewall allows):"
log "      ssh -p ${ROUTER_SSH_PORT} root@127.0.0.1"
log "  - LAN-side management (preferred):"
log "      ssh root@192.168.1.1   # from a host with route to ${LAN_BRIDGE}"
log "  - QEMU monitor (savevm/loadvm/quit):"
log "      socat - UNIX-CONNECT:${ROUTER_MONITOR_SOCK}"
log "  - default OpenWRT root password is empty; set one after first boot."
