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

check() {
  if [ "$2" = "ok" ]; then
    printf "  PASS: %s\n" "$1"; PASS=$((PASS + 1))
  else
    printf "  FAIL: %s — %s\n" "$1" "$2"; FAIL=$((FAIL + 1))
  fi
}

[ -f "$SCRIPT" ] || { printf "MISSING: %s\n" "$SCRIPT"; exit 1; }

# #287: confdir must be set so dnsmasq actually loads
# /tmp/dnsmasq.d/familydns.conf (the agent's rendered profile/NXDOMAIN config).
grep -q "confdir=/tmp/dnsmasq.d" "$SCRIPT" \
  && check "sets dhcp.@dnsmasq[0].confdir=/tmp/dnsmasq.d" ok \
  || check "sets dhcp.@dnsmasq[0].confdir=/tmp/dnsmasq.d" "missing UCI confdir wiring"

# #287: logqueries must be enabled at the UCI layer (not via familydns.conf)
# so dnsmasq emits the structured query+reply lines dns_log.lua parses.
grep -q "logqueries=1" "$SCRIPT" \
  && check "enables dhcp.@dnsmasq[0].logqueries=1" ok \
  || check "enables dhcp.@dnsmasq[0].logqueries=1" "missing UCI logqueries wiring"

# #287: logfacility must be a file path AND set via UCI so dnsmasq's init.d
# binds the file RW into the ujail. Setting it via familydns.conf alone
# leaves the file unwritable from inside the jail.
grep -q "logfacility=/tmp/familydns-dnsmasq.log" "$SCRIPT" \
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

# #303: block-page uhttpd listener must have home=/www/familydns (not /www).
# When nft prerouting DNAT redirects http://<anything>/ to 127.0.0.1:8081,
# uhttpd serves home/index.html — with home=/www that's the LuCI redirect
# page, not the block page (which lives at /www/familydns/index.html).
grep -q "home=/www/familydns" "$SCRIPT" \
  && check "block-page uhttpd home is /www/familydns" ok \
  || check "block-page uhttpd home is /www/familydns" "expected home=/www/familydns"

# #303: the installer must not leave a bare home=/www behind. Use a regex
# that excludes /www/<anything> so home=/www/familydns doesn't match.
if grep -Eq "home=/www([^/a-z]|\$)" "$SCRIPT"; then
  check "no leftover home=/www in block-page section" "found bare home=/www"
else
  check "no leftover home=/www in block-page section" ok
fi

# #303: installer must idempotently fix an existing listener whose home is
# still the pre-#303 value (uci set + commit + uhttpd reload on upgrade).
grep -q "Updating block-page uhttpd home" "$SCRIPT" \
  && check "upgrades existing block-page listener to new home" ok \
  || check "upgrades existing block-page listener to new home" "missing upgrade path"

printf "\n%d passed, %d failed\n" "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
