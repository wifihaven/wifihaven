#!/bin/sh
# Shell-level smoke tests for openwrt/files/etc/init.d/familydns-boot (#308).
#
# The boot skeleton must load *before* fw4 brings the firewall up, so this
# init script runs at START=19 (fw4 is START=20). Its job is to install the
# `inet familydns_boot` default-deny table from /usr/share/familydns/boot.nft
# so that no LAN→WAN traffic forwards until the agent runs policy.apply()
# (which atomically swaps the skeleton out — see render_spec.lua #308 tests).
#
# Run from openwrt/:  sh test/init_boot_spec.sh
set -e

PASS=0; FAIL=0
INIT="$(cd "$(dirname "$0")/.." && pwd)/files/etc/init.d/familydns-boot"

check() {
  if [ "$2" = "ok" ]; then
    printf "  PASS: %s\n" "$1"; PASS=$((PASS + 1))
  else
    printf "  FAIL: %s — %s\n" "$1" "$2"; FAIL=$((FAIL + 1))
  fi
}

[ -f "$INIT" ] || { printf "MISSING: %s\n" "$INIT"; exit 1; }

[ -x "$INIT" ] \
  && check "init script is executable" ok \
  || check "init script is executable" "missing +x bit"

grep -q '/etc/rc.common' "$INIT" \
  && check "sources /etc/rc.common" ok \
  || check "sources /etc/rc.common" "not found"

# START=19 — must run BEFORE fw4 (START=20). If this number is >= 20, the
# fw4 firewall comes up first with its default-accept forward policy and
# packets can leak in the gap before familydns-boot loads. This is the
# entire point of the issue (#308).
grep -q '^START=19$' "$INIT" \
  && check "START=19 (runs before fw4 START=20)" ok \
  || check "START=19 (runs before fw4 START=20)" "must declare START=19 exactly"

grep -q '^start()' "$INIT" \
  && check "defines start()" ok \
  || check "defines start()" "not found"

# Must load the static boot ruleset.
grep -q 'nft -f /usr/share/familydns/boot.nft' "$INIT" \
  && check "loads /usr/share/familydns/boot.nft via nft -f" ok \
  || check "loads /usr/share/familydns/boot.nft via nft -f" "expected exact `nft -f /usr/share/familydns/boot.nft` invocation"

# Deliberately no stop action that removes the skeleton — the skeleton is
# handed over atomically by the agent's first policy.apply(); a manual
# `service familydns-boot stop` after that point is a no-op. We still allow
# a stop() definition (rc.common quirk), but it must not delete the table
# while the agent is depending on its absence to install the runtime table.
if grep -q '^stop()' "$INIT"; then
  grep -q 'stop().*nft.*delete table' "$INIT" \
    && check "stop() does not remove the skeleton (only the agent owns the swap)" "found nft delete in stop()" \
    || check "stop() does not remove the skeleton (only the agent owns the swap)" ok
else
  check "no stop() defined (skeleton removal is exclusively via atomic agent swap)" ok
fi

printf "\nresult: %d passed, %d failed\n" "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
