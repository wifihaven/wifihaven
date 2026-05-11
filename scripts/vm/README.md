# VM e2e harness — router VM

Foundational QEMU/KVM scripts for the OpenWRT router VM used by the e2e suite
(parent issue: #106, this slice: #144). Sibling pieces — client VM (#146),
orchestrator (#148), custom OpenWRT image (#150) — share the contracts below.

## Shared contracts (read before adding sibling VMs)

| Contract | Value | Override |
|---|---|---|
| LAN bridge name | `fdns-lan0` | `FDNS_LAN_BRIDGE` |
| LAN subnet | `192.168.50.0/24` | `FDNS_LAN_SUBNET` |
| LAN host IP (Linux) | `192.168.50.1/24` on the bridge | `FDNS_LAN_HOST_IP` / `FDNS_LAN_HOST_CIDR` |
| Router LAN IP | `192.168.1.1` (OpenWRT default — does **not** use 192.168.50.0/24 yet; see "Known quirks") | n/a |
| Router SSH (WAN hostfwd) | `127.0.0.1:2222 → router:22` | `FDNS_ROUTER_SSH_PORT` |
| Router HTTP (LuCI hostfwd) | `127.0.0.1:8080 → router:80` | `FDNS_ROUTER_HTTP_PORT` |
| Router WAN MAC | `52:54:00:fd:00:01` | `FDNS_ROUTER_MAC_WAN` |
| Router LAN MAC | `52:54:00:fd:00:02` | `FDNS_ROUTER_MAC_LAN` |
| OpenWRT version | `23.05.6` (x86/64, generic-ext4-combined) | edit `versions.sh` |

Client VMs (#146) attach their LAN NIC to the same bridge:
- Linux: `-netdev bridge,id=lan,br=fdns-lan0`
- macOS: `-netdev vmnet-shared,id=lan`

## Host prerequisites

### Linux (primary, fully supported)
- `qemu-system-x86_64`, `qemu-img` (Debian/Ubuntu: `qemu-system-x86 qemu-utils`)
- `iproute2` (`ip` command)
- `/dev/kvm` accessible (`sudo usermod -aG kvm $USER` if needed)
- `socat` (for monitor commands and snapshot/restore)
- `sudo` (used by `lan-bridge-up.sh` for `ip link add`)
- `/etc/qemu/bridge.conf` must contain `allow fdns-lan0`, owned by root:
  ```
  echo 'allow fdns-lan0' | sudo tee -a /etc/qemu/bridge.conf
  ```
  `qemu-bridge-helper` must be setuid root (default on Debian/Ubuntu).

### macOS (limited — see "Known quirks")
- QEMU 7.1+ with `vmnet-shared` support: `brew install qemu`
- `socat`: `brew install socat`
- `sudo` (vmnet requires it for the QEMU process)

## Quick start

```bash
# One-time: create the shared LAN bridge.
scripts/vm/lan-bridge-up.sh

# Boot the router.
scripts/vm/router-up.sh

# Watch it come up.
tail -f scripts/vm/.cache/router-serial.log

# Get a shell on the router (over WAN-side hostfwd; default root password is empty).
ssh -p 2222 root@127.0.0.1

# Stop the router.
scripts/vm/router-down.sh

# Tear down the bridge when you're completely done.
scripts/vm/lan-bridge-down.sh
```

## Snapshots

`router-snapshot.sh` / `router-restore.sh` use QEMU's `savevm`/`loadvm`, which
stores both disk and live RAM in the qcow2 overlay (`.cache/router-overlay.qcow2`).

```bash
scripts/vm/router-snapshot.sh enrolled        # save current state
scripts/vm/router-restore.sh  enrolled        # restore (works whether VM is running or stopped)
qemu-img snapshot -l scripts/vm/.cache/router-overlay.qcow2   # list
```

Snapshot names must match `[A-Za-z0-9_.-]+`.

## QEMU monitor (live debugging)

The monitor is exposed as a unix socket so it doesn't collide with the serial
console (which is also used for unattended logging):

```bash
socat - UNIX-CONNECT:scripts/vm/.cache/router-monitor.sock
# now type 'info network', 'info snapshots', 'system_powerdown', etc.
```

## Bumping the OpenWRT version

1. Edit `OPENWRT_VERSION` in `scripts/vm/versions.sh`.
2. Refresh the SHA256:
   ```bash
   curl -sSL "https://downloads.openwrt.org/releases/${VER}/targets/x86/64/sha256sums" \
     | grep generic-ext4-combined.img.gz
   ```
3. Update `OPENWRT_IMAGE_SHA256`.
4. `rm -rf scripts/vm/.cache/` and re-run `router-up.sh`.

## Known quirks (v1 — to be tightened later)

- **OpenWRT default LAN IP is `192.168.1.1`, not on the `192.168.50.0/24`
  bridge subnet.** The bridge sits at `192.168.50.1` (Linux). For v1, the
  orchestrator should talk to the router over the WAN-side SSH hostfwd
  (`127.0.0.1:2222`), or reconfigure OpenWRT's LAN IP via a uci-defaults
  script — that work lives in #150 (custom image). The host-side bridge IP
  is provided so a future client VM (#146) can be assigned a 192.168.50.x
  address by the router (once #150 reconfigures DHCP) and the host can reach
  it for debugging.
- **macOS: real bridging requires QEMU under sudo via `vmnet-shared`.** The
  router will boot, but cross-VM L2 traffic between router and client on
  macOS is less battle-tested than the Linux bridge path. If you hit
  trouble, prefer a Linux host (Multipass / Lima / a real Linux VM) for
  running the e2e suite.
- **Root password is empty on first boot.** This is the stock OpenWRT image
  default. The custom image work (#150) will bake in a known test password
  and/or SSH key.
- **No automatic image build.** This slice only consumes the stock image.
  #150 adds the familydns-agent ipk and uci-defaults overrides.

## Verification (manual e2e for #144)

Until the orchestrator (#148) is wired up, verify by hand:

1. `scripts/vm/router-up.sh` boots without error; `router-down.sh` is a no-op
   when not running and cleanly stops a running VM.
2. `ssh -p 2222 root@127.0.0.1` lands a shell on the router (empty password).
3. From inside the router: `ping -c2 8.8.8.8` succeeds (WAN NIC + NAT).
4. From inside the router: `ip link show eth0` (LAN NIC) is `UP`. On Linux,
   `bridge link show` on the host shows a tap attached to `fdns-lan0`.
5. `scripts/vm/router-snapshot.sh smoke` reports success; restart the VM
   (`router-down.sh && router-up.sh`); `scripts/vm/router-restore.sh smoke`
   applies the snapshot offline; bring the VM back up and confirm state.

## Layout

```
scripts/vm/
  versions.sh            pinned OpenWRT version + bridge / port contracts
  lib.sh                 sourced by all scripts; image download + verify
  lan-bridge-up.sh       create the shared LAN bridge (idempotent)
  lan-bridge-down.sh     remove the LAN bridge
  router-up.sh           boot the router VM (daemonized, qcow2 overlay)
  router-down.sh         clean shutdown via monitor, escalating to SIGKILL
  router-snapshot.sh     savevm <name>
  router-restore.sh      loadvm <name> (live) or qemu-img snapshot -a (offline)
  .cache/                downloaded image, overlay, pidfile, sockets (gitignored)
```
