#!/usr/bin/env bash
# Free leaked TCP loopback listeners on a run's per-arm test ports (#1667).
#
# Why this exists
# ---------------
# The self-hosted KVM runner (api.lan) runs concurrent Master Router CD arms
# that all bind deterministic per-arm host ports derived from $WH_PORT_BASE:
#   router-ssh   = WH_PORT_BASE
#   router-http  = WH_PORT_BASE + 1
#   client-ssh   = WH_PORT_BASE + 2
#   fake-api     = WH_PORT_BASE + 1000   (scripts/e2e-vm.sh, #907)
# When a prior run is cancelled / SIGKILL'd / OOM'd, the in-process
# fake_api python listener can leak: subsequent runs of the same matrix arm
# bind the same WH_PORT_BASE and crash at conftest._start_async with
# `OSError: [Errno 98] address already in use`, taking out Gate 2 fake-mode
# and Gate 3a (#1667). The existing workflow pre-flight kills qemu by name
# scoped to *this* run's WH_RUN_ID, which does not catch the leaked python.
#
# Scope (safety): only TCP LISTEN sockets on 127.0.0.1:<port> or ::1:<port>
# are touched. The production wifihaven-api docker container does not listen
# on those loopback ports, so this script cannot affect production — and we
# *enforce* that scope in the ss filter (`src 127.0.0.1` / `src [::1]`)
# rather than relying on it as a coincidence of the port ranges we currently
# allocate. We resolve the owning pid via `ss -lntpH "sport = :<port> and
# ( src 127.0.0.1 or src [::1] )"` and SIGTERM, then SIGKILL as a backstop
# if the port is still bound a few seconds later.
#
# Usage
# -----
#   scripts/vm/lib/free-test-ports.sh <port> [<port>...]
#   scripts/vm/lib/free-test-ports.sh --from-port-base   # uses $WH_PORT_BASE
#
# Linux-only (uses ss(8)). Idempotent. Echoes each kill so the kill record
# appears in the workflow log (operator-visible signal per AGENTS.md
# §instrument-new-functionality — a CI script's operator surface is the log).
set -euo pipefail

log() { printf '[free-test-ports] %s\n' "$*" >&2; }

pids_on_port() {
  local port="$1"
  # Constrain to loopback (v4 + v6) so we cannot match a non-loopback
  # listener that happens to share the port number — the docstring's
  # "this cannot affect production" safety claim relies on this filter.
  ss -lntpH "sport = :${port} and ( src 127.0.0.1 or src [::1] )" 2>/dev/null \
    | grep -oE 'pid=[0-9]+' \
    | awk -F= '{print $2}' \
    | sort -u
}

free_one_port() {
  local port="$1"
  local pids
  pids="$(pids_on_port "${port}" || true)"
  if [[ -z "${pids}" ]]; then
    return 0
  fi
  for pid in ${pids}; do
    log "SIGTERM pid=${pid} on tcp:${port}"
    kill -TERM "${pid}" 2>/dev/null || true
  done
  for _ in 1 2 3 4 5 6; do
    sleep 0.5
    local still
    still="$(pids_on_port "${port}" || true)"
    if [[ -z "${still}" ]]; then
      return 0
    fi
  done
  # Backstop: SIGKILL anything still holding the port.
  local still
  still="$(pids_on_port "${port}" || true)"
  for pid in ${still}; do
    log "SIGKILL pid=${pid} on tcp:${port} (TERM did not clear in ~3s)"
    kill -KILL "${pid}" 2>/dev/null || true
  done
}

PORTS=()
if [[ "${1:-}" == "--from-port-base" ]]; then
  if [[ -z "${WH_PORT_BASE:-}" ]]; then
    echo "free-test-ports.sh: --from-port-base requires WH_PORT_BASE" >&2
    exit 2
  fi
  PORTS=(
    "${WH_PORT_BASE}"
    "$((WH_PORT_BASE + 1))"
    "$((WH_PORT_BASE + 2))"
    "$((WH_PORT_BASE + 1000))"
  )
elif [[ "$#" -eq 0 ]]; then
  echo "usage: free-test-ports.sh <port> [<port>...] | --from-port-base" >&2
  exit 2
else
  PORTS=( "$@" )
fi

for p in "${PORTS[@]}"; do
  free_one_port "${p}"
done
