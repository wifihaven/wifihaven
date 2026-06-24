#!/usr/bin/env bash
# Build a QEMU-bootable OpenWRT x86_64 image with the wifihaven agent
# pre-installed.
#
# Wraps OpenWRT's official Image Builder, run inside a Docker container so
# the host toolchain (Linux or macOS) doesn't matter.
#
# Flavor selection (PKG_FORMAT env var, default ipk):
#   ipk → OpenWRT 23.05 (opkg-era) IB + locally-built wifihaven_*.ipk
#   apk → OpenWRT 25.12 (apk-era)  IB + locally-built wifihaven_*.apk
#
# Host prereqs: docker. That's it — for PKG_FORMAT=apk, the apk-tools v3
# bootstrap (build-apk.sh) is run inside the same debian:bookworm-slim
# container we use for the Image Builder, so no host-side
# build-essential/meson/ninja/etc. is needed. This makes the script
# runner-agnostic: GitHub-hosted ubuntu-latest, self-hosted KVM (#685),
# and local Linux dev hosts all work identically as long as docker is
# present. .github/workflows/openwrt-build.yml installs those packages
# directly only because it doesn't shell out to this script.
#
# Usage:
#   scripts/vm/build-router-image.sh                         # ipk (default)
#   PKG_FORMAT=apk scripts/vm/build-router-image.sh          # apk path
#   IPK_SOURCE=release scripts/vm/build-router-image.sh      # latest GH release ipk
#   IPK_SOURCE=path IPK_PATH=/abs/path/to.ipk scripts/vm/build-router-image.sh
#   APK_SOURCE=release scripts/vm/build-router-image.sh PKG_FORMAT=apk
#   APK_SOURCE=path APK_PATH=/abs/path/to.apk PKG_FORMAT=apk scripts/vm/build-router-image.sh
#
# Output:
#   scripts/vm/.cache/openwrt-wifihaven-ipk-23.05.img   (PKG_FORMAT=ipk)
#   scripts/vm/.cache/openwrt-wifihaven-apk-25.12.img   (PKG_FORMAT=apk)
#
# Acceptance test (cf. issues #150, #532):
#   1. This script runs to completion on a clean checkout, for both flavors.
#   2. WH_ROUTER_IMAGE_PATH=<output-img> scripts/vm/router-up.sh boots it.
#   3. The wifihaven package is installed:
#        ipk: opkg list-installed | grep wifihaven
#        apk: apk list --installed | grep wifihaven
#   4. logread | grep wifihaven shows the agent starting.
#   5. uci show wifihaven returns the pre-baked defaults.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# shellcheck source=versions.sh
. "$SCRIPT_DIR/versions.sh"

CACHE_DIR="$SCRIPT_DIR/.cache"
DOWNLOAD_DIR="$CACHE_DIR/downloads"
IB_ROOT="$CACHE_DIR/imagebuilder"
STAGING_DIR="$CACHE_DIR/staging"
OUTPUT_IMG="$CACHE_DIR/openwrt-wifihaven-${PKG_FORMAT}-${OPENWRT_VERSION_SHORT}.img"

mkdir -p "$DOWNLOAD_DIR" "$STAGING_DIR"

# ── 1. Locate the wifihaven package (flavor-specific) ────────────────────────
PKG_SOURCE_DEFAULT="local"
case "$PKG_FORMAT" in
    ipk)
        PKG_SOURCE="${IPK_SOURCE:-$PKG_SOURCE_DEFAULT}"
        PKG_OVERRIDE_PATH="${IPK_PATH:-}"
        PKG_EXT="ipk"
        ;;
    apk)
        PKG_SOURCE="${APK_SOURCE:-$PKG_SOURCE_DEFAULT}"
        PKG_OVERRIDE_PATH="${APK_PATH:-}"
        PKG_EXT="apk"
        ;;
esac

build_apk_in_docker() {
    # openwrt/build-apk.sh bootstraps apk-tools v3 from source. Run it in
    # the same debian:bookworm-slim container we use for the Image Builder
    # so the host doesn't need build-essential/meson/ninja/etc. installed,
    # and so CI runners (self-hosted KVM included) work without per-host
    # apt provisioning. The apk-tools source build is cached in
    # $HOME/.cache/wifihaven-apk-tools (host-side mount) so subsequent
    # runs reuse it.
    local apk_cache="$HOME/.cache/wifihaven-apk-tools"
    mkdir -p "$apk_cache"
    docker run --rm \
        -e APK_TOOLS_PREFIX=/cache/apk-tools \
        -e HOST_UID="$(id -u)" \
        -e HOST_GID="$(id -g)" \
        -v "$apk_cache":/cache/apk-tools \
        -v "$REPO_ROOT":/repo \
        -w /repo \
        "$IMAGEBUILDER_DOCKER_IMAGE" \
        bash -euxo pipefail -c '
            trap "chown -R \"$HOST_UID:$HOST_GID\" /cache/apk-tools /repo/openwrt 2>/dev/null || true" EXIT
            export DEBIAN_FRONTEND=noninteractive
            apt-get update -qq
            apt-get install -y --no-install-recommends \
                build-essential meson ninja-build pkg-config \
                libssl-dev libzstd-dev zlib1g-dev \
                git ca-certificates wget
            cd openwrt && ./build-apk.sh
        ' >&2
}

resolve_pkg() {
    case "$PKG_SOURCE" in
        local)
            echo "==> Building wifihaven .$PKG_EXT locally" >&2
            if [ "$PKG_FORMAT" = "apk" ]; then
                build_apk_in_docker
            else
                (cd "$REPO_ROOT/openwrt" && "./build-${PKG_EXT}.sh" >&2)
            fi
            ls "$REPO_ROOT"/openwrt/wifihaven_*."$PKG_EXT" | head -n1
            ;;
        release)
            echo "==> Fetching latest wifihaven .$PKG_EXT from GitHub Releases" >&2
            local url
            url="$(gh release view --json assets \
                   --jq ".assets[] | select(.name | endswith(\".${PKG_EXT}\")) | .url" \
                   | head -n1)"
            if [ -z "$url" ]; then
                echo "ERROR: no .$PKG_EXT asset found on latest release" >&2
                exit 1
            fi
            local out="$DOWNLOAD_DIR/$(basename "$url")"
            gh release download --pattern "*.${PKG_EXT}" --output "$out" --clobber >&2
            echo "$out"
            ;;
        path)
            if [ -z "$PKG_OVERRIDE_PATH" ] || [ ! -f "$PKG_OVERRIDE_PATH" ]; then
                echo "ERROR: ${PKG_EXT^^}_SOURCE=path requires ${PKG_EXT^^}_PATH=<file>" >&2
                exit 1
            fi
            echo "$PKG_OVERRIDE_PATH"
            ;;
        *)
            echo "ERROR: unknown ${PKG_EXT^^}_SOURCE=$PKG_SOURCE (use local|release|path)" >&2
            exit 1
            ;;
    esac
}

PKG_FILE="$(resolve_pkg)"
echo "==> Using package: $PKG_FILE"

# ── 2. Download + verify Image Builder ───────────────────────────────────────
IB_TARBALL="$DOWNLOAD_DIR/$OPENWRT_IMAGEBUILDER_TARBALL"
if [ ! -f "$IB_TARBALL" ]; then
    echo "==> Downloading Image Builder ${OPENWRT_VERSION}"
    curl -fsSL -o "$IB_TARBALL" "$OPENWRT_IMAGEBUILDER_URL"
fi

echo "==> Verifying Image Builder sha256"
SUMS_FILE="$DOWNLOAD_DIR/sha256sums-${OPENWRT_VERSION}"
curl -fsSL -o "$SUMS_FILE" "$OPENWRT_SHA256SUMS_URL"
EXPECTED_SUM="$(grep -E "[[:space:]]\*?${OPENWRT_IMAGEBUILDER_TARBALL}\$" "$SUMS_FILE" | awk '{print $1}')"
if [ -z "$EXPECTED_SUM" ]; then
    echo "ERROR: sha256 for $OPENWRT_IMAGEBUILDER_TARBALL not found in sha256sums" >&2
    exit 1
fi
ACTUAL_SUM="$(shasum -a 256 "$IB_TARBALL" | awk '{print $1}')"
if [ "$ACTUAL_SUM" != "$EXPECTED_SUM" ]; then
    echo "ERROR: sha256 mismatch for $OPENWRT_IMAGEBUILDER_TARBALL" >&2
    echo "  expected: $EXPECTED_SUM" >&2
    echo "  actual:   $ACTUAL_SUM" >&2
    rm -f "$IB_TARBALL"
    exit 1
fi

# ── 3. Extract Image Builder ─────────────────────────────────────────────────
# 23.05 ships .tar.xz; 25.12 ships .tar.zst. GNU tar auto-detects xz; zstd
# needs the explicit flag (older tar releases don't auto-detect zst).
rm -rf "$IB_ROOT"
mkdir -p "$IB_ROOT"
echo "==> Extracting Image Builder"
case "$OPENWRT_IB_EXT" in
    tar.xz)  tar -xf "$IB_TARBALL" -C "$IB_ROOT" --strip-components=1 ;;
    tar.zst) tar --zstd -xf "$IB_TARBALL" -C "$IB_ROOT" --strip-components=1 ;;
    *)       echo "ERROR: unknown IB extension $OPENWRT_IB_EXT" >&2; exit 1 ;;
esac

# ── 4. Stage FILES tree (overlay copied verbatim into the rootfs) ────────────
echo "==> Staging overlay files"
rm -rf "$STAGING_DIR"
mkdir -p "$STAGING_DIR/etc/uci-defaults"
install -m 0755 "$SCRIPT_DIR/uci-defaults/99-wifihaven" \
    "$STAGING_DIR/etc/uci-defaults/99-wifihaven"

# Bake the test-only SSH key into /etc/dropbear/authorized_keys so the
# orchestrator can SSH in without a console-based key-injection step. Safe
# only for ephemeral VMs; the keypair under scripts/vm/keys/ is documented as
# committed test infra (see scripts/vm/keys/README.md).
mkdir -p "$STAGING_DIR/etc/dropbear"
install -m 0600 "$SCRIPT_DIR/keys/client_test_ed25519.pub" \
    "$STAGING_DIR/etc/dropbear/authorized_keys"

# ── 5. Stage the wifihaven package into the IB's local packages dir ─────────
mkdir -p "$IB_ROOT/packages"
case "$PKG_FORMAT" in
    ipk)
        # OpenWRT 23.05's scripts/ipkg-make-index.sh expects .ipk files to be
        # tarballs (`tar czf foo.ipk debian-binary control.tar.gz data.tar.gz`),
        # but openwrt/build-ipk.sh produces the deb-style `ar` format that opkg
        # also accepts when installed directly. Repackage on the fly so the
        # Image Builder can index it. (We deliberately do not change
        # openwrt/build-ipk.sh — production routers install the ar-format ipk
        # directly and bumping the format risks the deploy path.)
        IPK_CONV_DIR="$(mktemp -d)"
        (cd "$IPK_CONV_DIR" && ar x "$PKG_FILE")
        (cd "$IPK_CONV_DIR" && tar -czf "$IB_ROOT/packages/$(basename "$PKG_FILE")" \
            ./debian-binary ./control.tar.gz ./data.tar.gz)
        rm -rf "$IPK_CONV_DIR"
        ;;
    apk)
        # apk packages are consumed by the IB as-is — no repackaging needed.
        # We do, however, need to rename: openwrt/build-apk.sh emits the
        # ipk-convention filename `<name>_<version>-<release>_<arch>.apk`
        # (e.g. wifihaven_0.1.0-1_all.apk) because that filename pattern is
        # what the GitHub release / upload artifact pipeline keys on. But
        # apk-tools v3's default pkgname-spec is `<name>-<version>.apk`
        # (e.g. wifihaven-0.1.0-r1.apk); the IB's `apk add` lookup uses
        # that pattern at install time, so an unrenamed file gets indexed
        # OK by mkndx but install fails with "package mentioned in index
        # not found (try 'apk update')". Read the version straight from
        # the .apk's ADB metadata so this stays consistent if PKG_RELEASE
        # ever bumps.
        : "${APK_TOOLS_PREFIX:=$HOME/.cache/wifihaven-apk-tools}"
        apk_name="$("$APK_TOOLS_PREFIX/build/src/apk" \
            adbdump "$PKG_FILE" 2>/dev/null \
            | awk '/^  name:/ {n=$2} /^  version:/ {print n"-"$2; exit}')" || apk_name=""
        if [ -z "$apk_name" ]; then
            # Fallback: parse from filename (wifihaven_0.1.0-1_all.apk ->
            # wifihaven-0.1.0-r1). Triggers if adbdump fails for any reason.
            apk_name="$(basename "$PKG_FILE" | sed -E 's/_([0-9.]+)-([0-9]+)_all\.apk/-\1-r\2/')"
        fi
        cp "$PKG_FILE" "$IB_ROOT/packages/${apk_name}.apk"
        echo "==> Staged as $IB_ROOT/packages/${apk_name}.apk"
        ;;
esac

# ── 6. Invoke Image Builder inside a Docker container ────────────────────────
# Image Builder needs a Linux toolchain; running it through Docker means
# this script works identically on Linux and macOS dev machines.
if ! command -v docker >/dev/null 2>&1; then
    echo "ERROR: docker is required (cf. AGENTS.md for daemon health check)" >&2
    exit 1
fi

echo "==> Verifying Docker daemon"
if ! timeout 5 docker info >/dev/null 2>&1; then
    echo "ERROR: docker daemon not responding within 5s — restart Docker Desktop" >&2
    exit 1
fi

echo "==> Running Image Builder in $IMAGEBUILDER_DOCKER_IMAGE (PKG_FORMAT=$PKG_FORMAT)"
HOST_UID=$(id -u)
HOST_GID=$(id -g)

# The set of packages to include. Dependencies declared by the wifihaven
# package (lua, libuci-lua, luci-lib-jsonc, conntrack, curl, uhttpd-mod-lua)
# are pulled in automatically from the upstream OpenWRT feed.
#
# Swap stock dnsmasq for dnsmasq-full so the `ipset=` directives the agent
# renders into /tmp/dnsmasq.d/wifihaven.conf are accepted — plain dnsmasq is
# built without HAVE_IPSET and rejects the config at line 7 (#148 e2e).
#
# uhttpd hosts the local block page on 127.0.0.1:8081 (#303 / #351).
# render.lua DNATs blocked HTTP/80 traffic there; without uhttpd in
# the image, the DNAT lands on a dead port and curl times out
# instead of receiving the block page.
#
# cqueues + luaossl: the wifihaven-ws sidecar's hard runtime deps (the async
# event loop + TLS/crypto the agent lacks, #1845/#1848). They are NOT a package
# DEPENDS of wifihaven (ws is opt-in, default-off — see openwrt/files/etc/config/
# wifihaven), so they are not pulled in automatically. The Gate-2 ws push→apply
# scenario (scripts/e2e/scenarios_fake/test_ws_push_apply.py, #1939) flips
# `wifihaven.ws.enabled=1`, which only starts a *working* sidecar when these are
# present. Both are in the official OpenWrt feeds for Lua 5.1 on the 23.05 (ipk)
# and snapshot (apk) generations the matrix builds (verified by the #1845 spike),
# so the Image Builder pulls them from the upstream feed. e2e image only —
# production routers install these out-of-band when an operator opts into ws.
PACKAGES_LIST="wifihaven -dnsmasq dnsmasq-full uhttpd cqueues luaossl"

# Per-flavor IB prep script — chosen branch is run inside the container.
case "$PKG_FORMAT" in
    ipk)
        IB_PREP_SCRIPT='
        # OpenWRT 23.05 scripts/ipkg-make-index.sh calls a bare `sha256`
        # binary that is normally built as a host tool but is not in the
        # Image Builder distribution. Shim it to sha256sum (compatible
        # output).
        printf "#!/bin/sh\nexec sha256sum \"\$@\"\n" > /usr/local/bin/sha256
        chmod +x /usr/local/bin/sha256
        # Build a minimal Packages index for opkg. We deliberately do not
        # call scripts/ipkg-make-index.sh: it choked on our control file
        # in earlier CI runs with a sed error, and the usign signing step
        # it normally pairs with requires host keys that have not been
        # generated yet on a first IB build. We control the inputs here,
        # so generate the index by hand.
        (cd packages
         : > Packages
         for ipk in *.ipk; do
             tar -xzOf "$ipk" ./control.tar.gz | tar -xzO ./control >> Packages
             size=$(stat -c%s "$ipk")
             sha=$(sha256sum "$ipk" | awk "{print \$1}")
             printf "Filename: %s\nSize: %s\nSHA256sum: %s\n\n" \
                 "$ipk" "$size" "$sha" >> Packages
         done
         gzip -9nc Packages > Packages.gz)
        # Local ipk is unsigned; disable signature checking.
        sed -i "s/^option check_signature/# option check_signature/" repositories.conf
        echo "--- packages/Packages ---"
        cat packages/Packages
        '
        ;;
    apk)
        # 25.12 IB ships staging_dir/host/bin/apk and auto-runs
        # `apk mkndx --allow-untrusted` over packages/*.apk on every
        # `make image` (rules.mk:215), so there is no manual index step.
        # Sanity-check the local apk file is parseable; bail early if not.
        #
        # CONFIG_SIGNATURE_CHECK=y in the stock IB .config — that gates
        # whether the Makefile's APK invocation gets --allow-untrusted
        # (Makefile:103). Without --allow-untrusted, our locally-built
        # unsigned wifihaven.apk is silently excluded from candidates and
        # `make image` fails with "wifihaven (no such package)" despite
        # the package being present in packages.adb. Flip the flag so
        # the local unsigned package resolves. Upstream signed feeds
        # still verify normally (the keys/ dir is untouched).
        IB_PREP_SCRIPT='
        echo "--- local apk file ---"
        ls -la packages/*.apk
        ./staging_dir/host/bin/apk mkndx --allow-untrusted \
            --output packages/packages.adb packages/*.apk
        echo "--- index built ---"
        ls -la packages/packages.adb
        sed -i "s/^CONFIG_SIGNATURE_CHECK=y/# CONFIG_SIGNATURE_CHECK is not set/" .config
        echo "--- .config after sig disable ---"
        grep CONFIG_SIGNATURE_CHECK .config || true
        '
        ;;
esac

docker run --rm \
    -e HOST_UID="$HOST_UID" \
    -e HOST_GID="$HOST_GID" \
    -e IB_PREP_SCRIPT="$IB_PREP_SCRIPT" \
    -e PACKAGES_LIST="$PACKAGES_LIST" \
    -v "$IB_ROOT":/ib \
    -v "$STAGING_DIR":/staging:ro \
    -w /ib \
    "$IMAGEBUILDER_DOCKER_IMAGE" \
    bash -euxo pipefail -c '
        # Cleanup trap: even on build failure, hand the entire imagebuilder
        # tree back to the invoking user. Image Builder writes to bin/,
        # build_dir/, tmp/, dl/, staging_dir/ — anything left root-owned
        # blocks subsequent host-side `rm` and (on CI) breaks the next
        # actions/checkout cleanup with EACCES on stale package / tmp/test.fs
        # files. chown the whole tree to be future-proof against new IB
        # output dirs.
        trap "chown -R \"$HOST_UID:$HOST_GID\" /ib 2>/dev/null || true; chmod -R u+rwX /ib 2>/dev/null || true" EXIT

        export DEBIAN_FRONTEND=noninteractive
        apt-get update -qq
        apt-get install -y --no-install-recommends \
            build-essential libncurses-dev zlib1g-dev gawk git \
            gettext libssl-dev xsltproc rsync wget unzip file \
            python3 python3-distutils \
            ca-certificates coreutils

        # Image Builder writes to bin/; ensure it is clean.
        rm -rf bin/

        # Flavor-specific index prep.
        eval "$IB_PREP_SCRIPT"

        make image \
            PROFILE=generic \
            PACKAGES="$PACKAGES_LIST" \
            FILES=/staging
    '

# ── 7. Extract + decompress the built image ──────────────────────────────────
BUILT="$IB_ROOT/bin/targets/${OPENWRT_TARGET}/${OPENWRT_SUBTARGET}/${OPENWRT_BUILT_IMAGE_NAME}"
if [ ! -f "$BUILT" ]; then
    echo "ERROR: expected build artifact not found: $BUILT" >&2
    echo "Contents of bin/targets/${OPENWRT_TARGET}/${OPENWRT_SUBTARGET}:" >&2
    ls -la "$IB_ROOT/bin/targets/${OPENWRT_TARGET}/${OPENWRT_SUBTARGET}/" >&2 || true
    exit 1
fi

echo "==> Decompressing image to $OUTPUT_IMG"
# gzip returns 2 on warnings — OpenWRT's ext4-combined.img.gz has a few bytes
# of trailing padding past the deflate stream, which trips "trailing garbage
# ignored". The decompressed image itself is correct, so tolerate exit 2.
set +e
gunzip -c "$BUILT" > "$OUTPUT_IMG"
GUNZIP_RC=$?
set -e
if [ "$GUNZIP_RC" -ne 0 ] && [ "$GUNZIP_RC" -ne 2 ]; then
    echo "ERROR: gunzip failed with code $GUNZIP_RC" >&2
    exit "$GUNZIP_RC"
fi

SIZE_MB=$(( $(stat -f%z "$OUTPUT_IMG" 2>/dev/null || stat -c%s "$OUTPUT_IMG") / 1024 / 1024 ))
echo ""
echo "==> Done."
echo "    Image: $OUTPUT_IMG (${SIZE_MB} MB)"
echo "    Boot with: WH_ROUTER_IMAGE_PATH=$OUTPUT_IMG scripts/vm/router-up.sh"
