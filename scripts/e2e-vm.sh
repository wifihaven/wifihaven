#!/usr/bin/env bash
# scripts/e2e-vm.sh — VM e2e orchestrator entrypoint.
#
# Boots a disposable docker compose API stack (with debug endpoints enabled),
# brings up the router VM, enrolls it, takes a base snapshot, then runs the
# six v1 scenarios as pytest tests. The VM lifecycle and snapshot reuse are
# owned by the pytest fixtures in scripts/e2e/conftest.py — this script is a
# thin wrapper that sets up the venv and shells out to pytest.
#
# Usage:
#   scripts/e2e-vm.sh                            # run live-mode scenarios (default)
#   scripts/e2e-vm.sh --mode=fake                # run fake-API mode scenarios (#683)
#   scripts/e2e-vm.sh --only blocked-domain      # run a single scenario
#   scripts/e2e-vm.sh --keep                     # leave VMs + stack up after run
#   scripts/e2e-vm.sh -- -k allowed              # passthrough to pytest
#
# Modes:
#   live  (default) — boot docker-compose API stack, exercise live scenarios.
#   fake            — boot in-process fake API shim, exercise fake-mode
#                     scenarios under scripts/e2e/scenarios_fake/ (Gate 2).
#
# Environment overrides (read by conftest.py / conftest_fake.py):
#   E2E_VM_API_PORT         API stack host port (default 18080; live mode)
#   WH_FAKE_API_PORT        fake API host port (fake mode). If unset, defaults
#                           to WH_PORT_BASE+1000 when WH_PORT_BASE is set (#907),
#                           else a randomly-allocated free port (#902).
#   WH_ROUTER_SSH_PORT      router VM WAN-side SSH hostfwd (auto-allocated if unset)
#   WH_ROUTER_HTTP_PORT     router VM WAN-side HTTP hostfwd (auto-allocated if unset)
#   WH_CLIENT_SSH_PORT_BASE client VM SSH hostfwd (auto-allocated if unset)
#   E2E_VM_KEEP_STACK=1     don't tear down docker compose (live mode)
#   E2E_VM_KEEP=1           don't tear down VMs (router + clients)
#   E2E_VM_SKIP_STACK=1     assume stack already up at $E2E_VM_API_PORT
#   E2E_VM_SKIP_VMS=1       skip VM-dependent tests (CI sanity mode)
#   WH_ROUTER_IMAGE_PATH  use a custom-built router image instead of stock
#                           (required for v1; see scripts/vm/build-router-image.sh)
#
# Host port allocation (#902): all host-side ports (fake-api bind, qemu
# hostfwd) default to free ports allocated at session start, NOT fixed
# constants. This sidesteps a self-hosted-runner failure mode where an
# orphan qemu / fake-api from a prior job lingers and holds the previously
# hard-coded port. Set the relevant env var to override.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
E2E_DIR="${REPO_ROOT}/scripts/e2e"
VENV_DIR="${REPO_ROOT}/.e2e-vm-venv"

ONLY=""
SMOKE=0
MODE="live"
PYTEST_ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --only)
      ONLY="$2"; shift 2 ;;
    --only=*)
      ONLY="${1#--only=}"; shift ;;
    --smoke)
      SMOKE=1; shift ;;
    --mode)
      MODE="$2"; shift 2 ;;
    --mode=*)
      MODE="${1#--mode=}"; shift ;;
    --keep)
      export E2E_VM_KEEP=1 E2E_VM_KEEP_STACK=1; shift ;;
    --skip-vms)
      export E2E_VM_SKIP_VMS=1; shift ;;
    --skip-stack)
      export E2E_VM_SKIP_STACK=1; shift ;;
    --help|-h)
      sed -n '2,30p' "$0"; exit 0 ;;
    --)
      shift; PYTEST_ARGS+=("$@"); break ;;
    *)
      PYTEST_ARGS+=("$1"); shift ;;
  esac
done

case "${MODE}" in
  live|fake) ;;
  *) echo "unknown --mode: ${MODE} (valid: live, fake)" >&2; exit 2 ;;
esac

# --only <name> maps to pytest's marker selection. Supported names match the
# markers defined in scripts/e2e/pytest.ini.
case "${ONLY}" in
  "")           MARK="" ;;
  enrollment)   MARK="enrollment" ;;
  allowed|allowed-browsing)
                MARK="allowed" ;;
  blocked|blocked-domain)
                MARK="blocked" ;;
  daily-limit|daily_limit)
                MARK="daily_limit" ;;
  usage)        MARK="usage" ;;
  blocked-page|blocked_page)
                MARK="blocked_page" ;;
  pause)        MARK="pause" ;;
  extra-blocked|extra_blocked)
                MARK="extra_blocked" ;;
  schedule)     MARK="schedule" ;;
  time-limit|time_limit)
                MARK="time_limit" ;;
  reassignment) MARK="reassignment" ;;
  unknown-device|unknown_device)
                MARK="unknown_device" ;;
  install-health|install_health)
                MARK="install_health" ;;
  *) echo "unknown --only: ${ONLY}" >&2
     echo "valid: enrollment, allowed-browsing, blocked-domain, daily-limit, usage, blocked-page," >&2
     echo "       pause, extra-blocked, schedule, time-limit, reassignment, unknown-device," >&2
     echo "       install-health" >&2
     exit 2 ;;
esac

if [[ "${SMOKE}" == "1" ]]; then
  if [[ -n "${MARK}" ]]; then
    MARK="${MARK} and smoke"
  else
    MARK="smoke"
  fi
fi

# ── venv ─────────────────────────────────────────────────────────────────────

if [[ ! -d "${VENV_DIR}" ]]; then
  echo "creating venv at ${VENV_DIR}"
  python3 -m venv "${VENV_DIR}"
  "${VENV_DIR}/bin/pip" install --quiet --upgrade pip
fi
"${VENV_DIR}/bin/pip" install --quiet -r "${E2E_DIR}/requirements.txt"

# ── prerequisite checks ──────────────────────────────────────────────────────

if [[ "${MODE}" == "live" && "${E2E_VM_SKIP_STACK:-0}" != "1" ]]; then
  command -v docker >/dev/null || { echo "docker not found" >&2; exit 1; }
fi
if [[ "${E2E_VM_SKIP_VMS:-0}" != "1" ]]; then
  command -v qemu-system-x86_64 >/dev/null || {
    echo "qemu-system-x86_64 not found — VM tests will fail." >&2
    echo "(set E2E_VM_SKIP_VMS=1 to skip VM-dependent scenarios)" >&2
    exit 1
  }
  [[ -e /dev/kvm ]] || {
    echo "/dev/kvm not present — VM tests require KVM acceleration." >&2
    echo "(set E2E_VM_SKIP_VMS=1 to skip VM-dependent scenarios)" >&2
    exit 1
  }
fi

# ── host port allocation (#902) ──────────────────────────────────────────────
# Pick free ports for all host-side bindings so a lingering orphan from a
# prior CI run on this same self-hosted runner can't collide. Only fill in
# vars the caller hasn't already set.

alloc_free_port() {
  "${VENV_DIR}/bin/python3" - <<'PY'
import socket
with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
    s.bind(("127.0.0.1", 0))
    print(s.getsockname()[1])
PY
}

# If the caller set WH_PORT_BASE (the #891 concurrent-pair knob), defer to
# config.sh's base-derived defaults rather than randomizing. Keeps the
# explicit "give me a deterministic port window" workflow working.
if [[ "${E2E_VM_SKIP_VMS:-0}" != "1" && -z "${WH_PORT_BASE:-}" ]]; then
  : "${WH_ROUTER_SSH_PORT:=$(alloc_free_port)}"
  : "${WH_ROUTER_HTTP_PORT:=$(alloc_free_port)}"
  : "${WH_CLIENT_SSH_PORT_BASE:=$(alloc_free_port)}"
  export WH_ROUTER_SSH_PORT WH_ROUTER_HTTP_PORT WH_CLIENT_SSH_PORT_BASE
  echo "host ports: router-ssh=${WH_ROUTER_SSH_PORT} router-http=${WH_ROUTER_HTTP_PORT} client-ssh=${WH_CLIENT_SSH_PORT_BASE}"
fi

if [[ "${MODE}" == "fake" ]]; then
  # When the caller pinned a deterministic port window via WH_PORT_BASE
  # (#891 concurrent-pair workflow), derive the fake-api port from it so a
  # second arm gets a distinct, predictable port without needing a second env
  # var. +1000 keeps it clear of the router/client SSH+HTTP triplet (base..base+2)
  # for any reasonable WH_PORT_BASE spacing — concurrent pairs typically
  # space WH_PORT_BASE by ~100 (see issue #907 acceptance repro), so the
  # naive +100 here would collide A's fake-api with B's router-ssh.
  if [[ -z "${WH_FAKE_API_PORT:-}" && -n "${WH_PORT_BASE:-}" ]]; then
    WH_FAKE_API_PORT=$((WH_PORT_BASE + 1000))
  fi
  : "${WH_FAKE_API_PORT:=$(alloc_free_port)}"
  export WH_FAKE_API_PORT
  echo "host ports: fake-api=${WH_FAKE_API_PORT}"
fi

# ── run pytest ───────────────────────────────────────────────────────────────

cd "${E2E_DIR}"

CMD=( "${VENV_DIR}/bin/pytest" )
case "${MODE}" in
  live) CMD+=( "scenarios" ) ;;
  fake) CMD+=( "scenarios_fake" ) ;;
esac
if [[ -n "${MARK}" ]]; then
  CMD+=( -m "${MARK}" )
fi
CMD+=( "${PYTEST_ARGS[@]}" )

echo "→ ${CMD[*]}"
exec "${CMD[@]}"
