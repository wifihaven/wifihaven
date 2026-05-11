# shellcheck shell=bash
# Shared configuration for the VM e2e harness. Sourced by scripts under
# scripts/vm/. Keep this file POSIX-bash compatible (no fancy features) — it is
# the single source of truth for names that span the router VM (#144) and the
# client VM (#146), so changes here propagate to both halves.

# --- LAN bridge (shared with the router VM from #144) ------------------------
# If #144 picks different names/ranges, update *here* — these constants are
# referenced everywhere else by variable, not by literal.
FDNS_LAN_BRIDGE="${FDNS_LAN_BRIDGE:-fdns-lan0}"
FDNS_LAN_SUBNET="${FDNS_LAN_SUBNET:-192.168.100.0/24}"
FDNS_LAN_ROUTER_IP="${FDNS_LAN_ROUTER_IP:-192.168.100.1}"

# --- Alpine cloud image (client VM base) -------------------------------------
# Pinned to a specific release + checksum. Bump deliberately, not casually.
# Source index: https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/cloud/
FDNS_ALPINE_VERSION="3.22.4"
FDNS_ALPINE_IMAGE="nocloud_alpine-${FDNS_ALPINE_VERSION}-x86_64-bios-cloudinit-r0.qcow2"
FDNS_ALPINE_URL="https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/cloud/${FDNS_ALPINE_IMAGE}"
# SHA-512 of the pristine qcow2 (Alpine publishes .sha512 alongside the image).
FDNS_ALPINE_SHA512="d0ddf1faae4d44aee3ad6621f166bd414c2f99b6974fb455408612d59cbb31a5390ca259800ac0c6b60505493880ed6de8beb986f6d0883b26c8e84ce75a266c"

# --- Paths -------------------------------------------------------------------
# Resolve relative to the directory containing config.sh, so callers can be
# invoked from anywhere.
FDNS_VM_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FDNS_CACHE_DIR="${FDNS_VM_DIR}/.cache"
FDNS_RUN_DIR="${FDNS_VM_DIR}/.run"
FDNS_KEYS_DIR="${FDNS_VM_DIR}/keys"
FDNS_CLIENT_BASE_IMG="${FDNS_CACHE_DIR}/client-base.qcow2"
FDNS_CLIENT_SSH_KEY="${FDNS_KEYS_DIR}/client_test_ed25519"
FDNS_CLIENT_SSH_PUB="${FDNS_KEYS_DIR}/client_test_ed25519.pub"

# --- SSH management NIC ------------------------------------------------------
# The client VM has two NICs:
#   eth0 — LAN side, attached to ${FDNS_LAN_BRIDGE}, DHCP from router VM.
#   eth1 — host-side user-mode NIC (QEMU SLIRP), used ONLY for orchestrator
#          SSH via hostfwd. No default route, no DNS — keeps all real traffic
#          on the LAN side so DNS scenarios actually exercise the router.
# Default port for the first client; client-up.sh increments per --name slot.
FDNS_CLIENT_SSH_PORT_BASE="${FDNS_CLIENT_SSH_PORT_BASE:-2223}"
