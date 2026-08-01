#!/bin/sh
# #2554 — regression spec for the install -> uninstall -> install cycle.
#
# The existing install_spec.sh only covers a CLEAN install, which is exactly
# why #2554 survived: `openwrt/uninstall.sh` was `rm`-ing two files the apk/opkg
# package OWNS —
#
#   /etc/config/wifihaven
#   /etc/sysctl.d/99-wifihaven.conf
#
# — which desynchronises the package database. On the next install apk drops the
# config as `/etc/config/wifihaven.apk-new` and does not restore the sysctl file
# at all, so `uci set wifihaven.@wifihaven[0].api_url=...` dies with a bare
# `uci: Entry not found` and the router is left half-installed.
#
# The sysctl half is the dangerous one: it sets
# net.ipv4.conf.br-lan.route_localnet=1, without which the kernel silently drops
# the DNAT'd HTTP/80 traffic that carries blocked clients to the local block
# page. Its absence is INVISIBLE at runtime because setup-uhttpd-block-page.sh
# sets the value live — it only bites after a reboot.
#
# This spec covers three things the clean-install spec cannot:
#   1. uninstall.sh no longer removes package-owned files.
#   2. install.sh's recovery functions actually repair the post-uninstall state
#      (functional simulation against a fake root with stubbed uci/sysctl).
#   3. install.sh's post-install self-check fails LOUDLY and SPECIFICALLY when
#      the individually-silent bits are missing.
#
# Run from the openwrt/ directory:  sh test/install_reinstall_cycle_spec.sh
set -e

PASS=0; FAIL=0
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
INSTALL="$ROOT/install.sh"
UNINSTALL="$ROOT/uninstall.sh"
PKG_SYSCTL="$ROOT/files/etc/sysctl.d/99-wifihaven.conf"

check() {
  if [ "$2" = "ok" ]; then
    printf "  PASS: %s\n" "$1"; PASS=$((PASS + 1))
  else
    printf "  FAIL: %s — %s\n" "$1" "$2"; FAIL=$((FAIL + 1))
  fi
}

for f in "$INSTALL" "$UNINSTALL" "$PKG_SYSCTL"; do
  [ -f "$f" ] || { printf "MISSING: %s\n" "$f"; exit 1; }
done

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# ---------------------------------------------------------------------------
# 1. uninstall.sh must not delete package-owned files.
# ---------------------------------------------------------------------------

if grep -Eq '^[[:space:]]*rm .*(/etc/config/wifihaven)([[:space:]]|$)' "$UNINSTALL"; then
  check "#2554 uninstall.sh does not rm /etc/config/wifihaven (package-owned)" \
        "uninstall.sh still rm's /etc/config/wifihaven — desyncs the apk/opkg file db"
else
  check "#2554 uninstall.sh does not rm /etc/config/wifihaven (package-owned)" ok
fi

if grep -Eq '^[[:space:]]*rm .*/etc/sysctl\.d/99-wifihaven\.conf' "$UNINSTALL"; then
  check "#2554 uninstall.sh does not rm /etc/sysctl.d/99-wifihaven.conf (package-owned)" \
        "uninstall.sh still rm's the route_localnet sysctl file — it never comes back on reinstall"
else
  check "#2554 uninstall.sh does not rm /etc/sysctl.d/99-wifihaven.conf (package-owned)" ok
fi

# #303's actual intent — reverting the LIVE kernel value so LAN clients can't
# route to 127.0.0.0/8 after uninstall — must survive; only the `rm` goes.
grep -q 'sysctl -w net.ipv4.conf.br-lan.route_localnet=0' "$UNINSTALL" \
  && check "#303 uninstall.sh still reverts the live route_localnet value" ok \
  || check "#303 uninstall.sh still reverts the live route_localnet value" \
           "uninstall.sh no longer resets net.ipv4.conf.br-lan.route_localnet=0"

# ---------------------------------------------------------------------------
# 2. install.sh must carry the recovery + self-check machinery.
# ---------------------------------------------------------------------------

for fn in restore_wifihaven_sysctl ensure_wifihaven_config post_install_self_check; do
  grep -q "^${fn}()" "$INSTALL" \
    && check "#2554 install.sh defines ${fn}()" ok \
    || check "#2554 install.sh defines ${fn}()" "missing ${fn}() in install.sh"
done

# The guard must run BEFORE the `uci set wifihaven.@wifihaven[0].api_url` that
# blew up on the affected router.
guard_line=$(grep -n '^ensure_wifihaven_config ' "$INSTALL" | head -n1 | cut -d: -f1)
apiurl_line=$(grep -n 'uci set wifihaven.@wifihaven\[0\].api_url' "$INSTALL" | head -n1 | cut -d: -f1)
if [ -n "$guard_line" ] && [ -n "$apiurl_line" ] && [ "$guard_line" -lt "$apiurl_line" ]; then
  check "#2554 ensure_wifihaven_config runs before the api_url uci set" ok
else
  check "#2554 ensure_wifihaven_config runs before the api_url uci set" \
        "guard is missing or runs after 'uci set wifihaven.@wifihaven[0].api_url' (guard=${guard_line:-none}, uci set=${apiurl_line:-none})"
fi

# SSOT test-pin (docs/process/single-source-of-truth.md, ACCEPT + TEST-PIN):
# install.sh is fetched standalone over the network, so it cannot read the
# package's copy of the sysctl file — the restore path has to carry the setting
# inline. Pin the two copies equal so they cannot drift.
PKG_SYSCTL_LINE=$(grep -E '^net\.ipv4\.conf\.br-lan\.route_localnet' "$PKG_SYSCTL" | head -n1)
[ -n "$PKG_SYSCTL_LINE" ] \
  && check "SSOT: package sysctl file carries the route_localnet setting" ok \
  || check "SSOT: package sysctl file carries the route_localnet setting" \
           "could not read net.ipv4.conf.br-lan.route_localnet from $PKG_SYSCTL"

grep -qF "$PKG_SYSCTL_LINE" "$INSTALL" \
  && check "SSOT: install.sh restore path matches the package sysctl line verbatim" ok \
  || check "SSOT: install.sh restore path matches the package sysctl line verbatim" \
           "install.sh does not contain '$PKG_SYSCTL_LINE' — the restored file would drift from the packaged one"

# ---------------------------------------------------------------------------
# 3. Functional simulation. Extract the recovery functions from install.sh and
#    run them against a fake root with stubbed uci/sysctl, reproducing the
#    exact post-uninstall states seen on the affected router.
# ---------------------------------------------------------------------------

# A deliberately tiny uci stub: the anchor section's existence is read straight
# off the fake /etc/config/wifihaven, so "adopt the .apk-new file" and "the uci
# entry now resolves" are genuinely coupled the way they are on the router.
sim_prelude() {
  cat <<'PRELUDE'
set -eu
info() { printf 'info: %s\n' "$*"; }
err()  { printf 'error: %s\n' "$*" >&2; exit 1; }
sysctl() { printf 'sysctl %s\n' "$*" >> "$SIM_LOG"; }
uci() {
  _q=0
  [ "${1:-}" = "-q" ] && { _q=1; shift; }
  _cmd=${1:-}; shift 2>/dev/null || true
  case "$_cmd $*" in
    "show wifihaven.@wifihaven[0]")
      grep -q "^config wifihaven" "$WIFIHAVEN_CONFIG" 2>/dev/null ;;
    "show uhttpd")
      [ -f "$SIM_UHTTPD" ] && cat "$SIM_UHTTPD" ;;
    "get wifihaven.settings.enforcement_disabled")
      sed -n "s/^[[:space:]]*option enforcement_disabled '\\(.*\\)'/\\1/p" \
        "$WIFIHAVEN_CONFIG" 2>/dev/null | grep . ;;
    "set wifihaven.wifihaven=wifihaven")
      grep -q "^config wifihaven" "$WIFIHAVEN_CONFIG" 2>/dev/null \
        || printf "config wifihaven 'wifihaven'\n" >> "$WIFIHAVEN_CONFIG" ;;
    "commit wifihaven") : ;;
    *) [ "$_q" = 1 ] || printf 'uci: unstubbed call: %s %s\n' "$_cmd" "$*" >&2; return 1 ;;
  esac
}
PRELUDE
  sed -n '/^restore_wifihaven_sysctl()/,/^}/p'  "$INSTALL"
  sed -n '/^ensure_wifihaven_config()/,/^}/p'   "$INSTALL"
  sed -n '/^post_install_self_check()/,/^}/p'   "$INSTALL"
}

# Build a fake root. $1 = scenario name.
#   config=absent|apk-new|opkg-new|present   sysctl=absent|present
new_fake_root() {
  _name=$1; _config=$2; _sysctl=$3
  FR="$TMP/$_name"
  rm -rf "$FR"
  mkdir -p "$FR/etc/config" "$FR/etc/sysctl.d" "$FR/etc/uci-defaults"
  case "$_config" in
    apk-new)  printf "config wifihaven 'wifihaven'\n\toption enforcement_disabled '0'\n" \
                > "$FR/etc/config/wifihaven.apk-new" ;;
    opkg-new) printf "config wifihaven 'wifihaven'\n\toption enforcement_disabled '0'\n" \
                > "$FR/etc/config/wifihaven.opkg-new" ;;
    present)  printf "config wifihaven 'wifihaven'\n\toption enforcement_disabled '0'\n" \
                > "$FR/etc/config/wifihaven" ;;
    absent)   : ;;
  esac
  [ "$_sysctl" = present ] && cp "$PKG_SYSCTL" "$FR/etc/sysctl.d/99-wifihaven.conf"
  cat > "$FR/uhttpd" <<'UHTTPD'
uhttpd.wifihaven=uhttpd
uhttpd.wifihaven.listen_http='127.0.0.1:8081' '[::]:8081'
uhttpd.wifihaven.listen_https='127.0.0.1:8443' '[::]:8443'
uhttpd.wifihaven.lua_handler='/www/wifihaven/handler.lua'
UHTTPD
  : > "$FR/sim.log"
}

# Run shell code with the extracted functions in scope against fake root $FR.
run_sim() {
  sh -c "
    WIFIHAVEN_CONFIG='$FR/etc/config/wifihaven'
    WIFIHAVEN_SYSCTL='$FR/etc/sysctl.d/99-wifihaven.conf'
    WIFIHAVEN_UCI_DEFAULTS='$FR/etc/uci-defaults'
    SIM_UHTTPD='$FR/uhttpd'
    SIM_LOG='$FR/sim.log'
    $(sim_prelude)
    $1
  " 2>&1
}

# --- Scenario A: the exact state #2554 was diagnosed in (apk) --------------
new_fake_root apk apk-new absent
out=$(run_sim 'restore_wifihaven_sysctl; ensure_wifihaven_config' || printf 'SIM-FAILED')
case "$out" in *SIM-FAILED*) recovered=no ;; *) recovered=yes ;; esac

[ "$recovered" = yes ] \
  && check "#2554 recovery succeeds from the post-uninstall apk state" ok \
  || check "#2554 recovery succeeds from the post-uninstall apk state" \
           "recovery returned nonzero: $out"

[ -f "$FR/etc/config/wifihaven" ] && ! [ -f "$FR/etc/config/wifihaven.apk-new" ] \
  && check "#2554 install.sh adopts /etc/config/wifihaven.apk-new" ok \
  || check "#2554 install.sh adopts /etc/config/wifihaven.apk-new" \
           "the .apk-new file was not moved into place"

grep -q "^config wifihaven" "$FR/etc/config/wifihaven" 2>/dev/null \
  && check "#2554 the wifihaven anchor section resolves after adoption" ok \
  || check "#2554 the wifihaven anchor section resolves after adoption" \
           "no 'config wifihaven' section in the adopted file"

grep -qF "$PKG_SYSCTL_LINE" "$FR/etc/sysctl.d/99-wifihaven.conf" 2>/dev/null \
  && check "#2554 install.sh restores the missing route_localnet sysctl FILE" ok \
  || check "#2554 install.sh restores the missing route_localnet sysctl FILE" \
           "/etc/sysctl.d/99-wifihaven.conf was not restored — setting would not survive a reboot"

# --- Scenario B: same, opkg flavour ---------------------------------------
new_fake_root opkg opkg-new absent
run_sim 'restore_wifihaven_sysctl; ensure_wifihaven_config' >/dev/null 2>&1 || true
[ -f "$FR/etc/config/wifihaven" ] && ! [ -f "$FR/etc/config/wifihaven.opkg-new" ] \
  && check "#2554 install.sh adopts /etc/config/wifihaven.opkg-new" ok \
  || check "#2554 install.sh adopts /etc/config/wifihaven.opkg-new" \
           "the .opkg-new file was not moved into place"

# --- Scenario C: nothing on disk at all — the section must be created ------
new_fake_root bare absent absent
out=$(run_sim 'restore_wifihaven_sysctl; ensure_wifihaven_config' || printf 'SIM-FAILED')
case "$out" in
  *SIM-FAILED*) check "#2554 install.sh creates the anchor section when no config file exists" \
                      "recovery returned nonzero: $out" ;;
  *) grep -q "^config wifihaven" "$FR/etc/config/wifihaven" 2>/dev/null \
       && check "#2554 install.sh creates the anchor section when no config file exists" ok \
       || check "#2554 install.sh creates the anchor section when no config file exists" \
                "no config file / anchor section after recovery" ;;
esac

# --- Scenario D: the self-check must fail LOUDLY on the silent failures ----
new_fake_root selfcheck-sysctl present absent
out=$(run_sim 'post_install_self_check' || printf 'SELFCHECK-FAILED')
case "$out" in
  *SELFCHECK-FAILED*) check "#2554 self-check fails when the sysctl file is missing" ok ;;
  *) check "#2554 self-check fails when the sysctl file is missing" \
           "self-check passed with /etc/sysctl.d/99-wifihaven.conf absent: $out" ;;
esac
case "$out" in
  *99-wifihaven.conf*) check "#2554 self-check names the missing sysctl file" ok ;;
  *) check "#2554 self-check names the missing sysctl file" \
           "diagnosis does not name the file: $out" ;;
esac

new_fake_root selfcheck-config absent present
out=$(run_sim 'post_install_self_check' || printf 'SELFCHECK-FAILED')
case "$out" in
  *SELFCHECK-FAILED*) check "#2554 self-check fails when the wifihaven config section is missing" ok ;;
  *) check "#2554 self-check fails when the wifihaven config section is missing" \
           "self-check passed with no wifihaven UCI section: $out" ;;
esac

# --- Scenario E: a healthy router must pass cleanly ------------------------
new_fake_root healthy present present
out=$(run_sim 'post_install_self_check' || printf 'SELFCHECK-FAILED')
case "$out" in
  *SELFCHECK-FAILED*) check "#2554 self-check passes on a healthy install" \
                            "false positive on a healthy fake root: $out" ;;
  *) check "#2554 self-check passes on a healthy install" ok ;;
esac

# --- Scenario F: missing uhttpd block-page listener is caught --------------
new_fake_root no-uhttpd present present
: > "$FR/uhttpd"
out=$(run_sim 'post_install_self_check' || printf 'SELFCHECK-FAILED')
case "$out" in
  *SELFCHECK-FAILED*) check "#2554 self-check fails when the uhttpd block-page listener is missing" ok ;;
  *) check "#2554 self-check fails when the uhttpd block-page listener is missing" \
           "self-check passed with no block-page listener: $out" ;;
esac

printf "\n%d passed, %d failed\n" "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
