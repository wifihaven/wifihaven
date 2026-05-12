# shellcheck shell=bash
# Pinned versions for the VM e2e test harness.
#
# Source this from other scripts (`. scripts/vm/versions.sh`) so the
# router-image build, the VM bring-up (#144), and CI all agree on which
# OpenWRT release we are testing against.
#
# Bumping the release:
#   1. Update OPENWRT_VERSION below.
#   2. Re-run scripts/vm/build-router-image.sh from a clean cache; it
#      downloads the official sha256sums from the same release directory
#      and verifies the Image Builder tarball against it, so no hash
#      needs to be hand-edited here.
#   3. Re-run the VM e2e suite (#148) end-to-end against the new image.

OPENWRT_VERSION="23.05.5"
OPENWRT_TARGET="x86"
OPENWRT_SUBTARGET="64"

OPENWRT_IMAGEBUILDER_TARBALL="openwrt-imagebuilder-${OPENWRT_VERSION}-${OPENWRT_TARGET}-${OPENWRT_SUBTARGET}.Linux-x86_64.tar.xz"
OPENWRT_RELEASE_URL="https://downloads.openwrt.org/releases/${OPENWRT_VERSION}/targets/${OPENWRT_TARGET}/${OPENWRT_SUBTARGET}"
OPENWRT_IMAGEBUILDER_URL="${OPENWRT_RELEASE_URL}/${OPENWRT_IMAGEBUILDER_TARBALL}"
OPENWRT_SHA256SUMS_URL="${OPENWRT_RELEASE_URL}/sha256sums"

# Image Builder runs inside this container so host toolchain (macOS/Linux)
# doesn't matter. Pinned for reproducibility.
IMAGEBUILDER_DOCKER_IMAGE="debian:bookworm-slim"

# Profile + filename for the x86_64 combined ext4 image that QEMU boots.
# (Image Builder emits this as bin/targets/x86/64/openwrt-${OPENWRT_VERSION}-x86-64-generic-ext4-combined.img.gz)
OPENWRT_PROFILE="generic"
OPENWRT_BUILT_IMAGE_NAME="openwrt-${OPENWRT_VERSION}-${OPENWRT_TARGET}-${OPENWRT_SUBTARGET}-${OPENWRT_PROFILE}-ext4-combined.img.gz"
