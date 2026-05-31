# shellcheck shell=bash
# Shared configuration for the VM e2e harness. Sourced by scripts under
# scripts/vm/. Keep this file POSIX-bash compatible (no fancy features) — it is
# the single source of truth for names that span the router VM (#144) and the
# client VM (#146), so changes here propagate to both halves.

# --- Concurrent-run keying (#891) --------------------------------------------
# Set WH_RUN_ID to a short, filesystem-safe token (e.g. "a", "ci-7") to run a
# second router+client pair on the same host. When empty, all derived names,
# paths, ports, and MACs are byte-identical to the pre-#891 layout — single-
# pair invocations need no changes.
WH_RUN_ID="${WH_RUN_ID:-}"

# WH_PORT_BASE: shorthand to allocate three consecutive host ports. Only
# applies as a *default* for the individual port vars below — explicit
# WH_ROUTER_SSH_PORT / WH_ROUTER_HTTP_PORT / WH_CLIENT_SSH_PORT_BASE still win.
WH_PORT_BASE="${WH_PORT_BASE:-}"

# WH_MAC_PREFIX: three-octet OUI used to build default WAN/LAN MACs. The
# per-run suffix is derived from WH_RUN_ID (sha256, first 2 hex bytes) so two
# concurrent pairs don't collide. Explicit WH_ROUTER_MAC_* still wins.
WH_MAC_PREFIX="${WH_MAC_PREFIX:-52:54:00}"

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
# invoked from anywhere. When WH_RUN_ID is set, runtime state lives under
# .run/<run-id>/ so a second concurrent pair doesn't collide on overlay/pid/
# socket paths.
WH_VM_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WH_CACHE_DIR="${WH_VM_DIR}/.cache"
if [[ -n "${WH_RUN_ID}" ]]; then
  WH_RUN_DIR="${WH_VM_DIR}/.run/${WH_RUN_ID}"
else
  WH_RUN_DIR="${WH_VM_DIR}/.run"
fi
# Short base dir for qemu UNIX *control sockets* (router monitor, client QMP).
# These MUST NOT live under .run/: the AF_UNIX sun_path limit is ~108 chars,
# and the .run/ tree sits under a deep CI runner home
# (.../_work/wifihaven/wifihaven/scripts/vm/.run/<run-id>/router/monitor.sock).
# The multi-instance self-hosted runner from #1163 lengthened that prefix
# (actions-runner → actions-runner-4), pushing the monitor socket path past the
# ceiling — qemu refused to create the socket and no VM gate could boot. Keeping
# the sockets in /run (or /tmp) makes the path independent of the runner home.
# Only the sockets relocate here; serial logs, overlays, pidfiles, and bridge
# markers stay under .run/ so nothing else moves.
if [[ -n "${WH_RUN_ID}" ]]; then
  WH_SOCK_DIR="${XDG_RUNTIME_DIR:-/tmp}/wh-vm/${WH_RUN_ID}"
else
  WH_SOCK_DIR="${XDG_RUNTIME_DIR:-/tmp}/wh-vm"
fi

# QMP control-socket path for a client slot. Lives under WH_SOCK_DIR (short
# path) rather than ${WH_RUN_DIR}/<name>/ for the sun_path reason above.
wh_client_qmp_sock() {
  printf '%s/client-%s-qmp.sock' "${WH_SOCK_DIR}" "$1"
}

WH_KEYS_DIR="${WH_VM_DIR}/keys"
WH_CLIENT_BASE_IMG="${WH_CACHE_DIR}/client-base.qcow2"
WH_CLIENT_SSH_KEY="${WH_KEYS_DIR}/client_test_ed25519"
WH_CLIENT_SSH_PUB="${WH_KEYS_DIR}/client_test_ed25519.pub"

# --- LAN bridge (shared with the router VM from #144) ------------------------
# Resolution order:
#   1. WH_LAN_BRIDGE explicitly set in env → use it (back-compat).
#   2. router-up.sh in this run recorded a pool pick under .run/<id>/lan-bridge
#      → use that (so client-up.sh sees the same bridge router-up chose).
#   3. Default to wh-lan0 (today's single-pair behavior).
# The pool-pick itself happens in lib.sh::wh_pick_lan_bridge, called from
# router-up.sh before qemu boot (#891).
if [[ -z "${WH_LAN_BRIDGE:-}" && -f "${WH_RUN_DIR}/lan-bridge" ]]; then
  WH_LAN_BRIDGE="$(cat "${WH_RUN_DIR}/lan-bridge")"
fi
WH_LAN_BRIDGE="${WH_LAN_BRIDGE:-wh-lan0}"
WH_LAN_SUBNET="${WH_LAN_SUBNET:-192.168.100.0/24}"
WH_LAN_ROUTER_IP="${WH_LAN_ROUTER_IP:-192.168.100.1}"

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
# Monitor socket lives under WH_SOCK_DIR (short path), not WH_ROUTER_RUN_DIR —
# see the WH_SOCK_DIR note above for the sun_path reason.
WH_ROUTER_MONITOR_SOCK="${WH_SOCK_DIR}/router-monitor.sock"
WH_ROUTER_SERIAL_LOG="${WH_ROUTER_RUN_DIR}/console.log"

# QEMU `-name` for the router VM. Suffixed with WH_RUN_ID so `pgrep -f` in
# router-down.sh only matches *this* instance — without the suffix two
# concurrent runs would each kill the other's qemu (#891).
if [[ -n "${WH_RUN_ID}" ]]; then
  WH_ROUTER_VM_NAME="${WH_ROUTER_VM_NAME:-wh-router-${WH_RUN_ID}}"
else
  WH_ROUTER_VM_NAME="${WH_ROUTER_VM_NAME:-wh-router}"
fi

# Router VM size + WAN-side SSH hostfwd (LuCI HTTP forwarded for manual poking).
# When WH_PORT_BASE is set and the individual port var is unset, derive:
#   router SSH  = WH_PORT_BASE
#   router HTTP = WH_PORT_BASE + 1
#   client SSH  = WH_PORT_BASE + 2
WH_ROUTER_MEM_MB="${WH_ROUTER_MEM_MB:-512}"
WH_ROUTER_DISK_SIZE="${WH_ROUTER_DISK_SIZE:-512M}"
if [[ -n "${WH_PORT_BASE}" ]]; then
  WH_ROUTER_SSH_PORT="${WH_ROUTER_SSH_PORT:-${WH_PORT_BASE}}"
  WH_ROUTER_HTTP_PORT="${WH_ROUTER_HTTP_PORT:-$((WH_PORT_BASE + 1))}"
  WH_CLIENT_SSH_PORT_BASE="${WH_CLIENT_SSH_PORT_BASE:-$((WH_PORT_BASE + 2))}"
else
  WH_ROUTER_SSH_PORT="${WH_ROUTER_SSH_PORT:-2222}"
  WH_ROUTER_HTTP_PORT="${WH_ROUTER_HTTP_PORT:-8080}"
  WH_CLIENT_SSH_PORT_BASE="${WH_CLIENT_SSH_PORT_BASE:-2223}"
fi

# Stable, locally-administered MACs so the orchestrator can match-by-MAC in
# router-side logs. When WH_RUN_ID is set, mix it into the last two octets so
# two concurrent pairs get distinct MACs even on the same L2; explicit
# WH_ROUTER_MAC_LAN / WH_ROUTER_MAC_WAN still win.
if [[ -n "${WH_RUN_ID}" ]]; then
  # First 2 bytes of sha256(WH_RUN_ID) → 4 hex chars, split into two octets.
  if command -v sha256sum >/dev/null 2>&1; then
    _wh_mac_hash="$(printf '%s' "${WH_RUN_ID}" | sha256sum | awk '{print $1}')"
  else
    _wh_mac_hash="$(printf '%s' "${WH_RUN_ID}" | shasum -a 256 | awk '{print $1}')"
  fi
  _wh_mac_oct3="${_wh_mac_hash:0:2}"
  _wh_mac_oct4="${_wh_mac_hash:2:2}"
  WH_ROUTER_MAC_LAN="${WH_ROUTER_MAC_LAN:-${WH_MAC_PREFIX}:fd:${_wh_mac_oct3}:${_wh_mac_oct4}}"
  # WAN differs in the last octet so router-side logs can disambiguate.
  _wh_mac_oct4_alt="$(printf '%02x' $(( 0x${_wh_mac_oct4} ^ 0x01 )))"
  WH_ROUTER_MAC_WAN="${WH_ROUTER_MAC_WAN:-${WH_MAC_PREFIX}:fd:${_wh_mac_oct3}:${_wh_mac_oct4_alt}}"
  unset _wh_mac_hash _wh_mac_oct3 _wh_mac_oct4 _wh_mac_oct4_alt
else
  WH_ROUTER_MAC_LAN="${WH_ROUTER_MAC_LAN:-52:54:00:fd:00:02}"
  WH_ROUTER_MAC_WAN="${WH_ROUTER_MAC_WAN:-52:54:00:fd:00:01}"
fi

# --- SSH management NIC ------------------------------------------------------
# The client VM has two NICs:
#   eth0 — LAN side, attached to ${WH_LAN_BRIDGE}, DHCP from router VM.
#   eth1 — host-side user-mode NIC (QEMU SLIRP), used ONLY for orchestrator
#          SSH via hostfwd. No default route, no DNS — keeps all real traffic
#          on the LAN side so DNS scenarios actually exercise the router.

# Helper: compute the QEMU `-name` for a client slot. Suffixed with WH_RUN_ID
# so client-down.sh's pgrep fallback only matches *this* instance.
wh_client_vm_name() {
  local slot="$1"
  if [[ -n "${WH_RUN_ID}" ]]; then
    printf 'wh-%s-%s' "${slot}" "${WH_RUN_ID}"
  else
    printf 'wh-%s' "${slot}"
  fi
}
