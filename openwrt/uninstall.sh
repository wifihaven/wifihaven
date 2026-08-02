#!/bin/sh
# WifiHaven OpenWRT agent — uninstaller.
#
# Cleanly reverses what openwrt/install.sh did: stops and disables the
# agent, removes the package via apk/opkg, deletes the uhttpd block-page
# listener section, and wipes the wifihaven UCI config (which contains a
# bearer token).
#
# Usage (on an OpenWRT router, as root):
#
#   sh -c "$(uclient-fetch -qO - https://raw.githubusercontent.com/wifihaven/wifihaven/main/openwrt/uninstall.sh)"
#
# Or download then run:
#
#   uclient-fetch -qO /tmp/wifihaven-uninstall.sh \
#     https://raw.githubusercontent.com/wifihaven/wifihaven/main/openwrt/uninstall.sh
#   sh /tmp/wifihaven-uninstall.sh
#
# Flags:
#   -y, --yes     skip the confirmation prompt
#       --purge   also remove /usr/lib/wifihaven and /usr/lib/lua/wifihaven
#                 (manual-workaround leftovers from older e2e shakeouts).
#                 Default behaviour only undoes what install.sh did.
#   -h, --help    print this usage and exit

set -eu

err()  { printf 'error: %s\n' "$*" >&2; exit 1; }
info() { printf '==> %s\n' "$*"; }

# Package-owned path we must never delete ourselves (#2554) — we only scrub the
# secret out of it and let apk del / opkg remove take the file.
WIFIHAVEN_CONFIG=/etc/config/wifihaven
# Runtime state the agent writes (NOT package-owned) plus install.sh's
# displaced-config backup — see prune_runtime_artifacts below. The backup path
# is pinned equal to install.sh's by
# openwrt/test/install_reinstall_cycle_spec.sh.
WIFIHAVEN_RUNTIME_DIR=/etc/wifihaven
WIFIHAVEN_CONFIG_BACKUP=/tmp/wifihaven-config.bak-2554

usage() {
  sed -n '2,28p' "$0" | sed 's/^# \{0,1\}//'
}

ASSUME_YES=0
PURGE=0

while [ $# -gt 0 ]; do
  case "$1" in
    -y|--yes)   ASSUME_YES=1 ;;
    --purge)    PURGE=1 ;;
    -h|--help)  usage; exit 0 ;;
    *)          err "unknown flag: $1 (try --help)" ;;
  esac
  shift
done

# Read from the controlling terminal so this works under `curl | sh`,
# where stdin is the script body.
TTY=/dev/tty
if [ "$ASSUME_YES" -eq 0 ]; then
  [ -r "$TTY" ] && [ -w "$TTY" ] || err "no /dev/tty available — run with --yes, or download the script and run it from an interactive shell"
fi

prompt() {
  # prompt VAR "Question" [default]
  _var=$1; _q=$2; _def=${3:-}
  if [ -n "$_def" ]; then
    printf '%s [%s]: ' "$_q" "$_def" >"$TTY"
  else
    printf '%s: ' "$_q" >"$TTY"
  fi
  IFS= read -r _val <"$TTY" || _val=""
  [ -n "$_val" ] || _val=$_def
  eval "$_var=\$_val"
}

# Sanity checks.
[ "$(id -u)" -eq 0 ] || err "must run as root"
command -v uci >/dev/null 2>&1 || err "uci not found — is this OpenWRT?"

# Detect the package manager. OpenWRT 24.10+ (SNAPSHOT) uses apk; earlier
# releases use opkg.
if command -v apk >/dev/null 2>&1; then
  PKG_MGR=apk
  PKG_REMOVE=del
elif command -v opkg >/dev/null 2>&1; then
  PKG_MGR=opkg
  PKG_REMOVE=remove
else
  err "neither apk nor opkg found — is this OpenWRT?"
fi

# Confirmation.
if [ "$ASSUME_YES" -eq 0 ]; then
  cat >"$TTY" <<EOF

WifiHaven OpenWRT agent — uninstall
===================================
This will:
  - stop and disable the wifihaven service
  - remove the wifihaven package via $PKG_MGR
  - delete the uhttpd block-page listener on 127.0.0.1:8081 and [::1]:8081
  - clear the wifihaven UCI config (router_token will be lost); the
    package manager owns /etc/config/wifihaven and removes the file itself
EOF
  if [ "$PURGE" -eq 1 ]; then
    cat >"$TTY" <<EOF
  - [purge] rm -rf /usr/lib/wifihaven /usr/lib/lua/wifihaven
EOF
  fi
  printf '\n' >"$TTY"
  prompt CONFIRM "Proceed? (y/N)" "N"
  case "$CONFIRM" in
    y|Y|yes|YES) : ;;
    *) err "aborted" ;;
  esac
fi

# Track whether we actually did anything so we can print "nothing to do"
# on a clean router.
DID_ANYTHING=0
SUMMARY=""
note() { SUMMARY="${SUMMARY}  - $*\n"; DID_ANYTHING=1; }

# 1. Stop the service (tolerate "not installed" / "not running").
if [ -x /etc/init.d/wifihaven ]; then
  info "Stopping wifihaven service..."
  /etc/init.d/wifihaven stop  >/dev/null 2>&1 || true
  /etc/init.d/wifihaven disable >/dev/null 2>&1 || true
  note "stopped and disabled wifihaven service"
fi

# 1b. #308: disable the boot default-deny skeleton init and drop any live
# `inet wifihaven_boot` table so the router stops blocking forwarded LAN→WAN
# traffic. If we don't do this an admin who uninstalls without rebooting
# is left with a default-deny router.
if [ -x /etc/init.d/wifihaven-boot ]; then
  info "Disabling wifihaven-boot default-deny skeleton..."
  /etc/init.d/wifihaven-boot disable >/dev/null 2>&1 || true
  note "disabled wifihaven-boot service"
fi
if command -v nft >/dev/null 2>&1 && nft list table inet wifihaven_boot >/dev/null 2>&1; then
  info "Removing live wifihaven_boot nft table..."
  nft delete table inet wifihaven_boot >/dev/null 2>&1 || true
  note "removed inet wifihaven_boot table"
fi
if command -v nft >/dev/null 2>&1 && nft list table inet wifihaven >/dev/null 2>&1; then
  info "Removing live wifihaven runtime nft table..."
  nft delete table inet wifihaven >/dev/null 2>&1 || true
  note "removed inet wifihaven table"
fi

# 2. Remove the package.
case "$PKG_MGR" in
  apk)
    if apk list -I wifihaven 2>/dev/null | grep -q '^wifihaven'; then
      info "Removing wifihaven package via apk..."
      apk del wifihaven >/dev/null 2>&1 || apk del wifihaven || true
      note "removed wifihaven package (apk)"
    fi
    ;;
  opkg)
    if opkg list-installed 2>/dev/null | grep -q '^wifihaven '; then
      info "Removing wifihaven package via opkg..."
      opkg remove wifihaven >/dev/null 2>&1 || opkg remove wifihaven || true
      note "removed wifihaven package (opkg)"
    fi
    ;;
esac

# 3. Remove the uhttpd block-page listener (match by listen_http, not by
# section index — install.sh used `uci add uhttpd uhttpd` which assigns
# whatever index was free).
uhttpd_section=$(uci show uhttpd 2>/dev/null \
  | awk -F'[.=]' "/^uhttpd\\.[^.]+\\.listen_http=.*'127\\.0\\.0\\.1:8081'/{print \$2; exit}")
if [ -n "${uhttpd_section:-}" ]; then
  info "Removing uhttpd block-page listener (section: $uhttpd_section)..."
  uci delete "uhttpd.${uhttpd_section}" 2>/dev/null || true
  uci commit uhttpd
  /etc/init.d/uhttpd reload >/dev/null 2>&1 || true
  note "removed uhttpd listener on 127.0.0.1:8081 + [::1]:8081 (#411)"
fi

# 3a. #303: revert the route_localnet sysctl. The package removal (step 2) owns
# /etc/sysctl.d/99-wifihaven.conf and takes the FILE; all we do here is reset
# the value in the RUNNING kernel, which package removal cannot touch — without
# this, LAN clients stay able to route to 127.0.0.0/8 until the next reboot.
#
# #2554: we used to `rm -f` that file ourselves. It is package-OWNED (`apk info
# -L wifihaven` lists it), so deleting it out from under apk desynchronises
# apk's file database and the file does NOT come back on the next install — not
# even as a .apk-new. The loss is invisible at runtime because
# setup-uhttpd-block-page.sh sets route_localnet live, so it only bites after a
# reboot, as "the block page is broken" days later. Never remove it here; let
# apk del / opkg remove own it.
if [ "$(sysctl -n net.ipv4.conf.br-lan.route_localnet 2>/dev/null)" = "1" ]; then
  sysctl -w net.ipv4.conf.br-lan.route_localnet=0 >/dev/null 2>&1 || true
  note "reset net.ipv4.conf.br-lan.route_localnet=0"
fi

# 4. Scrub wifihaven UCI state. apk/opkg removal should have taken
# /etc/config/wifihaven (the package owns it), but a locally-modified conffile
# can survive removal — and that file holds the router bearer token. Clearing
# the UCI tree and committing rewrites the file empty, so the secret is gone.
#
# #2554: do NOT `rm` the file afterwards. It is package-OWNED, and deleting it
# behind apk's back desynchronises apk's file database: the next install writes
# the shipped config to /etc/config/wifihaven.apk-new instead of
# /etc/config/wifihaven, so every subsequent `uci` call against it fails with a
# bare "uci: Entry not found". Removal of the file is apk del / opkg remove's
# job, not ours.
#
# `uci delete wifihaven` (a PACKAGE-only pointer) is not a complete uci lookup
# and does not delete anything, so the scrub enumerates the sections and deletes
# each one. If anything still leaves a live router_token behind, truncate the
# file in place as a fail-safe: truncation removes the secret while KEEPING the
# path, so the package database stays in sync (unlike the `rm` this replaces).
#
# The verify+truncate step is deliberately NOT gated on `uci show` succeeding:
# uci also fails on a malformed / hand-edited config, and that is exactly a
# state where the token is still sitting in the file. The file-level check is
# what actually guarantees the wipe; the uci scrub is best-effort on top.
SCRUB_FAILED=0

config_has_router_token() {
  [ -f "$WIFIHAVEN_CONFIG" ] || return 1
  [ -n "$(uci -q get wifihaven.@wifihaven[0].router_token 2>/dev/null || true)" ] && return 0
  # Match every spelling uci accepts — bare, single- and double-quoted key, and
  # any value whose first character is not whitespace or a quote (so `''`, `""`
  # and a bare `option router_token` correctly read as "no token"). A narrower
  # pattern would miss the token in a hand-edited config and report a wipe that
  # did not happen.
  grep -Eq "^[[:space:]]*option[[:space:]]+['\"]?router_token['\"]?[[:space:]]+['\"]?[^[:space:]'\"]" \
    "$WIFIHAVEN_CONFIG" 2>/dev/null
}

scrub_wifihaven_config() {
  # -s, not -f: an already-empty file is nothing to do, and claiming otherwise
  # would break the "nothing to do — router is already clean" path below.
  [ -s "$WIFIHAVEN_CONFIG" ] || return 1

  # `uci show <package>` exits 0 for a config file that exists but holds no
  # sections — which is exactly what a SUCCESSFUL scrub leaves behind, since
  # #2554 requires keeping the (package-owned) file. So the presence of state is
  # "show printed at least one line", never "show exited 0".
  _had_state=0
  if uci show wifihaven 2>/dev/null | grep -q .; then
    _had_state=1
    info "Wiping wifihaven UCI config..."
    # Unquoted expansion is intentional (one section name per word); `set -f`
    # stops a name like `@wifihaven[0]` being treated as a glob.
    set -f
    for _sec in $(uci show wifihaven 2>/dev/null \
                    | sed -n 's/^wifihaven\.\([^.=]*\)[.=].*/\1/p' | sort -u); do
      uci -q delete "wifihaven.$_sec" >/dev/null 2>&1 || true
    done
    set +f
    uci commit wifihaven 2>/dev/null || true
  fi

  # Verify the secret is actually gone — never report a wipe we didn't do.
  if config_has_router_token; then
    # `cp /dev/null`, not `: >file`: `:` is a POSIX SPECIAL built-in, so a
    # redirection error on it terminates a non-interactive ash/dash outright —
    # neither `2>/dev/null` nor `|| true` catches it. That would kill the
    # uninstaller on exactly the case this branch exists for (a read-only /etc
    # overlay), before it could report the failure.
    cp /dev/null "$WIFIHAVEN_CONFIG" 2>/dev/null || true
    if config_has_router_token; then
      SCRUB_FAILED=1
      note "FAILED to wipe router_token from $WIFIHAVEN_CONFIG"
    else
      note "truncated $WIFIHAVEN_CONFIG (router_token survived the UCI scrub)"
    fi
  elif [ "$_had_state" -eq 1 ]; then
    # There was no token, but say what actually happened: if sections survived
    # the deletes (a broken uci), the file is unchanged and claiming a clear
    # would be the same lying-summary bug in a lower-stakes spot.
    if uci show wifihaven 2>/dev/null | grep -q .; then
      note "wifihaven UCI state could NOT be cleared (no router_token found in it)"
    else
      note "cleared wifihaven UCI state (router_token wiped)"
    fi
  fi
}

scrub_wifihaven_config || true

# Runtime artifacts under /etc/wifihaven: the cached policy snapshot (#309),
# the fetched blocklist cache, and the self-signed block-page cert/key. The
# agent writes these at runtime, so the package manager won't remove them.
#
# #2554: /etc/wifihaven is NOT wholly ours to delete. The release builders
# (build-ipk.sh:78 / build-apk.sh) stage openwrt/files/ wholesale, so
# /etc/wifihaven/keys/release.pub — the update-signature verification key
# (#2078, read by wifihaven-update:44) — is a PACKAGE file. `rm -rf
# /etc/wifihaven` deleted it behind apk's back, which is the same file-database
# desync this issue is about, and the symptom is silent: wifihaven-update fails
# closed on a missing key, so the router just stops auto-updating. Remove only
# the runtime artifacts, then rmdir the directory if the package manager has
# already taken everything else out of it.
#
# $WIFIHAVEN_CONFIG_BACKUP is install.sh's copy of a displaced config (see its
# ensure_wifihaven_config) and can carry a router_token. tmpfs clears it at the
# next reboot, but a router decommissioned or re-homed WITHOUT a reboot would
# still be carrying the credential, so erase it here.
prune_runtime_artifacts() {
  _pruned=0
  for _p in "$WIFIHAVEN_RUNTIME_DIR/policy.json" "$WIFIHAVEN_RUNTIME_DIR/policy.json.tmp" \
            "$WIFIHAVEN_RUNTIME_DIR/blocklists" \
            "$WIFIHAVEN_RUNTIME_DIR/block_page.crt" "$WIFIHAVEN_RUNTIME_DIR/block_page.key" \
            "$WIFIHAVEN_CONFIG_BACKUP"; do
    [ -e "$_p" ] || continue
    rm -rf "$_p"
    # Count the removal only if it actually happened: `set -e` is suspended
    # inside this function (it is called `|| true`), and a read-only overlay
    # makes `rm` fail without stopping us. $WIFIHAVEN_CONFIG_BACKUP can carry a
    # router_token, so a survivor gets the same loud treatment as a surviving
    # token in the config — never a summary claiming a removal we did not do.
    if [ -e "$_p" ]; then
      SCRUB_FAILED=1
      note "FAILED to remove $_p"
    else
      _pruned=1
    fi
  done
  [ "$_pruned" -eq 1 ] || return 1
  note "removed wifihaven runtime artifacts (policy snapshot, blocklist cache, block-page cert, config backup)"
}
prune_runtime_artifacts || true
# Only succeeds once the package manager has taken everything else (notably
# keys/release.pub) out of the directory.
rmdir "$WIFIHAVEN_RUNTIME_DIR" 2>/dev/null || true

# 5. Purge mode: also kill manual-workaround leftovers from older e2e
# shakeouts (pre-#202, when modules were dropped under these paths by hand).
if [ "$PURGE" -eq 1 ]; then
  for d in /usr/lib/wifihaven /usr/lib/lua/wifihaven; do
    if [ -e "$d" ]; then
      info "Purging $d..."
      rm -rf "$d"
      note "removed $d"
    fi
  done
fi

if [ "$DID_ANYTHING" -eq 0 ]; then
  info "Nothing to do — router is already clean."
  exit 0
fi

printf '\nDone. Summary of actions:\n'
printf '%b' "$SUMMARY"
printf '\nRe-run install.sh on this router for a fresh enrollment.\n'

# The bearer-token wipe is a security property, not best-effort: if it did not
# take, say so loudly and exit nonzero rather than letting an operator believe
# a decommissioned router carries no credential.
if [ "$SCRUB_FAILED" -eq 1 ]; then
  err "the router bearer token could NOT be removed from $WIFIHAVEN_CONFIG. Delete or empty that file by hand before decommissioning or re-homing this router."
fi
