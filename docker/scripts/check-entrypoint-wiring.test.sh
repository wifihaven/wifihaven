#!/usr/bin/env bash
#
# Regression guard (#2337): every WIFIHAVEN_PRESS_* / WIFIHAVEN_SUPPORT_* env key declared in
# render.yaml MUST be wired into docker/entrypoint.sh — both referenced in the rendered
# application.conf heredoc AND given a `: "${VAR:=...}"` default declaration (the entrypoint runs
# under `set -u`, so an unwired-but-referenced var would abort; a declared-but-unbound key silently
# falls back to the Scala case-class default). This is the bug that stalled the press go-live: the
# #2327 claude-code-cloud transport added WIFIHAVEN_PRESS_DISPATCHER / _CLAUDE_CODE_ROUTINE_ID /
# _CLAUDE_CODE_ROUTINE_TOKEN to render.yaml + the Scala config, but the entrypoint heredoc was never
# extended, so press.dispatcher fell back to "managed-agents" and boot crash-looped demanding the
# managed-agents keys even though claude-code-cloud was selected in Render.
#
# Auto-discovered + run by CI's "Shell Tests" job (any docker/**/*.test.sh).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
render="$repo/render.yaml"
entrypoint="$repo/docker/entrypoint.sh"

fail=0

# Every distinct WIFIHAVEN_PRESS_* / WIFIHAVEN_SUPPORT_* key declared in render.yaml (deduped across
# the staging + prod service blocks, which repeat the same keys).
keys="$(grep -oE 'WIFIHAVEN_(PRESS|SUPPORT)_[A-Z0-9_]+' "$render" | sort -u)"

if [ -z "$keys" ]; then
  echo "FAIL: no WIFIHAVEN_PRESS_/SUPPORT_ keys found in render.yaml — did the file move?"
  exit 1
fi

while IFS= read -r key; do
  [ -z "$key" ] && continue
  # (a) referenced in the entrypoint (the config heredoc binds it into application.conf)
  if ! grep -qF "\${${key}}" "$entrypoint"; then
    echo "FAIL: $key is declared in render.yaml but never referenced in docker/entrypoint.sh"
    fail=1
  fi
  # (b) given a default declaration so `set -u` can't abort on an unset value
  if ! grep -qE ": \"\\\$\{${key}:=" "$entrypoint"; then
    echo "FAIL: $key has no ': \"\${${key}:=...}\"' default declaration in docker/entrypoint.sh"
    fail=1
  fi
done <<< "$keys"

if [ "$fail" -eq 0 ]; then
  echo "PASS: all WIFIHAVEN_PRESS_/SUPPORT_ render.yaml keys are wired in docker/entrypoint.sh"
fi
exit "$fail"
