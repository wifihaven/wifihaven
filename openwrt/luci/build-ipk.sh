#!/bin/sh
# Build luci-app-wifihaven_<version>_all.ipk without the full OpenWRT SDK.
# Pure Lua + JSON assets, so PKGARCH:=all and no cross-compilation needed.
# Output: openwrt/luci/luci-app-wifihaven_<version>-<release>_all.ipk
set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

PKG_VERSION="${PKG_VERSION:-$(grep '^PKG_VERSION:=' "$SCRIPT_DIR/Makefile" | cut -d= -f2)}"
PKG_RELEASE="${PKG_RELEASE:-$(grep '^PKG_RELEASE:=' "$SCRIPT_DIR/Makefile" | cut -d= -f2)}"
OUT_IPK="$SCRIPT_DIR/luci-app-wifihaven_${PKG_VERSION}-${PKG_RELEASE}_all.ipk"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

printf '2.0\n' > "$WORK/debian-binary"

mkdir "$WORK/ctrl"
cat > "$WORK/ctrl/control" <<EOF
Package: luci-app-wifihaven
Version: ${PKG_VERSION}-${PKG_RELEASE}
Depends: luci-base, luci-compat, wifihaven
Architecture: all
Maintainer: WifiHaven <noreply@example.com>
Description: LuCI web UI for the WifiHaven router agent. Exposes cadence
 tunables from /etc/config/wifihaven plus a read-only status overview under
 Administration -> Services -> WifiHaven.
EOF

(cd "$WORK/ctrl" && tar czf "$WORK/control.tar.gz" .)

mkdir -p "$WORK/data/usr/lib/lua/luci/controller"
mkdir -p "$WORK/data/usr/lib/lua/luci/model/cbi/wifihaven"
mkdir -p "$WORK/data/usr/lib/lua/luci/view/wifihaven"
mkdir -p "$WORK/data/usr/share/rpcd/acl.d"
mkdir -p "$WORK/data/usr/share/luci/menu.d"
mkdir -p "$WORK/data/usr/libexec/rpcd"

cp "$SCRIPT_DIR/luasrc/controller/wifihaven.lua"          "$WORK/data/usr/lib/lua/luci/controller/"
cp "$SCRIPT_DIR/luasrc/model/cbi/wifihaven/settings.lua"  "$WORK/data/usr/lib/lua/luci/model/cbi/wifihaven/"
cp "$SCRIPT_DIR/luasrc/view/wifihaven/status.htm"         "$WORK/data/usr/lib/lua/luci/view/wifihaven/"
cp "$SCRIPT_DIR/root/usr/share/rpcd/acl.d/luci-app-wifihaven.json" "$WORK/data/usr/share/rpcd/acl.d/"
cp "$SCRIPT_DIR/root/usr/share/luci/menu.d/luci-app-wifihaven.json" "$WORK/data/usr/share/luci/menu.d/"
cp "$SCRIPT_DIR/root/usr/libexec/rpcd/wifihaven"          "$WORK/data/usr/libexec/rpcd/wifihaven"

find "$WORK/data" -type f -exec chmod 0644 {} \;
chmod 0755 "$WORK/data/usr/libexec/rpcd/wifihaven"

(cd "$WORK/data" && tar czf "$WORK/data.tar.gz" .)

rm -f "$OUT_IPK"
ar r "$OUT_IPK" \
    "$WORK/debian-binary" \
    "$WORK/control.tar.gz" \
    "$WORK/data.tar.gz"

echo "Built: $OUT_IPK"
