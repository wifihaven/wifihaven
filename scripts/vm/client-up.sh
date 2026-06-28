#!/usr/bin/env bash
# Boot a client VM as a qcow2 overlay on top of the cured base image.
#
# Usage:
#   client-up.sh --mac aa:bb:cc:dd:ee:ff [--name client1] [--ssh-port PORT]
#
# Two NICs:
#   eth0 — virtio-net attached to ${WH_LAN_BRIDGE} (the router VM's LAN).
#          DHCP from the router VM. This is the path under test.
#   eth1 — virtio-net on QEMU user-mode networking with an SSH hostfwd. No
#          default route, no DNS. Orchestrator SSH only.
#
# State for a running client lives under ${WH_RUN_DIR}/<name>/:
#   overlay.qcow2  — disk overlay (discarded by client-down.sh)
#   qemu.pid       — qemu pid
#   ssh.port       — host port for SSH
#   mac            — the LAN-side MAC, for `client-exec.sh` debugging
# The QMP control socket lives outside .run/, under WH_SOCK_DIR (a short base
# path), because the AF_UNIX sun_path limit caps socket paths at ~108 chars.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/vm/config.sh
source "${HERE}/config.sh"

NAME="client1"
MAC=""
SSH_PORT=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --mac) MAC="$2"; shift 2 ;;
    --name) NAME="$2"; shift 2 ;;
    --ssh-port) SSH_PORT="$2"; shift 2 ;;
    -h|--help) sed -n '2,18p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown flag: $1" >&2; exit 2 ;;
  esac
done

if [[ -z "${MAC}" ]]; then
  echo "client-up.sh: --mac is required (e.g. 02:00:00:00:00:01)" >&2
  exit 2
fi
if [[ ! "${MAC}" =~ ^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$ ]]; then
  echo "client-up.sh: --mac must be in aa:bb:cc:dd:ee:ff format (got '${MAC}')" >&2
  exit 2
fi
if [[ ! -f "${WH_CLIENT_BASE_IMG}" ]]; then
  if [[ "${WH_AUTOBUILD_CLIENT_BASE:-0}" == "1" ]]; then
    echo "client-up.sh: base image missing — auto-building (WH_AUTOBUILD_CLIENT_BASE=1, ~5 min)" >&2
    "${HERE}/build-client-base.sh"
  else
    {
      echo "client-up.sh: base image missing."
      echo "  Build it:    scripts/vm/build-client-base.sh   (~5 min)"
      echo "  Auto-build:  re-run with WH_AUTOBUILD_CLIENT_BASE=1"
      sibling=""
      if command -v git >/dev/null 2>&1; then
        while IFS= read -r line; do
          [[ "${line}" == "worktree "* ]] || continue
          cand="${line#worktree }/scripts/vm/.cache/client-base.qcow2"
          if [[ -f "${cand}" && "${cand}" != "${WH_CLIENT_BASE_IMG}" ]]; then
            sibling="${cand}"; break
          fi
        done < <(git -C "${HERE}" worktree list --porcelain 2>/dev/null)
      fi
      if [[ -n "${sibling}" ]]; then
        echo "  Or reuse:    mkdir -p '${WH_CACHE_DIR}' && ln -s '${sibling}' '${WH_CLIENT_BASE_IMG}'"
      fi
    } >&2
    exit 1
  fi
fi
if ! ip link show "${WH_LAN_BRIDGE}" >/dev/null 2>&1; then
  echo "client-up.sh: LAN bridge '${WH_LAN_BRIDGE}' does not exist." >&2
  echo "  Bring up the router VM (scripts/vm/router-up.sh, #144) first." >&2
  exit 1
fi

# Git doesn't track non-executable file modes, so the committed private key
# checks out with the umask default (often 0644). SSH refuses to use a key
# that's group/world-readable. Fix it idempotently before any client SSH.
if [[ -f "${WH_CLIENT_SSH_KEY}" ]]; then
  chmod 0600 "${WH_CLIENT_SSH_KEY}" 2>/dev/null || true
fi

RUN_DIR="${WH_RUN_DIR}/${NAME}"

# Within a single WH_RUN_ID, this client slot is single-tenant. If a stale
# client is found (e.g. left by a previous test whose client-up timed out and
# leaked the qemu — #1286), reclaim it rather than hard-failing. This is
# belt-and-suspenders with the EXIT trap below; without it, a leak from any
# code path would cascade into "already running" failures for every subsequent
# scenario test.
if [[ -f "${RUN_DIR}/qemu.pid" ]] && kill -0 "$(cat "${RUN_DIR}/qemu.pid")" 2>/dev/null; then
  echo "[client-up] stale '${NAME}' found (pid $(cat "${RUN_DIR}/qemu.pid")) — reclaiming" >&2
  "${HERE}/client-down.sh" --name "${NAME}" >&2 || true
fi
rm -rf "${RUN_DIR}"
mkdir -p "${RUN_DIR}" "${WH_SOCK_DIR}"

if [[ -z "${SSH_PORT}" ]]; then
  SSH_PORT="${WH_CLIENT_SSH_PORT_BASE}"
fi

OVERLAY="${RUN_DIR}/overlay.qcow2"
PIDFILE="${RUN_DIR}/qemu.pid"
# QMP socket lives under WH_SOCK_DIR (short path) — see config.sh WH_SOCK_DIR
# note for the AF_UNIX sun_path reason.
QMP_SOCK="$(wh_client_qmp_sock "${NAME}")"

# Per-attempt SSH-reachability budget (clients normally come up in ~5s) and the
# number of boot attempts. One retry by default (#2033): on a loaded shared host
# the occasional client never finishes its first boot (DHCP not yet served, tap
# carrier lag, a wedged first boot), reddening an arbitrary scenario with
# "did not become reachable". A single fresh-overlay retry turns that transient
# into a ~90s blip instead of a job failure, while the normal ~5s path is
# unchanged. Overridable for tests / unusual hosts.
WH_CLIENT_BOOT_TIMEOUT="${WH_CLIENT_BOOT_TIMEOUT:-90}"
WH_CLIENT_BOOT_ATTEMPTS="${WH_CLIENT_BOOT_ATTEMPTS:-2}"

# EXIT trap: if the client never becomes reachable (or any failure between
# here and the "client booted" sentinel), kill the qemu we last spawned and
# drop the disposable overlay + control socket. Mirrors router-up.sh (#1225).
# Crucially it does NOT remove ${RUN_DIR}: the serial console.log (and any
# per-attempt console.attemptN.log) must survive so the CI "Upload failure
# artifacts" step can capture them — a boot timeout was previously
# undiagnosable because this trap rm -rf'd the whole run dir, deleting
# console.log before upload (#2033). Leaving the dir behind is safe: the next
# client-up start reclaims+wipes it (stale-reclaim above) and client-down.sh
# removes it in teardown. Once the client is reachable, _client_booted=1 makes
# the trap a no-op on the success path.
_client_booted=0
# shellcheck disable=SC2329  # invoked via trap EXIT, not a direct call
_cleanup_on_fail() {
  [[ "${_client_booted}" == "1" ]] && return 0
  local _pid=""
  if [[ -f "${PIDFILE}" ]]; then
    _pid="$(cat "${PIDFILE}" 2>/dev/null || true)"
  fi
  if [[ -n "${_pid}" ]] && kill -0 "${_pid}" 2>/dev/null; then
    echo "[client-up] cleaning up orphan qemu (pid ${_pid})" >&2
    kill -KILL "${_pid}" 2>/dev/null || true
  fi
  rm -f "${OVERLAY}" "${QMP_SOCK}" 2>/dev/null || true
}
trap _cleanup_on_fail EXIT

ACCEL_ARGS=()
if [[ -e /dev/kvm ]]; then
  ACCEL_ARGS=(-enable-kvm -cpu host)
else
  ACCEL_ARGS=(-cpu max)
fi

# A tap helper is the most portable way to attach to a host bridge from
# unprivileged QEMU. The router-VM side (#144) is expected to ensure the
# bridge exists and that /etc/qemu/bridge.conf allows it.
LAN_NETDEV="bridge,id=lan,br=${WH_LAN_BRIDGE}"
LAN_DEVICE="virtio-net-pci,netdev=lan,mac=${MAC}"

MGMT_NETDEV="user,id=mgmt,net=10.0.2.0/24,host=10.0.2.2,dhcpstart=10.0.2.15,restrict=on,hostfwd=tcp:127.0.0.1:${SSH_PORT}-10.0.2.15:22"
MGMT_DEVICE="virtio-net-pci,netdev=mgmt"

# Bridge readiness (#2033): router-up.sh creates and brings up the LAN bridge
# before the client attaches, but client-up.sh:75 only checked that the netdev
# *exists* (`ip link show`). On a loaded shared host the bridge's IFF_UP /
# carrier can lag that existence check, so the client's tap attaches to a bridge
# that isn't yet passing frames and the first boot's DHCP fails. Poll IFF_UP
# (flags & 0x1) briefly so we attach to a bridge that is actually up. Best
# effort and never fatal — the SSH-reachability loop is still the real gate, and
# the flags file is absent on non-Linux dev hosts / test stubs (skip cleanly).
_wait_lan_bridge_ready() {
  local flags_file="${WH_SYS_CLASS_NET:-/sys/class/net}/${WH_LAN_BRIDGE}/flags"
  [[ -r "${flags_file}" ]] || return 0
  local flags
  for _ in $(seq 1 20); do
    flags="$(cat "${flags_file}" 2>/dev/null || echo 0)"
    if (( (flags & 0x1) != 0 )); then
      return 0
    fi
    sleep 0.5
  done
  echo "[client-up] warning: bridge ${WH_LAN_BRIDGE} not IFF_UP after wait — attaching anyway" >&2
}

# Boot qemu once and wait up to WH_CLIENT_BOOT_TIMEOUT for SSH. Returns 0 when
# reachable, 1 on timeout OR if qemu exits during boot (a crashed boot — e.g. a
# failed tap attach — should not burn the full timeout before we retry).
_boot_attempt() {
  local attempt="$1"
  rm -f "${OVERLAY}"
  qemu-img create -f qcow2 -F qcow2 -b "${WH_CLIENT_BASE_IMG}" "${OVERLAY}" >/dev/null
  rm -f "${QMP_SOCK}"

  # Background the qemu process. -daemonize gives us a stable pidfile. A
  # non-zero launch (e.g. a transient qemu-bridge-helper tap-attach failure
  # under shared-host bridge contention) is treated as a failed ATTEMPT —
  # `return 1` so the retry loop re-spawns — not a hard `set -e` abort that
  # would bypass the retry (#2033).
  if ! qemu-system-x86_64 \
    -name "$(wh_client_vm_name "${NAME}")" \
    -m 512 -smp 1 \
    "${ACCEL_ARGS[@]}" \
    -display none -serial "file:${RUN_DIR}/console.log" \
    -drive "if=virtio,file=${OVERLAY},format=qcow2" \
    -netdev "${LAN_NETDEV}" -device "${LAN_DEVICE}" \
    -netdev "${MGMT_NETDEV}" -device "${MGMT_DEVICE}" \
    -qmp "unix:${QMP_SOCK},server=on,wait=off" \
    -pidfile "${PIDFILE}" \
    -daemonize; then
    echo "[client-up] qemu failed to launch (attempt ${attempt}); see ${RUN_DIR}/console.log" >&2
    return 1
  fi

  echo "${SSH_PORT}" > "${RUN_DIR}/ssh.port"
  echo "${MAC}"     > "${RUN_DIR}/mac"

  local qpid
  qpid="$(cat "${PIDFILE}" 2>/dev/null || true)"
  if [[ -z "${qpid}" ]]; then
    echo "[client-up] qemu wrote no pidfile (attempt ${attempt})" >&2
    return 1
  fi
  echo "[client-up] '${NAME}' booting (attempt ${attempt}/${WH_CLIENT_BOOT_ATTEMPTS}, pid ${qpid}, ssh 127.0.0.1:${SSH_PORT}, mac ${MAC})"

  local deadline
  deadline=$(( $(date +%s) + WH_CLIENT_BOOT_TIMEOUT ))
  while (( $(date +%s) < deadline )); do
    if ! kill -0 "${qpid}" 2>/dev/null; then
      echo "[client-up] qemu (pid ${qpid}) exited during boot (attempt ${attempt}) — console.log tail:" >&2
      tail -n 20 "${RUN_DIR}/console.log" >&2 2>/dev/null || true
      return 1
    fi
    if ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
          -o ConnectTimeout=2 -o BatchMode=yes \
          -i "${WH_CLIENT_SSH_KEY}" -p "${SSH_PORT}" \
          root@127.0.0.1 true 2>/dev/null; then
      echo "[client-up] '${NAME}' ready (attempt ${attempt})"
      return 0
    fi
    sleep 1
  done

  echo "[client-up] '${NAME}' did not become reachable within ${WH_CLIENT_BOOT_TIMEOUT}s (attempt ${attempt}); see ${RUN_DIR}/console.log" >&2
  return 1
}

_wait_lan_bridge_ready

attempt=1
while (( attempt <= WH_CLIENT_BOOT_ATTEMPTS )); do
  if _boot_attempt "${attempt}"; then
    _client_booted=1   # disarm EXIT trap — clean exit from here
    exit 0
  fi
  # This attempt failed: kill its qemu before re-spawning, and preserve its
  # console.log under an attempt-scoped name so a successful retry doesn't
  # overwrite the failed boot's diagnostics (both get swept up by the CI
  # artifact upload, which globs .run/**/console*.log via the broad .run/**).
  _failed_pid=""
  [[ -f "${PIDFILE}" ]] && _failed_pid="$(cat "${PIDFILE}" 2>/dev/null || true)"
  if [[ -n "${_failed_pid}" ]] && kill -0 "${_failed_pid}" 2>/dev/null; then
    kill -KILL "${_failed_pid}" 2>/dev/null || true
  fi
  if (( attempt < WH_CLIENT_BOOT_ATTEMPTS )); then
    [[ -f "${RUN_DIR}/console.log" ]] && \
      mv -f "${RUN_DIR}/console.log" "${RUN_DIR}/console.attempt${attempt}.log" 2>/dev/null || true
    echo "[client-up] '${NAME}' boot attempt ${attempt} failed — retrying" >&2
  fi
  attempt=$(( attempt + 1 ))
done

# Preserve the boot console(s) OUTSIDE the per-client run dir so the diagnostics
# survive the scenario fixture's client_down (scripts/e2e/scenarios_fake/
# conftest.py) — which rm -rf's ${WH_RUN_DIR}/${NAME} — and are still present at
# the CI "Upload failure artifacts" glob (scripts/vm/.run/**). This matters
# because a boot timeout is a pytest SETUP failure, which the conftest
# makereport hook does NOT capture, so without this a mode-1 boot failure lands
# with no console at all (#2033). client_down only removes the per-NAME dir, so
# a sibling boot-failures/ dir survives.
_diag_dir="${WH_RUN_DIR}/boot-failures"
mkdir -p "${_diag_dir}" 2>/dev/null || true
for _cl in "${RUN_DIR}"/console.log "${RUN_DIR}"/console.attempt*.log; do
  [[ -f "${_cl}" ]] || continue
  cp -f "${_cl}" "${_diag_dir}/${NAME}-$(basename "${_cl}")" 2>/dev/null || true
done

echo "[client-up] '${NAME}' did not become reachable after ${WH_CLIENT_BOOT_ATTEMPTS} attempt(s); see ${RUN_DIR}/console.log (preserved under ${_diag_dir}/)" >&2
exit 1
