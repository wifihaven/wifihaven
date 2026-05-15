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
