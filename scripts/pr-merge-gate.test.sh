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
case "${args}" in
  "pr view"*"--json state,"*)   echo "${FAKE_STATE:-OPEN} ${FAKE_DRAFT:-false} ${FAKE_HEAD} main ${FAKE_MERGEABLE:-MERGEABLE}" ;;
  "pr view"*"--json headRefOid"*)
    # Successive head reads after the check: FAKE_LATER_HEADS (space-separated), else FAKE_HEAD.
    n="$(grep -c -- '--json headRefOid ' "${FAKE_LOG}")"
    read -r -a later <<< "${FAKE_LATER_HEADS:-}"
    echo "${later[$((n - 1))]:-${FAKE_HEAD}}" ;;
  "pr merge"*) : ;;
  "api repos/"*"/issues/"*"/comments --paginate"*) printf '%s\n' ${FAKE_COMMENT_IDS:-} ;;
  "api repos/"*"/issues/comments/"*) cat "${FAKE_BODY_FILE}" ;;
  "api repos/"*"/protection/required_status_checks"*) echo CI ;;
  "api repos/"*"/check-runs"*) printf '%s\n' ${FAKE_CI-success} ;;
  "api repos/"*"/status "*|"api repos/"*"/status") : ;;
  "api repos/"*"/pulls/"*"/files"*) printf '%s\n' ${FAKE_FILES:-api/src/Main.scala} ;;
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
  export FAKE_HEAD="${SHA_A}" FAKE_COMMENT_IDS="11 22" FAKE_CI=success FAKE_FILES="api/src/Main.scala"
  unset FAKE_STATE FAKE_DRAFT FAKE_MERGEABLE FAKE_LATER_HEADS
  body "${SHA_A}" "VERDICT: APPROVE @ ${SHA_A}" > "${TMP}/body"
}

scenario; run_gate check
expect_eq "happy: rc" 0 "${RC}"
expect_eq "happy: decision" "MERGE ${SHA_A}" "$(head -1 <<< "${OUT}")"
grep -q 'issues/comments/22' "${TMP}/log" && ok || bad "happy: should read the LATEST marked comment (22)"

scenario; body "${SHA_B}" "VERDICT: APPROVE @ ${SHA_B}" > "${TMP}/body"; run_gate check
expect_eq "stale approve (#2829): rc" 1 "${RC}"

scenario; FAKE_CI="success failure"; run_gate check
expect_eq "CI red: rc" 1 "${RC}"
scenario; FAKE_CI=""; run_gate check
expect_eq "CI not reported: rc" 1 "${RC}"
scenario; FAKE_CI="in_progress"; run_gate check
expect_eq "CI pending: rc" 1 "${RC}"

scenario; FAKE_COMMENT_IDS=""; run_gate check
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

# enqueue: HEAD moved during the call -> disarm.
scenario; export FAKE_LATER_HEADS="${SHA_A} ${SHA_B}"; run_gate enqueue
expect_eq "moved during enqueue: rc" 1 "${RC}"
grep -q -- '--disable-auto' "${TMP}/log" && ok || bad "moved during enqueue: must disarm"

# enqueue never runs for an excluded class.
scenario; FAKE_FILES="infra/cloudflare/main.tf"; run_gate enqueue
expect_eq "enqueue excluded: rc" 3 "${RC}"
grep -q '^pr merge' "${TMP}/log" && bad "enqueue excluded: must not call gh pr merge" || ok

echo "pr-merge-gate: ${pass} passed, ${fail} failed"
[[ ${fail} -eq 0 ]]
