#!/usr/bin/env bash
# Common helpers for the router VM scripts. Source, do not execute.

set -euo pipefail

HERE_LIB="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=config.sh
source "${HERE_LIB}/config.sh"

mkdir -p "${WH_CACHE_DIR}" "${WH_ROUTER_RUN_DIR}" "${WH_SOCK_DIR}"

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
# If WH_ROUTER_IMAGE_PATH is set, treat it as a pre-built local image
# (e.g. the output of build-router-image.sh) and use it verbatim, skipping
# the download + sha256 check. This is how the custom-image flow from #150
# plugs into the stock router-up.sh path.
ensure_openwrt_image() {
  if [[ -n "${WH_ROUTER_IMAGE_PATH:-}" ]]; then
    [[ -f "${WH_ROUTER_IMAGE_PATH}" ]] || \
      die "WH_ROUTER_IMAGE_PATH set but file missing: ${WH_ROUTER_IMAGE_PATH}"
    WH_ROUTER_BASE_IMG="${WH_ROUTER_IMAGE_PATH}"
    return 0
  fi

  local gz="${WH_CACHE_DIR}/${WH_OPENWRT_IMAGE}"
  local img="${WH_ROUTER_BASE_IMG}"
  local rc

  if [[ -f "${img}" ]]; then
    return 0
  fi

  if [[ ! -f "${gz}" ]]; then
    log "downloading ${WH_OPENWRT_URL}"
    require_cmd curl
    curl -fSL --retry 3 -o "${gz}.part" "${WH_OPENWRT_URL}"
    mv "${gz}.part" "${gz}"
  fi

  local actual
  actual="$(sha256_file "${gz}")"
  if [[ "${actual}" != "${WH_OPENWRT_SHA256}" ]]; then
    rm -f "${gz}"
    die "SHA256 mismatch for ${WH_OPENWRT_IMAGE}: got ${actual}, expected ${WH_OPENWRT_SHA256}"
  fi

  log "decompressing $(basename "${gz}")"
  require_cmd gunzip
  # `if ! gunzip` loses gunzip's real rc ($? inside `then` is the negation's,
  # always 0), which let "rc=2 = trailing garbage, tolerate" silently treat
  # every warning as a fatal rc=0. Capture rc directly via ||.
  local rc=0
  gunzip -k -f "${gz}" || rc=$?
  if (( rc != 0 && rc != 2 )); then
    die "gunzip failed (rc=${rc}) on ${gz}"
  fi
  if (( rc == 2 )); then
    log "gunzip warned on ${gz} (rc=2, trailing garbage ignored) — decompression result follows"
  fi
  [[ -f "${img}" ]] || die "expected ${img} after gunzip"
}

# Create the qcow2 overlay backed by the raw OpenWRT image (idempotent).
#
# If the overlay already exists but its recorded backing-file path does not
# match ${WH_ROUTER_BASE_IMG}, recreate it. Otherwise an operator who points
# WH_ROUTER_IMAGE_PATH at a freshly built image would silently keep booting
# the old one (see issue #756).
ensure_router_overlay() {
  require_cmd qemu-img
  if [[ -f "${WH_ROUTER_OVERLAY}" ]]; then
    local current
    current="$(qemu-img info -U --output=json "${WH_ROUTER_OVERLAY}" \
      | sed -n 's/.*"backing-filename":[[:space:]]*"\([^"]*\)".*/\1/p' \
      | head -n1)"
    if [[ "${current}" != "${WH_ROUTER_BASE_IMG}" ]]; then
      log "overlay backing-file mismatch: have '${current}', want '${WH_ROUTER_BASE_IMG}' — recreating"
      rm -f "${WH_ROUTER_OVERLAY}"
    else
      log "reusing overlay ${WH_ROUTER_OVERLAY} (backing ${current})"
    fi
  fi
  if [[ ! -f "${WH_ROUTER_OVERLAY}" ]]; then
    log "creating qcow2 overlay ${WH_ROUTER_OVERLAY} (backing ${WH_ROUTER_BASE_IMG})"
    qemu-img create -q -f qcow2 -F raw -b "${WH_ROUTER_BASE_IMG}" \
      "${WH_ROUTER_OVERLAY}" "${WH_ROUTER_DISK_SIZE}"
  fi
}

router_is_running() {
  [[ -f "${WH_ROUTER_PIDFILE}" ]] && kill -0 "$(cat "${WH_ROUTER_PIDFILE}")" 2>/dev/null
}

# Host-wide reservation dir for pool bridges (#907). Created mode 1777 by
# lan-bridge-pool-bootstrap.sh so non-root pickers can write reservations.
WH_BRIDGE_RESERVATION_DIR="${WH_BRIDGE_RESERVATION_DIR:-/run/wh-lan-bridge}"

# Host paths the bridge picker reads. Overridable so tests can simulate a
# bridge pool without root / dummy netdevs.
WH_QEMU_BRIDGE_CONF="${WH_QEMU_BRIDGE_CONF:-/etc/qemu/bridge.conf}"
WH_SYS_CLASS_NET="${WH_SYS_CLASS_NET:-/sys/class/net}"

# Atomically (re)write the reservation marker for ${1}=bridge to ${2}=pid,
# tagging it with WH_RUN_ID for human debugging. Silently no-ops if the
# reservation dir does not exist (un-bootstrapped host).
wh_write_bridge_reservation() {
  local br="$1" pid="$2"
  [[ -d "${WH_BRIDGE_RESERVATION_DIR}" ]] || return 0
  local f="${WH_BRIDGE_RESERVATION_DIR}/${br}.reservation"
  local tmp="${f}.tmp.$$"
  printf 'pid=%s\nrun_id=%s\n' "${pid}" "${WH_RUN_ID:-}" > "${tmp}" 2>/dev/null || return 0
  mv -f "${tmp}" "${f}" 2>/dev/null || rm -f "${tmp}"
}

wh_clear_bridge_reservation() {
  local br="$1"
  [[ -d "${WH_BRIDGE_RESERVATION_DIR}" ]] || return 0
  rm -f "${WH_BRIDGE_RESERVATION_DIR}/${br}.reservation" 2>/dev/null || true
}

# Returns 0 if bridge has a live reservation. Reaps stale (dead-PID) markers
# as a side effect so the picker can recycle them.
wh_bridge_reserved() {
  local br="$1"
  local f="${WH_BRIDGE_RESERVATION_DIR}/${br}.reservation"
  [[ -f "${f}" ]] || return 1
  local pid
  pid="$(awk -F= '$1=="pid"{print $2; exit}' "${f}" 2>/dev/null)"
  if [[ -n "${pid}" && "${pid}" =~ ^[0-9]+$ ]] && kill -0 "${pid}" 2>/dev/null; then
    return 0
  fi
  rm -f "${f}" 2>/dev/null || true
  return 1
}

# --- Orphan reaping (#657 / #1163) -------------------------------------------
# A bridge is removed from the pool when it has either an attached tap OR a live
# reservation. A run that ends without its teardown running — a manual session
# the operator forgot, the keep-staging-warm cron, or a CI job whose host
# crashed before the `if: always()` teardown step — leaks one or both and
# permanently shrinks the pool. (This is exactly how run 26716313048 hit "LAN
# bridge pool exhausted": three VMs from a manual debug session four days
# earlier were still squatting wh-lan0/wh-lan1.) The reaper below reclaims such
# leaks before each pick, precisely enough to never disturb a concurrent
# legitimate run.

# Wall-clock lifetime (whole seconds) of a pid, empty if not running.
wh_proc_age_secs() {
  local pid="$1"
  [[ "${pid}" =~ ^[0-9]+$ ]] || return 1
  ps -o etimes= -p "${pid}" 2>/dev/null | tr -d '[:space:]'
}

# Longest wall-clock lifetime a *CI* VM can legitimately reach. The VM gates
# carry timeout-minutes 20–30, so any wh-* qemu older than this on a non-
# headroom bridge is unambiguously a leaked orphan (its run is long gone). The
# 45-minute default sits well above the longest job timeout AND above any live
# concurrent run, so age-reaping can never catch a VM that a real run still
# owns. Override for unusual hosts.
WH_VM_MAX_LIFETIME="${WH_VM_MAX_LIFETIME:-2700}"

# pids of wh-* qemu VMs whose LAN netdev is attached to bridge $1. The trailing
# `([^0-9]|$)` anchors the bridge name so `wh-lan1` does not also match
# `wh-lan10` (substring) — ERE has no lookahead, and the netdev token is always
# followed by a space (the next `-device`) or end-of-arg.
wh_qemu_pids_on_bridge() {
  local br="$1"
  pgrep -f "qemu-system-x86_64.*-name wh-.*br=${br}([^0-9]|\$)" 2>/dev/null || true
}

# pids of ANY qemu attached to bridge $1 (not just our wh-* VMs). Used to decide
# whether a lingering tap is truly ownerless before deleting it.
wh_any_qemu_pids_on_bridge() {
  local br="$1"
  pgrep -f "qemu-system-x86_64.*br=${br}([^0-9]|\$)" 2>/dev/null || true
}

# Delete taps on bridge $1 that no qemu owns (qemu exited but its tap lingered,
# e.g. a hard SIGKILL). No-op while any qemu is still attached — those taps are
# legitimately in use. Best-effort on privilege: tap teardown needs CAP_NET_ADMIN,
# granted to the runner via the NOPASSWD `ip` sudoers rule.
wh_delete_orphan_taps() {
  local br="$1"
  local brif="${WH_SYS_CLASS_NET}/${br}/brif"
  [[ -d "${brif}" ]] || return 0
  # If any qemu (wh-* or otherwise) still holds the bridge, leave its taps alone.
  [[ -n "$(wh_any_qemu_pids_on_bridge "${br}")" ]] && return 0
  local tap
  while IFS= read -r tap; do
    [[ -n "${tap}" ]] || continue
    log "deleting orphan tap ${tap} on ${br} (no owning qemu)"
    ip link delete "${tap}" 2>/dev/null \
      || sudo -n ip link delete "${tap}" 2>/dev/null \
      || log "could not delete tap ${tap} (needs privilege) — leaving for operator"
  done < <(ls "${brif}" 2>/dev/null)
}

# Reclaim leaked bridges across the pool given as positional args. Precise by
# construction — only ever touches a bridge whose owner is provably gone:
#   1. stale (dead-pid) reservation markers           → removed
#   2. wh-* qemus older than WH_VM_MAX_LIFETIME on a   → killed (definitive
#      NON-headroom bridge                                orphan; its run is gone)
#   3. taps with no owning qemu                         → deleted
# It NEVER kills a within-lifetime qemu (a live legitimate run, local or a
# concurrent sibling, is always well under the ceiling) and NEVER age-reaps the
# headroom bridge (the highest-numbered pool member), so a long-running manual
# debug VM parked there survives. Long-lived manual VMs therefore belong on the
# headroom slot — see docs/ops/kvm-runner.md.
wh_reap_orphan_bridges() {
  local headroom
  headroom="$(printf '%s\n' "$@" | sort -V | tail -n1)"
  local br pid age
  for br in "$@"; do
    # (1) Drop a dead-pid reservation as a side effect.
    wh_bridge_reserved "${br}" >/dev/null 2>&1 || true
    # (2) Age-reap orphan qemus on non-headroom bridges.
    if [[ "${br}" != "${headroom}" ]]; then
      while IFS= read -r pid; do
        [[ -n "${pid}" ]] || continue
        age="$(wh_proc_age_secs "${pid}")"
        [[ "${age}" =~ ^[0-9]+$ ]] || continue
        if (( age >= WH_VM_MAX_LIFETIME )); then
          log "reaping orphan qemu pid=${pid} on ${br} (age ${age}s ≥ ${WH_VM_MAX_LIFETIME}s ceiling — no live run owns it)"
          kill "${pid}" 2>/dev/null || true
          for _ in 1 2 3 4 5; do kill -0 "${pid}" 2>/dev/null || break; sleep 0.2; done
          kill -9 "${pid}" 2>/dev/null || true
          wh_clear_bridge_reservation "${br}"
        fi
      done < <(wh_qemu_pids_on_bridge "${br}")
    fi
    # (3) Delete taps left behind by a now-dead qemu.
    wh_delete_orphan_taps "${br}"
  done
}

# Pick a LAN bridge from the wh-lan* pool when one wasn't set explicitly (#891).
#
# Contract:
#   - If WH_LAN_BRIDGE was already set in env, or already resolved from a prior
#     in-run pick (.run/<id>/lan-bridge), this is a no-op.
#   - If no wh-lan* bridges exist at all, this is a no-op — config.sh's default
#     (wh-lan0) stands, and lan-bridge-up.sh will create it. Byte-identical to
#     pre-#891 behavior on un-bootstrapped hosts.
#   - Otherwise, under a host-wide flock, picks the lowest-numbered wh-lan*
#     bridge that is (a) free of attached taps AND (b) has no live reservation
#     marker, records the pick in .run/<id>/lan-bridge plus a host-wide
#     reservation file, releases the flock, and exports WH_LAN_BRIDGE. The
#     reservation is the durable in-use signal for subsequent picks; the flock
#     is only held long enough to make the (check + write) atomic. The flock
#     must NOT be held past return, because bash does not set FD_CLOEXEC on
#     fds opened by `exec N>`, so qemu (forked from router-up.sh) inherits the
#     fd and keeps the lock alive for its own lifetime — blocking every later
#     pick on the host (#907).
#
# Exits non-zero with a clear message if every pool bridge is already in use.
wh_pick_lan_bridge() {
  # Caller set it explicitly (or we already picked in a prior phase) — leave alone.
  if [[ -n "${WH_LAN_BRIDGE_PICKED:-}" ]]; then
    return 0
  fi
  if [[ -f "${WH_RUN_DIR}/lan-bridge" ]]; then
    return 0
  fi
  # If the user set WH_LAN_BRIDGE in env to anything other than the wh-lan0
  # default, treat it as explicit and skip auto-pick. We can't tell "user set
  # to wh-lan0" from "default wh-lan0", so we always run the pick logic — but
  # the pick will only override if a pool exists *and* wh-lan0 is in use.
  # Only consider bridges that are (a) named wh-lanN, (b) actually exist as a
  # kernel netdev, and (c) listed in /etc/qemu/bridge.conf. (c) is what
  # distinguishes a real pool member from a stale leftover bridge a previous
  # experiment forgot to clean up — qemu-bridge-helper would refuse to attach
  # to such a leftover anyway.
  local pool=()
  if [[ -f "${WH_QEMU_BRIDGE_CONF}" ]]; then
    local br
    while IFS= read -r br; do
      [[ -n "${br}" ]] || continue
      [[ "${br}" =~ ^wh-lan[0-9]+$ ]] || continue
      [[ -d "${WH_SYS_CLASS_NET}/${br}" ]] || continue
      pool+=("${br}")
    done < <(awk '/^[[:space:]]*allow[[:space:]]+/ {print $2}' "${WH_QEMU_BRIDGE_CONF}")
  fi
  # No pool bridges exist → fall back to today's behavior (config.sh default).
  if (( ${#pool[@]} == 0 )); then
    return 0
  fi
  # Sort numerically (wh-lan0, wh-lan1, ..., wh-lan10).
  local sorted
  sorted=$(printf '%s\n' "${pool[@]}" | sort -V)
  mkdir -p "${WH_VM_DIR}/.run"
  local lock_file="${WH_VM_DIR}/.run/.bridge-pool.lock"
  exec 9>"${lock_file}"
  # 60s was the original #891 budget when the flock had to span qemu boot;
  # with the reservation marker we only hold it across (scan + write), so 60s
  # is now generous. Kept as a backstop for runaway picker stalls.
  if ! flock -w 60 9; then
    die "could not acquire bridge-pool lock (${lock_file}) within 60s"
  fi
  # Reclaim bridges leaked by crashed / out-of-namespace / forgotten runs before
  # scanning, so a leaked tap or stale marker can't permanently shrink the pool
  # (#657). Held under the same flock as the scan+reserve below, so two
  # concurrent pickers can't race on the same orphan.
  wh_reap_orphan_bridges "${pool[@]}"
  local chosen=""
  local br
  while IFS= read -r br; do
    local attached
    attached=$(ls "${WH_SYS_CLASS_NET}/${br}/brif" 2>/dev/null | wc -l)
    local res="none"
    if [[ -f "${WH_BRIDGE_RESERVATION_DIR}/${br}.reservation" ]]; then
      res="$(awk -F= '$1=="pid"{print $2; exit}' "${WH_BRIDGE_RESERVATION_DIR}/${br}.reservation" 2>/dev/null)"
    fi
    log "pick-debug ${br}: attached=${attached} reservation_pid=${res}"
    if (( attached == 0 )) && ! wh_bridge_reserved "${br}"; then
      chosen="${br}"
      break
    fi
  done <<<"${sorted}"
  if [[ -z "${chosen}" ]]; then
    die "LAN bridge pool exhausted — every wh-lan* is in use (attached tap or live reservation): $(printf '%s ' "${pool[@]}")"
  fi
  mkdir -p "${WH_RUN_DIR}"
  printf '%s\n' "${chosen}" > "${WH_RUN_DIR}/lan-bridge"
  # Reserve under the flock, tagged with the picking shell's PID. router-up.sh
  # overwrites this with qemu's PID once daemonize returns, so the reservation
  # survives the calling shell exiting while qemu still holds the tap.
  wh_write_bridge_reservation "${chosen}" "$$"
  WH_LAN_BRIDGE="${chosen}"
  WH_LAN_BRIDGE_PICKED=1
  export WH_LAN_BRIDGE WH_LAN_BRIDGE_PICKED
  log "picked LAN bridge from pool: ${chosen}"
  # Release the flock immediately — the reservation marker is the durable
  # in-use signal for the next picker, and holding FD 9 open past this point
  # would leak into qemu (no FD_CLOEXEC on `exec N>`) and block every later
  # pick on the host until qemu exits.
  exec 9>&-
}
