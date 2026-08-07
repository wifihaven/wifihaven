#!/usr/bin/env bash
#
# Regression guard (#1843): the apex (`wifihaven.net`) and `www.wifihaven.net`
# hostnames must NOT appear in WIFIHAVEN_ALLOWED_ORIGINS or
# WIFIHAVEN_UI_ALLOWED_HOSTS in render.yaml.
#
# Since #1842 those two hostnames front the marketing Cloudflare Pages project
# (`wifihaven-www`), which serves no SPA bundle and makes no API calls, and no
# apex `/blocked` router-compat shim was ever shipped (the fleet's
# `block_page_url` points at app.wifihaven.net — see openwrt/install.sh). So
# neither entry is reachable, and #1843 dropped both after the soak.
#
# Re-adding them is not cosmetic. WIFIHAVEN_UI_ALLOWED_HOSTS is unioned into
# EVERY profile's snapshot `extraAllowed` (#944, PolicyService `uiGlobalAllow`),
# so a re-widened set re-opens a fleet-wide carve-out that survives every block.
#
# `app.wifihaven.net` / `app-staging.wifihaven.net` / `api.wifihaven.net` /
# `staging.wifihaven.net` are all fine — the check compares whole
# comma-separated entries, never substrings.
#
# Scope: these two keys only. WIFIHAVEN_WS_ALLOWED_ORIGINS still carries
# apex/www and is deliberately left alone here (#1969 landed after #1843 was
# filed, so the cleanup issue never enumerated it).
# TODO(#2612): extend this guard to WIFIHAVEN_WS_ALLOWED_ORIGINS once that key
# is cleaned up too.
#
# Auto-discovered + run by CI's "Shell Tests" job (any *.test.sh).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/.." && pwd)"
render="$repo/render.yaml"

fail=0

# Emit "<KEY> <value>" for each guarded key, pairing a `- key: X` line with the
# `value: …` line that follows it. Both staging and prod service blocks declare
# these keys, so each is expected to appear twice.
#
# This is a line-oriented parse, not a YAML parse, so it must refuse to guess
# rather than pass on a shape it can't read. Handled here: double- OR
# single-quoted values, and a trailing `# comment`. NOT handled, and rejected
# loudly by the well-formedness check below: block/folded scalars (`value: >-`,
# `|`) and any other multi-line form, which would otherwise parse as the literal
# indicator and silently pass with apex/www still in the file.
pairs="$(
  awk '
    /^[[:space:]]*-[[:space:]]*key:[[:space:]]*WIFIHAVEN_(ALLOWED_ORIGINS|UI_ALLOWED_HOSTS)[[:space:]]*$/ {
      k = $NF; next
    }
    k != "" && /^[[:space:]]*value:/ {
      v = $0
      sub(/^[[:space:]]*value:[[:space:]]*/, "", v)
      sub(/[[:space:]]+#.*$/, "", v)   # trailing comment
      gsub(/["'"'"']/, "", v)          # double or single quotes
      sub(/[[:space:]]+$/, "", v)
      print k, v
      k = ""
      next
    }
    k != "" { k = "" }
  ' "$render"
)"

if [ -z "$pairs" ]; then
  echo "FAIL: no WIFIHAVEN_ALLOWED_ORIGINS / WIFIHAVEN_UI_ALLOWED_HOSTS key+value pairs found in render.yaml — did the file move or change shape?"
  exit 1
fi

# Non-vacuity anchor: staging + prod each declare both keys.
for key in WIFIHAVEN_ALLOWED_ORIGINS WIFIHAVEN_UI_ALLOWED_HOSTS; do
  n="$(printf '%s\n' "$pairs" | grep -c "^${key} " || true)"
  if [ "$n" -lt 2 ]; then
    echo "FAIL: expected $key in both the staging and prod service blocks of render.yaml, found $n"
    fail=1
  fi
done

while IFS= read -r line; do
  [ -z "$line" ] && continue
  key="${line%% *}"
  value="${line#* }"
  # Well-formedness: every entry must look like a hostname (contain a dot).
  # A block/folded scalar leaves the indicator (`>-`, `|`) as the whole value,
  # and any other shape this parse can't read degrades to something that isn't a
  # host list. Fail loudly there rather than reporting a clean PASS over a file
  # whose values were never actually inspected.
  case "$value" in
    *.*) ;;
    *)
      echo "FAIL: $key value '$value' is not a comma-separated host list — this guard cannot read block/folded scalars or other multi-line YAML values. Keep the value inline, or rewrite this guard with a real YAML parser."
      fail=1
      continue
      ;;
  esac
  IFS=',' read -r -a entries <<< "$value"
  for raw in "${entries[@]}"; do
    entry="$(printf '%s' "$raw" | tr -d '[:space:]')"
    # Compare the bare hostname: strip an optional scheme (ALLOWED_ORIGINS
    # carries full origins, UI_ALLOWED_HOSTS carries bare hosts).
    host="${entry#https://}"
    host="${host#http://}"
    case "$host" in
      wifihaven.net | www.wifihaven.net)
        echo "FAIL: $key contains '$entry' — apex/www were dropped in #1843 (marketing-only since #1842); re-adding re-opens a fleet-wide carve-out"
        fail=1
        ;;
    esac
  done
done <<< "$pairs"

if [ "$fail" -eq 0 ]; then
  echo "PASS: apex/www absent from WIFIHAVEN_ALLOWED_ORIGINS and WIFIHAVEN_UI_ALLOWED_HOSTS in render.yaml"
fi
exit "$fail"
