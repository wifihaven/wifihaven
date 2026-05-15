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
#   scripts/e2e-vm.sh                            # run all scenarios
#   scripts/e2e-vm.sh --only blocked-domain      # run a single scenario
#   scripts/e2e-vm.sh --keep                     # leave VMs + stack up after run
#   scripts/e2e-vm.sh -- -k allowed              # passthrough to pytest
#
# Environment overrides (read by conftest.py):
#   E2E_VM_API_PORT         API stack host port (default 18080)
#   E2E_VM_KEEP_STACK=1     don't tear down docker compose
#   E2E_VM_KEEP=1           don't tear down VMs (router + clients)
#   E2E_VM_SKIP_STACK=1     assume stack already up at $E2E_VM_API_PORT
#   E2E_VM_SKIP_VMS=1       skip VM-dependent tests (CI sanity mode)
#   FDNS_ROUTER_IMAGE_PATH  use a custom-built router image instead of stock
#                           (required for v1; see scripts/vm/build-router-image.sh)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
E2E_DIR="${REPO_ROOT}/scripts/e2e"
VENV_DIR="${REPO_ROOT}/.e2e-vm-venv"

ONLY=""
SMOKE=0
PYTEST_ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --only)
      ONLY="$2"; shift 2 ;;
    --only=*)
      ONLY="${1#--only=}"; shift ;;
    --smoke)
      SMOKE=1; shift ;;
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

if [[ "${E2E_VM_SKIP_STACK:-0}" != "1" ]]; then
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
if [[ -n "${MARK}" ]]; then
  CMD+=( -m "${MARK}" )
fi
CMD+=( "${PYTEST_ARGS[@]}" )

echo "→ ${CMD[*]}"
exec "${CMD[@]}"
