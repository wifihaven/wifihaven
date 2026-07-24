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
from lib.wan_health import (  # noqa: E402
    CONTROL_APEX_HOSTS,
    smoke_check_nil_signature,
    wan_lease_flake_signature,
)

log = logging.getLogger(__name__)

FAKE_API_HOST = os.environ.get("WH_FAKE_API_HOST", "127.0.0.1")
ROUTER_IMAGE_PATH = os.environ.get("WH_ROUTER_IMAGE_PATH")
KEEP_VMS = os.environ.get("E2E_VM_KEEP", "0") == "1"
SKIP_VMS = os.environ.get("E2E_VM_SKIP_VMS", "0") == "1"

# Upstream the router VM's dnsmasq forwards to — the runner's LAN resolver
# (the household wifihaven gateway), NOT external public DNS. See the rationale
# at its use site in `router_session` (#1935). Override via env if the
# self-hosted runner sits on a different LAN.
ROUTER_LAN_RESOLVER = os.environ.get("WH_ROUTER_LAN_RESOLVER", "192.168.10.1")

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

    # Pin the router VM's dnsmasq upstream to the **LAN resolver** — the
    # self-hosted runner's gateway, the household wifihaven router at
    # ROUTER_LAN_RESOLVER (reached via SLIRP → host) — and `noresolv=1` to
    # ignore the WAN-DHCP-supplied resolver list. NOT external public DNS.
    #
    # Why not 1.1.1.1 / 8.8.8.8 (the previous value)? Two reasons, both #1935:
    #   1. #1911's network-wide `blockEncryptedDns` toggle drops FORWARDED LAN
    #      traffic on :53 to the curated public-resolver IPs (1.1.1.1 / 8.8.8.8 /
    #      9.9.9.9 / …). The e2e VMs are LAN devices behind the household router
    #      (SLIRP → host → gateway), so once that toggle is enabled on the
    #      gateway the harness's own resolution would be killed by the very
    #      feature under test. The LAN resolver survives it: the gateway answers
    #      on the VM's behalf via its OWN upstream (the output hook), which the
    #      forward-hook drop never touches — a LAN device querying the gateway
    #      keeps resolving.
    #   2. The public-DNS path (VM → SLIRP → host → gateway → ISP → 8.8.8.8) was
    #      intermittently unreliable in CI — `dig @8.8.8.8` timeouts and dnsmasq
    #      "smoke check … nil for tiktok.com", red-gating router releases even
    #      though the host itself resolved fine. The gateway is a directly-
    #      attached, far shorter path.
    #
    # `rebind_domain=wifihaven.net` whitelists our zone from dnsmasq's
    # `--stop-dns-rebind` protection. OpenWRT ships `rebind_protection=1`, which
    # discards upstream answers in the "private" set (RFC1918, loopback,
    # link-local, **plus the RFC 5737 documentation ranges** incl. TEST-NET-1
    # 192.0.2.0/24). The Suite G (#1351) leaf A record is 192.0.2.10 (chosen by
    # #1360 for its never-routed guarantee), so without the carve-out dnsmasq
    # logs `possible DNS-rebind attack detected: e2e-edge.wifihaven.net` and
    # drops it. The carve-out is needed at BOTH hops: here on the VM, AND on the
    # gateway (applied out-of-band: `uci add_list
    # dhcp.@dnsmasq[0].rebind_domain='wifihaven.net'` on ROUTER_LAN_RESOLVER) —
    # the gateway strips the leaf before the VM ever sees it otherwise. See
    # #1935.
    router_ssh(
        "uci -q delete dhcp.@dnsmasq[0].server; "
        f"uci add_list dhcp.@dnsmasq[0].server='{ROUTER_LAN_RESOLVER}'; "
        "uci set dhcp.@dnsmasq[0].noresolv='1'; "
        "uci add_list dhcp.@dnsmasq[0].rebind_domain='wifihaven.net'; "
        "uci commit dhcp; "
        "/etc/init.d/dnsmasq restart",
        timeout=30,
    )

    enrolled = enroll_router_against_fake(
        register_url=f"{fake_server.base_url_host}/api/router/register",
        api_url_for_router=fake_server.base_url_router,
        name=f"e2e-fake-router-{uuid.uuid4().hex[:8]}",
    )
    _wait_for_fake_slirp_ready(port=fake_server.port, timeout_s=120)
    # Confirm the agent has issued at least one policy poll before snapshotting.
    fake_api.wait_for_policy_fetch(timeout_s=180)
    # Take the base snapshot on a warm WAN so restores start from a healthy
    # upstream (#2390). Larger budget here — a cold base snapshot would poison
    # every restore, and this runs once per session (not per scenario).
    _wait_for_router_wan_healthy(timeout_s=120)
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
    # Heal a lost/cold WAN lease so the scenario runs on a healthy upstream
    # instead of flaking on the #2390 guest-WAN-DHCP boot race.
    _wait_for_router_wan_healthy()
    return router_session


@pytest.fixture()
def client_factory():
    # Names of clients that were *started* (even if client_up raised mid-setup)
    # so the finalizer can unconditionally tear them down and leave no orphan.
    # Tracked separately from a "booted" list so a setup failure that leaves
    # a partially-started client still gets cleaned up (#1286).
    started: list[str] = []

    def _boot(*, mac: str | None = None, name: str = "client1", ssh_port: int | None = None) -> Client:
        chosen_mac = mac or _gen_mac()
        # Record the name before calling client_up so the finalizer tears it
        # down even if client_up raises (e.g. SSH-wait timeout after qemu start).
        started.append(name)
        c = client_up(mac=chosen_mac, name=name, ssh_port=ssh_port)
        return c

    yield _boot

    if not KEEP_VMS:
        for name in started:
            try:
                client_down(name)
            except Exception:  # noqa: BLE001
                log.warning("client_factory teardown: client_down(%r) raised; ignoring", name)


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
        # #2001: the on-disk snapshot etag + the live per-MAC drop rules. The
        # absence of these two facts (was policy.json BASE or PAUSED? was any
        # wh_drop installed?) is exactly what made the ws apply-on-push flake so
        # hard to diagnose — capture them so any future enforcement/snapshot
        # divergence is a one-look read rather than a re-run.
        try:
            r = router_ssh(
                "echo 'policy.json etag:'; "
                # The etag value is itself a quoted HTTP etag (\"\\\"sha256:..\\\"\"),
                # so match to the field delimiter (comma), not the next quote, or we
                # truncate at the escaped inner quote and never show BASE vs PAUSED.
                "grep -o '\"etag\":[^,]*' /etc/wifihaven/policy.json 2>/dev/null "
                "|| echo '(policy.json absent/unreadable)'; "
                "echo 'wh_drop rules:'; "
                "nft list table inet wifihaven 2>/dev/null | grep -F 'wh_drop:' "
                "|| echo '(no wh_drop rules)'",
                check=False, timeout=10,
            )
            sections.append(("router policy.json + nft drops", (r.stdout or "") + (r.stderr or "")))
        except Exception as e:  # noqa: BLE001
            sections.append(("router policy.json + nft drops", f"<unavailable: {e}>"))
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


# ── Router WAN warm-up + heal (#2390) ────────────────────────────────────────
#
# The router VM's WAN NIC is qemu user-mode (SLIRP) networking with an internal
# DHCP server. After a `loadvm` restore the NIC re-DHCPs and the SLIRP
# UDP-forward table starts cold; on the shared KVM host, occasionally udhcpc
# loses the lease outright (`udhcpc: no lease, failing`) so the router has no
# WAN upstream. The agent's policy.apply smoke check then resolves its probe
# host to "nil", the resolved_/eb_/bl_ nft sets never populate, and whichever
# scenario runs next flakes on a block-page / fallback assertion (1 random fail
# of ~51). `wait_for_client_dns` already gates the CLIENT on the same cold-SLIRP
# warm-up; this does the ROUTER side and, unlike a passive wait, actively HEALS
# a lost lease (`ifup wan`) so the scenario runs on a healthy WAN instead of
# being skipped or failed.

def _router_upstream_resolves() -> bool:
    """True iff the router's own resolver answers a control host — the
    post-restore WAN path (lease + SLIRP UDP-forward table + upstream) is warm.

    Uses the shared CONTROL_APEX_HOSTS: resolving either proves WAN lease +
    SLIRP UDP-forward + upstream are all live — exactly the condition a scenario
    needs. They are egress-dependent on purpose (there is no shorter honest
    proof the upstream works); a persistent external-egress outage is the
    separate #2034 concern handled per-test, not here."""
    for host in CONTROL_APEX_HOSTS:
        # NB: keep the regex out of the f-string — its `{1,3}` quantifiers would
        # be parsed as f-string fields. Adjacent-literal concat side-steps that.
        cmd = (
            f"nslookup {host} 127.0.0.1 2>/dev/null | sed -n '/^Name:/,$p' | "
            r"grep -oE '([0-9]{1,3}\.){3}[0-9]{1,3}' | head -n1"
        )
        try:
            res = router_ssh(cmd, check=False, timeout=10)
        except Exception:  # noqa: BLE001
            # A transient SSH timeout/hiccup mid-heal must NOT abort the fixture
            # (run() raises TimeoutExpired even with check=False) — treat it as
            # "not warm yet" so the caller keeps polling/kicking.
            continue
        if (res.stdout or "").strip():
            return True
    return False


def _wan_flake_note() -> str:
    """Diagnostic tag for the heal log: read the router's agent log for the
    #2390 flake signature so a heal that fires in CI records WHY the WAN was
    cold (udhcpc no-lease vs policy.apply smoke-check nil)."""
    try:
        r = router_ssh(
            "logread -e wifihaven | grep -E 'udhcpc: *no lease|smoke check failed' "
            "| tail -n 20",
            check=False, timeout=10,
        )
        blob = r.stdout or ""
    except Exception:  # noqa: BLE001
        return "router log unavailable"
    if smoke_check_nil_signature(blob):
        return "root cause: policy.apply smoke-check nil (no upstream)"
    if wan_lease_flake_signature(blob):
        return "root cause: udhcpc no-lease"
    return "no #2390 flake signature in log"


def _wait_for_router_wan_healthy(*, timeout_s: float = 60, kick_interval_s: float = 20) -> None:
    """Retry until the router's WAN upstream is healthy, healing it if needed.

    Poll the router's own resolver for a control host; if it stays cold, re-kick
    `ifup wan` to force a fresh DHCP lease (the #2390 fix — heal, don't skip).
    Returns as soon as the upstream resolves — the transient flake heals within a
    few seconds of a kick, so the happy path costs one nslookup. If it never
    warms within the budget — a persistent WAN/egress problem, not the transient
    boot flake — log and proceed: the scenario's own assertions and the #2034
    egress guard then speak, so a genuine outage still surfaces rather than being
    hidden.

    The per-scenario budget is kept modest on purpose: a persistent outage must
    not multiply into a >75m Gate-timeout cancellation across ~51 scenarios
    (worst case ≈ 51 × timeout_s), so keep timeout_s small here; the
    once-per-session base-snapshot warm-up passes a larger budget."""
    deadline = time.monotonic() + timeout_s
    started = time.monotonic()
    # Give the natural post-restore warm-up (cf. wait_for_client_dns) a grace
    # window before forcibly kicking the interface.
    last_kick = started
    attempts = 0
    while time.monotonic() < deadline:
        attempts += 1
        if _router_upstream_resolves():
            if attempts > 1:
                log.info(
                    "router WAN upstream healthy after %.1fs (%d attempts)",
                    time.monotonic() - started, attempts,
                )
            return
        now = time.monotonic()
        if now - last_kick >= kick_interval_s:
            log.warning(
                "router WAN upstream cold (attempt %d) — re-kicking `ifup wan` [%s]",
                attempts, _wan_flake_note(),
            )
            try:
                router_ssh("ifup wan", check=False, timeout=30)
            except Exception:  # noqa: BLE001
                pass  # best-effort heal; keep polling regardless
            last_kick = now
        time.sleep(3)
    log.warning(
        "router WAN upstream never warmed after %.0fs (%d attempts); proceeding "
        "— scenario assertions + #2034 egress guard will speak [%s]",
        timeout_s, attempts, _wan_flake_note(),
    )


