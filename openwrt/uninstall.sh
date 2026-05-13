#!/bin/sh
# FamilyDNS OpenWRT agent — uninstaller.
#
# Cleanly reverses what openwrt/install.sh did: stops and disables the
# agent, removes the package via apk/opkg, deletes the uhttpd block-page
# listener section, and wipes the familydns UCI config (which contains a
# bearer token).
#
# Usage (on an OpenWRT router, as root):
#
#   sh -c "$(uclient-fetch -qO - https://raw.githubusercontent.com/sameerparekh/familydns/main/openwrt/uninstall.sh)"
#
# Or download then run:
#
#   uclient-fetch -qO /tmp/familydns-uninstall.sh \
#     https://raw.githubusercontent.com/sameerparekh/familydns/main/openwrt/uninstall.sh
#   sh /tmp/familydns-uninstall.sh
#
# Flags:
#   -y, --yes     skip the confirmation prompt
#       --purge   also remove /usr/lib/familydns and /usr/lib/lua/familydns
#                 (manual-workaround leftovers from older e2e shakeouts).
#                 Default behaviour only undoes what install.sh did.
#   -h, --help    print this usage and exit

set -eu

err()  { printf 'error: %s\n' "$*" >&2; exit 1; }
info() { printf '==> %s\n' "$*"; }

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

FamilyDNS OpenWRT agent — uninstall
===================================
This will:
  - stop and disable the familydns service
  - remove the familydns package via $PKG_MGR
  - delete the uhttpd block-page listener on 127.0.0.1:8081
  - wipe /etc/config/familydns (router_token will be lost)
EOF
  if [ "$PURGE" -eq 1 ]; then
    cat >"$TTY" <<EOF
  - [purge] rm -rf /usr/lib/familydns /usr/lib/lua/familydns
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
if [ -x /etc/init.d/familydns ]; then
  info "Stopping familydns service..."
  /etc/init.d/familydns stop  >/dev/null 2>&1 || true
  /etc/init.d/familydns disable >/dev/null 2>&1 || true
  note "stopped and disabled familydns service"
fi

# 2. Remove the package.
case "$PKG_MGR" in
  apk)
    if apk list -I familydns 2>/dev/null | grep -q '^familydns'; then
      info "Removing familydns package via apk..."
      apk del familydns >/dev/null 2>&1 || apk del familydns || true
      note "removed familydns package (apk)"
    fi
    ;;
  opkg)
    if opkg list-installed 2>/dev/null | grep -q '^familydns '; then
      info "Removing familydns package via opkg..."
      opkg remove familydns >/dev/null 2>&1 || opkg remove familydns || true
      note "removed familydns package (opkg)"
    fi
    ;;
esac

# 3. Remove the uhttpd block-page listener (match by listen_http, not by
# section index — install.sh used `uci add uhttpd uhttpd` which assigns
# whatever index was free).
uhttpd_section=$(uci show uhttpd 2>/dev/null \
  | awk -F'[.=]' "/^uhttpd\\.[^.]+\\.listen_http='127\\.0\\.0\\.1:8081'\$/{print \$2; exit}")
if [ -n "${uhttpd_section:-}" ]; then
  info "Removing uhttpd block-page listener (section: $uhttpd_section)..."
  uci delete "uhttpd.${uhttpd_section}" 2>/dev/null || true
  uci commit uhttpd
  /etc/init.d/uhttpd reload >/dev/null 2>&1 || true
  note "removed uhttpd listener on 127.0.0.1:8081"
fi

# 3a. #303: revert the route_localnet sysctl. The package removal takes the
# /etc/sysctl.d/99-familydns.conf file, but the running kernel still has the
# value set until reboot — reset it explicitly so we don't leave LAN clients
# able to route to 127.0.0.0/8 after uninstall.
if [ -f /etc/sysctl.d/99-familydns.conf ] || [ "$(sysctl -n net.ipv4.conf.br-lan.route_localnet 2>/dev/null)" = "1" ]; then
  rm -f /etc/sysctl.d/99-familydns.conf
  sysctl -w net.ipv4.conf.br-lan.route_localnet=0 >/dev/null 2>&1 || true
  note "reset net.ipv4.conf.br-lan.route_localnet=0"
fi

# 4. Wipe familydns UCI. apk/opkg removal should have taken /etc/config/familydns
# (the package owns it), but be defensive: scrub UCI state and the file if it
# survived.
if uci -q show familydns >/dev/null 2>&1; then
  info "Wiping familydns UCI config..."
  # `uci -q delete familydns` clears the in-memory tree; commit to disk.
  while uci -q delete familydns >/dev/null 2>&1; do :; done
  uci commit familydns 2>/dev/null || true
  note "cleared familydns UCI state"
fi
if [ -e /etc/config/familydns ]; then
  rm -f /etc/config/familydns
  note "removed /etc/config/familydns"
fi

# 5. Purge mode: also kill manual-workaround leftovers from older e2e
# shakeouts (pre-#202, when modules were dropped under these paths by hand).
if [ "$PURGE" -eq 1 ]; then
  for d in /usr/lib/familydns /usr/lib/lua/familydns; do
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
