#!/usr/bin/env bash
# Informational check: report whether this checkout has git hooks wired up
# to .githooks. Used as a CI surface for #1050 — never fails CI; just prints
# the state so a contributor whose hooks weren't installed sees it in the log.
set -uo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || echo "")"
if [[ -z "$ROOT" ]]; then
  echo "verify-hooks: not a git checkout — skipping"
  exit 0
fi
cd "$ROOT"

current="$(git config --get core.hooksPath 2>/dev/null || echo "")"
if [[ "$current" == ".githooks" ]]; then
  echo "verify-hooks: OK (core.hooksPath=.githooks)"
  exit 0
fi

echo "verify-hooks: WARNING — core.hooksPath is '${current:-<unset>}', expected '.githooks'."
echo "verify-hooks: pre-commit/pre-push gates (scalafmt, tsc, eslint) will NOT fire locally."
echo "verify-hooks: run 'mill resolve _' or 'scripts/install-hooks.sh' to install."
exit 0
