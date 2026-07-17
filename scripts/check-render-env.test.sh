#!/usr/bin/env bash
#
# Tests for check-render-env.sh (#2266). Auto-discovered + run by CI's "Shell Tests"
# job (any *.test.sh). Exercises the three exit/report paths without touching any real
# Render env.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
script="$here/check-render-env.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fail=0
check() { # check <desc> <expected-substr> <file-content-var> <expected-exit>
  local desc="$1" expect="$2" body="$3" want_exit="$4" out ec
  printf '%s' "$body" > "$tmp/env"
  set +e
  out="$(bash "$script" "$tmp/env" test 2>&1)"; ec=$?
  set -e
  if [ "$ec" -ne "$want_exit" ]; then
    echo "FAIL: $desc — exit $ec, want $want_exit"; echo "$out"; fail=1; return
  fi
  if ! printf '%s' "$out" | grep -qF "$expect"; then
    echo "FAIL: $desc — output missing '$expect'"; echo "$out"; fail=1; return
  fi
  echo "ok: $desc"
}

good='WIFIHAVEN_JWT_SECRET=a-perfectly-fine-generated-secret-of-48-characters
WIFIHAVEN_HTTP_PORT=8080
WIFIHAVEN_ALLOWED_ORIGINS=https://app.wifihaven.net
WIFIHAVEN_WS_ALLOWED_ORIGINS=app.wifihaven.net
WIFIHAVEN_METRICS_SCRAPE_TOKEN=tok
WIFIHAVEN_EMAIL_RESEND_API_KEY=re_x
WIFIHAVEN_EMAIL_FROM_ADDRESS=a@b.co
'

# 1. valid required config → PASS (exit 0), and a set follow-up gate reads READY.
check "valid config passes" "PASS" "$good" 0
check "email gate ready when both set" "[READY]" "$good" 0

# 2. short JWT secret → boot-critical failure (exit 1).
check "short jwt secret fails" "boot WILL crash" \
  'WIFIHAVEN_JWT_SECRET=tooshort
WIFIHAVEN_HTTP_PORT=8080
WIFIHAVEN_ALLOWED_ORIGINS=x
WIFIHAVEN_WS_ALLOWED_ORIGINS=y
' 1

# 3. placeholder JWT secret → boot-critical failure (exit 1).
check "placeholder jwt secret fails" "placeholder" \
  'WIFIHAVEN_JWT_SECRET=change-this-please-change-this-please-really
WIFIHAVEN_HTTP_PORT=8080
WIFIHAVEN_ALLOWED_ORIGINS=x
WIFIHAVEN_WS_ALLOWED_ORIGINS=y
' 1

# 4. a partially-set follow-up group reads BLOCKED with the missing key named.
check "partial stripe group blocked" "WIFIHAVEN_STRIPE_PRICE_ANNUAL" \
  "${good}WIFIHAVEN_STRIPE_SECRET_KEY=sk
WIFIHAVEN_STRIPE_WEBHOOK_SECRET=wh
WIFIHAVEN_STRIPE_PRICE_MONTHLY=pm
" 0

# 5. an empty value counts as unset (not just an absent line).
check "empty value treated as unset" "[MISSING]" \
  'WIFIHAVEN_JWT_SECRET=a-perfectly-fine-generated-secret-of-48-characters
WIFIHAVEN_HTTP_PORT=8080
WIFIHAVEN_ALLOWED_ORIGINS=
WIFIHAVEN_WS_ALLOWED_ORIGINS=y
' 1

if [ "$fail" -ne 0 ]; then echo "FAILED"; exit 1; fi
echo "all check-render-env.test.sh assertions passed"
