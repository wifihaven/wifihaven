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

# #2785 widened the match from `metrics.post(` because the call site is now
# `wh_timed_step("metrics_push", metrics.post, …)` — a bare function value, no
# paren. Filter LIVE lines only, the same idiom the `stat -c` guard above uses:
# the agent carries an explanatory comment naming `metrics.post`, and a bare
# `metrics\.post` match would let this #1206 guard pass off that comment after a
# refactor deleted the real call site.
if grep -n 'metrics\.post' "$SCRIPT" | grep -v '^[0-9]*:[[:space:]]*--' >/dev/null; then
  check "metrics push wired into the agent loop (#1206)" ok
else
  check "metrics push wired into the agent loop (#1206)" "registry built but metrics.post never called — push won't happen"
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
  # Window-free on purpose. An earlier version inspected the builder plus the
  # next two lines, looking for the CURL_TIMEOUTS argument — but the window size
  # was arbitrary, so a call whose argument landed a line lower would have
  # reported "ok" and quietly restored the unbounded curl this guard exists to
  # prevent. Pin the splice point in the FORMAT STRING instead: a bounded curl
  # is either `curl -s%s` / `curl -sS%s` (the %s carrying http_bounds.curl_args)
  # or carries the flags literally. Both are on the one line we matched.
  case "$l" in
    *'"curl -s%s'*|*'"curl -sS%s'*|*--connect-timeout*) ;;
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

# (5b) #2782: the sweep's blocklist half is the whole subscribed catalog —
#      161,523 hosts on the prod family router. It cost nothing while `dig` was
#      missing (every resolve failed at exec). With a working resolver it is
#      0.3145 s/host measured on that router, i.e. a 14.1 h full sweep; and
#      since an in-progress sweep is due on EVERY tick and spends its whole
#      slice, enabling it would hold the cooperative loop essentially
#      permanently — the loop that produced #2785 — plus sustained dnsmasq load
#      (#1864) and continuous injection of the catalog into bl_ sets that carry
#      no `size` cap. It is therefore behind an explicit named flag, OFF by
#      default, until #2783 scopes the top-up to hosts actually seen resolved.
if grep -q 'eb_refresh_bl and bl_hosts_by_mac or {}' "$SCRIPT"; then
  check "blocklist members are gated out of the re-resolve sweep (#2782/#2783)" ok
else
  check "blocklist members are gated out of the re-resolve sweep (#2782/#2783)" \
    "the sweep walks the whole blocklist catalog — at 0.3145s/host that is a 14.1h sweep holding the loop permanently"
fi

if grep -q 'uci_get("eb_refresh_blocklists", "0")' "$SCRIPT"; then
  check "the blocklist-sweep flag defaults OFF and is named (#2782)" ok
else
  check "the blocklist-sweep flag defaults OFF and is named (#2782)" \
    "no explicit eb_refresh_blocklists flag — a silent branch, not a named off-switch"
fi

if grep -q 'log.info("eb_refresh: blocklist members' "$SCRIPT"; then
  check "the blocklist-sweep flag is logged at startup (#2782)" ok
else
  check "the blocklist-sweep flag is logged at startup (#2782)" \
    "flag state is not logged — an operator cannot tell which mode the sweep is in (AGENTS.md no-dark-by-default)"
fi

# (6) A policy apply that lands MID-sweep leaves the sweep walking a stale
#     flattened inventory (review of PR #2786). The sweep must not be mutated
#     under its own cursor — indices would shift and hosts would be skipped —
#     so instead every apply path that rebuilds eb_hosts_by_mac /
#     bl_hosts_by_mac marks a re-sweep, and a completed sweep re-arms for NOW
#     rather than for another eb_refresh_interval.
#     The mark is made in ONE place — the shared apply_and_publish seam, right
#     after render.update_shared rebuilds the tables the sweep flattens — not
#     copied to each caller. Three identical copies at three call sites was the
#     drift-by-omission shape: the next apply path added would quietly not mark.
RESWEEP_MARKS=$(grep -c 'ts\.eb_resweep = ts\.eb_resweep or ts\.eb_cursor > 0' "$SCRIPT" || true)
if [ "${RESWEEP_MARKS:-0}" -eq 1 ] && grep -q 'note_inventory_change()' "$SCRIPT"; then
  check "a mid-sweep inventory change is marked once, in the shared apply seam (#2785)" ok
else
  check "a mid-sweep inventory change is marked once, in the shared apply seam (#2785)" \
    "found ${RESWEEP_MARKS:-0} copies of the mark (want exactly 1, called via note_inventory_change from apply_and_publish)"
fi

# The empty-inventory branch has to clear the mark too. An apply that EMPTIED
# the inventory sets it, and with nothing left to sweep the mark would survive
# until the inventory refilled — making that first sweep re-arm immediately for
# no reason. Three sites touch eb_resweep and every one of them is pinned:
# the single mark above, the completion re-arm below, and this clear.
# Whitespace-tolerant on purpose: keying on the exact column alignment would
# fail on a cosmetic realignment and blame it on a missing clear.
RESWEEP_CLEARS=$(grep -cE 'ts\.eb_resweep[[:space:]]*= false' "$SCRIPT" || true)
if [ "${RESWEEP_CLEARS:-0}" -ge 2 ]; then
  check "both sweep exits clear the re-sweep mark (#2785)" ok
else
  check "both sweep exits clear the re-sweep mark (#2785)" \
    "found ${RESWEEP_CLEARS:-0} of the 2 clears (sweep completion, and the empty-inventory branch) — a stale mark makes the next sweep re-arm for no reason"
fi

if grep -q 'ts\.last_eb_refresh_run = ts\.eb_resweep and (mono - eb_refresh_int) or mono' "$SCRIPT"; then
  check "a stale-inventory sweep re-arms immediately on completion (#2785)" ok
else
  check "a stale-inventory sweep re-arms immediately on completion (#2785)" \
    "the re-sweep mark is never consumed — a host blocked mid-sweep waits out another eb_refresh_interval"
fi

# (7) The four tick-liveness knobs form one ordering
#     (slice < http_max < step_budget < stall). Breaking a rung does not break
#     enforcement, it makes the new signals cry wolf — a curl running to its own
#     configured timeout counted as a slow step. Assert the agent checks it at
#     startup rather than mis-reporting silently.
if grep -q 'tick_guard\.check_bounds(' "$SCRIPT"; then
  check "agent validates the tick-liveness knob ordering at startup (#2785)" ok
else
  check "agent validates the tick-liveness knob ordering at startup (#2785)" \
    "no check_bounds call — an operator override can silently invert the budgets"
fi

printf "\n%d passed, %d failed\n" "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
