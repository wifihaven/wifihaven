#!/bin/sh
# Build luci-app-wifihaven_<version>-<release>_all.apk (OpenWRT APKv3) without
# the full SDK. PKGARCH:=all → arch=noarch; no cross-compilation needed.
# Output: openwrt/luci/luci-app-wifihaven_<version>-<release>_all.apk
#
# Same approach as openwrt/build-apk.sh: builds apk-tools v3 once into
# $APK_TOOLS_PREFIX (default $HOME/.cache/wifihaven-apk-tools) and reuses it.
# When invoked after openwrt/build-apk.sh in the same job, the apk binary is
# already cached and this script is just `apk mkpkg`.
#
# Linux-only. Override version at build time:
#   PKG_VERSION=0.2.0 PKG_RELEASE=1 ./openwrt/luci/build-apk.sh
set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

PKG_VERSION="${PKG_VERSION:-$(grep '^PKG_VERSION:=' "$SCRIPT_DIR/Makefile" | cut -d= -f2)}"
PKG_RELEASE="${PKG_RELEASE:-$(grep '^PKG_RELEASE:=' "$SCRIPT_DIR/Makefile" | cut -d= -f2)}"
OUT_APK="$SCRIPT_DIR/luci-app-wifihaven_${PKG_VERSION}-${PKG_RELEASE}_all.apk"

APK_TOOLS_REPO="${APK_TOOLS_REPO:-https://gitlab.alpinelinux.org/alpine/apk-tools.git}"
APK_TOOLS_REF="${APK_TOOLS_REF:-v3.0.6}"
APK_TOOLS_PREFIX="${APK_TOOLS_PREFIX:-$HOME/.cache/wifihaven-apk-tools}"
APK_BIN="$APK_TOOLS_PREFIX/build/src/apk"

ensure_apk() {
    if [ -x "$APK_BIN" ] && "$APK_BIN" version 2>/dev/null | grep -q '^apk-tools 3'; then
        return 0
    fi
    echo "Building apk-tools ($APK_TOOLS_REF) into $APK_TOOLS_PREFIX..."
    mkdir -p "$APK_TOOLS_PREFIX"
    if [ ! -d "$APK_TOOLS_PREFIX/src/.git" ]; then
        rm -rf "$APK_TOOLS_PREFIX/src"
        git clone --depth 1 --branch "$APK_TOOLS_REF" "$APK_TOOLS_REPO" "$APK_TOOLS_PREFIX/src"
    fi
    (
        cd "$APK_TOOLS_PREFIX/src"
        meson setup --reconfigure \
            -Dprefix=/usr \
            -Durl_backend=wget \
            -Dhelp=disabled \
            -Ddocs=disabled \
            -Dlua=disabled \
            -Dpython=disabled \
            "$APK_TOOLS_PREFIX/build"
        ninja -C "$APK_TOOLS_PREFIX/build" src/apk
    )
}

ensure_apk

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ── stage payload ────────────────────────────────────────────────────────────
mkdir -p "$WORK/data/usr/lib/lua/luci/controller"
mkdir -p "$WORK/data/usr/lib/lua/luci/model/cbi/wifihaven"
mkdir -p "$WORK/data/usr/lib/lua/luci/view/wifihaven"
mkdir -p "$WORK/data/usr/share/rpcd/acl.d"
mkdir -p "$WORK/data/usr/share/luci/menu.d"

cp "$SCRIPT_DIR/luasrc/controller/wifihaven.lua"          "$WORK/data/usr/lib/lua/luci/controller/"
cp "$SCRIPT_DIR/luasrc/model/cbi/wifihaven/settings.lua"  "$WORK/data/usr/lib/lua/luci/model/cbi/wifihaven/"
cp "$SCRIPT_DIR/luasrc/view/wifihaven/status.htm"         "$WORK/data/usr/lib/lua/luci/view/wifihaven/"
cp "$SCRIPT_DIR/root/usr/share/rpcd/acl.d/luci-app-wifihaven.json" "$WORK/data/usr/share/rpcd/acl.d/"
cp "$SCRIPT_DIR/root/usr/share/luci/menu.d/luci-app-wifihaven.json" "$WORK/data/usr/share/luci/menu.d/"

find "$WORK/data" -type f -exec chmod 0644 {} \;

# ── build .apk ───────────────────────────────────────────────────────────────
rm -f "$OUT_APK"
"$APK_BIN" mkpkg \
    --info "name:luci-app-wifihaven" \
    --info "version:${PKG_VERSION}-r${PKG_RELEASE}" \
    --info "description:LuCI web UI for the WifiHaven router agent" \
    --info "arch:noarch" \
    --info "license:MIT" \
    --info "url:https://github.com/wifihaven/wifihaven" \
    --info "maintainer:WifiHaven <noreply@example.com>" \
    --info "depends:luci-base luci-compat wifihaven" \
    --files "$WORK/data" \
    --output "$OUT_APK"

echo "Built: $OUT_APK"
