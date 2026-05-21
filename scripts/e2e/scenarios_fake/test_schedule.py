"""Suite C — schedule enforcement (fake-mode #684).

Live-mode `scenarios/test_schedule.py` exercises both router enforcement AND
the API's TZ-aware schedule evaluator (#334/#442) by manipulating the router
wall clock and adding ScheduleRequest rows on the live API.

In fake mode the API-side evaluator is out of the picture: the fake serves
whatever `blocked` / `blockReason` we POST. So the schedule scenario
collapses to three router-side observables:

  - In-window snapshot (blocked=True, blockReason=Schedule) → MAC in
    blocked_macs + event reason=schedule.
  - Out-of-window snapshot (blocked=False) → MAC absent, HTTP succeeds.
  - Pause overrides schedule precedence (#406) — observed at the wire level
    as `blockReason=Paused` vs `blockReason=Schedule`. The router emits the
    matching event reason; the precedence math itself lives in the API and
    is locked in by live-mode `test_schedule.py::test_pause_overrides_schedule`.

C3 (cross-midnight TZ math) and C5 (all-day-Monday) are intentionally NOT
migrated — they test API-side TZ logic the fake doesn't reproduce. Tracked
in live-mode test_schedule.py.
"""
from __future__ import annotations

import pytest

from ._observers import (
    mac_in_blocked_set,
    wait_block_page,
    wait_event_for_mac,
    wait_http_succeeds,
    wait_mac_in_blocked_set,
)
from .snapshot_builder import SnapshotBuilder

pytestmark = pytest.mark.schedule

PROFILE_ID = 600


def _snap(*, etag: str, mac: str, blocked: bool, reason: str | None) -> dict:
    return (
        SnapshotBuilder()
        .add_profile(
            id=PROFILE_ID, name="e2e-sched",
            blocked=blocked,
            block_reason=reason if blocked else None,
        )
        .add_device(mac=mac, name="e2e-sched-dev", profile_id=PROFILE_ID)
        .build(etag=etag)
    )


# ── C1 — in-window blocks ──────────────────────────────────────────────────


def test_in_window_blocks(router, client, fake_api):
    """C1: schedule covering the current window → MAC blocked, reason=schedule."""
    etag = fake_api.serve_snapshot(
        _snap(etag='"sha256:sched-in-v1"', mac=client.mac, blocked=True, reason="Schedule"),
    )
    fake_api.wait_for_etag_served(etag=etag, timeout_s=240)

    wait_mac_in_blocked_set(client.mac, present=True, timeout_s=180)
    # Drive HTTP from the client so the agent emits a connection_attempt
    # event we can match. Without traffic, blocked_macs is populated but no
    # blocked-reason event fires (router emits events on actual packets).
    wait_block_page(client, timeout_s=60)
    wait_event_for_mac(fake_api, client.mac, allowed=False, reason="schedule", timeout_s=60)


# ── C2 — out-of-window allows ──────────────────────────────────────────────


def test_out_of_window_allows(router, client, fake_api):
    """C2: out-of-window snapshot (blocked=False) → HTTP works, MAC not blocked."""
    etag = fake_api.serve_snapshot(
        _snap(etag='"sha256:sched-out-v1"', mac=client.mac, blocked=False, reason=None),
    )
    fake_api.wait_for_etag_served(etag=etag, timeout_s=240)

    assert not mac_in_blocked_set(client.mac)
    wait_http_succeeds(client, timeout_s=60)


# ── C6 — smoke: in→out transition ──────────────────────────────────────────


@pytest.mark.smoke
def test_in_then_out_smoke(router, client, fake_api):
    """C6 (smoke): in-window blocks, then sliding the snapshot to out-of-window
    unblocks within one poll. Single device, two snapshot swaps — fastest
    signal."""
    e1 = fake_api.serve_snapshot(
        _snap(etag='"sha256:sched-smoke-in"', mac=client.mac, blocked=True, reason="Schedule"),
    )
    fake_api.wait_for_etag_served(etag=e1, timeout_s=240)
    wait_mac_in_blocked_set(client.mac, present=True, timeout_s=180)
    wait_block_page(client, timeout_s=60)
    wait_event_for_mac(fake_api, client.mac, allowed=False, reason="schedule", timeout_s=60)

    e2 = fake_api.serve_snapshot(
        _snap(etag='"sha256:sched-smoke-out"', mac=client.mac, blocked=False, reason=None),
    )
    fake_api.wait_for_etag_served(etag=e2, timeout_s=240)
    wait_mac_in_blocked_set(client.mac, present=False, timeout_s=180)
    wait_http_succeeds(client, timeout_s=60)
