#!/bin/sh
# Verify every module under files/usr/lib/lua/wifihaven/ loads cleanly under
# the LUA_PATH that exists on a real router — i.e., only the qualified
# `wifihaven.X` form is reachable from /usr/lib/lua/, NOT the bare `X` form.
#
# The default test harness (test/run_tests.sh) sets LUA_PATH to include both
# ./files/usr/lib/lua/?.lua AND ./files/usr/lib/lua/wifihaven/?.lua, so
# require("dns_tail_sets") works alongside require("wifihaven.dns_tail_sets").
# On the installed router, lua's default LUA_PATH is just /usr/share/lua/?.lua
# and /usr/lib/lua/?.lua, so a bare require("dns_tail_sets") fails with
# "module not found" and the wifihaven-agent crash-loops at startup.
#
# This caught #1683: PR #1659 (eb_refresh.lua, #1658) used
# `require("dns_tail_sets")` instead of `require("wifihaven.dns_tail_sets")`,
# which passed locally but broke every Gate 2 / Gate 3a run because the
# agent could not load.
set -e

PASS=0; FAIL=0
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MOD_DIR="$ROOT/files/usr/lib/lua/wifihaven"

[ -d "$MOD_DIR" ] || { printf "MISSING: %s\n" "$MOD_DIR"; exit 1; }

# Mirror the production LUA_PATH: only the qualified `wifihaven.X` form is
# reachable. Bare names must use require("wifihaven.X") to resolve.
PROD_LUA_PATH="$ROOT/files/usr/lib/lua/?.lua;$ROOT/test/shim/?.lua;$ROOT/test/shim/?/init.lua;$(lua -e 'print(package.path)')"

check() {
  if [ "$2" = "ok" ]; then
    printf "  PASS: %s\n" "$1"; PASS=$((PASS + 1))
  else
    printf "  FAIL: %s — %s\n" "$1" "$2"; FAIL=$((FAIL + 1))
  fi
}

for f in "$MOD_DIR"/*.lua; do
  name="$(basename "$f" .lua)"
  # `init` files under a subdir are loaded as the parent module name, skip.
  case "$name" in
    init) continue ;;
  esac
  err="$(LUA_PATH="$PROD_LUA_PATH" lua -e "require('wifihaven.$name')" 2>&1 || true)"
  if [ -z "$err" ]; then
    check "require('wifihaven.$name') loads under production LUA_PATH" ok
  else
    # Trim to the first line for readability.
    first="$(printf "%s" "$err" | head -n1)"
    check "require('wifihaven.$name') loads under production LUA_PATH" "$first"
  fi
done

printf "\n%d passed, %d failed\n" "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
