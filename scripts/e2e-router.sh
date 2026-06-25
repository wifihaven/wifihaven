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
# Against deployed staging (Gate 1 / #653) overridden from WH_STAGING_ADMIN_PASS.
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

# Importable e2e helpers (shared with the unit tests). Used by the #1122
# read-back so the typed-BlockReason wire contract has one source of truth.
E2E_LIB="$(cd "$(dirname "${BASH_SOURCE[0]}")/e2e/lib" && pwd)"

# ── Admin login ────────────────────────────────────────────────────────────
# #1790: shared self-healing helper. On a fresh staging DB reset, rotates
# the seeded 'changeme' password back to $ADMIN_PASS before returning a JWT;
# on the normal day this is a single-login pass-through.
step "Admin login"
# shellcheck source=scripts/e2e/lib/admin-auth.sh
source "$E2E_LIB/admin-auth.sh"
export STAGING_API_URL="$BASE"
export STAGING_ADMIN_USER="admin"
export STAGING_ADMIN_PASSWORD="$ADMIN_PASS"
ADMIN=$(admin_login_self_heal) || fail "admin login failed (see stderr)"
[ -n "$ADMIN" ] || fail "admin_login_self_heal returned empty token"
unset STAGING_API_URL STAGING_ADMIN_USER STAGING_ADMIN_PASSWORD
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

# Extra per-scenario artifacts (coverage-gap scenarios below create their own
# RUN_ID-namespaced profiles/devices so they don't depend on the heavily-mutated
# main profile $PID). Scenarios append MACs / profile ids here and cleanup()
# tears them down on exit regardless of pass/fail. Bash reads these globals at
# trap-fire time, so appending anywhere before exit is sufficient.
EXTRA_DEVICES=()
EXTRA_PROFILES=()

# Register cleanup so test data is removed even on failure.
cleanup() {
  echo
  echo "▶ Cleanup"
  curl -s -X DELETE "$BASE/api/devices/$MAC"        "${AUTH[@]}" >/dev/null 2>&1 || true
  curl -s -X DELETE "$BASE/api/profiles/$PID"       "${AUTH[@]}" >/dev/null 2>&1 || true
  curl -s -X DELETE "$BASE/api/admin/routers/$RID"  "${AUTH[@]}" >/dev/null 2>&1 || true
  for m in "${EXTRA_DEVICES[@]:-}"; do
    [ -n "$m" ] && curl -s -X DELETE "$BASE/api/devices/$m" "${AUTH[@]}" >/dev/null 2>&1 || true
  done
  for p in "${EXTRA_PROFILES[@]:-}"; do
    [ -n "$p" ] && curl -s -X DELETE "$BASE/api/profiles/$p" "${AUTH[@]}" >/dev/null 2>&1 || true
  done
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
# and the read. Use a wait-with-timeout rather than asserting on a single
# read — the read path itself is unchanged, we just give it a few tries.
# (Same pattern as scripts/e2e/scenarios_fake/test_time_limit.py.)
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

# ── 4b. #1846: WS↔REST ingest-transport demux parity ──────────────────────
#
# The websocket transport (GET /api/router/ws) demuxes {op,payload} frames into
# the SAME RouterIngestService / RouterMetricsService the REST endpoints call.
# Prove that end-to-end against the real API — this script runs against both the
# in-docker compose stack and deployed staging — by sending identical payloads
# over REST and over ws frames and asserting:
#   (a) the ws upgrade authenticates exactly like REST (bad/missing → 401, valid → 101),
#   (b) every usage/events/metrics frame is acked ok, and
#   (c) the connection events land identically whether posted via REST or ws
#       (read back through the public /api/logs surface — staging has no
#        /api/debug/*, so this uses the same endpoint the #1122 step does).
# Usage/metrics are validated at acceptance granularity (REST 200 == ws ack ok)
# since neither exposes a per-mac public readback on staging; the events leg is
# the deep parity proof of the shared ingest path.
step "#1846: websocket transport demux parity with REST"

WS_SEND="$E2E_LIB/ws_send.py"
WS_URL="$(printf '%s' "$BASE" | sed -e 's#^http#ws#')/api/router/ws"
ws_status() { python3 "$WS_SEND" --url "$WS_URL" "$@" --expect-upgrade-status \
  | _py 'import json,sys; print(json.load(sys.stdin)["status"])'; }

st_none=$(ws_status)
st_bad=$(ws_status --token bogus-token)
st_ok=$(ws_status --token "$RTOK")
{ [ "$st_none" = 401 ] && [ "$st_bad" = 401 ]; } \
  || fail "ws upgrade not 401 for missing/bad token (none=$st_none bad=$st_bad)"
[ "$st_ok" = 101 ] || fail "ws upgrade not 101 for valid token (got $st_ok)"
pass "ws upgrade auth parity: missing/bad → 401, valid → 101"

# Disjoint MACs so the REST and ws connection-event rows compare side by side.
PARITY_TS="$(_py 'import datetime; print(datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))')"
MAC_REST="e2:e2:e3:${mac_suffix:0:2}:${mac_suffix:2:2}:${mac_suffix:4:2}"
MAC_WS="e2:e2:e4:${mac_suffix:0:2}:${mac_suffix:2:2}:${mac_suffix:4:2}"

# Build the REST bodies + the ws frame array together so payloads are identical
# bar the MAC. usage/events carry routerId=$RID (the authed router); the ws
# frames wrap the same bodies under {op,payload,seq}.
PARITY=$(_py "
import json, uuid
rid, ts = '$RID', '$PARITY_TS'
def events(mac):
    return {'routerId': rid, 'events': [
        {'type':'connection_attempt','mac':mac,'host':{'type':'fqdn','value':'youtube.com'},
         'destIp':'203.0.113.51','allowed':False,'reason':'category:adult','ts':ts,'eventId':str(uuid.uuid4())},
        {'type':'connection_attempt','mac':mac,'host':{'type':'fqdn','value':'khanacademy.org'},
         'destIp':'203.0.113.52','allowed':True,'reason':'allow','ts':ts,'eventId':str(uuid.uuid4())},
    ]}
def usage(mac):
    return {'routerId': rid, 'periodStart': ts, 'periodEnd': ts,
            'records': [{'mac':mac,'ip':'192.168.50.10','host':{'type':'fqdn','value':'youtube.com'},
                         'activeSeconds':240,'bytesIn':100000,'bytesOut':50000}]}
def metrics():
    return {'routerId': rid, 'agentVersion':'ws-parity-e2e','agentStartedAt':ts,'sampledAt':ts,
            'gauges':[{'name':'agent_uptime_seconds','value':4242.0}]}
print(json.dumps({
  'rest_events': events('$MAC_REST'), 'rest_usage': usage('$MAC_REST'), 'rest_metrics': metrics(),
  'ws_frames': [
    {'op':'events','seq':1,'payload': events('$MAC_WS')},
    {'op':'usage','seq':2,'payload': usage('$MAC_WS')},
    {'op':'metrics','seq':3,'payload': metrics()},
  ],
}))
")

# REST legs — each must 200.
for kind in events usage metrics; do
  body=$(printf '%s' "$PARITY" | _py "import json,sys; print(json.dumps(json.load(sys.stdin)['rest_$kind']))")
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/router/$kind" "${RAUTH[@]}" \
    -H 'content-type: application/json' -d "$body")
  [ "$code" = 200 ] || fail "REST /api/router/$kind returned $code (expected 200)"
done
pass "REST usage/events/metrics accepted (200)"

# ws leg — send all three frames over one connection; each SENT frame must get
# a matching `ack ok` (matched by op+seq). The server ALSO pushes unsolicited
# `{op:policy}` frames (#1849 first-policy-on-connect / push-on-change), and an
# earlier policy mutation in this script makes one land among the replies; those
# are expected push-on-change traffic, NOT a parity violation, so we partition
# them out before the ack check rather than asserting "every reply is an ack"
# (the pre-#1849 assumption that broke in #1951).
# `|| true` so a ws_send.py failure doesn't abort under `set -e` before we can
# surface it: on error the helper prints `{"error":…}` to stdout, which the
# WS_PARITY parse below reports verbatim in the fail message.
WS_REPLIES=$(printf '%s' "$PARITY" \
  | _py "import json,sys; print(json.dumps(json.load(sys.stdin)['ws_frames']))" \
  | python3 "$WS_SEND" --url "$WS_URL" --token "$RTOK" || true)
WS_PARITY=$(printf '%s\n%s' "$PARITY" "$WS_REPLIES" | _py "
import json, sys
sent = json.loads(sys.stdin.readline()).get('ws_frames', [])
raw = sys.stdin.readline()
if not raw.strip():
    print('bad: ws_send produced no output (helper crashed before any reply)'); sys.exit()
replies = json.loads(raw)
if not isinstance(replies, list):
    print('bad: ws_send error: ' + json.dumps(replies)); sys.exit()
# Server→client pushes are expected #1849 traffic — partition them from the acks.
# NB: the push-op set here must mirror ws_send.py's _PUSH_OPS (only 'policy' today).
pushes = [r for r in replies if isinstance(r, dict) and r.get('op') == 'policy']
acks   = [r for r in replies if isinstance(r, dict) and r.get('op') == 'ack']
def acked_ok(op, seq):
    return any((a.get('payload') or {}).get('op') == op
               and (a.get('payload') or {}).get('seq') == seq
               and (a.get('payload') or {}).get('status') == 'ok'
               for a in acks)
missing = ['%s#%s' % (f['op'], f['seq']) for f in sent if not acked_ok(f['op'], f['seq'])]
if missing:
    print('bad: unacked ' + ','.join(missing) + ' in ' + json.dumps(replies))
else:
    # ok[:push-count] — surface whether the #1849 push was observed (coverage,
    # not a gate: its arrival races frame dispatch so it may land after the acks).
    print('ok:%d' % len(pushes))
")
case "$WS_PARITY" in
  ok:*) pass "ws usage/events/metrics frames each acked ok (op+seq); #1849 policy pushes observed: ${WS_PARITY#ok:}, ignored" ;;
  *)    fail "ws frames not all acked ok: $WS_PARITY" ;;
esac

# (c) Deep parity — the connection events land identically whether posted over
# REST or ws. Read both MACs via the public /api/logs and compare the
# (host, allowed, reason-kind) projection.
proj_for() { # $1 = mac → sorted JSON projection of that mac's youtube/khan events
  curl -fsS "${AUTH[@]}" "$BASE/api/logs?mac=$1&hours=1" | _py "
import json, sys
rows = json.load(sys.stdin).get('rows', [])
def host(r):
    h = r.get('host') or {}
    return h.get('value','') if h.get('type') == 'fqdn' else ''
print(json.dumps(sorted(
    (host(r), bool(r.get('allowed')), (r.get('reason') or {}).get('kind'))
    for r in rows if host(r) in ('youtube.com', 'khanacademy.org'))))
"
}
REST_PROJ=""; WS_PROJ=""
deadline=$(( $(date +%s) + 15 ))
while (( $(date +%s) < deadline )); do
  REST_PROJ=$(proj_for "$MAC_REST"); WS_PROJ=$(proj_for "$MAC_WS")
  n=$(printf '%s' "$REST_PROJ" | _py 'import json,sys; print(len(json.load(sys.stdin)))')
  { [ "$n" = 2 ] && [ "$REST_PROJ" = "$WS_PROJ" ]; } && break
  sleep 1
done
[ "$REST_PROJ" = "$WS_PROJ" ] || fail "ws/REST event parity mismatch: rest=$REST_PROJ ws=$WS_PROJ"
[ "$(printf '%s' "$REST_PROJ" | _py 'import json,sys; print(len(json.load(sys.stdin)))')" = 2 ] \
  || fail "expected 2 events per transport, got rest=$REST_PROJ"
pass "ws and REST connection events landed identically via /api/logs"

# ── 5a. #1122: nflog-synthesized blocked-MAC events end-to-end ────────────
#
# Server-side half of #1122 (router-side half lives in
# scripts/e2e/scenarios_fake/test_blocked_mac_events.py, Gate 2). Simulates
# the OpenWRT agent's nflog tail by POSTing a `connection_attempt` for each
# MacBlockReason wire string with `allowed=false`, then reads them back via
# `/api/logs?blocked=true` and `/api/connection-events/series`. If the API
# ever stops accepting these reason strings — or stops surfacing them on
# the Logs page — this step fails before the deploy promotes to prod.
#
# Wire strings are pinned to MacBlockReason.asString in shared/Models.scala
# and to render.lua's `comment "wh_drop:<mac>:<reason>"` emission. The Scala
# side has PolicySnapshotMacDropAttributionSpec; the Lua side has
# render_spec.lua's `drop rules carry log prefix + counter + comment` block.
# This step is the third leg — proving the API actually round-trips them.
step "#1122: nflog-synthesized blocked-MAC events round-trip /api/logs?blocked=true"

# Anchor event timestamps to "just now" so the default `hours=1` window
# includes them on the staging API (which uses real wall-clock).
NFLOG_NOW="$(_py 'import datetime; print(datetime.datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ"))')"
NFLOG_EVTS=$(_py "
import json, uuid
mac, ts = '$MAC', '$NFLOG_NOW'
reasons = ['Paused', 'Schedule', 'TimeLimit', 'Manual', 'Unmanaged']
evs = []
for i, r in enumerate(reasons):
    evs.append({
        'type': 'connection_attempt',
        'mac':  mac,
        # nflog gives us the destination IP, not a hostname — match the
        # router agent's emission shape when DNS attribution is unavailable.
        'host':    {'type': 'ipv4', 'value': f'198.51.100.{10+i}'},
        'destIp':  f'198.51.100.{10+i}',
        'allowed': False,
        'reason':  r,
        'ts':      ts,
        'eventId': str(uuid.uuid4()),
    })
print(json.dumps({'routerId': '$RID', 'events': evs}))
")
curl -fsS -X POST "$BASE/api/router/events" "${RAUTH[@]}" \
  -H 'content-type: application/json' \
  -d "$NFLOG_EVTS" >/dev/null
pass "5 blocked-MAC connection_attempt events posted (one per MacBlockReason)"

# Read back via /api/logs?mac=...&blocked=true. The async insert path
# (#720 backfill + ON CONFLICT DO NOTHING) lands within ~1s on the in-
# compose stack and within a few seconds against staging — poll briefly.
LOGS_OK=""
deadline=$(( $(date +%s) + 15 ))
while (( $(date +%s) < deadline )); do
  curl -fsS "${AUTH[@]}" \
    "$BASE/api/logs?mac=$MAC&blocked=true&hours=1" >"$TMP/nflog_logs.json"
  LOGS_OK=$(_py "
import json, sys
sys.path.insert(0, '$E2E_LIB')
from log_reason import blocked_reason_kinds
page = json.load(open('$TMP/nflog_logs.json'))
rows = page.get('rows', [])
# Only consider the rows we just posted (filter to the dst-IP prefix used
# above so prior runs against staging don't contaminate the check).
ours = [r for r in rows if (r.get('host') or {}).get('value', '').startswith('198.51.100.1')]
# /api/logs reason is a kind-tagged object since #1147 (#962) — key on .kind.
reasons = blocked_reason_kinds(ours)
want = ['manual', 'paused', 'schedule', 'timeLimit', 'unmanaged']
print('ok' if reasons == want else f'have={reasons}')
")
  [ "$LOGS_OK" = "ok" ] && break
  sleep 1
done
[ "$LOGS_OK" = "ok" ] || fail "/api/logs?blocked=true missing one or more reasons: $LOGS_OK"
pass "/api/logs returned all 5 MacBlockReason rows with blocked=true"

# /api/connection-events/series with bucket=1h must also see them as blocked.
curl -fsS "${AUTH[@]}" \
  "$BASE/api/connection-events/series?bucket=1h&blocked=true&hours=1" \
  >"$TMP/nflog_series.json"
SERIES_OK=$(_py "
import json
page = json.load(open('$TMP/nflog_series.json'))
rows = page.get('rows', [])
total_blocked = sum(r.get('countBlocked', 0) for r in rows)
print('ok' if total_blocked >= 5 else f'countBlocked={total_blocked}')
")
[ "$SERIES_OK" = "ok" ] || fail "/api/connection-events/series did not see 5+ blocked: $SERIES_OK"
pass "/api/connection-events/series rolled up the 5 blocked-MAC events"

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

# ── 5b. #1105: time_limited app + exemptFromDaily carves around the MAC block ─
#
# Profile is currently blocked (reason=TimeLimit from §3's 90s usage). Pick a
# template-seeded app, assign it `mode=time_limited, exemptFromDaily=true`, and
# re-fetch the snapshot. The app's host MUST appear in profile.rules.extraAllowed
# even though `blocked=true` is still set — that's the contract the router
# (render.lua) relies on to carve the host out of @blocked_macs.
# PolicySnapshotAppsSpec covers the same logic in the unit tests; this step locks
# it in over the wire so a future refactor can't drop the extraAllowed entry
# without tripping CI.
#
# #1798 retired POST /api/apps (app *definitions* are now template-authored
# only). So instead of minting a Khan-shaped app on the fly, seed the built-in
# AppTemplates, then drive the assertion off whichever seeded app the API
# returns (its real host-set) — the carve-out contract is host-agnostic.
step "#1105: exempt time_limited app folds host into extraAllowed under MAC block"
# Idempotent on a persistent staging DB — re-seeding an already-seeded slug is a
# no-op (preserved), so concurrent runs don't collide.
curl -fsS -X POST "$BASE/api/apps/seed-from-templates" "${AUTH[@]}" >/dev/null
curl -fsS "${AUTH[@]}" "$BASE/api/apps" >"$TMP/apps.json"
# Pick a seeded app+host whose host is NOT already in the profile's extraAllowed
# *before* we assign anything. The global infra-allowlist (#1311) seeds some
# hosts into every profile's extraAllowed, so picking a host that's already
# allowed would let the post-assignment assertion pass even if the
# time_limited+exempt → extraAllowed fold regressed. Selecting an as-yet-unallowed
# host keeps the carve-out attributable solely to the assignment (the property
# the synthetic-host version relied on pre-#1798).
curl -fsS "${RAUTH[@]}" "$BASE/api/router/policy" >"$TMP/snap_pre.json"
APP_SEL=$(_py "
import json
snap = json.load(open('$TMP/snap_pre.json'))
p = snap['profiles'].get('$PID') or {}
ea_before = set((p.get('rules') or {}).get('extraAllowed') or [])
apps = json.load(open('$TMP/apps.json'))
for a in apps:
    for h in (a.get('hosts') or []):
        if h not in ea_before:
            print(a['app']['id'], h); raise SystemExit(0)
raise SystemExit('no seeded app host outside the pre-existing extraAllowed set')
") || fail "could not select a seeded app+host (see stderr)"
APP_ID=${APP_SEL%% *}
APP_HOST=${APP_SEL#* }
[ -n "$APP_ID" ] && [ -n "$APP_HOST" ] || fail "empty app id/host from selection: '$APP_SEL'"
curl -fsS -X PUT "$BASE/api/apps/$APP_ID/policy/$PID" "${AUTH[@]}" \
  -H 'content-type: application/json' \
  -d '{"mode":"time_limited","dailyMinutes":60,"exemptFromDaily":true}' >/dev/null
pass "seeded app $APP_ID (host=$APP_HOST) assigned time_limited+exempt to profile $PID"

# Snapshot's extraAllowed must now include the app host while the profile
# remains blocked.
EA_OK=""
deadline=$(( $(date +%s) + 15 ))
while (( $(date +%s) < deadline )); do
  curl -fsS "${RAUTH[@]}" "$BASE/api/router/policy" >"$TMP/snap_exempt.json"
  EA_OK=$(_py "
import json
snap = json.load(open('$TMP/snap_exempt.json'))
p = snap['profiles'].get('$PID')
if p is None:
    raise SystemExit('profile $PID missing from snapshot.profiles')
r = p['rules']
ea = set(r.get('extraAllowed') or [])
blocked = r.get('blocked')
reason = r.get('blockReason')
ok = ('$APP_HOST' in ea) and bool(blocked) and reason == 'TimeLimit'
print('ok' if ok else f'fail: blocked={blocked} reason={reason} ea={sorted(ea)}')
")
  [ "$EA_OK" = "ok" ] && break
  sleep 1
done
[ "$EA_OK" = "ok" ] || fail "expected blocked=True+reason=TimeLimit+ea contains '$APP_HOST', got: $EA_OK"
pass "extraAllowed carves '$APP_HOST' under blocked=True/TimeLimit"

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
step "Attach always-on named schedule → blockReason=Schedule in snapshot"
# #1494: enforcement reads schedules from named_schedules / profile_schedule_rules
# since #1490; the profile upsert no longer writes the legacy `schedules` table
# (an inline `schedules` array is now ignored). So a schedule must be authored as
# a household-scoped named schedule (POST /api/schedules) and attached to the
# profile (PUT /api/profiles/{id}/schedules). The snapshot then collapses an
# active window into blockReason=Schedule.
#
# First (re)assert the profile carries a 1-minute daily limit so TimeLimit would
# otherwise fire (§3 already accrued ~90s of usage today) — this lets §7 prove
# the Schedule > TimeLimit precedence end to end, not just "some block".
curl -fsS -X PUT "$BASE/api/profiles/$PID" "${AUTH[@]}" \
  -H 'content-type: application/json' \
  -d "{
        \"name\": \"$PROFILE_NAME\",
        \"blockedCategories\": [],
        \"extraBlocked\": [],
        \"extraAllowed\": [],
        \"paused\": false,
        \"timeLimit\": 1,
        \"siteTimeLimits\": []
      }" >/dev/null

# Create an all-days, all-hours named schedule (unique name per run).
SCHED_ID=$(curl -fsS -X POST "$BASE/api/schedules" "${AUTH[@]}" \
  -H 'content-type: application/json' \
  -d "{
        \"name\": \"e2e-always-${RUN_ID}\",
        \"windows\": [
          {
            \"days\": [\"mon\",\"tue\",\"wed\",\"thu\",\"fri\",\"sat\",\"sun\"],
            \"startLocal\": \"00:00\",
            \"endLocal\":   \"23:59\",
            \"tz\":         \"UTC\"
          }
        ]
      }" | _py "import json,sys; print(json.load(sys.stdin)['id'])")

# Attach the named schedule to the profile as a BLOCK schedule.
curl -fsS -X PUT "$BASE/api/profiles/$PID/schedules" "${AUTH[@]}" \
  -H 'content-type: application/json' \
  -d "{\"scheduleIds\": [$SCHED_ID]}" >/dev/null

curl -fsS "${RAUTH[@]}" "$BASE/api/router/policy" >"$TMP/snap4.json"
SCHED_REASON=$(_py "
import json
snap = json.load(open('$TMP/snap4.json'))
p = snap['profiles'].get('$PID')
if p is None:
    raise SystemExit('profile $PID missing from snapshot.profiles')
print(p['rules'].get('blockReason'))
")
# The profile has an active always-on schedule AND a reached 1-min daily limit
# (~90s used in §3). PolicyService precedence is Schedule > TimeLimit, so the
# snapshot must collapse to Schedule. Asserting strictly Schedule (not "some
# block") is what catches the #1494 regression: if the attach write path or the
# #1490 read path breaks, the schedule goes unseen and this falls through to
# TimeLimit.
case "$SCHED_REASON" in
  Schedule)  pass "blockReason=Schedule in snapshot (Schedule > TimeLimit)" ;;
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

# ════════════════════════════════════════════════════════════════════════════
# Gate 1 coverage-gap scenarios (#924 #927 #928 #929 #931 #932).
#
# Each creates its own RUN_ID-namespaced profile(s) + device(s) so it does not
# depend on the heavily-mutated main profile $PID (paused + scheduled by the
# steps above). Artifacts are appended to EXTRA_{PROFILES,DEVICES} and torn down
# by cleanup() on exit. NOW / FIVE_AGO from §3 are reused where a recent
# wall-clock timestamp is all that's needed.
# ════════════════════════════════════════════════════════════════════════════

# ── #924: per-app (per-FQDN) time — session-span presence attribution (#1465) ──
#
# Gap from #715: e2e only ever asserted whole-device minutes, never the per-host
# split on /api/time/status `hostUsage`.
#
# #1491 / #1465 note: `proportionalMins` USED to be a byte-share weighting of a
# single bucket's duration (#715). The #1465 presence rework (session-stitch,
# api/src/presence/Presence.scala `proportionalHostSeconds`, landed in #1488)
# REPLACED byte-share with a wall-clock session-span model: a host's
# `proportionalMins` is now the length of its stitched [period_start, period_end]
# sessions — independent of bytes and of report rate. The old fixture posted one
# bucket with a 90/10 *byte* split and asserted heavy>light by bytes; under the
# session model both hosts share the same single 5-min window, so both came out
# `proportionalMins:5` and the assertion flaked/failed (#1491). The race wasn't in
# timing — it was the semantics changing under the test as #1488 rolled out to
# staging.
#
# So the differential is now expressed as PRESENCE SPAN, not byte ratio: the heavy
# host is active across three contiguous 5-min windows (which stitch into one
# ~15-min session) while the light host is active in only the last window (~5-min
# session). We poll /api/time/status until the heavy session has settled to
# clearly dominate the light one. Do NOT reintroduce a byte-ratio expectation
# here — the byte-share path is gone from this surface. If #1466 (connection-event
# anchored span edges) later shifts the magnitudes, the ratio tolerance below may
# need a bump, but `heavy > light` is the stable invariant.
step "#924: hostUsage per-app session-span split on /api/time/status"
P924_ID=$(curl -fsS -X POST "$BASE/api/profiles" "${AUTH[@]}" \
  -H 'content-type: application/json' \
  -d "{\"name\":\"e2e-924-${RUN_ID}\",\"blockedCategories\":[],\"extraBlocked\":[],\"extraAllowed\":[],\"paused\":false,\"schedules\":[],\"timeLimit\":null,\"siteTimeLimits\":[]}" \
  | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
[ -n "$P924_ID" ] || fail "#924: no profile id"
EXTRA_PROFILES+=("$P924_ID")
MAC924="e2:92:40:${mac_suffix:0:2}:${mac_suffix:2:2}:${mac_suffix:4:2}"
curl -fsS -X PUT "$BASE/api/devices" "${AUTH[@]}" \
  -H 'content-type: application/json' \
  -d "{\"mac\":\"$MAC924\",\"name\":\"e2e-924-dev-${RUN_ID}\",\"profileId\":$P924_ID}" >/dev/null
EXTRA_DEVICES+=("$MAC924")

# Three contiguous 5-min windows ending ~now. traffic_reports is keyed by
# (router_id, period_start, mac, host_type, host_value) and one /api/router/usage
# batch carries a single (periodStart, periodEnd), so each window is its own POST.
# heavy924 is present in all three windows → stitched into one ~15-min session;
# light924 only in the last → one ~5-min session. Byte counts are equal and
# irrelevant under the session model (kept non-trivial just so the records are
# realistic). Timestamps come from a single clock read so the windows abut exactly
# (gap 0 ≤ effectiveGap, so they stitch).
read -r W15 W10 W5 W0 < <(_py "
from datetime import datetime,timezone,timedelta
n=datetime.now(timezone.utc)
f=lambda m:(n-timedelta(minutes=m)).strftime('%Y-%m-%dT%H:%M:%SZ')
print(f(15),f(10),f(5),f(0))
")
HEAVY924_REC="{\"mac\":\"$MAC924\",\"ip\":\"192.168.4.10\",\"host\":{\"type\":\"fqdn\",\"value\":\"heavy924.example.com\"},\"activeSeconds\":300,\"bytesIn\":100000,\"bytesOut\":0}"
LIGHT924_REC="{\"mac\":\"$MAC924\",\"ip\":\"192.168.4.10\",\"host\":{\"type\":\"fqdn\",\"value\":\"light924.example.com\"},\"activeSeconds\":300,\"bytesIn\":100000,\"bytesOut\":0}"
post_924_window() { # $1=periodStart $2=periodEnd $3=records JSON array
  curl -fsS -X POST "$BASE/api/router/usage" "${RAUTH[@]}" \
    -H 'content-type: application/json' \
    -d "{\"routerId\":\"$RID\",\"periodStart\":\"$1\",\"periodEnd\":\"$2\",\"records\":$3}" >/dev/null
}
post_924_window "$W15" "$W10" "[$HEAVY924_REC]"
post_924_window "$W10" "$W5"  "[$HEAVY924_REC]"
post_924_window "$W5"  "$W0"  "[$HEAVY924_REC,$LIGHT924_REC]"
pass "#924: posted 3 windows — heavy in all (≈15-min session), light in last (≈5-min)"

# today-cache TTL is 30s (api/src/cache/TimeStatusCache.scala); allow up to 40s
# for a poisoned-empty entry to expire and for all three windows to ingest. We
# poll until the heavy session-span has SETTLED to dominate the light one, rather
# than asserting at a fixed point (the heavy windows may land one at a time).
H924_OK=""
deadline=$(( $(date +%s) + 40 ))
while (( $(date +%s) < deadline )); do
  curl -fsS "${AUTH[@]}" "$BASE/api/time/status?profileId=$P924_ID" >"$TMP/s924.json"
  H924_OK=$(_py "
import json
st = json.load(open('$TMP/s924.json'))
p = next((x for x in st if x.get('profileId') == $P924_ID), None)
if p is None:
    print('profile-missing'); raise SystemExit
hu = {h['host'].get('value'): h for h in p.get('hostUsage', []) if h['host'].get('type') == 'fqdn'}
heavy = hu.get('heavy924.example.com'); light = hu.get('light924.example.com')
if not heavy or not light:
    print('hosts-missing have=' + ','.join(sorted(hu))); raise SystemExit
# #1465 session-span: heavy's stitched ~15-min session must clearly dominate
# light's single ~5-min window. Require >=2x separation + both>0 so a transient
# tie (before all three heavy windows ingest) keeps the loop polling instead of
# passing by coincidence.
ok = (light['proportionalMins'] > 0
      and heavy['proportionalMins'] >= 2 * light['proportionalMins'])
print('ok' if ok else 'fail heavy=%s light=%s' % (heavy, light))
")
  [ "$H924_OK" = "ok" ] && break
  sleep 2
done
[ "$H924_OK" = "ok" ] || fail "#924: per-app session-span split wrong: $H924_OK"
pass "#924: heavy session-span proportionalMins ≥ 2× light; both > 0"

# ── #927: household-local day bucketing of usage across the reset boundary ───
#
# Gap from #794/#1104: usage timestamps are attributed to a calendar day by
# householdLocalDate(periodStart, settings) — projecting into dailyResetTz and
# rolling back a day when the wall-clock time is before dailyResetTime. e2e never
# proved that the boundary actually splits two near-simultaneous records onto
# different days. Read the live settings, find the most-recent reset boundary
# comfortably in the past, post one record 5 min BEFORE it and one 5 min AFTER,
# and assert each lands on its expected (different) household-local day and NOT
# on the other.
step "#927: usage straddling the daily-reset boundary splits across local days"
curl -fsS "${AUTH[@]}" "$BASE/api/household/settings" >"$TMP/hs927.json"
# Compute the boundary + the two periodStart instants + their expected local
# days, mirroring PolicyService.householdLocalDate in python via zoneinfo.
read -r BEFORE_TS AFTER_TS DAY_BEFORE DAY_AFTER < <(_py "
import json
from datetime import datetime, timedelta, time
from zoneinfo import ZoneInfo
s = json.load(open('$TMP/hs927.json'))
tz = ZoneInfo(s['dailyResetTz'])
rt = s['dailyResetTime']  # 'HH:MM' or 'HH:MM:SS'
parts = [int(x) for x in rt.split(':')]
reset = time(parts[0], parts[1], parts[2] if len(parts) > 2 else 0)
def local_date(inst):
    z = inst.astimezone(tz)
    return (z.date() - timedelta(days=1)) if z.timetz().replace(tzinfo=None) < reset else z.date()
now = datetime.now(tz)
cutoff = now - timedelta(minutes=10)  # boundary must be safely in the past
cand = datetime.combine(cutoff.date(), reset, tzinfo=tz)
if cand > cutoff:
    cand = datetime.combine(cutoff.date() - timedelta(days=1), reset, tzinfo=tz)
before = cand - timedelta(minutes=5)
after  = cand + timedelta(minutes=5)
fmt = lambda d: d.astimezone(ZoneInfo('UTC')).strftime('%Y-%m-%dT%H:%M:%SZ')
print(fmt(before), fmt(after), local_date(before).isoformat(), local_date(after).isoformat())
")
[ -n "$BEFORE_TS" ] && [ -n "$AFTER_TS" ] && [ "$DAY_BEFORE" != "$DAY_AFTER" ] \
  || fail "#927: boundary computation failed ($BEFORE_TS/$AFTER_TS $DAY_BEFORE/$DAY_AFTER)"
pass "#927: boundary split → before=$DAY_BEFORE after=$DAY_AFTER"

P927_ID=$(curl -fsS -X POST "$BASE/api/profiles" "${AUTH[@]}" \
  -H 'content-type: application/json' \
  -d "{\"name\":\"e2e-927-${RUN_ID}\",\"blockedCategories\":[],\"extraBlocked\":[],\"extraAllowed\":[],\"paused\":false,\"schedules\":[],\"timeLimit\":null,\"siteTimeLimits\":[]}" \
  | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
[ -n "$P927_ID" ] || fail "#927: no profile id"
EXTRA_PROFILES+=("$P927_ID")
MAC927="e2:92:70:${mac_suffix:0:2}:${mac_suffix:2:2}:${mac_suffix:4:2}"
curl -fsS -X PUT "$BASE/api/devices" "${AUTH[@]}" \
  -H 'content-type: application/json' \
  -d "{\"mac\":\"$MAC927\",\"name\":\"e2e-927-dev-${RUN_ID}\",\"profileId\":$P927_ID}" >/dev/null
EXTRA_DEVICES+=("$MAC927")

# Two single-host buckets, one each side of the boundary (120s active → 2 min).
post_usage_927() {  # $1=periodStart  $2=host
  local body
  body=$(cat <<EOF
{"routerId":"$RID","periodStart":"$1","periodEnd":"$AFTER_TS","records":[{"mac":"$MAC927","ip":"192.168.7.27","host":{"type":"fqdn","value":"$2"},"activeSeconds":120,"bytesIn":50000,"bytesOut":5000}]}
EOF
)
  curl -fsS -X POST "$BASE/api/router/usage" "${RAUTH[@]}" \
    -H 'content-type: application/json' -d "$body" >/dev/null
}
post_usage_927 "$BEFORE_TS" "before927.example.com"
post_usage_927 "$AFTER_TS"  "after927.example.com"
pass "#927: posted before/after-boundary usage"

# Assert each day's hostUsage contains only its own host.
check_day_927() {  # $1=date  $2=expected-host  $3=other-host
  local got
  local deadline=$(( $(date +%s) + 40 ))
  while (( $(date +%s) < deadline )); do
    curl -fsS "${AUTH[@]}" "$BASE/api/time/status?profileId=$P927_ID&date=$1" >"$TMP/s927.json"
    got=$(_py "
import json
st = json.load(open('$TMP/s927.json'))
p = next((x for x in st if x.get('profileId') == $P927_ID), None)
hosts = {h['host'].get('value') for h in (p or {}).get('hostUsage', [])}
has_want = '$2' in hosts
has_other = '$3' in hosts
print('ok' if (has_want and not has_other) else 'have=' + ','.join(sorted(hosts)))
")
    [ "$got" = "ok" ] && break
    sleep 2
  done
  [ "$got" = "ok" ] || fail "#927: day $1 expected only $2, got: $got"
  pass "#927: day $1 → only $2"
}
check_day_927 "$DAY_BEFORE" "before927.example.com" "after927.example.com"
check_day_927 "$DAY_AFTER"  "after927.example.com"  "before927.example.com"

# ── #928: cross-device overlap Sum vs Dedup totals ───────────────────────────
#
# Gap from #751: two devices on one profile active in the SAME period_start
# bucket must total differently by mode — Sum adds per-device minutes (5+5=10),
# Dedup unions the bucket once (5). Build one profile per mode with two devices
# each, post identical overlapping usage, and read /api/time/status/summary
# (cache-free, overlap-aware via dayStateAll).
#
# Assert the SEMANTICS via a tolerance band, not exact 10/5: presence over real
# wall-clock timing has ±1 min/device boundary jitter (a 300s span can resolve
# to 4 or 5 min at the seconds→minute / period-bucket boundary), so an exact
# `sum==10 and dedup==5` check is inherently flaky (#1555: observed 9/4). The
# band keeps the discrimination intact — Sum ≈ 2 devices, Dedup ≈ 1 device, and
# the gap proves dedup actually unioned — while tolerating the jitter. Drift
# beyond ±1/device (sum<=7 or dedup<=3) is a real presence regression to
# investigate, NOT a tolerance to widen further.
step "#928: cross-device overlap Sum (10) vs Dedup (5) on /api/time/status/summary"
mk_profile_928() {  # $1=label  $2=mode → echoes id
  curl -fsS -X POST "$BASE/api/profiles" "${AUTH[@]}" \
    -H 'content-type: application/json' \
    -d "{\"name\":\"e2e-928-$1-${RUN_ID}\",\"blockedCategories\":[],\"extraBlocked\":[],\"extraAllowed\":[],\"paused\":false,\"schedules\":[],\"timeLimit\":null,\"siteTimeLimits\":[],\"crossDeviceOverlapMode\":\"$2\"}" \
    | sed -n 's/.*"id":\([0-9]*\).*/\1/p'
}
P928_SUM=$(mk_profile_928 sum sum)
P928_DED=$(mk_profile_928 dedup dedup)
[ -n "$P928_SUM" ] && [ -n "$P928_DED" ] || fail "#928: profile create failed"
EXTRA_PROFILES+=("$P928_SUM" "$P928_DED")
MAC_S1="e2:92:81:${mac_suffix:0:2}:${mac_suffix:2:2}:${mac_suffix:4:2}"
MAC_S2="e2:92:82:${mac_suffix:0:2}:${mac_suffix:2:2}:${mac_suffix:4:2}"
MAC_D1="e2:92:8d:${mac_suffix:0:2}:${mac_suffix:2:2}:${mac_suffix:4:2}"
MAC_D2="e2:92:8e:${mac_suffix:0:2}:${mac_suffix:2:2}:${mac_suffix:4:2}"
enroll_928() {  # $1=mac  $2=profileId
  curl -fsS -X PUT "$BASE/api/devices" "${AUTH[@]}" \
    -H 'content-type: application/json' \
    -d "{\"mac\":\"$1\",\"name\":\"e2e-928-dev-${RUN_ID}\",\"profileId\":$2}" >/dev/null
  EXTRA_DEVICES+=("$1")
}
enroll_928 "$MAC_S1" "$P928_SUM"
enroll_928 "$MAC_S2" "$P928_SUM"
enroll_928 "$MAC_D1" "$P928_DED"
enroll_928 "$MAC_D2" "$P928_DED"

# All four records share the batch periodStart → each profile's two devices land
# in one common bucket; 300s active → 5 min/device.
U928_BODY=$(cat <<EOF
{
  "routerId": "$RID",
  "periodStart": "$FIVE_AGO",
  "periodEnd": "$NOW",
  "records": [
    {"mac":"$MAC_S1","ip":"192.168.8.11","host":{"type":"fqdn","value":"shared928.example.com"},"activeSeconds":300,"bytesIn":40000,"bytesOut":4000},
    {"mac":"$MAC_S2","ip":"192.168.8.12","host":{"type":"fqdn","value":"shared928.example.com"},"activeSeconds":300,"bytesIn":40000,"bytesOut":4000},
    {"mac":"$MAC_D1","ip":"192.168.8.13","host":{"type":"fqdn","value":"shared928.example.com"},"activeSeconds":300,"bytesIn":40000,"bytesOut":4000},
    {"mac":"$MAC_D2","ip":"192.168.8.14","host":{"type":"fqdn","value":"shared928.example.com"},"activeSeconds":300,"bytesIn":40000,"bytesOut":4000}
  ]
}
EOF
)
curl -fsS -X POST "$BASE/api/router/usage" "${RAUTH[@]}" \
  -H 'content-type: application/json' -d "$U928_BODY" >/dev/null
pass "#928: posted overlapping usage to 4 devices (2 per profile)"

SUMM_OK=""
deadline=$(( $(date +%s) + 20 ))
while (( $(date +%s) < deadline )); do
  curl -fsS "${AUTH[@]}" "$BASE/api/time/status/summary" >"$TMP/s928.json"
  SUMM_OK=$(_py "
import json
xs = json.load(open('$TMP/s928.json'))
by = {x['profileId']: x['usedMins'] for x in xs}
s = by.get($P928_SUM); d = by.get($P928_DED)
# ±1 min per device is expected presence boundary jitter (300s span can resolve
# to 4 or 5 min at the seconds->minute / period-bucket boundary under real
# wall-clock timing). Assert the SEMANTICS, not exact values. Drift beyond this
# (sum<=7 or dedup<=3) means a real presence regression — investigate, do NOT
# widen further.
ok = (s is not None and d is not None
      and 8 <= s <= 11        # ~2 devices summed (5+5, +/-1 each)
      and 4 <= d <= 6         # ~1 device after dedup union (5, +/-1)
      and s - d >= 3          # dedup actually deduplicated (expected saving 5; jitter floor 3)
      and s > d)
print('ok' if ok else 'sum=%s dedup=%s' % (s, d))
")
  [ "$SUMM_OK" = "ok" ] && break
  sleep 2
done
[ "$SUMM_OK" = "ok" ] || fail "#928: expected sum~10 dedup~5 (±1), got: $SUMM_OK"
pass "#928: Sum~10 (2 devices) > Dedup~5 (1 device, overlapping bucket counted once); within ±1/device presence jitter"

# ── #929: new-device alert on an unseen MAC + dismiss ────────────────────────
#
# Gap from #711: a dhcp_lease for a MAC we've never seen must raise a pending
# new_device alert; dismissing it (POST /api/alerts/{id}/deny) removes it from
# the default (pending-only) feed and the repo won't resurrect it.
step "#929: dhcp_lease for unseen MAC raises new_device alert; deny dismisses it"
MAC929="e2:92:90:${mac_suffix:0:2}:${mac_suffix:2:2}:${mac_suffix:4:2}"
DHCP929=$(cat <<EOF
{"routerId":"$RID","events":[{"type":"dhcp_lease","mac":"$MAC929","ip":"192.168.9.29","hostname":"newdev-${RUN_ID}","ts":"$NOW"}]}
EOF
)
curl -fsS -X POST "$BASE/api/router/events" "${RAUTH[@]}" \
  -H 'content-type: application/json' -d "$DHCP929" >/dev/null
EXTRA_DEVICES+=("$MAC929")  # tear down the auto-created device row
pass "#929: dhcp_lease posted for unseen MAC $MAC929"

ALERT929=""
deadline=$(( $(date +%s) + 15 ))
while (( $(date +%s) < deadline )); do
  curl -fsS "${AUTH[@]}" "$BASE/api/alerts" >"$TMP/al929.json"
  ALERT929=$(_py "
import json
mac = '$MAC929'.lower()
for a in json.load(open('$TMP/al929.json')):
    if a.get('kind') == 'new_device' and a.get('mac','').lower() == mac and a.get('status') == 'pending':
        print(a['id']); break
")
  [ -n "$ALERT929" ] && break
  sleep 1
done
[ -n "$ALERT929" ] || fail "#929: no pending new_device alert for $MAC929"
pass "#929: raised pending new_device alert id=$ALERT929"

curl -fsS -X POST "$BASE/api/alerts/$ALERT929/deny" "${AUTH[@]}" >/dev/null
curl -fsS "${AUTH[@]}" "$BASE/api/alerts" >"$TMP/al929b.json"
STILL929=$(_py "
import json
mac = '$MAC929'.lower()
n = sum(1 for a in json.load(open('$TMP/al929b.json'))
        if a.get('kind') == 'new_device' and a.get('mac','').lower() == mac and a.get('status') == 'pending')
print(n)
")
[ "$STILL929" = "0" ] || fail "#929: alert still pending after deny (count=$STILL929)"
pass "#929: alert dismissed — absent from pending feed"

# ── #931: connection-events /series buckets + groupBy; raw via /api/logs ─────
#
# Gap from #847: assert the aggregated series reconciles succeeded/blocked counts
# under a real groupBy, that raw rows are served by /api/logs (not /series), and
# that bucket=off is rejected by /series (the contract steers raw to /api/logs).
step "#931: /series aggregates (groupBy=device) reconcile; raw via /api/logs"
P931_ID=$(curl -fsS -X POST "$BASE/api/profiles" "${AUTH[@]}" \
  -H 'content-type: application/json' \
  -d "{\"name\":\"e2e-931-${RUN_ID}\",\"blockedCategories\":[],\"extraBlocked\":[],\"extraAllowed\":[],\"paused\":false,\"schedules\":[],\"timeLimit\":null,\"siteTimeLimits\":[]}" \
  | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
[ -n "$P931_ID" ] || fail "#931: no profile id"
EXTRA_PROFILES+=("$P931_ID")
MAC931="e2:93:10:${mac_suffix:0:2}:${mac_suffix:2:2}:${mac_suffix:4:2}"
curl -fsS -X PUT "$BASE/api/devices" "${AUTH[@]}" \
  -H 'content-type: application/json' \
  -d "{\"mac\":\"$MAC931\",\"name\":\"e2e-931-dev-${RUN_ID}\",\"profileId\":$P931_ID}" >/dev/null
EXTRA_DEVICES+=("$MAC931")

# 3 allowed + 2 blocked connection_attempts, all "now", unique dest IPs.
EVT931=$(_py "
import json, uuid
mac, ts = '$MAC931', '$NOW'
evs = []
for i in range(3):
    evs.append({'type':'connection_attempt','mac':mac,'host':{'type':'fqdn','value':f'ok931-{i}.example.com'},'destIp':f'203.0.113.{30+i}','allowed':True,'reason':'allow','ts':ts,'eventId':str(uuid.uuid4())})
for i in range(2):
    evs.append({'type':'connection_attempt','mac':mac,'host':{'type':'fqdn','value':f'blk931-{i}.example.com'},'destIp':f'203.0.113.{40+i}','allowed':False,'reason':'blocked','ts':ts,'eventId':str(uuid.uuid4())})
print(json.dumps({'routerId':'$RID','events':evs}))
")
curl -fsS -X POST "$BASE/api/router/events" "${RAUTH[@]}" \
  -H 'content-type: application/json' -d "$EVT931" >/dev/null
pass "#931: posted 3 allowed + 2 blocked connection_attempts"

SER931=""
deadline=$(( $(date +%s) + 15 ))
while (( $(date +%s) < deadline )); do
  curl -fsS "${AUTH[@]}" \
    "$BASE/api/connection-events/series?bucket=1h&mac=$MAC931&groupBy=device&hours=1" \
    >"$TMP/ser931.json"
  SER931=$(_py "
import json
rows = json.load(open('$TMP/ser931.json')).get('rows', [])
succ = sum(r.get('countSucceeded', 0) for r in rows)
blk  = sum(r.get('countBlocked', 0) for r in rows)
has_dev = all('device' in r.get('groups', {}) for r in rows) and len(rows) > 0
print('ok' if (succ == 3 and blk == 2 and has_dev) else 'succ=%s blk=%s rows=%s' % (succ, blk, len(rows)))
")
  [ "$SER931" = "ok" ] && break
  sleep 1
done
[ "$SER931" = "ok" ] || fail "#931: series reconcile failed: $SER931"
pass "#931: /series countSucceeded=3 countBlocked=2, grouped by device"

# Raw individual rows come from /api/logs (not /series).
RAW931=""
deadline=$(( $(date +%s) + 15 ))
while (( $(date +%s) < deadline )); do
  curl -fsS "${AUTH[@]}" "$BASE/api/logs?mac=$MAC931&hours=1" >"$TMP/raw931.json"
  RAW931=$(_py "
import json
rows = json.load(open('$TMP/raw931.json')).get('rows', [])
print('ok' if len(rows) == 5 else 'rows=%s' % len(rows))
")
  [ "$RAW931" = "ok" ] && break
  sleep 1
done
[ "$RAW931" = "ok" ] || fail "#931: /api/logs raw rows != 5: $RAW931"
pass "#931: /api/logs returned all 5 raw rows"

# bucket=off must be rejected by /series (raw belongs to /api/logs).
CODE=$(curl -s -o /dev/null -w '%{http_code}' "${AUTH[@]}" \
  "$BASE/api/connection-events/series?bucket=off&mac=$MAC931&hours=1")
[ "$CODE" = "400" ] || fail "#931: expected 400 for bucket=off on /series, got $CODE"
pass "#931: /series rejects bucket=off (400)"

# ── #932: /api/usage/traffic raw + aggregated bytes reconcile ────────────────
#
# Gap from #846: assert the traffic endpoint serves raw rows (bucket=raw), folds
# them into aggregated byte sums (bucket=1h, groupBy=domain) that reconcile to
# what was posted, and rejects unknown bucket / unimplemented groupBy=apex. Also
# smoke /api/usage/series for the same device. NOTE: unlike the issue's wording,
# /api/usage/series does NOT take bucket/groupBy=device — bucket lives only on
# /api/usage/traffic and /series's groupBy supports only 'app' (see PR notes).
step "#932: /api/usage/traffic raw rows + aggregated byte reconcile"
P932_ID=$(curl -fsS -X POST "$BASE/api/profiles" "${AUTH[@]}" \
  -H 'content-type: application/json' \
  -d "{\"name\":\"e2e-932-${RUN_ID}\",\"blockedCategories\":[],\"extraBlocked\":[],\"extraAllowed\":[],\"paused\":false,\"schedules\":[],\"timeLimit\":null,\"siteTimeLimits\":[]}" \
  | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
[ -n "$P932_ID" ] || fail "#932: no profile id"
EXTRA_PROFILES+=("$P932_ID")
MAC932="e2:93:20:${mac_suffix:0:2}:${mac_suffix:2:2}:${mac_suffix:4:2}"
curl -fsS -X PUT "$BASE/api/devices" "${AUTH[@]}" \
  -H 'content-type: application/json' \
  -d "{\"mac\":\"$MAC932\",\"name\":\"e2e-932-dev-${RUN_ID}\",\"profileId\":$P932_ID}" >/dev/null
EXTRA_DEVICES+=("$MAC932")

U932_BODY=$(cat <<EOF
{
  "routerId": "$RID",
  "periodStart": "$FIVE_AGO",
  "periodEnd": "$NOW",
  "records": [
    {"mac":"$MAC932","ip":"192.168.32.10","host":{"type":"fqdn","value":"a932.example.com"},"activeSeconds":120,"bytesIn":300000,"bytesOut":0},
    {"mac":"$MAC932","ip":"192.168.32.10","host":{"type":"fqdn","value":"b932.example.com"},"activeSeconds":120,"bytesIn":100000,"bytesOut":0}
  ]
}
EOF
)
curl -fsS -X POST "$BASE/api/router/usage" "${RAUTH[@]}" \
  -H 'content-type: application/json' -d "$U932_BODY" >/dev/null
pass "#932: posted 2 hosts (300k + 100k bytes_in)"

# from/to are ISO instants; wrap the posted window.
T932_FROM=$(_py "from datetime import datetime,timezone,timedelta; print((datetime.now(timezone.utc)-timedelta(hours=1)).strftime('%Y-%m-%dT%H:%M:%SZ'))")
T932_TO=$(_py "from datetime import datetime,timezone,timedelta; print((datetime.now(timezone.utc)+timedelta(minutes=1)).strftime('%Y-%m-%dT%H:%M:%SZ'))")

RAW932=""
deadline=$(( $(date +%s) + 20 ))
while (( $(date +%s) < deadline )); do
  curl -fsS "${AUTH[@]}" \
    "$BASE/api/usage/traffic?mac=$MAC932&bucket=raw&from=$T932_FROM&to=$T932_TO" \
    >"$TMP/raw932.json"
  RAW932=$(_py "
import json
r = json.load(open('$TMP/raw932.json'))
rows = [x for x in r.get('rawRows', []) if x['host'].get('value','').endswith('932.example.com')]
total_in = sum(x['bytesIn'] for x in rows)
print('ok' if (r.get('bucket') == 'raw' and total_in == 400000 and len(rows) == 2) else 'bucket=%s rows=%s in=%s' % (r.get('bucket'), len(rows), total_in))
")
  [ "$RAW932" = "ok" ] && break
  sleep 2
done
[ "$RAW932" = "ok" ] || fail "#932: raw rows reconcile failed: $RAW932"
pass "#932: bucket=raw → 2 rows, bytesIn sum=400000"

curl -fsS "${AUTH[@]}" \
  "$BASE/api/usage/traffic?mac=$MAC932&bucket=1h&groupBy=domain&from=$T932_FROM&to=$T932_TO" \
  >"$TMP/agg932.json"
AGG932=$(_py "
import json
r = json.load(open('$TMP/agg932.json'))
rows = r.get('aggregateRows', [])
total_in = sum(x['totalBytesIn'] for x in rows)
total_out = sum(x['totalBytesOut'] for x in rows)
domains = {v for x in rows for v in x.get('groups', {}).values()} | {x['soleDomain'] for x in rows if x.get('soleDomain')}
ours = {d for d in domains if d and d.endswith('932.example.com')}
ok = ('domain' in r.get('groupBy', []) and total_in == 400000 and total_out == 0 and len(ours) == 2)
print('ok' if ok else 'groupBy=%s in=%s out=%s domains=%s' % (r.get('groupBy'), total_in, total_out, sorted(ours)))
")
[ "$AGG932" = "ok" ] || fail "#932: aggregated reconcile failed: $AGG932"
pass "#932: bucket=1h groupBy=domain → bytesIn sum=400000, 2 domains"

CODE=$(curl -s -o /dev/null -w '%{http_code}' "${AUTH[@]}" \
  "$BASE/api/usage/traffic?mac=$MAC932&bucket=bogus&from=$T932_FROM&to=$T932_TO")
[ "$CODE" = "400" ] || fail "#932: expected 400 for unknown bucket, got $CODE"
pass "#932: unknown bucket → 400"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "${AUTH[@]}" \
  "$BASE/api/usage/traffic?mac=$MAC932&bucket=1h&groupBy=apex&from=$T932_FROM&to=$T932_TO")
[ "$CODE" = "400" ] || fail "#932: expected 400 for groupBy=apex (unimplemented), got $CODE"
pass "#932: groupBy=apex → 400 (not implemented)"

# /api/usage/series smoke — the screen-time series surface for the same device.
curl -fsS "${AUTH[@]}" "$BASE/api/usage/series?mac=$MAC932" >"$TMP/ser932.json"
SER932=$(_py "
import json
r = json.load(open('$TMP/ser932.json'))
print('ok' if isinstance(r.get('topHosts'), list) and isinstance(r.get('buckets'), list) else 'shape-bad')
")
[ "$SER932" = "ok" ] || fail "#932: /api/usage/series shape unexpected: $SER932"
pass "#932: /api/usage/series returns topHosts + buckets arrays"

# ── #1912: network-wide blockEncryptedDns reflected on the wire ───────────
#
# The household "block encrypted DNS & relays" toggle surfaces on the snapshot
# as the additive top-level boolean `blockEncryptedDns` (sibling of global /
# devices / profiles / blocklists). The router agent (#1911) bakes the curated
# relay/DoH host + resolver-IP lists itself; the API only ships the boolean.
# Assert: default is false, flipping the household setting flips the wire flag,
# and we reset it so the run is idempotent against persistent staging.
step "#1912: blockEncryptedDns reflects the household setting"

read_bed() {  # → "true"/"false" from a fresh /api/router/policy read
  curl -fsS "${RAUTH[@]}" "$BASE/api/router/policy" >"$TMP/snap_bed.json"
  _py "
import json
snap = json.load(open('$TMP/snap_bed.json'))
print('true' if snap.get('blockEncryptedDns') is True else ('false' if snap.get('blockEncryptedDns') is False else 'MISSING'))
"
}

# Default (household setting untouched this run) must be a present, false bool —
# never absent (the field is non-optional on the wire).
BED0="$(read_bed)"
[ "$BED0" = "false" ] || fail "#1912: expected default blockEncryptedDns=false, got: $BED0"
pass "#1912: default blockEncryptedDns=false"

curl -fsS -X PATCH "$BASE/api/household/settings" "${AUTH[@]}" \
  -H 'content-type: application/json' \
  -d '{"blockEncryptedDns":true}' >/dev/null
pass "#1912: household setting → blockEncryptedDns=true"

# Poll (staging Render rollover may briefly serve a stale instance — same
# pattern as the TimeLimit step above).
BED1=""
deadline=$(( $(date +%s) + 15 ))
while (( $(date +%s) < deadline )); do
  BED1="$(read_bed)"
  [ "$BED1" = "true" ] && break
  sleep 1
done
[ "$BED1" = "true" ] || fail "#1912: expected blockEncryptedDns=true after toggle, got: $BED1"
pass "#1912: snapshot blockEncryptedDns=true after enabling"

# Reset to false so persistent staging is left as we found it.
curl -fsS -X PATCH "$BASE/api/household/settings" "${AUTH[@]}" \
  -H 'content-type: application/json' \
  -d '{"blockEncryptedDns":false}' >/dev/null
BED2=""
deadline=$(( $(date +%s) + 15 ))
while (( $(date +%s) < deadline )); do
  BED2="$(read_bed)"
  [ "$BED2" = "false" ] && break
  sleep 1
done
[ "$BED2" = "false" ] || fail "#1912: expected blockEncryptedDns=false after reset, got: $BED2"
pass "#1912: snapshot blockEncryptedDns=false after disabling"

echo
echo "All router e2e checks passed."
