#!/usr/bin/env bash
# Build the client VM base qcow2 from a pristine Alpine cloud image.
#
# Idempotent: a no-op if ${FDNS_CLIENT_BASE_IMG} already exists, unless --force
# is passed.
#
# What it does:
#   1. Download the pinned Alpine NoCloud qcow2 and verify its SHA-512.
#   2. Generate a NoCloud cloud-init seed ISO that:
#        - installs the test tooling (curl, wget, bind-tools, openssh, ...);
#        - bakes the TEST-ONLY SSH key into /root/.ssh/authorized_keys;
#        - configures eth0=dhcp (LAN, via router) and eth1=static no-gateway
#          (orchestrator SSH, host-side user-mode NIC);
#        - poweroffs when done.
#   3. Boot QEMU once with the seed attached, on user-mode networking so apk
#      can reach the internet.
#   4. Move the cured image to ${FDNS_CLIENT_BASE_IMG}.
#
# The base is read-only after this point — per-boot scripts layer qcow2
# overlays on top of it (see client-up.sh).

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/vm/config.sh
source "${HERE}/config.sh"

FORCE=0
for arg in "$@"; do
  case "${arg}" in
    --force) FORCE=1 ;;
    -h|--help)
      sed -n '2,20p' "${BASH_SOURCE[0]}"
      exit 0
      ;;
    *)
      echo "unknown flag: ${arg}" >&2; exit 2 ;;
  esac
done

if [[ -f "${FDNS_CLIENT_BASE_IMG}" && ${FORCE} -eq 0 ]]; then
  echo "[build-client-base] ${FDNS_CLIENT_BASE_IMG} already exists (use --force to rebuild)"
  exit 0
fi

require() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "[build-client-base] missing required tool: $1" >&2
    exit 1
  }
}
require qemu-system-x86_64
require qemu-img
require curl
# Seed ISO generator — accept any of the common names.
ISO_TOOL=""
for cand in xorrisofs genisoimage mkisofs; do
  if command -v "${cand}" >/dev/null 2>&1; then ISO_TOOL="${cand}"; break; fi
done
if [[ -z "${ISO_TOOL}" ]]; then
  echo "[build-client-base] need one of: xorrisofs, genisoimage, mkisofs" >&2
  exit 1
fi

mkdir -p "${FDNS_CACHE_DIR}/pristine"
PRISTINE="${FDNS_CACHE_DIR}/pristine/${FDNS_ALPINE_IMAGE}"

if [[ ! -f "${PRISTINE}" ]]; then
  echo "[build-client-base] downloading ${FDNS_ALPINE_URL}"
  curl -fL --retry 3 -o "${PRISTINE}.tmp" "${FDNS_ALPINE_URL}"
  mv "${PRISTINE}.tmp" "${PRISTINE}"
fi

echo "[build-client-base] verifying SHA-512"
ACTUAL_SHA="$(openssl dgst -sha512 "${PRISTINE}" | awk '{print $NF}')"
if [[ "${ACTUAL_SHA}" != "${FDNS_ALPINE_SHA512}" ]]; then
  echo "[build-client-base] SHA-512 mismatch for ${PRISTINE}" >&2
  echo "  expected: ${FDNS_ALPINE_SHA512}" >&2
  echo "  actual:   ${ACTUAL_SHA}" >&2
  exit 1
fi

if [[ ! -f "${FDNS_CLIENT_SSH_PUB}" ]]; then
  echo "[build-client-base] missing ${FDNS_CLIENT_SSH_PUB}" >&2
  exit 1
fi
PUBKEY="$(cat "${FDNS_CLIENT_SSH_PUB}")"

WORK="$(mktemp -d -t fdns-client-build.XXXXXX)"
trap 'rm -rf "${WORK}"' EXIT

WORK_IMG="${WORK}/work.qcow2"
cp "${PRISTINE}" "${WORK_IMG}"
# Give the base 4 GiB so apk and the package cache fit comfortably.
qemu-img resize "${WORK_IMG}" 4G

# --- NoCloud seed ISO --------------------------------------------------------
SEED_DIR="${WORK}/seed"
mkdir -p "${SEED_DIR}"

cat >"${SEED_DIR}/meta-data" <<EOF
instance-id: fdns-client-base
local-hostname: fdns-client
EOF

# network-config: tell cloud-init's network renderer how to set up both NICs.
# Without this, cloud-init's default network module overwrites
# /etc/network/interfaces with eth0=dhcp only, leaving eth1 (the mgmt NIC
# the orchestrator SSHes into via 127.0.0.1:hostfwd) DOWN forever.
# Match interfaces by MAC: eth0 = LAN-side virtio (any MAC, set per-client
# at client-up time), eth1 = the QEMU user-net MAC 52:54:00:12:34:56.
cat >"${SEED_DIR}/network-config" <<EOF
version: 2
ethernets:
  eth0:
    dhcp4: true
  eth1:
    addresses:
      - 10.0.2.15/24
EOF

# user-data: install packages, set up SSH, write /etc/network/interfaces, then
# poweroff so the caller (this script) knows the build is done.
cat >"${SEED_DIR}/user-data" <<EOF
#cloud-config
ssh_pwauth: false
disable_root: false
users:
  - name: root
    ssh_authorized_keys:
      - ${PUBKEY}

write_files:
  # Stop cloud-init's network module from rewriting /etc/network/interfaces
  # on every subsequent boot. Without this, cloud-init's "fallback" config
  # (used when no seed is present, i.e. every boot after the build) renders
  # eth0=dhcp only and wipes the eth1 config we want.
  - path: /etc/cloud/cloud.cfg.d/99-disable-network-config.cfg
    permissions: '0644'
    content: |
      network: {config: disabled}
  # /etc/network/interfaces: cloud-init's network renderer writes a version
  # of this from the seed's network-config at first boot — we overwrite
  # with the eth0=dhcp + eth1=static config we actually want. write_files
  # runs in the "init" stage, after the renderer's "init-local" stage.
  - path: /etc/network/interfaces
    permissions: '0644'
    content: |
      auto lo
      iface lo inet loopback

      auto eth0
      iface eth0 inet dhcp

      auto eth1
      iface eth1 inet static
          address 10.0.2.15
          netmask 255.255.255.0
  # Write the test SSH key under /etc (parent dir exists) — runcmd below
  # installs it into /root/.ssh. write_files to /root/.ssh/* is unreliable
  # on this Alpine cloud-init build because the parent dir doesn't yet exist.
  - path: /etc/fdns-authorized_keys
    permissions: '0600'
    owner: root:root
    content: |
      ${PUBKEY}
  - path: /etc/motd
    content: |
      wifihaven test client VM — TEST FIXTURE, not for real use.

# apk install. The cloud image already has community/main configured.
packages:
  - curl
  - wget
  - bind-tools
  - openssh
  - openssh-server
  - qemu-guest-agent
  - ca-certificates

runcmd:
  # Alpine cloud images ship with root's /etc/shadow entry set to '!' (locked).
  # PAM rejects SSH for locked accounts even with PermitRootLogin
  # prohibit-password and a valid key in authorized_keys ("User root not
  # allowed because account is locked"). Unlock by setting the password
  # field to '*' — login via password is still impossible, but the account
  # is no longer "locked" for PAM purposes, so key auth works.
  - sed -i 's|^root:[!*]*:|root:*:|' /etc/shadow
  - rc-update add sshd default
  - rc-update add qemu-guest-agent default
  # On Alpine cloud images the networking service is not in the default
  # runlevel out of the box — without this, openrc never reads
  # /etc/network/interfaces on subsequent boots and eth1 stays down.
  - rc-update add networking default
  # PermitRootLogin prohibit-password is the Alpine default; make explicit.
  - sed -i 's/^#\\?PermitRootLogin.*/PermitRootLogin prohibit-password/' /etc/ssh/sshd_config
  # Install the test SSH key staged at /etc/fdns-authorized_keys.
  - mkdir -p /root/.ssh
  - chmod 700 /root/.ssh
  - install -m 0600 -o root -g root /etc/fdns-authorized_keys /root/.ssh/authorized_keys
  # Signal "build complete" by powering off cleanly.
  - poweroff
EOF

SEED_ISO="${WORK}/seed.iso"
case "${ISO_TOOL}" in
  xorrisofs)
    xorrisofs -output "${SEED_ISO}" -volid cidata -joliet -rock \
      "${SEED_DIR}/user-data" "${SEED_DIR}/meta-data" "${SEED_DIR}/network-config" >/dev/null
    ;;
  genisoimage|mkisofs)
    "${ISO_TOOL}" -output "${SEED_ISO}" -volid cidata -joliet -rock \
      "${SEED_DIR}/user-data" "${SEED_DIR}/meta-data" "${SEED_DIR}/network-config" >/dev/null
    ;;
esac

# --- One-shot boot to cure the image -----------------------------------------
ACCEL_ARGS=()
if [[ -e /dev/kvm ]]; then
  ACCEL_ARGS=(-enable-kvm -cpu host)
else
  echo "[build-client-base] /dev/kvm not available — build will be slow" >&2
  ACCEL_ARGS=(-cpu max)
fi

echo "[build-client-base] booting Alpine to install tools (this can take 1–2 minutes)"
# 10 minute hard cap so a stuck build doesn't hang CI.
timeout 600 qemu-system-x86_64 \
  -name fdns-client-base-build \
  -m 1024 -smp 2 \
  "${ACCEL_ARGS[@]}" \
  -nographic -serial mon:stdio \
  -drive "if=virtio,file=${WORK_IMG},format=qcow2" \
  -drive "if=virtio,file=${SEED_ISO},format=raw,readonly=on" \
  -netdev user,id=net0 -device virtio-net-pci,netdev=net0 \
  -no-reboot

mkdir -p "${FDNS_CACHE_DIR}"
mv "${WORK_IMG}" "${FDNS_CLIENT_BASE_IMG}"
echo "[build-client-base] wrote ${FDNS_CLIENT_BASE_IMG}"
