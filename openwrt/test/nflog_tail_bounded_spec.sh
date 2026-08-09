#!/bin/sh
# Shell-level checks for openwrt/files/usr/sbin/wifihaven-nflog-tail.
#
# #1864 / AGENTS.md#bounded-tmp-writes: the nflog-tail sidecar appends every
# `wh_drop:` line it reads off `logread -f` to a tmpfs spool. On OpenWRT /tmp is
# tmpfs (RAM), and under a retry-storm the drop-log volume is unbounded — so the
# spool MUST be capped and truncated in place. This is the agent's read channel
# for blocked-flow visibility events; it is bounded by the sidecar itself
# (truncate-on-cap), NOT by the wifihaven-rotate-dnsmasq-log cron (the cron
# rotates the dnsmasq/SNI logs, whose writers hold the fd open). This spec
# pins that the cap + truncate path stays present.
#
# Run from the openwrt/ directory:  sh test/nflog_tail_bounded_spec.sh
set -e

PASS=0; FAIL=0
TAIL="$(cd "$(dirname "$0")/.." && pwd)/files/usr/sbin/wifihaven-nflog-tail"

check() {
  if [ "$2" = "ok" ]; then
    printf "  PASS: %s\n" "$1"; PASS=$((PASS + 1))
  else
    printf "  FAIL: %s — %s\n" "$1" "$2"; FAIL=$((FAIL + 1))
  fi
}

[ -f "$TAIL" ] || { printf "MISSING: %s\n" "$TAIL"; exit 1; }

# 1. Must read a positive byte cap for the spool (defaulted, UCI-overridable).
grep -q 'nflog_spool_max_bytes' "$TAIL" \
  && check "reads a spool byte cap (nflog_spool_max_bytes)" ok \
  || check "reads a spool byte cap (nflog_spool_max_bytes)" "not found"

# 2. Must compare the spool size against the cap (bounded-write guard).
grep -q 'cap_bytes' "$TAIL" \
  && check "compares spool size against cap_bytes" ok \
  || check "compares spool size against cap_bytes" "not found"

# 3. Must truncate in place when over cap — reopen with mode "w" zeroes the
#    file on the same path (the agent detects the shrink and rewinds). A rename
#    would orphan the writer's fd; "w" is the correct in-place truncation.
grep -q 'io.open(out_path, "w")' "$TAIL" \
  && check "truncates the spool in place when over cap" ok \
  || check "truncates the spool in place when over cap" "not found"

# 4. The default cap must be a sane, small positive value (tmpfs is RAM). The
#    default is 262144 (256 KiB); assert a non-zero numeric default is present.
grep -Eq 'nflog_spool_max_bytes",[[:space:]]*"[1-9][0-9]+"' "$TAIL" \
  && check "default cap is a non-zero positive integer" ok \
  || check "default cap is a non-zero positive integer" "not found / not positive"

# 5. #2647: the sidecar must also spool the block-page DNAT records. The NAT
#    path (prerouting) redirects TCP 80/443 to the block page and never reaches
#    the forward hook, so its records carry the `wh_dnat:` prefix rather than
#    `wh_drop:`. Miss it here and the blocks users actually SEE stay invisible
#    in Connection Events — the sidecar's substring prefilter is the first gate
#    the line has to pass. Same spool, same cap: the new lines ride the
#    truncate-on-cap path checked above (AGENTS.md#bounded-tmp-writes).
grep -q 'wh_dnat:' "$TAIL" \
  && check "prefilters block-page DNAT records (wh_dnat:) too (#2647)" ok \
  || check "prefilters block-page DNAT records (wh_dnat:) too (#2647)" "not found"

printf "\nResults: %d passed, %d failed\n" "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
