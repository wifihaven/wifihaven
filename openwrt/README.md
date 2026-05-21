# WifiHaven OpenWrt package

OpenWrt agent for WifiHaven. Supports both **OpenWRT 23.05.x** (opkg / `.ipk`)
and **OpenWRT 24.10+ / SNAPSHOT** (apk / `.apk`). Enforces per-device
connection-level filtering via **nftables** (forward-drop keyed on MAC +
destination ipset), accounts traffic per `(mac, hostname)`, and streams
connection events to the WifiHaven API. dnsmasq on the router resolves DNS
normally — it is **not** the enforcement plane; it is used for hostname
attribution (`--ipset=` callbacks). See
[`../docs/architecture.md` §0](../docs/architecture.md#0-enforcement-model).

Each release attaches both a `.ipk` and a `.apk` built from the same
`openwrt/files/` tree; the one-line installer auto-detects the router's
package manager and downloads the matching asset.

## Package layout

```
openwrt/
├── Makefile                               opkg metadata (OpenWrt SDK)
├── build-ipk.sh                           lightweight .ipk builder for opkg (23.05.x)
├── build-apk.sh                           .apk builder for apk-tools (24.10+/SNAPSHOT)
├── files/
│   ├── etc/init.d/wifihaven               procd init script
│   ├── etc/config/wifihaven               UCI config (api_url, router_token, …)
│   ├── usr/sbin/wifihaven-agent           main daemon entry point (Lua)
│   └── usr/lib/lua/wifihaven/
│       ├── conntrack.lua                  conntrack new-flow watcher + event batcher
│       ├── policy.lua                     snapshot fetcher + atomic config apply
│       ├── render.lua                     dnsmasq conf + nft fragment generator
│       └── usage.lua                      nftables counter scraper + usage reporter
└── test/
    ├── conntrack_spec.lua
    ├── render_spec.lua
    ├── policy_spec.lua
    ├── usage_spec.lua
    ├── init_spec.sh
    └── run_tests.sh
```

## Daemon behaviour

The agent runs three co-operative responsibilities in a single process:

| Timer | Interval | What it does |
|---|---|---|
| Policy | 60 s | `GET /api/router/policy?since=<etag>`; on 200 atomically rewrites `/tmp/dnsmasq.d/wifihaven.conf` and `/tmp/nftables.d/wifihaven.nft`, then reloads dnsmasq + nft |
| Usage | 60 s | Scrapes nftables counters, builds per-(mac, hostname) records, `POST /api/router/usage`, resets counters on success |
| Events | per-flow | Watches `conntrack -E -e NEW`; batches `connection_attempt` events to `POST /api/router/events` |

Policy decisions (drop, DNAT to block page) are enforced in-kernel by
nftables — no round-trip to the API per request.

## Dependencies

Installed automatically by opkg/apk when you install the package (same package
names on both managers):

- `lua` — Lua 5.1 interpreter
- `libuci-lua` — Lua bindings for UCI (`require("uci")`)
- `luci-lib-jsonc` — JSON encode/decode (`require("luci.jsonc")`)
- `conntrack-tools` — provides `conntrack -E -e NEW`
- `curl` — HTTP client used by the agent

The following must be present on the router (standard on both OpenWRT 23.05.x
and 24.10+/SNAPSHOT):

| Package | Purpose |
|---------|---------|
| `dnsmasq-full` | DNS server with `--ipset=` and `--dhcp-script` support |
| `nftables` | Packet filter (policy rendering, traffic counters) |
| `uhttpd` | Local block-page server |

## Build

### Quick build (no SDK required)

Because the package is pure Lua (`PKGARCH:=all`), no cross-compilation is
needed. Two builders assemble the package directly:

```sh
# .ipk for OpenWRT 23.05.x (opkg) — works on any host with ar+tar:
./openwrt/build-ipk.sh
# → openwrt/wifihaven_0.1.0-1_all.ipk

# .apk for OpenWRT 24.10+/SNAPSHOT (apk-tools v3) — Linux only; the script
# builds apk-tools from source the first time, then caches it:
./openwrt/build-apk.sh
# → openwrt/wifihaven_0.1.0-1_all.apk

# Override version (e.g. when cutting a release):
PKG_VERSION=0.2.0 PKG_RELEASE=1 ./openwrt/build-ipk.sh
PKG_VERSION=0.2.0 PKG_RELEASE=1 ./openwrt/build-apk.sh
```

### Full SDK build

Use this if you add any C extensions in the future:

```sh
# Obtain an OpenWrt SDK matching your router's target/arch:
#   https://downloads.openwrt.org/releases/23.05.5/targets/
# e.g. for x86-64:
wget https://downloads.openwrt.org/releases/23.05.5/targets/x86/64/openwrt-sdk-23.05.5-x86-64_gcc-12.3.0_musl.Linux-x86_64.tar.xz
tar xf openwrt-sdk-*.tar.xz
cd openwrt-sdk-*/

cp -r /path/to/wifihaven/openwrt package/wifihaven
make package/wifihaven/compile V=s
# .ipk appears in: bin/packages/x86_64/base/wifihaven_*.ipk
```

### CI / automated build

The GitHub Actions workflow `.github/workflows/openwrt-build.yml` runs on
every PR that touches `openwrt/` and on every `v*` tag push. On a tag it
creates a GitHub release and attaches both the `.ipk` and the `.apk` as
release artifacts.

To cut a release:

```sh
git tag v0.2.0
git push origin v0.2.0
# CI builds wifihaven_0.2.0-1_all.ipk and wifihaven_0.2.0-1_all.apk
# and attaches both to the release.
```

## Install / Enrollment

End users install via the one-shot script:

```sh
sh -c "$(uclient-fetch -qO - https://raw.githubusercontent.com/wifihaven/wifihaven/main/openwrt/install.sh)"
```

The script source is [`install.sh`](install.sh); the full guide (with the
manual-fallback path for debugging) is in
[`docs/install-openwrt.md`](../docs/install-openwrt.md).

The script auto-detects the router's package manager (`opkg` on OpenWRT
23.05.x and earlier, `apk` on 24.10+/SNAPSHOT) and downloads the matching
release asset.

### Uninstalling

To cleanly revert what `install.sh` did (stop and disable the service,
remove the package, drop the uhttpd block-page listener, wipe the
wifihaven UCI config including the bearer token):

```sh
sh -c "$(uclient-fetch -qO - https://raw.githubusercontent.com/wifihaven/wifihaven/main/openwrt/uninstall.sh)"
```

Pass `--purge` to additionally remove `/usr/lib/wifihaven` and
`/usr/lib/lua/wifihaven` (manual-workaround leftovers from older e2e
shakeouts). The script is idempotent — re-running on an already-clean
router exits 0 with "nothing to do".

### For developer flashing of a locally built package:

```sh
# OpenWRT 23.05.x (opkg):
scp openwrt/wifihaven_*.ipk root@192.168.1.1:/tmp/
ssh root@192.168.1.1 opkg install /tmp/wifihaven_*.ipk

# OpenWRT 24.10+/SNAPSHOT (apk):
scp openwrt/wifihaven_*.apk root@192.168.1.1:/tmp/
ssh root@192.168.1.1 apk add --allow-untrusted /tmp/wifihaven_*.apk
```

Both managers install all files and run the post-install hook (which enables
the procd service), but neither starts the daemon yet — enrollment must
happen first. See the install guide above for the enrollment flow.

## Block-page redirect

Blocked HTTP/80 traffic is DNAT'd to `127.0.0.1:8081` (uhttpd) via a
dedicated `nat hook prerouting` chain in `inet wifihaven`. uhttpd dispatches
every request to the lua handler at `/www/wifihaven/handler.lua`
(uhttpd-mod-lua). The handler resolves the client MAC from `/proc/net/arp`
using `REMOTE_ADDR`, looks up the per-MAC block reason that the agent
writes to `/var/run/wifihaven/blocked_reasons` after each policy apply,
and returns an HTML document that redirects the browser to
`http://<api>/blocked?host=…&reason=…&mac=…` — populated so the React
block page can render reason-specific copy (#437).

The pre-#437 implementation served a static `index.html`; it had no way to
know the client's MAC, so the reason on the API page always fell back to
the generic "blocked" copy.

HTTPS to blocked hosts times out intentionally — intercepting TLS without
installing a custom CA on every client device is not practical.

Configure uhttpd to listen on `127.0.0.1:8081` and dispatch to the handler:

```sh
uci add uhttpd uhttpd
uci set uhttpd.@uhttpd[-1].listen_http='127.0.0.1:8081'
uci set uhttpd.@uhttpd[-1].home='/www/wifihaven'
uci set uhttpd.@uhttpd[-1].lua_prefix='/'
uci set uhttpd.@uhttpd[-1].lua_handler='/www/wifihaven/handler.lua'
uci commit uhttpd
/etc/init.d/uhttpd reload
```

## Verify

Confirm the agent is running and talking to the API:

```sh
# Is the process up?
ps | grep wifihaven-agent

# Tail the agent log (procd routes stderr to the system log):
logread -f | grep wifihaven

# Expected on a healthy start:
#   [wifihaven] starting conntrack watcher
#   [wifihaven] flushed N events to /api/router/events

# Show current UCI config:
uci show wifihaven
```

The WifiHaven admin UI → Routers → `<router name>` shows `last_seen_at`;
it should update every ~60 s once the policy timer is running.

If the agent refuses to start, the most common cause is a missing or empty
`router_token`. Check with `uci get wifihaven.@wifihaven[0].router_token`.

## Update

### Auto-update (default)

The package installs `/usr/sbin/wifihaven-update` and a cron entry that runs
it once a day at 04:00 router-local time:

```
0 4 * * * /usr/sbin/wifihaven-update
```

Each run hits the GitHub Releases API for the `latest` release, parses the
`.ipk` asset version, and only invokes `opkg install --force-reinstall` when
the released version is strictly newer than the installed one (verified via
`opkg compare-versions`). All output goes to syslog under tag `wifihaven`
(`logread | grep wifihaven`). `/etc/config/wifihaven` is declared as a
conffile so the router token survives the upgrade — no re-enrollment.

On OpenWRT 24.10+/SNAPSHOT (apk-only systems) the script exits silently;
the parallel `.apk` track is in [#176](https://github.com/wifihaven/wifihaven/issues/176).

To force an update immediately:

```sh
/usr/sbin/wifihaven-update
```

To disable auto-updates (e.g. on a pinned router), edit the cron table and
delete the `wifihaven-update` line:

```sh
crontab -e
# remove the "0 4 * * * /usr/sbin/wifihaven-update" line, save, exit
/etc/init.d/cron restart
```

### Manual update

```sh
# OpenWRT 23.05.x (opkg) — build, copy, upgrade. --force-reinstall preserves
# /etc/config/wifihaven:
PKG_VERSION=0.2.0 ./openwrt/build-ipk.sh
scp openwrt/wifihaven_0.2.0-1_all.ipk root@192.168.1.1:/tmp/
ssh root@192.168.1.1 'opkg install --force-reinstall /tmp/wifihaven_0.2.0-1_all.ipk'

# OpenWRT 24.10+/SNAPSHOT (apk):
PKG_VERSION=0.2.0 ./openwrt/build-apk.sh
scp openwrt/wifihaven_0.2.0-1_all.apk root@192.168.1.1:/tmp/
ssh root@192.168.1.1 'apk add --allow-untrusted /tmp/wifihaven_0.2.0-1_all.apk'
```

Both managers preserve `/etc/config/wifihaven` across upgrades. The bearer
token and router ID survive unchanged; no re-enrollment is needed.

### Via CI release

1. Push a `v*` tag — CI builds both `.ipk` and `.apk` and attaches them to the GitHub release.
2. Download the asset matching your router's package manager from the release page.
3. `scp` + `opkg install --force-reinstall` (23.05.x) or `apk add --allow-untrusted` (24.10+) as above.

## Rollback

If the new agent misbehaves:

```sh
# Stop immediately without waiting for procd to respawn it:
/etc/init.d/wifihaven stop
/etc/init.d/wifihaven disable

# Reinstall the previous .ipk if you kept it:
scp wifihaven_0.1.0-1_all.ipk root@192.168.1.1:/tmp/
ssh root@192.168.1.1 'opkg install --force-reinstall /tmp/wifihaven_0.1.0-1_all.ipk'
/etc/init.d/wifihaven enable
/etc/init.d/wifihaven start
```

While the agent is stopped, dnsmasq/nftables rules from the last policy
apply remain in place until a reboot or you clear them manually:

```sh
# Remove dnsmasq fragment and reload:
rm -f /tmp/dnsmasq.d/wifihaven.conf
/etc/init.d/dnsmasq restart

# Remove nftables fragment and flush:
rm -f /tmp/nftables.d/wifihaven.nft
nft flush ruleset
```

The API config (`/etc/config/wifihaven`) is preserved; re-enabling and
starting the service resumes operation without re-enrollment.

## Running tests

Tests require [busted](https://olivinelabs.com/busted/) and `lua-cjson`.
Install with [LuaRocks](https://luarocks.org/):

```sh
luarocks install busted
luarocks install lua-cjson
```

Run all tests from the repo root:

```sh
cd openwrt && sh test/run_tests.sh
```

Or run a single spec:

```sh
cd openwrt && LUA_PATH="./files/usr/lib/lua/?.lua;./files/usr/lib/lua/wifihaven/?.lua;;" \
  busted test/render_spec.lua
```

CI runs Lua tests automatically in the `lua-tests` job in
`.github/workflows/ci.yml`.

## conntrack hook: design rationale

`conntrack -E -e NEW` is used rather than nftables `meta nftrace` because:

- Available on stock OpenWrt 23.x via the `conntrack-tools` package without
  enabling per-rule tracing.
- Produces line-oriented output consumable with a simple `io.popen` loop in Lua.
- `meta nftrace` requires a matching `nftrace` rule on every chain and
  produces verbose output that is harder to parse safely.

Hostname attribution uses the forward-lookup cache populated by the
`wifihaven-dns-tail` sidecar from the dnsmasq query log, with the
`--ipset=` callback path as a fallback for the site-limit ipsets. Reverse
DNS is intentionally avoided because CDN PTR records do not reflect what
the user resolved. See [`../docs/architecture.md` §7.2](../docs/architecture.md).

Both allowed and blocked flows are reported so the admin UI shows a
complete connection timeline, not just block events.

## Tuning

The cadence knobs in `/etc/config/wifihaven` (`policy_poll_interval`,
`usage_report_interval`, `activity_sample_int`, `event_batch_size`,
`event_flush_interval`) are documented with trade-offs and suggested
ranges in [`docs/router-tuning.md`](../docs/router-tuning.md).

## Architecture reference

See [`docs/architecture.md`](../docs/architecture.md) for the full design —
especially §0 (enforcement model: DNS never blocks, router is a dumb applier),
§7 (OpenWRT agent design), §7.2 (forward-lookup hostname attribution via
dns-tail), and §7.6 (block-page redirect flow).
