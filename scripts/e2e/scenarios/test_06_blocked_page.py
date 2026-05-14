"""Scenario 6: /blocked page rendering.

Per architecture Truth 1: blocked HTTP traffic is DNATed by nftables to the
local block page on port 80. The page is served by lighttpd on the router
from /www/familydns/index.html. Curling a blocked HTTP destination should
yield the block page body.
"""
import pytest

from lib.traffic import http_get
from lib.wait import wait_for_etag_change, wait_until

pytestmark = pytest.mark.blocked_page


BLOCKED_HOST = "blocked-page-test.example.net"


def test_blocked_request_returns_block_page(
    router, client, scratch_device, scratch_profile, admin,
):
    full = admin.get_profile(scratch_profile["id"])
    prof = full["profile"]
    prof["extraBlocked"] = [BLOCKED_HOST]
    admin.update_profile(scratch_profile["id"], **prof)
    wait_for_etag_change(admin, router.router_id, timeout_s=120)

    # The agent may need a few seconds after applying the new policy before
    # the DNAT rule is hot.
    def block_page_body():
        probe = http_get(client, f"http://{BLOCKED_HOST}/", timeout_s=8)
        if probe.http_code == 200 and "familydns" in probe.body.lower():
            return probe
        if probe.http_code == 200 and "blocked" in probe.body.lower():
            return probe
        return None

    probe = wait_until(block_page_body, timeout_s=60, interval_s=3,
                       description="block page response from router")
    assert probe.http_code == 200, probe
