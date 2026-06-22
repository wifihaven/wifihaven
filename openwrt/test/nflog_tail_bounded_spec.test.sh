#!/usr/bin/env bash
# Thin shim: auto-discovered by the CI shell-tests job (*.test.sh pattern in
# .github/workflows/ci.yml) and delegates to nflog_tail_bounded_spec.sh, the
# canonical home for the wifihaven-nflog-tail bounded-write assertions (#1864).
set -euo pipefail
OPENWRT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
sh "${OPENWRT_DIR}/test/nflog_tail_bounded_spec.sh"
