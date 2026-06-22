#!/bin/sh
# Configure the uhttpd block-page listener on 127.0.0.1:8081 (v4) + [::]:8081
# (v6, #1865 — wildcard so nft `redirect`'d forwarded v6 traffic lands on it)
# with the lua handler at /www/wifihaven/handler.lua.
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

# #383: generate the TLS cert before reloading uhttpd, otherwise the
# listen_https socket fails to bind on a clean install and the HTTPS DNAT
# from render.lua's wifihaven_block_nat chain has nowhere to land. Helper
# is idempotent so re-runs on an already-provisioned router are no-ops.
CERT_GEN="/usr/lib/wifihaven/generate-block-page-cert.sh"
if [ -x "$CERT_GEN" ] || [ -f "$CERT_GEN" ]; then
  sh "$CERT_GEN" || log "WARN: cert generator failed; TLS listener will not bind"
fi
BLOCK_PAGE_CRT="/etc/wifihaven/block_page.crt"
BLOCK_PAGE_KEY="/etc/wifihaven/block_page.key"

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

# v6 sibling (#411). #1865: bind the wildcard [::] rather than loopback [::1].
# Forwarded IPv6 block-page traffic is delivered by render.lua's nft `redirect`
# to the br-lan address (::1 is non-routable for forwarded traffic, RFC 4291),
# so the listener must accept on the router's real v6 address, not just
# loopback. Migrate any legacy [::1]:8081 entry to [::]:8081 — binding BOTH
# would fail (the [::] wildcard already covers ::1, so the second bind hits
# "address already in use").
if uci -q get "uhttpd.${uhttpd_section}.listen_http" \
    | tr ' ' '\n' | grep -qx '\[::1\]:8081'; then
  log "migrating v6 block-page listener [::1]:8081 -> [::]:8081"
  uci del_list "uhttpd.${uhttpd_section}.listen_http=[::1]:8081"
  uhttpd_changed=1
fi
if ! uci -q get "uhttpd.${uhttpd_section}.listen_http" \
    | tr ' ' '\n' | grep -qx '\[::\]:8081'; then
  log "adding v6 block-page uhttpd listener on [::]:8081"
  uci add_list "uhttpd.${uhttpd_section}.listen_http=[::]:8081"
  uhttpd_changed=1
fi

# #383: TLS sibling listeners on 127.0.0.1:8443 + [::1]:8443. render.lua's
# wifihaven_block_nat chain DNATs blocked TCP/443 here, the self-signed cert
# at /etc/wifihaven/block_page.* terminates TLS, and the same lua_handler
# below serves the block page (uhttpd-mod-lua sees the request after TLS
# termination — handler.lua doesn't care which listener fired).
if ! uci -q get "uhttpd.${uhttpd_section}.listen_https" \
    | tr ' ' '\n' | grep -qx '127.0.0.1:8443'; then
  log "adding v4 TLS block-page uhttpd listener on 127.0.0.1:8443"
  uci add_list "uhttpd.${uhttpd_section}.listen_https=127.0.0.1:8443"
  uhttpd_changed=1
fi
# #1865: wildcard [::] for v6 TLS, same rationale as listen_http above.
if uci -q get "uhttpd.${uhttpd_section}.listen_https" \
    | tr ' ' '\n' | grep -qx '\[::1\]:8443'; then
  log "migrating v6 TLS block-page listener [::1]:8443 -> [::]:8443"
  uci del_list "uhttpd.${uhttpd_section}.listen_https=[::1]:8443"
  uhttpd_changed=1
fi
if ! uci -q get "uhttpd.${uhttpd_section}.listen_https" \
    | tr ' ' '\n' | grep -qx '\[::\]:8443'; then
  log "adding v6 TLS block-page uhttpd listener on [::]:8443"
  uci add_list "uhttpd.${uhttpd_section}.listen_https=[::]:8443"
  uhttpd_changed=1
fi
current_cert=$(uci -q get "uhttpd.${uhttpd_section}.cert" || echo "")
current_key=$(uci -q get "uhttpd.${uhttpd_section}.key"  || echo "")
if [ "$current_cert" != "$BLOCK_PAGE_CRT" ]; then
  uci set "uhttpd.${uhttpd_section}.cert=$BLOCK_PAGE_CRT"
  uhttpd_changed=1
fi
if [ "$current_key" != "$BLOCK_PAGE_KEY" ]; then
  uci set "uhttpd.${uhttpd_section}.key=$BLOCK_PAGE_KEY"
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
