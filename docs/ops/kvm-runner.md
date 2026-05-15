# KVM self-hosted GitHub Actions runner

The VM e2e harness (`scripts/e2e-vm.sh`, see `scripts/e2e/README.md`) requires
`/dev/kvm`. GitHub-hosted runners do not expose KVM, so the VM e2e workflow
(#433, gated on this issue) must run on a **self-hosted runner** that we
provision ourselves.

> **Operator action required.** Provisioning the KVM host, installing the
> runner, and registering it with GitHub are manual steps. The repo ships the
> sanity workflow (`.github/workflows/e2e-kvm-sanity.yml`) and this runbook;
> until the runner is online and labeled `kvm`, the workflow has nowhere to
> dispatch.

## Host requirements

- Linux with `/dev/kvm` accessible (KVM acceleration). Bare metal or a host
  with nested-virt enabled. Tested target: Ubuntu 24.04.
- ≥ 6 GB free RAM (`scripts/e2e/README.md` baseline) plus headroom for the
  Go/Node/Java toolchains pulled in by adjacent jobs.
- ≥ 20 GB free disk for qcow2 overlays, the OpenWRT image cache, the venv,
  and docker image cache.
- Required packages:
  - `qemu-system-x86` (provides `qemu-system-x86_64`)
  - `qemu-utils` (provides `qemu-img`)
  - One of `xorriso` (preferred) / `genisoimage` / `mkisofs`
  - `socat`, `openssl`, `curl`, `iproute2`
  - `docker.io` (or upstream Docker CE)
  - `python3` ≥ 3.11 **and** `python3-venv` (Ubuntu splits `ensurepip` into a separate package; `scripts/e2e-vm.sh` creates `.e2e-vm-venv/` per checkout and fails without it)
  - `binutils` (for `ar`, used by `openwrt/build-ipk.sh`)

Use `docs/vm-e2e-ubuntu.md` as the per-host bootstrap checklist: it covers
`/dev/kvm` group membership, the `fdns-lan0` bridge, `qemu-bridge-helper`
capabilities, and the narrow `NOPASSWD` rule for `ip`.

### Suggested hosts

- A dedicated Linux workstation already on the team's network.
- Bare-metal cloud (e.g. Hetzner AX-series). Hetzner cloud VPS does **not**
  expose nested-virt; only the dedicated/bare-metal lines do.
- GitHub-hosted "larger runners" advertise nested virtualization on some
  SKUs. We have **not** verified this end-to-end with the harness; treat as
  experimental.

## One-time host bootstrap

After packages and KVM access are in place (see `docs/vm-e2e-ubuntu.md`),
pre-build the VM caches once so the first sanity run isn't a 10-minute cold
start:

```bash
# As the runner user, from a checkout of this repo:
scripts/vm/build-client-base.sh      # → scripts/vm/.cache/client-base.qcow2
scripts/vm/build-router-image.sh     # → scripts/vm/.cache/openwrt-familydns.img
```

Cache locations (kept across runs, safe to delete to force a rebuild):

| path                                    | what                                   |
|-----------------------------------------|----------------------------------------|
| `scripts/vm/.cache/client-base.qcow2`   | Alpine client base image               |
| `scripts/vm/.cache/openwrt-familydns.img` | Custom OpenWRT image w/ agent baked in |
| `scripts/vm/.cache/imagebuilder/`       | OpenWRT Image Builder working tree     |
| `.e2e-vm-venv/`                         | pytest venv (created lazily)           |

## Registering the runner with GitHub

The runner installs under `~/actions-runner` in the runner user's home directory
— no sudo needed for the install itself.

1. Pick the runner user (non-root). It must be in the `kvm` and `docker`
   groups and have the NOPASSWD `ip` rule from `docs/vm-e2e-ubuntu.md`.
   The natural choice is whichever user you already bootstrapped the VM
   harness with.

2. Get a one-time registration token (from any machine with `gh` auth — the
   token's only valid ~1 hour, so do this right before step 4):
   ```bash
   gh api -X POST repos/sameerparekh/familydns/actions/runners/registration-token --jq .token
   ```

3. As the runner user, download and unpack the latest runner release. Check
   <https://github.com/actions/runner/releases> for the current version.
   ```bash
   mkdir -p ~/actions-runner && cd ~/actions-runner
   RUNNER_VER=2.321.0    # ← update to current
   curl -fsSL -o runner.tar.gz \
     "https://github.com/actions/runner/releases/download/v${RUNNER_VER}/actions-runner-linux-x64-${RUNNER_VER}.tar.gz"
   tar xzf runner.tar.gz && rm runner.tar.gz
   ```

4. Configure with the labels the workflow expects (paste the token from #2):
   ```bash
   cd ~/actions-runner
   ./config.sh \
     --url https://github.com/sameerparekh/familydns \
     --token <REGISTRATION_TOKEN> \
     --name familydns-kvm-1 \
     --labels self-hosted,linux,kvm \
     --work _work \
     --unattended
   ```

5. Smoke-test interactively before installing as a service:
   ```bash
   ./run.sh
   ```
   In another shell, dispatch the sanity workflow and watch it land:
   ```bash
   gh workflow run e2e-kvm-sanity.yml -R sameerparekh/familydns
   gh run watch -R sameerparekh/familydns
   ```
   Ctrl-C `./run.sh` once the workflow finishes.

6. Install as a systemd service so the runner survives reboots. A template
   lives at [`scripts/ci/kvm-runner.service`](../../scripts/ci/kvm-runner.service).
   This is the only step that needs sudo:
   ```bash
   sudo cp scripts/ci/kvm-runner.service /etc/systemd/system/kvm-runner.service
   sudo sed -i \
     -e "s|<RUNNER_USER>|$USER|g" \
     -e "s|<RUNNER_HOME>|$HOME/actions-runner|g" \
     /etc/systemd/system/kvm-runner.service
   sudo systemctl daemon-reload
   sudo systemctl enable --now kvm-runner.service
   journalctl -u kvm-runner -f
   ```

6. Verify the runner appears under
   `Settings → Actions → Runners` with the `kvm` label and status `Idle`.

## Runtime privileges

At runtime, the runner needs **no sudo**. Setup-time sudo is limited to
installing the systemd unit (step 6 above) — everything during job
execution is unprivileged:

| capability needed at runtime | how it's granted (one-time) |
|---|---|
| `/dev/kvm` read/write | runner user is in the `kvm` group |
| docker socket | runner user is in the `docker` group |
| attach taps to `fdns-lan0` | `cap_net_admin` on `qemu-bridge-helper` (`setcap`) + `/etc/qemu/bridge.conf` allowlists the bridge |
| `ip link add/set …` for the LAN bridge | NOPASSWD sudo rule in `/etc/sudoers.d/familydns-vm`, scoped to `/usr/sbin/ip` only |
| outbound HTTPS to GitHub | none (standard outbound) |

All of these are part of the one-time host bootstrap in
[`docs/vm-e2e-ubuntu.md`](../vm-e2e-ubuntu.md). If you've already developed
the VM e2e harness on this host as a regular user, you're done.

## Trust model

The repo is public, but the workflow that uses this runner triggers only on
`push: main` and `workflow_dispatch` — never on `pull_request`. A self-hosted
runner is registered to a specific repo, so a fork pushing to *its* main
does **not** reach our runner; the only way fork code can run on this box is
if a maintainer merges it to upstream `main`. Trust gate = code review.

## Maintenance

- **Runner self-updates** are on by default. To pin a version, pass
  `--disableupdate` to `config.sh` and update manually with `./svc.sh stop`,
  re-download, `./svc.sh start`.
- **OS patching**: standard `unattended-upgrades` is fine. Reboots interrupt
  in-flight jobs — schedule outside business hours, or drain the runner
  first (`./svc.sh stop`).
- **Token rotation**: registration tokens expire ~1 hour after issuance and
  are only needed at `config.sh` time. The runner uses its own long-lived
  credential thereafter (`.credentials`). To rotate, `./config.sh remove
  --token <REMOVAL_TOKEN>` (fetch via
  `gh api -X POST repos/.../actions/runners/remove-token`) then re-register.
- **Cache hygiene**: prune docker images/volumes weekly. The qcow2 caches
  are stable; only delete when bumping pinned versions in
  `scripts/vm/config.sh` or `scripts/vm/versions.sh`.
- **Disk monitoring**: VM overlays accumulate under `scripts/vm/.run/`. The
  harness cleans these on success; on aborted runs they linger. A nightly
  `find scripts/vm/.run -mtime +1 -delete` (on the runner work tree) is a
  reasonable guardrail.

## See also

- [`scripts/vm/README.md`](../../scripts/vm/README.md) — VM harness internals.
- [`scripts/e2e/README.md`](../../scripts/e2e/README.md) — orchestrator
  architecture and the `E2E_VM_SKIP_VMS=1` sanity mode.
- [`docs/vm-e2e-ubuntu.md`](../vm-e2e-ubuntu.md) — host setup checklist for
  Ubuntu 24.04 (apt packages, bridge, `qemu-bridge-helper` caps, etc.).
- Issue #149 — this runner. Issue #433 — the full enforcement-suite workflow
  that this unblocks.
