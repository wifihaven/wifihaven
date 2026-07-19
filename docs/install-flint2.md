# Installing WifiHaven on a GL.iNet Flint 2 (GL-MT6000)

Hardware-specific install path for the **GL.iNet Flint 2 (GL-MT6000)** — the
reference hardware for the production WifiHaven household router. This doc
covers everything you do to the box *before* the generic
[`install-openwrt.md`](install-openwrt.md) flow takes over: flashing OpenWRT,
baseline OpenWRT config, and the recovery path if the flash bricks.

If you're installing WifiHaven on different OpenWRT hardware, skip directly to
[`install-openwrt.md`](install-openwrt.md) — only the sections marked
"Hardware-specific" below apply to the Flint 2.

> **Flashing vanilla OpenWRT is the supported path — do not skip §1.** GL.iNet
> ships the Flint 2 with GL.iNet's own *forked* OpenWRT firmware, and the agent
> has **not been validated against that stock firmware**
> ([#2304](https://github.com/wifihaven/wifihaven/issues/2304)): GL.iNet's
> firewall/UI layer and dnsmasq management are untested against the agent's
> nftables and nftset config. This doc's §1 flashes the box to **vanilla
> OpenWRT** before installing WifiHaven — that is the validated configuration.
> Do not run the agent installer on the GL.iNet stock firmware until #2304
> closes.

## Why this hardware

- **Mediatek MT7986A (filogic)**, aarch64 cortex-a53 — plenty of CPU for line-rate
  filtering, accounting, and CAKE shaping at gigabit without offload.
- **Dual-band 802.11ax** (2.4GHz + 5GHz), four 1G LAN, one 2.5G WAN.
- USB-C power, USB-A for optional storage.
- Well-supported in mainline OpenWRT (25.12.x).

## Hardware orientation

Back panel, left to right:

| Port | Use |
|---|---|
| **WAN / 2.5G** (yellow, leftmost) | Uplink to your ISP modem (or to an upstream router for a parallel bring-up). |
| **LAN 1–4** | Household clients. Bridge to `br-lan` by default. |
| **USB-A** | Optional storage; not used by WifiHaven. |
| **USB-C** | Power. Not a data port on this hardware revision. |
| **Reset / mode button** | Pressed at boot to enter GL.iNet u-boot recovery (see [Recovery](#recovery-if-the-flash-bricks)). |

Power LED solid = booted. Power LED blinking during flash is normal — do not
power-cycle while it's blinking.

## 1. Flash OpenWRT

> **Cutover plan first.** Decide whether you're bringing the Flint 2 up in
> parallel with an existing household router or replacing it outright. Parallel
> keeps the household online during install (Flint 2's WAN goes into a spare
> LAN port on the existing router; its LAN becomes a separate subnet for test
> clients) and is the recommended path. Replace is faster but drops the whole
> household until install completes.

### 1.1 Get the image

Use the **sysupgrade** image (kernel + rootfs in one go), not initramfs.

Image used for the reference install (2026-05-20):

```
File:    openwrt-25.12.3-mediatek-filogic-glinet_gl-mt6000-squashfs-sysupgrade.bin
SHA256:  70650abdf83077f79d2acf81bad42f4e608d50782f645aed363eaf9b6a0f6a4a
Source:  https://firmware-selector.openwrt.org/ → search "GL.iNet GL-MT6000"
```

Always verify the published checksum on the firmware-selector page matches what
you downloaded **before** uploading to the router. A bad image on a Flint 2 is
recoverable but painful (see [Recovery](#recovery-if-the-flash-bricks)).

For a newer OpenWRT release, pick the latest 25.12.x or later sysupgrade for
`mediatek/filogic` / `glinet_gl-mt6000` from firmware-selector and verify the
checksum the same way.

### 1.2 Boot stock GL firmware and reach the admin UI

1. Power on the Flint 2 with WAN unplugged. Plug your laptop into one of the
   LAN ports (LAN 1–4, NOT WAN).
2. Wait ~60s for boot. Your laptop should get a DHCP lease from the GL.iNet
   stack on `192.168.8.0/24` (default GL subnet).
3. Open `http://192.168.8.1` in a browser. First-boot, GL prompts you to set
   an admin password. Set one — you'll only need it for the next 5 minutes.

> **Laptop-side gotcha.** If you're using a USB-ethernet dongle on a Mac, make
> sure macOS Internet Sharing is OFF for that interface — Sharing will assign
> the dongle a fixed `192.168.2.1` and prevent it from accepting a DHCP lease
> from the Flint 2. **System Settings → General → Sharing → Internet Sharing**
> off, then **System Settings → Network → USB Ethernet → Configure IPv4: Using
> DHCP**.

### 1.3 Upload sysupgrade

In the GL admin UI:

1. Navigate to firmware/upgrade.
2. Upload the sysupgrade image from §1.1.
3. **Uncheck any "keep settings" / "preserve config" option.** You want clean
   stock OpenWRT, not a GL-flavored install with leftover vendor config.
4. Confirm and flash. ~5–10 min including reboot. Do not power-cycle during
   this window.

### 1.4 Find the router post-flash

After the reboot the router is on stock OpenWRT defaults: **`192.168.1.1/24`**,
acting as DHCP server on its LAN, no SSH root password set.

1. Move your laptop's ethernet cable to a LAN port if it wasn't already.
2. DHCP-renew your laptop's interface (or unplug/replug). It should pick up a
   `192.168.1.x` lease.
3. Browse to `http://192.168.1.1` → LuCI. Set a root password on first login;
   save it to your password manager.
4. SSH in: `ssh root@192.168.1.1`.
5. Sanity-check the firmware:

   ```sh
   cat /etc/openwrt_release
   # Expect: DISTRIB_RELEASE='25.12.3', DISTRIB_TARGET='mediatek/filogic'
   apk update
   # Expect: a list of repos refreshed; if this hangs or 404s, fix WAN before continuing.
   ```

## 2. Baseline OpenWRT config (do this before installing WifiHaven)

These are not strictly required for WifiHaven to start, but they're things
you want on a long-running household router and are easiest to set now while
the box is fresh.

### 2.1 Wireless country code

OpenWRT defaults the wifi regulatory domain to `00` (world), which clamps TX
power and blocks some channels. Set to your country before bringing up the
radios:

```sh
uci set wireless.radio0.country='US'   # or your country code
uci set wireless.radio1.country='US'
uci commit wireless
wifi reload
```

Verify with `iw reg get` — it should show your country, not `00`.

### 2.2 Remove unused IPsec WAN-side rules

Stock OpenWRT firewall ships with `Allow-IPSec-ESP` and `Allow-ISAKMP` rules
opening WAN→LAN for IPsec passthrough. The household router almost certainly
isn't running IPsec; close them:

```sh
uci -q delete firewall.@rule[$(uci show firewall | awk -F'[][]' "/name='Allow-ISAKMP'/{print \$2; exit}")]
uci -q delete firewall.@rule[$(uci show firewall | awk -F'[][]' "/name='Allow-IPSec-ESP'/{print \$2; exit}")]
uci commit firewall
/etc/init.d/firewall restart
```

### 2.3 HW flow offload: leave disabled

The Flint 2 supports Mediatek WED (Wireless Ethernet Dispatch) HW flow offload,
and fw4's `flow_offloading` / `flow_offloading_hw` options expose it. **Do not
enable them for a WifiHaven router.** HW offload bypasses conntrack once a flow
is offloaded, and the agent's per-device accounting and forward-drop enforcement
relies on conntrack. See
[#703](https://github.com/wifihaven/wifihaven/issues/703) for the open
investigation into a supported configuration; until that closes, keep both off
(which is the stock default).

## 3. Recommended companion packages (optional)

These aren't required for WifiHaven, but make the Flint 2 a comfortable
household router. Install before or after the WifiHaven step — both orderings
work.

### 3.1 UPnP/IGD (miniupnpd-nftables)

For game consoles, video-calling apps, and BitTorrent clients that ask the
router to open ports for them.

```sh
apk add miniupnpd-nftables luci-app-upnp
```

**Use the `-nftables` variant, not `-iptables`.** OpenWRT 25.12 uses fw4
(nftables); the iptables variant requires fw3 shims and will fight with fw4.
WifiHaven also lives in nftables, so keeping the whole stack on one backend
avoids surprises. fw4 picks up miniupnpd-nftables's includes automatically —
you'll see lines like `[!] Automatically including
'/usr/share/nftables.d/chain-post/forward/20-miniupnpd.nft'` on the next
firewall restart.

### 3.2 SQM (CAKE) for bufferbloat

Highly recommended on residential cable/DSL connections where the upstream
upload is constrained — a single bulk upload from any device in the house can
otherwise spike latency for everyone.

On OpenWRT 25.12 (apk), `sqm-scripts`'s package metadata still references
legacy bare package names (`ip`, `iptables`, `tc`) that don't exist anymore;
install the providers first or it'll fail (tracked in
[openwrt/packages#29500](https://github.com/openwrt/packages/issues/29500)):

```sh
apk add ip-full iptables-nft tc-full kmod-sched-cake kmod-ifb iptables-mod-ipopt
apk add sqm-scripts luci-app-sqm
```

Configure under **LuCI → Network → SQM QoS**:

1. **Interface**: `eth1` (WAN device on Flint 2).
2. **Download / Upload rates (kbit/s)**: 85–90% of *measured* speeds — run
   [waveform.com/tools/bufferbloat](https://www.waveform.com/tools/bufferbloat)
   first with SQM disabled to get a baseline. Don't use the ISP-advertised
   numbers; use what the link actually delivers.
3. **Queueing discipline**: `cake`, script `piece_of_cake.qos`.
4. **Link layer**: `ethernet`, per-packet overhead `22` (typical DOCSIS cable).

Re-run the bufferbloat test after enabling — typical residential result is
grade A with idle-vs-loaded latency delta ≤15ms.

> **Don't confuse `kbit/s` with `Mbit/s` in the rate fields.** Entering `100`
> shapes you to 100 kbit/s, not 100 Mbit/s. Use the full integer (e.g. `850000`
> for 850 Mbit/s).

### 3.3 Local DNS names for static-IP devices

If you have devices on fixed IPs (NAS, Plex server, internal services) and
want them resolvable by name from the LAN, register them via dnsmasq:

```sh
uci add dhcp domain
uci set dhcp.@domain[-1].name='nas'
uci set dhcp.@domain[-1].ip='192.168.10.30'
uci commit dhcp
/etc/init.d/dnsmasq restart
```

Names resolve as `<name>.lan` (or just `<name>` from clients with `.lan` in
their DNS search domain — that's the default for clients DHCPing off this
router).

## 4. Install WifiHaven

Now follow **[`install-openwrt.md` §2](install-openwrt.md#2-install-with-the-one-shot-script-recommended)** for the actual agent install.

For a Flint 2 talking to the production cloud API, the prompt answers are:

- API server URL: `https://api.wifihaven.net` (the default)
- Enrollment token: the `et_…` value from `https://app.wifihaven.net → Routers → Add router`
- LAN prefix: auto-detected from `network.lan.ipaddr`; accept the default
  unless you've moved LAN off its first /24.

Verify per `install-openwrt.md §3`.

## 5. Known drift on OpenWRT 25.12.x (apk-based)

The published install path was developed primarily against opkg-based OpenWRT.
Items below have been observed on apk-based releases (24.10+, 25.12.x) and
have fixes in flight; check the linked issues before relying on them.

### 5.1 dnsmasq-full swap silently no-ops

The install script's `ensure_dnsmasq_full` function uses `apk list -I <pkg>`
to decide whether to install/swap `dnsmasq-full`. On apk-tools v3, that
command returns exit 0 even when the package isn't installed, so the function
returns early without swapping. The router ends up with basic `dnsmasq`,
which is compiled without `nftset` / `ipset` support — and **all hostname-based
blocking silently fails**.

Tracked + fixed in [#704](https://github.com/wifihaven/wifihaven/issues/704) /
[PR #707](https://github.com/wifihaven/wifihaven/pull/707). Until that merges
to `main`, apply the manual workaround after running install.sh:

```sh
apk del dnsmasq
apk add dnsmasq-full
/etc/init.d/dnsmasq restart
/etc/init.d/wifihaven restart
```

Confirm `dnsmasq --help | grep nftset` is non-empty afterwards.

### 5.2 sqm-scripts apk depends on legacy package names

Covered in [§3.2](#32-sqm-cake-for-bufferbloat). Tracked at
[openwrt/packages#29500](https://github.com/openwrt/packages/issues/29500).

### 5.3 Blocklist fetch failures log empty `status=`

Cosmetic during fresh installs — the seed blocklists in the prod DB are not
yet served (separately tracked in
[#706](https://github.com/wifihaven/wifihaven/issues/706)). The agent logs
`fetch failed for <name> (status=)` with an empty status field instead of
the actual HTTP code; tracked in
[#705](https://github.com/wifihaven/wifihaven/issues/705). Doesn't affect
enforcement — the `bl_*` sets just stay empty until the underlying issues
close.

## Recovery if the flash bricks

GL.iNet's u-boot has a built-in TFTP recovery mode. Walk through this BEFORE
you ever need it so the steps are familiar.

1. **Power the router off.**
2. **Hold the reset button**, then plug power in while still holding the
   button. Continue holding for ~10s — the LED will start flashing rapidly,
   indicating u-boot recovery mode.
3. Plug your laptop into a **LAN port** (not WAN).
4. Configure your laptop's ethernet to a static address on `192.168.1.0/24`
   (e.g. `192.168.1.2/24`, gateway `192.168.1.1`).
5. Browse to `http://192.168.1.1` — GL.iNet's recovery web UI loads.
6. Upload the **stock GL firmware** (NOT OpenWRT) from
   [dl.gl-inet.com/router/mt6000/](https://dl.gl-inet.com/router/mt6000/). The
   recovery image is typically a `.bin` named with a stock GL version
   suffix.
7. Wait for the flash to complete and the router to reboot. You're back on the
   GL stock firmware at `192.168.8.1`.
8. From here, restart at §1.3 to re-flash OpenWRT.

If u-boot web recovery doesn't respond, the next step is TFTP — the GL.iNet
wiki has the procedure (router boots into u-boot listening on `192.168.1.1`,
laptop runs a TFTP client and uploads the firmware). That path is involved
enough that it's out of scope for this doc; refer to the GL.iNet support page
for the GL-MT6000.

## References

- [`install-openwrt.md`](install-openwrt.md) — the canonical OpenWRT-side
  install once the box is flashed and on baseline OpenWRT.
- [`architecture.md`](architecture.md) — agent design.
- OpenWRT firmware selector: <https://firmware-selector.openwrt.org/>
- OpenWRT GL-MT6000 device page:
  <https://openwrt.org/toh/glinet/gl-mt6000>
- GL.iNet docs and stock firmware:
  <https://docs.gl-inet.com/router/en/4/specification/gl-mt6000/>
- [#660](https://github.com/wifihaven/wifihaven/issues/660) — issue this
  install doc closes.
