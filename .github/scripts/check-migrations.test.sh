#!/usr/bin/env bash
# Tests for check-migrations.sh. Runs against throwaway git repos in /tmp.
set -euo pipefail

SCRIPT="$(cd "$(dirname "$0")" && pwd)/check-migrations.sh"
[[ -x "${SCRIPT}" ]] || chmod +x "${SCRIPT}"

setup() {
  tmp="$(mktemp -d)"
  cd "${tmp}"
  git init -q
  git config user.email t@t.io
  git config user.name tester
  git config commit.gpgsign false
  mkdir -p api/resources/db/migration
  echo "-- v1" > api/resources/db/migration/V1__init.sql
  git add . && git commit -q -m base
  BASE="$(git rev-parse HEAD)"
  git checkout -q -b feature
}

cleanup() { rm -rf "${tmp}"; }

run_case() {
  local name="$1"; shift
  local want="$1"; shift  # "pass" or "fail"
  setup
  "$@"
  set +e
  "${SCRIPT}" "${BASE}" >/tmp/check.out 2>/tmp/check.err
  local rc=$?
  set -e
  unset ALLOW_MIGRATION_MUTATIONS
  cleanup
  if [[ "${want}" == "pass" && ${rc} -eq 0 ]]; then
    echo "PASS: ${name}"
  elif [[ "${want}" == "fail" && ${rc} -ne 0 ]]; then
    echo "PASS: ${name} (rejected as expected)"
  else
    echo "FAIL: ${name} (rc=${rc}, wanted ${want})"
    echo "--- stdout ---"; cat /tmp/check.out
    echo "--- stderr ---"; cat /tmp/check.err
    exit 1
  fi
}

case_no_changes() { :; }

case_add_new() {
  echo "-- v2" > api/resources/db/migration/V2__add.sql
  git add . && git commit -q -m add
}

case_modify() {
  echo "-- changed" >> api/resources/db/migration/V1__init.sql
  git add . && git commit -q -m mod
}

case_delete() {
  git rm -q api/resources/db/migration/V1__init.sql
  git commit -q -m del
}

case_rename() {
  git mv api/resources/db/migration/V1__init.sql api/resources/db/migration/V1__renamed.sql
  git commit -q -m rename
}

case_unrelated_change() {
  mkdir -p other
  echo hi > other/file.txt
  git add . && git commit -q -m unrelated
}

case_add_plus_modify() {
  echo "-- v2" > api/resources/db/migration/V2__add.sql
  echo "-- changed" >> api/resources/db/migration/V1__init.sql
  git add . && git commit -q -m mixed
}

# #883 guardrail: two files at the same V<n>__ prefix must be rejected.
case_dup_version() {
  echo "-- v2" > api/resources/db/migration/V2__one.sql
  echo "-- v2 again" > api/resources/db/migration/V2__two.sql
  git add . && git commit -q -m dup
}

# Escape hatch: ALLOW_MIGRATION_MUTATIONS=1 lets a rename through.
case_rename_with_escape() {
  export ALLOW_MIGRATION_MUTATIONS=1
  git mv api/resources/db/migration/V1__init.sql api/resources/db/migration/V2__init.sql
  git commit -q -m rename
}

run_case "no migration changes"         pass case_no_changes
run_case "added new migration"          pass case_add_new
run_case "unrelated file change"        pass case_unrelated_change
run_case "modified existing migration"  fail case_modify
run_case "deleted existing migration"   fail case_delete
run_case "renamed existing migration"   fail case_rename
run_case "added new + modified existing" fail case_add_plus_modify
run_case "duplicate version numbers"    fail case_dup_version
run_case "rename allowed via escape hatch" pass case_rename_with_escape

echo "All tests passed."
