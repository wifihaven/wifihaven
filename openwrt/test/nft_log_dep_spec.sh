#!/bin/sh
# Packaging-coherence guard (#1144, retargeted in #1126).
#
# render.lua emits forward-chain drop rules carrying a `log` action so the
# agent's nflog tail can synthesize connection_attempt events for forward-chain
# drops (the visibility path of #1122/#1126). An nftables `log` expression needs
# a kernel log backend; if it is absent, the SINGLE atomic `nft -f` that the
# agent applies (policy.lua) rejects the WHOLE table — including the block-page
# DNAT chain — so ALL enforcement silently disappears (blocked hosts resolve and
# serve real upstream, no block page). That is the Gate 3a regression in #1144.
#
# #1126 switched the drop rules from the NFLOG netlink form (`log ... group <N>`,
# backend kmod-nfnetlink-log) to the syslog form (`log prefix "wh_drop:…"`,
# backend kmod-nf-log / kmod-nf-log6) so the production reader can tail the drop
# records straight off `logread` with no NFLOG userspace consumer. The
# load-critical kernel dependency moved accordingly: this guard now ties the
# `log prefix` emission to the kmod-nf-log{,6} packages instead.
#
# Pure text checks — runs in the standard Lua/shell test CI with no kernel
# needed, which is exactly the gap that let #1122 through (render unit tests only
# string-compare output; Gate 2 actually loads nft but was starved by
# concurrency).
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

printf "nft_log_dep_spec: nft log kernel dep coherence (#1144/#1126)\n"

# Trigger condition: does render emit an nftables `log prefix` action on its
# drop rules? Grep for the rule emitter (`drop_suffix`), not prose, so a comment
# mentioning the old form can't false-trigger. If a future change drops the
# nflog path entirely, this guard becomes moot and the dep checks below should
# be relaxed in the same change.
if grep -Eq 'log prefix \\"wh_drop:' "$RENDER"; then
  EMITS_LOG=1
  check "render.lua emits a 'log prefix' drop action" ok
else
  EMITS_LOG=0
  check "render.lua no longer emits a 'log prefix' drop action — dep guard now optional" ok
fi

if [ "$EMITS_LOG" = "1" ]; then
  # Makefile DEPENDS uses the `+pkg` form. Both the v4 (kmod-nf-log) and v6
  # (kmod-nf-log6) syslog loggers are required: the drop rules log both families.
  for pkg in kmod-nf-log kmod-nf-log6; do
    grep -Eq "DEPENDS:=.*\+$pkg(\b| |$)" "$MAKEFILE" \
      && check "Makefile DEPENDS includes +$pkg" ok \
      || check "Makefile DEPENDS includes +$pkg" "missing — atomic nft -f will reject the whole table"

    grep -Eq "^Depends:.*$pkg" "$BUILD_IPK" \
      && check "build-ipk.sh Depends includes $pkg" ok \
      || check "build-ipk.sh Depends includes $pkg" "missing — ipk image lacks the nft log backend"

    grep -Eq "depends:[^\"]*$pkg" "$BUILD_APK" \
      && check "build-apk.sh depends includes $pkg" ok \
      || check "build-apk.sh depends includes $pkg" "missing — apk image lacks the nft log backend"
  done
fi

printf "  ── %d passed, %d failed\n" "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
