#!/usr/bin/env bash
# Common helpers for the VM e2e scripts. Source, do not execute.

set -euo pipefail

SCRIPTS_VM_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=versions.sh
source "${SCRIPTS_VM_DIR}/versions.sh"

CACHE_DIR="${SCRIPTS_VM_DIR}/.cache"
mkdir -p "${CACHE_DIR}"

HOST_OS="$(uname -s)"

log() { printf '[vm] %s\n' "$*" >&2; }
die() { printf '[vm] error: %s\n' "$*" >&2; exit 1; }

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

# Compute SHA256 portably (sha256sum on Linux, shasum -a 256 on macOS).
sha256_file() {
  local f="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$f" | awk '{print $1}'
  else
    shasum -a 256 "$f" | awk '{print $1}'
  fi
}

# Download the OpenWRT image to the cache and verify the SHA256.
ensure_openwrt_image() {
  local gz="${CACHE_DIR}/${OPENWRT_IMAGE_FILE}"
  local img="${CACHE_DIR}/${OPENWRT_IMAGE_FILE%.gz}"

  if [[ -f "${img}" ]]; then
    return 0
  fi

  if [[ ! -f "${gz}" ]]; then
    log "downloading ${OPENWRT_IMAGE_URL}"
    require_cmd curl
    curl -fSL --retry 3 -o "${gz}.part" "${OPENWRT_IMAGE_URL}"
    mv "${gz}.part" "${gz}"
  fi

  local actual
  actual="$(sha256_file "${gz}")"
  if [[ "${actual}" != "${OPENWRT_IMAGE_SHA256}" ]]; then
    rm -f "${gz}"
    die "SHA256 mismatch for ${OPENWRT_IMAGE_FILE}: got ${actual}, expected ${OPENWRT_IMAGE_SHA256}"
  fi

  log "decompressing $(basename "${gz}")"
  require_cmd gunzip
  gunzip -k -f "${gz}"
  [[ -f "${img}" ]] || die "expected ${img} after gunzip"
}

# Create / resize the qcow2 overlay backed by the raw OpenWRT image.
ensure_router_overlay() {
  require_cmd qemu-img
  local base="${CACHE_DIR}/${OPENWRT_IMAGE_FILE%.gz}"
  local overlay="${ROUTER_OVERLAY}"

  if [[ ! -f "${overlay}" ]]; then
    log "creating qcow2 overlay ${overlay}"
    qemu-img create -q -f qcow2 -F raw -b "${base}" "${overlay}" "${ROUTER_DISK_SIZE}"
  fi
}

router_is_running() {
  [[ -f "${ROUTER_PIDFILE}" ]] && kill -0 "$(cat "${ROUTER_PIDFILE}")" 2>/dev/null
}
