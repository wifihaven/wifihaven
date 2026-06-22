#!/bin/sh
# Run the wifihaven OpenWrt agent unit tests.
# Requires: luarocks install busted lua-cjson
#
# LUA_PATH is set so that:
#   require("render")            →  files/usr/lib/lua/wifihaven/render.lua    (short form, used in tests)
#   require("wifihaven.render")  →  files/usr/lib/lua/wifihaven/render.lua    (qualified form, used in modules)
#
# Specs are AUTO-DISCOVERED, not hand-listed (#1877): every test/*_spec.lua runs
# under busted and every test/*_spec.sh runs as a shell spec, so dropping a new
# spec into test/ gates it with no edit here. The only carve-out is the
# documented exclude allowlist in test/spec_exclude.txt (today: jsonc_shim_spec,
# which needs the real luci.jsonc C module and runs in CI's Contract Tests job).
# spec_coverage_spec.test.sh enforces that this discovery stays complete.
# Note: test/*_spec.test.sh files are a different suffix owned by CI's
# "Discover and run *.test.sh" job — the *_spec.sh glob does not match them.
set -e
cd "$(dirname "$0")/.."

# shellcheck source=test/spec_discovery.sh
. ./test/spec_discovery.sh

# Discover busted specs: test/*_spec.lua minus the exclude allowlist. Shell glob
# expansion is sorted, so the runlist is deterministic.
LUA_SPECS=""
for f in test/*_spec.lua; do
  [ -e "$f" ] || continue
  wh_spec_is_excluded "${f#test/}" && continue
  LUA_SPECS="$LUA_SPECS $f"
done

# Discover shell specs: test/*_spec.sh (does not match *_spec.test.sh).
SHELL_SPECS=""
for f in test/*_spec.sh; do
  [ -e "$f" ] || continue
  SHELL_SPECS="$SHELL_SPECS $f"
done

# List mode (WH_LIST_SPECS=1): print the specs this script would run, one per
# line, and exit without running anything. spec_coverage_spec.test.sh reads this
# to verify discovery is complete.
if [ "${WH_LIST_SPECS:-}" = "1" ]; then
  for f in $LUA_SPECS $SHELL_SPECS; do printf '%s\n' "$f"; done
  exit 0
fi

# shellcheck disable=SC2086 # word-splitting LUA_SPECS into separate args is intentional
LUA_PATH="./files/usr/lib/lua/?.lua;./files/usr/lib/lua/wifihaven/?.lua;./spike/ws-1845/?.lua;./test/shim/?.lua;./test/shim/?/init.lua;$(lua -e 'print(package.path)')" \
  busted $LUA_SPECS "$@"

echo ""
for f in $SHELL_SPECS; do
  sh "$f"
done
# ws-client e2e (#1845): runs ws://+wss:// round-trips through the real client;
# self-skips (exit 0) when lua-cqueues/lua-luaossl aren't installed.
sh spike/ws-1845/e2e_test.sh
