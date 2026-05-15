"""Scenario 1: Enrollment success.

The router_session fixture already does the work — booting the router VM,
calling /api/admin/routers, exchanging the enrollment token for a router
token, and waiting for the agent's first poll. This test just asserts the
end state is what we expect.
"""
import pytest

pytestmark = pytest.mark.enrollment


def test_router_visible_in_admin_api(router_session, admin):
    routers = admin.list_routers()
    ours = [r for r in routers if r.get("id") == router_session.router_id]
    assert len(ours) == 1, f"router {router_session.router_id} not in {routers!r}"
    assert ours[0].get("lastSeenAt"), "router never reported a poll cycle"
    assert ours[0].get("name") == router_session.name
