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

prompt API_URL          "API server URL (e.g. https://api.example.com)"
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
pkg_urls=$(echo "$releases_json" \
  | jsonfilter -e '@.assets[*].browser_download_url' \
  | grep -E '/wifihaven_[^/]*\.'"${PKG_EXT}"'$')
pkg_count=$(printf '%s\n' "$pkg_urls" | grep -c .)
case "$pkg_count" in
  0) err "could not find a wifihaven_*.${PKG_EXT} asset in the latest release at $RELEASES_API" ;;
  1) pkg_url=$pkg_urls ;;
  *) err "expected exactly one wifihaven_*.${PKG_EXT} asset in $RELEASES_API, found $pkg_count:
$pkg_urls" ;;
esac
pkg_path="/tmp/wifihaven.${PKG_EXT}"
info "Downloading $pkg_url"
curl -fsSL -o "$pkg_path" "$pkg_url"

# Install the package.
if [ "$PKG_MGR" = apk ]; then
  # apk installs a local file directly; no repo refresh needed since the .apk
  # carries its own dependency metadata and the runtime deps are in base.
  info "Installing $pkg_path..."
  apk add --allow-untrusted "$pkg_path"
else
  # opkg needs the package index refreshed before installing a local .ipk so
  # that runtime deps (e.g. dnsmasq-full bits) can be resolved against the
  # repos.
  info "Refreshing opkg index..."
  opkg update >/dev/null
  info "Installing $pkg_path..."
  opkg install "$pkg_path"
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

# Idempotent uhttpd listener for the local block page on 127.0.0.1:8081 and
# [::1]:8081 (#411 — v6 sibling so v6 HTTP requests to blocked hosts land on
# the block page, not a connection error).
#
# Every request to this listener is dispatched to the lua handler at
# /www/wifihaven/handler.lua (uhttpd-mod-lua). The handler resolves the
# requesting device's MAC (from /proc/net/arp by REMOTE_ADDR), looks up the
# per-MAC block reason written by the agent, and returns a redirect to the
# API's /blocked page with mac+reason populated (#437). The static
# index.html that used to live here had no way to know the client's MAC.
uhttpd_section=$(uci show uhttpd 2>/dev/null \
  | awk -F'[.=]' "/^uhttpd\\.[^.]+\\.listen_http=.*'127\\.0\\.0\\.1:8081'/{print \$2; exit}")
uhttpd_changed=0
if [ -n "$uhttpd_section" ]; then
  current_home=$(uci -q get "uhttpd.${uhttpd_section}.home" || echo "")
  if [ "$current_home" != "/www/wifihaven" ]; then
    info "Updating block-page uhttpd home to /www/wifihaven..."
    uci set "uhttpd.${uhttpd_section}.home=/www/wifihaven"
    uhttpd_changed=1
  fi
else
  info "Configuring uhttpd block-page listener on 127.0.0.1:8081..."
  uhttpd_section=$(uci add uhttpd uhttpd)
  uci add_list "uhttpd.${uhttpd_section}.listen_http=127.0.0.1:8081"
  uci set "uhttpd.${uhttpd_section}.home=/www/wifihaven"
  uhttpd_changed=1
fi

# Add v6 listener if absent. uhttpd treats listen_http as a list, so add_list
# is safe to append; we just need to dedupe.
if ! uci -q get "uhttpd.${uhttpd_section}.listen_http" \
    | tr ' ' '\n' | grep -qx '\[::1\]:8081'; then
  info "Adding v6 block-page uhttpd listener on [::1]:8081..."
  uci add_list "uhttpd.${uhttpd_section}.listen_http=[::1]:8081"
  uhttpd_changed=1
fi

# Route every URL through the lua handler so the block page can do per-MAC
# lookups instead of serving a static file (#437).
desired_lua_prefix='/'
desired_lua_handler='/www/wifihaven/handler.lua'
current_lua_prefix=$(uci -q get "uhttpd.${uhttpd_section}.lua_prefix" || echo "")
current_lua_handler=$(uci -q get "uhttpd.${uhttpd_section}.lua_handler" || echo "")
if [ "$current_lua_prefix" != "$desired_lua_prefix" ]; then
  uci set "uhttpd.${uhttpd_section}.lua_prefix=$desired_lua_prefix"
  uhttpd_changed=1
fi
if [ "$current_lua_handler" != "$desired_lua_handler" ]; then
  uci set "uhttpd.${uhttpd_section}.lua_handler=$desired_lua_handler"
  uhttpd_changed=1
fi

if [ "$uhttpd_changed" = 1 ]; then
  uci commit uhttpd
  /etc/init.d/uhttpd reload
else
  info "Block-page uhttpd listener already configured (v4 + v6, lua handler)."
fi

# #303: enable route_localnet on the LAN bridge so the nft prerouting DNAT
# to 127.0.0.1:8081 (block-page uhttpd) is routable for LAN clients. The
# persistent file is shipped at /etc/sysctl.d/99-wifihaven.conf by the
# package; apply it now so the running kernel picks it up without a reboot.
# Manual installs (no package manager) also need this — sysctl -p loads the
# file if present.
if [ -f /etc/sysctl.d/99-wifihaven.conf ]; then
  sysctl -p /etc/sysctl.d/99-wifihaven.conf >/dev/null 2>&1 || true
else
  sysctl -w net.ipv4.conf.br-lan.route_localnet=1 >/dev/null 2>&1 || true
fi

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
