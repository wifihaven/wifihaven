# shellcheck shell=bash
# Pinned versions for the VM e2e test harness.
#
# Source this from other scripts (`. scripts/vm/versions.sh`) so the
# router-image build, the VM bring-up (#144), and CI all agree on which
# OpenWRT release we are testing against.
#
# Two flavors are supported, selected via PKG_FORMAT (env var):
#
#   ipk (default): OpenWRT 23.05 stable. Image Builder is opkg-based and
#                  consumes .ipk packages. Legacy production target.
#   apk          : OpenWRT 25.12 stable. First apk-native stable release
#                  line; Image Builder ships apk-tools v3 and consumes
#                  .apk packages. (24.10's IB is still opkg-based despite
#                  apk being usable at runtime on a 24.10 system, so 25.12
#                  is the first release where "build image with .apk
#                  pre-installed via the IB" is testable end-to-end.)
#
# Bumping either pin:
#   1. Update OPENWRT_VERSION_IPK or OPENWRT_VERSION_APK below.
#   2. Re-run scripts/vm/build-router-image.sh PKG_FORMAT=<format> from a
#      clean cache. Both flavors fetch the release-dir sha256sums and
#      verify the downloaded tarball against it, so no hash needs to be
#      hand-edited here.
#   3. Re-run the relevant leg of the e2e suite end-to-end.

OPENWRT_VERSION_IPK="23.05.5"
OPENWRT_VERSION_APK="25.12.3"

OPENWRT_TARGET="x86"
OPENWRT_SUBTARGET="64"

# Resolve the active flavor based on PKG_FORMAT. Default: ipk.
PKG_FORMAT="${PKG_FORMAT:-ipk}"
case "$PKG_FORMAT" in
    ipk)
        OPENWRT_VERSION="$OPENWRT_VERSION_IPK"
        # 23.05 ships the IB as xz.
        OPENWRT_IB_EXT="tar.xz"
        ;;
    apk)
        OPENWRT_VERSION="$OPENWRT_VERSION_APK"
        # 25.12 ships the IB as zstd.
        OPENWRT_IB_EXT="tar.zst"
        ;;
    *)
        echo "ERROR: unknown PKG_FORMAT=$PKG_FORMAT (use ipk|apk)" >&2
        return 1 2>/dev/null || exit 1
        ;;
esac

# Major.minor used in the cache filename so the two flavors don't collide.
OPENWRT_VERSION_SHORT="$(echo "$OPENWRT_VERSION" | cut -d. -f1-2)"

OPENWRT_IMAGEBUILDER_TARBALL="openwrt-imagebuilder-${OPENWRT_VERSION}-${OPENWRT_TARGET}-${OPENWRT_SUBTARGET}.Linux-x86_64.${OPENWRT_IB_EXT}"
OPENWRT_RELEASE_URL="https://downloads.openwrt.org/releases/${OPENWRT_VERSION}/targets/${OPENWRT_TARGET}/${OPENWRT_SUBTARGET}"
OPENWRT_IMAGEBUILDER_URL="${OPENWRT_RELEASE_URL}/${OPENWRT_IMAGEBUILDER_TARBALL}"
OPENWRT_SHA256SUMS_URL="${OPENWRT_RELEASE_URL}/sha256sums"

# Image Builder runs inside this container so host toolchain (macOS/Linux)
# doesn't matter. Pinned for reproducibility.
IMAGEBUILDER_DOCKER_IMAGE="debian:bookworm-slim"

OPENWRT_PROFILE="generic"
OPENWRT_BUILT_IMAGE_NAME="openwrt-${OPENWRT_VERSION}-${OPENWRT_TARGET}-${OPENWRT_SUBTARGET}-${OPENWRT_PROFILE}-ext4-combined.img.gz"
