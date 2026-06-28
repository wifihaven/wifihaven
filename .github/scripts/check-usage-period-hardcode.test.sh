#!/usr/bin/env bash
# Tests for check-usage-period-hardcode.sh (#2020).
#
# Rule under test: a PR may not ADD a hardcoded usage-period literal
# (% 300 / * 300 / / 300 / bare 300 / ofMinutes(5) / ofSeconds(300)) to the
# scoped usage/traffic bucket + rollup paths. Full-comment lines and lines
# carrying the `usage-period-ok` marker are exempt; the same literal in an
# UNSCOPED file is ignored.
set -euo pipefail

SCRIPT="$(cd "$(dirname "$0")" && pwd)/check-usage-period-hardcode.sh"
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
  mkdir -p api/src/usage api/src/routes api/src/db
  # Baseline: scoped files exist with innocuous content so the diff only sees
  # what each case adds.
  echo "object UsageTraffic { def f = 1 }"          > api/src/usage/UsageTraffic.scala
  echo "object SpaPush { def f = 1 }"               > api/src/routes/SpaPush.scala
  echo "object RollupRepo { def f = 1 }"            > api/src/db/RollupRepo.scala
  echo "object Other { def f = 1 }"                 > api/src/routes/Other.scala
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
  "${SCRIPT}" "${BASE}" >/tmp/uph.out 2>/tmp/uph.err
  local rc=$?
  set -e
  rm -rf "${tmp}"; tmp=""
  if [[ "${want}" == "pass" && ${rc} -eq 0 ]]; then
    echo "PASS: ${name}"
  elif [[ "${want}" == "fail" && ${rc} -ne 0 ]]; then
    echo "PASS: ${name} (rejected as expected)"
  else
    echo "FAIL: ${name} (rc=${rc}, wanted ${want})" >&2
    echo "--- stdout ---" >&2; cat /tmp/uph.out >&2
    echo "--- stderr ---" >&2; cat /tmp/uph.err >&2
    exit 1
  fi
}

# ── PASS cases ─────────────────────────────────────────────────────────

case_no_period_literal() {
  echo "  def stepOf = Duration.ofMinutes(1)" >> api/src/usage/UsageTraffic.scala
  git add . && git commit -q -m ok
}

case_period_in_full_comment() {
  # An explanatory comment mentioning the dead literal is fine.
  echo "  // the old code did instant % 300 / ofMinutes(5) — removed in #2018" \
    >> api/src/usage/UsageTraffic.scala
  git add . && git commit -q -m okcomment
}

case_opt_out_marker() {
  echo "  val pageSize = 300 // usage-period-ok: raw row page cap, not a period" \
    >> api/src/routes/SpaPush.scala
  git add . && git commit -q -m optout
}

case_literal_in_unscoped_file() {
  # 300 in a file outside the scoped set is none of this guard's business.
  echo "  val x = instant % 300" >> api/src/routes/Other.scala
  git add . && git commit -q -m unscoped
}

# ── FAIL cases ─────────────────────────────────────────────────────────

case_mod_300() {
  echo "  val w = instant.getEpochSecond % 300" >> api/src/usage/UsageTraffic.scala
  git add . && git commit -q -m bad_mod
}

case_of_minutes_5() {
  echo "  def stepOf = Duration.ofMinutes(5)" >> api/src/usage/UsageTraffic.scala
  git add . && git commit -q -m bad_ofmin
}

case_of_seconds_300() {
  echo "  val step = Duration.ofSeconds(300)" >> api/src/db/RollupRepo.scala
  git add . && git commit -q -m bad_ofsec
}

case_bare_300() {
  echo "  val periodSecs = 300" >> api/src/routes/SpaPush.scala
  git add . && git commit -q -m bad_bare
}

case_mult_300() {
  echo "  val ms = buckets * 300" >> api/src/usage/UsageTraffic.scala
  git add . && git commit -q -m bad_mult
}

run_case "no period literal added"                 pass case_no_period_literal
run_case "literal only in a full comment"          pass case_period_in_full_comment
run_case "opt-out marker on the line"              pass case_opt_out_marker
run_case "literal in an unscoped file"             pass case_literal_in_unscoped_file
run_case "% 300 in scoped code (REJECTED)"         fail case_mod_300
run_case "ofMinutes(5) in scoped code (REJECTED)"  fail case_of_minutes_5
run_case "ofSeconds(300) in scoped code (REJECTED)" fail case_of_seconds_300
run_case "bare 300 in scoped code (REJECTED)"      fail case_bare_300
run_case "* 300 in scoped code (REJECTED)"         fail case_mult_300

echo "All usage-period-hardcode tests passed."
