#!/bin/sh
# #2736: the wifihaven-ws procd instance is started UNCONDITIONALLY.
#
# Replaces init_ws_toggle_spec.sh, which pinned the #1848/#2608
# `wifihaven.ws.enabled` gate. That toggle is gone: with the HTTP snapshot poll
# removed, an opted-out router would have no way to receive policy or report
# usage at all. The positive half of the old spec — "the ws instance is actually
# opened" — has to survive that deletion, and it matters MORE now, because the
# sidecar is the router's only transport and an init-script regression means a
# box that enforces its last snapshot forever and never hears from us again.
#
# We mock the procd / UCI shell helpers, source the start_service body out of
# the init script, and assert on which instances were opened.
set -e

PASS=0; FAIL=0
INIT="$(cd "$(dirname "$0")/.." && pwd)/files/etc/init.d/wifihaven"

check() {
  if [ "$2" = "ok" ]; then
    printf "  PASS: %s\n" "$1"; PASS=$((PASS + 1))
  else
    printf "  FAIL: %s — %s\n" "$1" "$2"; FAIL=$((FAIL + 1))
  fi
}

[ -f "$INIT" ] || { printf "MISSING: %s\n" "$INIT"; exit 1; }

run_start_service() {
  # $1 = fixture value the ws section's `enabled` would return, if anything
  # still read it. Passing a value here is the POINT of the leftover-config
  # case below: a router upgraded from a pre-#2736 package still carries
  # `option enabled '0'` in its conffile, and the init script must ignore it.
  WS_ENABLED_FIXTURE="$1"
  OPENED=""

  # shellcheck disable=SC2317
  config_load() { :; }
  # shellcheck disable=SC2317
  config_get() {
    # usage: config_get <var> <section> <option> <default>
    local _var="$1" _section="$2" _opt="$3" _def="$4"
    if [ "$_section" = "ws" ] && [ "$_opt" = "enabled" ] && [ -n "$WS_ENABLED_FIXTURE" ]; then
      eval "$_var=\"\$WS_ENABLED_FIXTURE\""
    else
      eval "$_var=\"\$_def\""
    fi
  }
  # shellcheck disable=SC2317
  procd_open_instance() { OPENED="$OPENED $1"; }
  # shellcheck disable=SC2317
  procd_set_param() { :; }
  # shellcheck disable=SC2317
  procd_close_instance() { :; }
  # shellcheck disable=SC2317
  procd_add_reload_trigger() { :; }
  # shellcheck disable=SC2317
  logger() { :; }

  eval "$(sed -n '/^start_service()/,/^}/p; /^service_triggers()/,/^}/p' "$INIT")"
  start_service
  echo "$OPENED"
}

# 1. Nothing configured → ws starts.
out=$(run_start_service "")
case " $out " in
  *" ws "*) check "ws sidecar starts with no ws config at all" ok ;;
  *) check "ws sidecar starts with no ws config at all" "OPENED=[$out]" ;;
esac

# 2. A leftover `enabled=0` from a pre-#2736 conffile must NOT keep it off.
#    Upgrades preserve conffiles, so this value survives on real routers; the
#    init script no longer reads it and the sidecar must come up anyway.
out=$(run_start_service "0")
case " $out " in
  *" ws "*) check "a leftover enabled=0 does not suppress the ws sidecar" ok ;;
  *) check "a leftover enabled=0 does not suppress the ws sidecar" "OPENED=[$out]" ;;
esac

# 3. ANCHOR: the other instances still start. Without this, a start_service that
#    silently did nothing at all would satisfy nothing above — but it would also
#    fail here, which is the point.
missing=""
for inst in agent dns-tail nflog-tail; do
  case " $out " in
    *" $inst "*) ;;
    *) missing="$missing $inst" ;;
  esac
done
if [ -z "$missing" ]; then
  check "ANCHOR the other sidecars still start" ok
else
  check "ANCHOR the other sidecars still start" "missing=[$missing] OPENED=[$out]"
fi

# 4. The gate is gone from the source, not merely defaulted to on.
if grep -q 'config_get .*ws enabled' "$INIT"; then
  check "init script no longer reads wifihaven.ws.enabled" "still greps config_get ... ws enabled"
else
  check "init script no longer reads wifihaven.ws.enabled" ok
fi

printf "\n%d passed, %d failed\n" "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
