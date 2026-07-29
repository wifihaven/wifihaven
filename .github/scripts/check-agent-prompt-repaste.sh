#!/usr/bin/env bash
# Guardrail (#2469): a Claude Code Cloud ROUTINE prompt is web-UI-only.
#
# `deploy/support-agent/agent.yaml` and `deploy/press-agent/agent.yaml` are the
# source of truth for TWO transports. The Managed Agents copy re-applies itself
# on every main-merge (.github/workflows/master-{support,press}-agent.yml), but
# the Claude Code Cloud ROUTINE copy does NOT: routine CRUD lives only in the
# web UI at https://claude.ai/code/routines. So merging a PR that edits the
# `system:` block leaves the live routine running the OLD prompt, and a stale
# routine reports a GREEN run while behaving from that old prompt. That has
# already bitten twice (#2419/#2425 consent, #2430/#2441 thread history) plus
# the original `<routine-fire-payload>` opt-in.
#
# This check does two things on a `system:`-block change:
#
#   1. REMINDS (warning, exit 0) that the routine prompt must be re-pasted after
#      merge, or the change is inert on the live path.
#   2. REJECTS (exit 1) a `system:`-block change that did NOT bump the
#      PROMPT_VERSION marker — or that dropped the marker altogether. The marker
#      is what the running agent echoes back on its reply callback
#      (`promptVersion`), and what the API compares against the compiled
#      `AgentPromptVersion.Channel.*.expected`. An un-bumped marker makes the
#      drift detector blind: the changed prompt and the stale live routine
#      report the SAME version, so a routine nobody re-pasted still reads
#      `state="current"`. Deleting the marker is the dark-by-default version of
#      the same bug.
#
# Changes OUTSIDE the `system:` block (a header comment, the `model:` line, the
# tool list) are re-applied automatically by the Managed Agents workflow and are
# never pasted into a routine, so they need neither a reminder nor a bump.
#
# Diff semantics: three-dot `BASE...HEAD` — i.e. HEAD against the MERGE BASE,
# never against the base-branch tip, per docs/process/branch-diff-checks.md. This
# comparison is a file-content one rather than a `git diff`, so the merge base is
# resolved explicitly below. Two-dot here would both over-report (a branch merely
# BEHIND main on an agent.yaml would warn) and — worse — under-gate: if main
# bumped the marker after we diverged, a branch-local un-bumped prompt change
# would compare base=v2 vs head=v1, look "bumped", and sail through.
set -euo pipefail

BASE="${1:?usage: check-agent-prompt-repaste.sh <base-ref>}"

# Three-dot semantics: compare against the merge base, not the base tip. Fall
# back to BASE itself if the two commits share no history (a synthetic ref).
MERGE_BASE="$(git merge-base "${BASE}" HEAD 2>/dev/null || echo "${BASE}")"

# Every cloud-agent prompt file, glob-ordered so output is deterministic. (A
# plain glob loop, not `mapfile` — this also runs under macOS's bash 3.2 via the
# *.test.sh harness.)
AGENT_YAMLS=()
for f in deploy/*-agent/agent.yaml; do
  [[ -f "${f}" ]] && AGENT_YAMLS+=("${f}")
done
if [[ ${#AGENT_YAMLS[@]} -eq 0 ]]; then
  echo "No routine prompt change (no deploy/*-agent/agent.yaml in this tree)."
  exit 0
fi

# Extract the block-literal `system:` prompt: everything from the `system:` line
# up to the next top-level key (or EOF). Reading the BLOCK — not the whole file
# — is what lets a comment/model change pass without a bump.
extract_system() {
  awk '
    /^system:/            { in_block = 1; next }
    in_block && /^[^[:space:]]/ { in_block = 0 }
    in_block              { print }
  '
}

# The version marker inside the prompt. Same syntax the API-side pin greps for
# (api/test/src/feature/AgentPromptVersionSpec.scala) — one syntax, two readers.
extract_version() {
  sed -n 's/^[[:space:]]*PROMPT_VERSION:[[:space:]]*\([^[:space:]][^[:space:]]*\)[[:space:]]*$/\1/p' | head -n1
}

# `git show` on a path that did not exist at the merge base is an error, not an
# empty file — a NEW agent.yaml must not blow up the check.
at_base() {
  git show "${MERGE_BASE}:${1}" 2>/dev/null || true
}

changed=()
violations=0

for f in "${AGENT_YAMLS[@]}"; do
  base_system="$(at_base "${f}" | extract_system)"
  head_system="$(extract_system <"${f}")"

  [[ "${base_system}" == "${head_system}" ]] && continue
  changed+=("${f}")

  base_version="$(printf '%s\n' "${base_system}" | extract_version)"
  head_version="$(printf '%s\n' "${head_system}" | extract_version)"

  if [[ -z "${head_version}" ]]; then
    echo "ERROR: ${f} — the system: prompt changed but carries NO PROMPT_VERSION marker (#2469)."
    echo "  The marker is what the live agent echoes back on its reply callback, and the only"
    echo "  signal that the running routine matches this repo. Add a line inside the system:"
    echo "  block, e.g.  PROMPT_VERSION: support-$(date -u +%Y-%m-%d).1"
    echo
    violations=$((violations + 1))
  elif [[ "${base_version}" == "${head_version}" ]]; then
    echo "ERROR: ${f} — the system: prompt changed but PROMPT_VERSION is still '${head_version}' (#2469)."
    echo "  Bump it (e.g. bump the trailing serial, or use today's date) and keep the SAME value in"
    echo "  the compiled mirror api/src/support/AgentPromptVersion.scala — AgentPromptVersionSpec"
    echo "  pins the two together. Without a bump the drift detector is blind: a routine nobody"
    echo "  re-pasted keeps reporting the new version and reads state=\"current\"."
    echo
    violations=$((violations + 1))
  fi
done

if [[ ${#changed[@]} -eq 0 ]]; then
  echo "No routine prompt change (no deploy/*-agent/agent.yaml system: block touched)."
  exit 0
fi

if [[ ${violations} -gt 0 ]]; then
  echo "Found ${violations} un-versioned routine prompt change(s). See the message(s) above."
  exit 1
fi

# The reminder. Non-blocking on purpose: the re-paste happens AFTER merge, so
# failing the PR could not be satisfied from inside the PR. The durable
# detection is the prompt-version echo (`agent_prompt_version_total{channel,
# state}` — support/press Grafana dashboards); this is the human nudge that
# makes it a same-day fix rather than a weeks-later mystery.
remind() {
  echo "The Claude Code Cloud ROUTINE prompt is WEB-UI-ONLY (#2469)."
  echo "These prompts changed in this PR:"
  for f in "${changed[@]}"; do echo "  - ${f}"; done
  echo
  echo "AFTER MERGE, re-paste each changed system: block into its routine at"
  echo "https://claude.ai/code/routines (keeping the <routine-fire-payload> opt-in), or the change"
  echo "is INERT on the live path — the routine will keep running the old prompt and still report a"
  echo "green run. See deploy/{support,press}-agent/README.md 'post-merge re-paste'."
  echo
  echo "Until you do, agent_prompt_version_total{state=\"stale\"} will fire on the support/press"
  echo "dashboards. That is the detector working, not a bug."
}

echo "::warning title=Re-paste the Claude Code Cloud routine prompt after merge (#2469)::$(
  for f in "${changed[@]}"; do printf '%s ' "${f}"; done
)changed — the routine prompt is web-UI-only; re-paste it at https://claude.ai/code/routines or the change is inert."

remind

if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  {
    echo "### ⚠️ Post-merge action required (#2469)"
    echo
    echo '```'
    remind
    echo '```'
  } >>"${GITHUB_STEP_SUMMARY}"
fi

exit 0
