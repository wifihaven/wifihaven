#!/usr/bin/env bash
#
# Tests for render-staging-power.sh (#2960). Auto-discovered + run by CI's "Shell Tests"
# job (any *.test.sh). A stub `curl` on PATH stands in for the Render API and the staging
# health endpoint, and records every call, so nothing real is touched.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
script="$here/render-staging-power.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

mkdir "$tmp/bin"
cat > "$tmp/bin/curl" <<'STUB'
#!/usr/bin/env bash
# Last argument is the URL. Log method + URL; answer from $STATE_FILE.
url="${@: -1}"; method=GET
for a in "$@"; do [ "$prev" = "-X" ] && method="$a"; prev="$a"; done
echo "$method $url" >> "$CALLS"
case "$method $url" in
  "GET https://api.render.com/"*) printf '{"suspended":"%s"}' "$(cat "$STATE_FILE")" ;;
  "POST "*) [ -n "$FAIL_POST" ] && exit 22 ;;&
  "POST "*"/suspend") echo suspended > "$STATE_FILE"; echo '{}' ;;
  "POST "*"/resume") echo not_suspended > "$STATE_FILE"; echo '{}' ;;
  *"/api/health") [ -n "$UNHEALTHY" ] && exit 22; echo '{"status":"ok"}' ;;
  *) echo "unexpected call: $method $url" >&2; exit 99 ;;
esac
STUB
chmod +x "$tmp/bin/curl"

# Stub `gh run list`: the in-flight count comes from $INFLIGHT_SEQ, one value per call
# (space-separated), so a test can make a run "appear" between the sweep's two checks.
# A value of `fail` makes that call exit 1.
cat > "$tmp/bin/gh" <<'STUB'
#!/usr/bin/env bash
n="$(cat "$GH_COUNTER" 2>/dev/null || echo 0)"; echo $(( n + 1 )) > "$GH_COUNTER"
read -r -a seq <<<"$INFLIGHT_SEQ"
v="${seq[$n]:-${seq[${#seq[@]}-1]}}"
[ "$v" = fail ] && { echo "gh: HTTP 502" >&2; exit 1; }
echo "$v"
STUB
chmod +x "$tmp/bin/gh"

fail=0
run() { # run <initial-state> <args...>; sets $out $ec, leaves calls in $CALLS
  echo "$1" > "$tmp/state"; shift
  : > "$tmp/calls"
  set +e
  rm -f "$tmp/gh_counter"
  out="$(PATH="$tmp/bin:$PATH" STATE_FILE="$tmp/state" CALLS="$tmp/calls" \
         GH_COUNTER="$tmp/gh_counter" INFLIGHT_SEQ="${INFLIGHT_SEQ:-0}" \
         GITHUB_REPOSITORY=wifihaven/wifihaven RENDER_API_KEY=test-key \
         RESUME_TIMEOUT_SECONDS="${RESUME_TIMEOUT_SECONDS:-1800}" POLL_SECONDS=0 \
         FAIL_POST="${FAIL_POST:-}" UNHEALTHY="${UNHEALTHY:-}" \
         bash "$script" "$@" 2>&1)"; ec=$?
  set -e
}
expect() { # expect <desc> <condition...>
  local desc="$1"; shift
  if "$@"; then echo "ok: $desc"; else echo "FAIL: $desc"; echo "$out"; cat "$tmp/calls"; fail=1; fi
}
posted() { grep -q "^POST .*/$1\$" "$tmp/calls"; }
not_posted() { ! grep -q '^POST ' "$tmp/calls"; }

run not_suspended suspend
expect "suspend: running service gets a suspend POST" posted suspend
expect "suspend: state is now suspended" grep -qx suspended "$tmp/state"

run suspended suspend
expect "suspend: already-suspended service is left alone" not_posted

run suspended resume
expect "resume: suspended service gets a resume POST" posted resume
expect "resume: waits for health" grep -q '/api/health$' "$tmp/calls"
expect "resume: exits 0 once healthy" test "$ec" -eq 0

run not_suspended resume
expect "resume: running service is not re-resumed" not_posted
expect "resume: still checks health" grep -q '/api/health$' "$tmp/calls"

run suspended status
expect "status: prints the Render state" test "$out" = suspended

echo not_suspended > "$tmp/state"
set +e
out="$(PATH="$tmp/bin:$PATH" STATE_FILE="$tmp/state" CALLS="$tmp/calls" \
       env -u RENDER_API_KEY bash "$script" suspend 2>&1)"; ec=$?
set -e
expect "missing RENDER_API_KEY fails loud" test "$ec" -ne 0
expect "missing RENDER_API_KEY names the secret" grep -q RENDER_API_KEY <<<"$out"

run suspended bogus
expect "unknown action exits 2" test "$ec" -eq 2


# ── resume failure paths ──
UNHEALTHY=1 RESUME_TIMEOUT_SECONDS=0 run suspended resume
expect "resume: never-healthy staging times out non-zero" test "$ec" -ne 0
expect "resume: timeout says so" grep -q "Timed out" <<<"$out"

FAIL_POST=1 run not_suspended suspend
expect "suspend: a failing Render POST exits non-zero" test "$ec" -ne 0

# ── sweep (#2960 review: the idle check must fail CLOSED) ──
INFLIGHT_SEQ="1" run not_suspended sweep
expect "sweep: a run in flight leaves staging up" not_posted
expect "sweep: in-flight exits 0" test "$ec" -eq 0

INFLIGHT_SEQ="0" run not_suspended sweep
expect "sweep: idle suspends" posted suspend
expect "sweep: idle does not resume" bash -c "! grep -q '/resume\$' '$tmp/calls'"

# The first check covers the four workflows (4 calls, all 0); the re-check sees a run.
INFLIGHT_SEQ="0 0 0 0 1" run not_suspended sweep
expect "sweep: run appearing mid-sweep -> suspend then resume" posted resume
expect "sweep: mid-sweep run still suspended first" posted suspend

INFLIGHT_SEQ="fail" run not_suspended sweep
expect "sweep: gh failure does NOT suspend" not_posted
expect "sweep: gh failure exits non-zero" test "$ec" -ne 0

INFLIGHT_SEQ="0 0 0 0 fail" run not_suspended sweep
expect "sweep: gh failure on the re-check resumes staging" posted resume
expect "sweep: gh failure on the re-check exits non-zero" test "$ec" -ne 0

INFLIGHT_SEQ="garbage" run not_suspended sweep
expect "sweep: non-integer count does NOT suspend" not_posted
expect "sweep: non-integer count exits non-zero" test "$ec" -ne 0

[ "$fail" -eq 0 ] && echo "all render-staging-power.test.sh assertions passed"
exit "$fail"
