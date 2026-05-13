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

printf "\n%d passed, %d failed\n" "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
