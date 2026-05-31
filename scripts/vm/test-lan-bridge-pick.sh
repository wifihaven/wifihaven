#!/usr/bin/env bash
# Smoke test for scripts/vm/lib.sh::wh_pick_lan_bridge (#895).
#
# Substitutes for a true red-green on the workflow YAML change in #895: the
# concurrency relaxation relies on the picker failing fast and loud when every
# pool bridge is already in use, so that a fourth concurrent VM run cannot
# silently wedge waiting on a slot that will never come free.
#
# Two cases:
#   1. Empty pool (no reservations) → picks the lowest-numbered bridge.
#   2. Every pool bridge reserved   → exits non-zero with a "pool exhausted"
#      message on stderr.
#
# Runs anywhere; no root, no real bridges, no qemu. Uses tmpdirs for
# /etc/qemu/bridge.conf, /sys/class/net/<br>, and the reservation dir via the
# WH_QEMU_BRIDGE_CONF / WH_SYS_CLASS_NET / WH_BRIDGE_RESERVATION_DIR overrides.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PASS=0
FAIL=0

# `flock(1)` ships with util-linux on Linux but is not present by default on
# macOS. The picker itself can't run without it either, so this test is only
# meaningful on the same hosts that run the VM e2e workflows (the self-hosted
# KVM runner). Skip cleanly elsewhere so `bash scripts/vm/test-lan-bridge-pick.sh`
# from a dev laptop is a no-op rather than a spurious failure.
if ! command -v flock >/dev/null 2>&1; then
  echo "test-lan-bridge-pick: SKIP (no flock(1) on PATH — Linux-only test)"
  exit 0
fi

setup_tmp() {
  TMP="$(mktemp -d)"
  export WH_QEMU_BRIDGE_CONF="${TMP}/bridge.conf"
  export WH_SYS_CLASS_NET="${TMP}/sys-class-net"
  export WH_BRIDGE_RESERVATION_DIR="${TMP}/reservations"
  export WH_RUN_ID="t-$$"
  # WH_RUN_DIR is derived from WH_RUN_ID under WH_VM_DIR; point it at the tmp.
  export WH_VM_DIR_OVERRIDE_TMP="${TMP}/vmdir"
  mkdir -p "${WH_SYS_CLASS_NET}" "${WH_BRIDGE_RESERVATION_DIR}" "${WH_VM_DIR_OVERRIDE_TMP}/.run"
  # Pool of 4 bridges, mirroring the real api.lan layout.
  {
    echo "allow wh-lan0"
    echo "allow wh-lan1"
    echo "allow wh-lan2"
    echo "allow wh-lan3"
  } > "${WH_QEMU_BRIDGE_CONF}"
  for i in 0 1 2 3; do
    mkdir -p "${WH_SYS_CLASS_NET}/wh-lan${i}/brif"
  done
}

cleanup_tmp() {
  rm -rf "${TMP}"
  unset WH_LAN_BRIDGE WH_LAN_BRIDGE_PICKED
}

# Run wh_pick_lan_bridge in a subshell so we can capture stdout/stderr/rc and
# isolate any exports/exit calls (die uses `exit 1`). We *want* die→exit to
# count as a non-zero rc here, so we wrap the call in `||` to keep the
# subshell alive long enough to emit the trailer.
run_pick() {
  local rc=0
  local stdout_stderr
  stdout_stderr="$(
    exec 2>&1
    set +e
    # shellcheck source=lib.sh
    source "${HERE}/lib.sh"
    # Force the run dir into the tmp so the picker writes its marker safely
    # without touching the real .run tree.
    WH_RUN_DIR="${WH_VM_DIR_OVERRIDE_TMP}/.run"
    WH_VM_DIR="${WH_VM_DIR_OVERRIDE_TMP}"
    wh_pick_lan_bridge
    inner_rc=$?
    echo "RC=${inner_rc}"
    echo "WH_LAN_BRIDGE=${WH_LAN_BRIDGE:-}"
  )" || rc=$?
  printf '%s\nRC=%s\n' "${stdout_stderr}" "${rc}"
}

assert() {
  local name="$1" expected="$2" actual="$3"
  if [[ "${actual}" == *"${expected}"* ]]; then
    echo "  ok: ${name}"
    PASS=$((PASS + 1))
  else
    echo "  FAIL: ${name}"
    echo "    expected substring: ${expected}"
    echo "    actual: ${actual}"
    FAIL=$((FAIL + 1))
  fi
}

set +e
echo "test-lan-bridge-pick: case 1 — empty pool picks wh-lan0"
setup_tmp
out="$(run_pick)"
assert "rc=0" "RC=0" "${out}"
assert "picked wh-lan0" "WH_LAN_BRIDGE=wh-lan0" "${out}"
cleanup_tmp

echo "test-lan-bridge-pick: case 2 — pool exhausted exits non-zero"
setup_tmp
# Reserve every bridge with this shell's PID (guaranteed live → not reaped).
for i in 0 1 2 3; do
  printf 'pid=%s\nrun_id=test\n' "$$" \
    > "${WH_BRIDGE_RESERVATION_DIR}/wh-lan${i}.reservation"
done
out="$(run_pick)"
assert "non-zero rc" "RC=1" "${out}"
assert "pool-exhausted message" "LAN bridge pool exhausted" "${out}"
cleanup_tmp

echo "test-lan-bridge-pick: case 3 — orphan reaper reclaims dead-pid markers"
setup_tmp
# Every bridge carries a reservation whose owning pid is dead (a crashed/
# forgotten run that never ran teardown). wh_reap_orphan_bridges must reclaim
# them so the pool isn't falsely reported exhausted. PID 2^31-1 is reserved by
# Linux and never assigned, so kill -0 always fails → treated as dead.
dead_pid=2147483647
for i in 0 1 2 3; do
  printf 'pid=%s\nrun_id=stale\n' "${dead_pid}" \
    > "${WH_BRIDGE_RESERVATION_DIR}/wh-lan${i}.reservation"
done
out="$(run_pick)"
assert "rc=0 after reclaim" "RC=0" "${out}"
assert "picked reclaimed wh-lan0" "WH_LAN_BRIDGE=wh-lan0" "${out}"
cleanup_tmp
set -e

echo
echo "test-lan-bridge-pick: passed=${PASS} failed=${FAIL}"
if (( FAIL > 0 )); then
  exit 1
fi
