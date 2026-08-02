#!/usr/bin/env bash
# Thin shim: auto-discovered by the CI shell-tests job (*.test.sh pattern in
# .github/workflows/ci.yml) and delegates to install_reinstall_cycle_spec.sh,
# which is the canonical home for the #2554 install -> uninstall -> install
# cycle assertions. Same pattern as install_spec.test.sh.
#
# install_reinstall_cycle_spec.sh expects openwrt/ as its working directory.
set -euo pipefail
OPENWRT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
sh "${OPENWRT_DIR}/test/install_reinstall_cycle_spec.sh"
