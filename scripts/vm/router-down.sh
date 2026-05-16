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
  if pgrep -f "qemu-system-x86_64.*-name fdns-router" >/dev/null 2>&1; then
    log "found stale fdns-router qemu without pidfile match — killing"
    pkill -f "qemu-system-x86_64.*-name fdns-router" || true
    # Give the kernel a moment to release the bound ports.
    sleep 1
  fi
}

if ! router_is_running; then
  log "router VM not running"
  rm -f "${FDNS_ROUTER_PIDFILE}" "${FDNS_ROUTER_MONITOR_SOCK}"
  stale_qemu_sweep
  exit 0
fi

pid="$(cat "${FDNS_ROUTER_PIDFILE}")"
log "asking router VM (pid ${pid}) to power off via monitor"

if [[ -S "${FDNS_ROUTER_MONITOR_SOCK}" ]] && command -v socat >/dev/null 2>&1; then
  printf 'system_powerdown\n' | socat - "UNIX-CONNECT:${FDNS_ROUTER_MONITOR_SOCK}" >/dev/null 2>&1 || true
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

rm -f "${FDNS_ROUTER_PIDFILE}" "${FDNS_ROUTER_MONITOR_SOCK}"
stale_qemu_sweep
log "router VM stopped"
