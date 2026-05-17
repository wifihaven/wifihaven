# Running the VM e2e harness on Ubuntu

The `scripts/vm/` harness (issues #144, #146, #150, #226) runs the OpenWRT
router and Alpine client in QEMU/KVM. It only runs on a Linux host with
`/dev/kvm` — there is no macOS path. This page is the host-setup checklist
for an Ubuntu 24.04 box.

## One-time host setup

The packages, the bridge, and the qemu-bridge-helper permission all
need root, so do them once and forget. Everything else is unprivileged.

### Packages

```bash
sudo apt-get install -y \
    qemu-system-x86 qemu-utils \
    xorriso socat \
    binutils \
    docker.io \
    python3-venv
```

What each is for:

| package          | needed by                                                   |
|------------------|-------------------------------------------------------------|
| `qemu-system-x86`| `router-up.sh`, `client-up.sh`, `build-client-base.sh`      |
| `qemu-utils`     | `qemu-img` (overlay creation in `*-up.sh`)                  |
| `xorriso`        | `build-client-base.sh` builds the NoCloud seed ISO          |
| `socat`          | `client-down.sh` ACPI poweroff via QMP; `router-snapshot.sh`|
| `binutils`       | `openwrt/build-ipk.sh` uses `ar` to assemble the .ipk       |
| `docker.io`      | `build-router-image.sh` runs OpenWRT Image Builder in a container |
| `python3-venv`   | `scripts/e2e-vm.sh` creates `.e2e-vm-venv/` via `python3 -m venv`  |

### `/dev/kvm` access

Add your user to the `kvm` group so QEMU can open `/dev/kvm`:

```bash
sudo usermod -aG kvm $USER
```

You must then **fully log out and back in** (or restart the Claude Code
desktop app) — group membership only takes effect in shells started after
the `usermod`. Verify with `id | grep kvm`.

For a single-session workaround that doesn't require relaunching anything:

```bash
sudo setfacl -m u:$USER:rw /dev/kvm
```

This resets on reboot, and we've seen it get wiped sooner by udev/snap
hooks on some hosts — the `usermod` route is more reliable.

### LAN bridge + `qemu-bridge-helper`

`client-up.sh` attaches the client VM's LAN NIC to the `fdns-lan0` bridge
via `qemu-bridge-helper`, which is not setuid by default on Ubuntu and
which refuses to attach to bridges that aren't in `/etc/qemu/bridge.conf`.
Both need fixing once:

```bash
sudo bash -c '
  mkdir -p /etc/qemu
  echo "allow fdns-lan0" > /etc/qemu/bridge.conf
  setcap cap_net_admin+ep /usr/lib/qemu/qemu-bridge-helper
'
```

`CAP_NET_ADMIN` is the modern equivalent of `chmod u+s`; either works,
but the capability is narrower.

### Passwordless `sudo` for `ip`

`scripts/vm/lan-bridge-up.sh` runs `sudo ip link add …` and `sudo ip
link set … up` every time the router VM boots, which would prompt for a
password each run. Grant a narrow `NOPASSWD` rule for `ip` only:

```bash
sudo bash -c "
  echo '$USER ALL=(root) NOPASSWD: /usr/sbin/ip, /sbin/ip' \
    > /etc/sudoers.d/familydns-vm
  chmod 0440 /etc/sudoers.d/familydns-vm
"
```

This is the same `ip` command used by `lan-bridge-up.sh` / `lan-bridge-down.sh`
— nothing wider.

## Bringing it all up

After the one-time setup, the per-session sequence is fully unprivileged:

```bash
# 1. Build the client base image (~30 s the first time, instant after).
scripts/vm/build-client-base.sh

# 2. Build the custom OpenWRT router image with familydns-agent baked in
#    (#150, ~60 s after caches are warm). Required for the LAN to be on
#    192.168.100.0/24 per the e2e plan in #226.
scripts/vm/build-router-image.sh

# 3. Bring up the router VM. Point at the custom image with an absolute
#    path, and pick a non-conflicting host HTTP-forward port if 8080 is
#    already taken on the host (e.g. by a running wifihaven API stack).
FDNS_ROUTER_HTTP_PORT=18081 \
FDNS_ROUTER_IMAGE_PATH="$PWD/scripts/vm/.cache/openwrt-wifihaven.img" \
    scripts/vm/router-up.sh

# 4. Bring up a client with a chosen MAC.
scripts/vm/client-up.sh --mac 02:00:00:00:00:01

# 5. Drive scenarios.
scripts/vm/client-exec.sh -- dig example.com
scripts/vm/client-exec.sh -- curl -fsS http://example.com

# 6. Tear down (overlay is discarded — next client-up is a fresh boot).
scripts/vm/client-down.sh
scripts/vm/router-down.sh
```

## Known gotchas on Ubuntu

- **Port 8080 collisions.** If you already run a wifihaven deploy on the
  host (e.g. via `/home/$USER/.familydns/docker-compose.yml`), the
  router VM's default HTTP forward (`-hostfwd tcp:127.0.0.1:8080-:80`)
  will fail with `Could not set up host forwarding rule`. Override via
  `FDNS_ROUTER_HTTP_PORT=18081 scripts/vm/router-up.sh` and adjust your
  own SSH forward (`FDNS_ROUTER_SSH_PORT`) similarly if 2222 is taken.

- **Image Builder leaves root-owned files in `.cache/`.** OpenWRT's
  Image Builder runs inside a Debian container as root, and its
  `build_dir`/`staging_dir` end up owned by uid 0 on the host. The
  re-run path tries to `rm -rf` them and fails. Workaround:

  ```bash
  docker run --rm -v "$PWD/scripts/vm/.cache":/c alpine \
      sh -c 'rm -rf /c/imagebuilder /c/staging'
  ```

  before re-running `build-router-image.sh`. There's an open follow-up
  to make the build script do this itself.

- **`/dev/kvm` ACLs vanish between runs.** On some Ubuntu setups (snap
  `qemu-virgil`, certain udev configs) the `setfacl` grant gets wiped
  shortly after access. If you hit `Could not access KVM kernel module:
  Permission denied` after it was working, use the `usermod -aG kvm`
  approach above instead of `setfacl`.

- **Bridge MTU.** `lan-bridge-up.sh` creates `fdns-lan0` without setting
  an MTU; the default 1500 is fine for the e2e plan, but if you bridge
  this to a tun/tap with smaller MTU you'll see TCP stalls.

## Verifying you're ready

```bash
# Should print "ok" lines for every tool:
for t in qemu-system-x86_64 qemu-img xorrisofs socat ar docker; do
  command -v "$t" >/dev/null && echo "ok $t" || echo "MISSING $t"
done

# KVM access:
test -r /dev/kvm && test -w /dev/kvm && echo "ok /dev/kvm" || echo "MISSING /dev/kvm rw"

# Bridge + qemu-bridge-helper:
grep -q "^allow fdns-lan0\b" /etc/qemu/bridge.conf && echo "ok bridge.conf"
getcap /usr/lib/qemu/qemu-bridge-helper | grep -q cap_net_admin && echo "ok qemu-bridge-helper cap"

# Passwordless ip:
sudo -n ip -V >/dev/null 2>&1 && echo "ok NOPASSWD ip" || echo "MISSING NOPASSWD ip"
```

## See also

- [`scripts/vm/README.md`](../scripts/vm/README.md) — harness internals,
  not host setup.
- Issue #226 — the validation plan this host setup is in service of.
- Issues #144, #146, #150 — the harness pieces being validated.
