"""Scenario 3: Blocked domain rejected.

Add a domain to extraBlocked, wait for the agent's next policy fetch (etag
change), then verify HTTP/80 traffic to that host is intercepted: per
render.lua the per-(MAC, host) rule DNATs port-80 traffic to the local
uhttpd block page (127.0.0.1:8081), so the response body should come from
the block page rather than the real upstream.
"""
import pytest

from lib.traffic import http_get
from lib.wait import wait_for_etag_change, wait_until

pytestmark = pytest.mark.blocked


BLOCKED_HOST = "example.org"


def test_blocked_domain_request_dropped(
    router, client, scratch_device, scratch_profile, admin, debug_api,
):
    admin.apply_profile_update(scratch_profile["id"], extraBlocked=[BLOCKED_HOST])

    wait_for_etag_change(admin, router.router_id, timeout_s=120)

    # Per #351 + render.lua, extraBlocked HTTP/80 traffic is DNAT'd to the
    # local block page; the response should not contain the real
    # example.org content. Wait until at least one curl pass returns the
    # block page — the agent may still be re-rendering the nft ruleset
    # immediately after `wait_for_etag_change` returns.
    def block_page_intercepted():
        probe = http_get(client, f"http://{BLOCKED_HOST}/", timeout_s=8)
        body_lower = (probe.body or "").lower()
        if probe.http_code == 200 and (
            "wifihaven" in body_lower or "blocked" in body_lower
        ):
            return probe
        return None

    probe = wait_until(
        block_page_intercepted, timeout_s=60, interval_s=3,
        description=f"block page response for {BLOCKED_HOST}",
    )
    assert probe is not None
