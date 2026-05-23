"""Fake-mode pytest fixtures (#683).

Parallel-track lifecycle: replaces the docker-compose API stack with the
in-process fake from `scripts/e2e/fake-api/`. The router VM lifecycle
(qemu boot, base-snapshot reuse) is identical to live mode — see
`scripts/e2e/conftest.py` for that side.

Scope hierarchy:
  session  → fake API (background asyncio thread), router VM boot + enroll,
             base snapshot
  function → router restored from base snapshot, fresh client VM, fake
             state reset to initial snapshot
"""
from __future__ import annotations

import asyncio
import logging
import os
import socket
import sys
import threading
import time
import uuid
from pathlib import Path

import pytest

# Make the fake-api package importable. It lives at scripts/e2e/fake-api/.
FAKE_API_PKG = Path(__file__).resolve().parents[1] / "fake-api"
if str(FAKE_API_PKG) not in sys.path:
    sys.path.insert(0, str(FAKE_API_PKG))

from fake_api.app import make_app  # noqa: E402
from fake_api.state import State  # noqa: E402

from lib.enrollment import EnrolledRouter, enroll_router_against_fake  # noqa: E402
from lib.fake_client import FakeAPIClient  # noqa: E402
from lib.vm import (  # noqa: E402
    Client,
    ROUTER_HOST_GATEWAY,
    client_down,
    client_up,
    router_down,
    router_restore,
    router_serial_log,
    router_snapshot,
    router_ssh,
    router_up,
)
from lib.wait import wait_for_client_dns  # noqa: E402

log = logging.getLogger(__name__)

FAKE_API_HOST = os.environ.get("WH_FAKE_API_HOST", "127.0.0.1")
ROUTER_IMAGE_PATH = os.environ.get("WH_ROUTER_IMAGE_PATH")
KEEP_VMS = os.environ.get("E2E_VM_KEEP", "0") == "1"
SKIP_VMS = os.environ.get("E2E_VM_SKIP_VMS", "0") == "1"

BASE_SNAPSHOT = "e2e-base-fake"


def alloc_free_port(host: str = "127.0.0.1") -> int:
    """Bind to port 0, read the kernel-assigned port, release immediately.

    There's a brief race between release and the next bind, but the kernel
    will not re-hand-out the same port within that window unless the host is
    under extreme port pressure — fine for a single-process session-scoped
    allocation. The point is to dodge orphans from prior CI runs on the same
    self-hosted runner that pin the previous hard-coded port (#902).
    """
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind((host, 0))
        return s.getsockname()[1]


# ── fake API server (background asyncio thread) ──────────────────────────────


class _FakeServer:
    """Run the aiohttp fake in a background asyncio event loop.

    The fake is async (aiohttp); the pytest harness is sync. Owning a
    dedicated event loop in a daemon thread lets us start/stop it cleanly
    without `pytest-aiohttp` infecting the rest of the harness.
    """

    def __init__(self, host: str, port: int) -> None:
        self.host = host
        self.port = port
        self.state: State = State.fresh()
        self._loop: asyncio.AbstractEventLoop | None = None
        self._thread: threading.Thread | None = None
        self._runner = None  # aiohttp.web.AppRunner

    @property
    def base_url_host(self) -> str:
        """URL the test process uses to reach the fake (loopback)."""
        return f"http://{self.host}:{self.port}"

    @property
    def base_url_router(self) -> str:
        """URL the router VM uses to reach the fake (SLIRP gateway)."""
        return f"http://{ROUTER_HOST_GATEWAY}:{self.port}"

    def start(self) -> None:
        ready = threading.Event()
        err: list[BaseException] = []

        def _run() -> None:
            try:
                loop = asyncio.new_event_loop()
                asyncio.set_event_loop(loop)
                self._loop = loop
                loop.run_until_complete(self._start_async(ready))
                loop.run_forever()
            except Exception as e:  # noqa: BLE001
                err.append(e)
                ready.set()

        t = threading.Thread(target=_run, name="fake-api", daemon=True)
        t.start()
        self._thread = t
        if not ready.wait(timeout=20):
            raise RuntimeError("fake API never became ready")
        if err:
            raise err[0]
        log.info("fake API listening on %s", self.base_url_host)

    async def _start_async(self, ready: threading.Event) -> None:
        from aiohttp import web

        app = make_app(self.state)
        runner = web.AppRunner(app)
        await runner.setup()
        site = web.TCPSite(runner, self.host, self.port)
        await site.start()
        self._runner = runner
        ready.set()

    def stop(self) -> None:
        if self._loop is None:
            return
        loop = self._loop
        runner = self._runner

        async def _shutdown() -> None:
            if runner is not None:
                await runner.cleanup()

        try:
            fut = asyncio.run_coroutine_threadsafe(_shutdown(), loop)
            fut.result(timeout=10)
        except Exception as e:  # noqa: BLE001
            log.warning("fake API shutdown errored: %s", e)
        loop.call_soon_threadsafe(loop.stop)
        if self._thread is not None:
            self._thread.join(timeout=5)


# ── session-scoped infrastructure ────────────────────────────────────────────


@pytest.fixture(scope="session")
def fake_server() -> _FakeServer:
    env_port = os.environ.get("WH_FAKE_API_PORT")
    port = int(env_port) if env_port else alloc_free_port(FAKE_API_HOST)
    server = _FakeServer(FAKE_API_HOST, port)
    server.start()
    try:
        yield server
    finally:
        server.stop()


@pytest.fixture(scope="session")
def fake_api(fake_server) -> FakeAPIClient:
    return FakeAPIClient(fake_server.base_url_host)


@pytest.fixture(scope="session")
def router_session(fake_server, fake_api) -> EnrolledRouter:
    """Boot router VM, enroll against the fake, snapshot the result."""
    if SKIP_VMS:
        pytest.skip("E2E_VM_SKIP_VMS=1 set; skipping VM-dependent scenarios")
    router_up(image_path=ROUTER_IMAGE_PATH)
    enrolled = enroll_router_against_fake(
        register_url=f"{fake_server.base_url_host}/api/router/register",
        api_url_for_router=fake_server.base_url_router,
        name=f"e2e-fake-router-{uuid.uuid4().hex[:8]}",
    )
    _wait_for_fake_slirp_ready(port=fake_server.port, timeout_s=120)
    # Confirm the agent has issued at least one policy poll before snapshotting.
    fake_api.wait_for_policy_fetch(timeout_s=180)
    router_snapshot(BASE_SNAPSHOT)
    log.info("fake-mode base snapshot taken: %s", BASE_SNAPSHOT)
    try:
        yield enrolled
    finally:
        if not KEEP_VMS:
            router_down()


# ── function-scoped lifecycle ────────────────────────────────────────────────


@pytest.fixture()
def router(router_session, fake_server, fake_api) -> EnrolledRouter:
    """Restore the router VM and reset the fake before each scenario."""
    router_restore(BASE_SNAPSHOT)
    fake_api.reset()
    _wait_for_fake_slirp_ready(port=fake_server.port, timeout_s=60)
    return router_session


@pytest.fixture()
def client_factory():
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
    c = client_factory()
    wait_for_client_dns(c, timeout_s=30)
    return c


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
            r = router_ssh("logread -e wifihaven | tail -n 80", check=False, timeout=10)
            sections.append(("router agent log", (r.stdout or "") + (r.stderr or "")))
        except Exception as e:  # noqa: BLE001
            sections.append(("router agent log", f"<unavailable: {e}>"))
        try:
            fake = item.funcargs.get("fake_api")
            client_obj = item.funcargs.get("client")
            if fake is not None and client_obj is not None:
                evs = fake.events_for_mac(client_obj.mac)
                lines = [f"fake events for mac {client_obj.mac} ({len(evs)} total):"]
                for e in evs[-20:]:
                    lines.append(
                        f"  ts={e.get('ts')!r} allowed={e.get('allowed')!r} "
                        f"reason={e.get('reason')!r} destIp={e.get('destIp')!r}"
                    )
                sections.append(("fake events", "\n".join(lines)))
        except Exception as e:  # noqa: BLE001
            sections.append(("fake events", f"<unavailable: {e}>"))
        for title, body in sections:
            rep.sections.append((title, body))


# ── helpers ──────────────────────────────────────────────────────────────────


def _gen_mac() -> str:
    import random
    return "02:e2:fa:%02x:%02x:%02x" % (
        random.randint(0, 255),
        random.randint(0, 255),
        random.randint(0, 255),
    )


def _wait_for_fake_slirp_ready(*, port: int, timeout_s: float = 60, interval_s: float = 1.0) -> None:
    """Probe the router→fake path until it responds.

    The fake's /api/router/policy returns 401 without a Bearer token, which
    is fine for the SLIRP readiness check — any non-000 HTTP code means
    NAT is hot and the route through SLIRP works.
    """
    probe = (
        f"curl -sS -o /dev/null -m 3 -w '%{{http_code}}' "
        f"http://{ROUTER_HOST_GATEWAY}:{port}/api/router/policy 2>/dev/null || echo 000"
    )
    deadline = time.monotonic() + timeout_s
    last = ""
    attempts = 0
    started = time.monotonic()
    while time.monotonic() < deadline:
        attempts += 1
        res = router_ssh(probe, timeout=10, check=False)
        last = (res.stdout or "").strip()
        if last and last != "000":
            log.info(
                "fake SLIRP ready after %.1fs (%d attempts, code=%s)",
                time.monotonic() - started, attempts, last,
            )
            return
        time.sleep(interval_s)
    raise TimeoutError(
        f"fake API never reachable from router after {timeout_s}s "
        f"({attempts} attempts, last code={last!r})"
    )


