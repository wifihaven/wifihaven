"""Suite A — pause enforcement (#428).

Verifies the contract walked manually in #264 / #297 / #311 and the
behavior locked in by #406:

  - When a profile is paused, every assigned MAC ends up in the router's
    `blocked_macs` nftables set within one poll cycle.
  - HTTP to any host gets DNATed to the local block page (Truth 1).
  - The API records an `allowed=False reason=paused` event.
  - Unpausing reverses all three within one poll.
  - Adding a device to a paused profile blocks it on next poll; reassigning
    a device away from a paused profile unblocks it on next poll.

Asserts current behavior — bugs surfaced here get filed separately.
"""
from __future__ import annotations

import uuid

import pytest

from lib.traffic import http_get
from lib.vm import router_nft_set
from lib.wait import wait_for_etag_change, wait_until

pytestmark = pytest.mark.pause

BLOCKED_MACS_SET = "blocked_macs"


def _norm_mac(mac: str) -> str:
    return mac.lower().strip()


def _mac_in_set(mac: str) -> bool:
    members = {_norm_mac(m) for m in router_nft_set(BLOCKED_MACS_SET)}
    return _norm_mac(mac) in members


def _wait_mac_in_blocked_set(mac: str, *, present: bool, timeout_s: float = 90) -> None:
    target = present
    wait_until(
        lambda: True if _mac_in_set(mac) is target else None,
        timeout_s=timeout_s,
        interval_s=2,
        description=(
            f"{mac} {'present in' if present else 'absent from'} {BLOCKED_MACS_SET}"
        ),
    )


def _wait_block_page(client, *, host: str = "example.com", timeout_s: float = 60):
    def probe():
        p = http_get(client, f"http://{host}/", timeout_s=8)
        if p.http_code == 200 and (
            "familydns" in p.body.lower() or "blocked" in p.body.lower()
        ):
            return p
        return None
    return wait_until(probe, timeout_s=timeout_s, interval_s=3,
                      description=f"block page response for {host}")


def _wait_http_succeeds(client, *, host: str = "example.com", timeout_s: float = 60):
    def probe():
        p = http_get(client, f"http://{host}/", timeout_s=8)
        if p.http_code is not None and 200 <= p.http_code < 400:
            # block-page also returns 200, so disambiguate by body
            if "familydns" in p.body.lower() or "blocked" in p.body.lower():
                return None
            return p
        return None
    return wait_until(probe, timeout_s=timeout_s, interval_s=3,
                      description=f"allowed HTTP response for {host}")


def _wait_pause_event(debug_api, mac: str, *, timeout_s: float = 30):
    return wait_until(
        lambda: (debug_api.events_for_mac_filtered(
            mac, allowed=False, reason="Paused"
        ) or None),
        timeout_s=timeout_s, interval_s=2,
        description=f"allowed=False reason=Paused event for {mac}",
    )


# ── A1 + A2 ────────────────────────────────────────────────────────────────


@pytest.mark.smoke
def test_pause_blocks_and_unpause_restores(
    router, client, scratch_device, scratch_profile, admin, debug_api,
):
    """A1 + A2 (smoke): pause → blocked within one poll → unpause → restored."""
    profile_id = scratch_profile["id"]
    mac = client.mac

    # Sanity: before pausing, the MAC is not in blocked_macs and HTTP works.
    assert not _mac_in_set(mac), "precondition: MAC should not be blocked yet"

    # ── A1: pause ─────────────────────────────────────────────────────────
    admin.set_profile_paused(profile_id, True)
    wait_for_etag_change(admin, router.router_id, timeout_s=240)

    _wait_mac_in_blocked_set(mac, present=True, timeout_s=180)
    _wait_block_page(client, timeout_s=60)
    _wait_pause_event(debug_api, mac, timeout_s=30)

    # ── A2: unpause ───────────────────────────────────────────────────────
    admin.set_profile_paused(profile_id, False)
    wait_for_etag_change(admin, router.router_id, timeout_s=240)

    _wait_mac_in_blocked_set(mac, present=False, timeout_s=180)
    _wait_http_succeeds(client, timeout_s=60)


# ── A3 ─────────────────────────────────────────────────────────────────────


def test_pause_then_add_device_blocks_new_mac(
    router, scratch_profile, admin,
):
    """A3: with profile paused, adding a new device gets it blocked on next poll.

    No client VM is needed — we only need the nft set to grow. This keeps
    the test fast and avoids the multi-client-boot limitation noted in
    scripts/vm/README.md.
    """
    profile_id = scratch_profile["id"]
    admin.set_profile_paused(profile_id, True)
    wait_for_etag_change(admin, router.router_id, timeout_s=240)

    # New MAC, never seen by the router or API before.
    new_mac = "02:e2:e2:a3:00:%02x" % (uuid.uuid4().int & 0xFF)
    admin.upsert_device(mac=new_mac, name=f"e2e-a3-{new_mac[-5:]}", profile_id=profile_id)
    wait_for_etag_change(admin, router.router_id, timeout_s=240)

    try:
        _wait_mac_in_blocked_set(new_mac, present=True, timeout_s=180)
    finally:
        try:
            admin.delete_device(new_mac)
        except Exception:
            pass


# ── A4 ─────────────────────────────────────────────────────────────────────


def test_reassign_off_paused_profile_unblocks_mac(
    router, client, scratch_device, scratch_profile, admin,
):
    """A4: paused-profile device reassigned to an unpaused profile unblocks."""
    paused_pid = scratch_profile["id"]
    mac = client.mac

    admin.set_profile_paused(paused_pid, True)
    wait_for_etag_change(admin, router.router_id, timeout_s=240)
    _wait_mac_in_blocked_set(mac, present=True, timeout_s=180)

    # Create a second, unpaused profile and reassign.
    other = admin.create_profile(name=f"e2e-a4-other-{uuid.uuid4().hex[:6]}")
    other_pid = other["profile"]["id"] if "profile" in other else other["id"]
    try:
        admin.upsert_device(mac=mac, name=f"e2e-a4-{mac[-5:]}", profile_id=other_pid)
        wait_for_etag_change(admin, router.router_id, timeout_s=240)
        _wait_mac_in_blocked_set(mac, present=False, timeout_s=180)
    finally:
        # scratch_device's teardown still tries delete_device(mac); reassign
        # leaves the row pointing at `other_pid`, which is fine. We just need
        # to clean up the extra profile.
        try:
            admin.delete_profile(other_pid)
        except Exception:
            pass
