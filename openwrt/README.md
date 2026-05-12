# familydns OpenWrt package

OpenWrt 23.x agent for FamilyDNS. Enforces per-device DNS filtering via
dnsmasq + nftables, accounts traffic per `(mac, hostname)`, and streams
connection events to the FamilyDNS API.

## Package layout

```
openwrt/
├── Makefile                               opkg metadata (OpenWrt SDK)
├── build-ipk.sh                           lightweight .ipk builder (no SDK needed)
├── files/
│   ├── etc/init.d/familydns               procd init script
│   ├── etc/config/familydns               UCI config (api_url, router_token, …)
│   ├── usr/sbin/familydns-agent           main daemon entry point (Lua)
│   └── usr/lib/lua/familydns/
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
| Policy | 60 s | `GET /api/router/policy?since=<etag>`; on 200 atomically rewrites `/tmp/dnsmasq.d/familydns.conf` and `/tmp/nftables.d/familydns.nft`, then reloads dnsmasq + nft |
| Usage | 5 min | Scrapes nftables counters, builds per-(mac, hostname) records, `POST /api/router/usage`, resets counters on success |
| Events | per-flow | Watches `conntrack -E -e NEW`; batches `connection_attempt` events to `POST /api/router/events` |

Policy decisions (drop, DNAT to block page) are enforced in-kernel by
nftables — no round-trip to the API per request.

## Dependencies

Installed automatically by opkg when you install the package:

- `lua` — Lua 5.1 interpreter
- `libuci-lua` — Lua bindings for UCI (`require("uci")`)
- `luci-lib-jsonc` — JSON encode/decode (`require("luci.jsonc")`)
- `conntrack-tools` — provides `conntrack -E -e NEW`
- `curl` — HTTP client used by the agent

The following must be present on the router (standard on OpenWrt 23.x):

| Package | Purpose |
|---------|---------|
| `dnsmasq-full` | DNS server with `--ipset=` and `--dhcp-script` support |
| `nftables` | Packet filter (policy rendering, traffic counters) |
| `uhttpd` | Local block-page server |

## Build

### Quick build (no SDK required)

Because the package is pure Lua (`PKGARCH:=all`), no cross-compilation is
needed. `build-ipk.sh` assembles the `.ipk` directly:

```sh
# From the repo root:
./openwrt/build-ipk.sh
# → openwrt/familydns_0.1.0-1_all.ipk

# Override version (e.g. when cutting a release):
PKG_VERSION=0.2.0 PKG_RELEASE=1 ./openwrt/build-ipk.sh
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

cp -r /path/to/familydns/openwrt package/familydns
make package/familydns/compile V=s
# .ipk appears in: bin/packages/x86_64/base/familydns_*.ipk
```

### CI / automated build

The GitHub Actions workflow `.github/workflows/openwrt-build.yml` runs on
every PR that touches `openwrt/` and on every `v*` tag push. On a tag it
creates a GitHub release and attaches the `.ipk` as a release artifact.

To cut a release:

```sh
git tag v0.2.0
git push origin v0.2.0
# CI builds familydns_0.2.0-1_all.ipk and attaches it to the release.
```

## Install / Enrollment

End users install via the one-shot script:

```sh
sh -c "$(uclient-fetch -qO - https://raw.githubusercontent.com/sameerparekh/familydns/main/openwrt/install.sh)"
```

The script source is [`install.sh`](install.sh); the full guide (with the
manual-fallback path for debugging) is in
[`docs/install-openwrt.md`](../docs/install-openwrt.md).

The script auto-detects the router's package manager (`opkg` on OpenWRT
23.05.x and earlier, `apk` on 24.10+/SNAPSHOT) and downloads the matching
release asset. Only `.ipk` is currently published; `.apk` support is tracked
in [#176](https://github.com/sameerparekh/familydns/issues/176).

### Uninstalling

To cleanly revert what `install.sh` did (stop and disable the service,
remove the package, drop the uhttpd block-page listener, wipe the
familydns UCI config including the bearer token):

```sh
sh -c "$(uclient-fetch -qO - https://raw.githubusercontent.com/sameerparekh/familydns/main/openwrt/uninstall.sh)"
```

Pass `--purge` to additionally remove `/usr/lib/familydns` and
`/usr/lib/lua/familydns` (manual-workaround leftovers from older e2e
shakeouts). The script is idempotent — re-running on an already-clean
router exits 0 with "nothing to do".

For developer flashing of a locally built `.ipk`:

```sh
scp openwrt/familydns_*.ipk root@192.168.1.1:/tmp/
ssh root@192.168.1.1 opkg install /tmp/familydns_*.ipk
```

opkg installs all files and runs the `postinst` script (which enables the
procd service), but does **not** start the daemon yet — enrollment must
happen first. See the install guide above for the enrollment flow.

## Block-page redirect

Blocked HTTP/80 traffic is DNAT'd to `127.0.0.1:8081` (uhttpd).
uhttpd serves `/www/familydns/block.html`, which uses JavaScript to
redirect the browser to `http://<api>/blocked?host=…&reason=…`.

HTTPS to blocked hosts times out intentionally — intercepting TLS without
installing a custom CA on every client device is not practical.

Configure uhttpd to listen on `127.0.0.1:8081`:

```sh
uci add uhttpd uhttpd
uci set uhttpd.@uhttpd[-1].listen_http='127.0.0.1:8081'
uci set uhttpd.@uhttpd[-1].home='/www'
uci commit uhttpd
/etc/init.d/uhttpd reload
```

## Verify

Confirm the agent is running and talking to the API:

```sh
# Is the process up?
ps | grep familydns-agent

# Tail the agent log (procd routes stderr to the system log):
logread -f | grep familydns

# Expected on a healthy start:
#   [familydns] starting conntrack watcher
#   [familydns] flushed N events to /api/router/events

# Show current UCI config:
uci show familydns
```

The FamilyDNS admin UI → Routers → `<router name>` shows `last_seen_at`;
it should update every ~60 s once the policy timer is running.

If the agent refuses to start, the most common cause is a missing or empty
`router_token`. Check with `uci get familydns.@familydns[0].router_token`.

## Update

### Auto-update (default)

The package installs `/usr/sbin/familydns-update` and a cron entry that runs
it once a day at 04:00 router-local time:

```
0 4 * * * /usr/sbin/familydns-update
```

Each run hits the GitHub Releases API for the `latest` release, parses the
`.ipk` asset version, and only invokes `opkg install --force-reinstall` when
the released version is strictly newer than the installed one (verified via
`opkg compare-versions`). All output goes to syslog under tag `familydns`
(`logread | grep familydns`). `/etc/config/familydns` is declared as a
conffile so the router token survives the upgrade — no re-enrollment.

On OpenWRT 24.10+/SNAPSHOT (apk-only systems) the script exits silently;
the parallel `.apk` track is in [#176](https://github.com/sameerparekh/familydns/issues/176).

To force an update immediately:

```sh
/usr/sbin/familydns-update
```

To disable auto-updates (e.g. on a pinned router), edit the cron table and
delete the `familydns-update` line:

```sh
crontab -e
# remove the "0 4 * * * /usr/sbin/familydns-update" line, save, exit
/etc/init.d/cron restart
```

### Manual update

```sh
# Build the new .ipk:
PKG_VERSION=0.2.0 ./openwrt/build-ipk.sh

# Copy and upgrade (--force-reinstall preserves /etc/config/familydns):
scp openwrt/familydns_0.2.0-1_all.ipk root@192.168.1.1:/tmp/
ssh root@192.168.1.1 'opkg install --force-reinstall /tmp/familydns_0.2.0-1_all.ipk'
```

opkg preserves `/etc/config/familydns` across upgrades. The bearer token
and router ID survive unchanged; no re-enrollment is needed.

### Via CI release

1. Push a `v*` tag — CI builds the `.ipk` and attaches it to the GitHub release.
2. Download the `.ipk` from the release page.
3. `scp` + `opkg install --force-reinstall` as above.

## Rollback

If the new agent misbehaves:

```sh
# Stop immediately without waiting for procd to respawn it:
/etc/init.d/familydns stop
/etc/init.d/familydns disable

# Reinstall the previous .ipk if you kept it:
scp familydns_0.1.0-1_all.ipk root@192.168.1.1:/tmp/
ssh root@192.168.1.1 'opkg install --force-reinstall /tmp/familydns_0.1.0-1_all.ipk'
/etc/init.d/familydns enable
/etc/init.d/familydns start
```

While the agent is stopped, dnsmasq/nftables rules from the last policy
apply remain in place until a reboot or you clear them manually:

```sh
# Remove dnsmasq fragment and reload:
rm -f /tmp/dnsmasq.d/familydns.conf
/etc/init.d/dnsmasq restart

# Remove nftables fragment and flush:
rm -f /tmp/nftables.d/familydns.nft
nft flush ruleset
```

The API config (`/etc/config/familydns`) is preserved; re-enabling and
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
cd openwrt && LUA_PATH="./files/usr/lib/lua/?.lua;./files/usr/lib/lua/familydns/?.lua;;" \
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

Hostname attribution uses the `nft_sets` table populated by `render.lua`
from dnsmasq `--ipset=` callbacks. Reverse DNS is intentionally avoided
because CDN PTR records do not reflect what the user resolved.

Both allowed and blocked flows are reported so the admin UI shows a
complete connection timeline, not just block events.

## Architecture reference

See [`docs/architecture.md`](../docs/architecture.md) for the full design —
especially §7 (OpenWRT agent design), §7.2 (hostname attribution via dnsmasq
`--ipset=`), and §7.6 (block-page redirect flow).
