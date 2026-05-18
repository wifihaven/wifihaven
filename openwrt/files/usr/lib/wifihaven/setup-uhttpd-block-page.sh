#!/bin/sh
# Configure the uhttpd block-page listener on 127.0.0.1:8081 (v4) + [::1]:8081
# (v6) with the lua handler at /www/wifihaven/handler.lua.
#
# Called from:
#   - openwrt/Makefile  Package/wifihaven/postinst (package installs)
#   - openwrt/install.sh                           (manual installs)
#
# Idempotent. Reloads uhttpd only when the UCI section was actually changed.
#
# Without this wiring, requests to the block-page DNAT (127.0.0.1:8081) land
# on a uhttpd instance with no lua_handler configured, so it tries to serve
# the requested URL as a static file out of /www/wifihaven and returns 404
# instead of the per-MAC redirect — which is what fix #542 addresses.
set -eu

log() { printf '[setup-uhttpd-block-page] %s\n' "$1" >&2; }

uhttpd_section=$(uci show uhttpd 2>/dev/null \
  | awk -F'[.=]' "/^uhttpd\\.[^.]+\\.listen_http=.*'127\\.0\\.0\\.1:8081'/{print \$2; exit}")
uhttpd_changed=0

if [ -n "$uhttpd_section" ]; then
  current_home=$(uci -q get "uhttpd.${uhttpd_section}.home" || echo "")
  if [ "$current_home" != "/www/wifihaven" ]; then
    log "updating block-page uhttpd home to /www/wifihaven"
    uci set "uhttpd.${uhttpd_section}.home=/www/wifihaven"
    uhttpd_changed=1
  fi
else
  log "configuring uhttpd block-page listener on 127.0.0.1:8081"
  uhttpd_section=$(uci add uhttpd uhttpd)
  uci add_list "uhttpd.${uhttpd_section}.listen_http=127.0.0.1:8081"
  uci set "uhttpd.${uhttpd_section}.home=/www/wifihaven"
  uhttpd_changed=1
fi

# v6 sibling (#411).
if ! uci -q get "uhttpd.${uhttpd_section}.listen_http" \
    | tr ' ' '\n' | grep -qx '\[::1\]:8081'; then
  log "adding v6 block-page uhttpd listener on [::1]:8081"
  uci add_list "uhttpd.${uhttpd_section}.listen_http=[::1]:8081"
  uhttpd_changed=1
fi

# Route every URL through handler.lua so the block page can do per-MAC
# lookups instead of serving a static file (#437).
desired_lua_prefix='/'
desired_lua_handler='/www/wifihaven/handler.lua'
current_lua_prefix=$(uci -q get "uhttpd.${uhttpd_section}.lua_prefix" || echo "")
current_lua_handler=$(uci -q get "uhttpd.${uhttpd_section}.lua_handler" || echo "")
if [ "$current_lua_prefix" != "$desired_lua_prefix" ]; then
  uci set "uhttpd.${uhttpd_section}.lua_prefix=$desired_lua_prefix"
  uhttpd_changed=1
fi
if [ "$current_lua_handler" != "$desired_lua_handler" ]; then
  uci set "uhttpd.${uhttpd_section}.lua_handler=$desired_lua_handler"
  uhttpd_changed=1
fi

if [ "$uhttpd_changed" = 1 ]; then
  uci commit uhttpd
  /etc/init.d/uhttpd reload 2>/dev/null || /etc/init.d/uhttpd restart 2>/dev/null || true
else
  log "block-page uhttpd listener already configured (v4 + v6, lua handler)"
fi

# #303: route_localnet so the LAN-side DNAT to 127.0.0.1:8081 is routable.
if [ -f /etc/sysctl.d/99-wifihaven.conf ]; then
  sysctl -p /etc/sysctl.d/99-wifihaven.conf >/dev/null 2>&1 || true
else
  sysctl -w net.ipv4.conf.br-lan.route_localnet=1 >/dev/null 2>&1 || true
fi
