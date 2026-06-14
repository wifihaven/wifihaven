#!/bin/sh
# Run the wifihaven OpenWrt agent unit tests.
# Requires: luarocks install busted lua-cjson
#
# LUA_PATH is set so that:
#   require("render")            →  files/usr/lib/lua/wifihaven/render.lua    (short form, used in tests)
#   require("wifihaven.render")  →  files/usr/lib/lua/wifihaven/render.lua    (qualified form, used in modules)
set -e
cd "$(dirname "$0")/.."

LUA_PATH="./files/usr/lib/lua/?.lua;./files/usr/lib/lua/wifihaven/?.lua;./test/shim/?.lua;./test/shim/?/init.lua;$(lua -e 'print(package.path)')" \
  busted test/conntrack_spec.lua \
         test/render_spec.lua \
         test/policy_spec.lua \
         test/usage_spec.lua \
         test/clock_spec.lua \
         test/failover_spec.lua \
         test/blocklists_spec.lua \
         test/block_page_spec.lua \
         test/contract_spec.lua \
         test/selfheal_spec.lua \
         test/version_spec.lua \
         test/update_spec.lua \
         test/metrics_spec.lua \
         test/dns_log_spec.lua \
         test/sni_spec.lua \
         test/quic_spec.lua \
         test/nft_drops_spec.lua \
         test/nflog_spec.lua \
         test/lan_warn_spec.lua \
         test/eb_refresh_spec.lua \
         test/static_ip_labels_spec.lua \
         test/host_metrics_spec.lua \
         "$@"

echo ""
sh test/init_spec.sh
sh test/init_boot_spec.sh
sh test/boot_skeleton_spec.sh
sh test/update_spec.sh
sh test/update_multi_pkg_spec.sh
sh test/install_spec.sh
sh test/lua_module_paths_spec.sh
sh test/nft_log_dep_spec.sh
sh test/agent_spec.sh
sh test/rotate_dnsmasq_log_spec.sh
sh test/init_sni_toggle_spec.sh
