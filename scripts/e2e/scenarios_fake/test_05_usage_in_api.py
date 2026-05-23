"""Scenario 5: usage reports flow upstream (fake-mode #684).

Live-mode `scenarios/test_05_usage_in_api.py` asserts that connection_events
appear in `/api/logs` and that the device shows up in `/api/time/status` —
both API-side projections of agent-emitted reports.

In fake mode the API-side projections don't exist; what's load-bearing is
that the agent *emits* the report and *emits* the events. The fake captures
both; this scenario asserts:

  1. After client HTTP activity, the fake has captured a usage report
     whose `records` contain an entry for the client's MAC.
  2. The fake has at least one connection_attempt event for the client's MAC.

This is the wire-shape equivalent of "usage shows up upstream" — the live
projections are tested separately by the API's own suite.
"""
from __future__ import annotations

import time

import pytest

from lib.traffic import http_get
from lib.wait import wait_until

from .snapshot_builder import SnapshotBuilder

pytestmark = pytest.mark.usage

PROFILE_ID = 900
ALLOWED_HOST = "example.com"


def test_usage_flows_to_fake_for_client_mac(router, client, fake_api):
    """Drive 5 HTTP probes and assert the fake captured a usage report
    referencing this MAC, plus at least one event."""
    snap = (
        SnapshotBuilder()
        .add_profile(id=PROFILE_ID, name="e2e-usage")
        .add_device(mac=client.mac, name="e2e-usage-dev", profile_id=PROFILE_ID)
        .build(etag='"sha256:usage-v1"')
    )
    etag = fake_api.serve_snapshot(snap)
    fake_api.wait_for_etag_served(etag=etag, timeout_s=240)

    for _ in range(5):
        http_get(client, f"http://{ALLOWED_HOST}/", timeout_s=5)
        time.sleep(1)

    # Events surface on the agent's event-report cadence (seconds). Look for
    # any event tagged with our MAC.
    def has_event():
        return fake_api.events_for_mac(client.mac) or None
    wait_until(
        has_event, timeout_s=120, interval_s=3,
        description=f"fake event captured for {client.mac}",
    )

    # Usage reports go out on a slower cadence (agent default ~300s). To keep
    # the suite under wall-time budget we don't wait the full cycle here —
    # we poll the fake's /test/usage capture buffer and time out gracefully
    # if no report has been flushed yet. The router-side enforcement signal
    # (events captured above) is the load-bearing observable.
    def has_usage_for_mac():
        for r in (fake_api.usage().get("reports") or []):
            for rec in (r.get("body", {}).get("records") or []):
                if (rec.get("mac") or "").lower() == client.mac.lower():
                    return rec
        return None
    rec = wait_until(
        has_usage_for_mac, timeout_s=360, interval_s=10,
        description=f"fake usage report with row for {client.mac}",
    )
    # Wire-shape sanity: matches shared/contract/router-to-api/usage_report.json.
    assert "activeSeconds" in rec
    assert "bytesIn" in rec
    assert "bytesOut" in rec
    # bytes_out cascade (#717 → #833 → #858 → #866 → #905 → #913): key-presence
    # alone let zeroed/swapped counters ship repeatedly. See #920.
    assert rec["bytesOut"] > 0, f"bytesOut should be >0 after HTTP GETs, got {rec!r}"
    assert rec["bytesIn"] > 0, f"bytesIn should be >0 after HTTP GETs, got {rec!r}"
    # GET-only workload is download-heavy; catches #905-class direction swaps.
    assert rec["bytesIn"] > rec["bytesOut"], (
        f"GET-only workload should have bytesIn > bytesOut, got {rec!r}"
    )
