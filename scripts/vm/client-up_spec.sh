#!/usr/bin/env bash
# Shell-level tests for scripts/vm/client-up.sh.
#
# Covers the orphan-qemu fix (#1286) and the Gate-2 boot-resilience hardening
# (#2033):
#   (a) On a boot timeout, client-up.sh kills the qemu it spawned AND PRESERVES
#       ${RUN_DIR}/console.log (so the CI artifact upload can capture it). The
#       trap used to rm -rf the whole run dir, deleting console.log before
#       upload — boot timeouts were undiagnosable.
#   (b) A second client-up.sh invocation reclaims a stale client1 instead of
#       erroring with "already running".
#   (c) A boot retry: when the first attempt times out, client-up.sh re-spawns
#       a fresh overlay and a second attempt that succeeds → exit 0.
#   (d) Early-crash detection: when qemu exits during boot, client-up.sh fails
#       fast ("exited during boot") instead of burning the whole timeout.
#
# Run from the scripts/vm/ directory:
#   bash client-up_spec.sh
#
# No actual qemu is started. A stub PATH intercepts external commands and
# simulates the minimal responses needed to exercise the boot/cleanup paths.
# The test uses the real .run/ directory (config.sh unconditionally sets
# WH_RUN_DIR from the script location) and cleans it up after each case.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PASS=0; FAIL=0
check() {
  if [[ "$2" == "ok" ]]; then
    printf "  PASS: %s\n" "$1"; PASS=$((PASS + 1))
  else
    printf "  FAIL: %s — %s\n" "$1" "$2"; FAIL=$((FAIL + 1))
  fi
}

# ── constants derived from config.sh layout ───────────────────────────────────
# config.sh sets these unconditionally from HERE, ignoring any env override.
REAL_RUN_DIR="${HERE}/.run"
REAL_CACHE_DIR="${HERE}/.cache"
REAL_BASE_IMG="${REAL_CACHE_DIR}/client-base.qcow2"
REAL_CLIENT_RUN_DIR="${REAL_RUN_DIR}/client1"

# ── scratch: stubs + temporary files ─────────────────────────────────────────
SCRATCH="$(mktemp -d)"
STUB_BIN="${SCRATCH}/bin"
mkdir -p "${STUB_BIN}"
DATE_COUNTER_FILE="${SCRATCH}/date-count"
SSH_COUNTER_FILE="${SCRATCH}/ssh-count"
SSH_OK_AFTER_FILE="${SCRATCH}/ssh-ok-after"

_STRAY_PIDS=()
_CREATED_CACHE_DIR=0
_CREATED_BASE_IMG=0

_spec_cleanup() {
  # Kill any stray sleep-300 processes we spawned as fake-qemu orphans.
  for p in "${_STRAY_PIDS[@]:-}"; do kill "${p}" 2>/dev/null || true; done
  # Remove the test's .run state (but not an existing real one, if any).
  rm -rf "${REAL_CLIENT_RUN_DIR}"
  rm -rf "${REAL_RUN_DIR}/boot-failures"
  # Remove the dummy base image we created (only if we created it).
  [[ "${_CREATED_BASE_IMG}" -eq 1 ]] && rm -f "${REAL_BASE_IMG}"
  [[ "${_CREATED_CACHE_DIR}" -eq 1 ]] && rmdir "${REAL_CACHE_DIR}" 2>/dev/null || true
  rm -rf "${SCRATCH}"
}
trap '_spec_cleanup' EXIT

# ── create dummy base image (expected by client-up.sh) ───────────────────────
if [[ ! -d "${REAL_CACHE_DIR}" ]]; then
  mkdir -p "${REAL_CACHE_DIR}"; _CREATED_CACHE_DIR=1
fi
if [[ ! -f "${REAL_BASE_IMG}" ]]; then
  touch "${REAL_BASE_IMG}"; _CREATED_BASE_IMG=1
fi

# ── stubs ─────────────────────────────────────────────────────────────────────

# ip link show — always succeeds (bridge exists)
cat > "${STUB_BIN}/ip" <<'EOF'
#!/bin/sh
exit 0
EOF
chmod +x "${STUB_BIN}/ip"

# qemu-img — succeeds silently
cat > "${STUB_BIN}/qemu-img" <<'EOF'
#!/bin/sh
exit 0
EOF
chmod +x "${STUB_BIN}/qemu-img"

# qemu-system-x86_64 — parses -pidfile and -serial, writes the serial log
# (simulating qemu's `-serial file:`), then either:
#   QEMU_CRASH=1 → writes a guaranteed-dead pid (2^31-1) and returns, so the
#                  caller's `kill -0` fails → early-crash path.
#   else         → starts a real background sleep (the "orphan VM") and records
#                  its pid in -pidfile, simulating -daemonize.
# Uses /bin/sleep (full path) to bypass the no-op sleep stub below.
cat > "${STUB_BIN}/qemu-system-x86_64" <<'EOF'
#!/bin/bash
PIDFILE=""; SERIAL=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -pidfile) PIDFILE="$2"; shift 2; continue ;;
    -serial)  SERIAL="$2";  shift 2; continue ;;
    *) shift ;;
  esac
done
case "${SERIAL}" in
  file:*) SLOG="${SERIAL#file:}"; printf 'fake qemu boot log\n' > "${SLOG}" 2>/dev/null || true ;;
esac
if [[ "${QEMU_CRASH:-0}" == "1" ]]; then
  [[ -n "${PIDFILE}" ]] && echo 2147483647 > "${PIDFILE}"
  exit 0
fi
# Detach the background "VM" from inherited stdio so a surviving qemu (the
# success path leaves it running) can't hold a command-substitution pipe open
# and wedge the caller. Real qemu -daemonize closes its inherited fds too.
/bin/sleep 300 </dev/null >/dev/null 2>&1 &
QPID=$!
[[ -n "${PIDFILE}" ]] && echo "${QPID}" > "${PIDFILE}"
/bin/sleep 0.15   # let parent read the pidfile before we return
exit 0
EOF
chmod +x "${STUB_BIN}/qemu-system-x86_64"

# ssh — succeeds on the SSH_OK_AFTER-th call (per the count file), else fails.
# Default threshold is huge (read from SSH_OK_AFTER_FILE) → "never reachable".
cat > "${STUB_BIN}/ssh" <<STUBEOF
#!/bin/sh
CF="${SSH_COUNTER_FILE}"
OKF="${SSH_OK_AFTER_FILE}"
c="\$(cat "\${CF}" 2>/dev/null || echo 0)"
c=\$((c + 1))
echo "\${c}" > "\${CF}"
ok_after="\$(cat "\${OKF}" 2>/dev/null || echo 999999)"
if [ "\${c}" -ge "\${ok_after}" ]; then
  exit 0
fi
exit 1
STUBEOF
chmod +x "${STUB_BIN}/ssh"

# sleep — instant no-op so the ssh-wait loop doesn't wait a real second.
# The qemu stub uses /bin/sleep (full path) to bypass this.
cat > "${STUB_BIN}/sleep" <<'EOF'
#!/bin/sh
exit 0
EOF
chmod +x "${STUB_BIN}/sleep"

# socat — no-op (used by client-down.sh for QMP)
cat > "${STUB_BIN}/socat" <<'EOF'
#!/bin/sh
exit 0
EOF
chmod +x "${STUB_BIN}/socat"

# pgrep — no stale process by name
cat > "${STUB_BIN}/pgrep" <<'EOF'
#!/bin/sh
exit 1
EOF
chmod +x "${STUB_BIN}/pgrep"

# pkill — no-op
cat > "${STUB_BIN}/pkill" <<'EOF'
#!/bin/sh
exit 0
EOF
chmod +x "${STUB_BIN}/pkill"

# date — monotonic stub: each call returns a counter that advances by 1. With a
# small WH_CLIENT_BOOT_TIMEOUT this expires each attempt's wait loop after a
# couple of iterations while still running the loop body (so the kill-0 and ssh
# probes execute), and naturally supports multiple boot attempts.
cat > "${STUB_BIN}/date" <<STUBEOF
#!/bin/sh
CF="${DATE_COUNTER_FILE}"
c="\$(cat "\${CF}" 2>/dev/null || echo 0)"
c=\$((c + 1))
echo "\${c}" > "\${CF}"
echo "\${c}"
STUBEOF
chmod +x "${STUB_BIN}/date"

# Reset per-case counters and ssh threshold.
reset_counters() {
  printf '0' > "${DATE_COUNTER_FILE}"
  printf '0' > "${SSH_COUNTER_FILE}"
  printf '%s' "${1:-999999}" > "${SSH_OK_AFTER_FILE}"   # ssh-ok-after
}

# ── export environment expected by config.sh / client-up.sh ──────────────────
export WH_LAN_BRIDGE="lo"         # ip stub always succeeds; value irrelevant
export WH_CLIENT_SSH_KEY="${SCRATCH}/id_ed25519"
touch "${SCRATCH}/id_ed25519"
export WH_CLIENT_SSH_PORT_BASE="22230"
export WH_RUN_ID=""               # single-pair mode
# Tight boot budget so the (stubbed) wait loops expire in a couple iterations.
export WH_CLIENT_BOOT_TIMEOUT=3

# Prepend stubs before system binaries.
export PATH="${STUB_BIN}:/usr/bin:/bin"

SCRIPT="${HERE}/client-up.sh"
[[ -f "${SCRIPT}" ]] || { printf "MISSING: %s\n" "${SCRIPT}"; exit 1; }

# Track the qemu pid (sleep-300 orphan) written to a run dir's pidfile so the
# spec cleanup can reap it regardless of outcome.
track_pidfile() {
  local pidfile="$1"
  [[ -f "${pidfile}" ]] && _STRAY_PIDS+=( "$(cat "${pidfile}" 2>/dev/null || true)" )
}

# ── test (a): boot timeout kills qemu but PRESERVES console.log (#2033) ───────
export QEMU_CRASH=0
export WH_CLIENT_BOOT_ATTEMPTS=1
reset_counters 999999   # ssh never succeeds
rm -rf "${REAL_CLIENT_RUN_DIR}"

( bash "${SCRIPT}" --mac "02:00:00:00:00:01" --name "client1" >/dev/null 2>&1; ) || true

# The qemu stub spawns /bin/sleep 300 — capture its pid (if the dir survived)
# before asserting it was killed.
QEMU_PID_A=""
if [[ -f "${REAL_CLIENT_RUN_DIR}/qemu.pid" ]]; then
  QEMU_PID_A="$(cat "${REAL_CLIENT_RUN_DIR}/qemu.pid" 2>/dev/null || true)"
  [[ -n "${QEMU_PID_A}" ]] && _STRAY_PIDS+=( "${QEMU_PID_A}" )
fi

if [[ -f "${REAL_CLIENT_RUN_DIR}/console.log" ]]; then
  check "(a) console.log preserved after boot-timeout failure" ok
else
  check "(a) console.log preserved after boot-timeout failure" \
    "console.log was deleted (run dir: ${REAL_CLIENT_RUN_DIR})"
fi

if [[ -n "${QEMU_PID_A}" ]] && kill -0 "${QEMU_PID_A}" 2>/dev/null; then
  check "(a) qemu process killed after boot-timeout failure" \
    "sleep-300 stub (pid ${QEMU_PID_A}) still alive — qemu not killed"
  kill "${QEMU_PID_A}" 2>/dev/null || true
else
  check "(a) qemu process killed after boot-timeout failure" ok
fi

# Overlay (disposable) should be gone; console.log (diagnostic) should remain.
if [[ -f "${REAL_CLIENT_RUN_DIR}/overlay.qcow2" ]]; then
  check "(a) disposable overlay removed on failure" \
    "overlay.qcow2 left behind"
else
  check "(a) disposable overlay removed on failure" ok
fi

# console preserved OUTSIDE the per-client dir (survives client_down rm -rf).
if [[ -f "${REAL_RUN_DIR}/boot-failures/client1-console.log" ]]; then
  check "(a) console preserved under boot-failures/ for CI upload" ok
else
  check "(a) console preserved under boot-failures/ for CI upload" \
    "no ${REAL_RUN_DIR}/boot-failures/client1-console.log"
fi
rm -rf "${REAL_CLIENT_RUN_DIR}" "${REAL_RUN_DIR}/boot-failures"

# ── test (b): second invocation reclaims stale client, no hard-fail ───────────
export QEMU_CRASH=0
export WH_CLIENT_BOOT_ATTEMPTS=1
reset_counters 999999

# Simulate a stale client: create a RUN_DIR with a live-looking pid.
# Use a disposable background sleep — guaranteed alive and safe for
# client-down.sh to terminate (unlike using $$ which would kill this script).
/bin/sleep 300 &
STALE_PID=$!
_STRAY_PIDS+=( "${STALE_PID}" )

rm -rf "${REAL_CLIENT_RUN_DIR}"
mkdir -p "${REAL_CLIENT_RUN_DIR}"
echo "${STALE_PID}" > "${REAL_CLIENT_RUN_DIR}/qemu.pid"

out="$(bash "${SCRIPT}" --mac "02:00:00:00:00:01" --name "client1" 2>&1 || true)"
track_pidfile "${REAL_CLIENT_RUN_DIR}/qemu.pid"
if echo "${out}" | grep -q "already running"; then
  check "(b) second client-up reclaims stale client, no 'already running' error" \
    "got 'already running' — stale client was not reclaimed"
else
  check "(b) second client-up reclaims stale client, no 'already running' error" ok
fi
rm -rf "${REAL_CLIENT_RUN_DIR}"

# ── test (c): boot retry — first attempt times out, second succeeds ───────────
export QEMU_CRASH=0
export WH_CLIENT_BOOT_ATTEMPTS=2
# With WH_CLIENT_BOOT_TIMEOUT=3 + monotonic date stub, each attempt runs ~2
# ssh probes. ssh calls 1,2 (attempt 1) fail; call 3 (attempt 2, iter 1)
# succeeds → exit 0 mid-attempt-2.
reset_counters 3
rm -rf "${REAL_CLIENT_RUN_DIR}"

out="$(bash "${SCRIPT}" --mac "02:00:00:00:00:01" --name "client1" 2>&1; echo "RC=$?")"
rc_c="$(printf '%s\n' "${out}" | sed -n 's/^RC=//p' | tail -n1)"
track_pidfile "${REAL_CLIENT_RUN_DIR}/qemu.pid"

if [[ "${rc_c}" == "0" ]]; then
  check "(c) retry: exit 0 when second attempt succeeds" ok
else
  check "(c) retry: exit 0 when second attempt succeeds" "rc=${rc_c}; out=${out}"
fi
if printf '%s' "${out}" | grep -q "ready (attempt 2)"; then
  check "(c) retry: second attempt reported ready" ok
else
  check "(c) retry: second attempt reported ready" "no 'ready (attempt 2)' in: ${out}"
fi
if [[ -f "${REAL_CLIENT_RUN_DIR}/ssh.port" ]]; then
  check "(c) retry: ssh.port written on success" ok
else
  check "(c) retry: ssh.port written on success" "ssh.port missing"
fi
# On success the EXIT trap is disarmed, so the success qemu (sleep-300) is left
# running. Kill it here.
if [[ -f "${REAL_CLIENT_RUN_DIR}/qemu.pid" ]]; then
  kill "$(cat "${REAL_CLIENT_RUN_DIR}/qemu.pid")" 2>/dev/null || true
fi
rm -rf "${REAL_CLIENT_RUN_DIR}"

# ── test (d): early-crash detection — qemu exits during boot ──────────────────
export QEMU_CRASH=1
export WH_CLIENT_BOOT_ATTEMPTS=1
reset_counters 999999
rm -rf "${REAL_CLIENT_RUN_DIR}"

out="$(bash "${SCRIPT}" --mac "02:00:00:00:00:01" --name "client1" 2>&1 || true)"
if printf '%s' "${out}" | grep -q "exited during boot"; then
  check "(d) early-crash: detected qemu exit during boot" ok
else
  check "(d) early-crash: detected qemu exit during boot" "no 'exited during boot' in: ${out}"
fi
rm -rf "${REAL_CLIENT_RUN_DIR}"
unset QEMU_CRASH

# ── summary ───────────────────────────────────────────────────────────────────
printf "\n%d passed, %d failed\n" "${PASS}" "${FAIL}"
[[ "${FAIL}" -eq 0 ]]
