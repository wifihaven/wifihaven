#!/usr/bin/env bash
# Shell-level tests for scripts/vm/client-up.sh — orphan-qemu fix (#1286).
#
# Asserts:
#   (a) client-up.sh leaves no stale qemu.pid when it exits non-zero
#       (client never became reachable). The spawned qemu process is killed.
#   (b) A second client-up.sh invocation reclaims a stale client1 instead of
#       erroring with "already running".
#
# Run from the scripts/vm/ directory:
#   bash client-up_spec.sh
#
# No actual qemu is started. A stub PATH intercepts external commands and
# simulates the minimal responses needed to exercise the cleanup paths.
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

_STRAY_PIDS=()
_CREATED_CACHE_DIR=0
_CREATED_BASE_IMG=0

_spec_cleanup() {
  # Kill any stray sleep-300 processes we spawned as fake-qemu orphans.
  for p in "${_STRAY_PIDS[@]:-}"; do kill "${p}" 2>/dev/null || true; done
  # Remove the test's .run state (but not an existing real one, if any).
  rm -rf "${REAL_CLIENT_RUN_DIR}"
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

# qemu-system-x86_64 — starts a real background sleep (the "orphan"), writes its
# pid to -pidfile (simulating -daemonize behaviour), then returns.
# Uses /bin/sleep (full path) to bypass the no-op sleep stub below.
cat > "${STUB_BIN}/qemu-system-x86_64" <<'EOF'
#!/bin/bash
while [[ $# -gt 0 ]]; do
  [[ "$1" == "-pidfile" ]] && { PIDFILE="$2"; shift 2; continue; }
  shift
done
/bin/sleep 300 &
QPID=$!
[[ -n "${PIDFILE:-}" ]] && echo "${QPID}" > "${PIDFILE}"
/bin/sleep 0.15   # let parent read the pidfile before we return
exit 0
EOF
chmod +x "${STUB_BIN}/qemu-system-x86_64"

# ssh — always fails (client never becomes reachable)
cat > "${STUB_BIN}/ssh" <<'EOF'
#!/bin/sh
exit 1
EOF
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

# date — stateful stub so the 90s SSH-wait loop expires after a single probe.
# Call 0 → returns a small epoch; sets deadline = small + 90.
# Call 1+ → returns large epoch; while condition (large < small+90) = false → loop exits.
cat > "${STUB_BIN}/date" <<STUBEOF
#!/bin/sh
COUNT_FILE="${DATE_COUNTER_FILE}"
count="\$(cat "\${COUNT_FILE}" 2>/dev/null || printf 0)"
if [ "\${count}" -eq 0 ]; then
  printf '100\n'
  printf '1' > "\${COUNT_FILE}"
else
  printf '9999\n'
fi
STUBEOF
chmod +x "${STUB_BIN}/date"

# Helper: reset date stub counter between test cases
reset_date_stub() { printf '0' > "${DATE_COUNTER_FILE}"; }

# ── export environment expected by config.sh ──────────────────────────────────
export WH_LAN_BRIDGE="lo"         # ip stub always succeeds; value irrelevant
export WH_CLIENT_SSH_KEY="${SCRATCH}/id_ed25519"
touch "${SCRATCH}/id_ed25519"
export WH_CLIENT_SSH_PORT_BASE="22230"
export WH_RUN_ID=""               # single-pair mode

# Prepend stubs before system binaries.
export PATH="${STUB_BIN}:/usr/bin:/bin"

SCRIPT="${HERE}/client-up.sh"
[[ -f "${SCRIPT}" ]] || { printf "MISSING: %s\n" "${SCRIPT}"; exit 1; }

# ── helper ────────────────────────────────────────────────────────────────────
run_client_up() {
  local name="${1:-client1}"
  reset_date_stub
  bash "${SCRIPT}" --mac "02:00:00:00:00:01" --name "${name}" 2>&1 || true
  # Track any "qemu" pid still in the pidfile (may have been cleaned by the fix)
  local pidfile="${REAL_RUN_DIR}/${name}/qemu.pid"
  if [[ -f "${pidfile}" ]]; then
    _STRAY_PIDS+=( "$(cat "${pidfile}")" )
  fi
}

# ── test (a): no orphan left after unreachable-timeout exit ───────────────────
rm -rf "${REAL_CLIENT_RUN_DIR}"

QEMU_PID_BEFORE_EXIT=""
# Run client-up; capture any qemu pid written before exit.
reset_date_stub
# Launch in a subshell so we can peek at the pidfile while qemu is alive.
( bash "${SCRIPT}" --mac "02:00:00:00:00:01" --name "client1" 2>/dev/null; ) || true

# If pidfile still exists, record it.
if [[ -f "${REAL_CLIENT_RUN_DIR}/qemu.pid" ]]; then
  QEMU_PID_BEFORE_EXIT="$(cat "${REAL_CLIENT_RUN_DIR}/qemu.pid")"
  _STRAY_PIDS+=( "${QEMU_PID_BEFORE_EXIT}" )
fi

if [[ -f "${REAL_CLIENT_RUN_DIR}/qemu.pid" ]]; then
  check "(a) no orphan qemu.pid after unreachable-timeout exit" \
    "qemu.pid still exists: ${REAL_CLIENT_RUN_DIR}/qemu.pid"
else
  check "(a) no orphan qemu.pid after unreachable-timeout exit" ok
fi

if [[ -n "${QEMU_PID_BEFORE_EXIT}" ]] && kill -0 "${QEMU_PID_BEFORE_EXIT}" 2>/dev/null; then
  check "(a) qemu process killed after unreachable-timeout exit" \
    "sleep-300 stub (pid ${QEMU_PID_BEFORE_EXIT}) still alive — qemu not killed"
  kill "${QEMU_PID_BEFORE_EXIT}" 2>/dev/null || true
else
  check "(a) qemu process killed after unreachable-timeout exit" ok
fi

# ── test (b): second invocation reclaims stale client, no hard-fail ───────────

# Simulate a stale client: create a RUN_DIR with a live-looking pid.
# Use a disposable background sleep — guaranteed alive and safe for
# client-down.sh to terminate (unlike using $$ which would kill this script).
/bin/sleep 300 &
STALE_PID=$!
_STRAY_PIDS+=( "${STALE_PID}" )

rm -rf "${REAL_CLIENT_RUN_DIR}"
mkdir -p "${REAL_CLIENT_RUN_DIR}"
echo "${STALE_PID}" > "${REAL_CLIENT_RUN_DIR}/qemu.pid"

out="$(run_client_up "client1")"
if echo "${out}" | grep -q "already running"; then
  check "(b) second client-up reclaims stale client, no 'already running' error" \
    "got 'already running' — stale client was not reclaimed"
else
  check "(b) second client-up reclaims stale client, no 'already running' error" ok
fi

if [[ -f "${REAL_CLIENT_RUN_DIR}/qemu.pid" ]]; then
  check "(b) no orphan qemu.pid after reclaim+timeout" \
    "qemu.pid still present after reclaim run"
else
  check "(b) no orphan qemu.pid after reclaim+timeout" ok
fi

# ── summary ───────────────────────────────────────────────────────────────────
printf "\n%d passed, %d failed\n" "${PASS}" "${FAIL}"
[[ "${FAIL}" -eq 0 ]]
