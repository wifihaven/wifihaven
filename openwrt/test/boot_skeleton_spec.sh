#!/bin/sh
# Shell-level tests for openwrt/files/usr/share/wifihaven/boot.nft (#308).
#
# This static nft file is loaded at boot by /etc/init.d/wifihaven-boot.
# It establishes `table inet wifihaven_boot` whose sole job is to drop
# forwarded LAN→WAN traffic until the agent's first policy.apply() swaps
# in the real rules atomically (see render_spec.lua #308 tests).
#
# Run from openwrt/:  sh test/boot_skeleton_spec.sh
set -e

PASS=0; FAIL=0
BOOT_NFT="$(cd "$(dirname "$0")/.." && pwd)/files/usr/share/wifihaven/boot.nft"

check() {
  if [ "$2" = "ok" ]; then
    printf "  PASS: %s\n" "$1"; PASS=$((PASS + 1))
  else
    printf "  FAIL: %s — %s\n" "$1" "$2"; FAIL=$((FAIL + 1))
  fi
}

[ -f "$BOOT_NFT" ] || { printf "MISSING: %s\n" "$BOOT_NFT"; exit 1; }

[ -s "$BOOT_NFT" ] \
  && check "boot.nft is non-empty" ok \
  || check "boot.nft is non-empty" "empty file"

grep -q 'table inet wifihaven_boot' "$BOOT_NFT" \
  && check "declares 'table inet wifihaven_boot'" ok \
  || check "declares 'table inet wifihaven_boot'" "not found"

# Forward chain with type filter hook forward — this is the LAN→WAN gate.
grep -q 'type filter hook forward' "$BOOT_NFT" \
  && check "has a filter chain on the forward hook" ok \
  || check "has a filter chain on the forward hook" "not found"

# Policy drop — the entire reason for this file's existence. Default-accept
# here would defeat #308 silently.
grep -E 'type filter hook forward[^;]*;[[:space:]]*policy drop' "$BOOT_NFT" >/dev/null \
  && check "forward chain policy is drop" ok \
  || check "forward chain policy is drop" "must declare 'policy drop' on the forward chain"

# Established/related allowed so existing flows (none on a real reboot, but
# matters for `service wifihaven-boot restart` against a live system) don't
# get torn down.
grep -q 'ct state established,related' "$BOOT_NFT" \
  && check "allows ct state established,related" ok \
  || check "allows ct state established,related" "not found"

# Runs BEFORE fw4 — priority must be less than the default `filter` (0) so
# our drop chain evaluates first. We use `filter - 10` (i.e. -10).
# This isn't strictly necessary for correctness (drop wins over accept
# across chains at the same hook regardless of order), but it's the
# clearer reading for future readers.
grep -E 'priority (filter - 10|-10)' "$BOOT_NFT" >/dev/null \
  && check "forward chain priority < default filter (runs before fw4)" ok \
  || check "forward chain priority < default filter (runs before fw4)" "expected 'priority filter - 10' or 'priority -10'"

# Negative test: forward chain must not contain a bare `accept` that would
# allow forwarded traffic. The only `accept` allowed is the ct-state line
# (which IS a stateful, bounded accept).
if grep -E '^[[:space:]]+accept[[:space:]]*$' "$BOOT_NFT" >/dev/null; then
  check "forward chain has no bare 'accept' line" "found a bare accept — would defeat default-deny"
else
  check "forward chain has no bare 'accept' line" ok
fi

printf "\nresult: %d passed, %d failed\n" "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
