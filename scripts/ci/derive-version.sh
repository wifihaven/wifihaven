#!/bin/sh
# Derive the WifiHaven release version (#499).
#
# Output (stdout): a single semver line "<MAJOR>.<MINOR>.<PATCH>" with an
# optional "-dev.<short-sha>" suffix.
#
# Inputs:
#   VERSION file at repo root — contains "<MAJOR>.<MINOR>" (e.g. "0.2"),
#     hand-edited by operators when bumping minor/major. Patch is derived.
#   Git history — PATCH is the number of commits since VERSION last changed,
#     so editing VERSION resets the patch counter to .0 on that commit.
#
# Usage:
#   scripts/ci/derive-version.sh              # release form: 0.2.7
#   scripts/ci/derive-version.sh --dev <sha>  # ad-hoc form:  0.2.7-dev.abc1234
#
# The script is the single source of truth for both the OpenWRT package
# version and the API image's v<X.Y.Z> tag. Callers from CI workflows use
# its output verbatim — do not transform the version downstream.
set -eu

cd "$(dirname "$0")/../.."

if [ ! -f VERSION ]; then
  echo "derive-version: missing repo-root VERSION file" >&2
  exit 1
fi

base=$(tr -d ' \n\t' < VERSION)
case "$base" in
  *.*.*) echo "derive-version: VERSION must be <MAJOR>.<MINOR>, got '$base'" >&2; exit 1 ;;
  *.*) : ;;
  *) echo "derive-version: VERSION must be <MAJOR>.<MINOR>, got '$base'" >&2; exit 1 ;;
esac

# Find the commit that last changed VERSION; patch = commits since then.
# In shallow CI clones (default fetch-depth=1) `git log -- VERSION` returns
# nothing — callers MUST `actions/checkout` with `fetch-depth: 0` for the
# count to be meaningful. Fall back to 0 so PR builds don't crash.
since=$(git log -1 --format=%H -- VERSION 2>/dev/null || true)
if [ -n "$since" ]; then
  patch=$(git rev-list --count "${since}..HEAD" 2>/dev/null || echo 0)
else
  patch=0
fi

ver="${base}.${patch}"

if [ "${1:-}" = "--dev" ]; then
  if [ -z "${2:-}" ]; then
    echo "derive-version: --dev requires a short sha argument" >&2
    exit 1
  fi
  ver="${ver}-dev.${2}"
fi

printf '%s\n' "$ver"
