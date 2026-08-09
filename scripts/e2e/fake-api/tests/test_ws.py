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


async def test_ws_push_to_all_with_no_connections_is_noop(client):
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


async def test_ws_push_is_recorded_as_a_policy_fetch(client, auth_headers):
    """#2608: a pushed snapshot lands in /test/policy_fetches, tagged transport=ws.

    Once ws is the shipped router default the HTTP poll goes dormant on a healthy
    link (#2037), so the ~40 Gate-2 scenarios that synchronise on
    `wait_for_etag_served` — which scans this list for a 200 carrying the served
    etag — would hang forever if only polls were recorded. Recording both
    transports in ONE list keeps that helper transport-agnostic.
    """
    before = await (await client.get("/test/policy_fetches")).json()
    assert before["count"] == 0

    ws = await client.ws_connect("/api/router/ws", headers=auth_headers)
    try:
        await ws.receive_json(timeout=5)  # on-connect push

        after_connect = await (await client.get("/test/policy_fetches")).json()
        assert after_connect["count"] == 1
        rec = after_connect["fetches"][-1]
        assert rec["transport"] == "ws"
        assert rec["status"] == 200
        assert rec["servedEtag"]

        new_snap = {
            "etag": '"sha256:ws-fetchrec-0002"',
            "generatedAt": "2026-05-19T12:00:00Z",
            "devices": {},
            "profiles": {},
            "blocklists": {},
        }
        assert (await client.post("/test/snapshot", json=new_snap)).status == 200
        await ws.receive_json(timeout=5)  # push-on-change

        after_change = await (await client.get("/test/policy_fetches")).json()
        pushed = after_change["fetches"][-1]
        assert pushed["transport"] == "ws"
        assert pushed["status"] == 200
        # The etag recorded is the one actually delivered, which is what
        # wait_for_etag_served matches on.
        assert pushed["servedEtag"] == new_snap["etag"]
    finally:
        await ws.close()


async def test_http_poll_is_still_recorded_as_transport_http(client, auth_headers):
    """The poll path keeps its own record, tagged transport=http (#2608)."""
    assert (await client.get("/api/router/policy", headers=auth_headers)).status == 200
    body = await (await client.get("/test/policy_fetches")).json()
    assert body["fetches"][-1]["transport"] == "http"


# --- #2642: the two ws-default-on regressions ---------------------------------


async def test_reset_keeps_a_live_ws_channel(client, auth_headers):
    """#2642: POST /test/reset must not forget a channel that is still open.

    `reset()` used to clear `ws_connections` outright, on the premise that the
    per-function `router` fixture restores the VM to a **ws-OFF** base snapshot,
    so anything left in the set had to be a dead socket. #2608 made ws the
    shipped default, which retired that premise: the restored VM brings its
    sidecar up on its own, and whether its connect lands before or after the
    fixture's reset is a race. Losing that race dropped a LIVE channel from the
    set, so the next `POST /test/snapshot` pushed to nobody — and with the
    agent's HTTP poll dormant on a healthy link (#2037) nothing delivered the
    etag, which is the Gate-2 `wait_for_etag_served` timeout in #2642.

    Liveness is owned by register_ws/deregister_ws (the handler's `finally`) and
    by `_push_policy`'s deregister-on-send-failure, not by the test-control
    reset.
    """
    ws = await client.ws_connect("/api/router/ws", headers=auth_headers)
    try:
        await ws.receive_json(timeout=5)  # on-connect push

        assert (await client.post("/test/reset", json={})).status == 200
        status = await (await client.get("/test/ws_status")).json()
        assert status["connections"] == 1, (
            "reset dropped a still-open ws channel — a snapshot change can no "
            "longer be pushed to the connected agent (#2642)"
        )

        new_snap = {
            "etag": '"sha256:ws-after-reset-0001"',
            "generatedAt": "2026-05-19T12:00:00Z",
            "devices": {},
            "profiles": {},
            "blocklists": {},
        }
        resp = await client.post("/test/snapshot", json=new_snap)
        assert (await resp.json())["wsPushed"] == 1
        pushed = await ws.receive_json(timeout=5)
        assert pushed["payload"] == new_snap
    finally:
        await ws.close()


async def test_reset_zeroes_the_policy_frame_tally(client, auth_headers):
    """reset() still re-bases the cumulative push tally (it is bookkeeping, not
    a connection), so a scenario reads only its own pushes."""
    ws = await client.ws_connect("/api/router/ws", headers=auth_headers)
    try:
        await ws.receive_json(timeout=5)
        assert (await (await client.get("/test/ws_status")).json())[
            "policyFramesSent"
        ] >= 1
        assert (await client.post("/test/reset", json={})).status == 200
        assert (await (await client.get("/test/ws_status")).json())[
            "policyFramesSent"
        ] == 0
    finally:
        await ws.close()


async def test_inbound_usage_frame_is_ingested_like_an_http_post(client, auth_headers):
    """#2642: an inbound `usage` frame must land in /test/usage.

    With ws the shipped default and the link healthy, the agent's outbound tee
    (`ws_outbound.make`) hands usage/events bodies to the sidecar instead of
    POSTing them, so the HTTP route stops being exercised. The fake used to
    read-and-discard every inbound frame, which left every Gate-2 telemetry
    scenario waiting on a `/test/usage` or `/test/events` record that no longer
    arrives. The real API demuxes the same envelope through the SAME ingest
    services the REST routes use (RouterWsRoutes.scala); the fake now records
    into the same lists its HTTP handlers write.
    """
    body = {
        "routerId": "11111111-2222-3333-4444-555555555555",
        "periodStart": "2026-05-17T14:00:00Z",
        "periodEnd": "2026-05-17T14:05:00Z",
        "records": [
            {
                "mac": "aa:bb:cc:11:22:33",
                "host": {"type": "fqdn", "value": "example.com"},
                "bytesIn": 10,
                "bytesOut": 20,
                "activeSeconds": 5,
            }
        ],
    }
    ws = await client.ws_connect("/api/router/ws", headers=auth_headers)
    try:
        await ws.receive_json(timeout=5)  # on-connect push
        await ws.send_json({"op": "usage", "payload": body})
        reports = await _wait_for(client, "/test/usage", "reports")
        assert reports[-1]["body"] == body
    finally:
        await ws.close()


async def test_inbound_events_frame_is_ingested_like_an_http_post(client, auth_headers):
    """#2642: an inbound `events` frame must land in /test/events."""
    body = {
        "events": [
            {
                "mac": "aa:bb:cc:11:22:33",
                "host": "example.com",
                "allowed": True,
                "reason": "allow",
                "ts": "2026-05-17T14:00:00Z",
            }
        ]
    }
    ws = await client.ws_connect("/api/router/ws", headers=auth_headers)
    try:
        await ws.receive_json(timeout=5)
        await ws.send_json({"op": "events", "payload": body})
        batches = await _wait_for(client, "/test/events", "batches")
        assert batches[-1]["body"] == body
    finally:
        await ws.close()


async def test_inbound_unknown_op_is_ignored(client, auth_headers):
    """Forward-compat (design §1.3): an unrecognised op is dropped, and the
    channel stays usable — a future op must not need a fake flag day."""
    ws = await client.ws_connect("/api/router/ws", headers=auth_headers)
    try:
        await ws.receive_json(timeout=5)
        await ws.send_json({"op": "policy_diff", "payload": {"whatever": 1}})
        await ws.send_json({"op": "events", "payload": {"events": []}})
        batches = await _wait_for(client, "/test/events", "batches")
        assert batches[-1]["body"] == {"events": []}
        assert (await (await client.get("/test/usage")).json())["reports"] == []
    finally:
        await ws.close()


async def test_inbound_malformed_frame_does_not_kill_the_channel(client, auth_headers):
    """A frame that is not the `{op, payload}` envelope is dropped, not fatal —
    the fake must never wedge the socket the whole scenario depends on."""
    ws = await client.ws_connect("/api/router/ws", headers=auth_headers)
    try:
        await ws.receive_json(timeout=5)
        await ws.send_str("not json at all")
        await ws.send_json({"op": "usage"})  # envelope with no payload
        await ws.send_json({"op": "events", "payload": {"events": []}})
        batches = await _wait_for(client, "/test/events", "batches")
        assert batches[-1]["body"] == {"events": []}
    finally:
        await ws.close()


async def _wait_for(client, path: str, key: str, *, timeout_s: float = 5.0) -> list:
    """Poll a /test/* list endpoint until it is non-empty.

    The inbound frame is handled by the ws read loop, which runs concurrently
    with this request — so the record is not guaranteed to be there on the first
    GET. Polling (rather than a fixed sleep) keeps the test fast and non-flaky.
    """
    import asyncio

    deadline = asyncio.get_running_loop().time() + timeout_s
    while True:
        items = (await (await client.get(path)).json()).get(key) or []
        if items:
            return items
        if asyncio.get_running_loop().time() >= deadline:
            raise AssertionError(f"no {key} recorded at {path} within {timeout_s}s")
        await asyncio.sleep(0.05)
