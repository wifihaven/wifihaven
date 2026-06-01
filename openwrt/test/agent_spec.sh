#!/bin/sh
# Shell-level guard for openwrt/files/usr/sbin/wifihaven-agent.
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
SCRIPT="$ROOT/files/usr/sbin/wifihaven-agent"

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

# #903: PR #899 shipped openwrt/files/usr/lib/lua/wifihaven/selfheal.lua with
# unit-test coverage but originally forgot to require + call it from the
# agent, so the heal never ran on startup. Wiring was added in a follow-up.
# Guard both halves so a future refactor can't silently drop them again.
if grep -q '^local selfheal[[:space:]]*=[[:space:]]*require("wifihaven\.selfheal")' "$SCRIPT"; then
  check "selfheal module required at agent startup (#903)" ok
else
  check "selfheal module required at agent startup (#903)" "missing 'require(\"wifihaven.selfheal\")' — startup heal won't run"
fi

if grep -q 'selfheal\.selfheal_cron(' "$SCRIPT"; then
  check "selfheal_cron called at agent startup (#903)" ok
else
  check "selfheal_cron called at agent startup (#903)" "module required but selfheal_cron() never invoked"
fi

# #1206: the metrics module must be required, a registry created, and the
# observability push wired in. Guard each half so a future refactor can't
# silently drop the 60 s push (the dnsmasq_restarts_total telemetry that
# motivated the feature would go dark with no test failure otherwise).
if grep -q '^local metrics[[:space:]]*=[[:space:]]*require("wifihaven\.metrics")' "$SCRIPT"; then
  check "metrics module required at agent startup (#1206)" ok
else
  check "metrics module required at agent startup (#1206)" "missing 'require(\"wifihaven.metrics\")' — no metrics push"
fi

if grep -q 'metrics\.new(' "$SCRIPT"; then
  check "metrics registry created at agent startup (#1206)" ok
else
  check "metrics registry created at agent startup (#1206)" "module required but metrics.new() never called"
fi

if grep -q 'metrics\.post(' "$SCRIPT"; then
  check "metrics push wired into the agent loop (#1206)" ok
else
  check "metrics push wired into the agent loop (#1206)" "registry built but metrics.post() never called — push won't happen"
fi

# The push MUST run on its own timer, decoupled from the ~5 s policy poll
# (#1206 explicitly: a dedicated metrics_report_interval, not coupled to the
# policy cadence). Assert the independent knob + a last_metrics_run scheduler
# slot distinct from last_policy_run.
if grep -q 'metrics_report_interval' "$SCRIPT"; then
  check "metrics push reads its own metrics_report_interval knob (#1206)" ok
else
  check "metrics push reads its own metrics_report_interval knob (#1206)" "no metrics_report_interval — push is coupled to the policy poll"
fi

if grep -q 'last_metrics_run' "$SCRIPT"; then
  check "metrics push has its own scheduler slot (#1206)" ok
else
  check "metrics push has its own scheduler slot (#1206)" "no last_metrics_run — timer not independent of the policy poll"
fi

printf "\n%d passed, %d failed\n" "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
