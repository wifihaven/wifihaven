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

SKIP=0
skip() {
  printf "  SKIP: %s — %s\n" "$1" "$2"; SKIP=$((SKIP + 1))
}

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

# Run the simulations under the strictest POSIX shell available. The target is
# BusyBox ash, and bash — which is /bin/sh on macOS — is lenient about things
# ash is not: a redirection error on a POSIX *special* built-in (`:`, `.`,
# `exec`, …) terminates ash/dash outright and is not catchable with `2>/dev/null
# || true`, while bash merely warns. Simulating under bash would hide exactly
# that class of bug.
SIM_SH=$(command -v dash 2>/dev/null || command -v ash 2>/dev/null || printf '')
if [ -n "$SIM_SH" ]; then
  SIM_SH_STRICT=1
else
  # No strict shell available (bash is /bin/sh on macOS, RHEL/Fedora ship
  # neither dash nor a standalone ash). Fall back so the rest of the suite still
  # runs, but say so loudly and SKIP — never PASS — the scenarios that can only
  # be judged under a strict shell. CI runs on ubuntu-latest, which has dash.
  SIM_SH=$(command -v sh)
  SIM_SH_STRICT=0
  printf '  NOTE: no dash/ash found — simulations run under %s; strict-shell scenarios will be SKIPPED.\n' \
    "$(command -v sh)"
fi

# ---------------------------------------------------------------------------
# 1. uninstall.sh must not delete package-owned files.
# ---------------------------------------------------------------------------

# Match any delete-flavoured command naming the path, in either literal or
# variable spelling, so the guard isn't defeated by `rm -f "$WIFIHAVEN_CONFIG"`,
# a trailing glob, or `find … -delete`.
# Guards run against the CODE only — a comment mentioning `rm /etc/config/...`
# (this file and uninstall.sh both explain why the rm is gone) must not trip them.
UNINSTALL_CODE="$TMP/uninstall.code.sh"
grep -v '^[[:space:]]*#' "$UNINSTALL" > "$UNINSTALL_CODE"

deletes_path() {
  # deletes_path FILE REGEX-FOR-PATH
  grep -Eq "(^|[[:space:];&|])(rm|unlink|shred)[[:space:]].*($2)|(find[[:space:]].*($2).*-delete)" "$1"
}

if deletes_path "$UNINSTALL_CODE" '/etc/config/wifihaven|\$\{?WIFIHAVEN_CONFIG'; then
  check "#2554 uninstall.sh does not rm /etc/config/wifihaven (package-owned)" \
        "uninstall.sh still rm's /etc/config/wifihaven — desyncs the apk/opkg file db"
else
  check "#2554 uninstall.sh does not rm /etc/config/wifihaven (package-owned)" ok
fi

if deletes_path "$UNINSTALL_CODE" '/etc/sysctl\.d/99-wifihaven\.conf|\$\{?WIFIHAVEN_SYSCTL'; then
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
if deletes_path "$UNINSTALL_CODE" '/etc/wifihaven(/keys)?/?([[:space:]]|"|>|;|$)|\$\{?WIFIHAVEN_RUNTIME_DIR'; then
  check "#2554 uninstall.sh does not rm -rf all of /etc/wifihaven (keys/release.pub is package-owned)" \
        "uninstall.sh removes the whole /etc/wifihaven tree, taking keys/release.pub with it"
else
  check "#2554 uninstall.sh does not rm -rf all of /etc/wifihaven (keys/release.pub is package-owned)" ok
fi

# The uninstaller's advertised job is to wipe the router bearer token. `uci
# delete <package>` is not a complete uci lookup and deletes nothing, so the
# scrub must enumerate sections — and must verify rather than assume.
if grep -Eq 'uci[[:space:]].*delete[[:space:]]+"?wifihaven"?([[:space:]]|>|;|$)' "$UNINSTALL_CODE"; then
  check "#2554 uninstall.sh does not rely on the no-op 'uci delete <package>' form" \
        "uninstall.sh still loops on 'uci delete wifihaven' — a package-only pointer deletes nothing"
else
  check "#2554 uninstall.sh does not rely on the no-op 'uci delete <package>' form" ok
fi

# A redirection error on a POSIX *special* built-in (`:`, `.`, `exec`, …)
# terminates ash/dash outright and is NOT catchable with `2>/dev/null || true`.
# Both scripts truncate/create the package-owned config, and both must do it
# with something ordinary (`cp /dev/null`, `touch`, `true >`) so the failure
# surfaces as a report instead of killing the uninstaller mid-teardown.
for _sb_f in "$UNINSTALL" "$INSTALL"; do
  _sb_label=$(basename "$_sb_f")
  if grep -v '^[[:space:]]*#' "$_sb_f" | grep -Eq '(^|[[:space:];&|])(:|\.|exec|eval|export|readonly|set|shift|times|trap|unset)[[:space:]]*>' ; then
    check "#2554 $_sb_label does not redirect onto a POSIX special built-in" \
          "a redirection error there kills ash/dash uncatchably — use cp /dev/null / touch / true >"
  else
    check "#2554 $_sb_label does not redirect onto a POSIX special built-in" ok
  fi
done

# The install-time config backup is a token-bearing path named by BOTH scripts:
# install.sh writes it, uninstall.sh erases it. Nothing can share a constant
# between two standalone scripts, so pin the literal in both (ACCEPT + TEST-PIN)
# — a rename on one side would otherwise silently stop the uninstaller erasing
# a credential.
CONFIG_BACKUP_PATH=/tmp/wifihaven-config.bak-2554
# Pin the ASSIGNMENT, not a free-text mention: a trailing comment carrying the
# old path would satisfy a `grep -qF` while the code drifted.
for _bk_f in "$INSTALL" "$UNINSTALL"; do
  _bk_label=$(basename "$_bk_f")
  grep -Eq "^WIFIHAVEN_CONFIG_BACKUP=$CONFIG_BACKUP_PATH\$" "$_bk_f" \
    && check "SSOT: $_bk_label sets WIFIHAVEN_CONFIG_BACKUP=$CONFIG_BACKUP_PATH" ok \
    || check "SSOT: $_bk_label sets WIFIHAVEN_CONFIG_BACKUP=$CONFIG_BACKUP_PATH" \
             "the two scripts disagree on the backup path — uninstall.sh's prune would miss install.sh's file"
done
sed -n '/^prune_runtime_artifacts()/,/^}/p' "$UNINSTALL" | sed -n '/for _p in /,/; do$/p' \
  | grep -v '^[[:space:]]*#' | grep -q '"\$WIFIHAVEN_CONFIG_BACKUP"' \
  && check "SSOT: uninstall.sh's prune list includes the config backup" ok \
  || check "SSOT: uninstall.sh's prune list includes the config backup" \
           "prune_runtime_artifacts no longer names \$WIFIHAVEN_CONFIG_BACKUP"

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

printf '%s\n' "$PKG_SYSCTL_DIRECTIVES" | while IFS= read -r _d; do
  [ -n "$_d" ] || continue
  grep -qF "$_d" "$INSTALL" || printf '%s\n' "$_d"
done > "$TMP/sysctl-drift"
_sysctl_drift=$(cat "$TMP/sysctl-drift")
[ -z "$_sysctl_drift" ] \
  && check "SSOT: install.sh restore path carries every packaged sysctl directive verbatim" ok \
  || check "SSOT: install.sh restore path carries every packaged sysctl directive verbatim" \
           "install.sh is missing: $_sysctl_drift — the restored file would drift from the packaged one"

# SSOT test-pin: "is the block-page listener present?" is answered in THREE
# standalone scripts — the shared uhttpd helper (which configures it), the
# uninstaller (which removes it), and install.sh's self-check (which asserts
# it). All three are fetched/run standalone, so COLLAPSE isn't available;
# ACCEPT + TEST-PIN is (docs/process/single-source-of-truth.md). Normalise away
# the shell/awk escaping and require the same anchored matcher in each — an
# unanchored variant in any one of them would be satisfied by the stock
# `uhttpd.main.listen_http='0.0.0.0:80'` plus an unrelated 8081 elsewhere.
# Scope: this pin catches the realistic drift — someone copying the existing
# `listen_http=…8081` matcher into a fourth place and loosening the anchor. A
# matcher built from a different shape entirely (e.g. comparing `uci get
# uhttpd.<sec>.listen_http` against a literal) carries no `listen_http=` and is
# out of its reach; that is a limit of a textual pin, not an oversight.
UHTTPD_MATCHER_CORE="^uhttpd.[^.]+.listen_http=.*'127.0.0.1:8081'"
normalized_matcher() {
  # Comment-stripped, so prose mentioning listen_http can never satisfy the pin
  # while the code drifts. EVERY searching line is normalised, not just the
  # first — a second, looser matcher added later must fail the pin too. Lines
  # that WRITE the listener (`uci add_list …listen_http=…`) are not matchers, so
  # the candidate set is restricted to lines that search (grep/awk).
  grep -v '^[[:space:]]*#' "$1" | grep "listen_http=" | grep "8081" | grep -Ev 'uci[[:space:]]+(set|add_list|del_list)' \
    | tr -d '\\ '
}
for _m_f in "$ROOT/files/usr/lib/wifihaven/setup-uhttpd-block-page.sh" "$UNINSTALL" "$INSTALL"; do
  _m_label=$(basename "$_m_f")
  _m_lines=$(normalized_matcher "$_m_f")
  _m_bad=$(printf '%s\n' "$_m_lines" | grep -vF "$UHTTPD_MATCHER_CORE" || true)
  if [ -n "$_m_lines" ] && [ -z "$_m_bad" ]; then
    check "SSOT: $_m_label uses the anchored block-page listener matcher" ok
  else
    check "SSOT: $_m_label uses the anchored block-page listener matcher" \
          "every listen_http/8081 line must normalise to '$UHTTPD_MATCHER_CORE'; offending: '${_m_bad:-<none found>}'"
  fi
done

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
  "$SIM_SH" -c "
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
      # Real uci: exit 0 (with NO output) when the file exists but holds no
      # sections; exit 1 only when the file is missing. A `| grep .` here would
      # invert that and hide a caller that keys off the exit status.
      [ -f "$WIFIHAVEN_CONFIG" ] || return 1
      awk -v q="'" '
        /^config /{ t=$2; n=$3; gsub(q,"",n); sec=n; printf "wifihaven.%s=%s\n", sec, t; next }
        /^[[:space:]]*option /{ if (sec != "") printf "wifihaven.%s.%s=%s\n", sec, $2, $3 }
      ' "$WIFIHAVEN_CONFIG" ;;
    delete)
      case "$_arg" in
        wifihaven) return 1 ;;                       # package-only pointer: no-op
        wifihaven.*)
          [ "${SIM_UCI_DELETE_BROKEN:-0}" = "1" ] && return 1
          _sec=${_arg#wifihaven.}
          awk -v sec="$_sec" -v q="'" '
            /^config /{ n=$3; gsub(q,"",n); skip = (n == sec); if (skip) next }
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
  printf 'TOKEN_SURVIVORS=""\nFAILED_PATHS=""\n'
  sed -n '/^file_has_router_token()/,/^}/p' "$UNINSTALL"
  sed -n '/^record_failure()/,/^}/p' "$UNINSTALL"
  sed -n '/^config_has_router_token()/,/^}/p' "$UNINSTALL"
  sed -n '/^scrub_wifihaven_config()/,/^}/p' "$UNINSTALL"
  sed -n '/^prune_runtime_artifacts()/,/^}/p' "$UNINSTALL"
  cat <<'GUARD'
for _fn in file_has_router_token record_failure config_has_router_token scrub_wifihaven_config prune_runtime_artifacts; do
  command -v "$_fn" >/dev/null 2>&1 \
    || { printf 'EXTRACTION-FAILED: %s not extracted from uninstall.sh\n' "$_fn" >&2; exit 99; }
done
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
  # $1 = extra env assignments, $2 = body (defaults to the scrub)
  "$SIM_SH" -c "
    WIFIHAVEN_CONFIG='$FR/etc/config/wifihaven'
    WIFIHAVEN_RUNTIME_DIR='$FR/etc/wifihaven'
    WIFIHAVEN_CONFIG_BACKUP='$FR/tmp/wifihaven-config.bak-2554'
    $1
    $(uninstall_sim_prelude)
    ${2:-scrub_wifihaven_config || true}
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

# The happy path must SAY it succeeded. `uci show <package>` exits 0 for a file
# that exists but holds no sections — exactly what a successful scrub leaves —
# so a caller that keys off the exit status reports a failure on success. The
# stub above models that faithfully; this assertion pins the message.
case "$out" in
  *"cleared wifihaven UCI state"*) check "#2554 a successful scrub reports success" ok ;;
  *) check "#2554 a successful scrub reports success" \
           "the scrub worked but did not report it as cleared: $out" ;;
esac

case "$out" in
  *EXTRACTION-FAILED*) check "#2554 uninstall.sh scrub keeps the package-owned path in place" \
                             "the scrub never ran: $out" ;;
  *) [ -f "$FR/etc/config/wifihaven" ] \
       && check "#2554 uninstall.sh scrub keeps the package-owned path in place" ok \
       || check "#2554 uninstall.sh scrub keeps the package-owned path in place" \
                "the config file was removed — that is the apk file-db desync this issue is about" ;;
esac

# --- Scenario I: the fail-safe when the section deletes don't take --------
new_enrolled_config wipe-failsafe
out=$(run_uninstall_sim "SIM_UCI_DELETE_BROKEN=1" || printf 'EXTRACTION-FAILED')
if grep -q "$TOKEN" "$FR/etc/config/wifihaven" 2>/dev/null; then
  check "#2554 uninstall.sh truncates the config when the UCI scrub doesn't take" \
        "the bearer token survived an uninstall: $out"
else
  check "#2554 uninstall.sh truncates the config when the UCI scrub doesn't take" ok
fi

case "$out" in
  *EXTRACTION-FAILED*) check "#2554 the fail-safe truncates rather than removes the file" \
                             "the scrub never ran: $out" ;;
  *) [ -f "$FR/etc/config/wifihaven" ] \
       && check "#2554 the fail-safe truncates rather than removes the file" ok \
       || check "#2554 the fail-safe truncates rather than removes the file" \
                "the file was unlinked — truncation is required so the package db stays in sync" ;;
esac

case "$out" in
  *"router_token survived"*) check "#2554 the fail-safe reports what it actually did" ok ;;
  *) check "#2554 the fail-safe reports what it actually did" \
           "no note about the truncation fail-safe: $out" ;;
esac

# --- Scenario J: an unwritable config must be REPORTED, not fatal ------------
# A read-only /etc overlay is a routine OpenWrt state (full or corrupt flash).
# The truncation must not take the shell down with it — `: >file` would, since
# `:` is a POSIX special built-in whose redirection errors are not catchable —
# and the operator must be told the credential is still on disk.
if [ "$SIM_SH_STRICT" -ne 1 ]; then
  skip "#2554 an unwritable config is reported, not fatal" \
       "needs dash/ash — bash does not exhibit the special-built-in exit this asserts"
elif [ "$(id -u)" -eq 0 ]; then
  # root ignores the mode bits, so the write succeeds and there is nothing to
  # assert. This matters: the openwrt/rootfs container — the most faithful place
  # to run this spec — runs as root.
  skip "#2554 an unwritable config is reported, not fatal" \
       "running as root: chmod 444 does not make the file unwritable"
else
  new_enrolled_config wipe-unwritable
  chmod 444 "$FR/etc/config/wifihaven"
  out=$(run_uninstall_sim "SIM_UCI_DELETE_BROKEN=1" \
        "scrub_wifihaven_config || true; printf 'FAILED_PATHS=[%s] TOKEN_SURVIVORS=[%s]\\n' \"\$FAILED_PATHS\" \"\$TOKEN_SURVIVORS\"" \
        || printf 'SIM-EXITED')
  chmod 644 "$FR/etc/config/wifihaven"
  case "$out" in
    *EXTRACTION-FAILED*) check "#2554 an unwritable config is reported, not fatal" "the scrub never ran: $out" ;;
    *"FAILED to wipe router_token"*) check "#2554 an unwritable config is reported, not fatal" ok ;;
    *) check "#2554 an unwritable config is reported, not fatal" \
             "the scrub neither wiped nor reported — a redirection error on a POSIX special built-in (\`: >file\`) kills ash/dash here, uncatchably: $out" ;;
  esac

  # A surviving credential must land in BOTH lists. TOKEN_SURVIVORS alone left
  # the terminal error printing "could not remove:" with nothing after it — a
  # heading that promises a list and delivers none, in the likeliest failure
  # case (read-only /etc).
  _fp=$(printf '%s\n' "$out" | sed -n 's/.*FAILED_PATHS=\[\([^]]*\)\].*/\1/p')
  case "$_fp" in
    *"/etc/config/wifihaven"*) check "#2554 a surviving credential is also recorded as a removal failure" ok ;;
    *) check "#2554 a surviving credential is also recorded as a removal failure" \
             "FAILED_PATHS=[$_fp] omits the config — the terminal error would print an empty enumeration" ;;
  esac
fi

# The token check must recognise every spelling uci accepts, not just the one
# the agent happens to write — a hand-edited config is exactly the state the
# file-level fail-safe exists for.
_spelling_n=0
for _spelling in "option 'router_token' 'TOKENVAL'" \
                 "option router_token TOKENVAL" \
                 "option router_token \"TOKENVAL\""; do
  _spelling_n=$((_spelling_n + 1))
  FR="$TMP/wipe-spelling-$_spelling_n"
  rm -rf "$FR"; mkdir -p "$FR/etc/config"
  printf "config wifihaven 'wifihaven'\n\t%s\n" "$_spelling" > "$FR/etc/config/wifihaven"
  out=$(run_uninstall_sim "SIM_UCI_DELETE_BROKEN=1" || printf 'SIM-EXITED')
  if grep -q TOKENVAL "$FR/etc/config/wifihaven" 2>/dev/null; then
    check "#2554 token check recognises: $_spelling" \
          "the token survived and the scrub reported success: $out"
  else
    case "$out" in
      *"router_token survived"*) check "#2554 token check recognises: $_spelling" ok ;;
      *) check "#2554 token check recognises: $_spelling" \
               "token gone but the truncation was never reported: $out" ;;
    esac
  fi
done

# The prune must actually erase the runtime artifacts AND the token-bearing
# install-time backup, and must leave the package-owned signing key alone.
FR="$TMP/prune"
rm -rf "$FR"; mkdir -p "$FR/etc/wifihaven/keys" "$FR/etc/wifihaven/blocklists" "$FR/tmp" "$FR/etc/config"
printf 'snapshot\n'  > "$FR/etc/wifihaven/policy.json"
printf 'partial\n'   > "$FR/etc/wifihaven/policy.json.tmp"
printf 'cert\n'      > "$FR/etc/wifihaven/block_page.crt"
printf 'key\n'       > "$FR/etc/wifihaven/block_page.key"
printf 'cached\n'    > "$FR/etc/wifihaven/blocklists/ads.txt"
printf 'PUBKEY\n'    > "$FR/etc/wifihaven/keys/release.pub"
printf "config wifihaven 'wifihaven'\n\toption router_token 'TOKENVAL'\n" \
  > "$FR/tmp/wifihaven-config.bak-2554"
out=$(run_uninstall_sim "" "prune_runtime_artifacts || true" || printf 'SIM-EXITED')
_leftovers=""
for _p in etc/wifihaven/policy.json etc/wifihaven/policy.json.tmp etc/wifihaven/blocklists \
          etc/wifihaven/block_page.crt etc/wifihaven/block_page.key \
          tmp/wifihaven-config.bak-2554; do
  [ -e "$FR/$_p" ] && _leftovers="$_leftovers $_p"
done
[ -z "$_leftovers" ] \
  && check "#2554 the prune erases every runtime artifact incl. the token-bearing backup" ok \
  || check "#2554 the prune erases every runtime artifact incl. the token-bearing backup" \
           "still present:$_leftovers ($out)"

[ -f "$FR/etc/wifihaven/keys/release.pub" ] \
  && check "#2554 the prune leaves the package-owned keys/release.pub alone" ok \
  || check "#2554 the prune leaves the package-owned keys/release.pub alone" \
           "the update-signature key was deleted — wifihaven-update fails closed without it"

# A prune that could NOT remove the token-bearing backup must say so, not claim
# it removed it: `set -e` is suspended inside the function (it runs `|| true`),
# so a failing rm on a read-only parent would otherwise pass silently.
if [ "$(id -u)" -eq 0 ]; then
  skip "#2554 a prune that cannot remove the config backup reports FAILED" \
       "running as root: a read-only parent directory does not stop rm"
else
  FR="$TMP/prune-readonly"
  rm -rf "$FR"; mkdir -p "$FR/etc/wifihaven" "$FR/tmp"
  printf "option router_token 'TOKENVAL'\n" > "$FR/tmp/wifihaven-config.bak-2554"
  chmod 555 "$FR/tmp"
  out=$(run_uninstall_sim "" "prune_runtime_artifacts || true" || printf 'SIM-EXITED')
  chmod 755 "$FR/tmp"
  case "$out" in
    *"FAILED to remove"*) check "#2554 a prune that cannot remove the config backup reports FAILED" ok ;;
    *"removed wifihaven runtime artifacts"*)
      check "#2554 a prune that cannot remove the config backup reports FAILED" \
            "claimed a removal that did not happen — the token-bearing backup is still there: $out" ;;
    *) check "#2554 a prune that cannot remove the config backup reports FAILED" \
             "expected an explicit failure note: $out" ;;
  esac
fi

# MIXED outcome: some entries removable, the token-bearing backup not. A single
# "did anything get removed?" flag would print a fixed enumeration here and
# claim the backup was erased.
if [ "$(id -u)" -eq 0 ]; then
  skip "#2554 a mixed-outcome prune does not claim the survivors were removed" \
       "running as root: a read-only parent directory does not stop rm"
else
  FR="$TMP/prune-mixed"
  rm -rf "$FR"; mkdir -p "$FR/etc/wifihaven" "$FR/tmp"
  printf 'snapshot\n' > "$FR/etc/wifihaven/policy.json"
  printf "option router_token 'TOKENVAL'\n" > "$FR/tmp/wifihaven-config.bak-2554"
  chmod 555 "$FR/tmp"
  out=$(run_uninstall_sim "" "prune_runtime_artifacts || true" || printf 'SIM-EXITED')
  chmod 755 "$FR/tmp"
  _mixed_bad=""
  [ -e "$FR/etc/wifihaven/policy.json" ] && _mixed_bad="policy.json survived;"
  grep -q TOKENVAL "$FR/tmp/wifihaven-config.bak-2554" 2>/dev/null || _mixed_bad="$_mixed_bad backup unexpectedly removed;"
  printf '%s\n' "$out" | grep -q 'FAILED to remove .*wifihaven-config\.bak-2554' \
    || _mixed_bad="$_mixed_bad the surviving backup was not reported;"
  # Whatever the wording, a success note must not enumerate something that
  # survived — a fixed "(… config backup)" string is exactly that failure.
  _mixed_note=$(printf '%s\n' "$out" | grep 'removed wifihaven runtime artifacts' || true)
  case "$_mixed_note" in
    *"config backup"*|*"wifihaven-config.bak-2554"*)
      _mixed_bad="$_mixed_bad the summary claims the surviving backup was removed;" ;;
  esac
  [ -z "$_mixed_bad" ] \
    && check "#2554 a mixed-outcome prune does not claim the survivors were removed" ok \
    || check "#2554 a mixed-outcome prune does not claim the survivors were removed" \
             "$_mixed_bad ($out)"
fi

# A surviving backup that holds NO token must not be escalated to a bearer-token
# failure — asserting a credential is present without looking is the same
# unsourced-claim bug in the other direction.
if [ "$(id -u)" -eq 0 ]; then
  skip "#2554 a token-free surviving backup is not called a bearer-token failure" \
       "running as root: a read-only parent directory does not stop rm"
else
  FR="$TMP/prune-readonly-notoken"
  rm -rf "$FR"; mkdir -p "$FR/etc/wifihaven" "$FR/tmp"
  printf "config wifihaven 'wifihaven'\n\toption api_url 'https://api.example'\n" \
    > "$FR/tmp/wifihaven-config.bak-2554"
  chmod 555 "$FR/tmp"
  out=$(run_uninstall_sim "" \
        "prune_runtime_artifacts || true; printf 'TOKEN_SURVIVORS=[%s]\\n' \"\$TOKEN_SURVIVORS\"" \
        || printf 'SIM-EXITED')
  chmod 755 "$FR/tmp"
  # Precondition: the assertion is only meaningful if the backup ACTUALLY
  # survived. An empty TOKEN_SURVIVORS is equally true when the prune succeeded,
  # so without this the case passes vacuously wherever chmod 555 stops biting.
  if [ ! -e "$FR/tmp/wifihaven-config.bak-2554" ]; then
    skip "#2554 a token-free surviving backup is not called a bearer-token failure" \
         "the read-only parent did not stop rm here, so nothing survived to judge"
  else
    case "$out" in
      *"TOKEN_SURVIVORS=[]"*) check "#2554 a token-free surviving backup is not called a bearer-token failure" ok ;;
      *) check "#2554 a token-free surviving backup is not called a bearer-token failure" \
               "a backup with no router_token was reported as a surviving credential: $out" ;;
    esac
  fi
fi

# The terminal error must name what actually failed. Pointing the operator at a
# clean /etc/config/wifihaven while the token sits in /tmp is worse than
# silence — they would empty the wrong file and stop looking.
# The err bodies are multi-line, so the greps cannot be anchored to the `err "`
# line — but they must NOT see the `if`/`elif` guards either: those name both
# variables, so matching the whole block makes every check below a tautology
# that an err body naming no runtime list at all would still satisfy. Reduce the
# haystack to the MESSAGE TEXT: drop comments and the shell control lines.
UNINSTALL_TAIL=$(sed -n '/^if \[ -n "\$TOKEN_SURVIVORS" \]/,$p' "$UNINSTALL")
UNINSTALL_ERR_BODY=$(printf '%s\n' "$UNINSTALL_TAIL" \
  | grep -v '^[[:space:]]*#' | grep -Ev '^[[:space:]]*(if|elif|else|fi)([[:space:]]|$)')
printf '%s' "$UNINSTALL_ERR_BODY" | grep -q '\$TOKEN_SURVIVORS' \
  && check "#2554 the terminal error names the files that actually held a token" ok \
  || check "#2554 the terminal error names the files that actually held a token" \
           "the error message does not interpolate \$TOKEN_SURVIVORS — it would name a file that may be clean"
printf '%s' "$UNINSTALL_ERR_BODY" | grep -q '\$FAILED_PATHS' \
  && check "#2554 a non-credential removal failure is reported separately" ok \
  || check "#2554 a non-credential removal failure is reported separately" \
           "a failed prune of non-secret state would be reported as a bearer-token failure"
printf '%s' "$UNINSTALL_ERR_BODY" | grep -q '/etc/config/wifihaven' \
  && check "#2554 the terminal error hardcodes no path" \
           "the terminal error names /etc/config/wifihaven literally — it would point at a file that may be clean" \
  || check "#2554 the terminal error hardcodes no path" ok

# The message depends on TOKEN_SURVIVORS being a SUBSET of FAILED_PATHS (it
# prints the full list, then the credential-bearing subset). Enforce that
# structurally, not per call site: exactly one function may append to either
# list, so a third append site added later cannot reintroduce the empty-list
# heading. (docs/process/single-source-of-truth.md — COLLAPSE.)
# Match the self-append shape, not one exact spelling: `X="${X} …"` and a
# missing separator are the same write, and failing on a no-op reformat would
# make this assertion about formatting instead of the invariant. These two are
# SUPPLEMENTARY — the load-bearing check is the behavioural case above, which
# catches an append that slips past this pattern.
APPEND_RE='(TOKEN_SURVIVORS|FAILED_PATHS)[[:space:]]*=[[:space:]]*"?\$\{?(TOKEN_SURVIVORS|FAILED_PATHS)\}?'
count_appends() { grep -v '^[[:space:]]*#' | grep -cE "$APPEND_RE" || true; }
_appenders=$(count_appends < "$UNINSTALL")
[ "$_appenders" = 2 ] \
  && check "#2554 exactly one writer appends to the failure/credential lists" ok \
  || check "#2554 exactly one writer appends to the failure/credential lists" \
           "found $_appenders append sites (expected the 2 inside record_failure) — the TOKEN_SURVIVORS ⊆ FAILED_PATHS invariant may be back to per-site convention"
_rf_appenders=$(sed -n '/^record_failure()/,/^}/p' "$UNINSTALL" | count_appends)
[ "$_rf_appenders" = 2 ] \
  && check "#2554 both appends live in record_failure()" ok \
  || check "#2554 both appends live in record_failure()" \
           "record_failure() holds $_rf_appenders of the 2 appends — the invariant is not structural"

# A scrub that did not take must not be summarised as one that did. With uci
# reachable but every delete failing and no token in the file, the file is left
# byte-for-byte unchanged — saying "cleared … router_token wiped" there is the
# same lying-summary pattern in a lower-stakes spot.
FR="$TMP/scrub-noop-no-token"
rm -rf "$FR"; mkdir -p "$FR/etc/config"
printf "config settings 'settings'\n\toption enforcement_disabled '0'\n" > "$FR/etc/config/wifihaven"
out=$(run_uninstall_sim "SIM_UCI_DELETE_BROKEN=1" || printf 'SIM-EXITED')
case "$out" in
  *"cleared wifihaven UCI state"*)
    check "#2554 a scrub that did not take is not reported as cleared" \
          "the summary claims a clear that never happened: $out" ;;
  *"could NOT be cleared"*) check "#2554 a scrub that did not take is not reported as cleared" ok ;;
  *) check "#2554 a scrub that did not take is not reported as cleared" \
           "expected an explicit could-not-clear note: $out" ;;
esac

# An EMPTY token must NOT read as present — otherwise a clean config gets
# pointlessly truncated and reported as a survival.
FR="$TMP/wipe-empty-token"
rm -rf "$FR"; mkdir -p "$FR/etc/config"
printf "config wifihaven 'wifihaven'\n\toption router_token ''\n" > "$FR/etc/config/wifihaven"
out=$(run_uninstall_sim "SIM_UCI_DELETE_BROKEN=1" || printf 'SIM-EXITED')
case "$out" in
  *"router_token survived"*|*"FAILED to wipe"*)
    check "#2554 an empty router_token does not read as a live token" \
          "an empty token triggered the truncation fail-safe: $out" ;;
  *) check "#2554 an empty router_token does not read as a live token" ok ;;
esac

if [ "$SKIP" -gt 0 ]; then
  printf "\n%d passed, %d failed, %d skipped\n" "$PASS" "$FAIL" "$SKIP"
else
  printf "\n%d passed, %d failed\n" "$PASS" "$FAIL"
fi
[ "$FAIL" -eq 0 ]
