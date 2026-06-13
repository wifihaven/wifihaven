#!/bin/sh
# Functional test for #1654: wifihaven.sni.enabled UCI toggle gates the
# sni-tail procd instance. We mock the procd / UCI shell helpers, source the
# start_service body out of the init script, and assert sni-tail is or is not
# opened based on the SNI_ENABLED_FIXTURE.
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
  # $1 = fixture value for wifihaven.sni.enabled ("" means unset → default)
  SNI_ENABLED_FIXTURE="$1"
  OPENED=""
  LOGGED=""

  # shellcheck disable=SC2317
  config_load() { :; }
  # shellcheck disable=SC2317
  config_get() {
    # usage: config_get <var> <section> <option> <default>
    local _var="$1" _opt="$3" _def="$4"
    if [ "$_opt" = "enabled" ] && [ -n "$SNI_ENABLED_FIXTURE" ]; then
      eval "$_var=\"\$SNI_ENABLED_FIXTURE\""
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
  logger() {
    # capture all args after option flags
    while [ "$#" -gt 0 ]; do
      case "$1" in
        -t) shift 2 ;;
        -*) shift ;;
        *) LOGGED="$LOGGED $1"; shift ;;
      esac
    done
  }

  # Source ONLY the function definitions out of the init script (skip the
  # shebang, which references /etc/rc.common — not present on macOS).
  eval "$(sed -n '/^start_service()/,/^}/p; /^service_triggers()/,/^}/p' "$INIT")"

  start_service
  echo "$OPENED"
}

# 1. Default (option unset) → sni-tail starts (preserves existing behavior)
out=$(run_start_service "")
case " $out " in
  *" sni-tail "*) check "default: sni-tail starts when enabled unset" ok ;;
  *) check "default: sni-tail starts when enabled unset" "OPENED=[$out]" ;;
esac

# 2. enabled=1 → sni-tail starts
out=$(run_start_service "1")
case " $out " in
  *" sni-tail "*) check "enabled=1: sni-tail starts" ok ;;
  *) check "enabled=1: sni-tail starts" "OPENED=[$out]" ;;
esac

# 3. enabled=0 → sni-tail SKIPPED, other sidecars still start
out=$(run_start_service "0")
case " $out " in
  *" sni-tail "*) check "enabled=0: sni-tail skipped" "OPENED=[$out]" ;;
  *) check "enabled=0: sni-tail skipped" ok ;;
esac
missing=""
for inst in agent dns-tail nflog-tail; do
  case " $out " in
    *" $inst "*) : ;;
    *) missing="$missing $inst" ;;
  esac
done
if [ -z "$missing" ]; then
  check "enabled=0: other sidecars still start" ok
else
  check "enabled=0: other sidecars still start" "missing=[$missing] OPENED=[$out]"
fi

# 4. Init script source must reference the toggle key so reviewers can grep it.
grep -q 'sni.*enabled' "$INIT" \
  && check "init script references wifihaven.sni.enabled" ok \
  || check "init script references wifihaven.sni.enabled" "not found"

printf "\nResults: %d passed, %d failed\n" "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
