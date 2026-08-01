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
# This spec covers four things the clean-install spec cannot:
#   1. uninstall.sh no longer removes package-owned files.
#   2. install.sh's recovery functions actually repair the post-uninstall state
#      (functional simulation against a fake root with stubbed uci/sysctl).
#   3. install.sh's post-install self-check fails LOUDLY and SPECIFICALLY when
#      the individually-silent bits are missing.
#   4. uninstall.sh still wipes the router bearer token now that unlinking the
#      file is no longer what erases it — including the fail-safe for when the
#      UCI scrub doesn't take.
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

# Match any delete-flavoured command naming the path, in either literal or
# variable spelling, so the guard isn't defeated by `rm -f "$WIFIHAVEN_CONFIG"`,
# a trailing glob, or `find … -delete`.
deletes_path() {
  # deletes_path FILE REGEX-FOR-PATH
  grep -Eq "(rm|unlink|shred)[[:space:]].*($2)|(find[[:space:]].*($2).*-delete)" "$1"
}

if deletes_path "$UNINSTALL" '/etc/config/wifihaven|\$\{?WIFIHAVEN_CONFIG'; then
  check "#2554 uninstall.sh does not rm /etc/config/wifihaven (package-owned)" \
        "uninstall.sh still rm's /etc/config/wifihaven — desyncs the apk/opkg file db"
else
  check "#2554 uninstall.sh does not rm /etc/config/wifihaven (package-owned)" ok
fi

if deletes_path "$UNINSTALL" '/etc/sysctl\.d/99-wifihaven\.conf|\$\{?WIFIHAVEN_SYSCTL'; then
  check "#2554 uninstall.sh does not rm /etc/sysctl.d/99-wifihaven.conf (package-owned)" \
        "uninstall.sh still rm's the route_localnet sysctl file — it never comes back on reinstall"
else
  check "#2554 uninstall.sh does not rm /etc/sysctl.d/99-wifihaven.conf (package-owned)" ok
fi

# #2078's update-signature key is staged into the package by build-ipk.sh /
# build-apk.sh (they copy openwrt/files/ wholesale), so it is package-owned too.
# A blanket `rm -rf /etc/wifihaven` takes it out from under apk, and the symptom
# is silent: wifihaven-update fails closed on a missing key and the router just
# stops auto-updating.
if grep -Eq '(rm|unlink|shred)[[:space:]].*/etc/wifihaven(/keys)?[[:space:]]*$' "$UNINSTALL"; then
  check "#2554 uninstall.sh does not rm -rf all of /etc/wifihaven (keys/release.pub is package-owned)" \
        "uninstall.sh removes the whole /etc/wifihaven tree, taking keys/release.pub with it"
else
  check "#2554 uninstall.sh does not rm -rf all of /etc/wifihaven (keys/release.pub is package-owned)" ok
fi

# The uninstaller's advertised job is to wipe the router bearer token. `uci
# delete <package>` is not a complete uci lookup and deletes nothing, so the
# scrub must enumerate sections — and must verify rather than assume.
if grep -Eq 'uci[[:space:]].*delete[[:space:]]+"?wifihaven"?([[:space:]]|>|;|$)' "$UNINSTALL"; then
  check "#2554 uninstall.sh does not rely on the no-op 'uci delete <package>' form" \
        "uninstall.sh still loops on 'uci delete wifihaven' — a package-only pointer deletes nothing"
else
  check "#2554 uninstall.sh does not rely on the no-op 'uci delete <package>' form" ok
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
# Pin EVERY directive the package file carries, not just the first — a second
# interface, a conf.all sibling, or a changed value on a later line must fail
# here rather than silently drift out of install.sh's inline copy.
PKG_SYSCTL_DIRECTIVES=$(grep -Ev '^[[:space:]]*(#|$)' "$PKG_SYSCTL")
PKG_SYSCTL_LINE=$(printf '%s\n' "$PKG_SYSCTL_DIRECTIVES" | head -n1)
[ -n "$PKG_SYSCTL_LINE" ] \
  && check "SSOT: package sysctl file carries the route_localnet setting" ok \
  || check "SSOT: package sysctl file carries the route_localnet setting" \
           "could not read any directive from $PKG_SYSCTL"

_sysctl_drift=""
printf '%s\n' "$PKG_SYSCTL_DIRECTIVES" | while IFS= read -r _d; do
  [ -n "$_d" ] || continue
  grep -qF "$_d" "$INSTALL" || printf '%s\n' "$_d"
done > "$TMP/sysctl-drift"
_sysctl_drift=$(cat "$TMP/sysctl-drift")
[ -z "$_sysctl_drift" ] \
  && check "SSOT: install.sh restore path carries every packaged sysctl directive verbatim" ok \
  || check "SSOT: install.sh restore path carries every packaged sysctl directive verbatim" \
           "install.sh is missing: $_sysctl_drift — the restored file would drift from the packaged one"

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
  # Guard against a vacuous suite: if a rename/reindent/move ever makes the
  # sed extraction above yield nothing, every "the check fails" scenario below
  # would pass on exit 127 (command not found) rather than on behaviour.
  cat <<'GUARD'
for _fn in restore_wifihaven_sysctl ensure_wifihaven_config post_install_self_check; do
  command -v "$_fn" >/dev/null 2>&1 \
    || { printf 'EXTRACTION-FAILED: %s not extracted from install.sh\n' "$_fn" >&2; exit 99; }
done
GUARD
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
#
# Each case asserts on the SPECIFIC diagnosis, not merely on a nonzero exit —
# a nonzero exit alone would also be produced by a missing function.
assert_selfcheck_fails() {
  # assert_selfcheck_fails LABEL OUTPUT NEEDLE
  case "$2" in
    *EXTRACTION-FAILED*) check "$1" "the functions were not extracted from install.sh: $2"; return ;;
    *SELFCHECK-FAILED*) : ;;
    *) check "$1" "self-check passed when it should have failed: $2"; return ;;
  esac
  case "$2" in
    *"$3"*) check "$1" ok ;;
    *) check "$1" "failed, but the diagnosis never mentions '$3': $2" ;;
  esac
}

new_fake_root selfcheck-sysctl present absent
out=$(run_sim 'post_install_self_check' || printf 'SELFCHECK-FAILED')
assert_selfcheck_fails "#2554 self-check fails and names the missing sysctl file" \
  "$out" "99-wifihaven.conf is missing on disk"

new_fake_root selfcheck-config absent present
out=$(run_sim 'post_install_self_check' || printf 'SELFCHECK-FAILED')
assert_selfcheck_fails "#2554 self-check fails and names the missing wifihaven config section" \
  "$out" "anchor section is missing"

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
assert_selfcheck_fails "#2554 self-check fails and names the missing uhttpd block-page listener" \
  "$out" "127.0.0.1:8081"

# --- Scenario G: an unrelated uhttpd listener must NOT satisfy the check ---
# The stock `uhttpd.main.listen_http='0.0.0.0:80'` plus a stray 8081/8443 on
# some other section would satisfy an unanchored substring match; the check has
# to identify the block-page listener per-line, the same way
# setup-uhttpd-block-page.sh and uninstall.sh do.
new_fake_root decoy-uhttpd present present
cat > "$FR/uhttpd" <<'DECOY'
uhttpd.main=uhttpd
uhttpd.main.listen_http='0.0.0.0:80'
uhttpd.other.redirect_https='127.0.0.1:8081'
uhttpd.other.note='127.0.0.1:8443'
DECOY
out=$(run_sim 'post_install_self_check' || printf 'SELFCHECK-FAILED')
assert_selfcheck_fails "#2554 self-check is not satisfied by an unrelated uhttpd listener" \
  "$out" "127.0.0.1:8081"

# ---------------------------------------------------------------------------
# 4. uninstall.sh must still wipe the router bearer token.
#
# Dropping the `rm` (part 1) took away the thing that used to erase the token by
# unlinking the file, so the UCI scrub is now load-bearing for a security
# property. Exercise it against a fake root with a stubbed uci, including the
# case where `uci delete` does nothing at all — the fail-safe must still leave
# no token on disk, WITHOUT removing the (package-owned) path.
# ---------------------------------------------------------------------------

TOKEN='SECRET-ROUTER-TOKEN'

uninstall_sim_prelude() {
  cat <<'PRELUDE'
set -eu
info() { printf 'info: %s\n' "$*"; }
note() { printf 'note: %s\n' "$*"; }
# A small file-backed uci that models the real semantics we depend on:
# `uci delete <package>` (no section) is NOT a complete lookup and deletes
# nothing, and section deletes rewrite the file.
uci() {
  [ "${1:-}" = "-q" ] && shift
  _cmd=${1:-}; _arg=${2:-}
  case "$_cmd" in
    show)
      [ -f "$WIFIHAVEN_CONFIG" ] || return 1
      awk '
        /^config /{ t=$2; n=$3; gsub(/\047/,"",n); sec=n; printf "wifihaven.%s=%s\n", sec, t; next }
        /^[[:space:]]*option /{ if (sec != "") printf "wifihaven.%s.%s=%s\n", sec, $2, $3 }
      ' "$WIFIHAVEN_CONFIG" | grep . ;;
    delete)
      case "$_arg" in
        wifihaven) return 1 ;;                       # package-only pointer: no-op
        wifihaven.*)
          [ "${SIM_UCI_DELETE_BROKEN:-0}" = "1" ] && return 1
          _sec=${_arg#wifihaven.}
          awk -v sec="$_sec" '
            /^config /{ n=$3; gsub(/\047/,"",n); skip = (n == sec); if (skip) next }
            { if (!skip) print }
          ' "$WIFIHAVEN_CONFIG" > "$WIFIHAVEN_CONFIG.tmp"
          mv "$WIFIHAVEN_CONFIG.tmp" "$WIFIHAVEN_CONFIG" ;;
        *) return 1 ;;
      esac ;;
    get)
      case "$_arg" in
        "wifihaven.@wifihaven[0].router_token")
          sed -n "s/^[[:space:]]*option[[:space:]]\\{1,\\}router_token[[:space:]]\\{1,\\}'\\(.*\\)'/\\1/p" \
            "$WIFIHAVEN_CONFIG" 2>/dev/null | grep . ;;
        *) return 1 ;;
      esac ;;
    commit) : ;;
    *) return 1 ;;
  esac
}
PRELUDE
  sed -n '/^scrub_wifihaven_config()/,/^}/p' "$UNINSTALL"
  cat <<'GUARD'
command -v scrub_wifihaven_config >/dev/null 2>&1 \
  || { printf 'EXTRACTION-FAILED: scrub_wifihaven_config not extracted\n' >&2; exit 99; }
GUARD
}

new_enrolled_config() {
  # $1 = scenario dir name
  FR="$TMP/$1"
  rm -rf "$FR"; mkdir -p "$FR/etc/config"
  cat > "$FR/etc/config/wifihaven" <<EOF
config wifihaven 'wifihaven'
	option api_url 'https://api.wifihaven.net'
	option router_token '$TOKEN'

config settings 'settings'
	option enforcement_disabled '0'
EOF
}

run_uninstall_sim() {
  # $1 = extra env assignments
  sh -c "
    WIFIHAVEN_CONFIG='$FR/etc/config/wifihaven'
    $1
    $(uninstall_sim_prelude)
    scrub_wifihaven_config || true
  " 2>&1
}

# --- Scenario H: the ordinary scrub erases the token ----------------------
new_enrolled_config wipe-ok
out=$(run_uninstall_sim "" || printf 'EXTRACTION-FAILED')
case "$out" in
  *EXTRACTION-FAILED*) check "#2554 uninstall.sh exposes the scrub as an extractable function" "$out" ;;
  *) check "#2554 uninstall.sh exposes the scrub as an extractable function" ok ;;
esac

if grep -q "$TOKEN" "$FR/etc/config/wifihaven" 2>/dev/null; then
  check "#2554 uninstall.sh scrub erases router_token" \
        "the bearer token is still on disk after the scrub: $out"
else
  check "#2554 uninstall.sh scrub erases router_token" ok
fi

[ -f "$FR/etc/config/wifihaven" ] \
  && check "#2554 uninstall.sh scrub keeps the package-owned path in place" ok \
  || check "#2554 uninstall.sh scrub keeps the package-owned path in place" \
           "the config file was removed — that is the apk file-db desync this issue is about"

# --- Scenario I: the fail-safe when the section deletes don't take --------
new_enrolled_config wipe-failsafe
out=$(run_uninstall_sim "SIM_UCI_DELETE_BROKEN=1" || printf 'EXTRACTION-FAILED')
if grep -q "$TOKEN" "$FR/etc/config/wifihaven" 2>/dev/null; then
  check "#2554 uninstall.sh truncates the config when the UCI scrub doesn't take" \
        "the bearer token survived an uninstall: $out"
else
  check "#2554 uninstall.sh truncates the config when the UCI scrub doesn't take" ok
fi

[ -f "$FR/etc/config/wifihaven" ] \
  && check "#2554 the fail-safe truncates rather than removes the file" ok \
  || check "#2554 the fail-safe truncates rather than removes the file" \
           "the file was unlinked — truncation is required so the package db stays in sync"

case "$out" in
  *"router_token survived"*) check "#2554 the fail-safe reports what it actually did" ok ;;
  *) check "#2554 the fail-safe reports what it actually did" \
           "no note about the truncation fail-safe: $out" ;;
esac

printf "\n%d passed, %d failed\n" "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
