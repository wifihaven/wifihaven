#!/usr/bin/env bash
# Build a QEMU-bootable OpenWRT x86_64 image with the familydns-agent ipk
# pre-installed.
#
# Wraps OpenWRT's official Image Builder, run inside a Docker container so
# the host toolchain (Linux or macOS) doesn't matter.
#
# Usage:
#   scripts/vm/build-router-image.sh                # build with locally-built ipk
#   IPK_SOURCE=local   scripts/vm/build-router-image.sh
#   IPK_SOURCE=release scripts/vm/build-router-image.sh   # latest GitHub release
#   IPK_SOURCE=path    IPK_PATH=/abs/path/to.ipk scripts/vm/build-router-image.sh
#
# Output:
#   scripts/vm/.cache/openwrt-familydns.img        (uncompressed, ready for QEMU)
#
# Acceptance test (cf. issue #150):
#   1. This script runs to completion on a clean checkout.
#   2. scripts/vm/router-up.sh --image familydns (provided by #144) boots it.
#   3. opkg list-installed | grep familydns shows the agent.
#   4. logread | grep familydns shows the agent starting up.
#   5. uci show familydns returns the pre-baked defaults.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# shellcheck source=versions.sh
. "$SCRIPT_DIR/versions.sh"

CACHE_DIR="$SCRIPT_DIR/.cache"
DOWNLOAD_DIR="$CACHE_DIR/downloads"
IB_ROOT="$CACHE_DIR/imagebuilder"
STAGING_DIR="$CACHE_DIR/staging"
OUTPUT_IMG="$CACHE_DIR/openwrt-familydns.img"

IPK_SOURCE="${IPK_SOURCE:-local}"
IPK_PATH="${IPK_PATH:-}"

mkdir -p "$DOWNLOAD_DIR" "$STAGING_DIR"

# ── 1. Locate the familydns-agent ipk ────────────────────────────────────────
resolve_ipk() {
    case "$IPK_SOURCE" in
        local)
            echo "==> Building familydns ipk locally" >&2
            (cd "$REPO_ROOT/openwrt" && ./build-ipk.sh >&2)
            ls "$REPO_ROOT"/openwrt/familydns_*.ipk | head -n1
            ;;
        release)
            echo "==> Fetching latest familydns ipk from GitHub Releases" >&2
            local url
            url="$(gh release view --json assets \
                   --jq '.assets[] | select(.name | endswith(".ipk")) | .url' \
                   | head -n1)"
            if [ -z "$url" ]; then
                echo "ERROR: no .ipk asset found on latest release" >&2
                exit 1
            fi
            local out="$DOWNLOAD_DIR/$(basename "$url")"
            gh release download --pattern '*.ipk' --output "$out" --clobber >&2
            echo "$out"
            ;;
        path)
            if [ -z "$IPK_PATH" ] || [ ! -f "$IPK_PATH" ]; then
                echo "ERROR: IPK_SOURCE=path requires IPK_PATH=<file>" >&2
                exit 1
            fi
            echo "$IPK_PATH"
            ;;
        *)
            echo "ERROR: unknown IPK_SOURCE=$IPK_SOURCE (use local|release|path)" >&2
            exit 1
            ;;
    esac
}

IPK_FILE="$(resolve_ipk)"
echo "==> Using ipk: $IPK_FILE"

# ── 2. Download + verify Image Builder ───────────────────────────────────────
IB_TARBALL="$DOWNLOAD_DIR/$OPENWRT_IMAGEBUILDER_TARBALL"
if [ ! -f "$IB_TARBALL" ]; then
    echo "==> Downloading Image Builder ${OPENWRT_VERSION}"
    curl -fsSL -o "$IB_TARBALL" "$OPENWRT_IMAGEBUILDER_URL"
fi

echo "==> Verifying Image Builder sha256"
SUMS_FILE="$DOWNLOAD_DIR/sha256sums"
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
rm -rf "$IB_ROOT"
mkdir -p "$IB_ROOT"
echo "==> Extracting Image Builder"
tar -xf "$IB_TARBALL" -C "$IB_ROOT" --strip-components=1

# ── 4. Stage FILES tree (overlay copied verbatim into the rootfs) ────────────
echo "==> Staging overlay files"
rm -rf "$STAGING_DIR"
mkdir -p "$STAGING_DIR/etc/uci-defaults"
install -m 0755 "$SCRIPT_DIR/uci-defaults/99-familydns" \
    "$STAGING_DIR/etc/uci-defaults/99-familydns"

# ── 5. Drop the familydns ipk into the Image Builder's local packages dir ───
# OpenWRT 23.05's scripts/ipkg-make-index.sh expects .ipk files to be
# tarballs (`tar czf foo.ipk debian-binary control.tar.gz data.tar.gz`),
# but openwrt/build-ipk.sh produces the deb-style `ar` format that opkg
# also accepts when installed directly. Repackage on the fly so the
# Image Builder can index it. (We deliberately do not change
# openwrt/build-ipk.sh — production routers install the ar-format ipk
# directly and bumping the format risks the deploy path.)
mkdir -p "$IB_ROOT/packages"
IPK_CONV_DIR="$(mktemp -d)"
(cd "$IPK_CONV_DIR" && ar x "$IPK_FILE")
tar -C "$IPK_CONV_DIR" -czf "$IB_ROOT/packages/$(basename "$IPK_FILE")" \
    debian-binary control.tar.gz data.tar.gz
rm -rf "$IPK_CONV_DIR"

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

echo "==> Running Image Builder in $IMAGEBUILDER_DOCKER_IMAGE"
docker run --rm \
    -v "$IB_ROOT":/ib \
    -v "$STAGING_DIR":/staging:ro \
    -w /ib \
    "$IMAGEBUILDER_DOCKER_IMAGE" \
    bash -euxo pipefail -c '
        export DEBIAN_FRONTEND=noninteractive
        apt-get update -qq
        apt-get install -y --no-install-recommends \
            build-essential libncurses-dev zlib1g-dev gawk git \
            gettext libssl-dev xsltproc rsync wget unzip file \
            python3 python3-distutils \
            ca-certificates coreutils
        # OpenWRT scripts/ipkg-make-index.sh calls a bare `sha256` binary
        # that is normally built as a host tool but is not in the Image
        # Builder distribution. Shim it to sha256sum (compatible output).
        printf "#!/bin/sh\nexec sha256sum \"\$@\"\n" > /usr/local/bin/sha256
        chmod +x /usr/local/bin/sha256
        # Image Builder writes to bin/; ensure it is clean.
        rm -rf bin/
        # Regenerate the local package index so our ipk is discoverable
        # by opkg. (make package_index would also sign the index with
        # usign, but those host keys havent been generated yet on the
        # very first build — skip the signing step and disable signature
        # checks in repositories.conf below.)
        (cd packages && /ib/scripts/ipkg-make-index.sh . > Packages \
            && gzip -9nc Packages > Packages.gz)
        sed -i "s/^option check_signature/# option check_signature/" repositories.conf
        echo "--- packages/Packages (head) ---"
        head -20 packages/Packages
        # The set of packages to include. Dependencies declared by the ipk
        # (lua, libuci-lua, luci-lib-jsonc, conntrack-tools, curl) are
        # pulled in automatically from the upstream OpenWRT feed.
        make image \
            PROFILE=generic \
            PACKAGES="familydns" \
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
gunzip -c "$BUILT" > "$OUTPUT_IMG"

SIZE_MB=$(( $(stat -f%z "$OUTPUT_IMG" 2>/dev/null || stat -c%s "$OUTPUT_IMG") / 1024 / 1024 ))
echo ""
echo "==> Done."
echo "    Image: $OUTPUT_IMG (${SIZE_MB} MB)"
echo "    Boot with: scripts/vm/router-up.sh --image familydns"
