# Installing WifiHaven on a GL.iNet Flint (GL-AX1800)

Hardware-specific install path for the **GL.iNet Flint (GL-AX1800)** — the
smaller, Wi-Fi 6 sibling of the reference [Flint 2 / GL-MT6000](install-flint2.md).
This doc covers everything you do to the box *before* the generic
[`install-openwrt.md`](install-openwrt.md) flow takes over: flashing OpenWRT,
baseline OpenWRT config, and the recovery path if the flash bricks.

If you're installing WifiHaven on different OpenWRT hardware, skip directly to
[`install-openwrt.md`](install-openwrt.md) — only the sections marked
"Hardware-specific" below apply to the GL-AX1800.

> **Flashing vanilla OpenWRT is the supported path — do not skip §1.** GL.iNet
> ships the Flint with GL.iNet's own *forked* OpenWRT firmware, and the agent
> has **not been validated against that stock firmware**
> ([#2304](https://github.com/wifihaven/wifihaven/issues/2304),
> [#2363](https://github.com/wifihaven/wifihaven/issues/2363)): GL.iNet's
> firewall/UI layer and its 2021-vintage `opkg`/dnsmasq fork are untested
> against — and in [#2363](https://github.com/wifihaven/wifihaven/issues/2363)
> actively incompatible with — the agent's package format, nftables, and
> nftset config. This doc's §1 flashes the box to **vanilla OpenWRT** before
> installing WifiHaven — that is the validated configuration. Do not run the
> agent installer on the GL.iNet stock firmware.

> **The GL-AX1800 first flash is NOT the same as the Flint 2's.** The Flint 2
> takes a `sysupgrade` image straight from the GL admin UI. The GL-AX1800 boots
> from NAND with `gluebi`, so the **first** flash from GL stock must use a
> **factory** image (`squashfs-factory.bin` via the GL admin UI, or
> `squashfs-factory.ubi` via U-Boot web recovery) — a `sysupgrade` image will
> not migrate cleanly off the stock firmware. Only *after* you're on OpenWRT do
> you use `sysupgrade` images for future upgrades. See §1.1 and §1.3.

## Why this hardware

- **Qualcomm IPQ6000 (`qualcommax`/`ipq60xx`)**, quad-core ARM Cortex-A53
  @1.2GHz, aarch64 — enough CPU for line-rate filtering and per-device
  accounting at gigabit.
- **Dual-band 802.11ax** (AX1800: 574 Mbps 2.4GHz + 1201 Mbps 5GHz).
- **512 MB RAM**, **128 MB NAND** flash.
- Five **gigabit** Ethernet ports (1 WAN + 4 LAN) — note there is **no 2.5G
  port** on this model, unlike the Flint 2.
- USB 3.0 for optional storage; DC barrel-jack power.
- Supported in mainline OpenWRT since 25.12.0.

## Hardware orientation

Back panel:

| Port | Use |
|---|---|
| **WAN** (1G, dedicated) | Uplink to your ISP modem (or to an upstream router for a parallel bring-up). |
| **LAN 1–4** (1G) | Household clients. Bridge to `br-lan` by default. |
| **USB 3.0** | Optional storage; not used by WifiHaven. |
| **DC power** | Barrel jack. |
| **Reset button** | Held during power-on to enter GL.iNet U-Boot recovery (see [Recovery](#recovery-if-the-flash-bricks)). |

## 1. Flash OpenWRT

> **Cutover plan first.** Decide whether you're bringing the Flint up in
> parallel with an existing household router or replacing it outright. Parallel
> keeps the household online during install (Flint's WAN goes into a spare LAN
> port on the existing router; its LAN becomes a separate subnet for test
> clients) and is the recommended path. Replace is faster but drops the whole
> household until install completes.

### 1.1 Get the image

You need **two** images for a GL-AX1800: a **factory** image for the first
flash off GL stock, and the **sysupgrade** image for all later upgrades. Both
come from the same firmware-selector target.

Images used for the reference install (OpenWRT **25.12.5**, verified
2026-07-22 against `downloads.openwrt.org`):

```
# First flash — via GL admin UI (§1.3, method A):
File:    openwrt-25.12.5-qualcommax-ipq60xx-glinet_gl-ax1800-squashfs-factory.bin
SHA256:  e839e571024031b2f963092d003739a1f038972d7304570eb030488e85a78581

# First flash — via U-Boot web recovery (§1.3, method B):
File:    openwrt-25.12.5-qualcommax-ipq60xx-glinet_gl-ax1800-squashfs-factory.ubi
SHA256:  aa0d496327a8579d89ccbdbef301234876041dc214fe2f2863dd0ee311148991

# Later upgrades only (do NOT use for the first flash):
File:    openwrt-25.12.5-qualcommax-ipq60xx-glinet_gl-ax1800-squashfs-sysupgrade.bin
SHA256:  55e952c6f6cf3333dc0daaa6a2d297d87732b0eb3d32423656a4f5aeb8ffe4d0
```

- Firmware selector: <https://firmware-selector.openwrt.org/> → search
  "GL.iNet GL-AX1800".
- Direct download path + checksums:
  <https://downloads.openwrt.org/releases/25.12.5/targets/qualcommax/ipq60xx/>
  (verify against the target's `sha256sums` file).

Always verify the published checksum matches what you downloaded **before**
uploading to the router. A bad image on a Flint is recoverable but painful (see
[Recovery](#recovery-if-the-flash-bricks)):

```sh
sha256sum openwrt-25.12.5-qualcommax-ipq60xx-glinet_gl-ax1800-squashfs-factory.bin
# Must exactly match the SHA256 above.
```

For a newer OpenWRT release, pick the latest sysupgrade + factory for
`qualcommax`/`ipq60xx` / `glinet_gl-ax1800` from firmware-selector and verify
the checksums the same way against that release's `sha256sums`.

### 1.2 Boot stock GL firmware and reach the admin UI

1. Power on the Flint with WAN unplugged. Plug your laptop into one of the LAN
   ports (LAN 1–4, NOT WAN).
2. Wait ~60s for boot. Your laptop should get a DHCP lease from the GL.iNet
   stack on `192.168.8.0/24` (default GL subnet).
3. Open `http://192.168.8.1` in a browser. On first boot GL prompts you to set
   an admin password. Set one — you'll only need it for the next 5 minutes.

> **Laptop-side gotcha.** If you're using a USB-ethernet dongle on a Mac, make
> sure macOS Internet Sharing is OFF for that interface — Sharing will assign
> the dongle a fixed `192.168.2.1` and prevent it from accepting a DHCP lease
> from the Flint. **System Settings → General → Sharing → Internet Sharing**
> off, then **System Settings → Network → USB Ethernet → Configure IPv4: Using
> DHCP**.

### 1.3 Flash to OpenWRT (pick one method)

**Method A — GL admin UI (simplest).** In the GL admin UI:

1. Navigate to firmware/upgrade (**System → Upgrade → Local Upgrade**).
2. Upload the **`…-squashfs-factory.bin`** image from §1.1 (the *factory* image,
   not sysupgrade).
3. **Uncheck any "keep settings" / "preserve config" option.** You want clean
   stock OpenWRT, not a GL-flavored install with leftover vendor config.
4. Confirm and flash. ~5–10 min including reboot. Do not power-cycle during
   this window.

**Method B — U-Boot web recovery (if the GL UI won't take it).** This is the
same mechanism as the [recovery path](#recovery-if-the-flash-bricks), but you
upload the OpenWRT **`…-squashfs-factory.ubi`** image instead of stock GL
firmware:

1. Power the router off.
2. Hold the reset button, then plug power in while still holding. Keep holding
   until the **blue LED flashes 5 times, then turns solid white** (~5s) —
   that's U-Boot recovery mode. Release.
3. Plug your laptop into a LAN port and set it to a **static** address on
   `192.168.1.0/24` (e.g. `192.168.1.2/24`, gateway `192.168.1.1`).
4. Browse to `http://192.168.1.1` → GL.iNet U-Boot recovery UI.
5. Upload the **`…-squashfs-factory.ubi`** image and flash. ~3 min.

### 1.4 Find the router post-flash

After the reboot the router is on stock OpenWRT defaults: **`192.168.1.1/24`**,
acting as DHCP server on its LAN, no SSH root password set.

1. Move your laptop's ethernet cable to a LAN port if it wasn't already, and
   switch its IPv4 back to **DHCP** (if you set it static in method B).
2. DHCP-renew your laptop's interface (or unplug/replug). It should pick up a
   `192.168.1.x` lease.
3. Browse to `http://192.168.1.1` → LuCI. Set a root password on first login;
   save it to your password manager.
4. SSH in: `ssh root@192.168.1.1`.
5. Sanity-check the firmware:

   ```sh
   cat /etc/openwrt_release
   # Expect: DISTRIB_RELEASE='25.12.5', DISTRIB_TARGET='qualcommax/ipq60xx'
   apk update
   # Expect: a list of repos refreshed; if this hangs or 404s, fix WAN before continuing.
   ```

## 2. Baseline OpenWRT config (do this before installing WifiHaven)

These are not strictly required for WifiHaven to start, but they're things you
want on a long-running household router and are easiest to set now while the
box is fresh. The steps are identical to the Flint 2's — see
[`install-flint2.md` §2](install-flint2.md#2-baseline-openwrt-config-do-this-before-installing-wifihaven)
for the full detail. In brief:

### 2.1 Turn on Wi-Fi and set the country code

**Wi-Fi is OFF after flashing** — vanilla OpenWRT ships with the radios disabled
and no wireless network, so you'll only have wired connectivity until you bring
it up. This step is identical on every OpenWRT router; do it (LuCI or `uci` —
enable `radio0`/`radio1`, set an SSID + WPA2/WPA3 password, set the country) per
[`install-flint2.md` §2.1](install-flint2.md#21-turn-on-wi-fi-and-set-the-country-code).

### 2.2 Remove unused IPsec WAN-side rules

Stock OpenWRT's firewall ships `Allow-IPSec-ESP` and `Allow-ISAKMP` rules
opening WAN→LAN for IPsec passthrough. Close them if you're not running IPsec
(see [`install-flint2.md` §2.2](install-flint2.md#22-remove-unused-ipsec-wan-side-rules)
for the exact `uci` commands).

### 2.3 HW flow offload: leave disabled

Do **not** enable fw4's `flow_offloading` / `flow_offloading_hw` on a WifiHaven
router. HW offload bypasses conntrack once a flow is offloaded, and the agent's
per-device accounting and forward-drop enforcement rely on conntrack. Keep both
off (the stock default). See
[#703](https://github.com/wifihaven/wifihaven/issues/703).

## 3. Recommended companion packages (optional)

UPnP/IGD (`miniupnpd-nftables`), SQM/CAKE for bufferbloat, and local DNS names
for static-IP devices are all worth setting up. The recipes are identical to
the Flint 2's — follow
[`install-flint2.md` §3](install-flint2.md#3-recommended-companion-packages-optional),
with **one hardware difference**: when configuring **SQM QoS**, the WAN
interface device is **not** `eth1`. Check **LuCI → Network → Interfaces → WAN**
for the actual L3 device on this box (the dedicated 1G WAN port) and use that
in the SQM **Interface** field.

## 4. Install WifiHaven

Now follow **[`install-openwrt.md` §2](install-openwrt.md#2-install-with-the-one-shot-script-recommended)**
for the actual agent install.

For a Flint talking to the production cloud API, the prompt answers are:

- API server URL: `https://api.wifihaven.net` (the default)
- Enrollment token: the `et_…` value from `https://app.wifihaven.net → Routers → Add router`
- LAN prefix: auto-detected from `network.lan.ipaddr`; accept the default
  unless you've moved LAN off its first /24.

Verify per `install-openwrt.md §3`.

## 5. Known drift on OpenWRT 24.10+ / 25.12.x (apk-based)

OpenWRT 25.12.x uses `apk`, not `opkg`. The apk-specific install caveats — most
importantly the **`dnsmasq-full` swap** that all hostname-based blocking depends
on ([#704](https://github.com/wifihaven/wifihaven/issues/704)) — are shared
across all apk routers and documented in
[`install-flint2.md` §5](install-flint2.md#5-known-drift-on-openwrt-2512x-apk-based).
Read that section before relying on the install. The quick check after running
`install.sh`:

```sh
dnsmasq --help | grep nftset   # must be non-empty; if empty, apply the §5.1 workaround
```

## Recovery if the flash bricks

GL.iNet's U-Boot has a built-in web recovery mode. Walk through this BEFORE you
ever need it so the steps are familiar.

1. **Power the router off.**
2. **Hold the reset button**, then plug power in while still holding. Keep
   holding until the **blue LED flashes 5 times, then turns solid white** (~5s),
   indicating U-Boot recovery mode. Release.
3. Plug your laptop into a **LAN port** (not WAN).
4. Configure your laptop's ethernet to a static address on `192.168.1.0/24`
   (e.g. `192.168.1.2/24`, subnet `255.255.255.0`, gateway `192.168.1.1`).
5. Browse to `http://192.168.1.1` — GL.iNet's recovery web UI loads.
6. Upload the **stock GL firmware** (NOT OpenWRT) for the GL-AX1800 from
   [dl.gl-inet.com](https://dl.gl-inet.com/) (pick the `ax1800` /
   U-Boot-compatible image). Click **Update firmware** and wait ~3 min.
   (To re-flash OpenWRT instead, upload the `…-squashfs-factory.ubi` image from
   §1.1 here — that is method B in §1.3.)
7. The router reboots. If you flashed stock GL, you're back at `192.168.8.1`;
   restart at §1.3 to re-flash OpenWRT.

> **Note:** the U-Boot operation removes the router's settings and installed
> packages — it is a full re-flash, not a config reset.

If U-Boot web recovery doesn't respond, the fallback is serial/TFTP U-Boot
recovery; that's involved enough to be out of scope here — refer to the GL.iNet
support page for the GL-AX1800.

## References

- [`install-openwrt.md`](install-openwrt.md) — the canonical OpenWRT-side
  install once the box is flashed and on baseline OpenWRT.
- [`install-flint2.md`](install-flint2.md) — the Flint 2 (GL-MT6000) guide;
  the shared baseline-config, companion-package, and apk-drift detail this doc
  references.
- [`architecture.md`](architecture.md) — agent design.
- OpenWRT firmware selector: <https://firmware-selector.openwrt.org/>
- OpenWRT GL-AX1800 support (merge PR with install/recovery notes):
  <https://github.com/openwrt/openwrt/pull/14950>
- GL.iNet stock firmware and debrick docs:
  <https://dl.gl-inet.com/> ·
  <https://docs.gl-inet.com/router/en/4/tutorials/debrick/>
- [#2364](https://github.com/wifihaven/wifihaven/issues/2364) — issue this
  install doc closes.
- [#2304](https://github.com/wifihaven/wifihaven/issues/2304) /
  [#2363](https://github.com/wifihaven/wifihaven/issues/2363) — why GL stock
  firmware is not a supported target.
