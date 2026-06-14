#!/usr/bin/env bash
# Asserts that the package DEPENDS list is single-sourced from openwrt/Makefile
# and reproduced identically in the local builder scripts that produce the
# shipped .ipk and .apk (#1717).
#
# Why: build-ipk.sh and build-apk.sh wrote a hard-coded Depends string of their
# own that drifted from Makefile DEPENDS. When #1702 added lua-openssl to the
# Makefile DEPENDS for the SNI sidecar's QUIC path, neither script picked it
# up — the shipped .ipk was missing lua-openssl, the on-router sni-tail's
# `require("openssl")` crashed the sidecar in a procd respawn loop, and the
# Gate 2 v4-TCP SNI attribution test timed out waiting for a dns-cache row.
# The runtime symptom traced back to a duplicated decision (the DEPENDS list)
# living in three places. This pins the equivalence so the next addition can't
# regress the same way.

set -euo pipefail
OPENWRT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

# Parse the +-prefixed DEPENDS line from the OpenWRT Makefile into a sorted,
# normalized package list (one per line, no leading +).
parse_makefile_depends() {
    grep -E '^[[:space:]]*DEPENDS:=' "$OPENWRT_DIR/Makefile" \
        | head -n1 \
        | sed -E 's/^[[:space:]]*DEPENDS:=//' \
        | tr ' \t' '\n' \
        | sed -E 's/^\+//' \
        | grep -v '^$' \
        | LC_ALL=C sort -u
}

# Pull the Depends string out of build-ipk.sh's control-file heredoc and
# normalize it the same way.
parse_ipk_depends() {
    grep -E '^Depends:' "$OPENWRT_DIR/build-ipk.sh" \
        | head -n1 \
        | sed -E 's/^Depends:[[:space:]]*//' \
        | tr ',' '\n' \
        | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//' \
        | grep -v '^$' \
        | LC_ALL=C sort -u
}

# Pull the apk depends list out of build-apk.sh's --info line.
parse_apk_depends() {
    grep -E '^[[:space:]]*--info "depends:' "$OPENWRT_DIR/build-apk.sh" \
        | head -n1 \
        | sed -E 's/.*--info "depends://; s/"[[:space:]]*\\?$//' \
        | tr ' ' '\n' \
        | grep -v '^$' \
        | LC_ALL=C sort -u
}

makefile=$(parse_makefile_depends)
ipk=$(parse_ipk_depends)
apk=$(parse_apk_depends)

fail=0

if [ "$makefile" != "$ipk" ]; then
    echo "FAIL: build-ipk.sh Depends drift from openwrt/Makefile DEPENDS" >&2
    (diff <(echo "$makefile") <(echo "$ipk") | sed 's/^/  /' >&2) || true
    fail=1
fi

if [ "$makefile" != "$apk" ]; then
    echo "FAIL: build-apk.sh --info depends drift from openwrt/Makefile DEPENDS" >&2
    (diff <(echo "$makefile") <(echo "$apk") | sed 's/^/  /' >&2) || true
    fail=1
fi

if [ "$fail" = 0 ]; then
    echo "ok: openwrt/Makefile DEPENDS == build-ipk.sh Depends == build-apk.sh --info depends"
fi
exit "$fail"
