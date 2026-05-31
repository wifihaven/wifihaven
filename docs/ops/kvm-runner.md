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
`/dev/kvm` group membership, the `wh-lan0` bridge, `qemu-bridge-helper`
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
scripts/vm/build-router-image.sh     # → scripts/vm/.cache/openwrt-wifihaven.img
```

Cache locations (kept across runs, safe to delete to force a rebuild):

| path                                    | what                                   |
|-----------------------------------------|----------------------------------------|
| `scripts/vm/.cache/client-base.qcow2`   | Alpine client base image               |
| `scripts/vm/.cache/openwrt-wifihaven.img` | Custom OpenWRT image w/ agent baked in |
| `scripts/vm/.cache/imagebuilder/`       | OpenWRT Image Builder working tree     |
| `.e2e-vm-venv/`                         | pytest venv (created lazily)           |

## Sizing: runner count vs. bridge pool — load-bearing invariant

> **Invariant (#1163): `runner-count = bridge-pool-size − 1`.** The trailing
> bridge in the pool is reserved as a manual-headroom slot for ad-hoc runs
> (`scripts/e2e-kvm-sanity`, operator debugging, the keep-staging-warm
> cron). Because `wh_pick_lan_bridge`
> ([`scripts/vm/lib.sh`](../../scripts/vm/lib.sh)) hands out the
> **lowest-numbered free bridge first** and there are never more than
> `N − 1` concurrent jobs (capped by runner-instance count), the
> highest-numbered bridge stays free in steady state.
>
> **Touch both knobs together.** If you scale runners, scale bridges. If
> you scale bridges, scale runners. The matching workflow knob is
> `max-parallel` in the VM e2e workflows (which equals `runner-count`).
>
> Today's sizing on api.lan: **5 bridges (`wh-lan0..wh-lan4`), 4 runner
> instances (`actions-runner-1..4`), `max-parallel: 4`.**

The runner-instance count is the global host-concurrency governor: GitHub
queues jobs naturally when no runner is free, so once all 4 runners are
busy, sibling jobs wait. No workflow-level `concurrency:` group is needed
across workflows — the runner pool *is* the queue.

## Registering the runners with GitHub

Each runner instance installs under `~/actions-runner-N` in the runner
user's home directory (N = 1..4 today). The install itself needs no sudo;
the per-instance systemd unit needs sudo once.

1. Pick the runner user (non-root). It must be in the `kvm` and `docker`
   groups and have the NOPASSWD `ip` rule from `docs/vm-e2e-ubuntu.md`.
   The natural choice is whichever user you already bootstrapped the VM
   harness with.

2. As the runner user, download and unpack the latest runner release once
   into a tarball — you'll unpack a fresh copy into each instance dir.
   Check <https://github.com/actions/runner/releases> for the current
   version.
   ```bash
   RUNNER_VER=2.321.0    # ← update to current
   curl -fsSL -o /tmp/runner.tar.gz \
     "https://github.com/actions/runner/releases/download/v${RUNNER_VER}/actions-runner-linux-x64-${RUNNER_VER}.tar.gz"
   ```

3. For each instance `N` in `1..4`, fetch a **fresh** one-time registration
   token (each is valid ~1 hour and is single-use) and configure a separate
   work directory. **All instances carry the same labels** — `kvm` is what
   the workflows match on:
   ```bash
   for N in 1 2 3 4; do
     mkdir -p ~/actions-runner-$N && cd ~/actions-runner-$N
     tar xzf /tmp/runner.tar.gz
     TOKEN=$(gh api -X POST \
       repos/wifihaven/wifihaven/actions/runners/registration-token --jq .token)
     ./config.sh \
       --url https://github.com/wifihaven/wifihaven \
       --token "$TOKEN" \
       --name "wifihaven-kvm-$N" \
       --labels self-hosted,linux,kvm \
       --work _work \
       --unattended
   done
   ```

4. Smoke-test one instance interactively before installing the systemd
   units:
   ```bash
   cd ~/actions-runner-1 && ./run.sh
   ```
   In another shell, dispatch the sanity workflow and watch it land:
   ```bash
   gh workflow run e2e-kvm-sanity.yml -R wifihaven/wifihaven
   gh run watch -R wifihaven/wifihaven
   ```
   Ctrl-C `./run.sh` once the workflow finishes.

5. Install the systemd **template** unit so each runner instance survives
   reboots. Template lives at
   [`scripts/ci/kvm-runner@.service`](../../scripts/ci/kvm-runner@.service);
   `%i` expands to the instance index, so a single file enables N services
   (`kvm-runner@1`, `kvm-runner@2`, …). This is the only step that needs
   sudo:
   ```bash
   sudo cp 'scripts/ci/kvm-runner@.service' /etc/systemd/system/kvm-runner@.service
   sudo sed -i \
     -e "s|<RUNNER_USER>|$USER|g" \
     -e "s|<RUNNER_HOME_BASE>|$HOME/actions-runner|g" \
     /etc/systemd/system/kvm-runner@.service
   sudo systemctl daemon-reload
   sudo systemctl enable --now kvm-runner@{1,2,3,4}.service
   journalctl -u 'kvm-runner@*' -f
   ```

6. Verify all 4 runners appear under
   `Settings → Actions → Runners` with the `kvm` label and status `Idle`.

## Bridge pool (`/etc/qemu/bridge.conf`)

The 4 job-runners share a host-wide pool of LAN bridges. The pool is
created by `scripts/vm/lan-bridge-pool-bootstrap.sh`, which also appends
`allow wh-lanN` entries to `/etc/qemu/bridge.conf`.

```bash
# Default pool size 5 → wh-lan0..wh-lan4. Override with WH_LAN_BRIDGE_POOL_SIZE.
sudo scripts/vm/lan-bridge-pool-bootstrap.sh
```

Resulting `/etc/qemu/bridge.conf` (on top of any pre-existing entries):

```
allow wh-lan0   # job slot 0
allow wh-lan1   # job slot 1
allow wh-lan2   # job slot 2
allow wh-lan3   # job slot 3
allow wh-lan4   # manual headroom (e2e-kvm-sanity, keep-staging-warm, debug)
```

The picker (`scripts/vm/lib.sh::wh_pick_lan_bridge`) treats the pool as a
uniform set, picking the lowest-numbered free bridge under a host-wide
flock. **There is no code-level "reserved" flag on `wh-lan4`** — the
headroom guarantee comes from the runner-count = pool-size − 1 invariant.
If you bump runner count to 5, `wh-lan4` becomes a job slot and you must
add `wh-lan5` to preserve headroom.

## Verifying parallelism

After all four runners are online, confirm the matrix arms actually run
concurrently with a `workflow_dispatch`:

```bash
gh workflow run e2e-vm-fake.yml -R wifihaven/wifihaven
RUN_ID=$(gh run list -R wifihaven/wifihaven \
  --workflow=e2e-vm-fake.yml --limit 1 --json databaseId --jq '.[0].databaseId')
gh run view "$RUN_ID" -R wifihaven/wifihaven --json jobs \
  --jq '.jobs[] | {name, startedAt, completedAt}'
```

Both matrix arms' `startedAt` timestamps should overlap (typically within
a few seconds of each other) instead of one arm starting only after the
other completes. If they're back-to-back, only one runner is online or
`max-parallel` is below the matrix size.

## Runtime privileges

At runtime, the runner needs **no sudo**. Setup-time sudo is limited to
installing the systemd unit (step 6 above) — everything during job
execution is unprivileged:

| capability needed at runtime | how it's granted (one-time) |
|---|---|
| `/dev/kvm` read/write | runner user is in the `kvm` group |
| docker socket | runner user is in the `docker` group |
| attach taps to `wh-lan0` | `cap_net_admin` on `qemu-bridge-helper` (`setcap`) + `/etc/qemu/bridge.conf` allowlists the bridge |
| `ip link add/set …` for the LAN bridge | NOPASSWD sudo rule in `/etc/sudoers.d/wifihaven-vm`, scoped to `/usr/sbin/ip` only |
| outbound HTTPS to GitHub | none (standard outbound) |

All of these are part of the one-time host bootstrap in
[`docs/vm-e2e-ubuntu.md`](../vm-e2e-ubuntu.md). If you've already developed
the VM e2e harness on this host as a regular user, you're done.

> **Don't re-add `NoNewPrivileges=true` to the systemd unit.** It refuses
> the setuid transition for the `sudo ip link …` call above and breaks VM
> bring-up with `sudo: The "no new privileges" flag is set …`. The unit
> file in `scripts/ci/kvm-runner@.service` deliberately omits it; the
> NOPASSWD sudoers rule on `/usr/sbin/ip` is already the security gate.

## Trust model

The repo is public. The self-hosted KVM runner is used by the surviving VM
e2e workflows — Gate 2 (`.github/workflows/e2e-vm-fake.yml`) and Gate 3
(`e2e-vm-gate3a.yml` / `e2e-vm-gate3b.yml`). (The legacy monolithic
`e2e-vm.yml` live-mode suite was retired in #656.) These are triggered by:

1. `workflow_call` — invoked from the Master CD pipelines
   (`master-router.yml` gates `publish-openwrt` on Gate 2 + Gate 3a;
   `master-api-ui.yml` gates `publish-api` on Gate 3b). These run only off
   `push: main` via the Master CD workflows, never off fork PRs.
2. `workflow_dispatch` — manual runs from the Actions tab against any ref,
   requires maintainer-equivalent permissions.

Each job additionally carries the
`if: github.event.pull_request.head.repo.full_name == github.repository`
guard, so even if a workflow grew a `pull_request` trigger, fork PRs would
be refused execution on the self-hosted runner.

Trust gate for the runner = (a) jobs run only from Master CD off `main` or
maintainer-triggered `workflow_dispatch`, (b) the same-repo head-branch
guard on any `pull_request`, (c) code review before any branch is pushed to
this repo.

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
