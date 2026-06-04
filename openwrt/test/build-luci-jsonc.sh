#!/usr/bin/env bash
# build-luci-jsonc.sh — compile the REAL OpenWrt luci-lib-jsonc (luci.jsonc) C
# module so contract tests exercise the exact encoder the agent uses in
# production, not the lua-cjson shim (#1367).
#
# Why: production serializes POST bodies with luci.jsonc.stringify. lua-cjson
# (the test shim's backend) diverges from it — empty Lua table → `{}` vs `[]`,
# float precision capped at 16 vs luci's full %.17g — which is how the #1365
# empty-`labels` bug shipped green. Building the genuine module removes any
# "is the shim faithful?" question: CI generates and verifies the golden
# fixtures with the same code the router runs.
#
# Pinned to the openwrt-23.05 source (matching the deployed router's
# OpenWrt 23.05.5) by commit SHA + sha256, so the bytes are reproducible and a
# silent upstream change fails the checksum loudly.
#
# Usage:  build-luci-jsonc.sh <out_dir>
#   Produces <out_dir>/luci/jsonc.so. Add "<out_dir>/?.so" to LUA_CPATH (and
#   keep the shim OFF LUA_PATH) so `require("luci.jsonc")` resolves to it.
#
# Requires: a C toolchain, liblua5.1-dev, libjson-c-dev, curl, sha256sum,
# pkg-config. Targets Debian/Ubuntu (the CI runner); not needed locally, where
# the verified-equivalent shim is the default.
set -euo pipefail

OUT="${1:?usage: build-luci-jsonc.sh <out_dir>}"

# openwrt/luci @ openwrt-23.05 — immutable commit + content hash.
REF="62eb53513e54831c622ea80b830caf545592581e"
SHA256="066f956939ef4ec915fa8fadc4b5d6bb2b9117ba93fab859f49318d205ba7ba7"
URL="https://raw.githubusercontent.com/openwrt/luci/${REF}/libs/luci-lib-jsonc/src/jsonc.c"

mkdir -p "$OUT/luci"
src="$OUT/jsonc.c"
curl -fsSL "$URL" -o "$src"
echo "${SHA256}  ${src}" | sha256sum -c -

lua_cflags="$(pkg-config --cflags lua5.1 2>/dev/null || echo -I/usr/include/lua5.1)"
jsonc_flags="$(pkg-config --cflags --libs json-c)"

# Stock lua5.1 lacks lua_isinteger (a Lua 5.3+ API the source short-circuits
# on; OpenWrt's lua is patched to provide it). Defining it to 0 routes every
# number through the identical integral-double→int64 / fractional-double path
# below it, so the emitted bytes match OpenWrt's build exactly — verified
# byte-for-byte against the live router and the committed fixtures (#1367).
# shellcheck disable=SC2086
gcc -O2 -fPIC -shared -D'lua_isinteger(a,b)=0' \
  -o "$OUT/luci/jsonc.so" "$src" \
  $lua_cflags $jsonc_flags

echo "built $OUT/luci/jsonc.so (luci-lib-jsonc @ ${REF})" >&2
