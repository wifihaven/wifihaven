#!/bin/sh
# #383: generate the self-signed cert + key used by the uhttpd TLS block-page
# listener on 127.0.0.1:8443 / [::1]:8443.
#
# Called from:
#   - setup-uhttpd-block-page.sh (before reloading uhttpd so the TLS listener
#     binds on first install)
#
# Idempotent: if both files already exist and are non-empty, exits 0 without
# touching them. Re-running on a deployed router never rotates the cert.
#
# Trust model — see docs/architecture.md §7.6 and issue #383: we do NOT
# install a CA on managed devices. The browser cert warning IS the design;
# the user clicks through and lands on the block page. CN block.wifihaven.local
# is a hint, not a real DNS name.
#
# Paths are overridable for the test harness (openwrt/test/cert_spec.test.sh).
set -eu

CRT="${WIFIHAVEN_BLOCK_PAGE_CRT:-/etc/wifihaven/block_page.crt}"
KEY="${WIFIHAVEN_BLOCK_PAGE_KEY:-/etc/wifihaven/block_page.key}"
CN="${WIFIHAVEN_BLOCK_PAGE_CN:-block.wifihaven.local}"
DAYS="${WIFIHAVEN_BLOCK_PAGE_DAYS:-3650}"

log() { printf '[generate-block-page-cert] %s\n' "$1" >&2; }

if [ -s "$CRT" ] && [ -s "$KEY" ]; then
  log "cert + key already present at $CRT / $KEY — skipping"
  exit 0
fi

mkdir -p "$(dirname "$CRT")" "$(dirname "$KEY")"

log "generating self-signed cert (CN=$CN, RSA-2048, ${DAYS} days)"
umask 077
openssl req -x509 \
  -newkey rsa:2048 -nodes \
  -keyout "$KEY" \
  -out "$CRT" \
  -days "$DAYS" \
  -subj "/CN=$CN" \
  >/dev/null 2>&1

chmod 600 "$KEY"
chmod 644 "$CRT"
log "wrote $CRT and $KEY"
