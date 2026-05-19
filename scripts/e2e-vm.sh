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
#   WH_FAKE_API_PORT        fake API host port (default 18090; fake mode)
#   E2E_VM_KEEP_STACK=1     don't tear down docker compose (live mode)
#   E2E_VM_KEEP=1           don't tear down VMs (router + clients)
#   E2E_VM_SKIP_STACK=1     assume stack already up at $E2E_VM_API_PORT
#   E2E_VM_SKIP_VMS=1       skip VM-dependent tests (CI sanity mode)
#   WH_ROUTER_IMAGE_PATH  use a custom-built router image instead of stock
#                           (required for v1; see scripts/vm/build-router-image.sh)

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
  *) echo "unknown --only: ${ONLY}" >&2
     echo "valid: enrollment, allowed-browsing, blocked-domain, daily-limit, usage, blocked-page," >&2
     echo "       pause, extra-blocked, schedule, time-limit, reassignment, unknown-device" >&2
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
