"""Enroll a router VM and wire the resulting routerToken into UCI.

The OpenWRT agent does NOT auto-enroll: it errors out if router_token is
empty (openwrt/files/usr/sbin/familydns-agent). So the orchestrator runs the
enrollment flow itself from the host:

  1. POST /api/admin/routers          → enrollmentToken
  2. POST /api/router/register        → routerToken (host-side, no SLIRP needed)
  3. SSH to the router and `uci set` router_id + router_token + api_url
  4. /etc/init.d/familydns restart
"""
from __future__ import annotations

import json
import logging
import urllib.request
from dataclasses import dataclass

from .api_admin import AdminAPI
from .vm import ROUTER_HOST_GATEWAY, router_ssh

log = logging.getLogger(__name__)


@dataclass
class EnrolledRouter:
    router_id: str
    router_token: str
    name: str


def enroll_router(
    admin: AdminAPI,
    *,
    name: str,
    api_port: int,
) -> EnrolledRouter:
    """Run the full enrollment dance. Leaves the agent restarted and polling."""
    log.info("creating admin router record name=%s", name)
    created = admin.create_router(name)
    router_id = created["routerId"]
    enrollment_token = created["enrollmentToken"]

    register_url = f"{admin.base_url}/api/router/register"
    log.info("exchanging enrollment_token for router_token via %s", register_url)
    req = urllib.request.Request(
        register_url,
        data=json.dumps({"enrollmentToken": enrollment_token}).encode("utf-8"),
        headers={"content-type": "application/json", "accept": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=15) as resp:  # noqa: S310
        body = json.loads(resp.read().decode("utf-8"))
    router_token = body["routerToken"]

    api_url_for_router = f"http://{ROUTER_HOST_GATEWAY}:{api_port}"
    uci_script = (
        f"uci set familydns.@familydns[0].api_url='{api_url_for_router}' && "
        f"uci set familydns.@familydns[0].router_id='{router_id}' && "
        f"uci set familydns.@familydns[0].router_token='{router_token}' && "
        f"uci commit familydns && "
        f"/etc/init.d/familydns restart"
    )
    log.info("provisioning router VM with router_id=%s api_url=%s", router_id, api_url_for_router)
    router_ssh(uci_script, timeout=60).check()

    return EnrolledRouter(router_id=router_id, router_token=router_token, name=name)
