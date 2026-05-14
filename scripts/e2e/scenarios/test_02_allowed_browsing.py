"""Scenario 2: Allowed browsing.

With a default profile (no blocks), an HTTP GET from the client should
succeed end-to-end and the API should record the connection event with the
client's MAC address.
"""
import pytest

from lib.traffic import http_get
from lib.wait import wait_until

pytestmark = pytest.mark.allowed


def test_allowed_request_succeeds_and_event_recorded(
    router, client, scratch_device, admin, debug_api,
):
    probe = http_get(client, "http://example.com/", timeout_s=10)
    assert probe.ok, f"curl failed: rc={probe.raw.returncode} stderr={probe.stderr!r}"
    assert probe.http_code is not None and 200 <= probe.http_code < 400, \
        f"unexpected status {probe.http_code}; body={probe.body!r}"

    def find_event():
        events = debug_api.events_for_mac(client.mac)
        for e in events:
            if "example.com" in (e.get("hostname") or "") and e.get("allowed") is True:
                return e
        return None

    matched = wait_until(
        find_event, timeout_s=120, interval_s=3,
        description="allowed connection_event for example.com",
    )
    assert matched is not None
