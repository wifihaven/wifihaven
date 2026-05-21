"""Scenario 3: Blocked domain rejected (fake-mode #684).

extraBlocked drives a per-(MAC, host) nft drop rule that DNATs port-80
traffic to the local block page. The response body comes from the block
page rather than the real upstream.
"""
from __future__ import annotations

import pytest

from ._observers import wait_block_page
from .snapshot_builder import SnapshotBuilder

pytestmark = pytest.mark.blocked

BLOCKED_HOST = "example.org"
PROFILE_ID = 500


def test_blocked_domain_request_dropped(router, client, fake_api):
    snap = (
        SnapshotBuilder()
        .add_profile(id=PROFILE_ID, name="e2e-blocked", extra_blocked=[BLOCKED_HOST])
        .add_device(mac=client.mac, name="e2e-blocked-dev", profile_id=PROFILE_ID)
        .build(etag='"sha256:blocked-domain-v1"')
    )
    etag = fake_api.serve_snapshot(snap)
    fake_api.wait_for_etag_served(etag=etag, timeout_s=240)

    probe = wait_block_page(client, host=BLOCKED_HOST, timeout_s=90)
    assert probe is not None
