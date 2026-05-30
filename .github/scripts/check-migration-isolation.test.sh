#!/usr/bin/env bash
# Tests for check-migration-isolation.sh.
set -euo pipefail

SCRIPT="$(cd "$(dirname "$0")" && pwd)/check-migration-isolation.sh"
[[ -x "${SCRIPT}" ]] || chmod +x "${SCRIPT}"

tmp=""
cleanup() { [[ -n "${tmp}" ]] && rm -rf "${tmp}"; return 0; }
trap cleanup EXIT

setup() {
  tmp="$(mktemp -d)"
  cd "${tmp}"
  git init -q
  git config user.email t@t.io
  git config user.name tester
  git config commit.gpgsign false
  mkdir -p api/resources/db/migration api/src/main api/test/src \
           shared/src shared/test openwrt/files openwrt/test \
           opnsense web/src docker .github
  echo "-- v1" > api/resources/db/migration/V1__init.sql
  echo "// prod" > api/src/main/Main.scala
  echo "// shared" > shared/src/Models.scala
  echo "-- lua" > openwrt/files/policy.lua
  echo "# agent" > opnsense/agent.py
  echo "# tsx" > web/src/App.tsx
  echo "# docs" > README.md
  git add . && git commit -q -m base
  BASE="$(git rev-parse HEAD)"
  git checkout -q -b feature
}

run_case() {
  local name="$1"; shift
  local want="$1"; shift
  setup
  "$@"
  set +e
  "${SCRIPT}" "${BASE}" >/tmp/iso.out 2>/tmp/iso.err
  local rc=$?
  set -e
  rm -rf "${tmp}"; tmp=""
  if [[ "${want}" == "pass" && ${rc} -eq 0 ]]; then
    echo "PASS: ${name}"
  elif [[ "${want}" == "fail" && ${rc} -ne 0 ]]; then
    echo "PASS: ${name} (rejected as expected)"
  else
    echo "FAIL: ${name} (rc=${rc}, wanted ${want})" >&2
    echo "--- stdout ---" >&2; cat /tmp/iso.out >&2
    echo "--- stderr ---" >&2; cat /tmp/iso.err >&2
    exit 1
  fi
}

case_no_migration() {
  echo "// edit" >> api/src/main/Main.scala
  git add . && git commit -q -m edit
}

case_migration_only() {
  echo "-- v2" > api/resources/db/migration/V2__add.sql
  git add . && git commit -q -m mig
}

case_migration_plus_tests() {
  echo "-- v2" > api/resources/db/migration/V2__add.sql
  echo "// test" > api/test/src/NewSpec.scala
  echo "// shared test" > shared/test/Spec.scala
  git add . && git commit -q -m migtest
}

case_migration_plus_docs() {
  echo "-- v2" > api/resources/db/migration/V2__add.sql
  echo "docs change" >> README.md
  mkdir -p docs
  echo "# new" > docs/new.md
  git add . && git commit -q -m migdoc
}

case_migration_plus_workflow() {
  echo "-- v2" > api/resources/db/migration/V2__add.sql
  mkdir -p .github/workflows
  echo "name: x" > .github/workflows/x.yml
  git add . && git commit -q -m migci
}

case_migration_plus_scala_src() {
  echo "-- v2" > api/resources/db/migration/V2__add.sql
  echo "// touch" >> api/src/main/Main.scala
  git add . && git commit -q -m bad1
}

case_migration_plus_shared_src() {
  echo "-- v2" > api/resources/db/migration/V2__add.sql
  echo "// touch" >> shared/src/Models.scala
  git add . && git commit -q -m bad2
}

case_migration_plus_router_agent() {
  echo "-- v2" > api/resources/db/migration/V2__add.sql
  echo "-- touch" >> openwrt/files/policy.lua
  git add . && git commit -q -m bad3
}

case_migration_plus_opnsense_agent() {
  echo "-- v2" > api/resources/db/migration/V2__add.sql
  echo "# touch" >> opnsense/agent.py
  git add . && git commit -q -m bad4
}

case_migration_plus_web_src() {
  echo "-- v2" > api/resources/db/migration/V2__add.sql
  echo "// touch" >> web/src/App.tsx
  git add . && git commit -q -m bad5
}

run_case "no migration — prod edit allowed"        pass case_no_migration
run_case "migration only"                          pass case_migration_only
run_case "migration + tests"                       pass case_migration_plus_tests
run_case "migration + docs"                        pass case_migration_plus_docs
run_case "migration + workflows"                   pass case_migration_plus_workflow
run_case "migration + Scala api/src"               fail case_migration_plus_scala_src
run_case "migration + shared/src"                  fail case_migration_plus_shared_src
run_case "migration + openwrt router agent"        fail case_migration_plus_router_agent
run_case "migration + opnsense agent"              fail case_migration_plus_opnsense_agent
run_case "migration + web/src"                     fail case_migration_plus_web_src

echo "All migration-isolation tests passed."
