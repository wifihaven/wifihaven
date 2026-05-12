#!/bin/sh
# Shell-level smoke tests for openwrt/files/etc/init.d/familydns
# Run from the openwrt/ directory:  sh test/init_spec.sh
set -e

PASS=0; FAIL=0
INIT="$(cd "$(dirname "$0")/.." && pwd)/files/etc/init.d/familydns"

check() {
  if [ "$2" = "ok" ]; then
    printf "  PASS: %s\n" "$1"; PASS=$((PASS + 1))
  else
    printf "  FAIL: %s — %s\n" "$1" "$2"; FAIL=$((FAIL + 1))
  fi
}

[ -f "$INIT" ] || { printf "MISSING: %s\n" "$INIT"; exit 1; }

# 1. Executable bit
[ -x "$INIT" ] \
  && check "init script is executable" ok \
  || check "init script is executable" "missing +x bit"

# 2. Must source /etc/rc.common (procd init contract)
grep -q '/etc/rc.common' "$INIT" \
  && check "sources /etc/rc.common" ok \
  || check "sources /etc/rc.common" "not found"

# 3. Must opt into procd
grep -q 'USE_PROCD=1' "$INIT" \
  && check "declares USE_PROCD=1" ok \
  || check "declares USE_PROCD=1" "not found"

# 4. Must define start_service()
grep -q 'start_service()' "$INIT" \
  && check "defines start_service()" ok \
  || check "defines start_service()" "not found"

# 5. start_service must set the command to familydns-agent
grep -q 'familydns-agent' "$INIT" \
  && check "command points to familydns-agent" ok \
  || check "command points to familydns-agent" "not found"

# 6. Must configure procd_set_param respawn (auto-restart on crash)
grep -q 'procd_set_param respawn' "$INIT" \
  && check "sets procd_set_param respawn" ok \
  || check "sets procd_set_param respawn" "not found"

# 7. Must define service_triggers() for UCI-triggered reload
grep -q 'service_triggers()' "$INIT" \
  && check "defines service_triggers()" ok \
  || check "defines service_triggers()" "not found"

# 8. Reload trigger must reference the familydns UCI section
grep -q 'procd_add_reload_trigger.*familydns' "$INIT" \
  && check "reload trigger references familydns UCI section" ok \
  || check "reload trigger references familydns UCI section" "not found"

# 9. START/STOP ordering values must be present
grep -q 'START=' "$INIT" \
  && check "declares START= ordering" ok \
  || check "declares START= ordering" "not found"

grep -q 'STOP=' "$INIT" \
  && check "declares STOP= ordering" ok \
  || check "declares STOP= ordering" "not found"

# 10. stderr logging must be enabled (so procd captures agent errors)
grep -q 'procd_set_param stderr' "$INIT" \
  && check "enables stderr logging via procd" ok \
  || check "enables stderr logging via procd" "not found"

# 11. stdout logging must also be enabled (#228: agent stdout was previously
# discarded, leaving `logread -f | grep familydns` empty)
grep -q 'procd_set_param stdout' "$INIT" \
  && check "enables stdout logging via procd" ok \
  || check "enables stdout logging via procd" "not found"

printf "\nResults: %d passed, %d failed\n" "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
