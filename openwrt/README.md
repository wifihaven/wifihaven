# familydns OpenWrt package

OpenWrt 23.x agent for FamilyDNS. Enforces per-device DNS filtering via
dnsmasq + nftables and reports traffic to the FamilyDNS API.

## Package layout

```
openwrt/
├── Makefile                               opkg metadata (OpenWrt SDK)
├── files/
│   ├── etc/init.d/familydns               procd init script
│   ├── etc/config/familydns               UCI config (api_url, router_token, …)
│   ├── usr/sbin/familydns-agent           main daemon entry point (Lua)
│   └── usr/lib/familydns/
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

Installed automatically by opkg:

- `lua` — Lua 5.1 interpreter
- `luci-lib-jsonc` — provides `cjson` for JSON encoding
- `conntrack-tools` — provides `conntrack -E -e NEW`
- `curl` — HTTP client used by the agent

## Build (OpenWrt SDK)

```sh
# From inside an OpenWrt SDK checkout
cp -r /path/to/familydns/openwrt package/familydns
make package/familydns/compile V=s
# .ipk appears in bin/packages/<arch>/base/
```

## Flash

```sh
scp bin/packages/.../familydns_*.ipk root@192.168.1.1:/tmp/
ssh root@192.168.1.1 opkg install /tmp/familydns_*.ipk
```

## Enrollment

### 1. Create a router record in the admin UI

Go to the FamilyDNS admin UI → **Routers → Add router**. Copy the
one-time enrollment token (looks like `et_5f3c9b…`).

### 2. Exchange the enrollment token for a router token

Run this from the router (or any host that can reach the API):

```sh
curl -s -X POST http://<api-host>:8080/api/router/register \
  -H 'Content-Type: application/json' \
  -d '{
    "enrollment_token": "et_…",
    "router_name":      "home-gw",
    "openwrt_version":  "23.05.3",
    "agent_version":    "0.1.0"
  }'
# → {"router_id":"9c1f2e8a-…","router_token":"rt_a7d12b…"}
```

### 3. Write the values into UCI config and start the agent

```sh
uci set familydns.@familydns[0].api_url='http://<api-host>:8080'
uci set familydns.@familydns[0].router_id='<router_id>'
uci set familydns.@familydns[0].router_token='<router_token>'
uci commit familydns
/etc/init.d/familydns restart
```

The init script rewrites `FAMILYDNS_API_URL` in `/www/familydns/block.html`
with the real `api_url` value on first start.

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
cd openwrt && LUA_PATH="./files/usr/lib/?.lua;./files/usr/lib/familydns/?.lua;;" \
  busted test/render_spec.lua
```

## Architecture reference

See [`docs/architecture-openwrt.md`](../docs/architecture-openwrt.md) for
the full design — especially §6.2 (hostname attribution via dnsmasq
`--ipset=`) and §6.6 (block-page redirect flow).
