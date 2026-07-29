#!/usr/bin/env bash
# Tests for check-agent-prompt-repaste.sh (#2469).
#
# Rule under test: a Claude Code Cloud routine prompt is web-UI-only, so a PR
# that changes the `system:` block of deploy/*-agent/agent.yaml must (a) be
# flagged with a post-merge re-paste reminder and (b) bump the PROMPT_VERSION
# marker, without which the drift detector cannot tell the live routine apart.
# A change OUTSIDE the system block (a header comment, the model line) needs
# neither.
set -euo pipefail

SCRIPT="$(cd "$(dirname "$0")" && pwd)/check-agent-prompt-repaste.sh"
[[ -x "${SCRIPT}" ]] || chmod +x "${SCRIPT}"

tmp=""
# Per-run capture files — a fixed /tmp path would race a concurrent run on the
# same host (this suite is auto-discovered by CI's shell-tests job).
outdir="$(mktemp -d)"
cleanup() {
  [[ -n "${tmp}" ]] && rm -rf "${tmp}"
  rm -rf "${outdir}"
  return 0
}
trap cleanup EXIT

# A minimal agent.yaml with the same shape as the real ones: comments, scalar
# keys, then a block-literal `system:` prompt carrying the version marker.
write_yaml() {
  local path="$1" version="$2" body="$3"
  mkdir -p "$(dirname "${path}")"
  cat >"${path}" <<EOF
# a header comment
name: wifihaven-test-responder
model: claude-sonnet-5
system: |
  You are a test agent.

  PROMPT_VERSION: ${version}

  ${body}
EOF
}

setup() {
  tmp="$(mktemp -d)"
  cd "${tmp}"
  git init -q
  git config user.email t@t.io
  git config user.name tester
  git config commit.gpgsign false
  write_yaml deploy/support-agent/agent.yaml "support-2026-01-01.1" "Do the support thing."
  write_yaml deploy/press-agent/agent.yaml "press-2026-01-01.1" "Do the press thing."
  echo "unrelated" > README.md
  git add . && git commit -q -m base
  BASE="$(git rev-parse HEAD)"
  # Whatever `git init` called the first branch (main/master) — the stale-branch
  # cases below advance it the way `origin/main` advances under a real PR.
  BASE_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
  git checkout -q -b feature
}

run_case() {
  local name="$1"; shift
  local want="$1"; shift
  local want_out="$1"; shift
  setup
  "$@"
  set +e
  "${SCRIPT}" "${BASE}" >"${outdir}/apr.out" 2>"${outdir}/apr.err"
  local rc=$?
  set -e
  local out; out="$(cat "${outdir}/apr.out" "${outdir}/apr.err")"
  rm -rf "${tmp}"; tmp=""
  local ok=1
  [[ "${want}" == "pass" && ${rc} -ne 0 ]] && ok=0
  [[ "${want}" == "fail" && ${rc} -eq 0 ]] && ok=0
  if [[ -n "${want_out}" ]] && ! grep -qi -- "${want_out}" <<<"${out}"; then ok=0; fi
  if [[ ${ok} -eq 1 ]]; then
    echo "PASS: ${name}"
  else
    echo "FAIL: ${name} (rc=${rc}, wanted ${want} + output matching '${want_out}')" >&2
    echo "--- output ---" >&2; echo "${out}" >&2
    exit 1
  fi
}

# ── No prompt change ───────────────────────────────────────────────────

case_untouched() {
  echo "more" >> README.md
  git add . && git commit -q -m unrelated
}

case_outside_system_block() {
  # The model line is applied by the Managed Agents workflow, not pasted into
  # the routine — no re-paste, no version bump.
  sed -i.bak 's/^model: .*/model: claude-opus-4-8/' deploy/support-agent/agent.yaml
  rm -f deploy/support-agent/agent.yaml.bak
  git add . && git commit -q -m modelbump
}

# ── Prompt changed, version bumped ─────────────────────────────────────

case_prompt_and_version_bumped() {
  write_yaml deploy/support-agent/agent.yaml "support-2026-02-02.1" "Do the support thing DIFFERENTLY."
  git add . && git commit -q -m bumped
}

case_both_channels_bumped() {
  write_yaml deploy/support-agent/agent.yaml "support-2026-02-02.1" "New support behaviour."
  write_yaml deploy/press-agent/agent.yaml "press-2026-02-02.1" "New press behaviour."
  git add . && git commit -q -m bothbumped
}

# ── Prompt changed, version NOT bumped ─────────────────────────────────

case_prompt_changed_version_stale() {
  write_yaml deploy/support-agent/agent.yaml "support-2026-01-01.1" "Do the support thing DIFFERENTLY."
  git add . && git commit -q -m nobump
}

case_press_changed_version_stale() {
  write_yaml deploy/press-agent/agent.yaml "press-2026-01-01.1" "Say something new to journalists."
  git add . && git commit -q -m nobumppress
}

case_marker_deleted() {
  # Removing the marker outright would silently disable the drift detector.
  cat >deploy/support-agent/agent.yaml <<'EOF'
# a header comment
name: wifihaven-test-responder
model: claude-sonnet-5
system: |
  You are a test agent with no version marker at all.
EOF
  git add . && git commit -q -m nomarker
}

# ── Stale branch: BASE has advanced on the same file ───────────────────
# The three-dot / merge-base pins. Two-dot semantics (comparing against the base
# TIP) breaks both ways here, so these are the regression cases for that.

case_base_advanced_only() {
  # The branch touches no prompt; the BASE tip moved one. Two-dot would warn.
  echo "feature-only" >> README.md
  git add . && git commit -q -m unrelated-feature
  git checkout -q "${BASE_BRANCH}"
  write_yaml deploy/press-agent/agent.yaml "press-2026-03-03.1" "Someone else's press change."
  git add . && git commit -q -m base-press-change
  BASE="$(git rev-parse HEAD)"
  git checkout -q feature
}

case_base_advanced_and_branch_forgot_bump() {
  # The branch changes the prompt WITHOUT bumping; the BASE tip separately bumped
  # to a newer marker. Two-dot compares base=v3 vs head=v1, sees "different", and
  # lets the un-bumped change through — the false negative that matters.
  write_yaml deploy/support-agent/agent.yaml "support-2026-01-01.1" "Feature-branch wording."
  git add . && git commit -q -m feature-nobump
  git checkout -q "${BASE_BRANCH}"
  write_yaml deploy/support-agent/agent.yaml "support-2026-03-03.1" "Someone else's change."
  git add . && git commit -q -m base-bump
  BASE="$(git rev-parse HEAD)"
  git checkout -q feature
}

run_case "agent.yaml untouched"                      pass "no routine prompt change" case_untouched
run_case "change outside the system: block"          pass "no routine prompt change" case_outside_system_block
run_case "prompt changed + version bumped"           pass "re-paste"                 case_prompt_and_version_bumped
run_case "both channels changed + bumped"            pass "press-agent"              case_both_channels_bumped
run_case "prompt changed, version NOT bumped (FAIL)" fail "PROMPT_VERSION"           case_prompt_changed_version_stale
run_case "press prompt changed, not bumped (FAIL)"   fail "PROMPT_VERSION"           case_press_changed_version_stale
run_case "PROMPT_VERSION marker deleted (FAIL)"      fail "PROMPT_VERSION"           case_marker_deleted
run_case "base advanced, branch touched no prompt"   pass "no routine prompt change" case_base_advanced_only
run_case "stale branch forgot the bump (FAIL)"       fail "PROMPT_VERSION"           case_base_advanced_and_branch_forgot_bump

echo "All agent-prompt-repaste tests passed."
