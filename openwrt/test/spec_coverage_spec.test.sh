#!/bin/sh
# Coverage guard for run_tests.sh spec auto-discovery (#1877).
#
# This is a *.test.sh, so CI's "Discover and run *.test.sh" job gates it
# automatically — which means the guard gates ITSELF with no list to maintain.
#
# It fails if any test/*_spec.lua or test/*_spec.sh on disk is neither
#   (a) in run_tests.sh's actual runlist (queried via WH_LIST_SPECS=1), nor
#   (b) documented in test/spec_exclude.txt with a reason.
#
# That catches the whole #1877 failure class:
#   - a newly-added spec that silently never runs (root cause behind #1870),
#   - an exclusion that hides a spec without being written down,
#   - the discovery globs being quietly reverted to a hand-maintained list.
#
# The runlist is read from run_tests.sh's *behavior* (list mode), not its text,
# so a revert to a static list that drops a spec is caught the moment the spec
# exists on disk; the literal glob-token checks below add belt-and-suspenders so
# a revert is caught even before the next spec lands.
set -eu

cd "$(dirname "$0")/.."   # → openwrt/

# shellcheck source=test/spec_discovery.sh
. ./test/spec_discovery.sh   # provides wh_spec_excludes / wh_spec_is_excluded

RUN_TESTS="test/run_tests.sh"
FAIL=0
report() { printf "  FAIL: %s\n" "$1"; FAIL=1; }

printf "spec_coverage_spec: run_tests.sh spec auto-discovery is complete (#1877)\n"

# Actual list of specs run_tests.sh would execute (no suite is run in list mode).
RUNLIST=$(WH_LIST_SPECS=1 sh "$RUN_TESTS")

# 1. Every on-disk spec must be either run or explicitly excluded.
for f in test/*_spec.lua test/*_spec.sh; do
  [ -e "$f" ] || continue
  base=${f#test/}
  if printf '%s\n' "$RUNLIST" | grep -Fxq "$f"; then continue; fi
  if wh_spec_is_excluded "$base"; then continue; fi
  report "$f exists on disk but run_tests.sh neither runs it nor excludes it in $WH_SPEC_EXCLUDE_FILE"
done

# 2. Every exclude entry must still exist on disk (no stale excludes).
EXCL=$(wh_spec_excludes)
for base in $EXCL; do
  [ -e "test/$base" ] || report "$WH_SPEC_EXCLUDE_FILE lists '$base' but test/$base does not exist (stale exclude)"
done

# 3. *_spec.test.sh files are owned by the *.test.sh discovery job — run_tests.sh
#    must not pick them up via the *_spec.sh glob.
if printf '%s\n' "$RUNLIST" | grep -q '_spec\.test\.sh$'; then
  report "run_tests.sh runlist includes a *_spec.test.sh file (owned by the *.test.sh job)"
fi

# 4. Belt-and-suspenders: run_tests.sh must still discover via glob, not a list.
grep -q 'test/\*_spec\.lua' "$RUN_TESTS" \
  || report "$RUN_TESTS no longer globs test/*_spec.lua (reverted to a static list?)"
grep -q 'test/\*_spec\.sh' "$RUN_TESTS" \
  || report "$RUN_TESTS no longer globs test/*_spec.sh (reverted to a static list?)"

if [ "$FAIL" -eq 0 ]; then
  printf "  PASS: every test/*_spec.{lua,sh} is discovered or documented-excluded\n"
fi
exit "$FAIL"
