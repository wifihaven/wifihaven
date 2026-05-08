# familydns OpenWrt package

OpenWrt 23.x agent for FamilyDNS. Enforces per-device DNS filtering via dnsmasq + nftables and reports traffic to the FamilyDNS API.

## Package contents

```
openwrt/
├── Makefile                               opkg metadata (OpenWrt SDK)
├── files/
│   ├── etc/init.d/familydns               procd init script
│   ├── etc/config/familydns               UCI config (api_url, router_token, …)
│   ├── usr/sbin/familydns-agent           main daemon entry point (Lua)
│   └── usr/lib/familydns/
│       └── conntrack.lua                  conntrack new-flow watcher + event batcher
└── test/
    └── conntrack_spec.lua                 busted unit tests for conntrack.lua
```

`policy.lua`, `usage.lua`, and `render.lua` land in #72.

## Dependencies

Installed automatically by opkg when you install the package:

- `lua` — Lua 5.1 interpreter
- `luci-lib-jsonc` — provides `cjson` for JSON encoding
- `conntrack-tools` — provides `conntrack -E -e NEW`
- `curl` — used by the agent for HTTP POSTs

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

1. In the FamilyDNS admin UI → Routers → **Add router**. Copy the enrollment token.
2. On the router:

```sh
curl -s -X POST http://<api-host>:8080/api/router/register \
  -H 'Content-Type: application/json' \
  -d '{"enrollment_token":"et_...","router_name":"home-gw","openwrt_version":"23.05.3","agent_version":"0.1.0"}'
# → {"router_id":"...","router_token":"rt_..."}
```

3. Write the returned values to UCI:

```sh
uci set familydns.@familydns[0].api_url='http://<api-host>:8080'
uci set familydns.@familydns[0].router_id='<router_id>'
uci set familydns.@familydns[0].router_token='<router_token>'
uci commit familydns
/etc/init.d/familydns restart
```

## Running tests

Tests require [busted](https://olivinelabs.com/busted/) and `lua-cjson`:

```sh
luarocks install busted
luarocks install lua-cjson
busted openwrt/test/conntrack_spec.lua
```

## conntrack hook: design rationale

`conntrack -E -e NEW` is used rather than nftables `meta nftrace` because:

- Available on stock OpenWrt 23.x via the `conntrack-tools` package without enabling per-rule tracing.
- Produces line-oriented output that can be consumed with a simple `io.popen` loop in Lua.
- `meta nftrace` requires a matching `nftrace` rule on every chain and produces verbose output that is harder to parse safely.

Hostname attribution uses the `nft_sets` table populated by `render.lua` from dnsmasq `--ipset=` callbacks (§6.2 of `docs/architecture-openwrt.md`). Reverse DNS is intentionally avoided because CDN PTR records do not reflect what the user resolved.

Both allowed and blocked flows are reported so the admin UI shows a complete connection timeline, not just block events.
