#!/bin/sh
# Run the wifihaven OpenWrt agent unit tests.
# Requires: luarocks install busted lua-cjson
#
# LUA_PATH is set so that:
#   require("render")            →  files/usr/lib/lua/wifihaven/render.lua    (short form, used in tests)
#   require("wifihaven.render")  →  files/usr/lib/lua/wifihaven/render.lua    (qualified form, used in modules)
set -e
cd "$(dirname "$0")/.."

# RED step for #1877: the spec lists are still hand-maintained (replaced by
# auto-discovery in the green commit). zzz_canary_spec.lua is on disk but absent
# here, so the coverage guard fails — that is the gap this issue closes.
LUA_SPECS="test/conntrack_spec.lua test/render_spec.lua test/policy_spec.lua test/usage_spec.lua test/clock_spec.lua test/failover_spec.lua test/blocklists_spec.lua test/block_page_spec.lua test/contract_spec.lua test/selfheal_spec.lua test/version_spec.lua test/update_spec.lua test/metrics_spec.lua test/log_spec.lua test/dns_log_spec.lua test/dns_tail_sets_spec.lua test/sni_spec.lua test/sni_reassembly_spec.lua test/quic_spec.lua test/nft_drops_spec.lua test/nflog_spec.lua test/host_norm_spec.lua test/lan_warn_spec.lua test/eb_refresh_spec.lua test/static_ip_labels_spec.lua test/host_metrics_spec.lua test/ws_frame_spec.lua"

SHELL_SPECS="test/init_spec.sh test/init_boot_spec.sh test/boot_skeleton_spec.sh test/update_spec.sh test/update_multi_pkg_spec.sh test/install_spec.sh test/lua_module_paths_spec.sh test/nft_log_dep_spec.sh test/agent_spec.sh test/rotate_dnsmasq_log_spec.sh test/nflog_tail_bounded_spec.sh test/init_sni_toggle_spec.sh"

# List mode (WH_LIST_SPECS=1): print the specs this script would run, one per
# line, and exit without running anything. spec_coverage_spec.test.sh reads this
# to verify discovery is complete.
if [ "${WH_LIST_SPECS:-}" = "1" ]; then
  for f in $LUA_SPECS $SHELL_SPECS; do printf '%s\n' "$f"; done
  exit 0
fi

# shellcheck disable=SC2086 # word-splitting LUA_SPECS into separate args is intentional
LUA_PATH="./files/usr/lib/lua/?.lua;./files/usr/lib/lua/wifihaven/?.lua;./spike/ws-1845/?.lua;./test/shim/?.lua;./test/shim/?/init.lua;$(lua -e 'print(package.path)')" \
  busted $LUA_SPECS "$@"

echo ""
for f in $SHELL_SPECS; do
  sh "$f"
done
# ws-client e2e (#1845): runs ws://+wss:// round-trips through the real client;
# self-skips (exit 0) when lua-cqueues/lua-luaossl aren't installed.
sh spike/ws-1845/e2e_test.sh
