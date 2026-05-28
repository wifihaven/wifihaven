#!/bin/sh
# Packaging-coherence guard (#1144).
#
# render.lua emits forward-chain drop rules carrying `log group <N> counter
# drop` (#1122, for the nflog → connection_attempt visibility path). The
# nftables `log ... group` expression is NFLOG and needs the nfnetlink_log
# kernel backend — OpenWRT package `kmod-nfnetlink-log`. It is NOT in the
# default x86 image profile (only kmod-nft-{core,fib,nat,offload} ship via
# firewall4), so wifihaven must pull it in itself.
#
# The agent applies the rendered ruleset with a single atomic `nft -f`
# (policy.lua). If `log group` is unsupported, that one statement makes the
# WHOLE table load fail — including the block-page DNAT chain — so ALL
# enforcement silently disappears (blocked hosts resolve and serve real
# upstream, no block page). That is the Gate 3a regression in #1144.
#
# This guard ties the two facts together: as long as render emits `log group`,
# every package-dependency declaration must list kmod-nfnetlink-log. Pure text
# checks — runs in the standard Lua/shell test CI with no kernel needed, which
# is exactly the gap that let #1122 through (render unit tests only string-
# compare output; Gate 2 actually loads nft but was starved by concurrency).
set -e

PASS=0; FAIL=0
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RENDER="$ROOT/files/usr/lib/lua/wifihaven/render.lua"
MAKEFILE="$ROOT/Makefile"
BUILD_IPK="$ROOT/build-ipk.sh"
BUILD_APK="$ROOT/build-apk.sh"

check() {
  if [ "$2" = "ok" ]; then
    printf "  PASS: %s\n" "$1"; PASS=$((PASS + 1))
  else
    printf "  FAIL: %s — %s\n" "$1" "$2"; FAIL=$((FAIL + 1))
  fi
}

for f in "$RENDER" "$MAKEFILE" "$BUILD_IPK" "$BUILD_APK"; do
  [ -f "$f" ] || { printf "MISSING: %s\n" "$f"; exit 1; }
done

printf "nft_log_dep_spec: NFLOG kernel dep coherence (#1144)\n"

# Trigger condition: does render still emit an NFLOG `log group` statement?
# If a future change drops the nflog path entirely, this guard becomes moot
# and the dep checks below should be relaxed in the same change.
if grep -q "log group" "$RENDER"; then
  EMITS_LOG_GROUP=1
  check "render.lua emits an NFLOG 'log group' statement" ok
else
  EMITS_LOG_GROUP=0
  check "render.lua no longer emits 'log group' — dep guard now optional" ok
fi

if [ "$EMITS_LOG_GROUP" = "1" ]; then
  # Makefile DEPENDS uses the `+pkg` form.
  grep -Eq "DEPENDS:=.*\+kmod-nfnetlink-log" "$MAKEFILE" \
    && check "Makefile DEPENDS includes +kmod-nfnetlink-log" ok \
    || check "Makefile DEPENDS includes +kmod-nfnetlink-log" "missing — atomic nft -f will reject the whole table"

  # build-ipk.sh control field uses comma-separated names.
  grep -Eq "^Depends:.*kmod-nfnetlink-log" "$BUILD_IPK" \
    && check "build-ipk.sh Depends includes kmod-nfnetlink-log" ok \
    || check "build-ipk.sh Depends includes kmod-nfnetlink-log" "missing — ipk image lacks NFLOG support"

  # build-apk.sh passes a space-separated --info depends:... string.
  grep -Eq "depends:[^\"]*kmod-nfnetlink-log" "$BUILD_APK" \
    && check "build-apk.sh depends includes kmod-nfnetlink-log" ok \
    || check "build-apk.sh depends includes kmod-nfnetlink-log" "missing — apk image lacks NFLOG support"
fi

printf "  ── %d passed, %d failed\n" "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
