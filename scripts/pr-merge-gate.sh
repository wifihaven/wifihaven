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
#   pr-merge-gate.sh disarm  <pr>    take the PR out of the merge queue and off auto-merge, and confirm
#   pr-merge-gate.sh classify        stdin: changed paths, one per line -> one line per excluded path
#   pr-merge-gate.sh verdict <sha>   stdin: a review comment body -> APPROVE | REQUEST-CHANGES | STALE | NONE
#
# Exit codes:
#   0  MERGE (check) / ENQUEUED (enqueue): the session may merge / has enqueued
#   3  OPERATOR: APPROVE on HEAD + CI green, but the PR is an excluded class; the operator merges
#   1  NOT-READY: no APPROVE on the current HEAD, CI not green, draft, conflicting, not open, ...
#      (disarm: still queued or armed afterwards)
#   2  usage error, or gh failed after retries
set -euo pipefail

REPO="${PR_MERGE_GATE_REPO:-wifihaven/wifihaven}"
MARKER='<!-- wifihaven-pr-review reviewed-sha='
# The repo is public: anyone can post a comment carrying the marker and an
# APPROVE line. Only comments from these author associations count as reviews.
TRUSTED_ASSOCIATIONS='OWNER|MEMBER|COLLABORATOR'

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
  local pr="$1" view state draft head base mergeable changed
  local -a not_ready=()
  view="$(gh_retry pr view "${pr}" --repo "${REPO}" \
    --json state,isDraft,headRefOid,baseRefName,mergeable,changedFiles \
    --jq '"\(.state) \(.isDraft) \(.headRefOid) \(.baseRefName) \(.mergeable) \(.changedFiles)"')" || return 2
  read -r state draft head base mergeable changed <<< "${view}"
  GATE_HEAD="${head}"
  [[ "${state}" == OPEN ]] || not_ready+=("PR state is ${state}, not OPEN")
  [[ "${draft}" == false ]] || not_ready+=("PR is a draft")
  [[ "${mergeable}" == MERGEABLE ]] || not_ready+=("mergeable is ${mergeable} (CONFLICTING: rebase; UNKNOWN: retry shortly)")

  # 1. Reviewer verdict, bound to HEAD. Only the LATEST marked comment from a
  #    trusted author counts; marked comments from anyone else are ignored, so
  #    they can neither approve nor void a real review.
  local ids last_id body v
  ids="$(gh_retry api "repos/${REPO}/issues/${pr}/comments" --paginate \
    --jq ".[] | select(.body | contains(\"${MARKER}\")) | \"\(.id) \(.author_association)\"")" || return 2
  last_id="$(grep -E "^[0-9]+ (${TRUSTED_ASSOCIATIONS})$" <<< "${ids}" | tail -n 1 | cut -d' ' -f1 || true)"
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

  # 2. Every required check green on HEAD. The required set, and the app each
  #    check is pinned to, are read from branch protection, not hardcoded here.
  local contexts app ctx results
  contexts="$(gh_retry api "repos/${REPO}/branches/${base}/protection/required_status_checks" \
    --jq '.checks[] | "\(.app_id // "")|\(.context)"')" || return 2
  [[ -n "${contexts}" ]] || not_ready+=("could not read required checks for ${base}")
  # Split on '|', not whitespace: `read` strips leading IFS whitespace (tabs
  # included), which would turn an unpinned check's empty app field into the
  # context. A context may contain spaces; ctx takes the rest of the line.
  while IFS='|' read -r app ctx; do
    [[ -z "${ctx}" ]] && continue
    # -X GET -f lets gh URL-encode the name (a context may contain '&', '#', ...).
    results="$(gh_retry api "repos/${REPO}/commits/${head}/check-runs" -X GET -f check_name="${ctx}" \
      ${app:+-f app_id="${app}"} --jq '.check_runs[] | (.conclusion // .status)')" || return 2
    # A commit status has no app, so it can only satisfy a check not pinned to one.
    if [[ -z "${app}" && -z "$(tr -d '[:space:]' <<< "${results}")" ]]; then
      results="$(gh_retry api "repos/${REPO}/commits/${head}/status" \
        --jq ".statuses[] | select(.context == \"${ctx}\") | .state")" || return 2
    fi
    if [[ -z "$(tr -d '[:space:]' <<< "${results}")" ]]; then
      not_ready+=("required check '${ctx}' has not reported on ${head}")
    elif grep -qvE '^(success)?$' <<< "${results}"; then
      not_ready+=("required check '${ctx}' is not green on ${head}: $(tr '\n' ' ' <<< "${results}")")
    fi
  done <<< "${contexts}"

  # 3. Excluded classes, by changed path (and the old path of a rename). The
  #    files API stops at 3000 entries; a list shorter than changedFiles cannot
  #    be classified, so it goes to the operator.
  local files listed excluded
  files="$(gh_retry api "repos/${REPO}/pulls/${pr}/files" --paginate \
    --jq '.[] | "\(.filename)\t\(.previous_filename // "")"')" || return 2
  listed="$(grep -c . <<< "${files}" || true)"
  excluded="$(tr '\t' '\n' <<< "${files}" | classify)"
  if [[ "${changed}" =~ ^[0-9]+$ && "${listed}" -lt "${changed}" ]]; then
    excluded+="${excluded:+$'\n'}unclassifiable: the files API listed ${listed} of ${changed} changed files"
  fi

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

# Prints "<node-id> <isInMergeQueue> <auto-merge armed>". Not available from
# `gh pr view --json`, so read it through GraphQL.
queue_state() {
  gh_retry api graphql -F n="$1" -f o="${REPO%%/*}" -f r="${REPO##*/}" \
    -f query='query($o:String!,$r:String!,$n:Int!){repository(owner:$o,name:$r){pullRequest(number:$n){id isInMergeQueue autoMergeRequest{enabledAt}}}}' \
    --jq '.data.repository.pullRequest | "\(.id) \(.isInMergeQueue) \(.autoMergeRequest != null)"'
}

# Takes the PR out of the queue and off auto-merge, then confirms it. Returns 0
# only when it is neither queued nor armed afterwards.
disarm() {
  local pr="$1" st id queued armed
  st="$(queue_state "${pr}")" || return 2
  read -r id queued armed <<< "${st}"
  # Mutation errors go to stderr (for whoever has to disarm by hand); success is
  # judged only by the re-read below.
  if [[ "${armed}" == true ]]; then
    gh pr merge "${pr}" --repo "${REPO}" --disable-auto > /dev/null || true
  fi
  if [[ "${queued}" == true ]]; then
    gh api graphql -f id="${id}" \
      -f query='mutation($id:ID!){dequeuePullRequest(input:{id:$id}){clientMutationId}}' > /dev/null || true
  fi
  st="$(queue_state "${pr}")" || return 2
  read -r id queued armed <<< "${st}"
  [[ "${queued}" == false && "${armed}" == false ]]
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
  # Called once, not through gh_retry: an enqueue that succeeded server-side and
  # then errored must not be repeated, and must still reach part 3.
  local merge_rc=0
  gh pr merge "${pr}" --repo "${REPO}" --squash --match-head-commit "${approved}" > /dev/null || merge_rc=$?

  # Head guard, part 3: whatever the merge call returned, if HEAD moved, disarm
  # rather than trust server-side enforcement of expectedHeadOid.
  if ! now="$(gh_retry pr view "${pr}" --repo "${REPO}" --json headRefOid --jq .headRefOid)"; then
    echo "ERROR: could not re-read HEAD after enqueueing ${approved}. Check PR ${pr} by hand and disarm it if HEAD moved."
    return 2
  fi
  if [[ "${now}" != "${approved}" ]]; then
    if disarm "${pr}"; then
      echo "ABORT: HEAD moved to ${now} during enqueue of ${approved}; dequeued and disarmed. Re-run /pr-review."
      return 1
    fi
    echo "ERROR: HEAD moved to ${now} during enqueue of ${approved} and the PR could NOT be confirmed dequeued/disarmed. Disarm PR ${pr} by hand now."
    return 2
  fi
  if [[ ${merge_rc} -ne 0 ]]; then
    echo "ERROR: gh pr merge exited ${merge_rc}; HEAD is still ${approved}, so anything it did enqueue is the approved SHA. Check the queue state and retry."
    return 2
  fi
  echo "ENQUEUED ${approved}"
}

cmd="${1:-}"
case "${cmd}" in
  check)    [[ $# -eq 2 ]] || die "usage: $0 check <pr>";    check "$2" ;;
  enqueue)  [[ $# -eq 2 ]] || die "usage: $0 enqueue <pr>";  enqueue "$2" ;;
  disarm)   [[ $# -eq 2 ]] || die "usage: $0 disarm <pr>"
            rc=0; disarm "$2" || rc=$?
            case "${rc}" in
              0) echo "DISARMED" ;;
              1) echo "STILL QUEUED OR ARMED"; exit 1 ;;
              *) echo "UNKNOWN: could not read the queue state"; exit 2 ;;
            esac ;;
  classify) [[ $# -eq 1 ]] || die "usage: $0 classify < paths"; classify ;;
  verdict)  [[ $# -eq 2 ]] || die "usage: $0 verdict <sha> < body"; verdict "$2" ;;
  *) die "usage: $0 {check|enqueue|disarm} <pr> | classify | verdict <sha>" ;;
esac
