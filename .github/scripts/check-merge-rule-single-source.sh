#!/usr/bin/env bash
# Guardrail (#2869): the PR merge rule — who may merge, when, and how — is
# defined in exactly ONE place: docs/pr-review-checklist.md#monitor-to-merged.
# Its mechanical check is scripts/pr-merge-gate.sh. Every other doc, skill,
# command, or CLAUDE.md/AGENTS.md entry LINKS to that section; it must not
# restate the rule. The old rule ("never `gh pr merge`, operator's call") was
# restated in six places and they all had to be found by hand when it changed.
#
# This check scans every Markdown file and everything under .claude/ (skills,
# commands) for the vocabulary only a restatement of the rule uses: the merge
# commands and flags, merge-when-ready / auto-merge, the "non-approving" review
# posture, and "never/do not merge". The canonical doc is the one allowed home.
#
# It is a text scan, so a paraphrase with none of these words gets past it;
# the independent review is the backstop for that. To point at the rule, write
# "merge per [the merge rule](docs/pr-review-checklist.md#monitor-to-merged)".
set -euo pipefail

ROOT="${1:-.}"
CANONICAL="docs/pr-review-checklist.md"
PATTERN='gh pr merge|--auto([^-a-z]|$)|auto-?merge|merge-when-ready|non-approving|operator merges|never merge|do (\*\*)?not(\*\*)? merge|don.t merge'

violations=0
while IFS= read -r -d '' f; do
  rel="${f#"${ROOT}"/}"
  [[ "${rel}" == "${CANONICAL}" ]] && continue
  hits="$(grep -n -i -E -- "${PATTERN}" "${f}" || true)"
  [[ -z "${hits}" ]] && continue
  if [[ ${violations} -eq 0 ]]; then
    echo "ERROR: the PR merge rule is restated outside ${CANONICAL}#monitor-to-merged (#2869)."
    echo "Link to that section instead of restating it, so the rule has one definition."
    echo
  fi
  while IFS= read -r h; do
    echo "  ${rel}:${h}"
    violations=$((violations + 1))
  done <<< "${hits}"
done < <(
  find "${ROOT}" \
    \( -path "${ROOT}/.git" -o -path "${ROOT}/.claude/worktrees" -o -name node_modules -o -path "${ROOT}/out" \) -prune \
    -o -type f \( -name '*.md' -o -path "${ROOT}/.claude/*" \) -print0
)

if [[ ${violations} -gt 0 ]]; then
  exit 1
fi
echo "OK: the merge rule is stated once, in ${CANONICAL}#monitor-to-merged."
