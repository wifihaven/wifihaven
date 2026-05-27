"""Scenario #1105 — time_limited app with `exemptFromDaily=true` carves
around the MAC-level @blocked_macs drop.

Prod repro (2026-05-26): a Kids profile with `dailyMinutes=30` and a
Khan-shaped app assigned `mode=time_limited`, `dailyMinutes=60`,
`exemptFromDaily=true`. When the profile cap exhausted, the MAC went
into `@blocked_macs` and Khan was dropped along with everything else,
even though the operator's contract is "Khan doesn't count toward the
daily limit" (which implies "and the daily limit doesn't gate Khan").

PolicyService now emits the app's hosts into `extraAllowed` while the
per-host budget remains. The router's render.lua already carves
`extraAllowed` around `@blocked_macs` (see feedback_extraallowed_beats_blocked
and openwrt/files/usr/lib/lua/wifihaven/render.lua), so the exempt host
stays reachable until its own budget exhausts — at which point
siteLimitExtraBlocked takes over and the host transitions allow → block.

The `nft list set inet wifihaven blocked_macs` assertion is the
regression lock: it physically proves the MAC reached the firewall's
blocked set, so a future regression that "passes" by simply not
populating @blocked_macs can't fake this test.
"""
from __future__ import annotations

import logging
import re
import time

import pytest

from lib.traffic import dns_query, http_get
from lib.vm import router_nft_set
from lib.wait import wait_for_etag_change, wait_for_next_poll, wait_until

pytestmark = pytest.mark.exempt_app_carve

log = logging.getLogger(__name__)

BLOCKED_MACS_SET = "blocked_macs"
PROFILE_CAP_MIN = 30
APP_CAP_MIN = 60

# Hosts: chosen to have real A-records reachable from the router VM's
# WAN. The exempt app is `example.com`; the unrelated host the kid
# burns the profile cap on is `example.net`.
APP_HOST = "example.com"
UNRELATED_HOST = "example.net"

IPV4_LINE = re.compile(r"^\d+\.\d+\.\d+\.\d+$")


def _norm_mac(mac: str) -> str:
    return mac.lower().strip()


def _mac_in_blocked_set(mac: str) -> bool:
    members = {_norm_mac(m) for m in router_nft_set(BLOCKED_MACS_SET)}
    return _norm_mac(mac) in members


def _dns_has_ipv4(client, host: str) -> bool:
    """True iff `dig +short host` returns at least one IPv4 line.

    Used as "DNS query succeeded" — when the host is dropped at the
    firewall via @blocked_macs (no extraAllowed carve), dnsmasq itself
    is reachable from the kid's MAC for the MX side-channel but the
    upstream-driven nft set never gets populated and the in-line drop
    rule blocks the answer. For exempt hosts carved into extraAllowed,
    the answer flows through.
    """
    r = dns_query(client, host, timeout_s=5)
    for line in (r.stdout or "").splitlines():
        if IPV4_LINE.match(line.strip()):
            return True
    return False


def test_exempt_app_carves_mac_block(
    router, client, scratch_device, scratch_profile, admin, debug_api,
):
    pid = scratch_profile["id"]
    mac = client.mac

    # Step 1: configure the cap + exempt app, then wait for the agent to
    # poll once so the new policy lands before we start generating usage.
    admin.apply_profile_update(pid, timeLimit=PROFILE_CAP_MIN)
    app = admin.create_app(name="Khan", hosts=[APP_HOST])
    app_id = app["id"] if "id" in app else app["app"]["id"]
    admin.upsert_app_assignment(
        app_id=app_id,
        profile_id=pid,
        mode="time_limited",
        daily_minutes=APP_CAP_MIN,
        exempt_from_daily=True,
    )
    wait_for_etag_change(admin, router.router_id, timeout_s=180)
    wait_for_next_poll(admin, router.router_id, timeout_s=120)

    assert not _mac_in_blocked_set(mac), (
        "precondition: MAC must not be blocked before we generate usage"
    )

    # Step 2: burn the profile cap on a NON-app host. Because the app's
    # host is exempt, traffic against it would NOT count toward the cap
    # (`totalMinutesUsed` excludes exempt domains). Drive enough activity
    # on the unrelated host to exceed PROFILE_CAP_MIN; the agent rolls
    # this up every 300s by default.
    burn_seconds = (PROFILE_CAP_MIN * 60) + 90
    deadline = time.monotonic() + burn_seconds
    while time.monotonic() < deadline:
        http_get(client, f"http://{UNRELATED_HOST}/", timeout_s=5)
        time.sleep(5)

    # Wait long enough for one usage-report + one policy poll → MAC
    # ends up in @blocked_macs once the snapshot's profile rules flip
    # to blocked=true with reason=TimeLimit.
    time.sleep(60)
    wait_for_etag_change(admin, router.router_id, timeout_s=400)
    wait_until(
        lambda: True if _mac_in_blocked_set(mac) else None,
        timeout_s=180, interval_s=3,
        description=f"{mac} present in {BLOCKED_MACS_SET} (profile cap exhausted)",
    )

    # Step 3: regression lock — with the MAC in @blocked_macs, the
    # exempt app host MUST still be reachable (extraAllowed > extraBlocked
    # > @blocked_macs at the router; see feedback_extraallowed_beats_blocked).
    assert _dns_has_ipv4(client, APP_HOST), (
        f"#1105 regression: exempt app host {APP_HOST} blocked after profile cap "
        f"exhausted; should be carved out via extraAllowed"
    )
    # And an unrelated host should be dropped — proves the MAC block is
    # actually live (i.e. the previous assertion isn't passing trivially
    # because no enforcement happened).
    assert not _dns_has_ipv4(client, UNRELATED_HOST), (
        f"unrelated host {UNRELATED_HOST} still reachable; MAC block not enforced"
    )

    # Step 4: now burn the app's own per-host budget. PolicyService will
    # promote the host out of extraAllowed (budget remaining → false) and
    # siteLimitExtraBlocked will simultaneously add it to extraBlocked.
    # Net: same host transitions allow → block at the router.
    app_burn_seconds = (APP_CAP_MIN * 60) + 90
    deadline = time.monotonic() + app_burn_seconds
    while time.monotonic() < deadline:
        # The host is currently carved-allowed; this should reach upstream.
        http_get(client, f"http://{APP_HOST}/", timeout_s=5)
        time.sleep(5)

    time.sleep(60)
    wait_for_etag_change(admin, router.router_id, timeout_s=400)
    wait_until(
        lambda: True if not _dns_has_ipv4(client, APP_HOST) else None,
        timeout_s=180, interval_s=5,
        description=f"{APP_HOST} blocked after per-host budget exhausted",
    )
