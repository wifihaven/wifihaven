# VM e2e orchestrator

End-to-end tests that drive a real OpenWRT router VM + Alpine client VM.
Two surviving tiers (the legacy monolithic live-mode suite was retired in
#656):

- **Gate 2** (`--mode=fake`) — router + client against an in-process **fake
  API** (`scripts/e2e/fake-api/`) serving snapshot goldens; scenarios in
  `scenarios_fake/`. Gates `publish-openwrt` via `e2e-vm-fake.yml`.
- **Gate 3** (`--mode=gate3`) — router against a **real staging API** (no
  fake, no local stack); one thin smoke in `gate3/`. Run by
  `e2e-vm-gate3a.yml` / `e2e-vm-gate3b.yml`.

See [`docs/testing.md`](../../docs/testing.md) for which gate a new test
belongs in.

## TL;DR

```bash
# Linux host with /dev/kvm and qemu, ~6 GB RAM free
scripts/vm/build-client-base.sh            # one-time, ~2 min
scripts/vm/build-router-image.sh           # one-time, ~5–10 min
scripts/e2e-vm.sh --mode=fake              # Gate 2 (fake API) — default mode
scripts/e2e-vm.sh --mode=fake --only blocked-domain   # one scenario
scripts/e2e-vm.sh --mode=fake --keep -- -k usage      # leave VMs up; pytest -k passthrough
```

## Architecture (Gate 2 / fake mode)

```
┌─────────── host ────────────┐
│  pytest orchestrator        │      ┌── router VM ──┐    ┌── client VM ──┐
│   ├ in-process fake API     │      │ OpenWRT 23.05 │    │ Alpine 3.22   │
│   │   (scripts/e2e/fake-api)│◀─────┤ wifihaven-    │    │               │
│   │   serves snapshot       │ HTTP │ agent baked in│◀──▶│   eth0 (LAN)  │
│   │   goldens               │      │               │ DHCP│   curl/dig   │
│   ├ vm.py (router/client    │      │ eth1 = SLIRP  │ DNS │               │
│   │   ssh + snapshots)      │      │   (10.0.2.2 → │    │   eth1 = mgmt │
│   └ fake_api fixture        │      │    host:fake) │    │   (host SSH)  │
└─────────────────────────────┘      └───────────────┘    └───────────────┘
```

Gate 3 swaps the in-process fake for a remote staging API and exercises
only the contract surface (`gate3/test_smoke.py`); it has no snapshot
goldens.

## Layout

```
scripts/e2e-vm.sh                     bash entrypoint (venv + pytest)
scripts/e2e/
  pytest.ini                          marker registry, log config
  requirements.txt                    pytest only (uses stdlib for HTTP)
  lib/                                primitive layer — shared across tiers
    paths.py                          repo-relative paths
    sh.py                             subprocess wrapper
    stack.py                          docker compose lifecycle (gate3 helpers)
    vm.py                             router + client VM ops, SSH, snapshots
    api_admin.py                      admin API client (login, profiles, devices, ...)
    api_debug.py                      /api/debug/* client (loopback only)
    enrollment.py                     create router → register → UCI provision
    wait.py                           race-free wait helpers (poll cycle, etag)
    clock.py                          set/get router VM wall clock
    traffic.py                        client-side curl/dig probes
  fake-api/                           in-process fake API (Gate 2)
  scenarios_fake/                     Gate 2 scenarios + conftest + snapshot builder
    conftest.py                       session/function fixtures, failure hooks
    snapshot_builder.py               golden snapshot construction
    test_02_allowed_browsing.py
    test_03_blocked_domain.py
    test_05_usage_in_api.py
    test_06_blocked_page.py
    test_pause.py · test_schedule.py · test_extra_blocked.py
    test_time_limit.py · test_reassignment.py
    test_blocked_mac_events.py · test_install_health.py
    test_port_alloc.py · test_snapshot_builder.py
    test_global_policy.py             suite H (#1460) — @global_allow / @global_block
  gate3/                              Gate 3 smoke + conftest
    test_smoke.py
```

## Primitive surface (lib/)

Both tiers build on these primitives. Anything stable enough to depend on
lives in `lib/`.

```python
from lib.api_admin import AdminAPI            # login(), create_profile(...), upsert_device(...), set_profile_paused(id, True)
from lib.api_debug import DebugAPI            # devices(), events(limit=...), events_for_mac(mac), time_usage()
from lib.enrollment import enroll_router      # returns EnrolledRouter(router_id, router_token, name)
from lib.vm import (
    router_up, router_down, router_snapshot, router_restore, router_ssh,
    client_up, client_down, client_exec,
    ROUTER_HOST_GATEWAY,                       # 10.0.2.2 — router's view of host
)
from lib.wait import (
    wait_until,                                # general purpose
    wait_for_router_active,                    # used after enrollment
    wait_for_next_poll,                        # lastSeenAt advances (every poll, 304 included)
    wait_for_etag_change,                      # lastEtag advances (only on policy change)
)
from lib.clock import set_router_clock, get_router_clock   # `date -s` on the router VM
from lib.traffic import http_get, dns_query                # client-side probes
```

### Tagging your scenarios

Add a marker to `pytest.ini` and decorate the test:

```python
import pytest
pytestmark = pytest.mark.pause     # then: scripts/e2e-vm.sh --mode=fake --only pause
```

Update `--only` parsing in `scripts/e2e-vm.sh` to accept the new name.

### Race-free synchronization

The agent polls policy on `CLOCK_MONOTONIC` every 60 s (#336 — wall-clock
jumps from `set_router_clock()` do **not** advance the timer). In fake mode,
gate on the snapshot the fake actually served:

```python
etag = fake_api.serve_snapshot(snap)
fake_api.wait_for_etag_served(etag=etag, timeout_s=240)   # agent fetched new policy
```

## Snapshot reuse

`router_session` (session-scoped fixture in `scenarios_fake/conftest.py`)
does the slow path once: **boot → enroll → first-poll-success → base
snapshot**. The function-scoped `router` fixture restores from the base
before each test. On dev hardware this typically gives <10 s reset between
scenarios.

## Running in CI

The harness needs `/dev/kvm`, so CI runs on a self-hosted runner labeled
`kvm`. Provisioning, runner registration, and the systemd template live in
[`docs/ops/kvm-runner.md`](../../docs/ops/kvm-runner.md). The sanity workflow
([`.github/workflows/e2e-kvm-sanity.yml`](../../.github/workflows/e2e-kvm-sanity.yml))
exercises orchestrator wiring without booting VMs and is the smoke test for a
newly registered runner.

## Running on a host without KVM

The harness requires `/dev/kvm` (no macOS support — see `scripts/vm/README.md`).
For sanity-checking the orchestrator wiring without VMs:

```bash
E2E_VM_SKIP_VMS=1 scripts/e2e-vm.sh --mode=fake
```

VM-dependent tests `pytest.skip` themselves.

## Diagnostics on failure

`pytest_runtest_makereport` (defined per tier in the relevant conftest)
attaches the router VM serial console tail and the agent's
`logread -e wifihaven` to every failed test; these appear in the standard
pytest captured-output footer.

## Known limitations / non-goals

- **OpnSense scenarios**: deferred (router-side primitives are OpenWRT-only).
- **Multi-client scenarios**: the fixtures use a single `client1` slot.
  `client_factory` accepts `name=` and `ssh_port=` so multi-client work can
  layer on without touching the existing scenarios.
- **Cross-day rollover** (time-limit D3): pending #334.
