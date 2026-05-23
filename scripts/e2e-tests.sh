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
# fake-router (docker/fake-router.py) rotates the seeded admin password on
# first run to this value. e2e scripts execute *after* `compose up --wait`,
# so the DB is always in the post-rotation state by the time we log in.
# Against the deployed staging API (Gate 1 / #653) this is overridden from
# the workflow's STAGING_ADMIN_PASS secret.
ADMIN_PASS="${ADMIN_PASS:-fake-router-bootstrap-pw-do-not-use-elsewhere}"
# Unique suffix so we don't collide with residue from a previous run against
# a persistent backend (staging). Against the disposable compose stack this
# is just a different per-run name with no observable effect.
RUN_ID="${RUN_ID:-$(date +%s)-$$}"
PROFILE_NAME="e2e-test-${RUN_ID}"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

pass() { echo "  ✓ $*"; }
fail() { echo "  ✗ $*" >&2; exit 1; }
step() { echo; echo "▶ $*"; }
_py() { python3 -c "$1"; }

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

step "Login as admin"
LOGIN=$(curl -fsS -X POST "$BASE/api/auth/login" \
  -H 'content-type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$ADMIN_PASS\"}")
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
  -d "{\"name\":\"$PROFILE_NAME\",\"blockedCategories\":[\"adult\"],\"extraBlocked\":[],\"extraAllowed\":[],\"paused\":false,\"schedules\":[],\"timeLimit\":null,\"siteTimeLimits\":[]}")
PID=$(echo "$CREATE" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
[ -n "$PID" ] || fail "no profile id in create response: $CREATE"
pass "created profile id=$PID name=$PROFILE_NAME"

# Best-effort cleanup so we don't leave residue in a persistent backend.
# Replaces the TMP-cleanup-only trap installed above; both compose together.
cleanup_tests() {
  local rc=$?
  rm -rf "$TMP"
  curl -s -X DELETE "$BASE/api/profiles/$PID" "${AUTH[@]}" >/dev/null 2>&1 || true
  return $rc
}
trap cleanup_tests EXIT

step "Fetch the profile we just created"
curl -fsS "${AUTH[@]}" "$BASE/api/profiles/$PID" | grep -q "\"name\":\"$PROFILE_NAME\"" \
  || fail "profile $PID did not round-trip"
pass "profile round-trips"

step "Devices endpoint"
curl -fsS "${AUTH[@]}" "$BASE/api/devices" >/dev/null
pass "devices endpoint OK"

# ── #926: PUT /api/devices accepts profileId=null on insert + update ─────
# UpsertDeviceRequest.profileId was made optional in #708/#841 so the
# unknown-MAC and admin-reassignment flows work. e2e-router.sh only ever
# upserts with a concrete profileId, so the optional path is unexercised.
# Three curls + re-reads cover insert-null, update-attach, update-clear.
step "Device upsert with profileId=null (insert, update attach, update clear) (#926)"
# Randomized locally-administered unicast MAC so concurrent runs don't collide.
TEST_MAC=$(_py "import random; print(':'.join(['%02x' % ((random.randint(0,255) & 0xfc) | 0x02)] + ['%02x' % random.randint(0,255) for _ in range(5)]))")
TEST_DEV_NAME="e2e-dev-${RUN_ID}"

# Cleanup the test device on exit in addition to the profile.
cleanup_tests_926() {
  local rc=$?
  rm -rf "$TMP"
  curl -s -X DELETE "$BASE/api/devices/$TEST_MAC" "${AUTH[@]}" >/dev/null 2>&1 || true
  curl -s -X DELETE "$BASE/api/profiles/$PID" "${AUTH[@]}" >/dev/null 2>&1 || true
  return $rc
}
trap cleanup_tests_926 EXIT

read_device_pid() {
  # Echo "null" if the device's profileId is absent/null in the listing,
  # else echo the numeric id. Fails (empty) if the MAC isn't found.
  curl -fsS "${AUTH[@]}" "$BASE/api/devices" >"$TMP/devices.json"
  _py "
import json, sys
mac = '$TEST_MAC'
for d in json.load(open('$TMP/devices.json')):
    if d.get('mac','').lower() == mac.lower():
        pid = d.get('profileId')
        print('null' if pid is None else pid)
        sys.exit(0)
sys.exit('device not found: ' + mac)
"
}

# (a) insert with profileId omitted → null
curl -fsS -X PUT "$BASE/api/devices" \
  "${AUTH[@]}" -H 'content-type: application/json' \
  -d "{\"mac\":\"$TEST_MAC\",\"name\":\"$TEST_DEV_NAME\"}" >/dev/null
GOT=$(read_device_pid)
[ "$GOT" = "null" ] || fail "insert profileId=null: expected null, got $GOT"
pass "insert with profileId omitted → null"

# (b) update to attach a real profile id
curl -fsS -X PUT "$BASE/api/devices" \
  "${AUTH[@]}" -H 'content-type: application/json' \
  -d "{\"mac\":\"$TEST_MAC\",\"name\":\"$TEST_DEV_NAME\",\"profileId\":$PID}" >/dev/null
GOT=$(read_device_pid)
[ "$GOT" = "$PID" ] || fail "update attach: expected profileId=$PID, got $GOT"
pass "update attach profileId=$PID"

# (c) update with profileId=null clears the assignment
curl -fsS -X PUT "$BASE/api/devices" \
  "${AUTH[@]}" -H 'content-type: application/json' \
  -d "{\"mac\":\"$TEST_MAC\",\"name\":\"$TEST_DEV_NAME\",\"profileId\":null}" >/dev/null
GOT=$(read_device_pid)
[ "$GOT" = "null" ] || fail "update clear: expected null, got $GOT"
pass "update with profileId=null clears assignment"

# (d) explicit delete (also covered by trap, but verify the route here).
curl -fsS -X DELETE "$BASE/api/devices/$TEST_MAC" "${AUTH[@]}" >/dev/null
pass "test device deleted"

step "Logs + stats endpoints"
curl -fsS "${AUTH[@]}" "$BASE/api/logs" >/dev/null
curl -fsS "${AUTH[@]}" "$BASE/api/stats" >/dev/null
pass "logs + stats OK"

step "Blocklists endpoint"
curl -fsS "${AUTH[@]}" "$BASE/api/blocklists" >/dev/null
pass "blocklists OK"

step "Sessions endpoint is gone (#845)"
# Sessions feature was killed in #845/#851; /api/sessions must 404.
# 200 = resurrection, 401 = auth regression, anything else = unexpected.
SESSIONS=$(curl -s -o /dev/null -w '%{http_code}' "${AUTH[@]}" "$BASE/api/sessions")
[ "$SESSIONS" = "404" ] || fail "expected 404 from /api/sessions, got $SESSIONS"
pass "/api/sessions returns 404"

step "Time status endpoint"
# Confirm /api/time/status returns a JSON array.
STATUS=$(curl -fsS "${AUTH[@]}" "$BASE/api/time/status")
echo "$STATUS" | grep -q '^\[' || fail "time/status did not return a JSON array: $STATUS"
pass "/api/time/status responded"

# ── fake-router steady-state health (#456) ───────────────────────────────
# Even with a 2xx contract assertion in e2e-router.sh, the fake-router
# container in compose can silently 4xx on every tick if its on-wire shape
# drifts — CI stays green because no test reads its logs. Scan post-warmup
# logs once and fail on any `events error` / `usage error` line.
#
# `docker` is the gatekeeper: when available we assert; otherwise skip (this
# script also runs against non-compose deployments).
step "fake-router steady-state log scan (no events/usage errors)"
if command -v docker >/dev/null 2>&1; then
  CID=$(docker ps --filter "label=com.docker.compose.service=fake-router" \
    --format '{{.ID}}' | head -n1)
  if [ -n "$CID" ]; then
    # Drop the first 5s of bootstrap chatter — login retries against the
    # not-yet-ready API legitimately log errors before settling.
    if docker logs --since 5s "$CID" 2>&1 | grep -E 'events error|usage error' >"$TMP/fr-errs.txt"; then
      echo "--- fake-router error lines ---"
      cat "$TMP/fr-errs.txt"
      echo "-------------------------------"
      fail "fake-router posted errors after warmup — wire contract drift?"
    fi
    pass "fake-router post-warmup logs clean"
  else
    pass "fake-router container not running — skipped"
  fi
else
  pass "docker not available — skipped"
fi

echo
echo "All e2e checks passed."
