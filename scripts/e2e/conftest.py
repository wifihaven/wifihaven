"""Pytest fixtures wiring the primitive layer into per-scenario lifecycles.

Scope hierarchy:
  session  → API stack (docker compose), router VM boot, base snapshot
  function → router restored from base snapshot, fresh client VM, scratch
             profile + device on the API

Each scenario is independently runnable via `pytest -k <name>` or
`scripts/e2e-vm.sh --only <name>`.
"""
from __future__ import annotations

import logging
import os
import uuid

import pytest

from lib.api_admin import AdminAPI
from lib.api_debug import DebugAPI
from lib.enrollment import EnrolledRouter, enroll_router
from lib.stack import bring_up
from lib.vm import (
    Client,
    client_down,
    client_up,
    router_down,
    router_restore,
    router_serial_log,
    router_snapshot,
    router_up,
)
from lib.wait import (
    wait_for_client_dns,
    wait_for_etag_change,
    wait_for_router_active,
    wait_for_router_slirp_ready,
)

log = logging.getLogger(__name__)

API_PORT = int(os.environ.get("E2E_VM_API_PORT", "18080"))
ROUTER_IMAGE_PATH = os.environ.get("FDNS_ROUTER_IMAGE_PATH")  # custom image w/ agent baked in
KEEP_VMS = os.environ.get("E2E_VM_KEEP", "0") == "1"
KEEP_STACK = os.environ.get("E2E_VM_KEEP_STACK", "0") == "1"
SKIP_STACK = os.environ.get("E2E_VM_SKIP_STACK", "0") == "1"
SKIP_VMS = os.environ.get("E2E_VM_SKIP_VMS", "0") == "1"

# Snapshot taken after first successful boot+enrollment. Each scenario
# restores from here so we don't pay enrollment cost N times.
BASE_SNAPSHOT = "e2e-base"


# ── session-scoped infrastructure ────────────────────────────────────────────


@pytest.fixture(scope="session")
def stack():
    if SKIP_STACK:
        # Useful when iterating: assume the operator already has the stack up.
        from lib.stack import StackHandle
        from lib.paths import REPO_ROOT
        from pathlib import Path
        yield StackHandle(
            project="(external)",
            api_port=API_PORT,
            base_url=f"http://127.0.0.1:{API_PORT}",
            compose_files=[Path(REPO_ROOT, "docker", "docker-compose.yml")],
            env=os.environ.copy(),
        )
        return
    handle = bring_up(api_port=API_PORT)
    try:
        yield handle
    finally:
        if KEEP_STACK:
            log.warning("E2E_VM_KEEP_STACK=1, leaving stack up at %s", handle.base_url)
        else:
            handle.teardown()


@pytest.fixture(scope="session")
def admin(stack) -> AdminAPI:
    a = AdminAPI(stack.base_url)
    a.login()
    return a


@pytest.fixture(scope="session")
def debug_api(stack) -> DebugAPI:
    return DebugAPI(stack.base_url)


@pytest.fixture(scope="session")
def router_session(stack, admin) -> EnrolledRouter:
    """Boot the router VM once, enroll it, snapshot the result."""
    if SKIP_VMS:
        pytest.skip("E2E_VM_SKIP_VMS=1 set; skipping VM-dependent scenarios")
    router_up(image_path=ROUTER_IMAGE_PATH)
    enrolled = enroll_router(admin, name=f"e2e-router-{uuid.uuid4().hex[:8]}", api_port=API_PORT)
    wait_for_router_active(admin, enrolled.router_id, timeout_s=120)
    router_snapshot(BASE_SNAPSHOT)
    log.info("base snapshot taken: %s", BASE_SNAPSHOT)
    try:
        yield enrolled
    finally:
        if not KEEP_VMS:
            router_down()
        # Note: we deliberately don't delete the router record from the API —
        # it's part of the disposable stack and goes away with `compose down`.


# ── function-scoped lifecycle ────────────────────────────────────────────────


@pytest.fixture()
def router(router_session) -> EnrolledRouter:
    """Restore the router VM from the base snapshot before each scenario.

    This gives every scenario the same starting state: enrolled, agent
    running, no profiles or devices touching this MAC yet.
    """
    router_restore(BASE_SNAPSHOT)
    # `loadvm` restores guest memory but QEMU SLIRP's NAT/DHCP table is
    # process-local and starts empty. Drive an HTTP probe from inside the
    # router until the host API answers — that gates on the agent's own
    # reachability path, which is what every scenario depends on. Without
    # this, scenarios racing the agent's first post-restore poll see
    # status 0 ("policy.fetch: unexpected status 0") and time out.
    wait_for_router_slirp_ready(api_port=API_PORT, timeout_s=60)
    return router_session


@pytest.fixture()
def client_factory():
    """Yields a callable that boots a client VM and tracks it for teardown."""
    booted: list[str] = []

    def _boot(*, mac: str | None = None, name: str = "client1", ssh_port: int = 2223) -> Client:
        chosen_mac = mac or _gen_mac()
        c = client_up(mac=chosen_mac, name=name, ssh_port=ssh_port)
        booted.append(name)
        return c

    yield _boot

    if not KEEP_VMS:
        for name in booted:
            client_down(name)


@pytest.fixture()
def client(client_factory) -> Client:
    """Default single-client fixture for scenarios that don't need multiple."""
    c = client_factory()
    # The router-side `_wait_for_client_lan` gates on DHCP + a single
    # `getent hosts` answer, but post-loadvm the upstream DNS path through
    # SLIRP can flap (UDP NAT entries time out independently of TCP). Block
    # here on a fresh `dig` so the test's first curl doesn't race.
    wait_for_client_dns(c, timeout_s=30)
    return c


# ── ephemeral profile + device ───────────────────────────────────────────────


@pytest.fixture()
def scratch_profile(admin):
    """Create a profile, yield it, delete on teardown."""
    p = admin.create_profile(name=f"e2e-{uuid.uuid4().hex[:8]}")
    pid = p["profile"]["id"] if "profile" in p else p["id"]
    yield {"id": pid, "raw": p}
    try:
        admin.delete_profile(pid)
    except Exception as e:  # noqa: BLE001
        log.warning("failed to delete scratch profile %s: %s", pid, e)


@pytest.fixture()
def scratch_device(admin, scratch_profile, client, router_session):
    admin.upsert_device(mac=client.mac, name=f"e2e-dev-{client.name}", profile_id=scratch_profile["id"])
    # The router boots with a default-deny boot.nft skeleton (#308) and the
    # agent only knows about MACs that appear in the policy snapshot it last
    # fetched. Without waiting for the agent to pick up this device, the
    # very first packet from the new MAC is dropped and curl returns
    # HTTPCODE:000 — the failure mode that masked scenarios 2/3/5/6.
    # Without this gate, the very first packet from the new MAC is dropped
    # (boot.nft default-deny + agent has no per-MAC accept rule yet). The
    # device upsert above changes the snapshot's `devicePolicies` map, which
    # bumps the policy etag — so wait_for_etag_change reliably gates on the
    # agent having fetched + applied the snapshot that includes our MAC.
    wait_for_etag_change(admin, router_session.router_id, timeout_s=180)
    # PolicyApply restarts dnsmasq (#341). The first DNS query immediately
    # after restart races and curl reports HTTPCODE:000. Re-gate the client
    # on a fresh resolver answer before yielding to the scenario.
    wait_for_client_dns(client, timeout_s=30)
    yield {"mac": client.mac, "profile_id": scratch_profile["id"]}
    try:
        admin.delete_device(client.mac)
    except Exception as e:  # noqa: BLE001
        log.warning("failed to delete scratch device %s: %s", client.mac, e)


# ── diagnostics on failure ───────────────────────────────────────────────────


@pytest.hookimpl(hookwrapper=True)
def pytest_runtest_makereport(item, call):
    outcome = yield
    rep = outcome.get_result()
    if rep.failed and call.when == "call":
        sections = []
        try:
            sections.append(("router serial (tail)", router_serial_log(tail=80)))
        except Exception as e:  # noqa: BLE001
            sections.append(("router serial", f"<unavailable: {e}>"))
        try:
            from lib.vm import router_ssh
            r = router_ssh("logread -e wifihaven | tail -n 80", check=False, timeout=10)
            sections.append(("router agent log", (r.stdout or "") + (r.stderr or "")))
        except Exception as e:  # noqa: BLE001
            sections.append(("router agent log", f"<unavailable: {e}>"))
        # Stack logs
        try:
            stack = item.funcargs.get("stack")
            if stack is not None:
                sections.append(("api logs (tail)", stack.logs(service="api", tail=120)))
        except Exception as e:  # noqa: BLE001
            sections.append(("api logs", f"<unavailable: {e}>"))
        try:
            client_obj = item.funcargs.get("client")
            debug = item.funcargs.get("debug_api")
            if client_obj is not None and debug is not None:
                from lib.api_debug import event_hostname
                evs = debug.events_for_mac(client_obj.mac)
                lines = [f"events for mac {client_obj.mac} ({len(evs)} total):"]
                for e in evs[-20:]:
                    # The connection_event wire schema (#391) wraps the hostname
                    # in a tagged `host` union; reading the legacy flat
                    # `hostname` key always returned None and made every event
                    # look unattributed in the failure dump (#480).
                    lines.append(
                        f"  ts={e.get('ts')} allowed={e.get('allowed')} "
                        f"reason={e.get('reason')!r} hostname={event_hostname(e) or None!r} "
                        f"destIp={e.get('destIp')!r}"
                    )
                sections.append(("debug events", "\n".join(lines)))
        except Exception as e:  # noqa: BLE001
            sections.append(("debug events", f"<unavailable: {e}>"))
        for title, body in sections:
            rep.sections.append((title, body))


# ── helpers ──────────────────────────────────────────────────────────────────


def _gen_mac() -> str:
    """Locally-administered, unicast MAC in the 02:e2:e2:* range."""
    import random
    return "02:e2:e2:%02x:%02x:%02x" % (
        random.randint(0, 255),
        random.randint(0, 255),
        random.randint(0, 255),
    )
