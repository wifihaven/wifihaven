#!/bin/sh
# e2e_test.sh — end-to-end integration test for the spike #1845 ws CLIENT.
#
# Drives the REAL ws_client.lua through a full round-trip against echo_server.lua
# over both transports, asserting on the outcome:
#   * ws://   loopback — connect, handshake, hello, echo, ping/pong, clean close
#   * wss://  loopback — the same cycle over real TLS (self-signed cert, luaossl)
#
# This is the automated form of the manual hardware validation: it is what
# caught the three Lua-5.1/cqueues bugs (header CR-strip, unchecked-error-limit,
# pcall-across-yield). It needs lua5.1 + cqueues + luaossl, so it SKIPS cleanly
# (exit 0) where they are absent — the dev macOS host and the pure-framing
# busted run stay green; CI installs the deps (see ci.yml) so it actually gates.
#
# Env:
#   LUA        lua interpreter (default: lua5.1, else lua)
#   WS_PREFIX  optional prefix dir for a no-sudo install (apt-get download +
#              dpkg-deb -x); sets LUA_PATH/LUA_CPATH/LD_LIBRARY_PATH at it.
#
# Exit 0 = all cases passed (or skipped for missing deps); non-zero = failure.

set -eu
cd "$(dirname "$0")"

# ── pick the interpreter ─────────────────────────────────────────────────────
if [ -z "${LUA:-}" ]; then
  if command -v lua5.1 >/dev/null 2>&1; then LUA=lua5.1; else LUA=lua; fi
fi

# ── optional no-sudo prefix (mirrors the README recipe) ──────────────────────
if [ -n "${WS_PREFIX:-}" ]; then
  export LD_LIBRARY_PATH="$WS_PREFIX/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
  export LUA_PATH="$WS_PREFIX/usr/share/lua/5.1/?.lua;$WS_PREFIX/usr/share/lua/5.1/?/init.lua;;"
  export LUA_CPATH="$WS_PREFIX/usr/lib/x86_64-linux-gnu/lua/5.1/?.so;;"
  LUA="$WS_PREFIX/usr/bin/lua5.1"
fi

# ── skip if the runtime deps aren't present ──────────────────────────────────
if ! "$LUA" -e 'require"cqueues"; require"openssl.ssl.context"' 2>/dev/null; then
  echo "SKIP ws e2e: cqueues/luaossl not installed (apt: lua-cqueues lua-luaossl)"
  exit 0
fi

WORK="$(mktemp -d)"
trap 'kill "${SRV_PID:-}" 2>/dev/null || true; rm -rf "$WORK"' EXIT
BASE_PORT=$(( 20000 + ($$ % 20000) ))
FAILED=0

# run_case <name> <server-args> <client-uri> <client-env> [client-cmd] [server-env]
# client-cmd defaults to `poc_echo.lua <uri> 4`; server-env is extra env for the
# echo server (e.g. WS_ECHO_FRAGMENT=512 to drive the #1959 reassembly case).
run_case() {
  name="$1"; srv_args="$2"; uri="$3"; cli_env="$4"
  cli_cmd="${5:-poc_echo.lua $uri 4}"; srv_env="${6:-}"
  log="$WORK/$name.srv.log"
  # shellcheck disable=SC2086
  env $srv_env WS_ECHO_DEBUG=1 "$LUA" run_echo_server.lua $srv_args >"$log" 2>&1 &
  SRV_PID=$!
  sleep 1
  # shellcheck disable=SC2086
  if env $cli_env "$LUA" $cli_cmd >"$WORK/$name.cli.log" 2>&1 \
       && grep -q "RESULT: PASS" "$WORK/$name.cli.log"; then
    echo "PASS  $name  ($uri)"
  else
    echo "FAIL  $name  ($uri)"
    echo "----- client output -----"; cat "$WORK/$name.cli.log"
    echo "----- server output -----"; cat "$log"
    FAILED=1
  fi
  kill "$SRV_PID" 2>/dev/null || true
  wait "$SRV_PID" 2>/dev/null || true
  SRV_PID=""
}

echo "== ws e2e (LUA=$LUA) =="

# Case 1: plaintext ws://
WS_PORT=$BASE_PORT
run_case "ws" "$WS_PORT" "ws://127.0.0.1:$WS_PORT" ""

# Case 2: wss:// with a real TLS handshake (self-signed cert; client skips verify)
WSS_PORT=$(( BASE_PORT + 1 ))
openssl req -x509 -newkey rsa:2048 -keyout "$WORK/key.pem" -out "$WORK/cert.pem" \
  -days 1 -nodes -subj /CN=localhost >/dev/null 2>&1
run_case "wss" "$WSS_PORT tls $WORK/cert.pem $WORK/key.pem" \
  "wss://localhost:$WSS_PORT" "WS_INSECURE=1"

# Case 3: #1959 fragment reassembly over ws:// — the server echoes a 12 KiB
# payload as 512-byte §5.4 fragments (TEXT FIN=0 + CONTINUATIONs) with an
# interleaved PING; the client must reassemble it byte-for-byte. Before #1959
# the client returned only the first fragment and poc_fragment FAILs the length
# check. This is the real-Lua-5.1/cqueues twin of the pure ws_frame_spec case.
FRAG_PORT=$(( BASE_PORT + 2 ))
run_case "frag-ws" "$FRAG_PORT" "ws://127.0.0.1:$FRAG_PORT" "" \
  "poc_fragment.lua ws://127.0.0.1:$FRAG_PORT 12288" "WS_ECHO_FRAGMENT=512"

# Case 4: the same over wss:// (TLS), since the prod re-fragmentation that
# motivates #1959 happens at the TLS edge.
FRAG_WSS_PORT=$(( BASE_PORT + 3 ))
run_case "frag-wss" "$FRAG_WSS_PORT tls $WORK/cert.pem $WORK/key.pem" \
  "wss://localhost:$FRAG_WSS_PORT" "WS_INSECURE=1" \
  "poc_fragment.lua wss://localhost:$FRAG_WSS_PORT 12288" "WS_ECHO_FRAGMENT=512"

if [ "$FAILED" -eq 0 ]; then
  echo "== ws e2e: ALL PASS =="
else
  echo "== ws e2e: FAILURES =="
fi
exit "$FAILED"
