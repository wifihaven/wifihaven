#!/usr/bin/env bash
# Router API end-to-end tests — exercises the full OpenWRT API surface.
#
# Requires a running staging stack:
#   docker compose -f docker/docker-compose.yml up -d --build --wait
#
# Env:
#   E2E_BASE_URL  default http://127.0.0.1:8080
set -euo pipefail

BASE="${E2E_BASE_URL:-http://127.0.0.1:8080}"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

pass() { echo "  ✓ $*"; }
fail() { echo "  ✗ $*" >&2; exit 1; }
step() { echo; echo "▶ $*"; }

# python3 helper — used for timestamp arithmetic and JSON inspection.
_py() { python3 -c "$1" 2>/dev/null; }

# ── Admin login ────────────────────────────────────────────────────────────
step "Admin login"
LOGIN=$(curl -fsS -X POST "$BASE/api/auth/login" \
  -H 'content-type: application/json' \
  -d '{"username":"admin","password":"changeme"}')
ADMIN=$(_py "import sys,json; print(json.loads('$LOGIN'.replace(\"'\",\"'\"))['token'])" 2>/dev/null \
  || echo "$LOGIN" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$ADMIN" ] || fail "no token in login response: $LOGIN"
pass "logged in"
AUTH=(-H "authorization: Bearer $ADMIN")

# ── Setup: profile (1 min daily limit) + device ───────────────────────────
step "Create test profile (dailyMinutes=1)"
PROF=$(curl -fsS -X POST "$BASE/api/profiles" "${AUTH[@]}" \
  -H 'content-type: application/json' \
  -d '{"name":"e2e-router","blockedCategories":[],"extraBlocked":[],"extraAllowed":[],"paused":false,"schedules":[],"timeLimit":1,"siteTimeLimits":[]}')
PID=$(echo "$PROF" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
[ -n "$PID" ] || fail "no profile id: $PROF"
pass "profile id=$PID"

# Register cleanup so test data is removed even on failure.
cleanup() {
  echo
  echo "▶ Cleanup"
  curl -s -X DELETE "$BASE/api/profiles/$PID"       "${AUTH[@]}" >/dev/null 2>&1 || true
  curl -s -X DELETE "$BASE/api/admin/routers/$RID"  "${AUTH[@]}" >/dev/null 2>&1 || true
  pass "test profile + router removed"
}
trap cleanup EXIT

MAC="e2:e2:e2:e2:e2:01"
curl -fsS -X PUT "$BASE/api/devices" "${AUTH[@]}" \
  -H 'content-type: application/json' \
  -d "{\"mac\":\"$MAC\",\"name\":\"e2e-laptop\",\"profileId\":$PID}" >/dev/null
pass "device mac=$MAC → profile $PID"

# ── 1. Router enrollment yields a usable bearer token ─────────────────────
step "Router enrollment"
ROUTER=$(curl -fsS -X POST "$BASE/api/admin/routers" "${AUTH[@]}" \
  -H 'content-type: application/json' \
  -d '{"name":"e2e-test-router"}')
RID=$(echo   "$ROUTER" | sed -n 's/.*"routerId":"\([^"]*\)".*/\1/p')
ENROLL=$(echo "$ROUTER" | sed -n 's/.*"enrollmentToken":"\([^"]*\)".*/\1/p')
[ -n "$RID" ] && [ -n "$ENROLL" ] || fail "bad create-router response: $ROUTER"
pass "router created id=$RID"

REG=$(curl -fsS -X POST "$BASE/api/router/register" \
  -H 'content-type: application/json' \
  -d "{\"enrollmentToken\":\"$ENROLL\"}")
RTOK=$(echo "$REG" | sed -n 's/.*"routerToken":"\([^"]*\)".*/\1/p')
[ -n "$RTOK" ] || fail "no routerToken in: $REG"
pass "registered — bearer token received"
RAUTH=(-H "authorization: Bearer $RTOK")

# Verify the token actually works.
curl -fsS "${RAUTH[@]}" "$BASE/api/router/policy" >/dev/null \
  || fail "bearer token rejected by /api/router/policy"
pass "bearer token accepted by /api/router/policy"

# ── 2. ETag round-trip ─────────────────────────────────────────────────────
step "Policy ETag round-trip"
curl -fsS -D "$TMP/hdrs.txt" "${RAUTH[@]}" "$BASE/api/router/policy" >"$TMP/snap1.json"
# Strip the "etag: " prefix; keep the quoted value verbatim, e.g. "sha256:abc...".
# cut -d: -f2 would truncate at the second colon inside the hash — don't use it.
ETAG=$(grep -i '^etag:' "$TMP/hdrs.txt" | sed 's/^[Ee][Tt][Aa][Gg]:[[:space:]]*//' | tr -d '\r')
[ -n "$ETAG" ] || fail "no ETag in policy response headers"
pass "ETag=$ETAG"

CODE=$(curl -s -o /dev/null -w '%{http_code}' "${RAUTH[@]}" \
  -H "if-none-match: $ETAG" "$BASE/api/router/policy")
[ "$CODE" = "304" ] || fail "expected 304 with same ETag, got $CODE"
pass "304 on repeat ETag"

# ── 3. Usage records increment time_usage + update last_seen_ip ───────────
step "Usage ingest → timeUsedToday + last_seen_ip"
# 90 active seconds = 1.5 min, which exceeds dailyMinutes=1.  Two birds, one batch.
NOW=$(_py "from datetime import datetime,timezone; print(datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'))")
FIVE_AGO=$(_py "from datetime import datetime,timezone,timedelta; print((datetime.now(timezone.utc)-timedelta(minutes=5)).strftime('%Y-%m-%dT%H:%M:%SZ'))")

USAGE_BODY=$(cat <<EOF
{
  "routerId": "$RID",
  "periodStart": "$FIVE_AGO",
  "periodEnd":   "$NOW",
  "records": [
    {
      "mac": "$MAC", "ip": "192.168.1.20", "hostname": "laptop.local",
      "activeSeconds": 90, "bytesIn": 50000, "bytesOut": 5000
    }
  ]
}
EOF
)
curl -fsS -X POST "$BASE/api/router/usage" "${RAUTH[@]}" \
  -H 'content-type: application/json' \
  -d "$USAGE_BODY" >/dev/null
pass "usage posted (90 active seconds)"

# Snapshot must now show timeUsedToday.totalMinutes > 0 for the profile.
curl -fsS "${RAUTH[@]}" "$BASE/api/router/policy" >"$TMP/snap2.json"
USED=$(_py "
import json
snap = json.load(open('$TMP/snap2.json'))
p = next((p for p in snap['profiles'] if p['id'] == $PID), None)
print(p['timeUsedToday']['totalMinutes'] if p else -1)
")
[ "$USED" -gt 0 ] 2>/dev/null || fail "timeUsedToday.totalMinutes=$USED, expected >0"
pass "timeUsedToday.totalMinutes=$USED"

# Device last_seen_ip should be updated.
curl -fsS "${AUTH[@]}" "$BASE/api/devices" >"$TMP/devices.json"
LAST_IP=$(_py "
import json
devs = json.load(open('$TMP/devices.json'))
d = next((d for d in devs if d['mac'] == '$MAC'), None)
print((d or {}).get('lastSeenIp') or '')
")
[ "$LAST_IP" = "192.168.1.20" ] || fail "expected lastSeenIp=192.168.1.20, got '$LAST_IP'"
pass "last_seen_ip=192.168.1.20"

# ── 4. Time-limit exceeded is visible in the policy snapshot ──────────────
step "Time-limit exceeded in policy snapshot (90s active > 1 min limit)"
EXCEEDED=$(_py "
import sys, json
snap = json.load(open('$TMP/snap2.json'))
p = next((p for p in snap['profiles'] if p['id'] == $PID), None)
if p is None: sys.exit(1)
used  = p['timeUsedToday']['totalMinutes']
daily = p.get('dailyMinutes') or 0
print('yes' if daily > 0 and used >= daily else 'no')
")
[ "$EXCEEDED" = "yes" ] || fail "expected time limit exceeded (used=$USED, dailyMinutes=1)"
pass "time_limit_exceeded (used=$USED >= dailyMinutes=1)"

# ── 5. Events ingest ───────────────────────────────────────────────────────
step "Events ingest (dhcp_lease + connection_attempt)"
EVTS=$(cat <<EOF
{
  "routerId": "$RID",
  "events": [
    {
      "type": "dhcp_lease",
      "mac":  "$MAC", "ip": "192.168.1.21", "hostname": "laptop.local",
      "ts":   "$NOW"
    },
    {
      "type":     "connection_attempt",
      "mac":      "$MAC",
      "hostname": "google.com",
      "destIp":   "8.8.8.8",
      "allowed":  true,
      "reason":   "allow",
      "ts":       "$NOW"
    }
  ]
}
EOF
)
curl -fsS -X POST "$BASE/api/router/events" "${RAUTH[@]}" \
  -H 'content-type: application/json' \
  -d "$EVTS" >/dev/null
pass "dhcp_lease + connection_attempt posted"

# ── 6. Paused profile reflected immediately in snapshot ───────────────────
step "Pause profile → paused:true in snapshot"
curl -fsS -X POST "$BASE/api/profiles/$PID/pause" "${AUTH[@]}" >/dev/null

curl -fsS "${RAUTH[@]}" "$BASE/api/router/policy" >"$TMP/snap3.json"
IS_PAUSED=$(_py "
import json
snap = json.load(open('$TMP/snap3.json'))
p = next((p for p in snap['profiles'] if p['id'] == $PID), None)
print('yes' if p and p.get('paused') else 'no')
")
[ "$IS_PAUSED" = "yes" ] || fail "expected paused=true in snapshot"
pass "paused=true in policy snapshot"

# Unpause so schedule test starts from a clean paused=false.
curl -fsS -X POST "$BASE/api/profiles/$PID/pause" "${AUTH[@]}" >/dev/null

# ── 7. Active schedule visible in policy snapshot ─────────────────────────
step "Add always-on schedule → visible in snapshot"
# PUT the full profile with an all-days, all-hours schedule.
SCHED_BODY=$(cat <<EOF
{
  "name": "e2e-router",
  "blockedCategories": [],
  "extraBlocked": [],
  "extraAllowed": [],
  "paused": false,
  "schedules": [
    {
      "name": "always",
      "days": ["Mon","Tue","Wed","Thu","Fri","Sat","Sun"],
      "blockFrom":  "00:00",
      "blockUntil": "23:59"
    }
  ],
  "timeLimit": 1,
  "siteTimeLimits": []
}
EOF
)
curl -fsS -X PUT "$BASE/api/profiles/$PID" "${AUTH[@]}" \
  -H 'content-type: application/json' \
  -d "$SCHED_BODY" >/dev/null

curl -fsS "${RAUTH[@]}" "$BASE/api/router/policy" >"$TMP/snap4.json"
SCHED_COUNT=$(_py "
import json
snap = json.load(open('$TMP/snap4.json'))
p = next((p for p in snap['profiles'] if p['id'] == $PID), None)
print(len(p.get('schedules', [])) if p else 0)
")
[ "$SCHED_COUNT" -ge 1 ] 2>/dev/null \
  || fail "expected >=1 schedule in snapshot, got $SCHED_COUNT"
pass "$SCHED_COUNT schedule(s) visible in snapshot"

# ── 8. /blocked page renders 200 for each reason ─────────────────────────
step "GET /blocked — SPA renders 200 for each block reason"
for REASON in "paused" "time_limit" "schedule" "category:adult" "extra_blocked"; do
  CODE=$(curl -s -o /dev/null -w '%{http_code}' \
    "$BASE/blocked?mac=$MAC&host=youtube.com&reason=$REASON")
  [ "$CODE" = "200" ] || fail "/blocked?reason=$REASON returned $CODE (expected 200)"
  pass "/blocked?reason=$REASON → 200"
done

echo
echo "All router e2e checks passed."
