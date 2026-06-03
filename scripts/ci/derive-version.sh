#!/bin/sh
# Derive the WifiHaven ROUTER release version (#499, #1343).
#
# This version is router-only. The API/UI no longer carry a v<X.Y.Z> number —
# they deploy by sha-tagged Docker images (sha-<short>) + :latest, and
# /api/version reports the SHA (#1140). So the only consumers of this script
# are the router build/release workflows (build-release-artifacts.yml,
# openwrt-build.yml).
#
# Output (stdout): a single semver line "<MAJOR>.<MINOR>.<PATCH>" with an
# optional "-dev.<short-sha>" suffix.
#
# Versioning scheme (#1343): PATCH is a per-release counter, NOT a commit
# count. It is the number of already-published `v<MAJOR>.<MINOR>.*` release
# tags on origin — so each approved router prod deploy publishes the next
# integer:
#
#   no v0.3.* tags yet      -> 0.3.0   (the first 0.3 release)
#   v0.3.0 exists           -> 0.3.1
#   v0.3.0, v0.3.1 exist    -> 0.3.2   ... etc.
#
# (The old scheme set PATCH = commit count since the VERSION file last
# changed, which tracked total repo activity — heavy api/ui churn inflated
# the router number even when the router didn't change. #1343 replaced it.)
#
# Inputs:
#   VERSION file at repo root — contains "<MAJOR>.<MINOR>" (e.g. "0.3"),
#     hand-edited by operators when bumping minor/major. Patch is derived.
#     NOTE: VERSION was bumped 0.2 -> 0.3 in #1343 because a fresh release
#     counter under 0.2 would restart at ~13 and collide with the already-
#     shipped v0.2.16…v0.2.96 tags. Field routers pinned to 0.2.x are
#     unaffected; the counter just starts clean at v0.3.0.
#   Origin tags — PATCH counts existing `v<base>.<N>` tags on origin,
#     queried authoritatively via `git ls-remote --tags origin` so the count
#     does not depend on the local clone depth. If origin is unreachable
#     (e.g. an offline ad-hoc build) it falls back to local tags, which the
#     caller must have fetched: #499 — `actions/checkout` with
#     `fetch-depth: 0` fetches full history AND tags, so keep it on the
#     release/build jobs as the offline fallback's safety net.
#
# Usage:
#   scripts/ci/derive-version.sh              # release form: 0.3.0
#   scripts/ci/derive-version.sh --dev <sha>  # ad-hoc form:  0.3.0-dev.abc1234
#
# Callers from CI workflows use the output verbatim — do not transform the
# version downstream.
#
# Collision note (#1224 / #1343): the number is computed PRE-gate (in
# build-release-artifacts.yml) and the v<X.Y.Z> tag is pushed POST-approval
# (in publish-openwrt-release.yml). Two concurrent publishes could compute
# the same number; Router CD serializes via its concurrency group + manual
# approval so this is rare, and the publish step FAILS LOUDLY if the computed
# tag already exists on origin (it does not silently skip), so a collision is
# never published under a wrong/duplicate number.
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

# Regex-escape the base ("0.3" -> "0\.3") so the dots are literal and the
# match is anchored: "v0\.3\.[0-9]+$" matches v0.3.5 but NOT v0.30.0
# (the char after "v0.3" there is "0", not ".").
esc=$(printf '%s' "$base" | sed 's/[.[\*^$]/\\&/g')

# Authoritative source: release tags on origin. `git ls-remote` succeeding
# with no matching tag (a legitimately fresh MINOR like 0.3) must NOT be
# confused with a network failure — so branch on the command's exit status,
# not on empty output, and only fall back to local tags when ls-remote
# actually fails (offline build).
if remote_refs=$(git ls-remote --tags origin 2>/dev/null); then
  tag_names=$(printf '%s\n' "$remote_refs" | awk '{print $2}')
else
  # Offline fallback: local tags (the caller must have fetched them — see the
  # #499 fetch-depth:0 note in the header). Normalize to the same ref form.
  tag_names=$(git tag -l 2>/dev/null | sed 's|^|refs/tags/|' || true)
fi

# PATCH = count of existing v<base>.<N> release tags. Strip the refs/tags/
# prefix and any peeled-tag "^{}" suffix (annotated tags appear twice in
# ls-remote output), keep only exact v<base>.<digits> tags, dedupe, count.
patch=$(printf '%s\n' "$tag_names" \
  | sed -e 's|^refs/tags/||' -e 's|\^{}$||' \
  | grep -E "^v${esc}\.[0-9]+$" \
  | sort -u \
  | wc -l \
  | tr -d ' ')

ver="${base}.${patch}"

if [ "${1:-}" = "--dev" ]; then
  if [ -z "${2:-}" ]; then
    echo "derive-version: --dev requires a short sha argument" >&2
    exit 1
  fi
  ver="${ver}-dev.${2}"
fi

printf '%s\n' "$ver"
