"""Suite E — profile reassignment (#432, verifies #140).

Locks in the contract that profile assignment changes propagate to the
router's nft state within one poll cycle:

  - Reassigning a device from a permissive profile to a paused profile
    blocks the MAC and routes its HTTP traffic to the block page.
  - Reassigning back to a permissive profile unblocks the MAC and HTTP
    succeeds normally.
  - Deleting a device removes it from the router's `blocked_macs` set
    (if it was there) and removes the row from the API's view.

Asserts current behavior — bugs surfaced here get filed separately.
"""
from __future__ import annotations

import uuid

import pytest

from lib.traffic import http_get
from lib.vm import router_nft_set
from lib.wait import wait_for_etag_change, wait_until

pytestmark = pytest.mark.reassignment

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


def _device_row_for_mac(admin, mac: str) -> dict | None:
    norm = _norm_mac(mac)
    for d in admin.list_devices():
        if _norm_mac(d.get("mac") or "") == norm:
            return d
    return None


# ── E1 + E2 ────────────────────────────────────────────────────────────────


@pytest.mark.smoke
def test_reassign_to_paused_blocks_and_back_restores(
    router, client, scratch_device, scratch_profile, admin, debug_api,
):
    """E1 + E2 (smoke): A→B(paused) blocks within one poll, B→A restores.

    `scratch_device` pre-registers the client's MAC on the permissive
    profile A (`scratch_profile`). We create a SECOND profile B, pause it,
    and walk the MAC across.
    """
    profile_a_id = scratch_profile["id"]
    mac = client.mac

    # Sanity: starting state — permissive profile, MAC not blocked.
    assert not _mac_in_set(mac), "precondition: MAC should not be blocked yet"

    # Create profile B and pause it.
    other = admin.create_profile(name=f"e2e-e1-paused-{uuid.uuid4().hex[:6]}")
    profile_b_id = other["profile"]["id"] if "profile" in other else other["id"]

    try:
        # ── E1: A → B (paused) ─────────────────────────────────────────
        admin.set_profile_paused(profile_b_id, True)
        wait_for_etag_change(admin, router.router_id, timeout_s=240)

        admin.upsert_device(
            mac=mac, name=f"e2e-e1-{mac[-5:]}", profile_id=profile_b_id,
        )
        wait_for_etag_change(admin, router.router_id, timeout_s=240)

        _wait_mac_in_blocked_set(mac, present=True, timeout_s=180)
        _wait_block_page(client, timeout_s=60)

        # ── E2: B → A restores ─────────────────────────────────────────
        admin.upsert_device(
            mac=mac, name=f"e2e-e2-{mac[-5:]}", profile_id=profile_a_id,
        )
        wait_for_etag_change(admin, router.router_id, timeout_s=240)

        _wait_mac_in_blocked_set(mac, present=False, timeout_s=180)
        _wait_http_succeeds(client, timeout_s=60)
    finally:
        # Reassignment leaves the device on profile A — scratch_device's
        # teardown will clean it up. Just drop the extra profile.
        try:
            admin.delete_profile(profile_b_id)
        except Exception:
            pass


# ── E3 ─────────────────────────────────────────────────────────────────────


def test_delete_device_clears_rules(
    router, client, scratch_device, scratch_profile, admin, debug_api,
):
    """E3: deleting a device removes the row and drops any nft block entry.

    To exercise the "delete clears rules" path, we first put the device
    onto a paused profile so its MAC is in `blocked_macs`. Then delete
    the device row and assert (a) the MAC is gone from `blocked_macs`
    within one poll, and (b) the row no longer appears via list_devices.
    """
    mac = client.mac

    paused = admin.create_profile(name=f"e2e-e3-paused-{uuid.uuid4().hex[:6]}")
    paused_id = paused["profile"]["id"] if "profile" in paused else paused["id"]

    deleted = False
    try:
        admin.set_profile_paused(paused_id, True)
        wait_for_etag_change(admin, router.router_id, timeout_s=240)

        admin.upsert_device(
            mac=mac, name=f"e2e-e3-{mac[-5:]}", profile_id=paused_id,
        )
        wait_for_etag_change(admin, router.router_id, timeout_s=240)
        _wait_mac_in_blocked_set(mac, present=True, timeout_s=180)

        # Now delete the device row.
        admin.delete_device(mac)
        deleted = True
        wait_for_etag_change(admin, router.router_id, timeout_s=240)

        # MAC should leave blocked_macs within one poll.
        _wait_mac_in_blocked_set(mac, present=False, timeout_s=180)

        # Row should be absent from list_devices.
        wait_until(
            lambda: True if _device_row_for_mac(admin, mac) is None else None,
            timeout_s=30, interval_s=2,
            description=f"device row for {mac} removed from list_devices",
        )
    finally:
        if not deleted:
            try:
                admin.delete_device(mac)
            except Exception:
                pass
        try:
            admin.delete_profile(paused_id)
        except Exception:
            pass
