"""#1103 — MAC-level packet drops observability.

The bug surfaced live on 2026-05-26: when a kid's profile exhausts its daily
cap, the router puts the MAC in `@blocked_macs` and packets get dropped at
the firewall. No DNS lookup ever reaches dnsmasq, so `/api/connection-events`
goes silent and the admin has no visual signal that the device is blocked.

This scenario locks in the new attribution path:

  1. Pause the profile → drops appear with reason=Paused.
  2. Time-limit exhaustion → reason=TimeLimit (covered conceptually; full
     drive-the-clock variant deferred — the pause + schedule legs cover the
     attribution wire path).
  3. Schedule window → reason=Schedule.
  4. Unmanaged-MAC default block (#374) → reason=Unmanaged.
  5. Absence-after-unblock — counters stop incrementing after a poll cycle.
  6. Cross-check: while blocked, connection_events stays empty for the MAC
     while mac_drops populates. Guards against a future refactor that
     accidentally starts logging blocked-MAC queries to connection_events.
"""
from __future__ import annotations

import uuid

import pytest

from lib.traffic import http_get
from lib.wait import wait_for_etag_change, wait_until

pytestmark = pytest.mark.mac_drops


def _has_drop(admin, mac: str, reason: str) -> bool:
    page = admin.mac_drops_series(bucket="1h", hours=2, macs=[mac])
    for row in page.get("rows", []):
        if (row.get("mac") or "").lower() == mac.lower() and row.get("reason") == reason:
            if (row.get("packets") or 0) > 0:
                return True
    return False


def _wait_drop_present(admin, mac: str, reason: str, *, timeout_s: float = 180):
    return wait_until(
        lambda: True if _has_drop(admin, mac, reason) else None,
        timeout_s=timeout_s,
        interval_s=5,
        description=f"mac_drops row for {mac} reason={reason}",
    )


def _fire_blocked_traffic(client, *, count: int = 5):
    """Hit something so the firewall has packets to drop. We don't care about
    the response — the goal is to make the nft drop counter increment."""
    for _ in range(count):
        http_get(client, "http://example.com/", timeout_s=4)


def test_paused_profile_surfaces_mac_drop_with_reason_paused(
    router, client, scratch_device, scratch_profile, admin,
):
    """Pause Kids → fire traffic at device → mac_drops shows reason=Paused."""
    profile_id = scratch_profile["id"]
    mac = client.mac

    admin.set_profile_paused(profile_id, True)
    wait_for_etag_change(admin, router.router_id, timeout_s=240)

    # Drive a few HTTP requests so packets enter the per-MAC drop counter rule.
    _fire_blocked_traffic(client, count=5)
    _wait_drop_present(admin, mac, "Paused", timeout_s=180)

    # Cross-check: connection_events stays empty for this MAC while blocked.
    # If a future refactor starts logging blocked-MAC queries, the assertion
    # below fails — forcing a deliberate decision rather than silent drift.
    logs = admin.logs(mac=mac, hours=1)
    blocked_log_rows = [l for l in logs if (l.get("mac") or "").lower() == mac.lower()]
    assert not blocked_log_rows, (
        "Regression: connection_events recorded rows for a firewall-blocked MAC. "
        "mac_drops is supposed to be the *only* signal for these — see #1103."
    )

    # Now unpause and confirm drops stop incrementing within one poll.
    admin.set_profile_paused(profile_id, False)
    wait_for_etag_change(admin, router.router_id, timeout_s=240)

    # Snapshot current packets; after the unblock poll the value should not climb.
    def _packets_for_paused() -> int:
        page = admin.mac_drops_series(bucket="1h", hours=2, macs=[mac])
        total = 0
        for row in page.get("rows", []):
            if row.get("reason") == "Paused":
                total += row.get("packets") or 0
        return total

    baseline = _packets_for_paused()
    _fire_blocked_traffic(client, count=5)
    # Wait a full poll interval, then verify no growth.
    import time
    time.sleep(75)
    after = _packets_for_paused()
    assert after == baseline, (
        f"Absence-after-unblock failed: paused drops still incrementing after "
        f"unpause (baseline={baseline} after={after}). See #1103 step 5."
    )


def test_schedule_window_surfaces_mac_drop_with_reason_schedule(
    router, client, scratch_device, scratch_profile, admin,
):
    """Open a near-now schedule window → mac_drops shows reason=Schedule."""
    profile_id = scratch_profile["id"]
    mac = client.mac

    # Wide-open window so we don't race the clock during the test.
    admin.add_schedule(profile_id, {
        "name": "e2e-mac-drops",
        "days": ["mon", "tue", "wed", "thu", "fri", "sat", "sun"],
        "startTime": "00:00",
        "endTime": "23:59",
        "tz": "UTC",
    })
    wait_for_etag_change(admin, router.router_id, timeout_s=240)
    _fire_blocked_traffic(client, count=5)
    _wait_drop_present(admin, mac, "Schedule", timeout_s=180)


def test_unmanaged_default_block_surfaces_mac_drop_with_reason_unmanaged(
    router, client, admin,
):
    """unprofiled MAC under unmanagedMacPolicy.policy=block → reason=Unmanaged."""
    # Flip the household policy to block; remember to restore.
    settings = admin._request("GET", "/api/household-settings")
    old_policy = settings.get("unmanagedMacPolicy", {"policy": "allow", "blockPage": True})
    admin._request(
        "PATCH", "/api/household-settings",
        body={"unmanagedMacPolicy": {"policy": "block", "blockPage": True}},
    )
    wait_for_etag_change(admin, router.router_id, timeout_s=240)
    try:
        # The fixture's client already exists with no profile assignment —
        # under policy=block the snapshot emits BlockRules with
        # blockReason=Unmanaged for it.
        mac = client.mac
        _fire_blocked_traffic(client, count=5)
        _wait_drop_present(admin, mac, "Unmanaged", timeout_s=180)
    finally:
        admin._request(
            "PATCH", "/api/household-settings",
            body={"unmanagedMacPolicy": old_policy},
        )
        wait_for_etag_change(admin, router.router_id, timeout_s=240)
