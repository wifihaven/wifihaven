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
# the workflow's WH_STAGING_ADMIN_PASS secret.
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

# Retry transient 502/503/504 + connection failures from the starter-tier
# staging API (#1961). Drop-in `curl` override; expected-4xx assertions still
# fail-fast. Sourced before any curl (including admin-auth.sh's logins).
# shellcheck source=scripts/e2e/lib/curl-retry.sh
source "$(dirname "${BASH_SOURCE[0]}")/e2e/lib/curl-retry.sh"

# One-time cold-start gate (#1963): poll /api/health until 200 before running
# the assertion burst, so a post-deploy JVM cold-start (503 status=starting) is
# absorbed once up front rather than fought per-request. Best-effort — the
# per-request curl retry is the backstop. Replaces the old bespoke login-wait
# loop; /api/health 200 means readiness has flipped and gated routes serve.
wh_wait_for_health "$BASE"

step "Login as admin"
# #1790: shared self-healing helper. On a fresh staging DB reset, rotates
# the seeded 'changeme' password back to $ADMIN_PASS before returning a JWT;
# on the normal day this is a single-login pass-through.
# shellcheck source=scripts/e2e/lib/admin-auth.sh
source "$(dirname "${BASH_SOURCE[0]}")/e2e/lib/admin-auth.sh"
export STAGING_API_URL="$BASE"
export STAGING_ADMIN_USER="admin"
export STAGING_ADMIN_PASSWORD="$ADMIN_PASS"
TOKEN=$(admin_login_self_heal) || fail "admin login failed (see stderr)"
[ -n "$TOKEN" ] || fail "admin_login_self_heal returned empty token"
unset STAGING_API_URL STAGING_ADMIN_USER STAGING_ADMIN_PASSWORD
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
  -d "{\"name\":\"$PROFILE_NAME\",\"blockedCategories\":[\"adult\"],\"extraBlocked\":[],\"extraAllowed\":[],\"paused\":false,\"timeLimit\":null,\"siteTimeLimits\":[]}")
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

# ── #933: remaining admin endpoints (dashboard, household-settings, me, ─────
#         time-extend round-trip, change-password) ─────────────────────────
# These five admin/auth surfaces have no Gate 1 coverage. They are read-only
# or self-contained except change-password, which we exercise via a throwaway
# non-admin user so the shared admin credential is never mutated.

step "Dashboard now snapshot (#933)"
# GET /api/dashboard/now → DashboardNow(asOf, profiles[]). Assert both keys
# (presence + shape), not just HTTP 200.
curl -fsS "${AUTH[@]}" "$BASE/api/dashboard/now" >"$TMP/dash.json"
_py "
import json
d = json.load(open('$TMP/dash.json'))
assert isinstance(d.get('asOf'), str) and d['asOf'], 'asOf missing/empty: %r' % d.get('asOf')
assert isinstance(d.get('profiles'), list), 'profiles not a list: %r' % d.get('profiles')
"
pass "dashboard/now has asOf + profiles[]"

step "Household settings (#933, #1468)"
# GET /api/household/settings → HouseholdSettings. dailyResetTime/dailyResetTz
# drive every household-local date computation, so assert they're present.
#
# #1468 (subsumes #930): the presence/heartbeat tuning knobs must also reach the
# wire. Staging is operator-mutable, so the deterministic exact-default pinning
# lives in the api.test gate (PresenceDefaultsPinSpec). Here we assert the live
# contract: the fields are present and well-typed, and the rate-independence
# invariant N >= 2*R holds (R = usage_report_interval, 60 s in the fleet) so a
# silently-shipped sub-2R default — which collapses presence to 0 (design §2d) —
# fails Gate 1 even on a tuned staging box.
curl -fsS "${AUTH[@]}" "$BASE/api/household/settings" >"$TMP/hs.json"
_py "
import json
s = json.load(open('$TMP/hs.json'))
assert isinstance(s.get('dailyResetTime'), str) and s['dailyResetTime'], 'dailyResetTime missing: %r' % s
assert isinstance(s.get('dailyResetTz'), str) and s['dailyResetTz'], 'dailyResetTz missing: %r' % s
hb = s.get('heartbeatFilter')
assert isinstance(hb, dict), 'heartbeatFilter missing/not an object: %r' % s
assert isinstance(hb.get('enabled'), bool), 'heartbeatFilter.enabled not a bool: %r' % hb
assert isinstance(hb.get('bytesThreshold'), int), 'heartbeatFilter.bytesThreshold not an int: %r' % hb
assert isinstance(hb.get('heartbeatHostPatterns'), list), 'heartbeatFilter.heartbeatHostPatterns not a list: %r' % hb
pcs = s.get('presenceContinuationSeconds')
assert isinstance(pcs, int) and pcs > 0, 'presenceContinuationSeconds missing/non-positive: %r' % s
# Rate-independence invariant: N >= 2*R with the fleet's R = 60 s.
assert pcs >= 120, 'presenceContinuationSeconds=%r violates N >= 2*R (R=60)' % pcs
"
pass "household/settings exposes presence/heartbeat knobs and holds N >= 2*R"

step "Identity endpoint /api/me (#933)"
# GET /api/me → MeResponse(username, role, profileIds). We logged in as admin,
# so assert both fields reflect that.
curl -fsS "${AUTH[@]}" "$BASE/api/me" >"$TMP/me.json"
_py "
import json
m = json.load(open('$TMP/me.json'))
assert m.get('username') == 'admin', 'expected username=admin, got %r' % m.get('username')
assert m.get('role') == 'admin', 'expected role=admin, got %r' % m.get('role')
assert isinstance(m.get('profileIds'), list), 'profileIds not a list: %r' % m.get('profileIds')
"
pass "/api/me returns admin identity"

step "Time-extension grant round-trips (#933)"
# POST /api/time/extend grants extra minutes for a profile under the
# household-local "today" bucket; GET /api/time/extensions/{profileId} must
# then list the grant for that same day. Assert the granted minutes survive
# the round-trip (not just that the POST returned 200).
EXTEND_MINS=17
GRANT=$(curl -fsS -X POST "$BASE/api/time/extend" \
  "${AUTH[@]}" -H 'content-type: application/json' \
  -d "{\"profileId\":$PID,\"extraMinutes\":$EXTEND_MINS,\"note\":\"e2e ${RUN_ID}\"}")
echo "$GRANT" | grep -q "\"grantedMinutes\":$EXTEND_MINS" \
  || fail "extend did not echo grantedMinutes=$EXTEND_MINS: $GRANT"
curl -fsS "${AUTH[@]}" "$BASE/api/time/extensions/$PID" >"$TMP/exts.json"
_py "
import json
exts = json.load(open('$TMP/exts.json'))
mine = [e for e in exts if e.get('extraMinutes') == $EXTEND_MINS and (e.get('note') or '') == 'e2e ${RUN_ID}']
assert mine, 'granted extension not found in listing: %r' % exts
"
pass "time/extend grant round-trips into extensions listing"

step "Change-password via throwaway user (#933)"
# Exercise POST /api/auth/change-password without touching the admin
# credential: create a non-admin user, log in as them, rotate their password,
# confirm the new password authenticates and the old one no longer does, then
# delete the user. The user is cleaned up by the trap below even on failure.
CP_USER="e2e-cp-${RUN_ID}"
CP_PW1="cp-pw1-${RUN_ID}"
CP_PW2="cp-pw2-${RUN_ID}"
NEW_USER_ID=""

cleanup_tests_933() {
  local rc=$?
  rm -rf "$TMP"
  [ -n "$NEW_USER_ID" ] && curl -s -X DELETE "$BASE/api/users/$NEW_USER_ID" "${AUTH[@]}" >/dev/null 2>&1 || true
  curl -s -X DELETE "$BASE/api/devices/$TEST_MAC" "${AUTH[@]}" >/dev/null 2>&1 || true
  curl -s -X DELETE "$BASE/api/profiles/$PID" "${AUTH[@]}" >/dev/null 2>&1 || true
  return $rc
}
trap cleanup_tests_933 EXIT

CREATE_USER=$(curl -fsS -X POST "$BASE/api/users" \
  "${AUTH[@]}" -H 'content-type: application/json' \
  -d "{\"username\":\"$CP_USER\",\"password\":\"$CP_PW1\",\"role\":\"adult\",\"profileIds\":[]}")
NEW_USER_ID=$(echo "$CREATE_USER" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
[ -n "$NEW_USER_ID" ] || fail "no user id in create response: $CREATE_USER"
pass "created throwaway user id=$NEW_USER_ID"

# Log in as the throwaway user to obtain its own token.
CP_LOGIN=$(curl -fsS -X POST "$BASE/api/auth/login" \
  -H 'content-type: application/json' \
  -d "{\"username\":\"$CP_USER\",\"password\":\"$CP_PW1\"}")
CP_TOKEN=$(echo "$CP_LOGIN" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$CP_TOKEN" ] || fail "no token for throwaway user: $CP_LOGIN"

# Rotate the password using the user's own credentials.
curl -fsS -X POST "$BASE/api/auth/change-password" \
  -H "authorization: Bearer $CP_TOKEN" -H 'content-type: application/json' \
  -d "{\"currentPassword\":\"$CP_PW1\",\"newPassword\":\"$CP_PW2\"}" >/dev/null
pass "change-password accepted"

# New password authenticates...
NEW_CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/auth/login" \
  -H 'content-type: application/json' \
  -d "{\"username\":\"$CP_USER\",\"password\":\"$CP_PW2\"}")
[ "$NEW_CODE" = "200" ] || fail "new password did not authenticate (got $NEW_CODE)"
# ...and the old one no longer does.
OLD_CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/auth/login" \
  -H 'content-type: application/json' \
  -d "{\"username\":\"$CP_USER\",\"password\":\"$CP_PW1\"}")
[ "$OLD_CODE" = "401" ] || fail "old password still authenticates (got $OLD_CODE)"
pass "password rotated: new pw 200, old pw 401"

echo
echo "All e2e checks passed."
