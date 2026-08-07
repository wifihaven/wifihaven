#!/bin/sh
# Functional test for #1848: wifihaven.ws.enabled UCI toggle gates the
# wifihaven-ws procd instance. As of #2608 the DEFAULT is ON — an unset flag
# starts the sidecar — while an explicit `enabled=0` still keeps it off (the
# poll path stays a first-class, operator-selectable transport). The other
# instances must always start regardless (back-compat: the agent's HTTP path is
# unaffected). We mock the procd / UCI shell helpers, source the start_service
# body out of the init script, and assert the ws instance is or is not opened
# based on the fixture.
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
  # $1 = fixture value for wifihaven.ws.enabled ("" means unset → default 1)
  WS_ENABLED_FIXTURE="$1"
  OPENED=""

  # shellcheck disable=SC2317
  config_load() { :; }
  # shellcheck disable=SC2317
  config_get() {
    # usage: config_get <var> <section> <option> <default>
    local _var="$1" _section="$2" _opt="$3" _def="$4"
    # Only override the ws section's `enabled`; everything else (incl. the sni
    # section's enabled) takes its default so the other instances behave normally.
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

# 1. Default (option unset) → ws STARTS (#2608 default-on).
out=$(run_start_service "")
case " $out " in
  *" ws "*) check "default: ws sidecar starts when enabled unset" ok ;;
  *) check "default: ws sidecar starts when enabled unset" "OPENED=[$out]" ;;
esac

# 2. enabled=0 → ws SKIPPED (an explicit opt-out still wins over the default).
out=$(run_start_service "0")
case " $out " in
  *" ws "*) check "enabled=0: ws sidecar skipped" "OPENED=[$out]" ;;
  *) check "enabled=0: ws sidecar skipped" ok ;;
esac

# 3. enabled=1 → ws starts.
out=$(run_start_service "1")
case " $out " in
  *" ws "*) check "enabled=1: ws sidecar starts" ok ;;
  *) check "enabled=1: ws sidecar starts" "OPENED=[$out]" ;;
esac

# 4. The other sidecars start regardless of the ws toggle (back-compat).
missing=""
for inst in agent dns-tail nflog-tail; do
  case " $out " in
    *" $inst "*) : ;;
    *) missing="$missing $inst" ;;
  esac
done
if [ -z "$missing" ]; then
  check "ws on: other sidecars still start" ok
else
  check "ws on: other sidecars still start" "missing=[$missing] OPENED=[$out]"
fi

# 5. Init script must invoke the UCI read in the documented form so a rename of
# the option key is caught here.
grep -q "config_get .* ws enabled '1'" "$INIT" \
  && check "init script reads wifihaven.ws.enabled via config_get, default 1" ok \
  || check "init script reads wifihaven.ws.enabled via config_get, default 1" "not found"

printf "\nResults: %d passed, %d failed\n" "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
