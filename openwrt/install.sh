#!/bin/sh
# WifiHaven OpenWRT agent — interactive first-install script.
#
# Supported firmware baseline: the agent is currently validated only on
# FLASHED VANILLA OpenWRT (23.05.x / 24.10+ / SNAPSHOT). GL.iNet routers ship
# GL.iNet's own *forked* OpenWRT firmware out of the box; that stock firmware
# is NOT YET a verified target — see
# https://github.com/wifihaven/wifihaven/issues/2304. This script will run
# wherever it finds apk-or-opkg + uci + jsonfilter (so it may start on stock
# GL.iNet firmware), but running it there is unverified. On GL.iNet hardware,
# flash vanilla OpenWRT first (docs/install-flint2.md) — that is the supported
# path.
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
#
# Before running this, mint a one-time enrollment token in the WifiHaven
# dashboard. The /routers?add=1 deep link opens the add-router dialog directly
# (name -> "Generate Token"):
#   Cloud installs:  https://app.wifihaven.net/routers?add=1
#   Self-hosted:     /routers?add=1 on your own dashboard host

set -eu

# TODO(#244): revert to /releases/latest once the rolling debug period for
# #228 is done and proper tags resume. Pairs with #280 (auto-updater swap).
RELEASES_API="https://api.github.com/repos/wifihaven/wifihaven/releases/tags/openwrt-latest"

err() { printf 'error: %s\n' "$*" >&2; exit 1; }
info() { printf '==> %s\n' "$*"; }

# Package-owned paths this script has to be able to repair (#2554). Kept as
# variables so the recovery functions below can be exercised against a fake
# root by openwrt/test/install_reinstall_cycle_spec.sh.
WIFIHAVEN_CONFIG=/etc/config/wifihaven
WIFIHAVEN_SYSCTL=/etc/sysctl.d/99-wifihaven.conf
WIFIHAVEN_UCI_DEFAULTS=/etc/uci-defaults
# Where a displaced config is parked if we have to adopt a .apk-new over it.
# It can carry a router_token, so uninstall.sh erases it too — the two spellings
# are pinned equal by openwrt/test/install_reinstall_cycle_spec.sh.
WIFIHAVEN_CONFIG_BACKUP=/tmp/wifihaven-config.bak-2554

# Read from the controlling terminal so this works under `curl | sh`,
# where stdin is the script body.
TTY=/dev/tty
# #2235: this installer is interactive (it prompts for the API URL, the
# enrollment token, and the LAN prefix). If there's no controlling terminal —
# e.g. run non-interactively / piped without a tty — fail loud and point the
# operator at where the enrollment token comes from, since that's the prompt
# most likely to strand them. Mint it first in the dashboard: Routers ->
# "+ Enroll Router" -> name -> "Generate Token".
[ -r "$TTY" ] && [ -w "$TTY" ] || err "no /dev/tty available — run this from an interactive root shell (or download the script first, then run it). This installer prompts for the one-time enrollment token: mint it in the WifiHaven dashboard via the add-router deep link (cloud: https://app.wifihaven.net/routers?add=1; self-hosted: /routers?add=1 on your own dashboard host)."

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
    # apk list -I exits 0 with empty stdout when a package is not installed
    # (apk-tools v3, OpenWRT 24.10+).  Use `apk info -e` instead — it exits
    # nonzero on miss, making it safe to use as a boolean probe (#704).
    apk info -e dnsmasq-full >/dev/null 2>&1 && return 0
    if apk info -e dnsmasq >/dev/null 2>&1; then
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

cat >"$TTY" <<'EOF'

          _  __ _ _
 _      _(_)/ _(_) |__   __ ___   _____ _ __
| | /| / / | |_| | '_ \ / _` \ \ / / _ \ '_ \
| |/ |/ /| |  _| | | | | (_| |\ V /  __/ | | |
|__/|__/ |_|_| |_|_| |_|\__,_| \_/ \___|_| |_|

  a haven for the household network

OpenWRT agent — interactive install
This will install the agent, enroll this router against your API server,
and start the agent. Press Ctrl-C at any prompt to abort.

EOF

prompt API_URL          "API server URL" "https://api.wifihaven.net"
[ -n "${API_URL:-}" ] || err "API URL is required"
API_URL=${API_URL%/}

# #1174: the block page redirects blocked clients to the public SPA that serves
# the /blocked route. In the cloud deploy the SPA lives on a SEPARATE host
# (Cloudflare Pages, e.g. https://app.wifihaven.net) from the API
# (api.wifihaven.net), so the redirect must target the SPA host, not the API.
# Self-hosted installs bundle the SPA into the API image on the same host, so
# the block-page URL is just the API URL there. Default to the canonical app
# host when enrolling against the managed cloud API; otherwise default to the
# API URL (correct for self-hosted).
# #1841/#1832: new cloud installs point at app.wifihaven.net (the canonical app
# host). Routers enrolled before the rename were re-pointed to app.wifihaven.net
# directly (re-run install / edit the block_page_url UCI key); there is no apex
# /blocked compat shim (#1842) — the apex now serves only the marketing site.
case "$API_URL" in
  *api.wifihaven.net*) block_page_default="https://app.wifihaven.net" ;;
  *)                   block_page_default="$API_URL" ;;
esac
prompt BLOCK_PAGE_URL   "Public SPA URL for the block page" "$block_page_default"
BLOCK_PAGE_URL=${BLOCK_PAGE_URL%/}

# #2235: point the operator at the EXACT place to mint the enrollment token.
# Derive the URL from the SPA host we already prompted for ($BLOCK_PAGE_URL)
# rather than hardcoding app.wifihaven.net — self-hosted installs serve the SPA
# from their own host, so their Routers tab is on that host, not the cloud one.
# The `?add=1` deep link opens the add-router dialog on arrival (RoutersPage.tsx
# consumes the param), so the operator lands directly on "Generate Token" with
# no extra clicks — just enter a name and copy the token.
cat >"$TTY" <<EOF

The enrollment token is a one-time secret you generate in the WifiHaven
dashboard. To get it, open this link (it opens the add-router dialog directly):

  ${BLOCK_PAGE_URL}/routers?add=1     (Routers tab; admin login required)

Then enter a name, click "Generate Token", copy the token it shows, and
paste it below (it is shown only once).

  Cloud installs:  https://app.wifihaven.net/routers?add=1
  Self-hosted:     /routers?add=1 on your own dashboard host (shown above)

EOF
prompt ENROLLMENT_TOKEN "One-time enrollment token"
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

# --- #2554 recovery: repair a package install that came back half-applied ---
#
# A pre-#2554 uninstall `rm`'d two files the package OWNS
# (/etc/config/wifihaven and /etc/sysctl.d/99-wifihaven.conf), which
# desynchronises the apk/opkg file database. On the install that follows,
# apk writes the shipped config to /etc/config/wifihaven.apk-new (opkg:
# .opkg-new) instead of the real path, and does not restore the sysctl file at
# ALL — not even as a .apk-new. Both failures are silent: the first surfaces
# only as a bare `uci: Entry not found` from the very next uci call, the second
# not until the router reboots. Repair both before touching uci, then verify.

# Restore the route_localnet sysctl file if the package didn't put it back.
#
# This setting is what makes the block page reachable: render.lua DNATs blocked
# HTTP/80 traffic to 127.0.0.1:8081, and the kernel refuses to route a packet
# destined for 127.0.0.0/8 that arrived on a non-loopback interface unless
# route_localnet is set on that interface. Checking the LIVE value here would
# be exactly the wrong test — setup-uhttpd-block-page.sh sets it at runtime, so
# it reads healthy even when the file is gone. Only the FILE survives a reboot.
#
# install.sh is fetched standalone over the network and cannot read the
# package's own copy of this file, so the setting is duplicated here. The two
# copies are pinned equal by openwrt/test/install_reinstall_cycle_spec.sh
# (docs/process/single-source-of-truth.md — ACCEPT + TEST-PIN).
restore_wifihaven_sysctl() {
  [ -f "$WIFIHAVEN_SYSCTL" ] && return 0
  info "Restoring $WIFIHAVEN_SYSCTL (missing — see #2554); without it the block page dies on the next reboot"
  mkdir -p "$(dirname "$WIFIHAVEN_SYSCTL")"
  cat >"$WIFIHAVEN_SYSCTL" <<'SYSCTL_EOF'
# wifihaven — allow DNAT from LAN clients to 127.0.0.1:8081 (block page).
#
# Restored by openwrt/install.sh (#2554) because the package did not put it
# back. Canonical copy: openwrt/files/etc/sysctl.d/99-wifihaven.conf; the two
# are pinned equal by openwrt/test/install_reinstall_cycle_spec.sh.
#
# Scoped to br-lan only (the LAN bridge); we never want to route external
# loopback traffic in from WAN.
net.ipv4.conf.br-lan.route_localnet = 1
SYSCTL_EOF
  sysctl -p "$WIFIHAVEN_SYSCTL" >/dev/null 2>&1 || true
}

# Make sure the `config wifihaven` anchor section the uci calls below address
# actually exists. Returns nonzero if it could not be recovered.
ensure_wifihaven_config() {
  uci -q show wifihaven.@wifihaven[0] >/dev/null 2>&1 && return 0

  # Adopt the config the package manager parked beside the real path.
  for _wh_new in "$WIFIHAVEN_CONFIG.apk-new" "$WIFIHAVEN_CONFIG.opkg-new"; do
    [ -f "$_wh_new" ] || continue
    info "Adopting $_wh_new as $WIFIHAVEN_CONFIG (#2554)"
    # Only reachable when the current file has no wifihaven section at all, but
    # keep a copy of whatever was there rather than silently overwriting it.
    # Park it under /tmp, NOT beside the config: the displaced file may still
    # carry a router_token, and uninstall.sh's scrub only covers
    # /etc/config/wifihaven itself — a permanent sibling would be a credential
    # the uninstaller never erases. uninstall.sh prunes this exact path, and
    # /tmp is tmpfs so it also disappears at the next reboot.
    [ -s "$WIFIHAVEN_CONFIG" ] && cp "$WIFIHAVEN_CONFIG" "$WIFIHAVEN_CONFIG_BACKUP"
    mv "$_wh_new" "$WIFIHAVEN_CONFIG"
    break
  done
  uci -q show wifihaven.@wifihaven[0] >/dev/null 2>&1 && return 0

  # Nothing to adopt — synthesise the anchor section. The named form matches
  # the section the package ships (`config wifihaven 'wifihaven'`), and
  # `@wifihaven[0]` resolves to it.
  info "Creating the wifihaven UCI anchor section in $WIFIHAVEN_CONFIG (#2554)"
  # `touch`, not `: >file`: a redirection failure on the `:` special built-in
  # would kill this shell outright instead of surfacing through the err below.
  [ -f "$WIFIHAVEN_CONFIG" ] || touch "$WIFIHAVEN_CONFIG"
  uci set wifihaven.wifihaven=wifihaven
  uci commit wifihaven

  # A synthesised file has none of the other shipped sections. Rather than
  # duplicate their contents here, run the package's own (idempotent)
  # uci-defaults stubs if they are still pending — those scripts are the single
  # source of truth for the sections they own (`settings`, the escape hatch; and
  # `ws`, whose #2608 default-on marker must exist here too, or a later upgrade
  # would run the migration with the marker absent and delete an opt-out the
  # operator set after this recovery). Leaving the files in place is fine — they
  # are idempotent, and OpenWrt's uci-defaults pass (`/etc/init.d/boot` ->
  # `uci_apply_defaults`) runs whatever is still in /etc/uci-defaults/ on the
  # next boot and deletes each script that exits 0.
  #
  # Keep this list in step with the uci-defaults scripts openwrt/Makefile
  # installs; `openwrt/test/ws_default_on_spec.sh` pins that 97 is here.
  for _wh_defaults in 96-wifihaven-settings; do
    if [ -f "$WIFIHAVEN_UCI_DEFAULTS/$_wh_defaults" ]; then
      sh "$WIFIHAVEN_UCI_DEFAULTS/$_wh_defaults" >/dev/null 2>&1 || true
    fi
  done

  uci -q show wifihaven.@wifihaven[0] >/dev/null 2>&1
}

restore_wifihaven_sysctl
ensure_wifihaven_config || err "the wifihaven UCI config section is missing and could not be recovered.
The package did not leave a usable $WIFIHAVEN_CONFIG (and no .apk-new/.opkg-new
copy was found beside it) — this is the #2554 failure mode, caused by an older
uninstall.sh deleting package-owned files behind apk's back. Recover with:

  $PKG_MGR $PKG_INSTALL --force-overwrite $pkg_path   # add --allow-untrusted on apk
  ls -l $WIFIHAVEN_CONFIG*                            # expect the real path, not .apk-new

then re-run this installer. Do NOT ignore this: without the section the router
cannot be enrolled."

# Write base UCI config before enrolling so a re-run after a failed enroll
# does not have to re-enter these.
uci set wifihaven.@wifihaven[0].api_url="$API_URL"
uci set wifihaven.@wifihaven[0].block_page_url="$BLOCK_PAGE_URL"  # #1174
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

# #2554 post-install self-check. Every item below is individually SILENT when
# it's wrong — the install looks like it succeeded and the breakage surfaces
# hours or days later somewhere else. Assert them here, while the operator is
# still at the keyboard, and name the specific thing that's missing rather than
# leaving them with a three-word uci error.
#
# Fatal vs. warning: the first three are load-bearing (no anchor section => the
# router cannot be enrolled or configured; no sysctl FILE => the block page
# stops working at the next reboot; no uhttpd listener => the block-page DNAT
# has nowhere to land), so they abort. The `settings` escape-hatch section is
# belt-and-suspenders — the agent fails safe when the key is absent
# (missing => enforcement ON) and wifihaven-disable/-enable self-create it — so
# it warns loudly instead of stranding an otherwise-good install.
post_install_self_check() {
  _sc_fail=""

  uci -q show wifihaven.@wifihaven[0] >/dev/null 2>&1 || _sc_fail="$_sc_fail
  - the wifihaven UCI anchor section is missing from $WIFIHAVEN_CONFIG
    (check for a leftover $WIFIHAVEN_CONFIG.apk-new / .opkg-new beside it)"

  # Assert the FILE, not the live sysctl value: setup-uhttpd-block-page.sh sets
  # the value at runtime, so `sysctl -n net.ipv4.conf.br-lan.route_localnet`
  # reads 1 even when the file is gone. Only the file survives a reboot.
  [ -f "$WIFIHAVEN_SYSCTL" ] || _sc_fail="$_sc_fail
  - $WIFIHAVEN_SYSCTL is missing on disk. The live route_localnet value may
    still read 1 (set at runtime), but it will NOT survive a reboot, and
    without it the kernel silently drops the DNAT'd traffic that carries
    blocked clients to the block page."

  # Match the SAME anchored, per-line form setup-uhttpd-block-page.sh and
  # uninstall.sh use to identify the block-page section, so all three agree on
  # what "the listener is present" means. An unanchored substring match would
  # be satisfied by the stock `uhttpd.main.listen_http='0.0.0.0:80'` line plus
  # an unrelated 8081 elsewhere in the output.
  _sc_uhttpd=$(uci show uhttpd 2>/dev/null || true)
  printf '%s\n' "$_sc_uhttpd" \
    | grep -Eq "^uhttpd\.[^.]+\.listen_http=.*'127\.0\.0\.1:8081'" || _sc_fail="$_sc_fail
  - no uhttpd block-page listener on 127.0.0.1:8081 — blocked HTTP traffic is
    DNAT'd there and would hit a closed port."
  printf '%s\n' "$_sc_uhttpd" \
    | grep -Eq "^uhttpd\.[^.]+\.listen_https=.*'127\.0\.0\.1:8443'" || _sc_fail="$_sc_fail
  - no uhttpd block-page TLS listener on 127.0.0.1:8443 — blocked HTTPS
    traffic would fail with a connection reset instead of the block page."

  # uci-defaults must be either consumed (its effect is visible) or pending
  # (still on disk, so `/etc/init.d/boot`'s uci_apply_defaults runs it on the
  # next boot). Neither means it ran, was deleted, and its effect was later
  # clobbered.
  if [ ! -f "$WIFIHAVEN_UCI_DEFAULTS/96-wifihaven-settings" ] \
     && [ -z "$(uci -q get wifihaven.settings.enforcement_disabled || true)" ]; then
    printf 'warning: the wifihaven.settings section is absent and %s/96-wifihaven-settings is gone (#2554).\n' \
      "$WIFIHAVEN_UCI_DEFAULTS" >&2
    printf 'warning: enforcement still defaults to ON; restore the LuCI toggle with:\n' >&2
    printf 'warning:   uci set wifihaven.settings=settings; uci set wifihaven.settings.enforcement_disabled=0; uci commit wifihaven\n' >&2
  fi

  [ -z "$_sc_fail" ] || err "post-install self-check failed:$_sc_fail

This install is half-applied. See
https://github.com/wifihaven/wifihaven/issues/2554 for the recovery steps.
NOTE: enrollment already succeeded, so the one-time enrollment token you
entered has been CONSUMED — the router is registered (router_id is in
$WIFIHAVEN_CONFIG) and the agent was not started. Fix the item(s) above and
re-run the installer with a FRESH enrollment token."
}
post_install_self_check

# Enable and start.
info "Enabling and starting the wifihaven agent..."
/etc/init.d/wifihaven enable
/etc/init.d/wifihaven start

# #2231: OpenWrt's dnsmasq init script probes for a rogue DHCP server on
# every restart (/etc/init.d/dnsmasq line 563: `[ $force -gt 0 ] ||
# dhcp_check`; dhcp_check at line 108 runs `udhcpc -n -q -s /bin/true -t 1`,
# line 121 — OpenWrt 25.12.3). On a gateway where dnsmasq IS the DHCP server
# nothing answers, so each non-ignored dhcp section burns udhcpc's ~3.5s
# discover timeout per restart (measured 3.53s default vs 0.34s with
# force=1). `option force 1` skips the probe, at the cost of the guard
# against a second DHCP server on the LAN — worth keeping only until this
# router is established as the network's sole DHCP server.
#
# /etc/config/dhcp is the operator's file: never set force silently. Offer
# it here, after enrollment, with an explicit y/N prompt (default No). Skip
# entirely when non-interactive or already set. Full write-up in
# docs/router-tuning.md.
offer_dhcp_force() {
  [ "${WIFIHAVEN_NONINTERACTIVE:-0}" = "0" ] || return 0
  [ -r "$TTY" ] && [ -w "$TTY" ] || return 0

  unforced=""
  for sec in $(uci show dhcp 2>/dev/null | sed -n 's/^dhcp\.\([^.=]*\)=dhcp$/\1/p'); do
    [ "$(uci -q get "dhcp.$sec.ignore" 2>/dev/null || true)" = "1" ] && continue
    [ "$(uci -q get "dhcp.$sec.force" 2>/dev/null || true)" = "1" ] && continue
    unforced="$unforced $sec"
  done
  [ -n "$unforced" ] || return 0

  cat >"$TTY" <<EOF

Optional tuning (#2231): every dnsmasq restart runs OpenWrt's rogue-DHCP
probe — ~3.5s per DHCP section when no other DHCP server answers (measured
3.53s -> 0.34s with 'option force 1'). Setting force=1 skips the probe but
disables the guard against a second DHCP server on your LAN — say yes only
if this router is (or is about to be) your network's only DHCP server.
EOF
  prompt DHCP_FORCE_ANSWER "Set force=1 on:${unforced}? (y/N)" "N"
  case "$DHCP_FORCE_ANSWER" in
    y|Y|yes|YES|Yes)
      for sec in $unforced; do
        uci set "dhcp.$sec.force=1"
      done
      uci commit dhcp
      info "Set force=1 on:${unforced}. Takes effect on the next dnsmasq restart."
      info "To revert: uci delete dhcp.<section>.force; uci commit dhcp; /etc/init.d/dnsmasq restart"
      ;;
    *)
      info "Leaving the rogue-DHCP probe enabled (dhcp force unset)."
      ;;
  esac
}
offer_dhcp_force

cat <<EOF

Done. Router enrolled successfully.

  Router ID:   $router_id
  API URL:     $API_URL
  Block page:  $BLOCK_PAGE_URL
  LAN prefix:  $LAN_PREFIX

Watch the agent log:
  logread -f | grep wifihaven

The admin UI -> Routers should show a fresh last_seen_at for this router
within ~60 seconds.

Emergency off switch (escape hatch):
  If blocking ever breaks the internet, a policy is wrong, or the WifiHaven
  server is down, you can turn OFF all enforcement on this router — it works
  even with the server unreachable and takes effect within seconds:

    wifihaven-disable     # turn off all blocking (internet works normally)
    wifihaven-enable      # restore normal blocking

  Or in LuCI: Services -> WifiHaven -> Settings -> "Disable all WifiHaven
  enforcement". Full details: docs/escape-hatch.md.
EOF
