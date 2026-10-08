#!/usr/bin/env bash
# Tests for check-device-profile-writers.sh (#2843). Each case builds a tiny
# api/src tree in a temp dir and asserts the guard's verdict.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
GUARD="${HERE}/check-device-profile-writers.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

fail=0
case_n=0

# run_case <name> <expect: pass|fail> <file-relative-path> <scala source>
run_case() {
  local name="$1" expect="$2" path="$3" src="$4"
  case_n=$((case_n + 1))
  local root="${TMP}/c${case_n}"
  mkdir -p "${root}/$(dirname "${path}")"
  printf '%s\n' "${src}" > "${root}/${path}"
  local got
  if bash "${GUARD}" "${root}" > "${root}.out" 2>&1; then got=pass; else got=fail; fi
  if [[ "${got}" == "${expect}" ]]; then
    echo "ok   ${name}"
  else
    echo "FAIL ${name}: expected ${expect}, got ${got}"
    sed 's/^/     /' "${root}.out"
    fail=1
  fi
}

run_case "UPDATE devices SET profile_id outside the primitive is rejected" fail \
  "api/src/db/Repos.scala" \
  'val q = sql"UPDATE devices SET profile_id = $p WHERE id = $d".update.run'

run_case "multi-line INSERT naming profile_id is rejected" fail \
  "api/src/db/Repos.scala" \
  'val q = sql"""INSERT INTO devices(mac,name,profile_id,household_id)
          VALUES($m,$n,$p,$h)""".update.run'

run_case "ON CONFLICT DO UPDATE SET profile_id is rejected" fail \
  "api/src/routes/X.scala" \
  'val q = sql"insert into devices(mac,name) VALUES($m,$n) ON CONFLICT(mac) DO UPDATE SET profile_id=EXCLUDED.profile_id"'

run_case "the primitive itself may write profile_id" pass \
  "api/src/db/DeviceAssignment.scala" \
  'val q = sql"UPDATE devices SET profile_id = $p WHERE id = $d".update.run'

run_case "a devices write that never names profile_id passes" pass \
  "api/src/db/Repos.scala" \
  'val q = sql"""INSERT INTO devices(mac,name,household_id) VALUES($m,$n,$h)
          ON CONFLICT(household_id,mac) DO UPDATE SET name=EXCLUDED.name RETURNING id"""
val r = sql"SELECT profile_id FROM devices WHERE id = $d"'

run_case "a write to another table naming profile_id passes" pass \
  "api/src/db/Repos.scala" \
  'val q = sql"UPDATE devices_extra SET profile_id = $p".update.run'

# The real tree must pass.
if bash "${GUARD}" "$(cd "${HERE}/../.." && pwd)" > /dev/null; then
  echo "ok   the repository tree passes"
else
  echo "FAIL the repository tree has a devices.profile_id writer outside the primitive"
  bash "${GUARD}" "$(cd "${HERE}/../.." && pwd)" || true
  fail=1
fi

exit "${fail}"
