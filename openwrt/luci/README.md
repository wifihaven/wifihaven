# luci-app-wifihaven

LuCI web UI for the WifiHaven router agent. Adds an
**Administration → Services → WifiHaven** page that:

- binds a form to `/etc/config/wifihaven` (cadence + connection settings —
  see [#748](https://github.com/wifihaven/wifihaven/issues/748)),
- shows a read-only status overview (stub in this scaffold).

## Build

Pure Lua / JSON, no cross-compilation:

```sh
./openwrt/luci/build-ipk.sh
```

Output: `openwrt/luci/luci-app-wifihaven_<version>-<release>_all.ipk`.

Install on a router:

```sh
scp openwrt/luci/luci-app-wifihaven_*.ipk root@<router>:/tmp/
ssh root@<router> 'opkg install /tmp/luci-app-wifihaven_*.ipk'
```

The base `wifihaven` package and `luci-base` must already be installed.

## Status panel

The Status tab is a stub. Wiring it to real signals (last successful policy
poll / usage POST, retry-queue depth) is a follow-up — the agent does not
currently surface those over a stable surface that LuCI can read.

## Layout

```
luci/
  Makefile                                          OpenWRT SDK build
  build-ipk.sh                                      SDK-less builder
  luasrc/
    controller/wifihaven.lua                        dispatcher
    model/cbi/wifihaven/settings.lua                CBI form
    view/wifihaven/status.htm                       status template
  root/
    usr/share/rpcd/acl.d/luci-app-wifihaven.json    ACL grant
    usr/share/luci/menu.d/luci-app-wifihaven.json   JSON menu (LuCI 22+)
```
