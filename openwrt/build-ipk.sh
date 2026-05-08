#!/bin/sh
# Build familydns_<version>_all.ipk without the full OpenWRT SDK.
# The package is pure Lua (PKGARCH:=all), so no cross-compilation is needed.
# Output: openwrt/familydns_<version>-<release>_all.ipk
#
# Override version at build time:
#   PKG_VERSION=0.2.0 PKG_RELEASE=1 ./openwrt/build-ipk.sh
set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

PKG_VERSION="${PKG_VERSION:-$(grep '^PKG_VERSION:=' "$SCRIPT_DIR/Makefile" | cut -d= -f2)}"
PKG_RELEASE="${PKG_RELEASE:-$(grep '^PKG_RELEASE:=' "$SCRIPT_DIR/Makefile" | cut -d= -f2)}"
OUT_IPK="$SCRIPT_DIR/familydns_${PKG_VERSION}-${PKG_RELEASE}_all.ipk"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ── debian-binary ────────────────────────────────────────────────────────────
printf '2.0\n' > "$WORK/debian-binary"

# ── control.tar.gz ───────────────────────────────────────────────────────────
mkdir "$WORK/ctrl"

cat > "$WORK/ctrl/control" <<EOF
Package: familydns
Version: ${PKG_VERSION}-${PKG_RELEASE}
Depends: lua, luci-lib-jsonc, conntrack-tools, curl
Architecture: all
Maintainer: FamilyDNS <noreply@example.com>
Description: Agent daemon for FamilyDNS. Enforces per-device DNS filtering
 and time limits via dnsmasq + nftables; reports traffic to the FamilyDNS API.
EOF

cat > "$WORK/ctrl/postinst" <<'POSTINST'
#!/bin/sh
[ -n "$IPKG_INSTROOT" ] && exit 0
/etc/init.d/familydns enable
POSTINST
chmod 0755 "$WORK/ctrl/postinst"

(cd "$WORK/ctrl" && tar czf "$WORK/control.tar.gz" .)

# ── data.tar.gz ───────────────────────────────────────────────────────────────
mkdir "$WORK/data"
cp -r "$SCRIPT_DIR/files/." "$WORK/data/"

# Fix permissions
find "$WORK/data/usr/sbin"            -type f -exec chmod 0755 {} \;
find "$WORK/data/etc/init.d"          -type f -exec chmod 0755 {} \;
find "$WORK/data/usr/lib/familydns"   -type f -exec chmod 0644 {} \; 2>/dev/null || true
if [ -f "$WORK/data/etc/config/familydns" ]; then
    chmod 0600 "$WORK/data/etc/config/familydns"
fi

(cd "$WORK/data" && tar czf "$WORK/data.tar.gz" .)

# ── assemble .ipk (ar archive, same as opkg expects) ─────────────────────────
rm -f "$OUT_IPK"
ar r "$OUT_IPK" \
    "$WORK/debian-binary" \
    "$WORK/control.tar.gz" \
    "$WORK/data.tar.gz"

echo "Built: $OUT_IPK"
