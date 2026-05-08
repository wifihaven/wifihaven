# familydns OpenWrt package

OpenWrt 23.x agent for FamilyDNS. Enforces per-device DNS filtering via
dnsmasq + nftables, accounts traffic per `(mac, hostname)`, and streams
connection events to the FamilyDNS API.

## Package contents

```
openwrt/
├── Makefile                               opkg metadata (OpenWrt SDK)
├── build-ipk.sh                           lightweight .ipk builder (no SDK needed)
├── files/
│   ├── etc/init.d/familydns               procd init script
│   ├── etc/config/familydns               UCI config (api_url, router_token, …)
│   ├── usr/sbin/familydns-agent           main daemon entry point (Lua)
│   └── usr/lib/familydns/
│       ├── conntrack.lua                  conntrack new-flow watcher + event batcher
│       ├── policy.lua                     snapshot fetcher, atomic apply  [pending #72]
│       ├── usage.lua                      nftables counter scraper, reporter  [pending #72]
│       └── render.lua                     writes dnsmasq + nft fragments  [pending #72]
└── test/
    └── conntrack_spec.lua                 busted unit tests for conntrack.lua
```

`policy.lua`, `usage.lua`, and `render.lua` land in #72. Deploy steps that
depend on those modules are marked **[pending #72]**.

## Dependencies

Installed automatically by opkg when you install the package:

| Package | Purpose |
|---------|---------|
| `lua` | Lua 5.1 interpreter |
| `luci-lib-jsonc` | `cjson` for JSON encoding |
| `conntrack-tools` | `conntrack -E -e NEW` for event watching |
| `curl` | HTTP POSTs to the API |

The following must be present on the router (standard on OpenWrt 23.x):

| Package | Purpose |
|---------|---------|
| `dnsmasq-full` | DNS server with `--ipset=` and `--dhcp-script` support |
| `nftables` | Packet filter (policy rendering, traffic counters) **[pending #72]** |
| `uhttpd` | Local block-page server **[pending #72]** |

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

## Flash / Install

```sh
# Copy the .ipk to the router and install:
scp openwrt/familydns_*.ipk root@192.168.1.1:/tmp/
ssh root@192.168.1.1 opkg install /tmp/familydns_*.ipk
```

opkg installs all files, runs the `postinst` script (which enables the
procd service), but does **not** start the daemon yet — enrollment must
happen first (see next section).

## Enroll

Enrollment exchanges a one-time token for a long-lived bearer token. Do
this once per router.

### Step 1: generate an enrollment token

In the FamilyDNS admin UI → **Routers** → **Add router**. Give the router
a name (e.g. `home-gw`) and copy the enrollment token (`et_…`).

### Step 2: register the router with the API

Run this from the router shell (or any host that can reach the API):

```sh
curl -s -X POST http://<api-host>:8080/api/router/register \
  -H 'Content-Type: application/json' \
  -d '{
    "enrollmentToken": "et_XXXX",
    "routerName":      "home-gw",
    "platformVersion": "23.05.5",
    "agentVersion":    "0.1.0"
  }'
# → {"routerId":"9c1f2e8a-…","routerToken":"rt_…"}
```

The enrollment token is single-use; it is invalidated on success.

### Step 3: write the returned values to UCI

```sh
uci set familydns.@familydns[0].api_url='http://<api-host>:8080'
uci set familydns.@familydns[0].router_id='<routerId from response>'
uci set familydns.@familydns[0].router_token='<routerToken from response>'
uci commit familydns
```

Adjust `lan_prefix` if your LAN subnet is not `192.168.1.`:

```sh
uci set familydns.@familydns[0].lan_prefix='10.0.0.'
uci commit familydns
```

### Step 4: start the daemon

```sh
/etc/init.d/familydns start
```

The agent is already enabled for autostart by the `postinst` script, so
it will also start on the next reboot.

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

# Check policy poll is working — the API admin UI should show
# this router's last_seen_at updating every ~60 s under
# Routers → <router name>.
```

If the agent refuses to start, the most common cause is a missing or empty
`router_token`. Check with `uci get familydns.@familydns[0].router_token`.

## Update

### Manual update

```sh
# On your dev machine — build the new .ipk:
PKG_VERSION=0.2.0 ./openwrt/build-ipk.sh

# Copy and upgrade (--force-reinstall overwrites config-protected files):
scp openwrt/familydns_0.2.0-1_all.ipk root@192.168.1.1:/tmp/
ssh root@192.168.1.1 'opkg install --force-reinstall /tmp/familydns_0.2.0-1_all.ipk'
```

opkg preserves `/etc/config/familydns` across upgrades (the file is
declared with `INSTALL_CONF`, which marks it as a config file in the
package control metadata). The bearer token and router ID survive the
upgrade unchanged; no re-enrollment is needed.

### Automated update via CI release

1. Push a `v*` tag — CI builds the `.ipk` and attaches it to the GitHub release.
2. Download the `.ipk` from the release page (or `gh release download`).
3. `scp` + `opkg install --force-reinstall` as above.

A self-update mechanism (the agent pulling its own upgrade) is out of scope
for v1 but can be layered on later using `opkg` from within the agent.

## Rollback

If the new agent misbehaves:

```sh
# Stop immediately without waiting for procd to respawn it:
/etc/init.d/familydns stop
/etc/init.d/familydns disable

# (Optional) reinstall the previous .ipk if you kept it:
scp familydns_0.1.0-1_all.ipk root@192.168.1.1:/tmp/
ssh root@192.168.1.1 'opkg install --force-reinstall /tmp/familydns_0.1.0-1_all.ipk'
/etc/init.d/familydns enable
/etc/init.d/familydns start
```

While the agent is stopped, DNS and packet-filter rules applied by the
previous run remain in place until the router reboots or you manually
clear them:

```sh
# Remove dnsmasq fragment and reload:  [pending #72 — render.lua]
rm -f /tmp/dnsmasq.d/familydns.conf
/etc/init.d/dnsmasq restart

# Remove nftables fragment and reload:  [pending #72 — render.lua]
rm -f /tmp/nftables.d/familydns.nft
nft flush ruleset
```

The API config (`/etc/config/familydns`) is preserved; re-enabling and
starting the service resumes operation without re-enrollment.

## Running tests

Tests require [busted](https://olivinelabs.com/busted/) and `lua-cjson`
(Lua 5.1 to match OpenWrt 23.x):

```sh
luarocks install busted
luarocks install lua-cjson
busted openwrt/test/conntrack_spec.lua
```

CI runs these automatically in the `lua-tests` job in `.github/workflows/ci.yml`.

## conntrack hook: design rationale

`conntrack -E -e NEW` is used rather than nftables `meta nftrace` because:

- Available on stock OpenWrt 23.x via the `conntrack-tools` package without
  enabling per-rule tracing.
- Produces line-oriented output that can be consumed with a simple `io.popen`
  loop in Lua.
- `meta nftrace` requires a matching `nftrace` rule on every chain and
  produces verbose output that is harder to parse safely.

Hostname attribution uses the `nft_sets` table populated by `render.lua`
from dnsmasq `--ipset=` callbacks (§6.2 of `docs/architecture.md`).
Reverse DNS is intentionally avoided because CDN PTR records do not reflect
what the user resolved.

Both allowed and blocked flows are reported so the admin UI shows a complete
connection timeline, not just block events.
