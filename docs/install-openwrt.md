# Installing the FamilyDNS Agent on OpenWRT

This guide walks through a first install of the FamilyDNS agent on an OpenWRT
router, from downloading the `.ipk` to verifying that the agent is enrolled
and reporting policy state to the API.

The agent enforces per-device DNS filtering, accounts traffic per
`(mac, hostname)`, and streams connection events to the FamilyDNS API.

## 1. Prerequisites

- A router running **OpenWRT 23.05.x** (the package is built and tested
  against 23.05.5 by `.github/workflows/openwrt-build.yml`).
- Internet access from the router (the agent calls out to the API on the
  policy/usage/events timers, and the install command pulls the `.ipk` from
  GitHub Releases).
- A FamilyDNS API server already deployed and reachable from the router. See
  [`install-api.md`](install-api.md) if you need to set one up first.
- Root SSH access to the router.

The agent depends on `dnsmasq-full`, `nftables`, and `uhttpd`, all of which
ship with stock OpenWRT 23.05. The remaining runtime dependencies (`lua`,
`luci-lib-jsonc`, `conntrack-tools`, `curl`) are pulled in automatically by
`opkg`.

## 2. Download the `.ipk`

From a root shell on the router:

```sh
curl -fsSL -o /tmp/familydns.ipk \
  $(curl -sf https://api.github.com/repos/sameerparekh/familydns/releases/latest \
    | jsonfilter -e '@.assets[0].browser_download_url')
```

The package is pure Lua (`PKGARCH:=all`), so the same `.ipk` works on every
OpenWRT target — no need to match your router's CPU architecture.

## 3. Install

```sh
opkg update
opkg install /tmp/familydns.ipk
```

`opkg` resolves and installs `lua`, `luci-lib-jsonc`, `conntrack-tools`, and
`curl`. The `postinst` script enables the `familydns` procd service for
autostart but does **not** start it yet — enrollment must complete first.

## 4. Configure the API URL

```sh
uci set familydns.@familydns[0].api_url='https://api.example.com'
uci commit familydns
```

Replace `https://api.example.com` with the URL of your API server (no
trailing slash). HTTP works for testing on a LAN; for anything reachable from
the public internet, terminate TLS in front of the API.

## 5. Enroll the router

### 5a. Generate an enrollment token in the admin UI

Log in to the FamilyDNS admin UI as an admin user, go to
**Routers → Add router**, give it a name, and copy the one-time enrollment
token (looks like `et_5f3c9b…`). The enrollment token is single-use and is
invalidated as soon as the agent exchanges it for a router token.

### 5b. Exchange the enrollment token for a router token

From the router:

```sh
curl -s -X POST https://api.example.com/api/router/register \
  -H 'Content-Type: application/json' \
  -d '{
    "enrollmentToken": "et_5f3c9b…",
    "routerName":      "home-router",
    "platformVersion": "23.05.5",
    "agentVersion":    "0.1.0"
  }'
# → {"routerId":"9c1f2e8a-…","routerToken":"rt_a7d12b…"}
```

The endpoint is unauthenticated — the enrollment token in the body is the
auth. On success it returns a `routerId` (UUID) and a long-lived
`routerToken` that the agent uses as a bearer token for every subsequent API
call.

### 5c. Persist the credentials and start the agent

```sh
uci set familydns.@familydns[0].router_id='9c1f2e8a-…'
uci set familydns.@familydns[0].router_token='rt_a7d12b…'
uci commit familydns
/etc/init.d/familydns start
```

The agent is already enabled for autostart by the `postinst` script, so
nothing extra is needed for it to come back after a reboot.

## 6. Configure `lan_prefix` for non-default LAN subnets

> **Important — read this even if your LAN looks normal.**
> The default `lan_prefix` is `192.168.1.` (note the trailing dot). The
> agent uses this prefix to identify which side of a connection is on the
> LAN when attributing flows to a device. **If your LAN is not on
> `192.168.1.0/24`, the agent will mis-attribute every flow** until you fix
> this.

```sh
# Example: LAN on 10.0.0.0/24
uci set familydns.@familydns[0].lan_prefix='10.0.0.'
uci commit familydns
/etc/init.d/familydns restart
```

The value is a literal string prefix (with the trailing dot), not a CIDR. If
your LAN is a `/16` like `10.0.0.0/16`, use `'10.0.'` instead.

## 7. Set up the local block page

When the agent blocks an HTTP request, it DNATs port 80 to `127.0.0.1:8081`.
A local `uhttpd` instance serves `/www/familydns/block.html`, which the
agent installs. The block page redirects the browser to the API's
`/blocked?host=…&reason=…` endpoint, where users see why the request was
blocked.

Add a uhttpd listener on `127.0.0.1:8081` (the main uhttpd config keeps
serving LuCI on its existing ports):

```sh
uci add uhttpd uhttpd
uci set uhttpd.@uhttpd[-1].listen_http='127.0.0.1:8081'
uci set uhttpd.@uhttpd[-1].home='/www'
uci commit uhttpd
/etc/init.d/uhttpd reload
```

HTTPS to blocked hosts intentionally times out — TLS interception would
require installing a custom CA on every client, which is not practical for
a household-grade tool.

## 8. Verify

```sh
# The agent process is up:
ps | grep familydns-agent

# Tail the system log for agent output (procd routes stderr there):
logread -f | grep familydns
```

On a healthy start you should see lines like:

```
[familydns] starting conntrack watcher
[familydns] policy snapshot fetched, etag=…
[familydns] flushed N events to /api/router/events
```

Then check the admin UI: **Routers → `<your router name>`** should show a
fresh `last_seen_at`, updating roughly every 60 seconds (the policy poll
interval).

If the agent fails to start, the most common cause is a missing or empty
`router_token`:

```sh
uci get familydns.@familydns[0].router_token
```

## 9. (Optional) Auto-update

Routers running unattended should pull new agent releases automatically. The
auto-update cron job is tracked in
[#131](https://github.com/sameerparekh/familydns/issues/131); link forward
to that once it lands. Until then, upgrade manually by re-running steps 2
and 3 — `opkg install --force-reinstall` preserves
`/etc/config/familydns`, so the router credentials survive the upgrade and
no re-enrollment is needed.

## Reference

- [`openwrt/README.md`](../openwrt/README.md) — package layout, build, and
  developer-facing details.
- [`docs/architecture.md`](architecture.md) §7 — full design of the OpenWRT
  agent.
- [`docs/deploy.md`](deploy.md) — overall CD pipeline for both deployment
  targets.
