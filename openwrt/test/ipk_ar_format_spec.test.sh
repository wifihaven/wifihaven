#!/usr/bin/env bash
# Pins the ar member NAMES of every released .ipk so they stay installable on
# OpenWrt 21.02-era opkg (GL.iNet stock 4.8.x), the first hop of the stock
# GL.iNet install path (#2363, found during beta hw validation #2334).
#
# Why: the build-ipk.sh scripts used to assemble the archive with `ar r`. On
# the Linux CI host that `ar` is GNU binutils, which writes SysV-style member
# names WITH a trailing slash ("control.tar.gz/") as its name terminator. opkg
# 1bf042dd… (2021-06-13, shipped on GL.iNet stock / OpenWrt 21.02) looks for
# members named EXACTLY control.tar.gz / data.tar.gz, doesn't find them, and
# aborts with "pkg_init_from_file: Malformed package file" — the package
# cannot install at all. Modern opkg (23.05+) tolerates the slash and macOS BSD
# `ar` writes plain names, which is why it went unnoticed on a dev build.
#
# Gate: each produced .ipk's ar members must be EXACTLY, in order and with no
# trailing slash: debian-binary, control.tar.gz, data.tar.gz. This inspects the
# raw on-disk header bytes (not `ar t`, which normalises the display and would
# hide the very slash we are guarding against). Covers both the agent .ipk
# (openwrt/build-ipk.sh) and the LuCI app .ipk (openwrt/luci/build-ipk.sh).
set -euo pipefail

OPENWRT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

# Walk the ar container by raw bytes and print each member name, one per line.
# ar layout: 8-byte global header "!<arch>\n", then per member a 60-byte header
# (name[16] mtime[12] uid[6] gid[6] mode[8] size[10] magic[2]) + data padded to
# an even byte boundary. Trailing spaces are the ar name padding; a trailing
# slash (the bug) is preserved so the assertion below can catch it.
read_ar_names() {
    ipk="$1"
    total=$(wc -c < "$ipk")
    off=8  # skip "!<arch>\n"
    while [ "$off" -lt "$total" ]; do
        name=$(dd if="$ipk" bs=1 skip="$off" count=16 2>/dev/null)
        size=$(dd if="$ipk" bs=1 skip=$((off + 48)) count=10 2>/dev/null | tr -d ' ')
        # Strip only trailing spaces (name-field padding); keep any slash.
        printf '%s\n' "$name" | sed 's/ *$//'
        adv=$((60 + size))
        [ $((adv % 2)) -ne 0 ] && adv=$((adv + 1))
        off=$((off + adv))
    done
}

expected=$'debian-binary\ncontrol.tar.gz\ndata.tar.gz'

# Build a fresh .ipk with the given builder into an isolated dir (so we read
# exactly this build's bytes, never a stale artifact), then assert its members.
check_builder() {
    label="$1"; builder="$2"; out_ipk="$3"
    tmp="$(mktemp -d)"
    ( cd "$tmp" && PKG_VERSION=0.0.1 PKG_RELEASE=1 "$builder" >/dev/null )
    if [ ! -f "$out_ipk" ]; then
        echo "FAIL: $label did not produce $out_ipk" >&2
        rm -rf "$tmp"
        return 1
    fi
    names=$(read_ar_names "$out_ipk")
    rm -rf "$tmp" "$out_ipk"  # builders write into the source tree; clean up

    if [ "$names" != "$expected" ]; then
        echo "FAIL: $label .ipk ar member names are not the exact set opkg 21.02 requires (#2363)." >&2
        echo "  expected (in order):" >&2
        printf '%s\n' "$expected" | sed 's/^/    /' >&2
        echo "  actual:" >&2
        printf '%s\n' "$names" | sed 's/^/    /' >&2
        if printf '%s\n' "$names" | grep -q '/$'; then
            echo "  → a member name carries a trailing slash (GNU-ar SysV naming);" >&2
            echo "    old opkg's pkg_init_from_file rejects this as Malformed." >&2
        fi
        return 1
    fi
    echo "ok: $label .ipk members are debian-binary, control.tar.gz, data.tar.gz (no trailing slash)"
}

fail=0
check_builder "agent" \
    "$OPENWRT_DIR/build-ipk.sh" \
    "$OPENWRT_DIR/wifihaven_0.0.1-1_all.ipk" || fail=1
check_builder "luci-app" \
    "$OPENWRT_DIR/luci/build-ipk.sh" \
    "$OPENWRT_DIR/luci/luci-app-wifihaven_0.0.1-1_all.ipk" || fail=1

exit "$fail"
