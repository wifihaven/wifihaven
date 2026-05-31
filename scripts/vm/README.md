# VM e2e harness

Scripted QEMU/KVM setup for end-to-end testing of the OpenWRT side of
WifiHaven. The big picture is in #106; this directory is the implementation
of #144 (router VM) and #146 (client VM).

## Host requirements

- Linux with `/dev/kvm` accessible (KVM-accelerated boots).
- `qemu-system-x86_64`, `qemu-img`
- One of `xorrisofs`, `genisoimage`, or `mkisofs` (for the NoCloud seed)
- `openssl`, `curl`, `ssh` (client), `iproute2` (for `ip`)
- `socat` (used by `client-down.sh` and by the router monitor / snapshot
  scripts — optional for client tear-down, required for `router-snapshot.sh`
  and `router-restore.sh`)
- The LAN bridge from the router VM (#144) must already be up before
  `client-up.sh` runs. `router-up.sh` brings it up automatically; otherwise
  call `lan-bridge-up.sh` directly.
- `/etc/qemu/bridge.conf` must contain `allow ${WH_LAN_BRIDGE}` (default
  `allow wh-lan0`) so `qemu-bridge-helper` will attach taps.

The harness is not expected to work on macOS — no KVM. Use a Linux dev host
or CI runner. For running in CI, see
[`docs/ops/kvm-runner.md`](../../docs/ops/kvm-runner.md) — self-hosted
runner provisioning, registration, and the systemd unit template.

## Shared configuration

[`config.sh`](config.sh) defines the names that span the router VM and the
client VM: the LAN bridge name, the LAN subnet, the Alpine image version, and
file paths. **Both halves of the harness read from here.** If you need to
change a bridge name or subnet, change it in this one file.

### Running two pairs concurrently (#891)

**One-time host setup**: pre-allocate a pool of LAN bridges and authorize
them all in `/etc/qemu/bridge.conf`. Then any subsequent VM e2e run
auto-picks a free bridge from the pool — no extra env vars needed for the
bridge.

```
# Creates wh-lan0..wh-lan6 (default pool size 7) and ensures bridge.conf
# has an `allow` line for each. Idempotent.
sudo scripts/vm/lan-bridge-pool-bootstrap.sh
```

The bootstrap uses `sudo` for both `ip link add` and `tee -a
/etc/qemu/bridge.conf` — existing entries are left alone, missing entries
are appended. Run it once per host (or whenever you bump
`WH_LAN_BRIDGE_POOL_SIZE`).

**Per-run knobs**: to launch a second pair on a bootstrapped host, set
`WH_RUN_ID` — the bridge is auto-picked from the pool and host ports are
auto-allocated by `scripts/e2e-vm.sh`:

```
WH_RUN_ID=b scripts/e2e-vm.sh --mode=fake
```

- `WH_RUN_ID` — short token. Suffixes the QEMU `-name` (so `pgrep`-based
  fallbacks in `*-down.sh` only match this instance) and the `.run/` subdir
  (so overlay/pid/socket files don't collide). Also seeds the default MACs.
- `WH_PORT_BASE` — optional. First host port; router SSH = base, router
  HTTP = base+1, client SSH = base+2. When set, `scripts/e2e-vm.sh` skips
  random port allocation and uses the base-derived window. Individual port
  vars (`WH_ROUTER_SSH_PORT`, `WH_ROUTER_HTTP_PORT`,
  `WH_CLIENT_SSH_PORT_BASE`) still win if set explicitly. Most callers
  shouldn't need this — `scripts/e2e-vm.sh` now auto-allocates free host
  ports per run (#902), so concurrent pairs no longer need a manual port
  window.
- `WH_LAN_BRIDGE` — optional override. If set explicitly, skips pool pick.
- For fake-mode, `WH_FAKE_API_PORT` defaults to `WH_PORT_BASE + 1000` when
  `WH_PORT_BASE` is set (so a second arm gets a distinct, predictable port
  with one knob — #907), or a randomly-allocated free port otherwise (#902).
  The +1000 offset keeps fake-api well clear of the router/client SSH+HTTP
  triplet at `base..base+2`, so a typical second-arm spacing of `+100`
  (e.g. `base=2222` and `base=2322`) doesn't pile A's fake-api on top of B's
  router-ssh. Override only if you need a fixed port.

**On a host without the pool**, the bridge picker is a no-op and the run
falls back to creating `wh-lan0` on the fly — byte-identical to single-pair
behavior on un-bootstrapped hosts.

**Bridge-pool reservation (#907).** The picker's per-bridge in-use signal is
"attached tap OR live reservation marker." Markers live at
`/run/wh-lan-bridge/wh-lan<N>.reservation` and contain the holding process's
PID plus its `WH_RUN_ID` for debugging. `router-up.sh` writes the marker
under the host-wide flock with its own PID, then rewrites it with qemu's
PID once `qemu -daemonize` returns — so the marker outlives the calling
shell and covers the small window between flock-release and qemu's tap
showing up in `/sys/class/net/<br>/brif`. `router-down.sh` and
`lan-bridge-down.sh` clear the marker on tidy teardown. A SIGKILL'd run
leaves a stale marker behind; the next picker reaps it automatically — a
marker whose PID is no longer alive is treated as absent and the slot is
recycled. `lan-bridge-pool-bootstrap.sh` creates `/run/wh-lan-bridge/` with
mode 1777, so non-root pickers can drop their own markers without affecting
others'.

## Client VM

A minimal Alpine VM that lives on the router VM's LAN bridge with a
caller-chosen MAC address. Used by the orchestrator (#148) to generate
traffic for individual e2e scenarios.

### Lifecycle

```
build-client-base.sh         # one-time: download Alpine + bake tools + SSH key
client-up.sh --mac ...       # per-scenario: boot a fresh overlay
client-exec.sh -- <cmd...>   # run shell commands inside the client
client-down.sh               # tear down + discard overlay
```

`build-client-base.sh` produces `.cache/client-base.qcow2`. Every `client-up.sh`
creates a fresh qcow2 overlay on top of that base, so resets are essentially
free (delete the overlay).

### Networking

Each client gets two NICs:

| iface | attached to                | purpose                                 |
|-------|----------------------------|------------------------------------------|
| eth0  | `${WH_LAN_BRIDGE}` bridge | LAN side. DHCP from router VM. **All real traffic** (DNS, HTTP, etc.) goes here. |
| eth1  | QEMU user-mode (SLIRP)     | Orchestrator SSH only (port-forwarded to `127.0.0.1:<ssh-port>`). **No default route**, **no DNS** — keeps the SSH path from leaking traffic around the router. |

The DHCP-provided resolver on eth0 (the router) becomes the system resolver,
so any `getaddrinfo` / `dig` / `curl` inside the VM uses the router. This is
verified by the acceptance criteria below.

### Usage

```bash
# One-time: build the base. Re-run with --force to refresh after bumping the
# Alpine version in config.sh.
scripts/vm/build-client-base.sh

# Boot a client with a specific MAC.
scripts/vm/client-up.sh --mac 02:00:00:00:00:01 --name kid-laptop

# Run commands inside the client. Exit code is the remote exit code.
scripts/vm/client-exec.sh --name kid-laptop -- ip a
scripts/vm/client-exec.sh --name kid-laptop -- dig example.com
scripts/vm/client-exec.sh --name kid-laptop -- curl -fsS http://example.com

# Tear down.
scripts/vm/client-down.sh --name kid-laptop
```

`--name` is optional (defaults to `client1`); the orchestrator only needs it
when the multi-client story lands (see "Out of scope" below).

### Test-only SSH key

[`keys/client_test_ed25519`](keys) is a fixed ed25519 keypair baked into the
base image so the orchestrator can SSH in without per-boot key injection.

**This key is committed to the repository on purpose** because the VMs are
ephemeral test fixtures on an isolated bridge with no inbound reachability
from outside the host. **Never reuse it for anything real.** See
[`keys/README.md`](keys/README.md).

### Out of scope (v1)

- **Multi-client concurrent boot.** Tracked as a follow-up; the current
  `--name` plumbing is forward-compatible but `client-up.sh` does not yet
  allocate non-overlapping SSH ports automatically.
- **Snapshot/restore via `savevm`/`loadvm`.** The overlay approach gives
  scenario resets cheaply enough that named snapshots aren't needed yet.

## Router VM

OpenWRT 23.05.6 x86/64 booted in QEMU/KVM with two NICs:

| iface | attached to | purpose |
|---|---|---|
| eth0 | `${WH_LAN_BRIDGE}` bridge | LAN side — shared with client VMs (#146). |
| eth1 | QEMU user-mode (SLIRP) | WAN side — gets NAT-to-internet for free, plus host port-forwards for orchestrator access. |

OpenWRT's default board config assigns eth0→lan, eth1→wan, matching the order
above.

### Lifecycle

```
lan-bridge-up.sh             # idempotent — also called by router-up.sh
router-up.sh                 # download + verify image, qcow2 overlay, boot
router-snapshot.sh <name>    # savevm
router-restore.sh  <name>    # loadvm (running) / qemu-img -a (stopped)
router-down.sh               # graceful shutdown via monitor → SIGTERM → SIGKILL
lan-bridge-down.sh           # tear down the bridge (only when all VMs are stopped)
```

`router-up.sh` writes its overlay, pidfile, and serial console log under
`.run/router/`. The QEMU monitor socket lives **outside** `.run/`, under a short
base dir (`${XDG_RUNTIME_DIR:-/tmp}/wh-vm/…`, exported as `WH_SOCK_DIR`), because
the AF_UNIX `sun_path` limit caps socket paths at ~108 chars and the `.run/` tree
sits under a deep CI runner home. Snapshots live inside the overlay (qcow2
internal snapshots) and can be inspected with `qemu-img snapshot -l`.

### Image pinning + bumping

The OpenWRT version and SHA256 are pinned in [`config.sh`](config.sh)
(`WH_OPENWRT_VERSION`, `WH_OPENWRT_SHA256`). To bump:

1. Edit `WH_OPENWRT_VERSION`.
2. Fetch the new SHA256:
   ```bash
   curl -sSL "https://downloads.openwrt.org/releases/${VER}/targets/x86/64/sha256sums" \
     | grep generic-ext4-combined.img.gz
   ```
3. Update `WH_OPENWRT_SHA256`.
4. `rm -rf scripts/vm/.cache scripts/vm/.run/router` and re-run `router-up.sh`.

### Host access

- **SSH**: `ssh -p ${WH_ROUTER_SSH_PORT} root@127.0.0.1` (default 2222).
  Stock OpenWRT root password is empty on first boot — set one immediately or
  wait for #150 (custom image bakes in a known test password + SSH key).
- **LuCI HTTP**: `http://127.0.0.1:${WH_ROUTER_HTTP_PORT}` (default 8080).
- **QEMU monitor** (savevm / loadvm / info network / system_powerdown). The
  socket path is printed by `router-up.sh`; it lives under `WH_SOCK_DIR`
  (`${XDG_RUNTIME_DIR:-/tmp}/wh-vm/…/router-monitor.sock`), not under `.run/`:
  ```bash
  socat - UNIX-CONNECT:"${XDG_RUNTIME_DIR:-/tmp}/wh-vm/router-monitor.sock"
  ```

### Snapshots

`savevm`/`loadvm` capture disk + RAM in the qcow2 overlay, so scenarios can
reset to a known-good state in seconds:

```bash
scripts/vm/router-snapshot.sh enrolled
scripts/vm/router-restore.sh  enrolled    # running or stopped
qemu-img snapshot -l scripts/vm/.run/router/overlay.qcow2
```

Snapshot names must match `[A-Za-z0-9_.-]+`.

## Custom router image (wifihaven-agent baked in, #150)

`router-up.sh` defaults to the stock OpenWRT image pinned in `config.sh`.
For end-to-end testing, [`build-router-image.sh`](build-router-image.sh)
produces an OpenWRT image with the `wifihaven-agent` ipk pre-installed
and first-boot defaults seeded under `/etc/uci-defaults/`. It wraps
OpenWRT's official Image Builder, run inside a pinned Debian container
for reproducibility.

```bash
# Local dev: build the ipk from the working tree, bake it in.
scripts/vm/build-router-image.sh

# Use a previously-published release ipk:
IPK_SOURCE=release scripts/vm/build-router-image.sh

# Use a specific ipk file:
IPK_SOURCE=path IPK_PATH=/abs/path/wifihaven_X.Y.Z-1_all.ipk \
    scripts/vm/build-router-image.sh
```

Output: `.cache/openwrt-wifihaven-ipk-23.05.img` for the default ipk
flavor, or `.cache/openwrt-wifihaven-apk-25.12.img` for `PKG_FORMAT=apk`
(uncompressed, ready to feed
directly to QEMU). Image size: ~30–50 MB.

To boot it via the existing harness, point `router-up.sh` at the file
through `WH_ROUTER_IMAGE_PATH`:

```bash
WH_ROUTER_IMAGE_PATH=scripts/vm/.cache/openwrt-wifihaven-ipk-23.05.img \
    scripts/vm/router-up.sh
```

When the env var is set, `ensure_openwrt_image` skips the stock-image
download + sha256 check and uses the file verbatim.

### Pinning + bumping the Image Builder release

The Image Builder version lives in [`versions.sh`](versions.sh)
(`OPENWRT_VERSION`). It does **not** have to match the stock-image
version in `config.sh`, though keeping them aligned is a good habit. The
build script downloads the official `sha256sums` from the same release
directory and verifies the tarball against it, so there's no hash to
hand-edit when bumping.

### Source-of-truth for the ipk

The image bakes in the **same `.ipk` artifact** that production routers
install via `opkg` — built by `openwrt/build-ipk.sh`, published by
`openwrt-build.yml`. VM e2e and the real install path therefore
exercise the same bits. If the package contents change, change them in
`openwrt/` — never patch a staged copy here.

### First-boot config (`uci-defaults/99-wifihaven`)

OpenWRT runs `/etc/uci-defaults/*` exactly once on first boot, then
deletes each script. The seeded values (`api_url`, `lan_prefix`) are
defaults the orchestrator (#148) overrides over SSH at boot — they only
exist so a developer who boots the image and pokes at it sees something
sensible. To extend the first-boot setup, add UCI commands to
`uci-defaults/99-wifihaven` and rebuild.

The script also seeds an empty `/etc/dropbear/authorized_keys` — the
orchestrator drops its public key in via the QEMU console. **Only safe
for ephemeral VMs**; do not flash this image to a real router.

### CI

[`.github/workflows/router-image-build.yml`](../../.github/workflows/router-image-build.yml)
runs the build on every push to `main` and on PRs touching `openwrt/`
or `scripts/vm/`. The resulting image is published as a workflow
artifact named `openwrt-wifihaven-<openwrt-version>-<sha>`, which the
VM e2e suite (#148) consumes.

### Known quirks (v1 — deferred)

- **OpenWRT's default LAN IP is `192.168.1.1`, not in `${WH_LAN_SUBNET}`.**
  Clients DHCP from the router so this still works end-to-end, but the host
  cannot reach the router over the LAN bridge by a `${WH_LAN_SUBNET}`
  address yet. Use the WAN-side SSH hostfwd (`127.0.0.1:2222`) to manage
  the router. Tracked as a follow-up.

### Manual verification (until #148 lands)

1. `scripts/vm/router-up.sh` boots without error; re-running is a no-op
   while the VM is running; `router-down.sh` is a no-op when not running.
2. `ssh -p 2222 root@127.0.0.1` lands a shell on the router (empty password).
3. From the router: `ping -c2 8.8.8.8` succeeds (WAN NAT working).
4. From the router: `ip link show eth0` is `UP`. On the host, `bridge link
   show` lists a tap attached to `${WH_LAN_BRIDGE}`.
5. `router-snapshot.sh smoke` succeeds; `qemu-img snapshot -l ...` lists it;
   `router-down.sh && router-up.sh && router-restore.sh smoke` round-trips.
