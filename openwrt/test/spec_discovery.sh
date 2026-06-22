#!/bin/sh
# Shared spec-discovery helpers for the OpenWrt test harness (#1877).
#
# Sourced by both run_tests.sh (which skips excluded specs during discovery) and
# spec_coverage_spec.test.sh (which fails if an on-disk spec is neither run nor
# excluded), so the exclude allowlist — both its DATA (test/spec_exclude.txt) and
# the parse that reads it — lives in exactly one place. Callers cd to openwrt/
# before sourcing, so the default relative path resolves.
#
# Not executable on its own; it only defines functions.

WH_SPEC_EXCLUDE_FILE="${WH_SPEC_EXCLUDE_FILE:-test/spec_exclude.txt}"

# Print the documented exclude basenames, one per line (comment + blank lines
# stripped). Empty output (no exclusions) is fine.
wh_spec_excludes() {
  grep -v '^[[:space:]]*#' "$WH_SPEC_EXCLUDE_FILE" | grep -v '^[[:space:]]*$' || true
}

# True if basename $1 is on the exclude allowlist.
wh_spec_is_excluded() {
  wh_spec_excludes | grep -Fxq "$1"
}
