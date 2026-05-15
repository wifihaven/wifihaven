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
  - `python3` ≥ 3.11 with `python3-venv`
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

1. Pick the runner user (non-root). It must be in the `kvm` and `docker`
   groups. Create a working directory it owns, e.g. `/opt/actions-runner`.

2. Get a one-time registration token:
   ```bash
   gh api -X POST repos/sameerparekh/familydns/actions/runners/registration-token
   ```
   The response contains `token` (valid ~1 hour) and `expires_at`.

3. Download and unpack the runner inside `/opt/actions-runner`. Use the
   latest release listed on the repo's
   `Settings → Actions → Runners → New self-hosted runner` page, or
   `https://github.com/actions/runner/releases`.

4. Configure with the labels the workflow expects:
   ```bash
   ./config.sh \
     --url https://github.com/sameerparekh/familydns \
     --token <REGISTRATION_TOKEN> \
     --name familydns-kvm-1 \
     --labels self-hosted,linux,kvm \
     --work _work \
     --unattended
   ```

5. Either run interactively to smoke-test:
   ```bash
   ./run.sh
   ```
   …or install as a systemd service. A template lives at
   [`scripts/ci/kvm-runner.service`](../../scripts/ci/kvm-runner.service);
   copy it to `/etc/systemd/system/`, fill in the placeholders, then:
   ```bash
   sudo systemctl daemon-reload
   sudo systemctl enable --now kvm-runner.service
   journalctl -u kvm-runner -f
   ```

6. Verify the runner appears under
   `Settings → Actions → Runners` with the `kvm` label and status `Idle`.

7. Smoke-test via the sanity workflow:
   ```bash
   gh workflow run e2e-kvm-sanity.yml
   gh run watch
   ```

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
