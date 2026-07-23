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

> **Supported firmware baseline.** The agent is currently validated only on
> **flashed vanilla OpenWRT** (the images from
> [firmware-selector.openwrt.org](https://firmware-selector.openwrt.org/)).
> Vendor-forked firmware — notably the **GL.iNet stock firmware** those routers
> ship with out of the box — is **not yet a verified target**
> ([#2304](https://github.com/wifihaven/wifihaven/issues/2304)). The install
> script only checks for `apk`-or-`opkg` + `uci` + `jsonfilter`, so it may
> *start* on stock GL.iNet firmware, but that path is unverified: GL.iNet's own
> firewall/UI layer and dnsmasq management have not been tested against the
> agent's nftables and nftset config. Flash vanilla OpenWRT first — per-router
> flash guides: [`install-flint2.md`](install-flint2.md) (Flint 2 / GL-MT6000),
> [`install-flint.md`](install-flint.md) (Flint / GL-AX1800), and
> [`install-wax206.md`](install-wax206.md) (Netgear WAX206) — that is the
> supported path.

- A router running **OpenWRT 23.05.x** (opkg / `.ipk`) **or OpenWRT 24.10+ /
  SNAPSHOT** (apk / `.apk`). CI builds and attaches both artifacts to every
  release.
- Internet access from the router.
- Root SSH access to the router.
- A WifiHaven API server already deployed and reachable from the router. See
  [`install-api.md`](install-api.md) if you need to set one up first.
- A one-time enrollment token generated in the admin UI. The `/routers?add=1`
  deep link opens the add-router dialog directly (cloud:
  `https://app.wifihaven.net/routers?add=1`; self-hosted: `/routers?add=1` on
  your own dashboard host) — enter a name, click **Generate Token**, and copy
  the token (shown only once). The install script prints this same deep link at
  the token prompt (#2235).
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
| API server URL | Defaults to `https://api.wifihaven.net` (production cloud API). Press enter to accept for the main household router; override for on-prem or dev installs (e.g. `http://192.168.1.1:8080`). No trailing slash needed; the script trims it. |
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
6. Optionally (y/N prompt, default No) sets `option force '1'` on your
   dhcp sections so dnsmasq restarts skip OpenWrt's ~3.5 s-per-section
   rogue-DHCP probe (measured 3.53 s → 0.34 s). `/etc/config/dhcp` is your
   file — the script never sets this without consent, and saying yes is
   only safe once this router is your network's sole DHCP server. Set
   `WIFIHAVEN_NONINTERACTIVE=1` in the environment to suppress the prompt
   (nothing is set). Details, measurements, and the revert command:
   [`router-tuning.md`](router-tuning.md#dnsmasq-restart-latency--option-force-1-2231)
   ([#2231](https://github.com/wifihaven/wifihaven/issues/2231)).

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
fresh `last_seen_at`, updating roughly every 60 seconds (the usage-report
cadence; the policy poll itself is faster, ~5 s).

For a deeper end-to-end check that mirrors the VM e2e suite — enrollment,
allowed browsing, blocking, pause, schedule, time limits, usage reporting,
unknown-device autocreation — follow [docs/manual-qa.md](manual-qa.md)
with one connected device.

### Blocking isn't instant — set expectations first

The first time you enable a block, expect a short **warm-up** rather than an
instant cutoff. Blocking is a connection-layer drop keyed to the IPs a device
resolves *through the router's DNS*, so a new block takes effect only after the
policy reaches the router (seconds) **and** the device does a fresh DNS lookup
for the host — until then, cached DNS answers and open connections keep working.
To test a block immediately, flush the device's DNS cache and reload the site.

Several device-side settings (a VPN / Cloudflare WARP full tunnel, or "Secure
DNS" / DoH / DoT / iCloud Private Relay) route around the router entirely and
defeat host-based filtering by design. If a block "doesn't work," check for
these before assuming a bug.

Full details, verified against the agent code, are in
[docs/enforcement-expectations.md](enforcement-expectations.md) — read it before
debugging a block that seems ineffective.

### Emergency: turn off all blocking (escape hatch)

If blocking ever breaks the internet, a policy is wrong, or the WifiHaven server
is down and you just need the internet back, you can **disable all enforcement on
this router**. It works **even when the server is unreachable** (the switch is
local to the router) and takes effect within a few seconds — no reboot needed:

```sh
wifihaven-disable     # turn off ALL blocking; every device gets normal internet
wifihaven-enable      # restore normal blocking
```

Or in LuCI: **Services → WifiHaven → Settings → "Disable all WifiHaven
enforcement"**, then **Save & Apply**. This is a reversible pause — it does not
uninstall anything or lose your settings.

There is also a per-household off switch in the web dashboard
([#2382](https://github.com/wifihaven/wifihaven/issues/2382)), which is easier but
needs the server to be up. The router switch above is the **offline** fallback.
Full details: [docs/escape-hatch.md](escape-hatch.md).

## 4. Enrolling against the cloud API

This section is for the **new main-house router** being brought up against the
production cloud API (`https://api.wifihaven.net`) for the first time. It is
not a migration or rollover guide — the existing OpenWRT box stays put as a
dev router pointed at the local API (see [§4.1](#41-dev-vs-prod-router-pattern)
and [#584](https://github.com/wifihaven/wifihaven/issues/584)).

The enrollment flow is the same as the rest of this doc; the only difference
is which `api_url` you point the router at and where you generate the
enrollment token. The one-shot script in [§2](#2-install-with-the-one-shot-script-recommended)
handles this if you answer the prompts with the cloud values below; the
explicit steps here are useful if you'd rather run the manual UCI path or
script the install into your own provisioning system.

### Prereqs

- An OpenWRT 23.05.x / 24.10+ router with the WifiHaven `.ipk` / `.apk`
  installed (see [§M1](#m1-download-the-matching-package) and
  [§M2](#m2-install)). The agent does **not** need to be started yet —
  enrollment runs first.
- Admin access to the cloud SPA at `https://app.wifihaven.net`. If the cloud
  side isn't deployed yet, see [`deploy-cloud.md`](deploy-cloud.md).

### Steps

1. **SSH into the new router as root.**

2. **Point the agent at the cloud API and set the LAN prefix.** Leave
   `router_id` and `router_token` empty for now — they'll come from the
   admin UI in the next step.

   ```sh
   uci set wifihaven.@wifihaven[0].api_url='https://api.wifihaven.net'
   # Override only if the new router's LAN isn't 192.168.1.0/24:
   # uci set wifihaven.@wifihaven[0].lan_prefix='10.0.0.'
   uci commit wifihaven
   ```

   See the warning in [§M3](#m3-configure-the-api-url-and-lan-prefix) before
   skipping the `lan_prefix` override — a wrong value silently mis-attributes
   every flow.

3. **Generate an enrollment token in the admin UI.** Open
   `https://app.wifihaven.net` → **Routers → Add router**. Enter a display name
   for the router (e.g. `main-house`) and submit. The UI returns a one-time
   `enrollmentToken` (`et_…`); copy it.

4. **Exchange the enrollment token for router credentials.** On the router:

   ```sh
   curl -s -X POST https://api.wifihaven.net/api/router/register \
     -H 'Content-Type: application/json' \
     -d '{
       "enrollmentToken": "et_<from-admin-ui>",
       "platformVersion": "23.05.5",
       "agentVersion":    "0.1.0"
     }'
   # → {"routerId":"<id>","routerToken":"rt_<token>"}
   ```

5. **Persist credentials and start the agent.**

   ```sh
   uci set wifihaven.@wifihaven[0].router_id='<id>'
   uci set wifihaven.@wifihaven[0].router_token='rt_<token>'
   uci commit wifihaven
   /etc/init.d/wifihaven enable
   /etc/init.d/wifihaven start
   ```

6. **Verify.** Tail the log:

   ```sh
   logread -f | grep wifihaven
   ```

   You should see `[wifihaven] policy snapshot fetched, etag=…` within ~60s.
   Then check `https://app.wifihaven.net` → **Routers** — the new router should
   appear with a recent `last_seen_at`. If you've never set up the local
   block page on this router, also walk [§M6](#m6-set-up-the-local-block-page).

### 4.1 Dev vs prod router pattern

Two routers in the same operator's environment will run with different
`api_url` values; this is by design and matches the topology in
[#584](https://github.com/wifihaven/wifihaven/issues/584):

| Role | `api_url` | Notes |
|---|---|---|
| Prod router (new main-house box) | `https://api.wifihaven.net` | Cloud-hosted API on Render; the SPA at `https://app.wifihaven.net` is what household admins use. |
| Dev router (existing OpenWRT box) | `http://192.168.10.43:8080` | Points at the on-prem dev API behind the prod router. Used for shakeout and integration testing. |

Each router enrolls independently against its own API — there is no shared
state between the two installs, and the `routerToken` issued by one API
is meaningless to the other.

The UCI default at
[`openwrt/files/etc/config/wifihaven`](../openwrt/files/etc/config/wifihaven)
ships with `api_url='http://192.168.1.1:8080'`. That is intentionally the
safe default for someone bringing up a fresh on-prem install on the LAN —
cloud users override it per the steps above.

### Common issues

- **TLS / certificate errors on `/api/router/register`.** Almost always
  router clock skew: a freshly flashed OpenWRT box can boot with
  `1970-01-01` and reject the cloud cert. Confirm the router has reached
  an NTP server (`date` should show the real date; check
  `/etc/init.d/sysntpd status`) and retry.
- **`register` returns 400 / "enrollment token expired or invalid".** The
  token is single-use and short-lived — generate a fresh one in the admin
  UI and re-run step 4.
- **Policy poll returns 401 in `logread`.** The persisted `router_id` /
  `router_token` doesn't match what the API has on file (typo, copied the
  wrong pair, or the cloud DB was reset). Generate a new enrollment token
  in the admin UI and re-run steps 3–5; the router will overwrite its
  stored credentials.

## 5. (Optional) Auto-update

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

We do **not** intercept TLS (that would need a custom CA on every client), so
there is no clean block *page* for HTTPS. The standard one-shot install also
adds a sibling `uhttpd` TLS listener on `127.0.0.1:8443` and DNATs blocked
port 443 to it with a self-signed cert
([#383](https://github.com/wifihaven/wifihaven/issues/383)): browsers then show
a certificate warning and, after clicking through, the block page, while apps
that pin certs simply see the TLS handshake fail. The manual steps above set up
only the HTTP listener; for the full block-page behavior use the one-shot
installer or run
[`setup-uhttpd-block-page.sh`](../openwrt/files/usr/lib/wifihaven/setup-uhttpd-block-page.sh).
See [docs/enforcement-expectations.md](enforcement-expectations.md) for what
HTTPS blocks look like in practice.

Then jump to §3 to verify.

## Reference

- [`openwrt/install.sh`](../openwrt/install.sh) — the install script
  source; read this if you want to know exactly what `curl | sh` will do.
- [`openwrt/README.md`](../openwrt/README.md) — package layout, build, and
  developer-facing details.
- [`docs/architecture.md`](architecture.md) §7 — full design of the OpenWRT
  agent.
- [`docs/enforcement-expectations.md`](enforcement-expectations.md) — how a
  block warms up, what HTTPS blocks look like, and what can bypass filtering.
- [`docs/disable-enforcement.md`](disable-enforcement.md) — the two **escape
  hatches** for turning off all blocking: the dashboard toggle (easy, needs the
  server up) and the on-router toggle (works offline). Read this so you know
  where the "off switch" is before you need it.
- [`docs/deploy.md`](deploy.md) — overall CD pipeline for both deployment
  targets.
