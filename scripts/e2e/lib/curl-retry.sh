# shellcheck shell=bash
# curl-retry.sh — sourced by the staging smoke scripts (#1961).
#
# Why this exists: Gate 1's staging smoke (scripts/e2e-router.sh, scripts/
# e2e-tests.sh) bursts a large, growing number of requests at the starter-tier
# staging API. Staging intermittently returns a transient 502/503/504 on
# cold-start / resource pressure (render.yaml notes `starter` is the floor), so
# a single random server 5xx anywhere in the burst failed the whole gate even
# though every assertion is correct. This wraps `curl` so transient server-side
# and connection-layer failures retry with short backoff before failing.
#
# CRITICAL: this only retries SERVER failures (5xx) and connection-layer
# failures (couldn't-connect / timeout / empty-reply / conn-reset). It does NOT
# retry a 4xx — those are real test outcomes (e.g. "expected 400 for unknown
# bucket", "wrong password → 401", "missing auth → 401") and must fail-fast.
# Retry lives at the transport layer; assertion logic is untouched.
#
# The override is transparent: every existing `curl ...` call in the sourcing
# script gets retry-on-transient for free, for both call shapes the smoke uses:
#   1. body/`-f` capture:   curl -fsS ... >file   |   curl -fsS ... | _py
#   2. status capture:      CODE=$(curl -s -o /dev/null -w '%{http_code}' ...)

# Tunables (env-overridable; tests set backoff to 0 for speed).
#
# Budget sizing (#1964): a single retry round of 5 × 1s (~4s) was too short —
# the starter-tier staging API throws transient 502 *bursts* under the smoke's
# request load that outlast 4s, so Gate 1 still flaked after #1962. Backoff now
# escalates (capped exponential: base, 2·base, 4·base … capped at BACKOFF_MAX),
# and the default 8-attempt budget waits 1+2+4+8+8+8+8 ≈ 39s total before
# giving up — enough to ride out a transient burst without masking a genuine
# outage (a persistent 5xx still fails the gate, just ~39s later).
WH_CURL_MAX_ATTEMPTS="${WH_CURL_MAX_ATTEMPTS:-8}"
WH_CURL_BACKOFF_SECS="${WH_CURL_BACKOFF_SECS:-1}"   # base; doubles each retry
WH_CURL_BACKOFF_MAX="${WH_CURL_BACKOFF_MAX:-8}"     # per-sleep cap (seconds)
# Indirection so the unit test can point this at a deterministic mock.
WH_CURL_BIN="${WH_CURL_BIN:-curl}"

# Connection-layer curl exit codes — no HTTP status was ever received, so the
# request never reached the application and is always safe to retry:
#   7  couldn't connect       28 operation timeout
#   35 SSL connect error      52 empty reply from server
#   56 recv failure (connection reset)
_wh_curl_transient_rc() {
  case "$1" in
    7 | 28 | 35 | 52 | 56) return 0 ;;
    *) return 1 ;;
  esac
}

_wh_curl_5xx() {
  case "$1" in
    502 | 503 | 504) return 0 ;;
    *) return 1 ;;
  esac
}

# curl — drop-in override. `command "$WH_CURL_BIN"` reaches the real binary
# (bypassing this function); the function name shadows it for callers.
curl() {
  local attempt=1 rc out code err has_w=0 a
  for a in "$@"; do
    case "$a" in
      -w | --write-out)
        has_w=1
        break
        ;;
    esac
  done

  while :; do
    if [ "$has_w" = 1 ]; then
      # Status-capture shape: the caller reads curl's stdout (the http_code
      # write-out; body goes to -o/dev/null). Buffer it so we can inspect the
      # status and retry a transient 5xx, then re-emit it verbatim. `|| rc=$?`
      # keeps `set -e` from aborting on a failed request.
      out=$(command "$WH_CURL_BIN" "$@") && rc=0 || rc=$?
      code=$(printf '%s' "$out" | grep -oE '[0-9]{3}' | tail -1) || true
      if [ "$rc" -eq 0 ]; then
        # curl itself succeeded; retry only when the status is a 5xx.
        _wh_curl_5xx "$code" || { printf '%s' "$out"; return 0; }
      else
        # curl failed at the connection layer; retry only the transient set.
        _wh_curl_transient_rc "$rc" || { printf '%s' "$out"; return "$rc"; }
      fi
    else
      # Body/`-f` shape: pass curl's stdout straight through (fd 3) and capture
      # only stderr so we can classify the failure and re-emit it on giving up.
      { err=$(command "$WH_CURL_BIN" "$@" 2>&1 1>&3) && rc=0 || rc=$?; } 3>&1
      if [ "$rc" -eq 0 ]; then
        return 0
      fi
      # With -f a 5xx surfaces as rc 22 + "...returned error: 50x" on stderr.
      # Retry that and the connection-layer set; fail-fast on a real 4xx.
      if ! _wh_curl_transient_rc "$rc" &&
        ! printf '%s' "$err" | grep -qE 'error: 50[0-9]'; then
        printf '%s\n' "$err" >&2
        return "$rc"
      fi
    fi

    if [ "$attempt" -ge "$WH_CURL_MAX_ATTEMPTS" ]; then
      echo "[curl-retry] giving up after $attempt attempts (rc=$rc code=${code:-n/a})" >&2
      if [ "$has_w" = 1 ]; then
        printf '%s' "$out"
        return "$rc"
      fi
      printf '%s\n' "${err:-}" >&2
      return "$rc"
    fi
    # Capped exponential backoff: base · 2^(attempt-1), clamped to BACKOFF_MAX.
    # With base 0 (tests) every sleep is 0, so the attempt-count assertions stay
    # deterministic and fast.
    local backoff=$((WH_CURL_BACKOFF_SECS * (1 << (attempt - 1))))
    [ "$backoff" -gt "$WH_CURL_BACKOFF_MAX" ] && backoff="$WH_CURL_BACKOFF_MAX"
    echo "[curl-retry] transient failure (attempt $attempt/$WH_CURL_MAX_ATTEMPTS, rc=$rc code=${code:-n/a}); retrying in ${backoff}s" >&2
    sleep "$backoff"
    attempt=$((attempt + 1))
  done
}
