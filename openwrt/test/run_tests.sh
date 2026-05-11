#!/bin/sh
# Run the familydns OpenWrt agent unit tests.
# Requires: luarocks install busted lua-cjson
#
# LUA_PATH is set so that:
#   require("render")            →  files/usr/lib/lua/familydns/render.lua    (short form, used in tests)
#   require("familydns.render")  →  files/usr/lib/lua/familydns/render.lua    (qualified form, used in modules)
set -e
cd "$(dirname "$0")/.."

LUA_PATH="./files/usr/lib/lua/?.lua;./files/usr/lib/lua/familydns/?.lua;./test/shim/?.lua;./test/shim/?/init.lua;$(lua -e 'print(package.path)')" \
  busted test/conntrack_spec.lua \
         test/render_spec.lua \
         test/policy_spec.lua \
         test/usage_spec.lua \
         "$@"

echo ""
sh test/init_spec.sh
sh test/update_spec.sh
