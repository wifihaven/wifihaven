#!/usr/bin/env bash
# Boot a client VM as a qcow2 overlay on top of the cured base image.
#
# Usage:
#   client-up.sh --mac aa:bb:cc:dd:ee:ff [--name client1] [--ssh-port 2223]
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
#   qemu.sock      — QMP monitor socket
#   ssh.port       — host port for SSH
#   mac            — the LAN-side MAC, for `client-exec.sh` debugging

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
  echo "client-up.sh: base image missing — run scripts/vm/build-client-base.sh first" >&2
  exit 1
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
if [[ -f "${RUN_DIR}/qemu.pid" ]] && kill -0 "$(cat "${RUN_DIR}/qemu.pid")" 2>/dev/null; then
  echo "client-up.sh: client '${NAME}' already running (pid $(cat "${RUN_DIR}/qemu.pid"))" >&2
  exit 1
fi
rm -rf "${RUN_DIR}"
mkdir -p "${RUN_DIR}"

if [[ -z "${SSH_PORT}" ]]; then
  SSH_PORT="${WH_CLIENT_SSH_PORT_BASE}"
fi

OVERLAY="${RUN_DIR}/overlay.qcow2"
qemu-img create -f qcow2 -F qcow2 -b "${WH_CLIENT_BASE_IMG}" "${OVERLAY}" >/dev/null

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

PIDFILE="${RUN_DIR}/qemu.pid"
QMP_SOCK="${RUN_DIR}/qemu.sock"

# Background the qemu process. -daemonize gives us a stable pidfile.
qemu-system-x86_64 \
  -name "$(wh_client_vm_name "${NAME}")" \
  -m 512 -smp 1 \
  "${ACCEL_ARGS[@]}" \
  -display none -serial "file:${RUN_DIR}/console.log" \
  -drive "if=virtio,file=${OVERLAY},format=qcow2" \
  -netdev "${LAN_NETDEV}" -device "${LAN_DEVICE}" \
  -netdev "${MGMT_NETDEV}" -device "${MGMT_DEVICE}" \
  -qmp "unix:${QMP_SOCK},server=on,wait=off" \
  -pidfile "${PIDFILE}" \
  -daemonize

echo "${SSH_PORT}" > "${RUN_DIR}/ssh.port"
echo "${MAC}"     > "${RUN_DIR}/mac"

echo "[client-up] '${NAME}' booting (pid $(cat "${PIDFILE}"), ssh 127.0.0.1:${SSH_PORT}, mac ${MAC})"

# Wait until SSH is reachable. Cap at 90s; clients normally come up in ~5s.
deadline=$(( $(date +%s) + 90 ))
while (( $(date +%s) < deadline )); do
  if ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
        -o ConnectTimeout=2 -o BatchMode=yes \
        -i "${WH_CLIENT_SSH_KEY}" -p "${SSH_PORT}" \
        root@127.0.0.1 true 2>/dev/null; then
    echo "[client-up] '${NAME}' ready"
    exit 0
  fi
  sleep 1
done

echo "[client-up] '${NAME}' did not become reachable within 90s; see ${RUN_DIR}/console.log" >&2
exit 1
