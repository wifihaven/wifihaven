"""Race-free wait helpers. Every helper takes a timeout and a poll interval,
returns True on success, raises TimeoutError on miss.

The agent polls policy every 60 s by default. To make scenarios fast, the
test framework can drop the poll interval at boot via UCI override; until
then, callers should pass timeouts large enough to span at least one cycle.
"""
from __future__ import annotations

import logging
import time
from datetime import datetime
from typing import Callable, TypeVar

from .api_admin import AdminAPI

log = logging.getLogger(__name__)

T = TypeVar("T")


def wait_until(
    pred: Callable[[], T | None],
    *,
    timeout_s: float,
    interval_s: float = 1.0,
    description: str = "condition",
) -> T:
    """Poll `pred` until it returns truthy; return the value. Raises on timeout."""
    deadline = time.monotonic() + timeout_s
    last_exc: Exception | None = None
    while time.monotonic() < deadline:
        try:
            v = pred()
            if v:
                return v
        except Exception as e:  # noqa: BLE001
            last_exc = e
        time.sleep(interval_s)
    suffix = f" (last error: {last_exc!r})" if last_exc else ""
    raise TimeoutError(f"timed out waiting for {description}{suffix}")


def _parse_iso(ts: str | None) -> datetime | None:
    if not ts:
        return None
    # Accept both naive and trailing-Z forms.
    return datetime.fromisoformat(ts.replace("Z", "+00:00"))


def wait_for_router_slirp_ready(
    *,
    api_port: int,
    timeout_s: float = 60,
    interval_s: float = 1.0,
) -> None:
    """Gate scenarios on the router's outbound NAT being usable.

    QEMU SLIRP keeps NAT/DHCP state in the host process, not in guest memory.
    A `loadvm` restores the guest but the SLIRP table is empty, so the agent's
    first post-restore TCP open to 10.0.2.2 fails with status 0. We poll a
    cheap HTTP probe from inside the router until SLIRP rehydrates and the
    API answers (any HTTP response counts — 400/401/404 all mean the path
    works; we only reject status 000).
    """
    # Imported here to avoid a circular import (vm imports wait indirectly via
    # other helpers in some call paths).
    from .vm import router_ssh

    # Two-phase probe: (a) router→host service via SLIRP-internal routing
    # (10.0.2.2 is the SLIRP gateway → host loopback), (b) router→internet via
    # SLIRP→host network outbound, masqueraded the same way client traffic is.
    # Scenarios that curl example.com from the client need (b) to be hot, not
    # just (a). Both must return a non-000 HTTP code.
    probe = (
        "code_a=$(curl -sS -o /dev/null -m 3 "
        f"-w '%{{http_code}}' http://10.0.2.2:{api_port}/api/auth/login "
        "-H 'content-type: application/json' -d '{}' 2>/dev/null || echo 000); "
        "code_b=$(curl -sS -o /dev/null -m 5 "
        "-w '%{http_code}' http://example.com/ 2>/dev/null || echo 000); "
        "echo \"$code_a $code_b\""
    )
    deadline = time.monotonic() + timeout_s
    last = ""
    attempts = 0
    started = time.monotonic()
    while time.monotonic() < deadline:
        attempts += 1
        res = router_ssh(probe, timeout=15, check=False)
        last = (res.stdout or "").strip()
        parts = last.split()
        if len(parts) == 2 and parts[0] != "000" and parts[1] != "000":
            log.info("router SLIRP ready after %.1fs (%d attempts, codes=%s)",
                     time.monotonic() - started, attempts, last)
            return
        time.sleep(interval_s)
    raise TimeoutError(
        f"router SLIRP NAT never rehydrated after {timeout_s}s "
        f"({attempts} attempts, last codes={last!r})"
    )


def wait_for_client_dns(
    client,
    *,
    host: str = "example.com",
    timeout_s: float = 30,
    interval_s: float = 1.0,
) -> None:
    """Gate test traffic on the client's view of DNS being resolvable.

    The router's dnsmasq forwards upstream via the WAN — same SLIRP path as
    the agent. After a `loadvm` restore, the first upstream DNS forward can
    time out (SLIRP UDP table empty) which leaves the client's curl seeing
    HTTPCODE:000. Poll an actual `dig` from inside the client until it
    returns an answer.
    """
    from .vm import client_exec

    deadline = time.monotonic() + timeout_s
    attempts = 0
    started = time.monotonic()
    last = ""
    cmd = ["sh", "-c", f"dig +time=2 +tries=1 +short {host} | head -n1"]
    while time.monotonic() < deadline:
        attempts += 1
        res = client_exec(client, cmd, timeout=8, check=False)
        last = (res.stdout or "").strip()
        # Accept any A-record-looking answer (dotted-quad). dig prints a
        # single line per record; the first non-empty stdout line is enough.
        if last and last[0].isdigit():
            log.info("client DNS for %s ready after %.1fs (%d attempts, answer=%s)",
                     host, time.monotonic() - started, attempts, last.split()[0])
            return
        time.sleep(interval_s)
    raise TimeoutError(
        f"client DNS for {host} never resolved after {timeout_s}s "
        f"({attempts} attempts, last={last!r})"
    )


def wait_for_router_active(
    admin: AdminAPI,
    router_id: str,
    *,
    timeout_s: float = 90,
    interval_s: float = 2.0,
) -> dict:
    """Wait until the API reports the router has checked in (lastSeenAt set).

    Used after enrollment to confirm the agent's first policy poll landed.
    """
    def check():
        r = admin.get_router(router_id)
        if r and r.get("lastSeenAt"):
            return r
        return None
    return wait_until(check, timeout_s=timeout_s, interval_s=interval_s,
                      description=f"router {router_id} to appear active")


def wait_for_next_poll(
    admin: AdminAPI,
    router_id: str,
    *,
    timeout_s: float = 90,
    interval_s: float = 2.0,
) -> dict:
    """Wait for `lastSeenAt` to advance past its current value.

    This is the canonical 'has the agent picked up my policy change yet?'
    primitive. Capture the row first, mutate policy, then call this.

    NOTE: lastSeenAt is updated on EVERY successful poll (304 included), so
    this advances even when the policy didn't change. That's the right
    semantics for synchronizing with the poll cycle — but if you need to know
    that the agent saw a *new* policy specifically, also compare `lastEtag`.
    """
    baseline = admin.get_router(router_id) or {}
    baseline_seen = _parse_iso(baseline.get("lastSeenAt"))

    def check():
        r = admin.get_router(router_id)
        if not r:
            return None
        cur = _parse_iso(r.get("lastSeenAt"))
        if cur and (baseline_seen is None or cur > baseline_seen):
            return r
        return None

    return wait_until(check, timeout_s=timeout_s, interval_s=interval_s,
                      description=f"router {router_id} next poll cycle")


def wait_for_etag_change(
    admin: AdminAPI,
    router_id: str,
    *,
    timeout_s: float = 120,
    interval_s: float = 2.0,
) -> dict:
    """Wait for the agent to fetch a *new* policy (lastEtag changed)."""
    baseline = admin.get_router(router_id) or {}
    baseline_etag = baseline.get("lastEtag")

    def check():
        r = admin.get_router(router_id)
        if r and r.get("lastEtag") and r.get("lastEtag") != baseline_etag:
            return r
        return None
    return wait_until(check, timeout_s=timeout_s, interval_s=interval_s,
                      description=f"router {router_id} to apply new policy etag")
