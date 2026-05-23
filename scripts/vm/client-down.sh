#!/usr/bin/env bash
# Shut down a client VM and discard its overlay.
#
# Usage: client-down.sh [--name client1]
#
# Tries QMP `system_powerdown` first (clean ACPI shutdown), then falls back to
# SIGTERM, then SIGKILL. Then removes the run directory so the overlay disk
# (and any scratch state baked into it during the scenario) is gone.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/vm/config.sh
source "${HERE}/config.sh"

NAME="client1"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --name) NAME="$2"; shift 2 ;;
    -h|--help) sed -n '2,10p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown flag: $1" >&2; exit 2 ;;
  esac
done

RUN_DIR="${WH_RUN_DIR}/${NAME}"
PIDFILE="${RUN_DIR}/qemu.pid"
QMP_SOCK="${RUN_DIR}/qemu.sock"

if [[ ! -d "${RUN_DIR}" ]]; then
  echo "[client-down] '${NAME}' not running (no ${RUN_DIR})"
  exit 0
fi

PID=""
if [[ -f "${PIDFILE}" ]]; then
  PID="$(cat "${PIDFILE}")"
fi

if [[ -n "${PID}" ]] && kill -0 "${PID}" 2>/dev/null; then
  if [[ -S "${QMP_SOCK}" ]] && command -v socat >/dev/null 2>&1; then
    # QMP greet+command. Ignore failure — we'll fall through to signals.
    {
      printf '{"execute":"qmp_capabilities"}\n'
      printf '{"execute":"system_powerdown"}\n'
      sleep 0.2
    } | socat - "UNIX-CONNECT:${QMP_SOCK}" >/dev/null 2>&1 || true
  fi

  # Give ACPI a chance, then escalate.
  for _ in $(seq 1 20); do
    kill -0 "${PID}" 2>/dev/null || break
    sleep 0.5
  done
  if kill -0 "${PID}" 2>/dev/null; then
    kill -TERM "${PID}" 2>/dev/null || true
    sleep 2
  fi
  if kill -0 "${PID}" 2>/dev/null; then
    kill -KILL "${PID}" 2>/dev/null || true
  fi
fi

rm -rf "${RUN_DIR}"

# Fallback: a previous run was killed hard (e.g., CI cancellation) and
# left a qemu alive without an up-to-date pidfile. Kill by name so the
# next client-up doesn't fail to bind hostfwd ports.
VM_NAME="$(wh_client_vm_name "${NAME}")"
if pgrep -f "qemu-system-x86_64.*-name ${VM_NAME} -" >/dev/null 2>&1; then
  echo "[client-down] found stale ${VM_NAME} qemu without pidfile match — killing"
  pkill -f "qemu-system-x86_64.*-name ${VM_NAME} -" || true
  # Give the kernel a moment to release the bound ports.
  sleep 1
fi

echo "[client-down] '${NAME}' stopped"
