#!/usr/bin/env bash
# Router API end-to-end tests — exercises the full OpenWRT API surface.
#
# Requires a running staging stack:
#   docker compose -f docker/docker-compose.yml up -d --build --wait
#
# Env:
#   E2E_BASE_URL  default http://127.0.0.1:8080
#   E2E_SPA_URL   default $E2E_BASE_URL — separate when SPA is on a different
#                 host (post-#613: Cloudflare Pages serves the SPA, Render
#                 serves the API). In-compose they coincide.
set -euo pipefail

BASE="${E2E_BASE_URL:-http://127.0.0.1:8080}"
# Post-SPA-split (#613) the API host no longer serves SPA fallback, so
# `/blocked` 404s on api-staging.wifihaven.net. Point that check at the SPA
# host explicitly; default to BASE so the in-compose stack (which serves
# both API and SPA from the same port) keeps working unchanged.
SPA_BASE="${E2E_SPA_URL:-$BASE}"
# See scripts/e2e-tests.sh — fake-router rotates the seeded admin password on
# first boot, so by the time this script runs the DB has the rotated value.
# Against deployed staging (Gate 1 / #653) overridden from STAGING_ADMIN_PASS.
ADMIN_PASS="${ADMIN_PASS:-fake-router-bootstrap-pw-do-not-use-elsewhere}"
# Unique suffix avoids collisions with residue from a previous (or concurrent)
# run against a persistent backend (staging). On the disposable compose stack
# this is just per-run state with no observable effect.
RUN_ID="${RUN_ID:-$(date +%s)-$$}"
PROFILE_NAME="e2e-router-${RUN_ID}"
ROUTER_NAME="e2e-test-router-${RUN_ID}"
# Derive a unicast, locally-administered MAC from RUN_ID so parallel runs
# against staging don't fight over the same `lastSeenIp` row.
mac_suffix=$(printf '%s' "$RUN_ID" | shasum 2>/dev/null | head -c 6)
MAC="e2:e2:e2:${mac_suffix:0:2}:${mac_suffix:2:2}:${mac_suffix:4:2}"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

pass() { echo "  ✓ $*"; }
fail() { echo "  ✗ $*" >&2; exit 1; }
step() { echo; echo "▶ $*"; }

# python3 helper — used for timestamp arithmetic and JSON inspection.
# Do NOT suppress stderr: when a helper crashes the traceback is the only clue
# we get in CI logs, and a silent empty result downstream looks identical to a
# legitimate zero/empty value.
_py() { python3 -c "$1"; }

# ── Admin login ────────────────────────────────────────────────────────────
step "Admin login"
LOGIN=$(curl -fsS -X POST "$BASE/api/auth/login" \
  -H 'content-type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$ADMIN_PASS\"}")
ADMIN=$(_py "import sys,json; print(json.loads('$LOGIN'.replace(\"'\",\"'\"))['token'])" 2>/dev/null \
  || echo "$LOGIN" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$ADMIN" ] || fail "no token in login response: $LOGIN"
pass "logged in"
AUTH=(-H "authorization: Bearer $ADMIN")

# ── Setup: profile (1 min daily limit) + device ───────────────────────────
step "Create test profile (dailyMinutes=1)"
PROF=$(curl -fsS -X POST "$BASE/api/profiles" "${AUTH[@]}" \
  -H 'content-type: application/json' \
  -d "{\"name\":\"$PROFILE_NAME\",\"blockedCategories\":[],\"extraBlocked\":[],\"extraAllowed\":[],\"paused\":false,\"schedules\":[],\"timeLimit\":1,\"siteTimeLimits\":[]}")
PID=$(echo "$PROF" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
[ -n "$PID" ] || fail "no profile id: $PROF"
pass "profile id=$PID name=$PROFILE_NAME"

# Register cleanup so test data is removed even on failure.
cleanup() {
  echo
  echo "▶ Cleanup"
  curl -s -X DELETE "$BASE/api/devices/$MAC"        "${AUTH[@]}" >/dev/null 2>&1 || true
  curl -s -X DELETE "$BASE/api/profiles/$PID"       "${AUTH[@]}" >/dev/null 2>&1 || true
  curl -s -X DELETE "$BASE/api/admin/routers/$RID"  "${AUTH[@]}" >/dev/null 2>&1 || true
  pass "test profile + device + router removed"
}
trap cleanup EXIT

curl -fsS -X PUT "$BASE/api/devices" "${AUTH[@]}" \
  -H 'content-type: application/json' \
  -d "{\"mac\":\"$MAC\",\"name\":\"e2e-laptop-${RUN_ID}\",\"profileId\":$PID}" >/dev/null
pass "device mac=$MAC → profile $PID"

# ── 1. Router enrollment yields a usable bearer token ─────────────────────
step "Router enrollment"
ROUTER=$(curl -fsS -X POST "$BASE/api/admin/routers" "${AUTH[@]}" \
  -H 'content-type: application/json' \
  -d "{\"name\":\"$ROUTER_NAME\"}")
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

# ── 3. Usage ingest → last_seen_ip updates + blockReason=TimeLimit ───────
#
# #354 slimmed the policy snapshot: timeUsedToday / dailyMinutes / paused /
# schedules are gone from the wire. The server collapses them into
# BlockRules.blocked + blockReason ("TimeLimit" | "Paused" | "Schedule").
# `profiles` is now a Map keyed by profile id, serialized as a JSON object
# with stringified id keys (zio-json's JsonFieldEncoder[Long]).
step "Usage ingest → last_seen_ip + blockReason=TimeLimit"
# 90 active seconds = 1.5 min, exceeds dailyMinutes=1 → server should block.
NOW=$(_py "from datetime import datetime,timezone; print(datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'))")
FIVE_AGO=$(_py "from datetime import datetime,timezone,timedelta; print((datetime.now(timezone.utc)-timedelta(minutes=5)).strftime('%Y-%m-%dT%H:%M:%SZ'))")

USAGE_BODY=$(cat <<EOF
{
  "routerId": "$RID",
  "periodStart": "$FIVE_AGO",
  "periodEnd":   "$NOW",
  "records": [
    {
      "mac": "$MAC", "ip": "192.168.1.20", "host": {"type": "fqdn", "value": "laptop.local"},
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

# Snapshot must show the profile as blocked with reason=TimeLimit.
#
# Poll with a short timeout (#780): against the in-compose stack the snapshot
# reflects the write on the very next request, but against deployed staging the
# Render rollover window can briefly serve a stale instance between the POST
# and the read. Match the wait-with-timeout pattern from
# scripts/e2e/scenarios/test_time_limit.py rather than asserting on a single
# read — the read path itself is unchanged, we just give it a few tries.
REASON=""
deadline=$(( $(date +%s) + 15 ))
while (( $(date +%s) < deadline )); do
  curl -fsS "${RAUTH[@]}" "$BASE/api/router/policy" >"$TMP/snap2.json"
  REASON=$(_py "
import json
snap = json.load(open('$TMP/snap2.json'))
p = snap['profiles'].get('$PID')
if p is None:
    raise SystemExit('profile $PID missing from snapshot.profiles: ' + ','.join(snap['profiles'].keys()))
r = p['rules']
print(('blocked=' + str(r['blocked']) + ' reason=' + str(r.get('blockReason'))))
")
  [ "$REASON" = "blocked=True reason=TimeLimit" ] && break
  sleep 1
done
echo "  · $REASON"
case "$REASON" in
  "blocked=True reason=TimeLimit") pass "blockReason=TimeLimit after 90s usage" ;;
  *) fail "expected blocked=True reason=TimeLimit, got: $REASON" ;;
esac

# Device last_seen_ip should be updated. Same poll pattern — the touch is in
# the same transaction as the usage insert, but the read may still hit a
# transient stale instance during a Render rollover.
LAST_IP=""
deadline=$(( $(date +%s) + 15 ))
while (( $(date +%s) < deadline )); do
  curl -fsS "${AUTH[@]}" "$BASE/api/devices" >"$TMP/devices.json"
  LAST_IP=$(_py "
import json
devs = json.load(open('$TMP/devices.json'))
d = next((d for d in devs if d['mac'] == '$MAC'), None)
print((d or {}).get('lastSeenIp') or '')
")
  [ "$LAST_IP" = "192.168.1.20" ] && break
  sleep 1
done
[ "$LAST_IP" = "192.168.1.20" ] || fail "expected lastSeenIp=192.168.1.20, got '$LAST_IP'"
pass "last_seen_ip=192.168.1.20"

# ── 5. Events ingest ───────────────────────────────────────────────────────
#
# connection_attempt carries `host` as a HostId tagged union (#391). The real
# OpenWRT agent (openwrt/files/usr/lib/lua/wifihaven/conntrack.lua build_event)
# emits three on-wire shapes; each must round-trip 2xx so the next time the
# wire contract drifts we catch it here instead of in production logs:
#   1) host.type=fqdn  — DNS-attributed flow
#   2) host.type=ipv4  — unattributed flow (DoH / hard-coded IP)
#   3) allowed=false + reason=blocked — block-verdict echo (#456)
step "Events ingest (dhcp_lease + connection_attempt × 3 shapes)"
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
      "type":    "connection_attempt",
      "mac":     "$MAC",
      "host":    {"type":"fqdn","value":"google.com"},
      "destIp":  "8.8.8.8",
      "allowed": true,
      "reason":  "allow",
      "ts":      "$NOW"
    },
    {
      "type":    "connection_attempt",
      "mac":     "$MAC",
      "host":    {"type":"ipv4","value":"203.0.113.7"},
      "destIp":  "203.0.113.7",
      "allowed": true,
      "reason":  "allow",
      "ts":      "$NOW"
    },
    {
      "type":    "connection_attempt",
      "mac":     "$MAC",
      "host":    {"type":"fqdn","value":"ads.example.com"},
      "destIp":  "198.51.100.42",
      "allowed": false,
      "reason":  "blocked",
      "ts":      "$NOW"
    }
  ]
}
EOF
)
curl -fsS -X POST "$BASE/api/router/events" "${RAUTH[@]}" \
  -H 'content-type: application/json' \
  -d "$EVTS" >/dev/null
pass "dhcp_lease + 3 connection_attempt shapes posted"

# Reject the legacy bare-string `hostname` form so this regression cannot
# silently come back — old agents writing `hostname` instead of `host` will
# 400 now, not be silently dropped.
LEGACY_EVTS=$(cat <<EOF
{
  "routerId": "$RID",
  "events": [
    {
      "type": "connection_attempt",
      "mac":  "$MAC",
      "hostname": "youtube.com",
      "destIp":   "142.250.80.14",
      "allowed":  true,
      "reason":   "allow",
      "ts":       "$NOW"
    }
  ]
}
EOF
)
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/router/events" "${RAUTH[@]}" \
  -H 'content-type: application/json' -d "$LEGACY_EVTS")
case "$CODE" in
  4*) pass "legacy hostname-on-connection_attempt rejected ($CODE)" ;;
  *)  fail "legacy hostname-on-connection_attempt unexpectedly accepted ($CODE)" ;;
esac

# ── 6. Paused profile reflected immediately in snapshot ───────────────────
#
# Precedence is Paused > Schedule > TimeLimit (PolicyService.computeBlockRules),
# so a paused profile reports reason=Paused even when other limits also apply.
# #406 removed POST /api/profiles/:id/pause (it toggled, which race-flipped under
# concurrent calls). Callers now set `paused` explicitly via PUT /api/profiles/:id.
step "Pause profile → blockReason=Paused in snapshot"
pause_profile() {
  local paused=$1
  curl -fsS -X PUT "$BASE/api/profiles/$PID" "${AUTH[@]}" \
    -H 'content-type: application/json' \
    -d "{\"name\":\"$PROFILE_NAME\",\"blockedCategories\":[],\"extraBlocked\":[],\"extraAllowed\":[],\"paused\":$paused,\"schedules\":[],\"timeLimit\":1,\"siteTimeLimits\":[]}" \
    >/dev/null
}
pause_profile true

curl -fsS "${RAUTH[@]}" "$BASE/api/router/policy" >"$TMP/snap3.json"
PAUSED_REASON=$(_py "
import json
snap = json.load(open('$TMP/snap3.json'))
p = snap['profiles'].get('$PID')
if p is None:
    raise SystemExit('profile $PID missing from snapshot.profiles')
print(p['rules'].get('blockReason'))
")
[ "$PAUSED_REASON" = "Paused" ] || fail "expected blockReason=Paused, got '$PAUSED_REASON'"
pass "blockReason=Paused in policy snapshot"

# Unpause so the schedule check below sees reason=Schedule (not Paused, which would win).
pause_profile false

# ── 7. Active schedule reflected in policy snapshot ───────────────────────
step "Add always-on schedule → blockReason=Schedule in snapshot"
# PUT the full profile with an all-days, all-hours schedule. The snapshot no
# longer carries raw schedules; it just collapses an active schedule into
# blockReason=Schedule.
SCHED_BODY=$(cat <<EOF
{
  "name": "$PROFILE_NAME",
  "blockedCategories": [],
  "extraBlocked": [],
  "extraAllowed": [],
  "paused": false,
  "schedules": [
    {
      "name": "always",
      "days": ["mon","tue","wed","thu","fri","sat","sun"],
      "startLocal": "00:00",
      "endLocal":   "23:59",
      "tz":         "UTC"
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
SCHED_REASON=$(_py "
import json
snap = json.load(open('$TMP/snap4.json'))
p = snap['profiles'].get('$PID')
if p is None:
    raise SystemExit('profile $PID missing from snapshot.profiles')
print(p['rules'].get('blockReason'))
")
# Either Schedule (always-on schedule) or TimeLimit (90s usage from §3 still
# in today's bucket). The snapshot's precedence is Schedule > TimeLimit, so we
# expect Schedule — but if scheduling is for any reason inactive we still want
# to assert *some* block, not silently regress.
case "$SCHED_REASON" in
  Schedule)  pass "blockReason=Schedule in snapshot" ;;
  *)         fail "expected blockReason=Schedule, got '$SCHED_REASON'" ;;
esac

# ── 8. /blocked page renders 200 for each reason ─────────────────────────
# Hits the SPA host (#613 split it from the API host). In-compose, SPA_BASE
# defaults to BASE so the same docker-compose path keeps working unchanged.
step "GET /blocked — SPA renders 200 for each block reason ($SPA_BASE)"
for REASON in "paused" "time_limit" "schedule" "category:adult" "extra_blocked"; do
  CODE=$(curl -s -o /dev/null -w '%{http_code}' \
    "$SPA_BASE/blocked?mac=$MAC&host=youtube.com&reason=$REASON")
  [ "$CODE" = "200" ] || fail "/blocked?reason=$REASON returned $CODE (expected 200)"
  pass "/blocked?reason=$REASON → 200"
done

# ── 9. Negative cases per router endpoint (#653) ──────────────────────────
#
# Gate 1 also asserts that the API rejects what it should: missing auth on
# every router endpoint, malformed JSON on events, bogus enrollment tokens.
# These overlap with the contract goldens but are exercised at runtime so a
# regression in the auth/parse path can't slip through.
step "Negative: missing auth → 401 on router endpoints"
for path in /api/router/policy /api/router/events /api/router/usage; do
  if [ "$path" = "/api/router/policy" ]; then
    CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE$path")
  else
    CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
      -H 'content-type: application/json' -d '{}' "$BASE$path")
  fi
  [ "$CODE" = "401" ] || fail "expected 401 on $path (no auth), got $CODE"
  pass "$path → 401 without auth"
done

step "Negative: bogus bearer → 401 on /api/router/policy"
CODE=$(curl -s -o /dev/null -w '%{http_code}' \
  -H 'authorization: Bearer rt_bogus_does_not_exist' \
  "$BASE/api/router/policy")
[ "$CODE" = "401" ] || fail "expected 401 with bogus bearer, got $CODE"
pass "bogus bearer → 401"

step "Negative: bogus enrollment token → 4xx on /api/router/register"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
  -H 'content-type: application/json' \
  -d '{"enrollmentToken":"et_bogus_does_not_exist"}' \
  "$BASE/api/router/register")
case "$CODE" in
  4*) pass "bogus enrollmentToken → $CODE" ;;
  *)  fail "expected 4xx on bogus enrollmentToken, got $CODE" ;;
esac

step "Negative: malformed JSON → 4xx on /api/router/events"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${RAUTH[@]}" \
  -H 'content-type: application/json' \
  --data-binary '{"this is": "not router events JSON"' \
  "$BASE/api/router/events")
case "$CODE" in
  4*) pass "malformed events body → $CODE" ;;
  *)  fail "expected 4xx on malformed events body, got $CODE" ;;
esac

step "Negative: wrong-password login → 401"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
  -H 'content-type: application/json' \
  -d '{"username":"admin","password":"definitely-not-the-password"}' \
  "$BASE/api/auth/login")
case "$CODE" in
  401|403) pass "wrong-password login → $CODE" ;;
  *)       fail "expected 401/403 on wrong password, got $CODE" ;;
esac

echo
echo "All router e2e checks passed."
