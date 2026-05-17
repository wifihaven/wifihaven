# shellcheck shell=bash
# Shared configuration for the VM e2e harness. Sourced by scripts under
# scripts/vm/. Keep this file POSIX-bash compatible (no fancy features) — it is
# the single source of truth for names that span the router VM (#144) and the
# client VM (#146), so changes here propagate to both halves.

# --- LAN bridge (shared with the router VM from #144) ------------------------
# If #144 picks different names/ranges, update *here* — these constants are
# referenced everywhere else by variable, not by literal.
WH_LAN_BRIDGE="${WH_LAN_BRIDGE:-wh-lan0}"
WH_LAN_SUBNET="${WH_LAN_SUBNET:-192.168.100.0/24}"
WH_LAN_ROUTER_IP="${WH_LAN_ROUTER_IP:-192.168.100.1}"

# --- Alpine cloud image (client VM base) -------------------------------------
# Pinned to a specific release + checksum. Bump deliberately, not casually.
# Source index: https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/cloud/
WH_ALPINE_VERSION="3.22.4"
WH_ALPINE_IMAGE="nocloud_alpine-${WH_ALPINE_VERSION}-x86_64-bios-cloudinit-r0.qcow2"
WH_ALPINE_URL="https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/cloud/${WH_ALPINE_IMAGE}"
# SHA-512 of the pristine qcow2 (Alpine publishes .sha512 alongside the image).
WH_ALPINE_SHA512="d0ddf1faae4d44aee3ad6621f166bd414c2f99b6974fb455408612d59cbb31a5390ca259800ac0c6b60505493880ed6de8beb986f6d0883b26c8e84ce75a266c"

# --- Paths -------------------------------------------------------------------
# Resolve relative to the directory containing config.sh, so callers can be
# invoked from anywhere.
WH_VM_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WH_CACHE_DIR="${WH_VM_DIR}/.cache"
WH_RUN_DIR="${WH_VM_DIR}/.run"
WH_KEYS_DIR="${WH_VM_DIR}/keys"
WH_CLIENT_BASE_IMG="${WH_CACHE_DIR}/client-base.qcow2"
WH_CLIENT_SSH_KEY="${WH_KEYS_DIR}/client_test_ed25519"
WH_CLIENT_SSH_PUB="${WH_KEYS_DIR}/client_test_ed25519.pub"

# --- Router VM (OpenWRT, #144) -----------------------------------------------
# Bump procedure:
#   1. Edit WH_OPENWRT_VERSION.
#   2. Refresh checksum:
#        curl -sSL "https://downloads.openwrt.org/releases/${WH_OPENWRT_VERSION}/targets/x86/64/sha256sums" \
#          | grep generic-ext4-combined.img.gz
#   3. Update WH_OPENWRT_IMAGE_SHA256.
#   4. Delete ${WH_CACHE_DIR} and re-run scripts/vm/router-up.sh.
WH_OPENWRT_VERSION="${WH_OPENWRT_VERSION:-23.05.6}"
WH_OPENWRT_IMAGE="openwrt-${WH_OPENWRT_VERSION}-x86-64-generic-ext4-combined.img.gz"
WH_OPENWRT_URL="https://downloads.openwrt.org/releases/${WH_OPENWRT_VERSION}/targets/x86/64/${WH_OPENWRT_IMAGE}"
WH_OPENWRT_SHA256="${WH_OPENWRT_SHA256:-c6e22b6f58ba721f15f3ccdbc26d4a85da64b7e3c564cd5bc70676eb91eeec51}"

# Where the router's qcow2 overlay + runtime state live. Snapshots are stored
# inside the overlay via qemu savevm.
WH_ROUTER_BASE_IMG="${WH_CACHE_DIR}/${WH_OPENWRT_IMAGE%.gz}"
WH_ROUTER_RUN_DIR="${WH_RUN_DIR}/router"
WH_ROUTER_OVERLAY="${WH_ROUTER_RUN_DIR}/overlay.qcow2"
WH_ROUTER_PIDFILE="${WH_ROUTER_RUN_DIR}/qemu.pid"
WH_ROUTER_MONITOR_SOCK="${WH_ROUTER_RUN_DIR}/monitor.sock"
WH_ROUTER_SERIAL_LOG="${WH_ROUTER_RUN_DIR}/console.log"

# Router VM size + WAN-side SSH hostfwd (LuCI HTTP forwarded for manual poking).
WH_ROUTER_MEM_MB="${WH_ROUTER_MEM_MB:-512}"
WH_ROUTER_DISK_SIZE="${WH_ROUTER_DISK_SIZE:-512M}"
WH_ROUTER_SSH_PORT="${WH_ROUTER_SSH_PORT:-2222}"
WH_ROUTER_HTTP_PORT="${WH_ROUTER_HTTP_PORT:-8080}"

# Stable, locally-administered MACs so the orchestrator can match-by-MAC in
# router-side logs.
WH_ROUTER_MAC_LAN="${WH_ROUTER_MAC_LAN:-52:54:00:fd:00:02}"
WH_ROUTER_MAC_WAN="${WH_ROUTER_MAC_WAN:-52:54:00:fd:00:01}"

# --- SSH management NIC ------------------------------------------------------
# The client VM has two NICs:
#   eth0 — LAN side, attached to ${WH_LAN_BRIDGE}, DHCP from router VM.
#   eth1 — host-side user-mode NIC (QEMU SLIRP), used ONLY for orchestrator
#          SSH via hostfwd. No default route, no DNS — keeps all real traffic
#          on the LAN side so DNS scenarios actually exercise the router.
# Default port for the first client; client-up.sh increments per --name slot.
WH_CLIENT_SSH_PORT_BASE="${WH_CLIENT_SSH_PORT_BASE:-2223}"
