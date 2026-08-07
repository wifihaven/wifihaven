# Installing WifiHaven on a Netgear WAX206 (AX3200)

Hardware-specific install path for the **Netgear WAX206 (AX3200)** — a
mainline-OpenWRT target on the same MediaTek MT7622 platform as the well-known
Linksys E8450 / Belkin RT3200. This doc covers everything you do to
the box *before* the generic [`install-openwrt.md`](install-openwrt.md) flow
takes over: flashing OpenWRT, baseline OpenWRT config, and the recovery path if
the flash bricks.

> **Alternative / advanced hardware — not one of the recommended tiers.** The
> recommended lineup is the three GL.iNet routers (Low = Flint, Medium =
> Flint 2, High = Flint 3) — see
> [Choosing your hardware](install-openwrt.md#choosing-your-hardware). The
> WAX206 is a fine target if you're comfortable flashing, but it prices *above*
> the Flint (~$95–110 vs ~$80), so it doesn't fit the "value" slot, and — like
> every supported router — it requires flashing **vanilla OpenWRT** first (§1).
> Netgear stock firmware is not a supported target.

If you're installing WifiHaven on different OpenWRT hardware, skip directly to
[`install-openwrt.md`](install-openwrt.md) — only the sections marked
"Hardware-specific" below apply to the WAX206.

> **The WAX206 ships Netgear's stock firmware, not OpenWRT — you must flash
> vanilla OpenWRT first (§1).** Unlike the GL.iNet models, the WAX206 has no
> OpenWRT-based vendor firmware at all; the agent is validated only on the
> **vanilla OpenWRT** images from
> [firmware-selector.openwrt.org](https://firmware-selector.openwrt.org/), and
> Netgear stock firmware is **not a supported target**
> ([#2304](https://github.com/wifihaven/wifihaven/issues/2304),
> [#2363](https://github.com/wifihaven/wifihaven/issues/2363) — the
> vendor-stock-unverified caveat applies to any non-OpenWRT firmware). Do not
> run the agent installer on Netgear stock.

## Why this hardware

- **MediaTek MT7622BV**, dual-core ARM Cortex-A53, aarch64 — the same mainline
  platform as the Linksys E8450 / Belkin RT3200, with strong community support.
- **Dual-band 802.11ax** (AX3200).
- **512 MB DDR3 RAM**, **256 MB SPI-NAND** flash.
- One **2.5G WAN** port + four **gigabit** LAN, USB 3.0.
- A solid "flash it yourself" target if you're comfortable with the process —
  though it's an [alternative, not one of the recommended
  tiers](install-openwrt.md#choosing-your-hardware) (it prices above the Low-tier
  Flint).

## Hardware orientation

Back panel:

| Port | Use |
|---|---|
| **WAN / 2.5G** | Uplink to your ISP modem (or to an upstream router for a parallel bring-up). |
| **LAN 1–4** (1G) | Household clients. Bridge to `br-lan` by default. |
| **USB 3.0** | Optional storage; not used by WifiHaven. |
| **Power** | DC barrel jack + physical power switch. |
| **Reset button** | Recessed; used for factory reset and (with `nmrpflash`) recovery. |

## 1. Flash OpenWRT

> **Cutover plan first.** Decide whether you're bringing the WAX206 up in
> parallel with an existing household router or replacing it outright. Parallel
> keeps the household online during install (WAX206's WAN goes into a spare LAN
> port on the existing router; its LAN becomes a separate subnet for test
> clients) and is the recommended path. Replace is faster but drops the whole
> household until install completes.

### 1.1 Get the image

The WAX206 boots from NAND, so the **first** flash off Netgear stock uses a
**factory** image (`squashfs-factory.img`) through the OEM web UI. All later
upgrades use the **sysupgrade** image. A third image, the
**`initramfs-recovery.itb`**, is only for the TFTP/`nmrpflash` de-brick path
(see [Recovery](#recovery-if-the-flash-bricks)).

Images used for the reference install (OpenWRT **25.12.5**, verified
2026-07-22 against `downloads.openwrt.org`):

```
# First flash — via Netgear OEM web UI (§1.3):
File:    openwrt-25.12.5-mediatek-mt7622-netgear_wax206-squashfs-factory.img
SHA256:  e441c92cccc3cdd22ace4b5f7483182b8b64b7d2c9b2fecbca63c32ed7c8a53d

# Later upgrades only (do NOT use for the first flash from stock):
File:    openwrt-25.12.5-mediatek-mt7622-netgear_wax206-squashfs-sysupgrade.bin
SHA256:  d9dbe5e02860b05b209c992e39ba74ec2590e2e11224fc5662bc278aeac7a8c3

# Recovery only (TFTP / nmrpflash de-brick, see Recovery §):
File:    openwrt-25.12.5-mediatek-mt7622-netgear_wax206-initramfs-recovery.itb
SHA256:  3982443bf4850b61f98ba11dca713d5d9e946b346825a5e67470336d5be6c8a0
```

- Firmware selector: <https://firmware-selector.openwrt.org/> → search
  "Netgear WAX206".
- Direct download path + checksums:
  <https://downloads.openwrt.org/releases/25.12.5/targets/mediatek/mt7622/>
  (verify against the target's `sha256sums` file).

Always verify the published checksum matches what you downloaded **before**
uploading to the router:

```sh
sha256sum openwrt-25.12.5-mediatek-mt7622-netgear_wax206-squashfs-factory.img
# Must exactly match the SHA256 above.
```

For a newer OpenWRT release, pick the latest images for `mediatek`/`mt7622` /
`netgear_wax206` from firmware-selector and verify the checksums the same way
against that release's `sha256sums`.

### 1.2 Boot stock Netgear firmware and reach the admin UI

1. Power on the WAX206 with WAN unplugged. Plug your laptop into one of the LAN
   ports (LAN 1–4, NOT WAN).
2. Wait ~60s for boot. Your laptop should get a DHCP lease from the Netgear
   stack (default LAN `192.168.1.1`, so you'll get a `192.168.1.x` address).
3. Open `http://192.168.1.1` (or `http://www.routerlogin.net`) in a browser and
   complete/skip Netgear's first-boot setup enough to reach the admin UI. Log in
   (default is on the label; you'll be prompted to set an admin password).

> **Laptop-side gotcha.** If you're using a USB-ethernet dongle on a Mac, make
> sure macOS Internet Sharing is OFF for that interface — Sharing will assign
> the dongle a fixed `192.168.2.1` and prevent it from accepting a DHCP lease.
> **System Settings → General → Sharing → Internet Sharing** off, then **System
> Settings → Network → USB Ethernet → Configure IPv4: Using DHCP**.

### 1.3 Flash the factory image via the OEM web UI

In the Netgear admin UI:

1. Navigate to **Advanced → Administration → Firmware Update** (labels vary
   slightly by stock version; look for a manual firmware-upload page).
2. Select the **`…-squashfs-factory.img`** image from §1.1 and upload it.
3. Netgear will warn the firmware is **older / not a Netgear image** — this is
   expected; confirm/OK past it.
4. Confirm and flash. The device reboots into OpenWRT. Do not power-cycle during
   this window.

If the OEM UI refuses the image, use the TFTP/`nmrpflash` path in
[Recovery](#recovery-if-the-flash-bricks) to push the same factory image.

### 1.4 Find the router post-flash

After the reboot the router is on stock OpenWRT defaults: **`192.168.1.1/24`**,
acting as DHCP server on its LAN, no SSH root password set.

1. Keep your laptop in a LAN port with IPv4 on **DHCP**.
2. DHCP-renew your laptop's interface (or unplug/replug). It should pick up a
   `192.168.1.x` lease.
3. Browse to `http://192.168.1.1` → LuCI. Set a root password on first login;
   save it to your password manager.
4. SSH in: `ssh root@192.168.1.1`.
5. Sanity-check the firmware:

   ```sh
   cat /etc/openwrt_release
   # Expect: DISTRIB_RELEASE='25.12.5', DISTRIB_TARGET='mediatek/mt7622'
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
router. The MT7622 supports Mediatek WED HW flow offload, but HW offload
bypasses conntrack once a flow is offloaded, and the agent's per-device
accounting and forward-drop enforcement rely on conntrack. Keep both off (the
stock default). See [#703](https://github.com/wifihaven/wifihaven/issues/703).

## 3. Recommended companion packages (optional)

UPnP/IGD (`miniupnpd-nftables`), SQM/CAKE for bufferbloat, and local DNS names
for static-IP devices are all worth setting up. The recipes are identical to
the Flint 2's — follow
[`install-flint2.md` §3](install-flint2.md#3-recommended-companion-packages-optional),
with **one hardware difference**: when configuring **SQM QoS**, set the
**Interface** to the WAN device shown in **LuCI → Network → Interfaces → WAN**
(the 2.5G WAN port on this box), not the Flint 2's `eth1`.

## 4. Install WifiHaven

Now follow **[`install-openwrt.md` §2](install-openwrt.md#2-install-with-the-one-shot-script-recommended)**
for the actual agent install.

For a WAX206 talking to the production cloud API, the prompt answers are:

- API server URL: `https://api.wifihaven.net` (the default)
- Enrollment token: the `et_…` value from `https://app.wifihaven.net → Routers → Add router`
- LAN prefix: auto-detected from `network.lan.ipaddr`; accept the default
  unless you've moved LAN off its first /24.

Verify per `install-openwrt.md §3`.

Then work through **[`install-openwrt.md` §4](install-openwrt.md#4-enroll-your-devices-then-block-unmanaged-ones)**
— assign your devices to profiles, then switch the unmanaged-device policy to
`block`. Installing the agent does not put anything under policy on its own.

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

Netgear routers recover via the **NMRP** protocol, which
[`nmrpflash`](https://github.com/jclehner/nmrpflash) speaks — this is the
OpenWRT-recommended de-brick path and does **not** require opening the case.

1. Install `nmrpflash` on your laptop (packages/builds are on the
   [nmrpflash releases page](https://github.com/jclehner/nmrpflash)).
2. Connect your laptop to a **LAN port** of the (powered-off) WAX206.
3. Give the laptop's wired interface a static address on `192.168.1.0/24` — e.g.
   `192.168.1.100/24`.
4. Run `nmrpflash` pointed at your interface and the image, then power the
   router on so `nmrpflash` catches its NMRP advertisement during boot:

   ```sh
   # Windows: nmrpflash -i "Ethernet" -f <image>
   # Linux/macOS: nmrpflash -i en0 -f <image>
   nmrpflash -i <your-wired-interface> \
     -f openwrt-25.12.5-mediatek-mt7622-netgear_wax206-squashfs-factory.img
   ```

   Use the OpenWRT **`…-squashfs-factory.img`** to (re-)install OpenWRT, or a
   Netgear stock `.img` to return to vendor firmware. Follow `nmrpflash`'s
   prompts; power-cycle the router when it asks you to.

**Advanced (serial/TFTP U-Boot).** The WAX206 also has a U-Boot TFTP recovery
that boots the **`initramfs-recovery.itb`** image, and a 3.3V serial console
(115200 8N1) for manual U-Boot control. Those paths are involved and easy to get
wrong — refer to the OpenWRT WAX206 device page rather than reproducing exact
bootloader commands here:
<https://openwrt.org/toh/netgear/wax206>.

## References

- [`install-openwrt.md`](install-openwrt.md) — the canonical OpenWRT-side
  install once the box is flashed and on baseline OpenWRT.
- [`install-flint2.md`](install-flint2.md) — the Flint 2 (GL-MT6000) guide; the
  shared baseline-config, companion-package, and apk-drift detail this doc
  references.
- [`architecture.md`](architecture.md) — agent design.
- OpenWRT firmware selector: <https://firmware-selector.openwrt.org/>
- OpenWRT Netgear WAX206 device page (install + recovery):
  <https://openwrt.org/toh/netgear/wax206>
- OpenWRT WAX206 support merge PR:
  <https://github.com/openwrt/openwrt/pull/11363>
- `nmrpflash` (Netgear NMRP de-brick tool):
  <https://github.com/jclehner/nmrpflash>
- [#2364](https://github.com/wifihaven/wifihaven/issues/2364) — issue this
  install doc closes.
- [#2304](https://github.com/wifihaven/wifihaven/issues/2304) /
  [#2363](https://github.com/wifihaven/wifihaven/issues/2363) — why vendor stock
  firmware is not a supported target.
