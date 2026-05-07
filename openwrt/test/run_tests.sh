#!/bin/sh
# Run the familydns OpenWrt agent unit tests.
# Requires: luarocks install busted lua-cjson
set -e
cd "$(dirname "$0")/.."
LUA_PATH="./files/usr/lib/familydns/?.lua;$(lua -e 'print(package.path)')" \
  busted test/conntrack_spec.lua "$@"
