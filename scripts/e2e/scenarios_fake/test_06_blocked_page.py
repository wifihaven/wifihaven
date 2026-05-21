"""Scenario 6: /blocked page rendering (fake-mode #684).

Per architecture Truth-1, blocked HTTP traffic is DNAT'd by nftables to the
local block page on port 80, served by uhttpd-mod-lua
(/www/wifihaven/handler.lua, #437). Curling a blocked HTTP destination should
yield the block page body.

Mirrors `scenarios/test_06_blocked_page.py` but drives policy via the fake.
"""
from __future__ import annotations

import pytest

from ._observers import wait_block_page
from .snapshot_builder import SnapshotBuilder

pytestmark = pytest.mark.blocked_page

BLOCKED_HOST = "example.net"
PROFILE_ID = 300


def test_blocked_request_returns_block_page(router, client, fake_api):
    snap = (
        SnapshotBuilder()
        .add_profile(id=PROFILE_ID, name="e2e-blockpage", extra_blocked=[BLOCKED_HOST])
        .add_device(mac=client.mac, name="e2e-blockpage-dev", profile_id=PROFILE_ID)
        .build(etag='"sha256:blockpage-v1"')
    )
    etag = fake_api.serve_snapshot(snap)
    fake_api.wait_for_etag_served(etag=etag, timeout_s=240)

    probe = wait_block_page(client, host=BLOCKED_HOST, timeout_s=90)
    assert probe.http_code == 200

    body_lower = probe.body.lower()
    assert "blocked" in body_lower, "expected 'blocked' in block page body"
    assert "request extension" not in body_lower, (
        "block page must not expose a 'Request extension' link (#577)"
    )
