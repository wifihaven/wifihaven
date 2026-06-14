#!/usr/bin/env bash
# Asserts the wifihaven package DEPENDS list is single-sourced from
# openwrt/Makefile and that the local builder scripts that ship the .ipk
# / .apk use the shared helper rather than a hard-coded copy (#1717).
#
# Why: build-ipk.sh and build-apk.sh used to embed a literal Depends string
# of their own. When #1702 added lua-openssl to openwrt/Makefile for the SNI
# sidecar's QUIC path, neither script picked it up — the shipped .ipk was
# missing lua-openssl, the on-router sni-tail's `require("openssl")` crashed
# the sidecar in a procd respawn loop, and the Gate 2 v4-TCP SNI attribution
# test timed out (master-router-cd red for two days; prod router.lan stuck on
# 24-day-old code per #1716 because no openwrt-latest could publish).
#
# Gate: fail if either builder script
#   (a) stops calling openwrt/depends-list.sh, OR
#   (b) re-introduces a literal hard-coded Depends list, OR
#   (c) the helper itself loses one of the regression-pinned packages.

set -euo pipefail
OPENWRT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
HELPER="$OPENWRT_DIR/depends-list.sh"

if [ ! -x "$HELPER" ]; then
    echo "FAIL: $HELPER missing or not executable (single source for wifihaven DEPENDS)" >&2
    exit 1
fi

# (c) Regression pins. lua-openssl was the live #1717 miss; kmod-nf-conntrack6
# was its #1690 sibling that also escaped the stale builders.
lines=$("$HELPER" lines || true)
if [ -z "$lines" ]; then
    echo "FAIL: depends-list.sh emitted nothing — openwrt/Makefile DEPENDS unparsable?" >&2
    exit 1
fi
for required in lua-openssl kmod-nf-conntrack6 tcpdump-mini; do
    if ! printf '%s\n' "$lines" | grep -qx "$required"; then
        echo "FAIL: canonical DEPENDS missing $required (regression-pinned by #1717)" >&2
        echo "  current list:" >&2
        printf '%s\n' "$lines" | sed 's/^/    /' >&2
        exit 1
    fi
done

fail=0

# (a) + (b): each builder script must call depends-list.sh AND have its
# control line reference ${DEPENDS_LIST}; a literal `Depends: lua, libuci-lua…`
# / `--info "depends:lua libuci-lua…"` line is forbidden because that is
# the failure mode this rule guards.
check_builder() {
    label="$1"; script="$2"; helper_arg="$3"; control_pattern="$4"
    if ! grep -qE "DEPENDS_LIST=.*depends-list\\.sh.* ${helper_arg}" "$script"; then
        echo "FAIL: $label does not assign DEPENDS_LIST=\$(.../depends-list.sh ${helper_arg})" >&2
        return 1
    fi
    if ! grep -qE -- "$control_pattern" "$script"; then
        echo "FAIL: $label control line does not expand \${DEPENDS_LIST} (re-hardcoded?)" >&2
        return 1
    fi
    # Reject any literal Depends/--info depends: line whose value is a real
    # package name rather than the ${DEPENDS_LIST} reference.
    if grep -nE '^(Depends:|[[:space:]]*--info "depends:)[[:space:]]*[a-z]' "$script" \
        | grep -vF '${DEPENDS_LIST}' >/dev/null; then
        echo "FAIL: $label contains a literal Depends list (must reference \${DEPENDS_LIST})" >&2
        grep -nE '^(Depends:|[[:space:]]*--info "depends:)[[:space:]]*[a-z]' "$script" >&2
        return 1
    fi
}

check_builder "build-ipk.sh" "$OPENWRT_DIR/build-ipk.sh" \
    ipk 'Depends: \$\{DEPENDS_LIST\}' || fail=1

check_builder "build-apk.sh" "$OPENWRT_DIR/build-apk.sh" \
    apk '--info "depends:\$\{DEPENDS_LIST\}"' || fail=1

if [ "$fail" = 0 ]; then
    echo "ok: openwrt/Makefile DEPENDS is the single source for build-ipk.sh and build-apk.sh"
fi
exit "$fail"
