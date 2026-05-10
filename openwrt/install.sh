#!/bin/sh
# FamilyDNS OpenWRT agent — interactive first-install script.
#
# Usage (on an OpenWRT 23.05.x router, as root):
#
#   sh -c "$(curl -fsSL https://raw.githubusercontent.com/sameerparekh/familydns/main/openwrt/install.sh)"
#
# Or download then run:
#
#   curl -fsSL -o /tmp/familydns-install.sh \
#     https://raw.githubusercontent.com/sameerparekh/familydns/main/openwrt/install.sh
#   sh /tmp/familydns-install.sh
#
# The script prompts for the API URL, the one-time enrollment token, a router
# name, and the LAN prefix; downloads the latest .ipk from GitHub Releases;
# installs it; enrolls the router against the API; writes the returned
# credentials into UCI; sets up the uhttpd block-page listener; and starts
# the agent.

set -eu

RELEASES_API="https://api.github.com/repos/sameerparekh/familydns/releases/latest"

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
command -v opkg       >/dev/null 2>&1 || err "opkg not found — is this OpenWRT?"
command -v uci        >/dev/null 2>&1 || err "uci not found — is this OpenWRT?"
command -v curl       >/dev/null 2>&1 || err "curl not found — install it first: opkg update && opkg install curl"
command -v jsonfilter >/dev/null 2>&1 || err "jsonfilter not found — this should be present on stock OpenWRT"

# Auto-detect defaults.
lan_ip=$(uci -q get network.lan.ipaddr || true)
if [ -n "$lan_ip" ]; then
  lan_default=$(echo "$lan_ip" | awk -F. '{print $1"."$2"."$3"."}')
else
  lan_default="192.168.1."
fi
hostname_default=$(uci -q get system.@system[0].hostname || echo home-router)
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

prompt ROUTER_NAME      "Router name" "$hostname_default"
prompt LAN_PREFIX       "LAN prefix (literal, with trailing dot)" "$lan_default"

case "$LAN_PREFIX" in
  *.) : ;;
  *)  err "lan_prefix must end with a dot (e.g. '10.0.0.'); got '$LAN_PREFIX'" ;;
esac

# Download the latest .ipk.
info "Resolving latest release asset..."
ipk_url=$(curl -fsSL "$RELEASES_API" | jsonfilter -e '@.assets[0].browser_download_url' | head -n1)
[ -n "$ipk_url" ] || err "could not resolve latest release asset URL from $RELEASES_API"
info "Downloading $ipk_url"
curl -fsSL -o /tmp/familydns.ipk "$ipk_url"

# Install (refresh opkg index for runtime deps, then install the .ipk).
info "Refreshing opkg index..."
opkg update >/dev/null

info "Installing /tmp/familydns.ipk..."
opkg install /tmp/familydns.ipk

# Write base UCI config before enrolling so a re-run after a failed enroll
# does not have to re-enter these.
uci set familydns.@familydns[0].api_url="$API_URL"
uci set familydns.@familydns[0].lan_prefix="$LAN_PREFIX"
uci commit familydns

# Enroll.
info "Enrolling router with $API_URL..."
agent_ver=$(opkg info familydns | awk '/^Version:/{print $2}' | head -n1)
body=$(printf '{"enrollmentToken":"%s","routerName":"%s","platformVersion":"%s","agentVersion":"%s"}' \
  "$ENROLLMENT_TOKEN" "$ROUTER_NAME" "$platform_ver" "$agent_ver")

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

  Router name: $ROUTER_NAME
  Router ID:   $router_id
  API URL:     $API_URL
  LAN prefix:  $LAN_PREFIX

Watch the agent log:
  logread -f | grep familydns

The admin UI -> Routers -> $ROUTER_NAME should show a fresh last_seen_at
within ~60 seconds.
EOF
