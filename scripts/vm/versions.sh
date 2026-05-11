#!/usr/bin/env bash
# Pinned versions for the VM e2e harness.
#
# To bump:
#   1. Update OPENWRT_VERSION below.
#   2. Re-fetch the SHA256:
#        curl -sSL "https://downloads.openwrt.org/releases/${OPENWRT_VERSION}/targets/x86/64/sha256sums" \
#          | grep generic-ext4-combined.img.gz
#   3. Update OPENWRT_IMAGE_SHA256.
#   4. Delete scripts/vm/.cache/ so the new image is downloaded.
#   5. Test scripts/vm/router-up.sh end-to-end before committing.

# OpenWRT release pinned for the router VM.
OPENWRT_VERSION="23.05.6"
OPENWRT_TARGET="x86/64"
OPENWRT_IMAGE_FILE="openwrt-${OPENWRT_VERSION}-x86-64-generic-ext4-combined.img.gz"
OPENWRT_IMAGE_URL="https://downloads.openwrt.org/releases/${OPENWRT_VERSION}/targets/${OPENWRT_TARGET}/${OPENWRT_IMAGE_FILE}"
OPENWRT_IMAGE_SHA256="c6e22b6f58ba721f15f3ccdbc26d4a85da64b7e3c564cd5bc70676eb91eeec51"

# Shared LAN bridge name — contract with the client VM (#146) and orchestrator (#148).
# Both the router VM and any client VM must attach their LAN NIC to a netdev
# that lands on this bridge.
LAN_BRIDGE="${FDNS_LAN_BRIDGE:-fdns-lan0}"

# LAN subnet for the bridge. Override via FDNS_LAN_SUBNET if 192.168.50.0/24
# clashes with your home network. The router VM keeps its OpenWRT default of
# 192.168.1.1 on its LAN NIC; the host-side bridge address below is used only
# for host -> router reachability when not using SSH hostfwd.
LAN_SUBNET="${FDNS_LAN_SUBNET:-192.168.50.0/24}"
LAN_HOST_IP="${FDNS_LAN_HOST_IP:-192.168.50.1}"
LAN_HOST_CIDR="${FDNS_LAN_HOST_CIDR:-24}"

# Host port forwarded to the router VM's SSH (via QEMU user-mode on the WAN NIC).
ROUTER_SSH_PORT="${FDNS_ROUTER_SSH_PORT:-2222}"
# Host port forwarded to the router VM's LuCI HTTP (helpful for manual poking).
ROUTER_HTTP_PORT="${FDNS_ROUTER_HTTP_PORT:-8080}"

# QEMU monitor socket — used by snapshot/restore.
ROUTER_MONITOR_SOCK="${FDNS_ROUTER_MONITOR_SOCK:-${SCRIPTS_VM_DIR:-.}/.cache/router-monitor.sock}"
ROUTER_PIDFILE="${FDNS_ROUTER_PIDFILE:-${SCRIPTS_VM_DIR:-.}/.cache/router.pid}"
ROUTER_SERIAL_LOG="${FDNS_ROUTER_SERIAL_LOG:-${SCRIPTS_VM_DIR:-.}/.cache/router-serial.log}"
ROUTER_OVERLAY="${FDNS_ROUTER_OVERLAY:-${SCRIPTS_VM_DIR:-.}/.cache/router-overlay.qcow2}"
ROUTER_BASE_IMAGE="${FDNS_ROUTER_BASE_IMAGE:-${SCRIPTS_VM_DIR:-.}/.cache/${OPENWRT_IMAGE_FILE%.gz}}"
ROUTER_MEM_MB="${FDNS_ROUTER_MEM_MB:-512}"
ROUTER_DISK_SIZE="${FDNS_ROUTER_DISK_SIZE:-512M}"
ROUTER_MAC_WAN="${FDNS_ROUTER_MAC_WAN:-52:54:00:fd:00:01}"
ROUTER_MAC_LAN="${FDNS_ROUTER_MAC_LAN:-52:54:00:fd:00:02}"
