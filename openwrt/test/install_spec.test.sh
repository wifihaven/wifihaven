#!/usr/bin/env bash
# Thin shim: auto-discovered by the CI shell-tests job (*.test.sh pattern in
# .github/workflows/ci.yml) and delegates to install_spec.sh, which is the
# canonical home for openwrt/install.sh assertions.
#
# install_spec.sh expects to be invoked with openwrt/ as its working directory.
set -euo pipefail
OPENWRT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
sh "${OPENWRT_DIR}/test/install_spec.sh"
