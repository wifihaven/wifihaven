#!/usr/bin/env bash
# Merge gate for author-side sessions (#2869).
#
# The rule itself is defined ONCE, in docs/pr-review-checklist.md#monitor-to-merged.
# This script is the mechanical check of that rule; if the two ever disagree, the
# doc is the definition and this script is the bug.
#
# Usage:
#   pr-merge-gate.sh check   <pr>    decide whether the session may enqueue; never mutates the PR
#   pr-merge-gate.sh enqueue <pr>    run `check`, then enqueue the approved SHA with a head guard
#   pr-merge-gate.sh classify        stdin: changed paths, one per line -> one line per excluded path
#   pr-merge-gate.sh verdict <sha>   stdin: a review comment body -> APPROVE | REQUEST-CHANGES | STALE | NONE
#
# Exit codes:
#   0  MERGE (check) / ENQUEUED (enqueue): the session may merge / has enqueued
#   3  OPERATOR: APPROVE on HEAD + CI green, but the PR is an excluded class; the operator merges
#   1  NOT-READY: no APPROVE on the current HEAD, CI not green, draft, conflicting, not open, ...
#   2  usage error, or gh failed after retries
set -euo pipefail

REPO="${PR_MERGE_GATE_REPO:-wifihaven/wifihaven}"
MARKER='<!-- wifihaven-pr-review reviewed-sha='

die() { echo "pr-merge-gate: $*" >&2; exit 2; }
indent() { local l; while IFS= read -r l; do printf "    %s\n" "${l}"; done; }

# gh occasionally returns a transient HTTP 401 / 5xx. Retry with a short backoff
# before giving up; a persistent failure exits 2 rather than reading as "not ready".
gh_retry() {
  local attempt=1 out
  until out="$(gh "$@")"; do
    [[ ${attempt} -ge 3 ]] && die "gh $* failed after ${attempt} attempts"
    sleep $((attempt * ${PR_MERGE_GATE_BACKOFF_SECS:-5}))
    attempt=$((attempt + 1))
  done
  printf '%s\n' "${out}"
}

# Excluded classes: the PR still needs an APPROVE on HEAD and green CI, but the
# operator merges it. Detected by changed path (including the old path of a
# rename), so the check is mechanical.
classify() {
  local p
  while IFS= read -r p; do
    [[ -z "${p}" ]] && continue
    if [[ "${p}" == api/resources/db/migration/* || "${p}" =~ (^|/)V[0-9]+__[^/]*\.sql$ ]]; then
      echo "migration: ${p}"
      continue
    fi
    case "${p}" in
      render.yaml | infra/cloudflare/* | wrangler*.toml | */wrangler*.toml | docker/entrypoint.sh | .github/workflows/master-*.yml)
        echo "prod-config: ${p}" ;;
      scripts/pr-merge-gate.sh | .claude/commands/pr-review.md | docs/pr-review-checklist.md | .github/scripts/check-merge-rule-single-source.sh)
        echo "merge-policy: ${p}" ;;
    esac
  done
}

# Reads one review comment body on stdin. APPROVE only when the marker AND the
# single verdict line both name <sha>; a verdict for any other SHA is STALE.
verdict() {
  local sha="$1" body marker_sha lines n v_kind v_sha
  [[ "${sha}" =~ ^[0-9a-f]{40}$ ]] || die "verdict: need a full 40-char SHA, got '${sha}'"
  body="$(cat)"
  marker_sha="$(grep -oE 'wifihaven-pr-review reviewed-sha=[0-9a-f]+' <<< "${body}" | head -1 | sed 's/.*=//' || true)"
  lines="$(grep -E '^VERDICT: (APPROVE|REQUEST-CHANGES) @ [0-9a-f]{40}[[:space:]]*$' <<< "${body}" || true)"
  n="$(grep -c . <<< "${lines}" || true)"
  if [[ "${n}" -ne 1 ]]; then
    echo NONE
    return
  fi
  v_kind="$(awk '{print $2}' <<< "${lines}")"
  v_sha="$(awk '{print $4}' <<< "${lines}")"
  if [[ "${v_sha}" != "${sha}" || "${marker_sha}" != "${sha}" ]]; then
    echo STALE
    return
  fi
  echo "${v_kind}"
}

# Prints the decision and the reasons; returns 0 / 3 / 1 as documented above.
# Sets GATE_HEAD to the SHA the decision covers. Each gh read ends in `|| return 2`
# because `enqueue` calls this under `||`, where set -e is suspended.
check() {
  local pr="$1" view state draft head base mergeable
  local -a not_ready=()
  view="$(gh_retry pr view "${pr}" --repo "${REPO}" \
    --json state,isDraft,headRefOid,baseRefName,mergeable \
    --jq '"\(.state) \(.isDraft) \(.headRefOid) \(.baseRefName) \(.mergeable)"')" || return 2
  read -r state draft head base mergeable <<< "${view}"
  GATE_HEAD="${head}"
  [[ "${state}" == OPEN ]] || not_ready+=("PR state is ${state}, not OPEN")
  [[ "${draft}" == false ]] || not_ready+=("PR is a draft")
  [[ "${mergeable}" == MERGEABLE ]] || not_ready+=("mergeable is ${mergeable} (CONFLICTING: rebase; UNKNOWN: retry shortly)")

  # 1. Reviewer verdict, bound to HEAD. Only the LATEST marked comment counts.
  local ids last_id body v
  ids="$(gh_retry api "repos/${REPO}/issues/${pr}/comments" --paginate \
    --jq ".[] | select(.body | contains(\"${MARKER}\")) | .id")" || return 2
  last_id="$(grep -E '^[0-9]+$' <<< "${ids}" | tail -n 1 || true)"
  if [[ -z "${last_id}" ]]; then
    not_ready+=("no /pr-review comment yet")
  else
    body="$(gh_retry api "repos/${REPO}/issues/comments/${last_id}" --jq .body)" || return 2
    v="$(verdict "${head}" <<< "${body}")"
    case "${v}" in
      APPROVE) ;;
      REQUEST-CHANGES) not_ready+=("latest review is REQUEST-CHANGES on ${head}") ;;
      STALE) not_ready+=("latest review covers a different SHA than HEAD ${head}: re-run /pr-review") ;;
      *) not_ready+=("latest review has no single 'VERDICT: <APPROVE|REQUEST-CHANGES> @ <sha>' line: re-run /pr-review") ;;
    esac
  fi

  # 2. Every required check green on HEAD. The required set is read from branch
  #    protection, not hardcoded here.
  local contexts ctx results
  contexts="$(gh_retry api "repos/${REPO}/branches/${base}/protection/required_status_checks" --jq '.contexts[]')" || return 2
  [[ -n "${contexts}" ]] || not_ready+=("could not read required checks for ${base}")
  while IFS= read -r ctx; do
    [[ -z "${ctx}" ]] && continue
    results="$(gh_retry api "repos/${REPO}/commits/${head}/check-runs?check_name=${ctx// /%20}" \
      --jq '.check_runs[] | (.conclusion // .status)')" || return 2
    if [[ -z "$(tr -d '[:space:]' <<< "${results}")" ]]; then
      results="$(gh_retry api "repos/${REPO}/commits/${head}/status" \
        --jq ".statuses[] | select(.context == \"${ctx}\") | .state")" || return 2
    fi
    if [[ -z "$(tr -d '[:space:]' <<< "${results}")" ]]; then
      not_ready+=("required check '${ctx}' has not reported on ${head}")
    elif grep -qvE '^(success)?$' <<< "${results}"; then
      not_ready+=("required check '${ctx}' is not green on ${head}: $(tr '\n' ' ' <<< "${results}")")
    fi
  done <<< "${contexts}"

  # 3. Excluded classes, by changed path.
  local files excluded
  files="$(gh_retry api "repos/${REPO}/pulls/${pr}/files" --paginate \
    --jq '.[] | .filename, (.previous_filename // empty)')" || return 2
  excluded="$(classify <<< "${files}")"

  if [[ ${#not_ready[@]} -gt 0 ]]; then
    echo "NOT-READY ${head}"
    printf '  - %s\n' "${not_ready[@]}"
    [[ -n "${excluded}" ]] && { echo "  (excluded class, so the operator merges once ready:)"; indent <<< "${excluded}"; }
    return 1
  fi
  if [[ -n "${excluded}" ]]; then
    echo "OPERATOR ${head}"
    echo "  APPROVE on HEAD and CI green, but this PR is an excluded class. The operator merges it:"
    indent <<< "${excluded}"
    return 3
  fi
  echo "MERGE ${head}"
}

enqueue() {
  local pr="$1" rc=0 approved now
  check "${pr}" || rc=$?
  [[ ${rc} -eq 0 ]] || return "${rc}"
  approved="${GATE_HEAD}"

  # Head guard, part 1: re-read HEAD immediately before enqueueing.
  now="$(gh_retry pr view "${pr}" --repo "${REPO}" --json headRefOid --jq .headRefOid)" || return 2
  if [[ "${now}" != "${approved}" ]]; then
    echo "ABORT: HEAD moved from ${approved} to ${now} after the check; nothing enqueued. Re-run /pr-review."
    return 1
  fi

  # Head guard, part 2: --match-head-commit is sent as expectedHeadOid on the
  # enqueue (gh uses enablePullRequestAutoMerge when a merge queue is required).
  gh_retry pr merge "${pr}" --repo "${REPO}" --squash --match-head-commit "${approved}" > /dev/null || return 2

  # Head guard, part 3: if HEAD moved during the call, disarm rather than trust
  # server-side enforcement of expectedHeadOid.
  now="$(gh_retry pr view "${pr}" --repo "${REPO}" --json headRefOid --jq .headRefOid)" || return 2
  if [[ "${now}" != "${approved}" ]]; then
    gh_retry pr merge "${pr}" --repo "${REPO}" --disable-auto > /dev/null || true
    echo "ABORT: HEAD moved to ${now} during enqueue of ${approved}; disarmed. Re-run /pr-review."
    return 1
  fi
  echo "ENQUEUED ${approved}"
}

cmd="${1:-}"
case "${cmd}" in
  check)    [[ $# -eq 2 ]] || die "usage: $0 check <pr>";    check "$2" ;;
  enqueue)  [[ $# -eq 2 ]] || die "usage: $0 enqueue <pr>";  enqueue "$2" ;;
  classify) [[ $# -eq 1 ]] || die "usage: $0 classify < paths"; classify ;;
  verdict)  [[ $# -eq 2 ]] || die "usage: $0 verdict <sha> < body"; verdict "$2" ;;
  *) die "usage: $0 {check|enqueue} <pr> | classify | verdict <sha>" ;;
esac
