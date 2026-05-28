#!/bin/sh
# Build wifihaven_<version>-<release>_all.apk (OpenWRT APKv3) without the full SDK.
# The package is pure Lua (PKGARCH:=all → arch=noarch), so no cross-compile.
# Output: openwrt/wifihaven_<version>-<release>_all.apk
#
# Override version at build time:
#   PKG_VERSION=0.2.0 PKG_RELEASE=1 ./openwrt/build-apk.sh
#
# Approach: build apk-tools v3 from source (alpine/apk-tools, which is the same
# codebase OpenWRT 24.10+ uses) under "$APK_TOOLS_PREFIX" (default $HOME/.cache/
# wifihaven-apk-tools) and invoke `apk mkpkg`. Considered alternatives:
#   - OpenWRT SDK Docker image: heavy (~1GB), needs Docker, harder for CI.
#   - Prebuilt apk-tools-static: no first-party static build is published yet
#     for x86_64 v3; building from source is more reliable.
# Building from source on a stock Ubuntu runner takes ~30s and only needs
# meson/ninja/libssl-dev/libzstd-dev (apt-get installable, unprivileged build).
#
# Linux-only (build dependencies assume apt/Debian-family). macOS is out of
# scope for now — run this in CI or a Linux VM/container.
set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

PKG_VERSION="${PKG_VERSION:-$(grep '^PKG_VERSION:=' "$SCRIPT_DIR/Makefile" | cut -d= -f2)}"
PKG_RELEASE="${PKG_RELEASE:-$(grep '^PKG_RELEASE:=' "$SCRIPT_DIR/Makefile" | cut -d= -f2)}"
OUT_APK="$SCRIPT_DIR/wifihaven_${PKG_VERSION}-${PKG_RELEASE}_all.apk"

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
        # -Durl_backend=wget avoids needing libfetch on Ubuntu.
        # -Ddocs/help/zstd disabled where not strictly required for `mkpkg`.
        # --reconfigure only on re-runs; meson 1.0.x (Debian bookworm)
        # errors out on first-run --reconfigure with "Directory does not
        # contain a valid build tree". Newer meson is tolerant.
        if [ -f "$APK_TOOLS_PREFIX/build/build.ninja" ]; then
            meson_reconfigure="--reconfigure"
        else
            meson_reconfigure=""
        fi
        meson setup $meson_reconfigure \
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
mkdir "$WORK/data"
cp -r "$SCRIPT_DIR/files/." "$WORK/data/"

# #771: bake PKG_VERSION into a file the lua agent reads at startup so it can
# report itself on every API checkin (X-WifiHaven-Agent-Version header).
mkdir -p "$WORK/data/usr/lib/wifihaven"
echo "$PKG_VERSION" > "$WORK/data/usr/lib/wifihaven/VERSION"

find "$WORK/data/usr/sbin"            -type f -exec chmod 0755 {} \;
find "$WORK/data/etc/init.d"          -type f -exec chmod 0755 {} \;
find "$WORK/data/usr/lib/lua/wifihaven" -type f -exec chmod 0644 {} \; 2>/dev/null || true
if [ -f "$WORK/data/etc/config/wifihaven" ]; then
    chmod 0600 "$WORK/data/etc/config/wifihaven"
fi

# ── post-install script ──────────────────────────────────────────────────────
# Equivalent of the .ipk postinst. The IPKG_INSTROOT guard from build-ipk.sh
# is dropped: apk runs scripts inside the target root directly, and there is
# no equivalent offline-install sentinel in apk v3 that we need to skip on.
cat > "$WORK/post-install" <<'POSTINSTALL'
#!/bin/sh

/etc/init.d/wifihaven enable
# #308: enable + start the boot default-deny skeleton init so first install
# (no reboot) is protected immediately, and every subsequent boot loads it
# before fw4.
/etc/init.d/wifihaven-boot enable
/etc/init.d/wifihaven-boot start 2>/dev/null || true
# #542: uhttpd block-page wire-up runs at first boot from
# /etc/uci-defaults/95-wifihaven-uhttpd (shipped in data/).

# #869: Install cron entries. Replace any existing wifihaven entries so
# upgrades migrate the cadence. Kept in sync with trigger below and
# openwrt/Makefile postinst, build-ipk.sh.
mkdir -p /etc/crontabs
[ -f /etc/crontabs/root ] && sed -i '/wifihaven-update/d' /etc/crontabs/root
[ -f /etc/crontabs/root ] && sed -i '/wifihaven-rotate-dnsmasq-log/d' /etc/crontabs/root
echo '0 4 * * * /usr/sbin/wifihaven-update' >> /etc/crontabs/root
echo '*/10 * * * * /usr/sbin/wifihaven-rotate-dnsmasq-log' >> /etc/crontabs/root
/etc/init.d/cron enable 2>/dev/null || true
/etc/init.d/cron restart 2>/dev/null || true
POSTINSTALL
chmod 0755 "$WORK/post-install"

# ── trigger script ───────────────────────────────────────────────────────────
# #898: apk-tools v3 skips `post-install` on `apk add --force-reinstall` when
# the package version hasn't changed, so a manual reinstall to "fix"
# /etc/crontabs/root would otherwise be a no-op. A trigger script keyed on
# /etc/crontabs fires whenever a package install/upgrade/reinstall touches
# that directory — and database.c sets ipkg->run_all_triggers=1 on every
# install of our own package, so reinstalling ourselves is enough to fire
# this even if /etc/crontabs was untouched on disk.
# Mirrors the cron-installation block in post-install above and in
# openwrt/Makefile (.ipk postinst); keep all three in sync.
cat > "$WORK/trigger" <<'TRIGGER'
#!/bin/sh
mkdir -p /etc/crontabs
[ -f /etc/crontabs/root ] && sed -i '/wifihaven-update/d' /etc/crontabs/root
[ -f /etc/crontabs/root ] && sed -i '/wifihaven-rotate-dnsmasq-log/d' /etc/crontabs/root
echo '0 4 * * * /usr/sbin/wifihaven-update' >> /etc/crontabs/root
echo '*/10 * * * * /usr/sbin/wifihaven-rotate-dnsmasq-log' >> /etc/crontabs/root
/etc/init.d/cron enable 2>/dev/null || true
/etc/init.d/cron restart 2>/dev/null || true
TRIGGER
chmod 0755 "$WORK/trigger"

# ── build .apk ───────────────────────────────────────────────────────────────
rm -f "$OUT_APK"
"$APK_BIN" mkpkg \
    --info "name:wifihaven" \
    --info "version:${PKG_VERSION}-r${PKG_RELEASE}" \
    --info "description:WifiHaven router agent (per-device DNS filtering + time limits)" \
    --info "arch:noarch" \
    --info "license:MIT" \
    --info "url:https://github.com/wifihaven/wifihaven" \
    --info "maintainer:WifiHaven <noreply@example.com>" \
    --info "depends:lua libuci-lua luci-lib-jsonc conntrack curl uhttpd-mod-lua kmod-nft-log" \
    --script "post-install:$WORK/post-install" \
    --script "trigger:$WORK/trigger" \
    --trigger "/etc/crontabs" \
    --files "$WORK/data" \
    --output "$OUT_APK"

echo "Built: $OUT_APK"
