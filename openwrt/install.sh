#!/bin/sh
# FamilyDNS OpenWRT agent — interactive first-install script.
#
# Usage (on an OpenWRT 23.05.x router, as root):
#
#   sh -c "$(uclient-fetch -qO - https://raw.githubusercontent.com/sameerparekh/familydns/main/openwrt/install.sh)"
#
# Or download then run:
#
#   uclient-fetch -qO /tmp/familydns-install.sh \
#     https://raw.githubusercontent.com/sameerparekh/familydns/main/openwrt/install.sh
#   sh /tmp/familydns-install.sh
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
RELEASES_API="https://api.github.com/repos/sameerparekh/familydns/releases/tags/openwrt-latest"

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

# Auto-detect defaults.
lan_ip=$(uci -q get network.lan.ipaddr || true)
if [ -n "$lan_ip" ]; then
  lan_default=$(echo "$lan_ip" | awk -F. '{print $1"."$2"."$3"."}')
else
  lan_default="192.168.1."
fi
platform_ver=$(awk -F"'" '/DISTRIB_RELEASE/{print $2}' /etc/openwrt_release 2>/dev/null || echo unknown)

cat >"$TTY" <<EOF

FamilyDNS OpenWRT agent — interactive install
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
pkg_url=$(echo "$releases_json" \
  | jsonfilter -e '@.assets[*].browser_download_url' \
  | grep -E "\.${PKG_EXT}\$" \
  | head -n1)
[ -n "$pkg_url" ] || err "could not find a .$PKG_EXT asset in the latest release at $RELEASES_API"
pkg_path="/tmp/familydns.${PKG_EXT}"
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
uci set familydns.@familydns[0].api_url="$API_URL"
uci set familydns.@familydns[0].lan_prefix="$LAN_PREFIX"
uci commit familydns

# Enroll.
info "Enrolling router with $API_URL..."
if [ "$PKG_MGR" = apk ]; then
  agent_ver=$(apk list -I familydns 2>/dev/null | head -n1 | awk '{print $1}' | sed 's/^familydns-//')
else
  agent_ver=$(opkg info familydns | awk '/^Version:/{print $2}' | head -n1)
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

uci set familydns.@familydns[0].router_id="$router_id"
uci set familydns.@familydns[0].router_token="$router_token"
uci commit familydns

# Wire dnsmasq for familydns (#287):
#   - confdir=/tmp/dnsmasq.d makes dnsmasq load /tmp/dnsmasq.d/familydns.conf
#     (the agent's rendered profile-tag / NXDOMAIN / ipset config). Without
#     this, dnsmasq only loads /tmp/dnsmasq.<section>.d/, which the agent
#     doesn't know the name of.
#   - logqueries=1 → --log-queries=extra, so dnsmasq emits the structured
#     query+reply lines dns_log.lua parses.
#   - logfacility=/tmp/familydns-dnsmasq.log routes the query log to a file
#     instead of syslog AND triggers the dnsmasq init.d to bind-mount that
#     file RW into dnsmasq's ujail (so the dnsmasq user can actually write
#     it).
dnsmasq_changed=0
for opt_kv in \
  "confdir=/tmp/dnsmasq.d" \
  "logqueries=1" \
  "logfacility=/tmp/familydns-dnsmasq.log"; do
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
  info "Restarting dnsmasq with familydns query-log + confdir wiring..."
  /etc/init.d/dnsmasq restart
fi

# Idempotent uhttpd listener for the local block page on 127.0.0.1:8081.
if uci show uhttpd 2>/dev/null | grep -q "listen_http='127.0.0.1:8081'"; then
  info "Block-page uhttpd listener already configured."
else
  info "Configuring uhttpd block-page listener on 127.0.0.1:8081..."
  section=$(uci add uhttpd uhttpd)
  uci set "uhttpd.${section}.listen_http=127.0.0.1:8081"
  uci set "uhttpd.${section}.home=/www"
  uci commit uhttpd
  /etc/init.d/uhttpd reload
fi

# Enable and start.
info "Enabling and starting the familydns agent..."
/etc/init.d/familydns enable
/etc/init.d/familydns start

cat <<EOF

Done. Router enrolled successfully.

  Router ID:   $router_id
  API URL:     $API_URL
  LAN prefix:  $LAN_PREFIX

Watch the agent log:
  logread -f | grep familydns

The admin UI -> Routers should show a fresh last_seen_at for this router
within ~60 seconds.
EOF
