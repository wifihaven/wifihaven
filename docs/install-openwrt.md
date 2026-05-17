# Installing the WifiHaven Agent on OpenWRT

This guide walks through a first install of the WifiHaven agent on an OpenWRT
router. The recommended path is the one-shot install script; the manual
steps below it exist for debugging and for environments where running a
piped shell script is not acceptable.

The agent enforces per-device connection-level filtering (nftables forward-drop
keyed on MAC + destination ipset), accounts traffic per `(mac, hostname)`, and
streams connection events to the WifiHaven API. dnsmasq on the router resolves
DNS normally — it is not the enforcement plane (see
[`architecture.md` §0](architecture.md#0-enforcement-model)).

## 1. Prerequisites

- A router running **OpenWRT 23.05.x** (opkg / `.ipk`) **or OpenWRT 24.10+ /
  SNAPSHOT** (apk / `.apk`). CI builds and attaches both artifacts to every
  release.
- Internet access from the router.
- Root SSH access to the router.
- A WifiHaven API server already deployed and reachable from the router. See
  [`install-api.md`](install-api.md) if you need to set one up first.
- A one-time enrollment token generated in the admin UI under
  **Routers → Add router** (looks like `et_5f3c9b…`).
The agent depends on `dnsmasq-full`, `nftables`, and `uhttpd`. The latter
two ship with stock OpenWRT on both 23.05.x and 24.10+. `dnsmasq-full` is
the stock build on a generic OpenWRT image but vendor images (notably
GL.iNet) ship the basic `dnsmasq` instead, which is compiled without
`HAVE_NFTSET` and silently refuses to load the agent's config — leaving
every hostname-based block ineffective. The install script auto-detects
this and swaps in `dnsmasq-full`; if you're installing manually, do the
swap yourself before starting the agent (`apk del dnsmasq && apk add
dnsmasq-full`, or the `opkg` equivalent). The remaining runtime
dependencies (`lua`, `luci-lib-jsonc`, `conntrack-tools`, `curl`) are
pulled in automatically by the system package manager (`opkg` on 23.05.x,
`apk` on 24.10+) — the package names are the same on both.

## 2. Install with the one-shot script (recommended)

SSH into the router as root and run:

```sh
sh -c "$(uclient-fetch -qO - https://raw.githubusercontent.com/wifihaven/wifihaven/main/openwrt/install.sh)"
```

The script prompts for:

| Prompt | Notes |
|---|---|
| API server URL | e.g. `https://api.example.com` (no trailing slash needed; the script trims it). |
| Enrollment token | The `et_…` value from the admin UI. Single-use; invalidated on success. |
| LAN prefix | Auto-detected from `network.lan.ipaddr` (last octet stripped); must end with a dot. The agent uses this literal-string prefix to decide which side of each connection is on the LAN when attributing flows to a device — accept the default unless your LAN isn't a /24 starting at .1. A wrong value silently mis-attributes every flow. For unattended provisioning, skip this script and use the manual `uci` path in §M3. |

The router's display name comes from whatever you typed in the admin UI
when you generated the enrollment token — the agent does not collect it.

It then:

1. Detects the router's package manager (`opkg` on 23.05.x, `apk` on
   24.10+/SNAPSHOT), downloads the matching asset (`.ipk` or `.apk`) from
   the latest GitHub release, and installs it (`opkg install …` or
   `apk add --allow-untrusted …`).
2. Writes `api_url` and `lan_prefix` to `/etc/config/wifihaven`.
3. POSTs `/api/router/register` to exchange the enrollment token for a
   `routerId` and `routerToken`, and writes both to UCI.
4. Adds a `uhttpd` listener on `127.0.0.1:8081` for the local block page
   (idempotent — skipped if already configured).
5. Enables and starts the `wifihaven` procd service.

If anything goes wrong it aborts before starting the agent, leaving the
router in a clean state so you can re-run after fixing the underlying issue.

### Prefer to read before piping?

Download and inspect the script first:

```sh
uclient-fetch -qO /tmp/wifihaven-install.sh \
  https://raw.githubusercontent.com/wifihaven/wifihaven/main/openwrt/install.sh
less /tmp/wifihaven-install.sh
sh /tmp/wifihaven-install.sh
```

### Uninstalling

To cleanly revert the install (stop and disable the service, remove the
package, drop the uhttpd block-page listener, wipe the wifihaven UCI
config):

```sh
sh -c "$(uclient-fetch -qO - https://raw.githubusercontent.com/wifihaven/wifihaven/main/openwrt/uninstall.sh)"
```

Pass `--purge` to additionally remove `/usr/lib/wifihaven` and
`/usr/lib/lua/wifihaven` (manual-workaround leftovers from older e2e
shakeouts). The script is idempotent — re-running on an already-clean
router exits 0.

## 3. Verify

```sh
# Tail the system log for agent output:
logread -f | grep wifihaven
```

On a healthy start you should see lines like:

```
[wifihaven] starting conntrack watcher
[wifihaven] policy snapshot fetched, etag=…
[wifihaven] flushed N events to /api/router/events
```

Then check the admin UI: **Routers → `<your router name>`** should show a
fresh `last_seen_at`, updating roughly every 60 seconds (the policy poll
interval).

## 4. (Optional) Auto-update

Routers running unattended should pull new agent releases automatically. The
auto-update cron job is tracked in
[#131](https://github.com/wifihaven/wifihaven/issues/131). Until then,
upgrade manually by re-running the one-shot install command from §2 — the
script's install step (`opkg install` or `apk add --allow-untrusted`) uses
the standard upgrade path, which preserves `/etc/config/wifihaven`, so the
router credentials survive and no re-enrollment is needed.

## Manual install (fallback)

Use this if you cannot or do not want to run the one-shot script — for
example, if you're debugging a failed install or want to script each step
into your own provisioning system.

### M1. Download the matching package

Pick the asset that matches your router's package manager (`.ipk` on
23.05.x, `.apk` on 24.10+/SNAPSHOT). Both are pure Lua (`PKGARCH:=all` /
`noarch`), so the same artifact works on every OpenWRT target of that
generation.

```sh
# OpenWRT 23.05.x (opkg):
curl -fsSL -o /tmp/wifihaven.ipk \
  $(curl -sf https://api.github.com/repos/wifihaven/wifihaven/releases/latest \
    | jsonfilter -e '@.assets[*].browser_download_url' \
    | grep -E '\.ipk$' | head -n1)

# OpenWRT 24.10+/SNAPSHOT (apk):
curl -fsSL -o /tmp/wifihaven.apk \
  $(curl -sf https://api.github.com/repos/wifihaven/wifihaven/releases/latest \
    | jsonfilter -e '@.assets[*].browser_download_url' \
    | grep -E '\.apk$' | head -n1)
```

### M2. Install

```sh
# OpenWRT 23.05.x (opkg):
opkg update
opkg install /tmp/wifihaven.ipk

# OpenWRT 24.10+/SNAPSHOT (apk):
apk add --allow-untrusted /tmp/wifihaven.apk
```

Either manager resolves and installs `lua`, `luci-lib-jsonc`,
`conntrack-tools`, and `curl`. The post-install hook enables the
`wifihaven` procd service for autostart but does **not** start it yet —
enrollment must complete first.

### M3. Configure the API URL and LAN prefix

```sh
uci set wifihaven.@wifihaven[0].api_url='https://api.example.com'
# Default lan_prefix is '192.168.1.' — override if your LAN is elsewhere.
# Example: LAN on 10.0.0.0/24
uci set wifihaven.@wifihaven[0].lan_prefix='10.0.0.'
uci commit wifihaven
```

> **Important — read this even if your LAN looks normal.**
> The agent uses `lan_prefix` (a literal string prefix, with a trailing dot)
> to identify which side of a connection is on the LAN when attributing
> flows to a device. **If your LAN is not on `192.168.1.0/24` and you don't
> override this, the agent will mis-attribute every flow.**

### M4. Exchange the enrollment token for a router token

```sh
curl -s -X POST https://api.example.com/api/router/register \
  -H 'Content-Type: application/json' \
  -d '{
    "enrollmentToken": "et_5f3c9b…",
    "platformVersion": "23.05.5",
    "agentVersion":    "0.1.0"
  }'
# → {"routerId":"9c1f2e8a-…","routerToken":"rt_a7d12b…"}
```

The endpoint is unauthenticated — the enrollment token in the body is the
auth.

### M5. Persist credentials and start the agent

```sh
uci set wifihaven.@wifihaven[0].router_id='9c1f2e8a-…'
uci set wifihaven.@wifihaven[0].router_token='rt_a7d12b…'
uci commit wifihaven
/etc/init.d/wifihaven start
```

### M6. Set up the local block page

When the agent blocks an HTTP request, it DNATs port 80 to `127.0.0.1:8081`.
A local `uhttpd` instance serves `/www/wifihaven/block.html`, which the
agent installs. The block page redirects the browser to the API's
`/blocked?host=…&reason=…` endpoint.

Add a `uhttpd` listener on `127.0.0.1:8081` (the main `uhttpd` keeps
serving LuCI on its existing ports):

```sh
uci add uhttpd uhttpd
uci set uhttpd.@uhttpd[-1].listen_http='127.0.0.1:8081'
uci set uhttpd.@uhttpd[-1].home='/www'
uci commit uhttpd
/etc/init.d/uhttpd reload
```

HTTPS to blocked hosts intentionally times out — TLS interception would
require installing a custom CA on every client, which is not practical.

Then jump to §3 to verify.

## Reference

- [`openwrt/install.sh`](../openwrt/install.sh) — the install script
  source; read this if you want to know exactly what `curl | sh` will do.
- [`openwrt/README.md`](../openwrt/README.md) — package layout, build, and
  developer-facing details.
- [`docs/architecture.md`](architecture.md) §7 — full design of the OpenWRT
  agent.
- [`docs/deploy.md`](deploy.md) — overall CD pipeline for both deployment
  targets.
