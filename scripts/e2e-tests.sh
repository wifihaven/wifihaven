#!/usr/bin/env bash
# Live end-to-end smoke tests against a running staging stack.
#
#   docker compose -f docker/docker-compose.yml up -d --build
#   scripts/e2e-tests.sh
#
# Exits non-zero on the first failure. The GitHub Actions e2e workflow runs
# this exact script — green here is the gate for promoting main → production.
#
# Env:
#   E2E_BASE_URL  default http://127.0.0.1:8080
set -euo pipefail

BASE="${E2E_BASE_URL:-http://127.0.0.1:8080}"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

pass() { echo "  ✓ $*"; }
fail() { echo "  ✗ $*" >&2; exit 1; }
step() { echo; echo "▶ $*"; }

# Quick wait for the API to come up (compose healthcheck should already gate, but be safe).
step "Waiting for API at $BASE"
for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
    -H 'content-type: application/json' -d '{}' \
    "$BASE/api/auth/login" 2>/dev/null || true)
  if [ "$code" = 400 ] || [ "$code" = 401 ]; then pass "API responding ($code)"; break; fi
  if [ "$i" = 60 ]; then fail "API never came up (last code: $code)"; fi
  sleep 1
done

step "Login as admin/changeme"
LOGIN=$(curl -fsS -X POST "$BASE/api/auth/login" \
  -H 'content-type: application/json' \
  -d '{"username":"admin","password":"changeme"}')
TOKEN=$(echo "$LOGIN" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] || fail "no token in login response: $LOGIN"
echo "$LOGIN" | grep -q '"role":"admin"' || fail "admin role missing"
pass "logged in"

AUTH=(-H "authorization: Bearer $TOKEN")

step "Profiles list (auth required)"
UNAUTH=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/profiles")
[ "$UNAUTH" = "401" ] || fail "expected 401 without auth, got $UNAUTH"
pass "401 without auth"

curl -fsS "${AUTH[@]}" "$BASE/api/profiles" >"$TMP/profiles.json"
pass "profiles list returned $(wc -c <"$TMP/profiles.json") bytes"

step "Create a profile"
CREATE=$(curl -fsS -X POST "$BASE/api/profiles" \
  "${AUTH[@]}" -H 'content-type: application/json' \
  -d '{"name":"e2e-test","blockedCategories":["adult"],"extraBlocked":[],"extraAllowed":[],"paused":false,"schedules":[],"timeLimit":null,"siteTimeLimits":[]}')
PID=$(echo "$CREATE" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
[ -n "$PID" ] || fail "no profile id in create response: $CREATE"
pass "created profile id=$PID"

step "Fetch the profile we just created"
curl -fsS "${AUTH[@]}" "$BASE/api/profiles/$PID" | grep -q '"name":"e2e-test"' \
  || fail "profile $PID did not round-trip"
pass "profile round-trips"

step "Devices endpoint"
curl -fsS "${AUTH[@]}" "$BASE/api/devices" >/dev/null
pass "devices endpoint OK"

step "Logs + stats endpoints"
curl -fsS "${AUTH[@]}" "$BASE/api/logs" >/dev/null
curl -fsS "${AUTH[@]}" "$BASE/api/stats" >/dev/null
pass "logs + stats OK"

step "Blocklists endpoint"
curl -fsS "${AUTH[@]}" "$BASE/api/blocklists" >/dev/null
pass "blocklists OK"

step "Time status endpoint"
# Confirm /api/time/status returns a JSON array.
STATUS=$(curl -fsS "${AUTH[@]}" "$BASE/api/time/status")
echo "$STATUS" | grep -q '^\[' || fail "time/status did not return a JSON array: $STATUS"
pass "/api/time/status responded"

echo
echo "All e2e checks passed."
