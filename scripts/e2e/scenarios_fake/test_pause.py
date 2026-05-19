"""Pause enforcement against the fake API (Gate 2 canary, #683).

Mirrors the live `scenarios/test_pause.py::test_pause_blocks_and_unpause_restores`
smoke case but drives policy via direct `POST /test/snapshot` calls instead
of admin API mutations, and observes events via `GET /test/events`.

Per #683 rewrite shape:
  1. POST /test/snapshot   (profile P unpaused, device D assigned)
  2. wait for agent fetch  (etag observable via fake state)
  3. GET  /test/events     → no paused events
  4. POST /test/snapshot   (same shape but profile P paused → blocked=true)
  5. wait for agent fetch + apply
  6. assert blocked_macs contains D's MAC
  7. assert client traffic to example.com hits block page
  8. GET  /test/events     → allowed=False reason=paused event present
"""
from __future__ import annotations

import pytest

from lib.traffic import http_get
from lib.vm import router_nft_set
from lib.wait import wait_until

from .snapshot_builder import snapshot_with_assigned_device

pytestmark = pytest.mark.pause

BLOCKED_MACS_SET = "blocked_macs"


def _norm_mac(mac: str) -> str:
    return mac.lower().strip()


def _mac_in_set(mac: str) -> bool:
    members = {_norm_mac(m) for m in router_nft_set(BLOCKED_MACS_SET)}
    return _norm_mac(mac) in members


def _wait_mac_in_blocked_set(mac: str, *, present: bool, timeout_s: float = 180) -> None:
    wait_until(
        lambda: True if _mac_in_set(mac) is present else None,
        timeout_s=timeout_s,
        interval_s=2,
        description=(
            f"{mac} {'present in' if present else 'absent from'} {BLOCKED_MACS_SET}"
        ),
    )


def _wait_block_page(client, *, host: str = "example.com", timeout_s: float = 60):
    def probe():
        p = http_get(client, f"http://{host}/", timeout_s=8)
        if p.http_code == 200 and (
            "wifihaven" in p.body.lower() or "blocked" in p.body.lower()
        ):
            return p
        return None
    return wait_until(probe, timeout_s=timeout_s, interval_s=3,
                      description=f"block page response for {host}")


@pytest.mark.smoke
def test_pause_blocks_via_fake_api(router, client, fake_api):
    """Canary: pause flag in served snapshot drives MAC into blocked_macs + block page + paused event."""
    mac = client.mac

    # 1. Initial snapshot: profile assigned, not paused.
    initial = snapshot_with_assigned_device(
        etag='"sha256:fake-pause-v1"',
        mac=mac,
        paused=False,
    )
    last_id_before_initial = fake_api.latest_fetch_id()
    initial_etag = fake_api.serve_snapshot(initial)

    # 2. Wait for the agent to fetch + apply the initial snapshot.
    fake_api.wait_for_etag_served(etag=initial_etag, timeout_s=240)
    _wait_mac_in_blocked_set(mac, present=False, timeout_s=60)

    # 3. No paused events for this MAC yet.
    paused_events = fake_api.events_for_mac(mac, allowed=False, reason="paused")
    assert paused_events == [], (
        f"expected no paused events before pause, got {paused_events!r}"
    )

    # 4. Pause: same shape but profile.rules.blocked=true.
    paused = snapshot_with_assigned_device(
        etag='"sha256:fake-pause-v2"',
        mac=mac,
        paused=True,
    )
    paused_etag = fake_api.serve_snapshot(paused)

    # 5. Wait for the agent to fetch + apply.
    fake_api.wait_for_etag_served(etag=paused_etag, timeout_s=240)

    # 6. blocked_macs contains the MAC.
    _wait_mac_in_blocked_set(mac, present=True, timeout_s=180)

    # 7. Client traffic to example.com hits the block page.
    _wait_block_page(client, timeout_s=60)

    # 8. The fake captured a paused event for this MAC.
    def _has_paused_event():
        evs = fake_api.events_for_mac(mac, allowed=False, reason="paused")
        return evs or None
    wait_until(
        _has_paused_event,
        timeout_s=60,
        interval_s=2,
        description=f"paused event for {mac} in fake",
    )
