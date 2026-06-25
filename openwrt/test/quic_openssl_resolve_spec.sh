#!/bin/sh
# #1949 on-target guard: quic.resolve_openssl() must return lua-openssl (the
# monolithic zhaozg module whose table carries .cipher/.hmac) and cipher.get
# for the AES-128 ciphers the QUIC Initial path needs must be CALLABLE — even
# when luaossl is also installed and shadows the bare `openssl` module name.
#
# This is the end-to-end complement to the busted resolve_openssl tests in
# quic_spec.lua (which exercise the selection logic with injected fakes,
# deterministic in CI). Here we hit the REAL native modules: a regression that
# drops lua-openssl from the image DEPENDS, or one where luaossl shadows it and
# resolve_openssl fails to recover, fails this guard in seconds instead of 35
# minutes into the Gate-2 KVM e2e (the sni-tail procd respawn loop, #1949).
#
# Runs only where lua-openssl is actually installed (the router, plex.lan via
# `ssh api.lan`). SKIPs in unit CI, which installs lua-luaossl but NOT
# lua-openssl — mirroring lua_compile_spec.sh's skip without luac5.1. The macOS
# dev host has neither, so it skips there too.
#
# Detecting "lua-openssl present" must NOT rely on a bare require"openssl": that
# is exactly what luaossl shadows (#1949), so under the shadow it would yield
# luaossl and we'd wrongly skip the one scenario this guard exists for. We
# instead look for lua-openssl's openssl.so on package.cpath. Exit codes from
# the probe: 0 = present and resolver OK, 1 = present but resolver BROKEN (hard
# fail), 2 = lua-openssl absent (skip this interpreter).
set -e
cd "$(dirname "$0")/.."

run_probe() {
  LUA_PATH="./files/usr/lib/lua/?.lua;./files/usr/lib/lua/wifihaven/?.lua;;" \
    "$1" - <<'LUA_EOF'
-- Is lua-openssl installed? Either a bare require already yields it (no luaossl
-- shadow) OR its openssl.so sits on package.cpath (shadowed-but-present).
local function lua_openssl_installed()
  local ok, o = pcall(require, "openssl")
  if ok and type(o) == "table" and o.cipher and o.hmac then return true end
  for tmpl in package.cpath:gmatch("[^;]+") do
    local file = tmpl:gsub("%?", "openssl")
    local fh = io.open(file, "rb")
    if fh then
      fh:close()
      local loader = package.loadlib(file, "luaopen_openssl")
      if loader then
        local okc, m = pcall(loader)
        if okc and type(m) == "table" and m.cipher and m.hmac then return true end
      end
    end
  end
  return false
end

if not lua_openssl_installed() then os.exit(2) end -- absent → caller skips

local ok, err = pcall(function()
  local quic = require("quic")
  assert(type(quic.resolve_openssl) == "function",
    "quic.resolve_openssl is missing (#1949)")
  local o = quic.resolve_openssl()
  assert(type(o) == "table", "resolve_openssl did not return a table")
  assert(o.cipher and o.cipher.get, "resolved module has no .cipher.get (not lua-openssl)")
  assert(o.hmac and o.hmac.hmac, "resolved module has no .hmac.hmac (not lua-openssl)")
  -- The exact ciphers quic.lua:openssl_crypto looks up. cipher.get returning
  -- nil here is precisely the #1949 crash one frame earlier.
  assert(o.cipher.get("aes-128-gcm"), "cipher.get('aes-128-gcm') returned nil")
  assert(o.cipher.get("aes-128-ecb"), "cipher.get('aes-128-ecb') returned nil")
  -- Wire it through the real crypto contract sni-tail builds at startup.
  local crypto = quic.openssl_crypto(o)
  assert(crypto.hkdf_extract and crypto.hkdf_expand_label
    and crypto.aes128_ecb and crypto.aes128_gcm_decrypt,
    "openssl_crypto contract incomplete")
end)
if not ok then
  io.stderr:write("FAIL quic_openssl_resolve_spec: " .. tostring(err) .. "\n")
  os.exit(1)
end
io.write("ok quic_openssl_resolve_spec: resolve_openssl -> lua-openssl, "
  .. "cipher.get('aes-128-gcm'/'aes-128-ecb') callable, openssl_crypto wired\n")
os.exit(0)
LUA_EOF
}

for c in lua5.1 lua luajit; do
  command -v "$c" >/dev/null 2>&1 || continue
  rc=0; run_probe "$c" || rc=$?
  case "$rc" in
    0) exit 0 ;;          # validated
    1) exit 1 ;;          # lua-openssl present but resolver broken → hard fail
    2) continue ;;        # lua-openssl absent on this interpreter → try next
    *) exit "$rc" ;;
  esac
done

printf "SKIP quic_openssl_resolve_spec: no Lua with lua-openssl installed.\n"
printf "      (CI installs lua-luaossl only; validate on the router or\n"
printf "       plex.lan via 'ssh api.lan' — #1949.)\n"
exit 0
