#!/usr/bin/env bash
# Smoke test for #1050: in a throwaway clone, simulate a fresh worktree and
# confirm the build.mill auto-install sets core.hooksPath=.githooks.
#
# Requires `mill` on PATH. Skip the mill-bound assertion (and only assert the
# install-hooks.sh path) if mill isn't available, so the script still runs in
# minimal environments.
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "test-hooks-install: staging copy in $TMP"
git clone --quiet --no-local "$ROOT" "$TMP/repo" >/dev/null
# Overlay any uncommitted script changes so the test reflects the working
# tree, not just the last commit (lets you iterate without committing first).
for f in install-hooks.sh verify-hooks-configured.sh test-hooks-install.sh; do
  if [[ -f "$ROOT/scripts/$f" ]]; then
    cp "$ROOT/scripts/$f" "$TMP/repo/scripts/$f"
  fi
done
cp "$ROOT/build.mill" "$TMP/repo/build.mill"
cd "$TMP/repo"

# Step 1: hooks should NOT be configured in a fresh clone.
got="$(git config --get core.hooksPath 2>/dev/null || echo "")"
if [[ -n "$got" ]]; then
  echo "test-hooks-install: FAIL — fresh clone already has core.hooksPath=$got" >&2
  exit 1
fi
echo "test-hooks-install: fresh clone has no core.hooksPath (expected)"

# Step 2: the manual installer is the source of truth — confirm it works.
bash scripts/install-hooks.sh >/dev/null
got="$(git config --get core.hooksPath)"
if [[ "$got" != ".githooks" ]]; then
  echo "test-hooks-install: FAIL — install-hooks.sh did not set core.hooksPath (got '$got')" >&2
  exit 1
fi
echo "test-hooks-install: install-hooks.sh set core.hooksPath=.githooks"

# Step 3: re-running is a no-op (idempotency).
bash scripts/install-hooks.sh >/dev/null
got="$(git config --get core.hooksPath)"
if [[ "$got" != ".githooks" ]]; then
  echo "test-hooks-install: FAIL — second install left core.hooksPath as '$got'" >&2
  exit 1
fi
echo "test-hooks-install: re-install is idempotent"

# Step 4: the verify script reports OK when configured.
if ! bash scripts/verify-hooks-configured.sh | grep -q "^verify-hooks: OK"; then
  echo "test-hooks-install: FAIL — verify-hooks-configured.sh did not report OK" >&2
  exit 1
fi
echo "test-hooks-install: verify-hooks-configured.sh reports OK"

# Step 5: unset and confirm verify warns (informational, doesn't fail).
git config --unset core.hooksPath
if ! bash scripts/verify-hooks-configured.sh | grep -q "^verify-hooks: WARNING"; then
  echo "test-hooks-install: FAIL — verify did not warn when unset" >&2
  exit 1
fi
echo "test-hooks-install: verify-hooks-configured.sh warns when unset"

# Step 6 (optional, only if mill is on PATH): mill evaluation auto-installs.
if command -v mill >/dev/null 2>&1; then
  echo "test-hooks-install: invoking mill installHooks to trigger build.mill auto-install"
  mill installHooks >/dev/null 2>&1 || true
  got="$(git config --get core.hooksPath 2>/dev/null || echo "")"
  if [[ "$got" != ".githooks" ]]; then
    echo "test-hooks-install: FAIL — mill did not auto-install hooks (got '$got')" >&2
    exit 1
  fi
  echo "test-hooks-install: mill auto-install restored core.hooksPath=.githooks"
else
  echo "test-hooks-install: mill not on PATH — skipping mill auto-install check"
fi

echo "test-hooks-install: PASS"
