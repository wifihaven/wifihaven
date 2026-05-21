"""Suite E — profile reassignment (fake-mode #684).

Mirrors `scenarios/test_reassignment.py::test_reassign_to_paused_blocks_and_back_restores`
(E1+E2 smoke). The fake-mode equivalent of "reassign" is "POST a new snapshot
where the device's `profileId` points at a different profile."

E3 (delete-clears-rules) is dropped: in fake mode the observable for deletion
is identical to "device removed from the snapshot," and the per-MAC unblock
on snapshot removal is already covered by E2 (reassignment to a non-paused
profile).
"""
from __future__ import annotations

import pytest

from ._observers import (
    mac_in_blocked_set,
    wait_block_page,
    wait_http_succeeds,
    wait_mac_in_blocked_set,
)
from .snapshot_builder import SnapshotBuilder

pytestmark = pytest.mark.reassignment

PROFILE_A_PERMISSIVE = 800
PROFILE_B_PAUSED = 801


def _snapshot_on_profile(*, etag: str, mac: str, profile_id: int) -> dict:
    """Snapshot with two profiles (A permissive, B paused) and the device
    pinned to `profile_id`. Both profiles always declared so the agent sees a
    consistent universe across swaps."""
    return (
        SnapshotBuilder()
        .add_profile(id=PROFILE_A_PERMISSIVE, name="e2e-permissive")
        .add_profile(
            id=PROFILE_B_PAUSED, name="e2e-paused",
            blocked=True, block_reason="Paused",
        )
        .add_device(mac=mac, name="e2e-reassign-dev", profile_id=profile_id)
        .build(etag=etag)
    )


@pytest.mark.smoke
def test_reassign_to_paused_blocks_and_back_restores(router, client, fake_api):
    """E1+E2 (smoke): A→B(paused) blocks within one poll; B→A restores."""
    mac = client.mac

    # Initial: device on permissive profile A — not blocked.
    e1 = fake_api.serve_snapshot(
        _snapshot_on_profile(
            etag='"sha256:reassign-A1"', mac=mac, profile_id=PROFILE_A_PERMISSIVE,
        ),
    )
    fake_api.wait_for_etag_served(etag=e1, timeout_s=240)
    assert not mac_in_blocked_set(mac), \
        "precondition: MAC must not be blocked on permissive profile"

    # E1: reassign to paused profile B → blocked + block page.
    e2 = fake_api.serve_snapshot(
        _snapshot_on_profile(
            etag='"sha256:reassign-B"', mac=mac, profile_id=PROFILE_B_PAUSED,
        ),
    )
    fake_api.wait_for_etag_served(etag=e2, timeout_s=240)
    wait_mac_in_blocked_set(mac, present=True, timeout_s=180)
    wait_block_page(client, timeout_s=60)

    # E2: reassign back to A → unblocked + HTTP works.
    e3 = fake_api.serve_snapshot(
        _snapshot_on_profile(
            etag='"sha256:reassign-A2"', mac=mac, profile_id=PROFILE_A_PERMISSIVE,
        ),
    )
    fake_api.wait_for_etag_served(etag=e3, timeout_s=240)
    wait_mac_in_blocked_set(mac, present=False, timeout_s=180)
    wait_http_succeeds(client, timeout_s=60)
