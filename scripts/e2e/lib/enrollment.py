"""Enroll a router VM and wire the resulting routerToken into UCI.

The OpenWRT agent does NOT auto-enroll: it errors out if router_token is
empty (openwrt/files/usr/sbin/wifihaven-agent). So the orchestrator runs the
enrollment flow itself from the host:

  1. POST /api/admin/routers          → enrollmentToken
     (live mode only; in fake mode any non-empty token works)
  2. POST /api/router/register        → routerToken (host-side, no SLIRP needed)
  3. SSH to the router and `uci set` router_id + router_token + api_url
  4. /etc/init.d/wifihaven restart
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


def exchange_enrollment_token(
    *,
    register_url: str,
    enrollment_token: str,
    timeout_s: float = 15,
) -> tuple[str, str]:
    """POST /api/router/register and return (routerId, routerToken).

    `register_url` is the full URL of the registration endpoint — in live
    mode `<api_base>/api/router/register`, in fake mode the fake's URL.
    """
    log.info("exchanging enrollment_token for router_token via %s", register_url)
    req = urllib.request.Request(
        register_url,
        data=json.dumps({"enrollmentToken": enrollment_token}).encode("utf-8"),
        headers={"content-type": "application/json", "accept": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout_s) as resp:  # noqa: S310
        body = json.loads(resp.read().decode("utf-8"))
    return body["routerId"], body["routerToken"]


def provision_router_uci(
    *,
    router_id: str,
    router_token: str,
    api_url: str,
) -> None:
    """uci-set the agent's identity + api endpoint and restart the agent."""
    uci_script = (
        f"uci set wifihaven.@wifihaven[0].api_url='{api_url}' && "
        f"uci set wifihaven.@wifihaven[0].router_id='{router_id}' && "
        f"uci set wifihaven.@wifihaven[0].router_token='{router_token}' && "
        f"uci commit wifihaven && "
        f"/etc/init.d/wifihaven restart"
    )
    log.info("provisioning router VM with router_id=%s api_url=%s", router_id, api_url)
    router_ssh(uci_script, timeout=60).check()


def enroll_router(
    admin: AdminAPI,
    *,
    name: str,
    api_port: int,
) -> EnrolledRouter:
    """Live-mode enrollment: mint via admin API, exchange against the same host."""
    log.info("creating admin router record name=%s", name)
    created = admin.create_router(name)
    router_id_from_admin = created["routerId"]
    enrollment_token = created["enrollmentToken"]

    register_url = f"{admin.base_url}/api/router/register"
    router_id, router_token = exchange_enrollment_token(
        register_url=register_url, enrollment_token=enrollment_token,
    )
    # In live mode the admin-issued id and the register-returned id are the
    # same record; keep the admin id for downstream calls.
    assert router_id == router_id_from_admin, (
        f"register returned {router_id!r}, admin issued {router_id_from_admin!r}"
    )

    api_url = f"http://{ROUTER_HOST_GATEWAY}:{api_port}"
    provision_router_uci(router_id=router_id, router_token=router_token, api_url=api_url)
    return EnrolledRouter(router_id=router_id, router_token=router_token, name=name)


def enroll_router_against_fake(
    *,
    register_url: str,
    api_url_for_router: str,
    name: str,
    enrollment_token: str = "fake-enrollment-token",
) -> EnrolledRouter:
    """Fake-mode enrollment: skip admin API, hit the fake's /api/router/register.

    The fake accepts any enrollmentToken and returns its deterministic
    (routerId, routerToken). Same UCI wire-up as live mode.
    """
    router_id, router_token = exchange_enrollment_token(
        register_url=register_url, enrollment_token=enrollment_token,
    )
    provision_router_uci(
        router_id=router_id, router_token=router_token, api_url=api_url_for_router,
    )
    return EnrolledRouter(router_id=router_id, router_token=router_token, name=name)
