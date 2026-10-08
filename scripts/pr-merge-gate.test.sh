#!/usr/bin/env bash
# Tests for scripts/pr-merge-gate.sh (#2869). `classify` and `verdict` are pure;
# `check` and `enqueue` run against a fake `gh` on PATH driven by FAKE_* env vars.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
GATE="${HERE}/pr-merge-gate.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

pass=0
fail=0
ok() { pass=$((pass + 1)); }
bad() { fail=$((fail + 1)); echo "FAIL: $*"; }

expect_eq() { # name expected actual
  if [[ "$2" == "$3" ]]; then ok; else bad "$1: expected [$2], got [$3]"; fi
}

SHA_A="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
SHA_B="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

# ── classify ────────────────────────────────────────────────────────────────
classify() { printf '%s\n' "$@" | bash "${GATE}" classify; }

expect_eq "migration file" "migration: api/resources/db/migration/V91__x.sql" \
  "$(classify api/resources/db/migration/V91__x.sql)"
expect_eq "V<n>__ anywhere" "migration: other/V7__y.sql" "$(classify other/V7__y.sql)"
expect_eq "render.yaml" "prod-config: render.yaml" "$(classify render.yaml)"
expect_eq "cloudflare tf" "prod-config: infra/cloudflare/main.tf" "$(classify infra/cloudflare/main.tf)"
expect_eq "wrangler" "prod-config: web/wrangler.staging.toml" "$(classify web/wrangler.staging.toml)"
expect_eq "entrypoint" "prod-config: docker/entrypoint.sh" "$(classify docker/entrypoint.sh)"
expect_eq "CD workflow" "prod-config: .github/workflows/master-api-ui.yml" \
  "$(classify .github/workflows/master-api-ui.yml)"
expect_eq "gate itself" "merge-policy: scripts/pr-merge-gate.sh" "$(classify scripts/pr-merge-gate.sh)"
expect_eq "review command" "merge-policy: .claude/commands/pr-review.md" "$(classify .claude/commands/pr-review.md)"
expect_eq "canonical doc" "merge-policy: docs/pr-review-checklist.md" "$(classify docs/pr-review-checklist.md)"
expect_eq "rule guard" "merge-policy: .github/scripts/check-merge-rule-single-source.sh" \
  "$(classify .github/scripts/check-merge-rule-single-source.sh)"
expect_eq "eligible paths" "" \
  "$(classify openwrt/files/usr/lib/lua/wifihaven/render.lua api/src/policy/PolicyService.scala \
      .github/workflows/ci.yml infra/grafana/main.tf web/src/App.tsx '')"

# ── verdict ─────────────────────────────────────────────────────────────────
body() { # marker-sha verdict-line...
  printf '<!-- wifihaven-pr-review reviewed-sha=%s -->\nBLOCKERS\n(none)\n' "$1"
  shift
  printf '%s\n' "$@"
}
verdict() { bash "${GATE}" verdict "$1"; }

expect_eq "approve on head" APPROVE "$(body "${SHA_A}" "VERDICT: APPROVE @ ${SHA_A}" | verdict "${SHA_A}")"
expect_eq "request changes" REQUEST-CHANGES \
  "$(body "${SHA_A}" "VERDICT: REQUEST-CHANGES @ ${SHA_A}" | verdict "${SHA_A}")"
# #2829: an APPROVE for the pre-fix SHA must not carry over to the pushed fix.
expect_eq "approve on older sha" STALE "$(body "${SHA_B}" "VERDICT: APPROVE @ ${SHA_B}" | verdict "${SHA_A}")"
expect_eq "marker/verdict disagree" STALE "$(body "${SHA_B}" "VERDICT: APPROVE @ ${SHA_A}" | verdict "${SHA_A}")"
expect_eq "old unbound format" NONE "$(body "${SHA_A}" "VERDICT: APPROVE" | verdict "${SHA_A}")"
expect_eq "two verdict lines" NONE \
  "$(body "${SHA_A}" "VERDICT: REQUEST-CHANGES @ ${SHA_A}" "VERDICT: APPROVE @ ${SHA_A}" | verdict "${SHA_A}")"
expect_eq "indented (quoted) verdict" NONE "$(body "${SHA_A}" "  VERDICT: APPROVE @ ${SHA_A}" | verdict "${SHA_A}")"
expect_eq "short sha" NONE "$(body "${SHA_A}" "VERDICT: APPROVE @ aaaaaaa" | verdict "${SHA_A}")"

# ── check / enqueue against a fake gh ───────────────────────────────────────
mkdir -p "${TMP}/bin"
cat > "${TMP}/bin/gh" <<'FAKE'
#!/usr/bin/env bash
# Fake gh: returns what the real call's --jq filter would print.
args="$*"
echo "${args}" >> "${FAKE_LOG}"
# FAKE_FAIL: any call whose args contain this substring fails (to test exit 2).
if [[ -n "${FAKE_FAIL:-}" && "${args}" == *"${FAKE_FAIL}"* ]]; then echo "HTTP 401" >&2; exit 1; fi
case "${args}" in
  "pr view"*"--json state,"*)
    echo "${FAKE_STATE:-OPEN} ${FAKE_DRAFT:-false} ${FAKE_HEAD} main ${FAKE_MERGEABLE:-MERGEABLE} ${FAKE_CHANGED:-1}" ;;
  "pr view"*"--json headRefOid"*)
    # Successive head reads after the check: FAKE_LATER_HEADS (space-separated), else FAKE_HEAD.
    n="$(grep -c -- '--json headRefOid ' "${FAKE_LOG}")"
    read -r -a later <<< "${FAKE_LATER_HEADS:-}"
    echo "${later[$((n - 1))]:-${FAKE_HEAD}}" ;;
  "pr merge"*"--disable-auto"*) : ;;
  "pr merge"*) exit "${FAKE_MERGE_RC:-0}" ;;
  "api graphql"*"dequeuePullRequest"*) : ;;
  "api graphql"*)
    # queue_state: first read FAKE_QUEUE_BEFORE, later reads FAKE_QUEUE_AFTER.
    n="$(grep -c '^api graphql.*isInMergeQueue' "${FAKE_LOG}")"
    if [[ "${n}" -le 1 ]]; then echo "PR_id ${FAKE_QUEUE_BEFORE:-true false}"; else echo "PR_id ${FAKE_QUEUE_AFTER:-false false}"; fi ;;
  "api repos/"*"/issues/"*"/comments --paginate"*) printf '%s\n' "${FAKE_COMMENTS:-}" ;;
  "api repos/"*"/issues/comments/"*) cat "${FAKE_BODY_FILE}" ;;
  "api repos/"*"/protection/required_status_checks"*) printf '%s\n' "${FAKE_REQUIRED-15368|CI}" ;;
  "api repos/"*"/check-runs"*) printf '%s\n' ${FAKE_CI-success} ;;
  "api repos/"*"/status "*|"api repos/"*"/status") printf '%s\n' ${FAKE_STATUS:-} ;;
  "api repos/"*"/pulls/"*"/files"*) for f in ${FAKE_FILES:-api/src/Main.scala}; do printf '%s\n' "${f/=/$'\t'}"; done ;;
  *) echo "fake gh: unhandled: ${args}" >&2; exit 9 ;;
esac
FAKE
chmod +x "${TMP}/bin/gh"

run_gate() { # cmd -> sets OUT and RC
  : > "${TMP}/log"
  set +e
  OUT="$(PATH="${TMP}/bin:${PATH}" FAKE_LOG="${TMP}/log" FAKE_BODY_FILE="${TMP}/body" \
    PR_MERGE_GATE_BACKOFF_SECS=0 bash "${GATE}" "$1" 123 2>&1)"
  RC=$?
  set -e
}
scenario() { # reset to the happy path: APPROVE on HEAD, CI green, eligible files
  export FAKE_HEAD="${SHA_A}" FAKE_COMMENTS=$'11 MEMBER\n22 MEMBER' FAKE_CI=success FAKE_FILES="api/src/Main.scala"
  unset FAKE_REQUIRED FAKE_STATUS FAKE_STATE FAKE_DRAFT FAKE_MERGEABLE FAKE_LATER_HEADS FAKE_FAIL FAKE_CHANGED FAKE_MERGE_RC \
    FAKE_QUEUE_BEFORE FAKE_QUEUE_AFTER
  body "${SHA_A}" "VERDICT: APPROVE @ ${SHA_A}" > "${TMP}/body"
}

scenario; run_gate check
expect_eq "happy: rc" 0 "${RC}"
expect_eq "happy: decision" "MERGE ${SHA_A}" "$(head -1 <<< "${OUT}")"
grep -q 'issues/comments/22' "${TMP}/log" && ok || bad "happy: should read the LATEST marked comment (22)"
grep -q 'check_name=CI&app_id=15368' "${TMP}/log" && ok || bad "happy: check-runs must be pinned to the required app"

# An unpinned required check (empty app) is still checked, via check-runs then
# commit statuses, and a context with spaces stays whole.
scenario; export FAKE_REQUIRED='|CI' FAKE_CI=failure; run_gate check
expect_eq "unpinned required check, red: rc" 1 "${RC}"
scenario; export FAKE_REQUIRED='|CI' FAKE_CI="" FAKE_STATUS=success; run_gate check
expect_eq "unpinned check satisfied by a commit status: rc" 0 "${RC}"
scenario; export FAKE_REQUIRED='|CI' FAKE_CI="" FAKE_STATUS=failure; run_gate check
expect_eq "unpinned check, red commit status: rc" 1 "${RC}"
scenario; export FAKE_REQUIRED='15368|Scala Build & Test'; run_gate check
grep -q 'check_name=Scala%20Build%20&%20Test&app_id=15368' "${TMP}/log" && ok || bad "multi-word context: $(grep check-runs "${TMP}/log")"

# The repo is public: a marked comment from an outsider neither approves nor voids.
scenario; FAKE_COMMENTS=$'11 MEMBER\n22 NONE'; run_gate check
expect_eq "outsider comment after a real APPROVE: rc" 0 "${RC}"
grep -q 'issues/comments/11' "${TMP}/log" && ok || bad "outsider: should read the trusted comment (11)"
scenario; FAKE_COMMENTS="22 CONTRIBUTOR"; run_gate check
expect_eq "forged APPROVE from a non-member: rc" 1 "${RC}"
grep -q 'issues/comments/22' "${TMP}/log" && bad "forged: must not even read the untrusted comment" || ok

# A gh failure is exit 2, never a decision.
for call in "--json state," "issues/123/comments" "required_status_checks" "check-runs" "pulls/123/files"; do
  scenario; export FAKE_FAIL="${call}"; run_gate check
  expect_eq "gh failure on '${call}': rc" 2 "${RC}"
done

scenario; body "${SHA_B}" "VERDICT: APPROVE @ ${SHA_B}" > "${TMP}/body"; run_gate check
expect_eq "stale approve (#2829): rc" 1 "${RC}"

scenario; FAKE_CI="success failure"; run_gate check
expect_eq "CI red: rc" 1 "${RC}"
scenario; FAKE_CI=""; run_gate check
expect_eq "CI not reported: rc" 1 "${RC}"
scenario; FAKE_CI="in_progress"; run_gate check
expect_eq "CI pending: rc" 1 "${RC}"

scenario; FAKE_COMMENTS=""; run_gate check
expect_eq "no review: rc" 1 "${RC}"
scenario; export FAKE_DRAFT=true; run_gate check
expect_eq "draft: rc" 1 "${RC}"
scenario; export FAKE_MERGEABLE=CONFLICTING; run_gate check
expect_eq "conflicting: rc" 1 "${RC}"

scenario; FAKE_FILES="api/src/Main.scala api/resources/db/migration/V91__x.sql"; run_gate check
expect_eq "migration: rc" 3 "${RC}"
expect_eq "migration: decision" "OPERATOR ${SHA_A}" "$(head -1 <<< "${OUT}")"
scenario; FAKE_FILES="render.yaml"; FAKE_CI=failure; run_gate check
expect_eq "excluded but red: NOT-READY wins" 1 "${RC}"
# Moving a migration out of the migration dir is still a migration PR.
scenario; FAKE_FILES="api/resources/moved.sql=api/resources/db/migration/V91__x.sql"; run_gate check
expect_eq "rename away from a migration: rc" 3 "${RC}"
# A file list shorter than changedFiles (files API caps at 3000) goes to the operator.
scenario; export FAKE_CHANGED=3001; run_gate check
expect_eq "truncated file list: rc" 3 "${RC}"

# enqueue: happy path passes the approved SHA as the head guard.
scenario; run_gate enqueue
expect_eq "enqueue: rc" 0 "${RC}"
expect_eq "enqueue: output" "ENQUEUED ${SHA_A}" "$(tail -1 <<< "${OUT}")"
grep -qx -- "pr merge 123 --repo wifihaven/wifihaven --squash --match-head-commit ${SHA_A}" "${TMP}/log" \
  && ok || bad "enqueue: expected a head-guarded merge call; log: $(cat "${TMP}/log")"

# enqueue: HEAD moved between check and enqueue -> no merge call at all.
scenario; export FAKE_LATER_HEADS="${SHA_B}"; run_gate enqueue
expect_eq "moved before enqueue: rc" 1 "${RC}"
grep -q '^pr merge' "${TMP}/log" && bad "moved before enqueue: must not call gh pr merge" || ok

# enqueue: HEAD moved during the call -> dequeue and confirm.
scenario; export FAKE_LATER_HEADS="${SHA_A} ${SHA_B}"; run_gate enqueue
expect_eq "moved during enqueue: rc" 1 "${RC}"
grep -q 'dequeuePullRequest' "${TMP}/log" && ok || bad "moved during enqueue: must dequeue"
# ...and armed rather than queued -> disable auto-merge.
scenario; export FAKE_LATER_HEADS="${SHA_A} ${SHA_B}" FAKE_QUEUE_BEFORE="false true"; run_gate enqueue
expect_eq "moved while armed: rc" 1 "${RC}"
grep -q -- '--disable-auto' "${TMP}/log" && ok || bad "moved while armed: must disable auto-merge"
# ...and the disarm does not stick -> loud exit 2, never "disarmed".
scenario; export FAKE_LATER_HEADS="${SHA_A} ${SHA_B}" FAKE_QUEUE_AFTER="true false"; run_gate enqueue
expect_eq "disarm failed: rc" 2 "${RC}"
grep -q 'could NOT be confirmed' <<< "${OUT}" && ok || bad "disarm failed: must say so; got ${OUT}"
scenario; export FAKE_LATER_HEADS="${SHA_A} ${SHA_B}" FAKE_QUEUE_BEFORE="false true" FAKE_QUEUE_AFTER="false true"; run_gate enqueue
expect_eq "still armed after disarm: rc" 2 "${RC}"

# enqueue: the merge call errors -> not retried, and the head is still re-checked.
scenario; export FAKE_MERGE_RC=1 FAKE_LATER_HEADS="${SHA_A} ${SHA_B}"; run_gate enqueue
expect_eq "merge errored and HEAD moved: rc" 1 "${RC}"
expect_eq "merge errored: called once" 1 "$(grep -c -- '--match-head-commit' "${TMP}/log")"
grep -q 'dequeuePullRequest' "${TMP}/log" && ok || bad "merge errored and HEAD moved: must still dequeue"
scenario; export FAKE_MERGE_RC=1; run_gate enqueue
expect_eq "merge errored, HEAD unchanged: rc" 2 "${RC}"

# enqueue never runs for an excluded class.
scenario; FAKE_FILES="infra/cloudflare/main.tf"; run_gate enqueue
expect_eq "enqueue excluded: rc" 3 "${RC}"
grep -q '^pr merge' "${TMP}/log" && bad "enqueue excluded: must not call gh pr merge" || ok

echo "pr-merge-gate: ${pass} passed, ${fail} failed"
[[ ${fail} -eq 0 ]]
