"""Scenario 6: /blocked page rendering.

Per architecture Truth 1: blocked HTTP traffic is DNATed by nftables to the
local block page on port 80. The page is served by uhttpd-mod-lua via the
handler at /www/familydns/handler.lua (#437); the handler resolves the
client MAC and per-MAC block reason, then returns an HTML document that
redirects to the API's /blocked page with mac+reason populated. Curling a
blocked HTTP destination should yield the block page body (its <title> is
"Blocked").
"""
import pytest

from lib.traffic import http_get
from lib.wait import wait_for_etag_change, wait_until

pytestmark = pytest.mark.blocked_page


# Must be a domain whose A-records are reachable from the router's WAN —
# the agent populates the per-host nft set from dnsmasq's actual upstream
# answers, and the DNAT-to-block-page rule only fires for IPs that landed
# in that set. A non-existent host never gets any IPs, so no DNAT, no
# block-page interception.
BLOCKED_HOST = "example.net"


def test_blocked_request_returns_block_page(
    router, client, scratch_device, scratch_profile, admin,
):
    admin.apply_profile_update(scratch_profile["id"], extraBlocked=[BLOCKED_HOST])
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
