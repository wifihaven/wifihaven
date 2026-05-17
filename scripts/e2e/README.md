# VM e2e orchestrator

End-to-end tests that drive a real OpenWRT router VM + Alpine client VM
against a real API stack (docker compose). Implements #148.

## TL;DR

```bash
# Linux host with /dev/kvm and qemu, docker, ~6 GB RAM free
scripts/vm/build-client-base.sh           # one-time, ~2 min
scripts/vm/build-router-image.sh          # one-time, ~5–10 min
scripts/e2e-vm.sh                         # full suite
scripts/e2e-vm.sh --only blocked-domain   # one scenario
scripts/e2e-vm.sh --keep -- -k usage      # leave VMs/stack up; pytest -k passthrough
```

## Architecture

```
┌─────────── host ────────────┐
│  docker compose             │      ┌── router VM ──┐    ┌── client VM ──┐
│   ├ postgres                │      │ OpenWRT 23.05 │    │ Alpine 3.22   │
│   └ api  (WIFIHAVEN_DEBUG=1)│◀─────┤ familydns-    │    │               │
│       :18080 (host port)    │ HTTP │ agent baked in│◀──▶│   eth0 (LAN)  │
│                             │      │               │ DHCP│   curl/dig   │
│  pytest orchestrator        │      │ eth1 = SLIRP  │ DNS │               │
│   ├ admin API client        │      │   (10.0.2.2 → │    │   eth1 = mgmt │
│   ├ debug API client        │      │    host:18080)│    │   (host SSH)  │
│   ├ vm.py (router/client    │      └───────────────┘    └───────────────┘
│   │   ssh + snapshots)      │           ▲                       ▲
│   └ enrollment.py           │           └── ssh hostfwd          └── ssh hostfwd
└─────────────────────────────┘               127.0.0.1:2222          127.0.0.1:2223
```

## Layout

```
scripts/e2e-vm.sh                     bash entrypoint (venv + pytest)
scripts/e2e/
  pytest.ini                          marker registry, log config
  requirements.txt                    pytest only (uses stdlib for HTTP)
  conftest.py                         session/function fixtures, failure hooks
  lib/                                primitive layer — public API for #346/#345
    paths.py                          repo-relative paths
    sh.py                             subprocess wrapper
    stack.py                          docker compose lifecycle
    vm.py                             router + client VM ops, SSH, snapshots
    api_admin.py                      admin API client (login, profiles, devices, ...)
    api_debug.py                      /api/debug/* client (loopback only)
    enrollment.py                     create router → register → UCI provision
    wait.py                           race-free wait helpers (poll cycle, etag)
    clock.py                          set/get router VM wall clock
    traffic.py                        client-side curl/dig probes
  scenarios/
    test_01_enrollment.py
    test_02_allowed_browsing.py
    test_03_blocked_domain.py
    test_04_daily_limit.py
    test_05_usage_in_api.py
    test_06_blocked_page.py
```

## Primitive surface (for #346 / #345)

The downstream enforcement and resilience suites build on these primitives.
Anything that's stable enough for them to depend on lives in `lib/`.

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
pytestmark = pytest.mark.pause     # then: scripts/e2e-vm.sh --only pause (after wiring)
```

Update `--only` parsing in `scripts/e2e-vm.sh` to accept the new name.

### Race-free synchronization

The agent polls policy on `CLOCK_MONOTONIC` every 60 s (#336 — wall-clock
jumps from `set_router_clock()` do **not** advance the timer). After
mutating policy:

```python
wait_for_etag_change(admin, router.router_id, timeout_s=120)   # agent fetched new policy
```

After mutating clock or wanting a generic next-cycle gate (e.g. confirm a
404 → 304 round-trip):

```python
wait_for_next_poll(admin, router.router_id, timeout_s=90)
```

## Snapshot reuse

`router_session` (session-scoped fixture) does the slow path once:
**boot → enroll → first-poll-success → `savevm e2e-base`**. The function-scoped
`router` fixture restores from `e2e-base` before each test. On dev hardware
this typically gives <10 s reset between scenarios.

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
E2E_VM_SKIP_VMS=1 scripts/e2e-vm.sh
```

VM-dependent tests `pytest.skip` themselves. Stack-only paths still run.

## Diagnostics on failure

`pytest_runtest_makereport` attaches three sections to every failed test:

1. Router VM serial console tail (`scripts/vm/.run/router/console.log`)
2. `logread -e familydns | tail -n 80` from the router (agent log)
3. `docker compose logs api --tail 120`

These appear in the standard pytest captured-output footer.

## Known limitations / non-goals

- **OpnSense scenarios**: deferred (router-side primitives are OpenWRT-only).
- **Multi-client scenarios**: the v1 fixtures use a single `client1` slot.
  `client_factory` accepts `name=` and `ssh_port=` so multi-client work can
  layer on without touching the existing scenarios; `client-up.sh` does
  *not* yet auto-allocate non-overlapping SSH ports.
- **Faster polls**: tests assume the default 60 s policy poll and 300 s
  usage-report intervals. Scenarios that need faster cadence (esp. #346's
  daily-limit suite) should override `policy_poll_interval` /
  `usage_report_interval` via UCI in their setup. We don't do that globally
  here so the orchestrator exercises the production cadence.
- **`/api/debug/router_status` not present**: we use `lastSeenAt` from
  `GET /api/admin/routers` as the agent-poll signal instead. If a richer
  debug surface lands later, prefer it for finer-grained sync.
- **Time-limit (scenario 4) is slow**: ~5–6 minutes because we wait for the
  agent's natural usage-report cycle. The other five scenarios complete in
  well under a minute each after enrollment.
