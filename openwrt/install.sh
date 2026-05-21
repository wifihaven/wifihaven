#!/bin/sh
# WifiHaven OpenWRT agent — interactive first-install script.
#
# Usage (on an OpenWRT 23.05.x router, as root):
#
#   sh -c "$(uclient-fetch -qO - https://raw.githubusercontent.com/wifihaven/wifihaven/main/openwrt/install.sh)"
#
# Or download then run:
#
#   uclient-fetch -qO /tmp/wifihaven-install.sh \
#     https://raw.githubusercontent.com/wifihaven/wifihaven/main/openwrt/install.sh
#   sh /tmp/wifihaven-install.sh
#
# The script prompts for the API URL, the one-time enrollment token, and the
# LAN prefix; downloads the latest .ipk from GitHub Releases; installs it;
# enrolls the router against the API; writes the returned credentials into
# UCI; sets up the uhttpd block-page listener; and starts the agent.
#
# The router's display name is set in the admin UI when the enrollment token
# is generated — the agent does not collect it.

set -eu

# TODO(#244): revert to /releases/latest once the rolling debug period for
# #228 is done and proper tags resume. Pairs with #280 (auto-updater swap).
RELEASES_API="https://api.github.com/repos/wifihaven/wifihaven/releases/tags/openwrt-latest"

err() { printf 'error: %s\n' "$*" >&2; exit 1; }
info() { printf '==> %s\n' "$*"; }

# Read from the controlling terminal so this works under `curl | sh`,
# where stdin is the script body.
TTY=/dev/tty
[ -r "$TTY" ] && [ -w "$TTY" ] || err "no /dev/tty available — run from an interactive shell or download the script first"

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
command -v uci        >/dev/null 2>&1 || err "uci not found — is this OpenWRT?"
command -v jsonfilter >/dev/null 2>&1 || err "jsonfilter not found — this should be present on stock OpenWRT"

# Detect the package manager. OpenWRT 24.10+ (SNAPSHOT) uses apk; earlier
# releases use opkg.
if command -v apk >/dev/null 2>&1; then
  PKG_MGR=apk
  PKG_EXT=apk
  PKG_INSTALL=add
elif command -v opkg >/dev/null 2>&1; then
  PKG_MGR=opkg
  PKG_EXT=ipk
  PKG_INSTALL=install
else
  err "neither apk nor opkg found — is this OpenWRT?"
fi

if ! command -v curl >/dev/null 2>&1; then
  info "curl not found — installing via $PKG_MGR..."
  $PKG_MGR update
  $PKG_MGR $PKG_INSTALL curl
  command -v curl >/dev/null 2>&1 || err "curl still not found after '$PKG_MGR $PKG_INSTALL curl'"
fi

# Ensure dnsmasq-full is installed. The agent's rendered config uses
# `nftset=...` directives to populate the eb_*/bl_* ipsets that the
# nftables forward-drop matches against; the basic `dnsmasq` package is
# compiled with HAVE_NFTSET disabled and silently refuses to start with
# that config ("recompile with HAVE_NFTSET defined" in syslog), leaving
# every hostname-based block ineffective. Vendor OpenWRT builds (e.g.
# GL.iNet) frequently ship plain dnsmasq instead of dnsmasq-full, so we
# detect and swap rather than relying on package-level depends.
ensure_dnsmasq_full() {
  if [ "$PKG_MGR" = apk ]; then
    apk list -I dnsmasq-full >/dev/null 2>&1 && return 0
    if apk list -I dnsmasq >/dev/null 2>&1; then
      info "Replacing dnsmasq with dnsmasq-full (required for nftset support)"
      apk del dnsmasq >/dev/null
    else
      info "Installing dnsmasq-full (required for nftset support)"
    fi
    apk add dnsmasq-full >/dev/null || err "failed to install dnsmasq-full"
  else
    opkg list-installed | grep -q '^dnsmasq-full ' && return 0
    info "Replacing dnsmasq with dnsmasq-full (required for nftset support)"
    opkg update >/dev/null
    opkg list-installed | grep -q '^dnsmasq ' && opkg remove dnsmasq >/dev/null
    opkg install dnsmasq-full >/dev/null || err "failed to install dnsmasq-full"
  fi
  /etc/init.d/dnsmasq restart >/dev/null 2>&1 || true
}
ensure_dnsmasq_full

# Auto-detect defaults.
lan_ip=$(uci -q get network.lan.ipaddr || true)
if [ -n "$lan_ip" ]; then
  lan_default=$(echo "$lan_ip" | awk -F. '{print $1"."$2"."$3"."}')
else
  lan_default="192.168.1."
fi
platform_ver=$(awk -F"'" '/DISTRIB_RELEASE/{print $2}' /etc/openwrt_release 2>/dev/null || echo unknown)

cat >"$TTY" <<EOF

WifiHaven OpenWRT agent — interactive install
=============================================
This will install the agent, enroll this router against your API server,
and start the agent. Press Ctrl-C at any prompt to abort.

EOF

prompt API_URL          "API server URL" "https://api.wifihaven.net"
[ -n "${API_URL:-}" ] || err "API URL is required"
API_URL=${API_URL%/}

prompt ENROLLMENT_TOKEN "One-time enrollment token (admin UI -> Routers -> Add router)"
[ -n "${ENROLLMENT_TOKEN:-}" ] || err "enrollment token is required"

cat >"$TTY" <<EOF

The LAN prefix is the literal IP-string prefix the agent uses to decide
which side of each connection is on your LAN when attributing flows to a
device. The default below was auto-detected from this router's LAN IP
(network.lan.ipaddr) and is correct for ~all home LANs — just press Enter
to accept it. Only override if your LAN is not on a /24 starting at .1
(e.g. you've carved up 10.0.0.0/16). A wrong value silently mis-attributes
every flow. For unattended provisioning, skip this script and use the
manual 'uci set ... lan_prefix=...' path in docs/install-openwrt.md §M3.

EOF
prompt LAN_PREFIX       "LAN prefix (literal, with trailing dot)" "$lan_default"

case "$LAN_PREFIX" in
  *.) : ;;
  *)  err "lan_prefix must end with a dot (e.g. '10.0.0.'); got '$LAN_PREFIX'" ;;
esac

# Download the latest package matching this system's package manager.
info "Resolving latest release asset (.$PKG_EXT)..."
releases_json=$(curl -fsSL "$RELEASES_API")
# Require the wifihaven_ prefix so we don't accidentally pick up a stale
# pre-rename familydns_*.{ipk,apk} that lingers in the openwrt-latest
# release between cleanup passes (#569). Fail loud on zero or multiple
# matches — a future surprise (e.g. unexpected per-arch variants of an
# _all package) should not silently install whichever sorts first.
asset_urls=$(echo "$releases_json" | jsonfilter -e '@.assets[*].browser_download_url')

pkg_urls=$(printf '%s\n' "$asset_urls" | grep -E '/wifihaven_[^/]*\.'"${PKG_EXT}"'$' || true)
pkg_count=$(printf '%s\n' "$pkg_urls" | grep -c . || true)
case "$pkg_count" in
  0) err "could not find a wifihaven_*.${PKG_EXT} asset in the latest release at $RELEASES_API" ;;
  1) pkg_url=$pkg_urls ;;
  *) err "expected exactly one wifihaven_*.${PKG_EXT} asset in $RELEASES_API, found $pkg_count:
$pkg_urls" ;;
esac
pkg_path="/tmp/wifihaven.${PKG_EXT}"
info "Downloading $pkg_url"
curl -fsSL -o "$pkg_path" "$pkg_url"

# Optional: LuCI web UI package. Older releases (pre-#750) don't ship it, so
# missing is fine — just skip. Multiple matches are still a release-bundling
# bug worth surfacing.
luci_pkg_path=""
luci_urls=$(printf '%s\n' "$asset_urls" | grep -E '/luci-app-wifihaven_[^/]*\.'"${PKG_EXT}"'$' || true)
luci_count=$(printf '%s\n' "$luci_urls" | grep -c . || true)
case "$luci_count" in
  0) info "no luci-app-wifihaven_*.${PKG_EXT} in release — skipping (older release?)" ;;
  1)
    luci_pkg_path="/tmp/luci-app-wifihaven.${PKG_EXT}"
    info "Downloading $luci_urls"
    curl -fsSL -o "$luci_pkg_path" "$luci_urls"
    ;;
  *) err "expected at most one luci-app-wifihaven_*.${PKG_EXT} asset in $RELEASES_API, found $luci_count:
$luci_urls" ;;
esac

# Install the package(s). Install the base agent first so the LuCI package's
# `Depends: wifihaven` resolves against the just-installed local file.
if [ "$PKG_MGR" = apk ]; then
  # apk installs a local file directly; no repo refresh needed since the .apk
  # carries its own dependency metadata and the runtime deps are in base.
  info "Installing $pkg_path..."
  apk add --allow-untrusted "$pkg_path"
  if [ -n "$luci_pkg_path" ]; then
    info "Installing $luci_pkg_path..."
    apk add --allow-untrusted "$luci_pkg_path" \
      || info "luci-app-wifihaven install failed (luci-base not present?) — continuing without web UI"
  fi
else
  # opkg needs the package index refreshed before installing a local .ipk so
  # that runtime deps (e.g. dnsmasq-full bits) can be resolved against the
  # repos.
  info "Refreshing opkg index..."
  opkg update >/dev/null
  info "Installing $pkg_path..."
  opkg install "$pkg_path"
  if [ -n "$luci_pkg_path" ]; then
    info "Installing $luci_pkg_path..."
    opkg install "$luci_pkg_path" \
      || info "luci-app-wifihaven install failed (luci-base not present?) — continuing without web UI"
  fi
fi

# Write base UCI config before enrolling so a re-run after a failed enroll
# does not have to re-enter these.
uci set wifihaven.@wifihaven[0].api_url="$API_URL"
uci set wifihaven.@wifihaven[0].lan_prefix="$LAN_PREFIX"
uci commit wifihaven

# Enroll.
info "Enrolling router with $API_URL..."
if [ "$PKG_MGR" = apk ]; then
  agent_ver=$(apk list -I wifihaven 2>/dev/null | head -n1 | awk '{print $1}' | sed 's/^wifihaven-//')
else
  agent_ver=$(opkg info wifihaven | awk '/^Version:/{print $2}' | head -n1)
fi
body=$(printf '{"enrollmentToken":"%s","platformVersion":"%s","agentVersion":"%s"}' \
  "$ENROLLMENT_TOKEN" "$platform_ver" "$agent_ver")

resp=$(curl -fsS -X POST "$API_URL/api/router/register" \
  -H 'Content-Type: application/json' \
  -d "$body") || err "enrollment request failed — check API_URL and that the enrollment token is valid"

router_id=$(echo    "$resp" | jsonfilter -e '@.routerId'    | head -n1)
router_token=$(echo "$resp" | jsonfilter -e '@.routerToken' | head -n1)
[ -n "$router_id" ]    || err "enrollment response missing routerId: $resp"
[ -n "$router_token" ] || err "enrollment response missing routerToken: $resp"

uci set wifihaven.@wifihaven[0].router_id="$router_id"
uci set wifihaven.@wifihaven[0].router_token="$router_token"
uci commit wifihaven

# Wire dnsmasq for wifihaven (#287):
#   - confdir=/tmp/dnsmasq.d makes dnsmasq load /tmp/dnsmasq.d/wifihaven.conf
#     (the agent's rendered profile-tag / NXDOMAIN / ipset config). Without
#     this, dnsmasq only loads /tmp/dnsmasq.<section>.d/, which the agent
#     doesn't know the name of.
#   - logqueries=1 → --log-queries=extra, so dnsmasq emits the structured
#     query+reply lines dns_log.lua parses.
#   - logfacility=/tmp/wifihaven-dnsmasq.log routes the query log to a file
#     instead of syslog AND triggers the dnsmasq init.d to bind-mount that
#     file RW into dnsmasq's ujail (so the dnsmasq user can actually write
#     it).
dnsmasq_changed=0
for opt_kv in \
  "confdir=/tmp/dnsmasq.d" \
  "logqueries=1" \
  "logfacility=/tmp/wifihaven-dnsmasq.log"; do
  opt=${opt_kv%%=*}
  val=${opt_kv#*=}
  cur=$(uci -q get "dhcp.@dnsmasq[0].$opt" || true)
  if [ "$cur" != "$val" ]; then
    uci set "dhcp.@dnsmasq[0].$opt=$val"
    dnsmasq_changed=1
  fi
done
if [ "$dnsmasq_changed" = 1 ]; then
  uci commit dhcp
  info "Restarting dnsmasq with wifihaven query-log + confdir wiring..."
  /etc/init.d/dnsmasq restart
fi

# Wire the uhttpd block-page listener + route_localnet sysctl. Shared
# helper so the package postinst (#542) and this manual installer don't
# drift — see openwrt/files/usr/lib/wifihaven/setup-uhttpd-block-page.sh.
info "Configuring uhttpd block-page listener..."
sh /usr/lib/wifihaven/setup-uhttpd-block-page.sh

# Enable and start.
info "Enabling and starting the wifihaven agent..."
/etc/init.d/wifihaven enable
/etc/init.d/wifihaven start

cat <<EOF

Done. Router enrolled successfully.

  Router ID:   $router_id
  API URL:     $API_URL
  LAN prefix:  $LAN_PREFIX

Watch the agent log:
  logread -f | grep wifihaven

The admin UI -> Routers should show a fresh last_seen_at for this router
within ~60 seconds.
EOF
