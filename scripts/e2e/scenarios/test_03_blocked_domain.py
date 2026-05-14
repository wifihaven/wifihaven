"""Scenario 3: Blocked domain rejected.

Add a domain to extraBlocked, wait for the agent's next policy fetch (etag
change), then verify a request to that domain is dropped/redirected and an
allowed=False event is recorded.
"""
import pytest

from lib.traffic import http_get
from lib.wait import wait_for_etag_change, wait_until

pytestmark = pytest.mark.blocked


BLOCKED_HOST = "example.org"


def test_blocked_domain_request_dropped(
    router, client, scratch_device, scratch_profile, admin, debug_api,
):
    full = admin.get_profile(scratch_profile["id"])
    prof = full["profile"]
    prof["extraBlocked"] = [BLOCKED_HOST]
    admin.update_profile(scratch_profile["id"], **prof)

    wait_for_etag_change(admin, router.router_id, timeout_s=120)

    probe = http_get(client, f"http://{BLOCKED_HOST}/", timeout_s=10)
    # Either curl gets a connection failure (drop) or it gets the block-page
    # response from the router. In either case the API should log an
    # allowed=False event.
    def find_block_event():
        for e in debug_api.events_for_mac(client.mac):
            if BLOCKED_HOST in (e.get("hostname") or "") and e.get("allowed") is False:
                return e
        return None

    blocked = wait_until(
        find_block_event, timeout_s=30, interval_s=2,
        description=f"blocked event for {BLOCKED_HOST}",
    )
    assert blocked is not None, f"no block event observed; probe={probe!r}"
