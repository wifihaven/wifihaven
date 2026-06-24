#!/bin/sh
# Compile-check every agent Lua source under REAL Lua 5.1.
#
# The router runs Lua 5.1 (OpenWrt's lua / LuaJIT-5.1). Lua 5.1 enforces hard
# limits the dev-host Lua (5.4/5.5, via luarocks/brew) does NOT — most notably
# the "a function may have at most 60 upvalues" load-time limit. A function that
# closes over >60 locals compiles fine on macOS but fails to LOAD on the router:
#
#   /usr/bin/lua: /usr/sbin/wifihaven-agent:1293: function at line 965 has more
#   than 60 upvalues
#
# That is a load-time (compile) error, so the WHOLE script fails to load, the
# agent never starts, no policy is fetched, and every Gate 2 fake-mode scenario
# errors in setup — 35 minutes into the run. This guard reproduces the same
# `luac -p` compile under genuine Lua 5.1 in seconds, so a >60-upvalue (or any
# other 5.1-only load) regression fails fast in unit CI instead.
#
# Caught #1930 (regression introduced via #1921/#1911 blockEncryptedDns +
# #1927 ws sidecar pushing the agent's main on_tick closure past 60 upvalues).
#
# Compile-only (`luac -p`) parses + compiles but never EXECUTES the chunk, so
# require()d native modules (cqueues/luaossl in ws_client) need not be present.
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# Locate a genuine Lua 5.1 bytecode compiler. luac5.1 is the apt `lua5.1`
# package's compiler (present in CI and on the plex.lan validation box). Fall
# back to a bare `luac` only if it self-reports 5.1 — a 5.4 luac would silently
# NOT enforce the 60-upvalue limit and give a misleading pass, so we'd rather
# SKIP than pretend we checked.
LUAC=""
if command -v luac5.1 >/dev/null 2>&1; then
  LUAC="luac5.1"
elif command -v luac >/dev/null 2>&1 && luac -v 2>&1 | grep -q "Lua 5.1"; then
  LUAC="luac"
fi

if [ -z "$LUAC" ]; then
  printf "SKIP: no Lua 5.1 compiler (luac5.1) on PATH — the 60-upvalue limit is\n"
  printf "      only enforced under real Lua 5.1; CI installs lua5.1 so this\n"
  printf "      guard runs there. Validate locally via plex.lan (ssh api.lan).\n"
  exit 0
fi

PASS=0; FAIL=0
check() {
  if [ "$2" = "ok" ]; then
    printf "  PASS: %s\n" "$1"; PASS=$((PASS + 1))
  else
    printf "  FAIL: %s — %s\n" "$1" "$2"; FAIL=$((FAIL + 1))
  fi
}

# Targets: every agent Lua module + the front-end handler + every lua-shebang
# executable under files/usr/sbin (the main agent script is the #1930 culprit).
# `luac -p` skips a leading `#!` shebang line, so the sbin scripts compile fine.
TARGETS=""
for f in "$ROOT"/files/usr/lib/lua/wifihaven/*.lua "$ROOT"/files/www/wifihaven/*.lua; do
  [ -e "$f" ] && TARGETS="$TARGETS $f"
done
for f in "$ROOT"/files/usr/sbin/*; do
  [ -e "$f" ] || continue
  head -n1 "$f" | grep -qE '^#!.*lua' && TARGETS="$TARGETS $f"
done

for f in $TARGETS; do
  rel="${f#"$ROOT"/}"
  err="$("$LUAC" -p "$f" 2>&1 || true)"
  if [ -z "$err" ]; then
    check "$rel compiles under $LUAC" ok
  else
    first="$(printf "%s" "$err" | head -n1)"
    check "$rel compiles under $LUAC" "$first"
  fi
done

printf "\n%d passed, %d failed (compiler: %s)\n" "$PASS" "$FAIL" "$LUAC"
[ "$FAIL" -eq 0 ]
