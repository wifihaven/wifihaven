#!/usr/bin/env bash
# Test scripts/vm/lib/free-test-ports.sh (#1667).
#
# The script frees TCP loopback listeners on the deterministic per-run
# ports a previously-cancelled Master Router CD arm can leak (qemu
# host-forward ports + the in-process fake_api at WH_PORT_BASE+1000).
# It must:
#   1. SIGTERM (then SIGKILL backstop) any process holding a LISTEN
#      socket on 127.0.0.1:<port> for each port passed in.
#   2. Be a no-op when the port is already free (idempotent across
#      back-to-back invocations).
#   3. Derive the four per-run ports from $WH_PORT_BASE when called
#      with --from-port-base.
#
# Linux-only (uses ss). Runs in CI's "Shell Tests" ubuntu-latest job.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="${HERE}/free-test-ports.sh"

[[ -x "${SCRIPT}" ]] || chmod +x "${SCRIPT}"

if ! command -v ss >/dev/null 2>&1; then
  echo "skip: ss(8) not available (non-Linux); free-test-ports.sh is Linux-only"
  exit 0
fi

PASS=0
FAIL=0

cleanup_pids=()
cleanup() {
  for pid in "${cleanup_pids[@]:-}"; do
    [[ -n "${pid}" ]] && kill -KILL "${pid}" 2>/dev/null || true
  done
}
trap cleanup EXIT

assert_free() {
  local name="$1" port="$2"
  if ss -lntH "sport = :${port}" 2>/dev/null | grep -q ":${port}"; then
    echo "  FAIL: ${name} — port ${port} still bound"
    FAIL=$((FAIL+1))
  else
    echo "  ok: ${name}"
    PASS=$((PASS+1))
  fi
}

assert_bound() {
  local name="$1" port="$2"
  if ss -lntH "sport = :${port}" 2>/dev/null | grep -q ":${port}"; then
    echo "  ok: ${name}"
    PASS=$((PASS+1))
  else
    echo "  FAIL: ${name} — port ${port} not bound"
    FAIL=$((FAIL+1))
  fi
}

start_listener() {
  # $1 = port. Echoes the listener pid.
  local port="$1"
  python3 -c "
import socket, time
s = socket.socket()
s.bind(('127.0.0.1', ${port}))
s.listen(1)
time.sleep(120)
" </dev/null >/dev/null 2>&1 &
  local pid=$!
  cleanup_pids+=("${pid}")
  # Give the bind a moment to land in the kernel's listen list.
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    if ss -lntH "sport = :${port}" 2>/dev/null | grep -q ":${port}"; then
      echo "${pid}"
      return 0
    fi
    sleep 0.1
  done
  echo "listener never bound on ${port}" >&2
  return 1
}

alloc_free_port() {
  python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); p=s.getsockname()[1]; s.close(); print(p)'
}

echo "── case 1: kills a held loopback listener ─────────────────────────────"
PORT1=$(alloc_free_port)
P1=$(start_listener "${PORT1}")
assert_bound "listener started on ${PORT1}" "${PORT1}"
"${SCRIPT}" "${PORT1}"
assert_free "port ${PORT1} freed after script" "${PORT1}"

echo "── case 2: no-op when port already free ───────────────────────────────"
"${SCRIPT}" "${PORT1}"
assert_free "port ${PORT1} still free after idempotent run" "${PORT1}"

echo "── case 3: --from-port-base derives WH_PORT_BASE+1000 (fake-api) ──────"
# Pick a base such that base+1000 is allocatable. Re-roll if any of the
# four derived ports collides.
for _ in 1 2 3 4 5 6 7 8; do
  BASE=$(alloc_free_port)
  P_FAKE=$((BASE+1000))
  # Confirm the fake-api port is actually free before we try to bind it.
  if ! ss -lntH "sport = :${P_FAKE}" 2>/dev/null | grep -q ":${P_FAKE}"; then
    break
  fi
done
P3=$(start_listener "${P_FAKE}")
assert_bound "listener started on derived fake-api port ${P_FAKE}" "${P_FAKE}"
WH_PORT_BASE="${BASE}" "${SCRIPT}" --from-port-base
assert_free "derived fake-api port ${P_FAKE} freed via --from-port-base" "${P_FAKE}"

echo "── case 4: --from-port-base errors out without WH_PORT_BASE ───────────"
if "${SCRIPT}" --from-port-base 2>/dev/null; then
  echo "  FAIL: expected non-zero exit when WH_PORT_BASE unset"
  FAIL=$((FAIL+1))
else
  echo "  ok: refused to run without WH_PORT_BASE"
  PASS=$((PASS+1))
fi

echo
echo "── summary ────────────────────────────────────────────────────────────"
echo "passed: ${PASS}, failed: ${FAIL}"
if [[ "${FAIL}" -ne 0 ]]; then
  exit 1
fi
