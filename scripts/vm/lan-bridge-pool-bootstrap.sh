#!/usr/bin/env bash
# One-time host setup: create the LAN bridge pool used for concurrent VM e2e
# runs (#891). Idempotent.
#
# Creates ${WH_LAN_BRIDGE_POOL_SIZE} bridges named wh-lan0 .. wh-lan${N-1}
# (default N=5) via `sudo ip link add`, and verifies each is listed in
# /etc/qemu/bridge.conf so qemu-bridge-helper will attach taps.
#
# Sizing convention (#1163): N = (concurrent-runner-count) + 1. The trailing
# bridge is the manual-headroom slot reserved for ad-hoc runs and the
# keep-staging-warm cron — it stays free because runners only fan out to N-1
# bridges, and the wh_pick_lan_bridge picker hands out lowest-numbered first.
# Today: 4 runners, 5 bridges. See docs/ops/kvm-runner.md for the full
# invariant.
#
# Also ensures /etc/qemu/bridge.conf has an `allow wh-lanK` line for each
# pool bridge (sudo tee -a). Idempotent: existing lines are left alone.
#
# Usage:
#   sudo scripts/vm/lan-bridge-pool-bootstrap.sh           # default size 5
#   WH_LAN_BRIDGE_POOL_SIZE=8 sudo scripts/vm/lan-bridge-pool-bootstrap.sh

set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=config.sh
source "${HERE}/config.sh"

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "lan-bridge-pool-bootstrap: required command not found: $1" >&2
    exit 1
  }
}
require_cmd ip

SIZE="${WH_LAN_BRIDGE_POOL_SIZE:-5}"
if ! [[ "${SIZE}" =~ ^[0-9]+$ ]] || (( SIZE < 1 )); then
  echo "lan-bridge-pool-bootstrap: WH_LAN_BRIDGE_POOL_SIZE must be a positive integer (got '${SIZE}')" >&2
  exit 2
fi

echo "[bridge-pool] target size: ${SIZE} (wh-lan0..wh-lan$((SIZE - 1)))"

for ((i = 0; i < SIZE; i++)); do
  br="wh-lan${i}"
  if ip link show "${br}" >/dev/null 2>&1; then
    echo "[bridge-pool] ${br} already exists"
  else
    echo "[bridge-pool] creating ${br}"
    sudo ip link add name "${br}" type bridge
  fi
  sudo ip link set "${br}" up
done

# Ensure /etc/qemu/bridge.conf has an `allow` line for every pool member.
# qemu-bridge-helper refuses to attach taps for un-allowlisted bridges.
conf=/etc/qemu/bridge.conf
conf_dir="$(dirname "${conf}")"
if [[ ! -d "${conf_dir}" ]]; then
  echo "[bridge-pool] creating ${conf_dir}"
  sudo mkdir -p "${conf_dir}"
fi
if [[ ! -f "${conf}" ]]; then
  echo "[bridge-pool] creating empty ${conf}"
  echo | sudo tee "${conf}" >/dev/null
fi

missing=()
for ((i = 0; i < SIZE; i++)); do
  br="wh-lan${i}"
  if ! sudo grep -qE "^[[:space:]]*allow[[:space:]]+${br}\b" "${conf}"; then
    missing+=("${br}")
  fi
done

if (( ${#missing[@]} > 0 )); then
  echo "[bridge-pool] appending allow entries to ${conf}: ${missing[*]}"
  for br in "${missing[@]}"; do
    printf 'allow %s\n' "${br}"
  done | sudo tee -a "${conf}" >/dev/null
fi

# Reservation dir (#907) — pool-bridge pickers write per-bridge marker files
# here so the gap between flock-release and qemu tap-attach doesn't admit a
# duplicate pick. Mode 1777 (sticky) so any non-root user can drop a marker
# but cannot remove markers owned by another user.
res_dir="/run/wh-lan-bridge"
if [[ ! -d "${res_dir}" ]]; then
  echo "[bridge-pool] creating ${res_dir} (mode 1777)"
  sudo mkdir -p "${res_dir}"
fi
sudo chmod 1777 "${res_dir}"

echo "[bridge-pool] pool ready: $(for ((i=0; i<SIZE; i++)); do printf 'wh-lan%s ' "${i}"; done)"
