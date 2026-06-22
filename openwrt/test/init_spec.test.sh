#!/usr/bin/env bash
# Thin shim: auto-discovered by the CI shell-tests job (*.test.sh pattern in
# .github/workflows/ci.yml) and delegates to init_spec.sh, the canonical home
# for openwrt/files/etc/init.d/wifihaven assertions. Added with #1864 so the
# `procd_set_param nice` checks (agent niced below dnsmasq) gate merges in CI,
# not just `run_tests.sh` locally.
set -euo pipefail
OPENWRT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
sh "${OPENWRT_DIR}/test/init_spec.sh"
