#!/bin/sh
# Build familydns_<version>-<release>_all.apk (OpenWRT APKv3) without the full SDK.
# The package is pure Lua (PKGARCH:=all → arch=noarch), so no cross-compile.
# Output: openwrt/familydns_<version>-<release>_all.apk
#
# Override version at build time:
#   PKG_VERSION=0.2.0 PKG_RELEASE=1 ./openwrt/build-apk.sh
#
# Approach: build apk-tools v3 from source (alpine/apk-tools, which is the same
# codebase OpenWRT 24.10+ uses) under "$APK_TOOLS_PREFIX" (default $HOME/.cache/
# familydns-apk-tools) and invoke `apk mkpkg`. Considered alternatives:
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
OUT_APK="$SCRIPT_DIR/familydns_${PKG_VERSION}-${PKG_RELEASE}_all.apk"

APK_TOOLS_REPO="${APK_TOOLS_REPO:-https://gitlab.alpinelinux.org/alpine/apk-tools.git}"
APK_TOOLS_REF="${APK_TOOLS_REF:-v3.0.6}"
APK_TOOLS_PREFIX="${APK_TOOLS_PREFIX:-$HOME/.cache/familydns-apk-tools}"
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
mkdir "$WORK/data"
cp -r "$SCRIPT_DIR/files/." "$WORK/data/"

find "$WORK/data/usr/sbin"            -type f -exec chmod 0755 {} \;
find "$WORK/data/etc/init.d"          -type f -exec chmod 0755 {} \;
find "$WORK/data/usr/lib/lua/familydns" -type f -exec chmod 0644 {} \; 2>/dev/null || true
if [ -f "$WORK/data/etc/config/familydns" ]; then
    chmod 0600 "$WORK/data/etc/config/familydns"
fi

# ── post-install script ──────────────────────────────────────────────────────
# Equivalent of the .ipk postinst. The IPKG_INSTROOT guard from build-ipk.sh
# is dropped: apk runs scripts inside the target root directly, and there is
# no equivalent offline-install sentinel in apk v3 that we need to skip on.
cat > "$WORK/post-install" <<'POSTINSTALL'
#!/bin/sh
/etc/init.d/familydns enable
# #308: enable + start the boot default-deny skeleton init so first install
# (no reboot) is protected immediately, and every subsequent boot loads it
# before fw4.
/etc/init.d/familydns-boot enable
/etc/init.d/familydns-boot start 2>/dev/null || true
POSTINSTALL
chmod 0755 "$WORK/post-install"

# ── build .apk ───────────────────────────────────────────────────────────────
rm -f "$OUT_APK"
"$APK_BIN" mkpkg \
    --info "name:familydns" \
    --info "version:${PKG_VERSION}-r${PKG_RELEASE}" \
    --info "description:FamilyDNS router agent (per-device DNS filtering + time limits)" \
    --info "arch:noarch" \
    --info "license:MIT" \
    --info "url:https://github.com/sameerparekh/familydns" \
    --info "maintainer:FamilyDNS <noreply@example.com>" \
    --info "depends:lua libuci-lua luci-lib-jsonc conntrack curl uhttpd-mod-lua" \
    --script "post-install:$WORK/post-install" \
    --files "$WORK/data" \
    --output "$OUT_APK"

echo "Built: $OUT_APK"
