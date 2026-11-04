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
  "POST "*"/suspend") echo suspended > "$STATE_FILE"; echo '{}' ;;
  "POST "*"/resume") echo not_suspended > "$STATE_FILE"; echo '{}' ;;
  *"/api/health") echo '{"status":"ok"}' ;;
  *) echo "unexpected call: $method $url" >&2; exit 99 ;;
esac
STUB
chmod +x "$tmp/bin/curl"

fail=0
run() { # run <initial-state> <args...>; sets $out $ec, leaves calls in $CALLS
  echo "$1" > "$tmp/state"; shift
  : > "$tmp/calls"
  set +e
  out="$(PATH="$tmp/bin:$PATH" STATE_FILE="$tmp/state" CALLS="$tmp/calls" \
         RENDER_API_KEY=test-key bash "$script" "$@" 2>&1)"; ec=$?
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

[ "$fail" -eq 0 ] && echo "all render-staging-power.test.sh assertions passed"
exit "$fail"
