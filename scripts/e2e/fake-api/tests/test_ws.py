"""Unit coverage for the fake's GET /api/router/ws server-side push (#1939).

The Gate-2 qemu scenario (scripts/e2e/scenarios_fake/test_ws_push_apply.py)
drives the real agent's wifihaven-ws sidecar against this endpoint; these tests
pin the fake's behaviour without a VM so a regression surfaces in seconds:

  - auth at upgrade (missing/empty bearer → 401 before the 101),
  - first-policy-on-connect push (#1849),
  - push-on-change when /test/snapshot swaps the snapshot (#1849),
  - the /test/ws_status bookkeeping the scenario syncs on.
"""
from __future__ import annotations

import aiohttp
import pytest

from fake_api.fixtures import load_initial_snapshot


async def test_ws_pushes_current_snapshot_on_connect(client, auth_headers):
    ws = await client.ws_connect("/api/router/ws", headers=auth_headers)
    try:
        frame = await ws.receive_json(timeout=5)
        assert frame["op"] == "policy"
        assert frame["payload"] == load_initial_snapshot()
    finally:
        await ws.close()


async def test_ws_missing_bearer_rejected_at_upgrade(client):
    with pytest.raises(aiohttp.WSServerHandshakeError) as exc:
        await client.ws_connect("/api/router/ws")
    assert exc.value.status == 401


async def test_ws_empty_bearer_rejected_at_upgrade(client):
    with pytest.raises(aiohttp.WSServerHandshakeError) as exc:
        await client.ws_connect(
            "/api/router/ws", headers={"Authorization": "Bearer "}
        )
    assert exc.value.status == 401


async def test_ws_pushes_on_snapshot_change(client, auth_headers):
    ws = await client.ws_connect("/api/router/ws", headers=auth_headers)
    try:
        # Drain the on-connect push first.
        first = await ws.receive_json(timeout=5)
        assert first["op"] == "policy"

        new_snap = {
            "etag": '"sha256:ws-change-0001"',
            "generatedAt": "2026-05-19T12:00:00Z",
            "devices": {},
            "profiles": {},
            "blocklists": {},
        }
        resp = await client.post("/test/snapshot", json=new_snap)
        assert resp.status == 200
        body = await resp.json()
        assert body["wsPushed"] == 1
        assert body["etag"] == new_snap["etag"]

        pushed = await ws.receive_json(timeout=5)
        assert pushed["op"] == "policy"
        assert pushed["payload"] == new_snap
    finally:
        await ws.close()


async def test_ws_status_tracks_connections_and_pushes(client, auth_headers):
    status = await (await client.get("/test/ws_status")).json()
    assert status["connections"] == 0
    assert status["policyFramesSent"] == 0

    ws = await client.ws_connect("/api/router/ws", headers=auth_headers)
    try:
        await ws.receive_json(timeout=5)  # on-connect push
        status = await (await client.get("/test/ws_status")).json()
        assert status["connections"] == 1
        # On-connect push counts toward the cumulative tally.
        assert status["policyFramesSent"] >= 1
    finally:
        await ws.close()


async def test_ws_push_to_all_with_no_connections_is_noop(client, auth_headers):
    # A snapshot change with nobody connected must not error and reports 0 pushes.
    new_snap = {
        "etag": '"sha256:ws-noconn-0001"',
        "generatedAt": "2026-05-19T12:00:00Z",
        "devices": {},
        "profiles": {},
        "blocklists": {},
    }
    resp = await client.post("/test/snapshot", json=new_snap)
    assert resp.status == 200
    assert (await resp.json())["wsPushed"] == 0
