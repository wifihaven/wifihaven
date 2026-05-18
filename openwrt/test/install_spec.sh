#!/bin/sh
# Shell-level smoke tests for openwrt/install.sh.
# Run from the openwrt/ directory:  sh test/install_spec.sh
#
# These check the bits that can't be exercised by Lua specs — specifically
# that the installer wires dnsmasq to load the agent's rendered config
# (#287). Without these UCI options the agent silently writes a config file
# dnsmasq never reads, query logging never turns on, and the hostname
# attribution path is dead.
set -e

PASS=0; FAIL=0
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$ROOT/install.sh"
# #542: the uhttpd block-page wiring is now in a shared helper that both
# install.sh (manual installer) and the package postinst (.ipk/.apk + the
# OpenWRT Makefile) invoke, so they can't drift. Several greps below run
# against the helper for that reason.
UHTTPD_HELPER="$ROOT/files/usr/lib/wifihaven/setup-uhttpd-block-page.sh"

check() {
  if [ "$2" = "ok" ]; then
    printf "  PASS: %s\n" "$1"; PASS=$((PASS + 1))
  else
    printf "  FAIL: %s — %s\n" "$1" "$2"; FAIL=$((FAIL + 1))
  fi
}

[ -f "$SCRIPT" ] || { printf "MISSING: %s\n" "$SCRIPT"; exit 1; }
[ -f "$UHTTPD_HELPER" ] || { printf "MISSING: %s\n" "$UHTTPD_HELPER"; exit 1; }

# #287: confdir must be set so dnsmasq actually loads
# /tmp/dnsmasq.d/wifihaven.conf (the agent's rendered profile/NXDOMAIN config).
grep -q "confdir=/tmp/dnsmasq.d" "$SCRIPT" \
  && check "sets dhcp.@dnsmasq[0].confdir=/tmp/dnsmasq.d" ok \
  || check "sets dhcp.@dnsmasq[0].confdir=/tmp/dnsmasq.d" "missing UCI confdir wiring"

# #287: logqueries must be enabled at the UCI layer (not via wifihaven.conf)
# so dnsmasq emits the structured query+reply lines dns_log.lua parses.
grep -q "logqueries=1" "$SCRIPT" \
  && check "enables dhcp.@dnsmasq[0].logqueries=1" ok \
  || check "enables dhcp.@dnsmasq[0].logqueries=1" "missing UCI logqueries wiring"

# #287: logfacility must be a file path AND set via UCI so dnsmasq's init.d
# binds the file RW into the ujail. Setting it via wifihaven.conf alone
# leaves the file unwritable from inside the jail.
grep -q "logfacility=/tmp/wifihaven-dnsmasq.log" "$SCRIPT" \
  && check "sets dhcp.@dnsmasq[0].logfacility to the agent's tail path" ok \
  || check "sets dhcp.@dnsmasq[0].logfacility to the agent's tail path" \
           "missing UCI logfacility wiring"

# #287: the installer must restart dnsmasq after touching the UCI options —
# a reload won't regenerate /var/etc/dnsmasq.conf.cfg01411c.
grep -q "/etc/init.d/dnsmasq restart" "$SCRIPT" \
  && check "restarts dnsmasq after UCI changes" ok \
  || check "restarts dnsmasq after UCI changes" "missing dnsmasq restart"

# #281 / TODO(#244): install.sh must fetch the rolling openwrt-latest
# release, not /releases/latest. The latter pins the install to a stale
# v0.0.99 snapshot and the auto-updater is then stuck behind it until
# the 24h cron tick, leaving fresh installs up to a day stale.
grep -q "releases/tags/openwrt-latest" "$SCRIPT" \
  && check "install.sh fetches releases/tags/openwrt-latest" ok \
  || check "install.sh fetches releases/tags/openwrt-latest" \
           "install.sh appears to hit /releases/latest — would land stale build"

# #197: the installer must not collect a router name. The name is set
# once in the admin UI when the enrollment token is issued; the install
# script only needs the token. A second prompt risks a mismatch that
# confuses users.
grep -qE "Router name|router_name|routerName" "$SCRIPT" \
  && check "install.sh does not prompt for router name" \
           "install.sh still references router-name input" \
  || check "install.sh does not prompt for router name" ok

# #303 + #437 + #542: block-page uhttpd UCI lives in the shared helper.
# install.sh must invoke it (not inline the UCI calls — that was #542's
# drift bug between install.sh and the package postinst).

grep -q "setup-uhttpd-block-page.sh" "$SCRIPT" \
  && check "install.sh invokes setup-uhttpd-block-page.sh helper" ok \
  || check "install.sh invokes setup-uhttpd-block-page.sh helper" \
           "install.sh no longer wires uhttpd directly; must call the helper"

# Helper itself must wire the right values. These were the inline asserts
# pre-#542; they live on the helper now.
grep -q "home=/www/wifihaven" "$UHTTPD_HELPER" \
  && check "block-page uhttpd home is /www/wifihaven" ok \
  || check "block-page uhttpd home is /www/wifihaven" "expected home=/www/wifihaven"

if grep -Eq "home=/www([^/a-z]|\$)" "$UHTTPD_HELPER"; then
  check "no leftover home=/www in helper" "found bare home=/www"
else
  check "no leftover home=/www in helper" ok
fi

grep -q "updating block-page uhttpd home" "$UHTTPD_HELPER" \
  && check "helper has upgrade path for existing block-page listener" ok \
  || check "helper has upgrade path for existing block-page listener" \
           "missing upgrade path"

grep -q "lua_prefix=" "$UHTTPD_HELPER" \
  && check "helper configures lua_prefix on block-page listener" ok \
  || check "helper configures lua_prefix on block-page listener" "missing lua_prefix"

grep -q "/www/wifihaven/handler.lua" "$UHTTPD_HELPER" \
  && check "helper configures lua_handler pointing at handler.lua" ok \
  || check "helper configures lua_handler pointing at handler.lua" \
           "missing lua_handler wiring"

# #542: the uci-defaults stub runs the helper at first boot. uci-defaults
# is the right idiom for postinst-style work that needs the live system
# (procd, running uhttpd, mounted /etc/config) — postinst itself fires at
# offline-install time when none of those exist. /etc/init.d/done runs
# every file in /etc/uci-defaults/ at first boot and deletes any that
# exit zero.
UHTTPD_DEFAULTS_STUB="$ROOT/files/etc/uci-defaults/95-wifihaven-uhttpd"
[ -f "$UHTTPD_DEFAULTS_STUB" ] \
  && check "/etc/uci-defaults/95-wifihaven-uhttpd shipped in package" ok \
  || check "/etc/uci-defaults/95-wifihaven-uhttpd shipped in package" \
           "missing first-boot trigger for uhttpd block-page setup"

grep -q "setup-uhttpd-block-page.sh" "$UHTTPD_DEFAULTS_STUB" \
  && check "uci-defaults stub invokes setup-uhttpd-block-page.sh" ok \
  || check "uci-defaults stub invokes setup-uhttpd-block-page.sh" \
           "stub doesn't call the helper"

# Makefile must ship the uci-defaults stub so Image-Builder-installed routers
# pick it up at first boot.
grep -q "95-wifihaven-uhttpd" "$ROOT/Makefile" \
  && check "Makefile installs the uci-defaults stub" ok \
  || check "Makefile installs the uci-defaults stub" \
           "Package/wifihaven/install doesn't ship 95-wifihaven-uhttpd"

# #437: the new handler ships at this path; ensure the file exists in tree.
[ -f "$ROOT/files/www/wifihaven/handler.lua" ] \
  && check "handler.lua exists in tree" ok \
  || check "handler.lua exists in tree" "missing openwrt/files/www/wifihaven/handler.lua"

# #437: the static index.html is gone (replaced by the lua handler).
[ ! -f "$ROOT/files/www/wifihaven/index.html" ] \
  && check "legacy static index.html has been removed" ok \
  || check "legacy static index.html has been removed" \
           "openwrt/files/www/wifihaven/index.html still present"

# #528: build-ipk.sh and build-apk.sh both hardcode the package's depends
# line (the OpenWRT Image Builder reads the package metadata, not the
# Makefile, when assembling the router image). Both must include
# uhttpd-mod-lua so the lua_handler wired in just above actually runs —
# without the module uhttpd silently ignores lua_handler, falls back to
# static-file serving, and 404s every request (the legacy index.html was
# removed alongside the handler).
grep -q "uhttpd-mod-lua" "$ROOT/build-ipk.sh" \
  && check "build-ipk.sh Depends pulls in uhttpd-mod-lua" ok \
  || check "build-ipk.sh Depends pulls in uhttpd-mod-lua" \
           "missing uhttpd-mod-lua dep in build-ipk.sh"

grep -q "uhttpd-mod-lua" "$ROOT/build-apk.sh" \
  && check "build-apk.sh depends pulls in uhttpd-mod-lua" ok \
  || check "build-apk.sh depends pulls in uhttpd-mod-lua" \
           "missing uhttpd-mod-lua dep in build-apk.sh"

printf "\n%d passed, %d failed\n" "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
