#!/usr/bin/env bash
# #383: tests for openwrt/files/usr/lib/wifihaven/generate-block-page-cert.sh.
#
# The script generates a self-signed cert + key for the uhttpd TLS block-page
# listener (127.0.0.1:8443 / [::1]:8443). The cert warning IS the design — see
# docs/architecture.md §7.6.
#
# Auto-discovered by CI via the *.test.sh pattern.
set -euo pipefail

OPENWRT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="${OPENWRT_DIR}/files/usr/lib/wifihaven/generate-block-page-cert.sh"

PASS=0; FAIL=0
check() {
  if [ "${2}" = "ok" ]; then
    printf "  PASS: %s\n" "${1}"; PASS=$((PASS + 1))
  else
    printf "  FAIL: %s — %s\n" "${1}" "${2}"; FAIL=$((FAIL + 1))
  fi
}

if [ ! -x "${SCRIPT}" ] && [ ! -f "${SCRIPT}" ]; then
  printf "MISSING: %s\n" "${SCRIPT}"
  exit 1
fi

if ! command -v openssl >/dev/null 2>&1; then
  printf "SKIP: openssl not available in test env\n"
  exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

CRT="${TMP}/block_page.crt"
KEY="${TMP}/block_page.key"

# First invocation must create both files.
WIFIHAVEN_BLOCK_PAGE_CRT="${CRT}" WIFIHAVEN_BLOCK_PAGE_KEY="${KEY}" \
  sh "${SCRIPT}" >/dev/null 2>&1 \
  && check "first run exits 0" ok \
  || check "first run exits 0" "script failed"

[ -s "${CRT}" ] && check "cert file present and non-empty" ok \
  || check "cert file present and non-empty" "missing ${CRT}"
[ -s "${KEY}" ] && check "key file present and non-empty" ok \
  || check "key file present and non-empty" "missing ${KEY}"

# Cert must be a valid PEM x509 with the expected CN.
if openssl x509 -in "${CRT}" -noout -subject 2>/dev/null | grep -q "CN.*block.wifihaven.local"; then
  check "cert CN is block.wifihaven.local" ok
else
  check "cert CN is block.wifihaven.local" \
    "subject was: $(openssl x509 -in "${CRT}" -noout -subject 2>/dev/null || echo 'unparseable')"
fi

# Cert must be at least 2048-bit RSA so modern browsers don't reject outright.
if openssl x509 -in "${CRT}" -noout -text 2>/dev/null \
    | grep -E "(Public-Key.*204[89]|Public-Key.*4096|RSA Public-Key.*204[89])" >/dev/null; then
  check "cert key length >= 2048 bits" ok
else
  check "cert key length >= 2048 bits" "unable to confirm key length"
fi

# Idempotent: a second run must NOT rotate either file. Assert on cert/key
# *identity* (cert SHA-256 fingerprint + key content hash), NOT mtime. mtime is
# a fragile proxy that this assertion previously used: idempotent runs leave the
# file untouched so the mtime is stable, but a same-second filesystem-timestamp
# window (and the BSD/GNU `stat` fallback) raced it intermittently in CI. The
# real invariant we care about is that the cert was not regenerated — a rotated
# cert would churn the uhttpd TLS listener on every boot — and a fingerprint
# comparison verifies exactly that, with no timing dependency.
CRT_FP_BEFORE="$(openssl x509 -in "${CRT}" -noout -fingerprint -sha256 2>/dev/null)"
KEY_HASH_BEFORE="$(openssl dgst -sha256 "${KEY}" 2>/dev/null)"
WIFIHAVEN_BLOCK_PAGE_CRT="${CRT}" WIFIHAVEN_BLOCK_PAGE_KEY="${KEY}" \
  sh "${SCRIPT}" >/dev/null 2>&1
CRT_FP_AFTER="$(openssl x509 -in "${CRT}" -noout -fingerprint -sha256 2>/dev/null)"
KEY_HASH_AFTER="$(openssl dgst -sha256 "${KEY}" 2>/dev/null)"

if [ "${CRT_FP_BEFORE}" = "${CRT_FP_AFTER}" ] && [ "${KEY_HASH_BEFORE}" = "${KEY_HASH_AFTER}" ]; then
  check "second run is idempotent (cert + key unchanged)" ok
else
  check "second run is idempotent (cert + key unchanged)" \
    "cert ${CRT_FP_BEFORE}→${CRT_FP_AFTER}, key ${KEY_HASH_BEFORE}→${KEY_HASH_AFTER}"
fi

printf "\n%d passed, %d failed\n" "${PASS}" "${FAIL}"
[ "${FAIL}" -eq 0 ]
