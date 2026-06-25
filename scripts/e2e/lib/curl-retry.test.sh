#!/usr/bin/env bash
# Unit test for curl-retry.sh (#1961). No network: points WH_CURL_BIN at a
# deterministic mock that replays a per-attempt response sequence, so we can
# assert exactly which transient cases retry and which 4xx cases fail-fast.
#
# Run: scripts/e2e/lib/curl-retry.test.sh  (CI's Shell Tests job discovers
# *.test.sh and runs it automatically).
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

# Mock "curl": ignores its real args, replays the WH_MOCK_SEQ tokens one per
# invocation. Tokens are ';'-separated (err text contains spaces). A token is
# "rc:out:err" — rc is the exit code, out goes to stdout (the http_code for the
# -w shape), err goes to stderr (the -f shape's "curl: (22) ...returned error:
# 50x" message). Colons in err are fine; we only split the first two fields.
cat >"$WORK/mock-curl" <<'MOCK'
#!/usr/bin/env bash
ctr="$WH_MOCK_CTR"; n=$(cat "$ctr" 2>/dev/null || echo 0); n=$((n + 1)); echo "$n" >"$ctr"
IFS=';' read -r -a seq <<<"$WH_MOCK_SEQ"
idx=$((n - 1)); [ "$idx" -ge "${#seq[@]}" ] && idx=$((${#seq[@]} - 1))
tok="${seq[$idx]}"; rc="${tok%%:*}"; rest="${tok#*:}"; out="${rest%%:*}"; err="${rest#*:}"
[ -n "$out" ] && printf '%s' "$out"
[ -n "$err" ] && printf '%s\n' "$err" >&2
exit "$rc"
MOCK
chmod +x "$WORK/mock-curl"

export WH_CURL_BIN="$WORK/mock-curl"
export WH_CURL_BACKOFF_SECS=0
export WH_CURL_MAX_ATTEMPTS=5
# shellcheck source=scripts/e2e/lib/curl-retry.sh
source "$HERE/curl-retry.sh"

# Override sleep so the backoff schedule can be asserted without real waiting.
# Records each requested duration to $SLEEP_LOG; default tests use base 0 so
# this just records 0s harmlessly.
SLEEP_LOG="$WORK/sleeps"; : >"$SLEEP_LOG"
sleep() { printf '%s\n' "$1" >>"$SLEEP_LOG"; }

FAILED=0
check() { if [ "$2" = "$3" ]; then echo "  ✓ $1"; else echo "  ✗ $1: expected '$3', got '$2'" >&2; FAILED=1; fi; }

run() { # $1=mock seq → sets global OUT, RC, ATTEMPTS
  WH_MOCK_CTR="$WORK/ctr"; : >"$WH_MOCK_CTR"; export WH_MOCK_CTR
  export WH_MOCK_SEQ="$1"; shift
  OUT=$(curl "$@" 2>/dev/null) && RC=0 || RC=$?
  ATTEMPTS=$(cat "$WH_MOCK_CTR")
}

echo "▶ status-capture (-w) shape"
# Transient 5xx then success → retried away, final 200 returned.
run "0:502:;0:502:;0:200:" -s -o /dev/null -w '%{http_code}' http://x
check "5xx then 200 retries to success (out)" "$OUT" "200"
check "5xx then 200 retries to success (attempts)" "$ATTEMPTS" "3"

# Expected 4xx → NOT retried, returned immediately on attempt 1.
run "0:400:" -s -o /dev/null -w '%{http_code}' http://x
check "400 is not retried (out)" "$OUT" "400"
check "400 is not retried (attempts)" "$ATTEMPTS" "1"

run "0:401:" -s -o /dev/null -w '%{http_code}' http://x
check "401 is not retried (attempts)" "$ATTEMPTS" "1"

# Persistent 5xx → exhausts attempts, surfaces the 5xx so the assertion fails.
run "0:503:" -s -o /dev/null -w '%{http_code}' http://x
check "persistent 503 exhausts attempts" "$ATTEMPTS" "5"
check "persistent 503 surfaces code" "$OUT" "503"

echo "▶ body/-f shape"
# -f 5xx surfaces as rc 22 + stderr → transient, retried.
run "22::curl: (22) The requested URL returned error: 502;22::curl: (22) The requested URL returned error: 502;0:body:" -fsS http://x
check "-f 5xx retries to success (out)" "$OUT" "body"
check "-f 5xx retries to success (rc)" "$RC" "0"
check "-f 5xx retries to success (attempts)" "$ATTEMPTS" "3"

# -f 4xx surfaces as rc 22 + "error: 400" → NOT transient, fail-fast.
run "22::curl: (22) The requested URL returned error: 400" -fsS http://x
check "-f 4xx fails fast (rc)" "$RC" "22"
check "-f 4xx fails fast (attempts)" "$ATTEMPTS" "1"

# Connection-layer rc (52 empty reply) → transient, retried.
run "52::curl: (52) Empty reply from server;0:body:" -fsS http://x
check "conn-reset rc 52 retries (rc)" "$RC" "0"
check "conn-reset rc 52 retries (attempts)" "$ATTEMPTS" "2"

# Non-transient hard failure (rc 6 couldn't resolve host) → fail-fast.
run "6::curl: (6) Could not resolve host" -fsS http://x
check "non-transient rc 6 fails fast (attempts)" "$ATTEMPTS" "1"

echo "▶ backoff schedule (#1964 — capped exponential)"
# base=1, cap=8, 6 attempts, persistent 5xx → 5 retries with sleeps
# 1,2,4,8,8 (the 5th is 16 capped to 8); the 6th attempt gives up, no sleep.
: >"$SLEEP_LOG"
WH_CURL_BACKOFF_SECS=1 WH_CURL_BACKOFF_MAX=8 WH_CURL_MAX_ATTEMPTS=6 \
  run "0:503:" -s -o /dev/null -w '%{http_code}' http://x
SCHEDULE=$(tr '\n' ' ' <"$SLEEP_LOG" | sed 's/ $//')
check "escalating backoff capped at 8s" "$SCHEDULE" "1 2 4 8 8"

echo "▶ wh_wait_for_health (#1963)"
hrun() { # $1=mock seq → sets HRC and HEALTH_POLLS
  WH_MOCK_CTR="$WORK/ctr-health"; : >"$WH_MOCK_CTR"; export WH_MOCK_CTR
  export WH_MOCK_SEQ="$1"
  WH_HEALTH_INTERVAL_SECS=0 WH_HEALTH_MAX_ATTEMPTS=5 \
    wh_wait_for_health http://x >/dev/null 2>&1 && HRC=0 || HRC=$?
  HEALTH_POLLS=$(cat "$WH_MOCK_CTR")
}

# 503 (still starting) then 200 → gate clears on the second poll.
hrun "0:503:;0:200:"
check "health gate clears on 200 (rc)" "$HRC" "0"
check "health gate stops polling once healthy" "$HEALTH_POLLS" "2"

# Persistent 503 → gate times out but returns 0 (best-effort) after the budget.
hrun "0:503:"
check "health gate times out best-effort (rc)" "$HRC" "0"
check "health gate exhausts its poll budget" "$HEALTH_POLLS" "5"

echo
if [ "$FAILED" = 0 ]; then echo "ALL PASS"; else echo "FAILURES"; exit 1; fi
