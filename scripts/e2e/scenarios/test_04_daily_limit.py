"""Scenario 4: Daily limit enforced.

Set timeLimit.dailyMinutes=1 on the profile. Generate enough usage to push
the device over the cap, wait a poll cycle, and confirm that follow-up
traffic is blocked (allowed=False event recorded).

NOTE: this test relies on usage being attributed to the device's MAC and on
PolicyService re-evaluating the device's BlockRules once dailyMinutes is
exceeded. The agent posts usage every 300 s by default; for a faster test
the orchestrator should override `usage_report_interval` (out of scope here
— we wait the full cycle).
"""
import time
import pytest

from lib.traffic import http_get
from lib.wait import wait_for_etag_change, wait_until

pytestmark = pytest.mark.daily_limit


def test_daily_limit_blocks_after_quota(
    router, client, scratch_device, scratch_profile, admin, debug_api,
):
    admin.apply_profile_update(scratch_profile["id"], timeLimit=1)
    wait_for_etag_change(admin, router.router_id, timeout_s=120)

    # Generate ~90 seconds of traffic against an allowed host to exceed 1 min.
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        http_get(client, "http://example.com/", timeout_s=5)
        time.sleep(2)

    # Allow at least one usage_report_interval + policy_poll_interval so the
    # API ingests usage and the agent picks up the time-exhausted snapshot.
    time.sleep(60)
    wait_for_etag_change(admin, router.router_id, timeout_s=400)

    probe = http_get(client, "http://example.com/", timeout_s=10)

    def find_block_event():
        for e in debug_api.events_for_mac(client.mac):
            reason = (e.get("reason") or "").lower()
            if e.get("allowed") is False and ("time" in reason or "limit" in reason or "quota" in reason):
                return e
        return None

    blocked = wait_until(
        find_block_event, timeout_s=60, interval_s=2,
        description="time-limit block event",
    )
    assert blocked is not None, f"no time-limit block observed; probe={probe!r}"
