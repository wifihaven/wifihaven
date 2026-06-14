#!/bin/sh
# Single source of truth for the wifihaven package's runtime DEPENDS list
# (#1717, AGENTS.md §single-source-of-truth). Parses the canonical +-prefixed
# OpenWRT Makefile DEPENDS line (openwrt/Makefile, sibling of this script)
# and emits the list in the format the named packaging tool expects:
#
#   ./depends-list.sh ipk    →  "pkg1, pkg2, pkg3"   (Debian control "Depends:")
#   ./depends-list.sh apk    →  "pkg1 pkg2 pkg3"     (apk mkpkg --info depends:)
#   ./depends-list.sh lines  →  one package per line  (test diffs, ad-hoc)
#
# Invoked from openwrt/build-ipk.sh and openwrt/build-apk.sh so adding a
# runtime dependency only requires editing openwrt/Makefile;
# openwrt/test/build_depends_sync_spec.test.sh pins the equivalence in CI.
#
# The Makefile path is the file named "Makefile" in the same directory as
# this script. Set WH_DEPENDS_MAKEFILE=/path/to/Makefile to override (tests).
#
# Execute, do not source: the script resolves its directory from $0 so sourcing
# from another shell would silently misbehave. Callers invoke it as
# `"$SCRIPT_DIR/depends-list.sh" ipk`.

set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
MAKEFILE="${WH_DEPENDS_MAKEFILE:-$SCRIPT_DIR/Makefile}"

if [ ! -f "$MAKEFILE" ]; then
    echo "depends-list.sh: cannot find Makefile at $MAKEFILE" >&2
    exit 1
fi

emit_lines() {
    # Bail loud if the Makefile grows a second DEPENDS:= line — silently
    # taking the first match would let a future luci subpackage's DEPENDS
    # accidentally take precedence over the wifihaven package's.
    n=$(grep -cE '^[[:space:]]*DEPENDS:=' "$MAKEFILE" || true)
    if [ "$n" -ne 1 ]; then
        echo "depends-list.sh: expected exactly one DEPENDS:= line in $MAKEFILE, found $n" >&2
        exit 1
    fi
    grep -E '^[[:space:]]*DEPENDS:=' "$MAKEFILE" \
        | sed -E 's/^[[:space:]]*DEPENDS:=//' \
        | tr ' \t' '\n' \
        | sed -E 's/^\+//' \
        | grep -v '^$'
}

case "${1:-lines}" in
    lines) emit_lines ;;
    ipk)   emit_lines | paste -sd ',' - | sed 's/,/, /g' ;;
    apk)   emit_lines | paste -sd ' ' - ;;
    *)
        echo "depends-list.sh: unknown format '$1' (want: lines|ipk|apk)" >&2
        exit 2
        ;;
esac
