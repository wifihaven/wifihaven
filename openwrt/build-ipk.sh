#!/bin/sh
# Build wifihaven_<version>_all.ipk without the full OpenWRT SDK.
# The package is pure Lua (PKGARCH:=all), so no cross-compilation is needed.
# Output: openwrt/wifihaven_<version>-<release>_all.ipk
#
# Override version at build time:
#   PKG_VERSION=0.2.0 PKG_RELEASE=1 ./openwrt/build-ipk.sh
set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

PKG_VERSION="${PKG_VERSION:-$(grep '^PKG_VERSION:=' "$SCRIPT_DIR/Makefile" | cut -d= -f2)}"
PKG_RELEASE="${PKG_RELEASE:-$(grep '^PKG_RELEASE:=' "$SCRIPT_DIR/Makefile" | cut -d= -f2)}"
OUT_IPK="$SCRIPT_DIR/wifihaven_${PKG_VERSION}-${PKG_RELEASE}_all.ipk"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ── debian-binary ────────────────────────────────────────────────────────────
printf '2.0\n' > "$WORK/debian-binary"

# ── control.tar.gz ───────────────────────────────────────────────────────────
mkdir "$WORK/ctrl"

cat > "$WORK/ctrl/control" <<EOF
Package: wifihaven
Version: ${PKG_VERSION}-${PKG_RELEASE}
Depends: lua, libuci-lua, luci-lib-jsonc, conntrack, curl, uhttpd-mod-lua, kmod-nfnetlink-log, kmod-nf-log, kmod-nf-log6
Architecture: all
Maintainer: WifiHaven <noreply@example.com>
Description: Agent daemon for WifiHaven. Enforces per-device DNS filtering
 and time limits via dnsmasq + nftables; reports traffic to the WifiHaven API.
EOF

cat > "$WORK/ctrl/postinst" <<'POSTINST'
#!/bin/sh
[ -n "$IPKG_INSTROOT" ] && exit 0

/etc/init.d/wifihaven enable
# #308: enable + start the boot default-deny skeleton init so first install
# (no reboot) is protected immediately, and every subsequent boot loads it
# before fw4.
/etc/init.d/wifihaven-boot enable
/etc/init.d/wifihaven-boot start 2>/dev/null || true
# #542: uhttpd block-page wire-up runs at first boot from
# /etc/uci-defaults/95-wifihaven-uhttpd (shipped in data/).
# Install cron entries. Replace any existing wifihaven entries so upgrades
# migrate the cadence. Kept in sync with Makefile postinst, build-apk.sh.
mkdir -p /etc/crontabs
[ -f /etc/crontabs/root ] && sed -i '/wifihaven-update/d' /etc/crontabs/root
[ -f /etc/crontabs/root ] && sed -i '/wifihaven-rotate-dnsmasq-log/d' /etc/crontabs/root
echo '0 * * * * /usr/sbin/wifihaven-update --jitter' >> /etc/crontabs/root
echo '*/10 * * * * /usr/sbin/wifihaven-rotate-dnsmasq-log' >> /etc/crontabs/root
/etc/init.d/cron enable 2>/dev/null || true
/etc/init.d/cron restart 2>/dev/null || true
POSTINST
chmod 0755 "$WORK/ctrl/postinst"

# Mark UCI config as a conffile so opkg preserves it across upgrades
# (router_token must survive auto-updates; see #131).
cat > "$WORK/ctrl/conffiles" <<'CONFFILES'
/etc/config/wifihaven
CONFFILES

(cd "$WORK/ctrl" && tar czf "$WORK/control.tar.gz" .)

# ── data.tar.gz ───────────────────────────────────────────────────────────────
mkdir "$WORK/data"
cp -r "$SCRIPT_DIR/files/." "$WORK/data/"

# #771: bake PKG_VERSION into a file the lua agent reads at startup so it can
# report itself on every API checkin (X-WifiHaven-Agent-Version header).
mkdir -p "$WORK/data/usr/lib/wifihaven"
echo "$PKG_VERSION" > "$WORK/data/usr/lib/wifihaven/VERSION"

# Fix permissions
find "$WORK/data/usr/sbin"            -type f -exec chmod 0755 {} \;
find "$WORK/data/etc/init.d"          -type f -exec chmod 0755 {} \;
find "$WORK/data/usr/lib/lua/wifihaven" -type f -exec chmod 0644 {} \; 2>/dev/null || true
if [ -f "$WORK/data/etc/config/wifihaven" ]; then
    chmod 0600 "$WORK/data/etc/config/wifihaven"
fi

(cd "$WORK/data" && tar czf "$WORK/data.tar.gz" .)

# ── assemble .ipk (ar archive, same as opkg expects) ─────────────────────────
rm -f "$OUT_IPK"
ar r "$OUT_IPK" \
    "$WORK/debian-binary" \
    "$WORK/control.tar.gz" \
    "$WORK/data.tar.gz"

echo "Built: $OUT_IPK"
