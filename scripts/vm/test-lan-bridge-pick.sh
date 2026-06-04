#!/usr/bin/env bash
# Smoke test for scripts/vm/lib.sh::wh_pick_lan_bridge (#895) and the
# orphan-reaper's TOCTOU tolerance (#1369).
#
# Substitutes for a true red-green on the workflow YAML change in #895: the
# concurrency relaxation relies on the picker failing fast and loud when every
# pool bridge is already in use, so that a fourth concurrent VM run cannot
# silently wedge waiting on a slot that will never come free.
#
# Pick-path cases (need flock(1), Linux-only):
#   1. Empty pool (no reservations) → picks the lowest-numbered bridge.
#   2. Every pool bridge reserved   → exits non-zero with a "pool exhausted"
#      message on stderr.
#   3. Dead-pid reservations        → reaper reclaims them, pick succeeds.
#
# TOCTOU cases (#1369, no flock — run everywhere):
#   4. wh_proc_age_secs <vanished pid> → rc 0 + empty string (never rc 1,
#      which under set -e+pipefail would abort the reaper's `age="$(...)"`
#      assignment BEFORE its numeric-guard `continue` could skip the pid).
#   5. wh_reap_orphan_bridges with a numeric-but-dead pid on a non-headroom
#      bridge → completes (rc 0) instead of aborting — i.e. the caller's
#      guard skips the vanished sibling qemu, matching the prod repro where a
#      concurrent run's qemu exited between the pgrep and our ps.
#
# Runs anywhere; no root, no real bridges, no qemu. Uses tmpdirs for
# /etc/qemu/bridge.conf, /sys/class/net/<br>, and the reservation dir via the
# WH_QEMU_BRIDGE_CONF / WH_SYS_CLASS_NET / WH_BRIDGE_RESERVATION_DIR overrides.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PASS=0
FAIL=0

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

# --- #1369 TOCTOU cases (no flock required) ----------------------------------
# PID 2^31-1 is reserved by Linux and never assigned, so it is numeric yet
# guaranteed dead — exactly the shape of a sibling qemu that exited between the
# reaper's pgrep and our ps.
DEAD_PID=2147483647

echo "test-lan-bridge-pick: case 4 — wh_proc_age_secs tolerates a vanished pid"
# Capture the helper's rc the way a callsite would, but via `|| rc=$?` so we can
# observe the rc instead of aborting on it. Pre-fix this returned 1 (→ caller
# abort under set -e); post-fix it returns 0 with empty output.
proc_age_out="$(
  exec 2>&1
  # shellcheck source=lib.sh
  source "${HERE}/lib.sh"   # sets -euo pipefail
  rc_dead=0
  age_dead="$(wh_proc_age_secs "${DEAD_PID}")" || rc_dead=$?
  rc_nonnum=0
  age_nonnum="$(wh_proc_age_secs "not-a-pid")" || rc_nonnum=$?
  printf 'DEAD_RC=%s\n' "${rc_dead}"
  printf 'DEAD_AGE=[%s]\n' "${age_dead}"
  printf 'NONNUM_RC=%s\n' "${rc_nonnum}"
  printf 'NONNUM_AGE=[%s]\n' "${age_nonnum}"
)"
assert "dead pid → rc 0" "DEAD_RC=0" "${proc_age_out}"
assert "dead pid → empty string" "DEAD_AGE=[]" "${proc_age_out}"
assert "non-numeric pid → rc 0" "NONNUM_RC=0" "${proc_age_out}"
assert "non-numeric pid → empty string" "NONNUM_AGE=[]" "${proc_age_out}"

echo "test-lan-bridge-pick: case 5 — reaper skips a vanished pid without aborting"
# Drive the real reaper with wh_qemu_pids_on_bridge overridden to yield a dead
# numeric pid (simulating a sibling qemu that exited mid-scan). wh-lan0 is the
# non-headroom bridge, so it enters the age-probe loop. Pre-fix: the bare
# `age="$(wh_proc_age_secs ...)"` aborts the whole reaper under set -e, so
# REAPER_DONE never prints and rc is non-zero. Post-fix: the numeric guard
# `continue`s past the dead pid and the reaper completes.
reaper_rc=0
reaper_out="$(
  exec 2>&1
  TMP5="$(mktemp -d)"
  export WH_SYS_CLASS_NET="${TMP5}/sys-class-net"
  export WH_BRIDGE_RESERVATION_DIR="${TMP5}/reservations"
  mkdir -p "${WH_SYS_CLASS_NET}" "${WH_BRIDGE_RESERVATION_DIR}"
  # shellcheck source=lib.sh
  source "${HERE}/lib.sh"   # sets -euo pipefail
  # Override AFTER sourcing so the reaper resolves our stub at call time.
  wh_qemu_pids_on_bridge() { printf '%s\n' "${DEAD_PID}"; }
  # wh-lan0 (non-headroom) gets the age loop; wh-lan1 is the headroom slot.
  wh_reap_orphan_bridges wh-lan0 wh-lan1
  printf 'REAPER_DONE=1\n'
  rm -rf "${TMP5}"
)" || reaper_rc=$?
assert "reaper completes (rc 0)" "REAPER_DONE=1" "${reaper_out}"
if (( reaper_rc == 0 )); then
  echo "  ok: reaper subshell exited 0"
  PASS=$((PASS + 1))
else
  echo "  FAIL: reaper subshell exited ${reaper_rc} (aborted on vanished pid)"
  echo "    output: ${reaper_out}"
  FAIL=$((FAIL + 1))
fi

# --- #895 pick-path cases (need flock(1)) ------------------------------------
# `flock(1)` ships with util-linux on Linux but is not present by default on
# macOS. The picker itself can't run without it either, so these cases are only
# meaningful on the same hosts that run the VM e2e workflows (the self-hosted
# KVM runner). Skip them cleanly elsewhere so `bash scripts/vm/test-lan-bridge-pick.sh`
# from a dev laptop still exercises the #1369 cases above rather than aborting.
if ! command -v flock >/dev/null 2>&1; then
  echo "test-lan-bridge-pick: SKIP pick-path cases 1-3 (no flock(1) on PATH — Linux-only)"
  echo
  echo "test-lan-bridge-pick: passed=${PASS} failed=${FAIL}"
  if (( FAIL > 0 )); then
    exit 1
  fi
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
