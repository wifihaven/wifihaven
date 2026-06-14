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

# #569: the release-asset filter must require the wifihaven_ prefix.
# Without it, a stale pre-rename familydns_*.{ipk,apk} co-resident in the
# openwrt-latest release sorts first and gets installed instead.
grep -q "wifihaven_\[\^/\]\*" "$SCRIPT" \
  && check "asset filter requires wifihaven_ prefix" ok \
  || check "asset filter requires wifihaven_ prefix" \
           "install.sh asset filter does not anchor on wifihaven_ prefix"

# #569: the filter must fail loud if multiple assets match (the _all
# package should produce exactly one match; a future surprise should
# not silently install whichever happens to sort first).
grep -q "expected exactly one wifihaven" "$SCRIPT" \
  && check "asset filter errors on multiple matches" ok \
  || check "asset filter errors on multiple matches" \
           "install.sh does not fail loud when >1 wifihaven asset matches"

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

# #383: TLS sibling listener on 127.0.0.1:8443 / [::1]:8443 so HTTPS DNAT
# lands somewhere instead of failing with a connection reset. Same lua
# handler — uhttpd terminates TLS before the handler runs.
grep -q "listen_https=127.0.0.1:8443" "$UHTTPD_HELPER" \
  && check "#383 helper configures TLS listener on 127.0.0.1:8443" ok \
  || check "#383 helper configures TLS listener on 127.0.0.1:8443" \
           "missing listen_https=127.0.0.1:8443 wiring"

grep -q "listen_https=\[::1\]:8443" "$UHTTPD_HELPER" \
  && check "#383 helper configures TLS listener on [::1]:8443" ok \
  || check "#383 helper configures TLS listener on [::1]:8443" \
           "missing listen_https=[::1]:8443 wiring"

grep -q "/etc/wifihaven/block_page.crt" "$UHTTPD_HELPER" \
  && check "#383 helper wires cert=/etc/wifihaven/block_page.crt" ok \
  || check "#383 helper wires cert=/etc/wifihaven/block_page.crt" \
           "missing cert path wiring"

grep -q "/etc/wifihaven/block_page.key" "$UHTTPD_HELPER" \
  && check "#383 helper wires key=/etc/wifihaven/block_page.key" ok \
  || check "#383 helper wires key=/etc/wifihaven/block_page.key" \
           "missing key path wiring"

# #383: helper must generate the self-signed cert before reloading uhttpd,
# otherwise the TLS listener fails to bind on first install.
grep -q "generate-block-page-cert.sh" "$UHTTPD_HELPER" \
  && check "#383 helper invokes generate-block-page-cert.sh" ok \
  || check "#383 helper invokes generate-block-page-cert.sh" \
           "missing cert generation step"

# #383: ship the cert generator + Makefile must install it under /usr/lib/wifihaven.
CERT_GEN="$ROOT/files/usr/lib/wifihaven/generate-block-page-cert.sh"
[ -f "$CERT_GEN" ] \
  && check "#383 generate-block-page-cert.sh shipped in package" ok \
  || check "#383 generate-block-page-cert.sh shipped in package" \
           "missing files/usr/lib/wifihaven/generate-block-page-cert.sh"

grep -q "generate-block-page-cert.sh" "$ROOT/Makefile" \
  && check "#383 Makefile installs generate-block-page-cert.sh" ok \
  || check "#383 Makefile installs generate-block-page-cert.sh" \
           "Package/wifihaven/install doesn't ship the cert generator"

# #383: package depends must pull in TLS support for uhttpd. libustream-mbedtls
# is the default OpenWRT TLS backend and what uhttpd's listen_https expects.
# openssl-util provides /usr/bin/openssl for the cert generator.
grep -Eq "libustream-(mbedtls|openssl|wolfssl)" "$ROOT/Makefile" \
  && check "#383 Makefile DEPENDS includes libustream-* for uhttpd TLS" ok \
  || check "#383 Makefile DEPENDS includes libustream-* for uhttpd TLS" \
           "missing libustream-mbedtls dep"

grep -q "openssl-util" "$ROOT/Makefile" \
  && check "#383 Makefile DEPENDS includes openssl-util for cert generation" ok \
  || check "#383 Makefile DEPENDS includes openssl-util for cert generation" \
           "missing openssl-util dep"

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

# #528 (post-#1717 collapse): the wifihaven package's DEPENDS must include
# uhttpd-mod-lua so the lua_handler wired in just above actually runs —
# without the module uhttpd silently ignores lua_handler, falls back to
# static-file serving, and 404s every request (the legacy index.html was
# removed alongside the handler).
#
# Pre-#1717 this was checked by grepping uhttpd-mod-lua out of build-ipk.sh
# and build-apk.sh, which kept their own hard-coded Depends strings. #1717
# collapsed that drift: the canonical DEPENDS lives in openwrt/Makefile and
# both builders derive their control-file Depends from it via
# openwrt/depends-list.sh (pinned by build_depends_sync_spec.test.sh). So
# checking openwrt/Makefile is the right place to enforce the
# uhttpd-mod-lua invariant — both .ipk and .apk inherit it automatically.
grep -q '^[[:space:]]*DEPENDS:=.*+uhttpd-mod-lua\b' "$ROOT/Makefile" \
  && check "Makefile DEPENDS pulls in uhttpd-mod-lua" ok \
  || check "Makefile DEPENDS pulls in uhttpd-mod-lua" \
           "missing +uhttpd-mod-lua in openwrt/Makefile DEPENDS"

# #1278: luci-app-wifihaven is a classic Lua/CBI LuCI app. On modern OpenWRT
# (23.05+, apk-based 24.10) luci-base is the ucode/JS framework; the server-
# side Lua dispatcher/CBI runtime ships separately in luci-compat. Without it
# the page renders "No Lua runtime installed." All three dep declarations for
# the luci app (Makefile + both build scripts, since Image Builder reads the
# built package metadata, not the Makefile) must pull in luci-compat.
grep -q "luci-compat" "$ROOT/luci/Makefile" \
  && check "luci/Makefile LUCI_DEPENDS pulls in luci-compat" ok \
  || check "luci/Makefile LUCI_DEPENDS pulls in luci-compat" \
           "missing luci-compat dep in luci/Makefile"

grep -q "luci-compat" "$ROOT/luci/build-ipk.sh" \
  && check "luci/build-ipk.sh Depends pulls in luci-compat" ok \
  || check "luci/build-ipk.sh Depends pulls in luci-compat" \
           "missing luci-compat dep in luci/build-ipk.sh"

grep -q "luci-compat" "$ROOT/luci/build-apk.sh" \
  && check "luci/build-apk.sh depends pulls in luci-compat" ok \
  || check "luci/build-apk.sh depends pulls in luci-compat" \
           "missing luci-compat dep in luci/build-apk.sh"

# #704: ensure_dnsmasq_full apk branch must use `apk info -e`, not
# `apk list -I`.  On apk-tools v3 (OpenWRT 24.10+) `apk list -I` exits 0
# with empty stdout when the package is absent, so the old check always
# returned early and left plain dnsmasq installed.
#
# Canary A — the probe must be `apk info -e`, not `apk list -I`:
grep -q "apk info -e dnsmasq-full" "$SCRIPT" \
  && check "#704 ensure_dnsmasq_full uses apk info -e for dnsmasq-full check" ok \
  || check "#704 ensure_dnsmasq_full uses apk info -e for dnsmasq-full check" \
           "apk branch still uses 'apk list -I dnsmasq-full' — exits 0 when absent on apk-tools v3"

grep -q "apk info -e dnsmasq " "$SCRIPT" \
  && check "#704 ensure_dnsmasq_full uses apk info -e for dnsmasq check" ok \
  || check "#704 ensure_dnsmasq_full uses apk info -e for dnsmasq check" \
           "apk branch still uses 'apk list -I dnsmasq' — exits 0 when absent on apk-tools v3"

# Canary B — regression guard: the old broken form must NOT appear:
if grep -q "apk list -I dnsmasq-full" "$SCRIPT"; then
  check "#704 broken apk list -I form removed from dnsmasq-full check" \
        "'apk list -I dnsmasq-full' still present — reverts #704 fix"
else
  check "#704 broken apk list -I form removed from dnsmasq-full check" ok
fi

if grep -q "apk list -I dnsmasq " "$SCRIPT"; then
  check "#704 broken apk list -I form removed from dnsmasq check" \
        "'apk list -I dnsmasq ' still present — reverts #704 fix"
else
  check "#704 broken apk list -I form removed from dnsmasq check" ok
fi

# Canary C — functional simulation: verify ensure_dnsmasq_full installs
# dnsmasq-full when only plain dnsmasq is present, using stub apk/err
# functions that replicate the apk-tools v3 exit-code behaviour.
# Pre-fix, `apk list -I dnsmasq-full` exited 0 even when absent, causing
# an early return before the swap.  Post-fix, `apk info -e dnsmasq-full`
# exits nonzero on absence, so the function proceeds to del+add.
#
# Side-effects are recorded in a temp file because install.sh suppresses
# stdout from apk calls (>/dev/null).
_sim_log=$(mktemp)
trap 'rm -f "$_sim_log"' EXIT
sh -c "
  set -eu
  err() { printf 'error: %s\n' \"\$*\" >&2; exit 1; }
  info() { true; }
  apk() {
    _cmd=\"\$*\"
    case \"\$_cmd\" in
      'info -e dnsmasq-full') return 1;;   # not installed — apk-tools v3 exits nonzero
      'info -e dnsmasq')      return 0;;   # plain dnsmasq IS installed
      'del dnsmasq')          printf 'del-dnsmasq\n' >> '${_sim_log}';;
      'add dnsmasq-full')     printf 'add-dnsmasq-full\n' >> '${_sim_log}';;
      *)                      true;;
    esac
  }
  PKG_MGR=apk
  $(sed -n '/^ensure_dnsmasq_full()/,/^}/p' "$SCRIPT")
  ensure_dnsmasq_full
" 2>/dev/null || true
if grep -q "add-dnsmasq-full" "$_sim_log"; then
  check "#704 ensure_dnsmasq_full installs dnsmasq-full when only dnsmasq present (apk-tools v3)" ok
else
  check "#704 ensure_dnsmasq_full installs dnsmasq-full when only dnsmasq present (apk-tools v3)" \
        "function returned early without installing dnsmasq-full (sim log: $(cat "$_sim_log"))"
fi

# #869: both the IPK postinst (openwrt/Makefile Package/wifihaven/postinst)
# and the APK postinst (openwrt/build-apk.sh post-install heredoc) must
# register the wifihaven-update cron entry. The APK builder originally
# dropped this block, so APK-installed routers never got /etc/crontabs/root
# wired up and auto-update never ran. Guard both producers so they can't
# drift again.
for f in "$ROOT/Makefile" "$ROOT/build-apk.sh"; do
  label=$(basename "$f")
  grep -q "wifihaven-update" "$f" \
    && check "#869 $label postinst references wifihaven-update cron" ok \
    || check "#869 $label postinst references wifihaven-update cron" \
             "missing cron registration for /usr/sbin/wifihaven-update"

  grep -q "/usr/sbin/wifihaven-update" "$f" \
    && check "#869 $label postinst writes /usr/sbin/wifihaven-update to crontab" ok \
    || check "#869 $label postinst writes /usr/sbin/wifihaven-update to crontab" \
             "missing crontab line for /usr/sbin/wifihaven-update"

  grep -q "/etc/init.d/cron restart" "$f" \
    && check "#869 $label postinst restarts cron" ok \
    || check "#869 $label postinst restarts cron" \
             "postinst doesn't restart cron after writing crontab"
done

# #898: build-apk.sh must also register an apk-tools v3 `trigger` script
# keyed on /etc/crontabs. apk v3 skips `post-install` on a same-version
# `apk add --force-reinstall`, so without a trigger a manual reinstall to
# repair /etc/crontabs/root is a no-op. The trigger fires whenever any
# install touches a path matching its key, and database.c sets
# run_all_triggers=1 on every install of our own package — so reinstalling
# ourselves fires it even if /etc/crontabs is untouched on disk.
APK="$ROOT/build-apk.sh"
grep -q '"trigger:\$WORK/trigger"' "$APK" \
  && check "#898 build-apk.sh declares trigger script via --script trigger:" ok \
  || check "#898 build-apk.sh declares trigger script via --script trigger:" \
           "build-apk.sh missing --script trigger:... — apk add --force-reinstall would skip cron block"

grep -q '\-\-trigger "/etc/crontabs"' "$APK" \
  && check "#898 build-apk.sh registers /etc/crontabs as trigger key" ok \
  || check "#898 build-apk.sh registers /etc/crontabs as trigger key" \
           "build-apk.sh missing --trigger /etc/crontabs"

# The trigger body must mirror the cron-installation block (same canonical
# crontab line + cron restart) so reinstall ends in the same state as a
# fresh install.
awk '/cat > "\$WORK\/trigger"/,/^TRIGGER$/' "$APK" \
  | grep -q "0 \* \* \* \* /usr/sbin/wifihaven-update --jitter" \
  && check "#898/#1414 trigger script writes canonical hourly wifihaven-update crontab line" ok \
  || check "#898/#1414 trigger script writes canonical hourly wifihaven-update crontab line" \
           "trigger heredoc missing the canonical '0 * * * * /usr/sbin/wifihaven-update --jitter' line"

awk '/cat > "\$WORK\/trigger"/,/^TRIGGER$/' "$APK" \
  | grep -q "/etc/init.d/cron restart" \
  && check "#898 trigger script restarts cron" ok \
  || check "#898 trigger script restarts cron" \
           "trigger heredoc doesn't restart cron after writing crontab"

printf "\n%d passed, %d failed\n" "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
