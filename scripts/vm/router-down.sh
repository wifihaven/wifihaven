#!/usr/bin/env bash
# Cleanly shut down the OpenWRT router VM. Safe to call when not running.
# Leaves the LAN bridge in place — call lan-bridge-down.sh separately.

set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
source "${HERE}/lib.sh"

stale_qemu_sweep() {
  # Fallback: a previous run was killed hard (e.g., CI cancellation) and
  # left a qemu alive without an up-to-date pidfile. Kill by name so the
  # next router-up doesn't fail to bind hostfwd ports.
  if pgrep -f "qemu-system-x86_64.*-name ${WH_ROUTER_VM_NAME} -" >/dev/null 2>&1; then
    log "found stale wh-router qemu without pidfile match — killing"
    pkill -f "qemu-system-x86_64.*-name ${WH_ROUTER_VM_NAME} -" || true
    # Give the kernel a moment to release the bound ports.
    sleep 1
  fi
}

if ! router_is_running; then
  log "router VM not running"
  rm -f "${WH_ROUTER_PIDFILE}" "${WH_ROUTER_MONITOR_SOCK}"
  stale_qemu_sweep
  # Release the bridge-pool reservation (#907) even on the not-running path —
  # a failed router-up.sh leaves a reservation containing a now-dead PID, which
  # the reaper would clean up eventually, but doing it here makes the slot
  # immediately available without waiting on the next pick to notice.
  if [[ -f "${WH_RUN_DIR}/lan-bridge" ]]; then
    wh_clear_bridge_reservation "$(cat "${WH_RUN_DIR}/lan-bridge")"
  fi
  exit 0
fi

pid="$(cat "${WH_ROUTER_PIDFILE}")"
log "asking router VM (pid ${pid}) to power off via monitor"

if [[ -S "${WH_ROUTER_MONITOR_SOCK}" ]] && command -v socat >/dev/null 2>&1; then
  printf 'system_powerdown\n' | socat - "UNIX-CONNECT:${WH_ROUTER_MONITOR_SOCK}" >/dev/null 2>&1 || true
  for _ in $(seq 1 20); do
    kill -0 "${pid}" 2>/dev/null || break
    sleep 0.5
  done
fi

if kill -0 "${pid}" 2>/dev/null; then
  log "graceful shutdown timed out; sending SIGTERM"
  kill "${pid}" 2>/dev/null || true
  for _ in $(seq 1 10); do
    kill -0 "${pid}" 2>/dev/null || break
    sleep 0.5
  done
fi

if kill -0 "${pid}" 2>/dev/null; then
  log "SIGTERM ignored; sending SIGKILL"
  kill -9 "${pid}" 2>/dev/null || true
fi

rm -f "${WH_ROUTER_PIDFILE}" "${WH_ROUTER_MONITOR_SOCK}"
stale_qemu_sweep
# Release the bridge-pool reservation (#907). Safe no-op if we weren't using
# a pool bridge — wh_clear_bridge_reservation just rm -f's the marker file.
if [[ -f "${WH_RUN_DIR}/lan-bridge" ]]; then
  wh_clear_bridge_reservation "$(cat "${WH_RUN_DIR}/lan-bridge")"
fi
log "router VM stopped"
