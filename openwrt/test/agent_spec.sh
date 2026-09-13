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

if grep -q 'metrics\.post\b' "$SCRIPT"; then
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

# ── #2785: the on_tick loop must never block for minutes ─────────────────────
#
# Prod 2026-09-13: a pushed snapshot sat persisted-but-unapplied for 4m11s while
# both processes stayed RUNNING, and usage/event reporting went silent for the
# same window, because the cooperative loop was blocked inside a synchronous
# curl. Every agent curl ran with NO timeout flag; curl's built-in default
# connect timeout is 300s and there is no default total-transfer cap, so a
# single blackholed request wedges the whole loop. These guards pin the fix.

# (1) Every live curl invocation carries BOTH timeout flags. The liveness anchor
#     matters here: a guard that only asserts "no unbounded curl" passes for
#     free the day someone deletes the curl calls (or renames the helper), so
#     first assert we actually FOUND the invocations we mean to constrain.
CURL_LINES=$(grep -n '"curl ' "$SCRIPT" | grep -v '^[0-9]*:[[:space:]]*--' || true)
CURL_COUNT=$(printf "%s" "$CURL_LINES" | grep -c . || true)
if [ "${CURL_COUNT:-0}" -ge 2 ]; then
  check "agent still builds its curl commands here (liveness anchor for the next check)" ok
else
  check "agent still builds its curl commands here (liveness anchor for the next check)" \
    "expected >=2 curl command builders in the agent, found ${CURL_COUNT:-0} — the timeout guard below would pass vacuously"
fi

UNBOUNDED=""
OLDIFS="$IFS"; IFS="
"
for l in $CURL_LINES; do
  [ -n "$l" ] || continue
  n=${l%%:*}
  # The flags are spliced through a %s whose argument sits on the continuation
  # line, so look at the builder plus the two lines that follow it.
  win=$(sed -n "${n},$((n + 2))p" "$SCRIPT")
  case "$win" in
    *CURL_TIMEOUTS*|*--connect-timeout*) ;;
    *) UNBOUNDED="$UNBOUNDED
$l" ;;
  esac
done
IFS="$OLDIFS"
if [ -z "$UNBOUNDED" ]; then
  check "every agent curl is timeout-bounded (#2785)" ok
else
  check "every agent curl is timeout-bounded (#2785)" \
    "unbounded curl invocation(s):$UNBOUNDED"
fi

# (2) The bounds come from the shared, unit-tested module rather than a literal
#     sprinkled at each call site.
if grep -q 'require("wifihaven\.http_bounds")' "$SCRIPT"; then
  check "http timeout bounds sourced from wifihaven.http_bounds (#2785)" ok
else
  check "http timeout bounds sourced from wifihaven.http_bounds (#2785)" \
    "missing require(\"wifihaven.http_bounds\") — timeouts are hand-rolled per call site"
fi

# (3) tick_guard is required AND wired. Same #903 lesson: a module with unit
#     tests but no call site heals nothing.
if grep -q 'require("wifihaven\.tick_guard")' "$SCRIPT"; then
  check "tick_guard module required at agent startup (#2785)" ok
else
  check "tick_guard module required at agent startup (#2785)" "missing require(\"wifihaven.tick_guard\")"
fi

if grep -q 'tick_guard\.observe(' "$SCRIPT"; then
  check "tick_guard.observe called from the tick loop (#2785)" ok
else
  check "tick_guard.observe called from the tick loop (#2785)" "module required but observe() never called — a stall emits no metric"
fi

if grep -q 'agent_tick_stall_total' "$SCRIPT"; then
  check "agent emits agent_tick_stall_total (#2785)" ok
else
  check "agent emits agent_tick_stall_total (#2785)" "no counted, alertable stall signal"
fi

if grep -q 'agent_slow_step_total' "$SCRIPT"; then
  check "agent emits agent_slow_step_total{step} (#2785)" ok
else
  check "agent emits agent_slow_step_total{step} (#2785)" "no per-step attribution — the next stall is undiagnosable again"
fi

# (4) ORDERING: applying a pushed snapshot must come BEFORE the discretionary
#     network work in on_tick. Pre-#2785 the ws apply-on-push block sat after
#     the policy timer (which fetches the block-page token over the network),
#     so a pushed policy change queued behind a hung curl. Compare line numbers
#     of the two markers inside the agent's single on_tick closure.
WS_APPLY_LINE=$(grep -n 'ws apply: applied pushed snapshot' "$SCRIPT" | head -1 | cut -d: -f1)
TOKEN_LINE=$(grep -n '"block_page_token"' "$SCRIPT" | tail -1 | cut -d: -f1)
METRICS_LINE=$(grep -n '"metrics_push"' "$SCRIPT" | tail -1 | cut -d: -f1)
if [ -n "$WS_APPLY_LINE" ] && [ -n "$TOKEN_LINE" ] && [ -n "$METRICS_LINE" ]; then
  check "found the ws-apply / token-fetch / metrics-push markers (liveness anchor)" ok
  if [ "$WS_APPLY_LINE" -lt "$TOKEN_LINE" ] && [ "$WS_APPLY_LINE" -lt "$METRICS_LINE" ]; then
    check "pushed-snapshot apply runs before the tick's network steps (#2785)" ok
  else
    check "pushed-snapshot apply runs before the tick's network steps (#2785)" \
      "ws apply at line $WS_APPLY_LINE runs after token fetch ($TOKEN_LINE) / metrics push ($METRICS_LINE) — a hung curl delays the apply"
  fi
else
  check "found the ws-apply / token-fetch / metrics-push markers (liveness anchor)" \
    "markers missing (ws_apply=$WS_APPLY_LINE token=$TOKEN_LINE metrics=$METRICS_LINE) — the ordering check below cannot run"
fi

# (5) The eb_/bl_ re-resolve sweep (#1658) is the measured root cause: an
#     unsliced pass over every subscribed blocklist member host, two dig forks
#     each, inside the cooperative loop, every 1800s. It must be time-boxed and
#     resumed from a cursor, and an in-progress sweep must continue on the next
#     TICK rather than waiting out another full cadence.
if grep -q 'deadline_seconds = eb_refresh_slice' "$SCRIPT"; then
  check "eb_refresh sweep is time-boxed per tick (#2785)" ok
else
  check "eb_refresh sweep is time-boxed per tick (#2785)" \
    "no deadline_seconds on the eb_refresh call — one pass walks the whole inventory and stalls the loop"
fi

if grep -q 'start_index      = ts\.eb_cursor' "$SCRIPT" && grep -q 'ts\.eb_cursor = stats\.next_index' "$SCRIPT"; then
  check "eb_refresh sweep resumes from a cursor (#2785)" ok
else
  check "eb_refresh sweep resumes from a cursor (#2785)" \
    "no cursor round-trip — a time-boxed sweep without one would only ever refresh the first slice"
fi

if grep -q 'ts\.eb_cursor > 0 or (mono - ts\.last_eb_refresh_run) >= eb_refresh_int' "$SCRIPT"; then
  check "an in-progress eb_refresh sweep continues on the next tick (#2785)" ok
else
  check "an in-progress eb_refresh sweep continues on the next tick (#2785)" \
    "sweep continuation is gated on the 1800s cadence — slicing would then cost coverage"
fi

if grep -q 'eb_refresh_inventory_hosts' "$SCRIPT"; then
  check "agent reports the sweep inventory size (#2785)" ok
else
  check "agent reports the sweep inventory size (#2785)" "no capacity signal for the sweep"
fi

printf "\n%d passed, %d failed\n" "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
