"""Scenario 5: Usage reflected in API.

After the client generates some traffic, the dashboard logs and time_status
endpoints should show activity attributed to the device's MAC.
"""
import time
import pytest

from lib.traffic import http_get
from lib.wait import wait_until

pytestmark = pytest.mark.usage


def test_usage_visible_in_logs_and_time_status(
    router, client, scratch_device, scratch_profile, admin, debug_api,
):
    for _ in range(5):
        http_get(client, "http://example.com/", timeout_s=5)
        time.sleep(1)

    # Wait for at least one connection_event for this MAC to surface.
    def has_event():
        return debug_api.events_for_mac(client.mac)
    events = wait_until(has_event, timeout_s=30, interval_s=2,
                        description="connection_events for client MAC")
    assert events, "no connection_events for our MAC"

    # /api/logs should mirror the same MAC.
    logs = admin.logs(mac=client.mac)
    assert isinstance(logs, list), f"unexpected /api/logs shape: {logs!r}"
    assert any((l.get("mac") or "").lower() == client.mac.lower() for l in logs), \
        f"no /api/logs entry for {client.mac}; got {len(logs)} entries"

    # /api/time/status returns ProfileTimeStatus[] keyed by profile, each
    # with a `devices: [DeviceUsageSummary]` list. The schema is
    # {deviceMac, deviceName, usedMins} (shared/Models.scala) — note the
    # field is `deviceMac`, not `mac`. The device shows up there as soon
    # as it has a profile, even before usage is ingested.
    statuses = admin.time_status()
    summaries = [
        d
        for prof in statuses
        for d in prof.get("devices", [])
    ]
    assert any((d.get("deviceMac") or "").lower() == client.mac.lower() for d in summaries), \
        f"no /api/time/status entry for {client.mac}; got {len(summaries)} summaries"
