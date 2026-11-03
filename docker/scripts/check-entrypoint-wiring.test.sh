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
#
# #2954: the Plain helpdesk integration and both Claude responders were REMOVED. The API no longer
# reads any WIFIHAVEN_SUPPORT_* / WIFIHAVEN_PRESS_* env var and docker/entrypoint.sh no longer binds
# them, but render.yaml still declares them (render.yaml is deliberately left to the follow-up
# deploy-config PR so the env vars stay harmlessly set until then). Every such key is therefore on
# the RETIRED allowlist below and skipped, instead of being required to be wired.
# TODO(#2954): delete this whole script together with the keys in render.yaml (follow-up PR).
set -euo pipefail

# Keys that render.yaml still declares but the API intentionally no longer reads. Prefix patterns,
# not a per-key list, so the follow-up PR can remove the keys in any order without touching this.
retired='^WIFIHAVEN_(PRESS|SUPPORT)_'

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
render="$repo/render.yaml"
entrypoint="$repo/docker/entrypoint.sh"

fail=0

# Every distinct WIFIHAVEN_PRESS_* / WIFIHAVEN_SUPPORT_* key declared in render.yaml (deduped across
# the staging + prod service blocks, which repeat the same keys).
all_keys="$(grep -oE 'WIFIHAVEN_(PRESS|SUPPORT)_[A-Z0-9_]+' "$render" | sort -u || true)"
# Everything not retired still must be wired (none today: the whole prefix family is retired).
keys="$(printf '%s\n' "$all_keys" | grep -Ev "$retired" || true)"

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
  echo "PASS: no non-retired WIFIHAVEN_PRESS_/SUPPORT_ render.yaml keys need entrypoint wiring (#2954)"
fi
exit "$fail"
