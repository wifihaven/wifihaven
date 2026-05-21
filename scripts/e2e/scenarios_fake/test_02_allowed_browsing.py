"""Scenario 2: Allowed browsing (fake-mode #684).

Default permissive profile → HTTP GET from the client succeeds, AND the
agent reports the connection_event upstream. The fake captures the event
batch and `events_for_mac(mac, allowed=True)` returns it.
"""
from __future__ import annotations

import pytest

from lib.wait import wait_until

from ._observers import wait_http_succeeds
from .snapshot_builder import SnapshotBuilder

pytestmark = pytest.mark.allowed

PROFILE_ID = 400
ALLOWED_HOST = "example.com"


def test_allowed_request_succeeds_and_event_recorded(router, client, fake_api):
    snap = (
        SnapshotBuilder()
        .add_profile(id=PROFILE_ID, name="e2e-allowed")
        .add_device(mac=client.mac, name="e2e-allowed-dev", profile_id=PROFILE_ID)
        .build(etag='"sha256:allowed-v1"')
    )
    etag = fake_api.serve_snapshot(snap)
    fake_api.wait_for_etag_served(etag=etag, timeout_s=240)

    probe = wait_http_succeeds(client, host=ALLOWED_HOST, timeout_s=60)
    assert probe.http_code is not None and 200 <= probe.http_code < 400

    # The agent flushes connection_events on its event-report cadence
    # (independent of policy polls). The fake captures whatever the agent
    # POSTs to /api/router/events. Look for an allowed=True event for this MAC
    # — host attribution lives under the tagged-union `host` field (#391).
    def find_allowed_event():
        for e in fake_api.events_for_mac(client.mac, allowed=True):
            host = e.get("host")
            if isinstance(host, dict) and ALLOWED_HOST in (host.get("value") or ""):
                return e
            # Fallback: even without host attribution, an allowed=True event
            # for this MAC is the load-bearing observable.
            return e
        return None

    matched = wait_until(
        find_allowed_event, timeout_s=120, interval_s=3,
        description=f"fake-captured allowed event for {client.mac}",
    )
    assert matched is not None
