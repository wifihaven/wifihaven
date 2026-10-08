#!/usr/bin/env bash
# Tests for check-merge-rule-single-source.sh (#2869). Each case builds a
# fixture tree; the guard must pass a tree that only links to the rule and fail
# one that restates it, so the guard is shown able to fail.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
GUARD="${HERE}/check-merge-rule-single-source.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

pass=0
fail=0

fixture() { # name -> fresh tree with the canonical doc and a linking pointer
  local d="${TMP}/$1"
  mkdir -p "${d}/docs" "${d}/.claude/skills/x"
  printf 'Enqueue with `gh pr merge <n> --squash --match-head-commit <sha>`; never arm --auto.\n' > "${d}/docs/pr-review-checklist.md"
  printf 'Merge per [the merge rule](docs/pr-review-checklist.md#monitor-to-merged).\n' > "${d}/AGENTS.md"
  printf 'Monitor through to MERGED per the merge rule.\n' > "${d}/.claude/skills/x/SKILL.md"
  echo "${d}"
}
expect() { # name expected-rc root
  local rc=0
  bash "${GUARD}" "$3" > "${TMP}/out" 2>&1 || rc=$?
  if [[ ${rc} -eq $2 ]]; then
    pass=$((pass + 1))
  else
    fail=$((fail + 1))
    echo "FAIL: $1: expected exit $2, got ${rc}"
    cat "${TMP}/out"
  fi
}

expect "canonical + pointers only" 0 "$(fixture clean)"

d="$(fixture old-rule-in-skill)"
printf 'Do **not** `gh pr merge` / enable auto-merge (operator'"'"'s call).\n' >> "${d}/.claude/skills/x/SKILL.md"
expect "old rule re-pasted into a skill" 1 "${d}"

d="$(fixture non-approving)"
printf 'Review is POSTED as a marked, non-approving PR comment.\n' >> "${d}/AGENTS.md"
expect "non-approving restated in AGENTS.md" 1 "${d}"

d="$(fixture heading)"
printf '## Step 6 — Review + monitor, do NOT merge\n' > "${d}/docs/other.md"
expect "do NOT merge heading" 1 "${d}"

d="$(fixture command)"
mkdir -p "${d}/.claude/commands"
printf 'Then run gh pr merge --auto.\n' > "${d}/.claude/commands/ship.md"
expect "merge command in a .claude command" 1 "${d}"

d="$(fixture link-does-not-launder)"
printf 'Never call `gh pr merge` — see [rule](docs/pr-review-checklist.md#monitor-to-merged).\n' >> "${d}/AGENTS.md"
expect "a link next to a restatement still fails" 1 "${d}"

d="$(fixture unrelated-merge)"
printf 'The override replaces the profile rules entirely (it is not a merge).\n' >> "${d}/AGENTS.md"
expect "unrelated use of 'merge'" 0 "${d}"

d="$(fixture worktrees-ignored)"
mkdir -p "${d}/.claude/worktrees/old/docs"
printf 'never `gh pr merge`\n' > "${d}/.claude/worktrees/old/docs/x.md"
expect "nested worktrees are not scanned" 0 "${d}"

echo "check-merge-rule-single-source: ${pass} passed, ${fail} failed"
[[ ${fail} -eq 0 ]]
