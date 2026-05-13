#!/bin/sh
# Shell-level guard for openwrt/files/usr/sbin/familydns-agent.
# Run from the openwrt/ directory:  sh test/agent_spec.sh
#
# The agent script is a top-level Lua daemon (no module return), so it
# can't be required from a busted spec. These checks catch regressions
# in behavior that depends on the host environment — specifically that
# we don't reintroduce dependencies on coreutils binaries that stock
# OpenWRT busybox doesn't ship.
set -e

PASS=0; FAIL=0
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$ROOT/files/usr/sbin/familydns-agent"

check() {
  if [ "$2" = "ok" ]; then
    printf "  PASS: %s\n" "$1"; PASS=$((PASS + 1))
  else
    printf "  FAIL: %s — %s\n" "$1" "$2"; FAIL=$((FAIL + 1))
  fi
}

[ -f "$SCRIPT" ] || { printf "MISSING: %s\n" "$SCRIPT"; exit 1; }

# #287: `stat -c %Y` isn't in OpenWRT's busybox build. Calling it via
# io.popen silently fails, leaves the mtime sentinel at 0, and freezes
# the dns-cache lookup table at empty — so #259's hostname attribution
# never fires on a real router.
# Match `stat -c` only outside Lua comments; a `--` ahead of the match means
# it's an explanatory comment (we keep one to document why the dependency is
# forbidden), not a live call.
if grep -n 'stat -c' "$SCRIPT" | grep -v '^[0-9]*:[[:space:]]*--' >/dev/null; then
  check "no dependency on coreutils stat -c" "found live 'stat -c' (broken on busybox)"
else
  check "no dependency on coreutils stat -c" ok
fi

printf "\n%d passed, %d failed\n" "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
