#!/bin/sh
# Shell-level tests for the #2231 dhcp force=1 consent prompt in
# openwrt/install.sh.
#
# OpenWrt's dnsmasq init script runs a rogue-DHCP-server probe on every
# restart (/etc/init.d/dnsmasq line 563: `[ $force -gt 0 ] || dhcp_check`;
# dhcp_check at line 108 runs `udhcpc -n -q -s /bin/true -t 1`, line 121 —
# verified on OpenWrt 25.12.3). On a gateway where dnsmasq IS the DHCP
# server nothing answers, so every restart burns udhcpc's ~3.5s discover
# timeout per dhcp section (measured 3.53s default vs 0.34s with force=1).
# install.sh OFFERS to set `option force 1` with explicit operator consent:
# /etc/config/dhcp is operator-owned and force=1 disables the double-DHCP
# guard, so it must never be set silently.
#
# These specs extract offer_dhcp_force() from install.sh (same sed+stub
# idiom as the #704 sim in install_spec.sh) and drive it against stubbed
# uci/prompt/info to pin the consent semantics:
#   - prompt shown when a non-ignored dhcp section lacks force=1
#   - 'y' sets force=1 on those sections, commits dhcp, prints the revert
#   - default (empty) and 'n' set nothing
#   - non-interactive (WIFIHAVEN_NONINTERACTIVE=1 or no usable TTY) never
#     prompts and never sets
#   - already-forced / ignored sections are skipped
set -e

PASS=0; FAIL=0
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$ROOT/install.sh"

check() {
  if [ "$2" = "ok" ]; then
    printf "  PASS: %s\n" "$1"; PASS=$((PASS + 1))
  else
    printf "  FAIL: %s — %s\n" "$1" "$2"; FAIL=$((FAIL + 1))
  fi
}

[ -f "$SCRIPT" ] || { printf "MISSING: %s\n" "$SCRIPT"; exit 1; }

# install.sh must invoke the offer after enrollment (function def + one
# bare top-level call).
grep -q '^offer_dhcp_force$' "$SCRIPT" \
  && check "#2231 install.sh invokes offer_dhcp_force" ok \
  || check "#2231 install.sh invokes offer_dhcp_force" \
           "no top-level offer_dhcp_force call in install.sh"

# Functional sim: run the extracted offer_dhcp_force() with stubbed
# uci/prompt/info. Side-effects land in a per-case log file.
#   sim <log> <uci-show-output> <uci-kv-state> <answer> <noninteractive> <tty>
sim() {
  _log=$1; _show=$2; _state=$3; _answer=$4; _ni=$5; _tty=$6
  : > "$_log"
  WH_SIM_LOG="$_log" WH_SIM_SHOW="$_show" WH_SIM_STATE="$_state" \
  WH_SIM_ANSWER="$_answer" WIFIHAVEN_NONINTERACTIVE="$_ni" WH_SIM_TTY="$_tty" \
  sh -c '
    set -eu
    TTY=$WH_SIM_TTY
    err() { printf "error: %s\n" "$*" >&2; exit 1; }
    info() { printf "info %s\n" "$*" >> "$WH_SIM_LOG"; }
    prompt() {
      printf "prompt %s\n" "$1" >> "$WH_SIM_LOG"
      eval "$1=\$WH_SIM_ANSWER"
    }
    uci() {
      case "$1" in
        show) printf "%s\n" "$WH_SIM_SHOW" ;;
        -q)
          _key=$3
          _val=$(printf "%s\n" "$WH_SIM_STATE" | sed -n "s/^$_key=//p" | head -n1)
          [ -n "$_val" ] && printf "%s\n" "$_val" || return 1
          ;;
        set)    printf "uci-set %s\n" "$2" >> "$WH_SIM_LOG" ;;
        commit) printf "uci-commit %s\n" "$2" >> "$WH_SIM_LOG" ;;
        *)      true ;;
      esac
    }
    '"$(sed -n '/^offer_dhcp_force()/,/^}/p' "$SCRIPT")"'
    offer_dhcp_force
  ' 2>/dev/null || true
}

LOG=$(mktemp)
trap 'rm -f "$LOG"' EXIT

TWO_SECTIONS="$(printf 'dhcp.lan=dhcp\ndhcp.wan=dhcp')"

# Case 1: interactive, lan unforced, wan ignored, answer 'y'.
sim "$LOG" "$TWO_SECTIONS" "dhcp.wan.ignore=1" "y" "0" "/dev/null"

grep -q '^prompt DHCP_FORCE_ANSWER$' "$LOG" \
  && check "#2231 prompts when a dhcp section lacks force=1" ok \
  || check "#2231 prompts when a dhcp section lacks force=1" \
           "no prompt recorded (log: $(tr '\n' ';' < "$LOG"))"

grep -q '^uci-set dhcp.lan.force=1$' "$LOG" \
  && check "#2231 'y' sets force=1 on the unforced section" ok \
  || check "#2231 'y' sets force=1 on the unforced section" \
           "missing uci set dhcp.lan.force=1"

if grep -q '^uci-set dhcp.wan.force=1$' "$LOG"; then
  check "#2231 skips sections with ignore='1'" "set force on ignored wan section"
else
  check "#2231 skips sections with ignore='1'" ok
fi

grep -q '^uci-commit dhcp$' "$LOG" \
  && check "#2231 'y' commits dhcp" ok \
  || check "#2231 'y' commits dhcp" "missing uci commit dhcp"

grep -q 'uci delete dhcp' "$LOG" \
  && check "#2231 prints the revert command after setting" ok \
  || check "#2231 prints the revert command after setting" \
           "no 'uci delete dhcp' revert hint in output"

# Case 2: default answer (empty → N) must not set anything.
sim "$LOG" "$TWO_SECTIONS" "dhcp.wan.ignore=1" "" "0" "/dev/null"

grep -q '^prompt DHCP_FORCE_ANSWER$' "$LOG" \
  && check "#2231 default-answer run still shows the prompt" ok \
  || check "#2231 default-answer run still shows the prompt" "no prompt recorded"

if grep -q '^uci-set ' "$LOG" || grep -q '^uci-commit ' "$LOG"; then
  check "#2231 default (empty) answer sets nothing" "uci set/commit recorded on default answer"
else
  check "#2231 default (empty) answer sets nothing" ok
fi

# Case 3: explicit 'n' must not set anything.
sim "$LOG" "$TWO_SECTIONS" "dhcp.wan.ignore=1" "n" "0" "/dev/null"
if grep -q '^uci-set ' "$LOG" || grep -q '^uci-commit ' "$LOG"; then
  check "#2231 'n' answer sets nothing" "uci set/commit recorded on 'n'"
else
  check "#2231 'n' answer sets nothing" ok
fi

# Case 4: non-interactive env flag — never prompts, never sets.
sim "$LOG" "$TWO_SECTIONS" "dhcp.wan.ignore=1" "y" "1" "/dev/null"
if grep -q '^prompt ' "$LOG" || grep -q '^uci-set ' "$LOG"; then
  check "#2231 WIFIHAVEN_NONINTERACTIVE=1 never prompts or sets" \
        "prompt or uci set recorded under WIFIHAVEN_NONINTERACTIVE=1"
else
  check "#2231 WIFIHAVEN_NONINTERACTIVE=1 never prompts or sets" ok
fi

# Case 5: no usable TTY — never prompts, never sets (must not hang either;
# the sim would deadlock rather than fail if the function tried to read).
sim "$LOG" "$TWO_SECTIONS" "dhcp.wan.ignore=1" "y" "0" "/nonexistent-tty"
if grep -q '^prompt ' "$LOG" || grep -q '^uci-set ' "$LOG"; then
  check "#2231 unusable TTY never prompts or sets" \
        "prompt or uci set recorded with unusable TTY"
else
  check "#2231 unusable TTY never prompts or sets" ok
fi

# Case 6: two non-ignored unforced sections — 'y' sets force=1 on BOTH
# (pins the loop, not just the single-section path).
sim "$LOG" "$(printf 'dhcp.lan=dhcp\ndhcp.guest=dhcp')" "" "y" "0" "/dev/null"
if grep -q '^uci-set dhcp.lan.force=1$' "$LOG" \
   && grep -q '^uci-set dhcp.guest.force=1$' "$LOG"; then
  check "#2231 'y' sets force=1 on every unforced section" ok
else
  check "#2231 'y' sets force=1 on every unforced section" \
        "expected uci set on both lan and guest (log: $(tr '\n' ';' < "$LOG"))"
fi

# Case 7: idempotent — force=1 already set on every non-ignored section
# skips the prompt entirely.
sim "$LOG" "$TWO_SECTIONS" "$(printf 'dhcp.lan.force=1\ndhcp.wan.ignore=1')" "y" "0" "/dev/null"
if grep -q '^prompt ' "$LOG"; then
  check "#2231 already-forced sections skip the prompt" "prompt shown despite force=1 everywhere"
else
  check "#2231 already-forced sections skip the prompt" ok
fi

# Docs (#2231): the tuning doc must cover the knob with the measured
# numbers and the trade-off.
TUNING_DOC="$ROOT/../docs/router-tuning.md"
grep -q "option force 1" "$TUNING_DOC" \
  && check "#2231 docs/router-tuning.md documents 'option force 1'" ok \
  || check "#2231 docs/router-tuning.md documents 'option force 1'" \
           "missing 'option force 1' section"

grep -q "dhcp_check" "$TUNING_DOC" \
  && check "#2231 docs cite the init-script dhcp_check probe" ok \
  || check "#2231 docs cite the init-script dhcp_check probe" \
           "docs don't cite /etc/init.d/dnsmasq dhcp_check"

printf "\n%d passed, %d failed\n" "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
