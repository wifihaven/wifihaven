# VM e2e harness

Scripted QEMU/KVM setup for end-to-end testing of the OpenWRT side of
FamilyDNS. The big picture is in #106; this directory is the implementation
of #144 (router VM) and #146 (client VM).

## Host requirements

- Linux with `/dev/kvm` accessible (KVM-accelerated boots).
- `qemu-system-x86_64`, `qemu-img`
- One of `xorrisofs`, `genisoimage`, or `mkisofs` (for the NoCloud seed)
- `openssl`, `curl`, `ssh` (client), `iproute2` (for `ip`)
- `socat` (used by `client-down.sh` to send a clean ACPI shutdown via QMP — optional, the script falls back to signals)
- The LAN bridge from the router VM (#144) must already be up before
  `client-up.sh` runs.

The harness is not expected to work on macOS — no KVM. Use a Linux dev host
or CI runner.

## Shared configuration

[`config.sh`](config.sh) defines the names that span the router VM and the
client VM: the LAN bridge name, the LAN subnet, the Alpine image version, and
file paths. **Both halves of the harness read from here.** If you need to
change a bridge name or subnet, change it in this one file.

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
| eth0  | `${FDNS_LAN_BRIDGE}` bridge | LAN side. DHCP from router VM. **All real traffic** (DNS, HTTP, etc.) goes here. |
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

Tracked in #144 — not in this directory yet.
