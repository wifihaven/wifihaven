#!/usr/bin/env bash
# Thin shim: auto-discovered by the CI shell-tests job (*.test.sh pattern in
# .github/workflows/ci.yml) and delegates to update_signature_verify_spec.sh.
set -euo pipefail
OPENWRT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
sh "${OPENWRT_DIR}/test/update_signature_verify_spec.sh"
