#!/usr/bin/env bash
# Common helpers for the router VM scripts. Source, do not execute.

set -euo pipefail

HERE_LIB="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=config.sh
source "${HERE_LIB}/config.sh"

mkdir -p "${FDNS_CACHE_DIR}" "${FDNS_ROUTER_RUN_DIR}"

log() { printf '[router-vm] %s\n' "$*" >&2; }
die() { printf '[router-vm] error: %s\n' "$*" >&2; exit 1; }

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

sha256_file() {
  local f="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$f" | awk '{print $1}'
  else
    shasum -a 256 "$f" | awk '{print $1}'
  fi
}

# Download (if missing) and decompress the pinned OpenWRT image.
#
# If FDNS_ROUTER_IMAGE_PATH is set, treat it as a pre-built local image
# (e.g. the output of build-router-image.sh) and use it verbatim, skipping
# the download + sha256 check. This is how the custom-image flow from #150
# plugs into the stock router-up.sh path.
ensure_openwrt_image() {
  if [[ -n "${FDNS_ROUTER_IMAGE_PATH:-}" ]]; then
    [[ -f "${FDNS_ROUTER_IMAGE_PATH}" ]] || \
      die "FDNS_ROUTER_IMAGE_PATH set but file missing: ${FDNS_ROUTER_IMAGE_PATH}"
    FDNS_ROUTER_BASE_IMG="${FDNS_ROUTER_IMAGE_PATH}"
    return 0
  fi

  local gz="${FDNS_CACHE_DIR}/${FDNS_OPENWRT_IMAGE}"
  local img="${FDNS_ROUTER_BASE_IMG}"

  if [[ -f "${img}" ]]; then
    return 0
  fi

  if [[ ! -f "${gz}" ]]; then
    log "downloading ${FDNS_OPENWRT_URL}"
    require_cmd curl
    curl -fSL --retry 3 -o "${gz}.part" "${FDNS_OPENWRT_URL}"
    mv "${gz}.part" "${gz}"
  fi

  local actual
  actual="$(sha256_file "${gz}")"
  if [[ "${actual}" != "${FDNS_OPENWRT_SHA256}" ]]; then
    rm -f "${gz}"
    die "SHA256 mismatch for ${FDNS_OPENWRT_IMAGE}: got ${actual}, expected ${FDNS_OPENWRT_SHA256}"
  fi

  log "decompressing $(basename "${gz}")"
  require_cmd gunzip
  gunzip -k -f "${gz}"
  [[ -f "${img}" ]] || die "expected ${img} after gunzip"
}

# Create the qcow2 overlay backed by the raw OpenWRT image (idempotent).
ensure_router_overlay() {
  require_cmd qemu-img
  if [[ ! -f "${FDNS_ROUTER_OVERLAY}" ]]; then
    log "creating qcow2 overlay ${FDNS_ROUTER_OVERLAY}"
    qemu-img create -q -f qcow2 -F raw -b "${FDNS_ROUTER_BASE_IMG}" \
      "${FDNS_ROUTER_OVERLAY}" "${FDNS_ROUTER_DISK_SIZE}"
  fi
}

router_is_running() {
  [[ -f "${FDNS_ROUTER_PIDFILE}" ]] && kill -0 "$(cat "${FDNS_ROUTER_PIDFILE}")" 2>/dev/null
}
