"""Unit coverage for the fake's GET /api/router/ws server-side push (#1939).

The Gate-2 qemu scenario (scripts/e2e/scenarios_fake/test_ws_push_apply.py)
drives the real agent's wifihaven-ws sidecar against this endpoint; these tests
pin the fake's behaviour without a VM so a regression surfaces in seconds:

  - auth at upgrade (missing/empty bearer → 401 before the 101),
  - first-policy-on-connect push (#1849),
  - push-on-change when /test/snapshot swaps the snapshot (#1849),
  - the /test/ws_status bookkeeping the scenario syncs on,
  - #2642: the reset-time liveness probe, and inbound usage/events ingest.
"""
from __future__ import annotations

import asyncio
import contextlib
import json

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


# --- #2642: what the ws-default-on flip retired -------------------------------
#
# A note on fidelity, because it decides how these tests are written. The real
# peer is the wifihaven-ws sidecar, which is ALWAYS inside `client:recv(poll)`
# (ws_loop.serve) — that is what makes it answer a server ping inline
# (ws_client.lua). aiohttp's *client* only processes control frames while
# someone is awaiting a read on it, so a test that connects and then sits idle
# models a wedged peer, not a live one. Tests that need a live peer therefore
# run a background reader (`_live_peer`); the one test that wants a dead peer
# deliberately omits it.


@contextlib.asynccontextmanager
async def _live_peer(ws):
    """Run a reader on `ws` for the block's duration, like the real sidecar.

    Yields the list of decoded TEXT frames received so far (appended to as they
    arrive). While this is running aiohttp's client auto-pongs, so the channel
    answers the fake's reset-time liveness probe.
    """
    frames: list[dict] = []

    async def _read() -> None:
        async for msg in ws:
            if msg.type == aiohttp.WSMsgType.TEXT:
                frames.append(json.loads(msg.data))

    task = asyncio.create_task(_read())
    try:
        yield frames
    finally:
        task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await task


async def _wait_for_frames(frames: list, n: int, *, timeout_s: float = 5.0) -> None:
    await _wait_until(lambda: len(frames) >= n, f"{n} ws frame(s)", timeout_s)


async def _wait_until(pred, what: str, timeout_s: float = 5.0) -> None:
    """Poll until `pred()` — the fake's work happens on tasks that interleave
    with this one, so a fixed sleep would be either flaky or slow."""
    deadline = asyncio.get_running_loop().time() + timeout_s
    while not pred():
        if asyncio.get_running_loop().time() >= deadline:
            raise AssertionError(f"timed out waiting for {what} within {timeout_s}s")
        await asyncio.sleep(0.02)


async def test_reset_keeps_a_ws_channel_whose_peer_is_alive(client, auth_headers):
    """#2642: POST /test/reset must not forget a channel whose peer answers.

    `reset()` used to clear `ws_connections` outright, on the premise that the
    per-function `router` fixture restores the VM to a **ws-OFF** base snapshot,
    so anything left in the set had to be a dead socket. #2608 made ws the
    shipped default, and a qemu `loadvm` leaves the HOST end of the socket
    untouched — so the restored sidecar can simply carry on using the very
    connection the fake holds. Clearing dropped that live channel, and since
    nothing severed the socket the sidecar never reconnected: the next
    `POST /test/snapshot` pushed to nobody, and with the agent's HTTP poll
    dormant on a healthy link (#2037) nothing delivered the etag. That is the
    Gate-2 `wait_for_etag_served` timeout in #2642.

    The fake now decides by measurement (ping → pong) rather than by assumption;
    this pins the live half of that decision.
    """
    ws = await client.ws_connect("/api/router/ws", headers=auth_headers)
    try:
        async with _live_peer(ws) as frames:
            await _wait_for_frames(frames, 1)  # on-connect push

            body = await (await client.post("/test/reset", json={})).json()
            assert body["wsDropped"] == 0, "a peer that pongs must not be dropped"
            status = await (await client.get("/test/ws_status")).json()
            assert status["connections"] == 1

            new_snap = {
                "etag": '"sha256:ws-after-reset-0001"',
                "generatedAt": "2026-05-19T12:00:00Z",
                "devices": {},
                "profiles": {},
                "blocklists": {},
            }
            resp = await client.post("/test/snapshot", json=new_snap)
            assert (await resp.json())["wsPushed"] == 1
            await _wait_for_frames(frames, 2)
            assert frames[-1]["payload"] == new_snap
    finally:
        await ws.close()


async def test_reset_drops_a_ws_channel_whose_peer_is_gone(client, auth_headers, state):
    """#2642: the other half — a channel that cannot answer is dropped AND closed.

    This is the failure mode keeping the channel unconditionally would have
    introduced. A qemu restore can leave the stream desynchronised, and
    `send_str` into a wedged socket still succeeds into the local buffer — so a
    kept-but-dead channel absorbs the push and `_push_policy` records it as a
    DELIVERY the router never saw, turning a loud Gate-2 timeout into a silent
    false pass. Here the peer never reads, so it never pongs, which is exactly
    how a wedged channel presents.

    Closing (not merely forgetting) is the load-bearing half: the FIN is what
    makes a real sidecar notice the socket is gone and reconnect, instead of
    sitting on a link only the fake knows is dead.
    """
    state.ws_probe_timeout_s = 0.3  # no live peer here; don't wait out the real budget
    # autoping=False on the CLIENT too: without it, the drain below trips
    # aiohttp's client-side auto-pong on an already-closing transport and raises
    # instead of surfacing the close. A wedged peer does not pong; this is how we
    # spell that in-process.
    ws = await client.ws_connect(
        "/api/router/ws", headers=auth_headers, autoping=False
    )
    try:
        await _wait_until(
            lambda: len(state.ws_connections) == 1, "the channel to register"
        )

        body = await (await client.post("/test/reset", json={})).json()
        assert body["wsDropped"] == 1
        status = await (await client.get("/test/ws_status")).json()
        assert status["connections"] == 0

        # A push now reports zero — no false delivery record for a dead channel.
        resp = await client.post(
            "/test/snapshot",
            json={
                "etag": '"sha256:ws-dead-0001"',
                "generatedAt": "2026-05-19T12:00:00Z",
                "devices": {},
                "profiles": {},
                "blocklists": {},
            },
        )
        assert (await resp.json())["wsPushed"] == 0
        fetches = (await (await client.get("/test/policy_fetches")).json())["fetches"]
        assert fetches == [], "a dropped channel must not record a delivery"

        # And the peer was closed, not just forgotten, so a real sidecar would
        # see the socket die and reconnect. Drain first: this peer never read,
        # so the on-connect push is still sitting in its receive buffer ahead of
        # the close.
        for _ in range(10):
            msg = await ws.receive(timeout=5)
            if msg.type in (aiohttp.WSMsgType.CLOSE, aiohttp.WSMsgType.CLOSED):
                break
        else:
            raise AssertionError("channel was dropped but never closed")
    finally:
        # The server already closed this one, so our close is a no-op that can
        # raise on the half-shut transport.
        with contextlib.suppress(Exception):
            await ws.close()


async def test_reset_zeroes_the_policy_frame_tally(client, auth_headers):
    """reset() still re-bases the cumulative push tally (it is bookkeeping, not
    a connection), so a scenario reads only its own pushes."""
    ws = await client.ws_connect("/api/router/ws", headers=auth_headers)
    try:
        async with _live_peer(ws) as frames:
            await _wait_for_frames(frames, 1)
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
    """#2642: an inbound `usage` frame must land in /test/usage, tagged ws.

    With ws the shipped default and the link healthy, the agent's outbound tee
    (`ws_outbound.make`) hands usage/events bodies to the sidecar instead of
    POSTing them, so the HTTP route stops being exercised. The fake used to
    read-and-discard every inbound frame, which left every Gate-2 telemetry
    scenario waiting on a `/test/usage` or `/test/events` record that no longer
    arrives. The payload is byte-for-byte the body the REST route takes, so
    recording it into the same list needs no translation — and `transport`
    preserves which path carried it.
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
        async with _live_peer(ws) as frames:
            await _wait_for_frames(frames, 1)  # on-connect push
            await ws.send_json({"op": "usage", "payload": body})
            reports = await _wait_for_list(client, "/test/usage", "reports")
            assert reports[-1]["body"] == body
            assert reports[-1]["transport"] == "ws"
    finally:
        await ws.close()


async def test_inbound_events_frame_is_ingested_like_an_http_post(client, auth_headers):
    """#2642: an inbound `events` frame must land in /test/events, tagged ws."""
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
        async with _live_peer(ws) as frames:
            await _wait_for_frames(frames, 1)
            await ws.send_json({"op": "events", "payload": body})
            batches = await _wait_for_list(client, "/test/events", "batches")
            assert batches[-1]["body"] == body
            assert batches[-1]["transport"] == "ws"
            events = (await (await client.get("/test/events")).json())["events"]
            assert events[-1]["_transport"] == "ws"
    finally:
        await ws.close()


async def test_http_telemetry_is_still_tagged_transport_http(client, auth_headers):
    """The POST path keeps its own marker, so a scenario can tell them apart —
    without it, a tee that silently stopped teeing would be invisible."""
    assert (
        await client.post(
            "/api/router/events",
            json={"events": []},
            headers=auth_headers,
        )
    ).status == 200
    batches = (await (await client.get("/test/events")).json())["batches"]
    assert batches[-1]["transport"] == "http"


async def test_inbound_unknown_op_is_ignored(client, auth_headers):
    """Forward-compat (design §1.3): an unrecognised op is dropped, and the
    channel stays usable — a future op must not need a fake flag day."""
    ws = await client.ws_connect("/api/router/ws", headers=auth_headers)
    try:
        async with _live_peer(ws) as frames:
            await _wait_for_frames(frames, 1)
            await ws.send_json({"op": "policy_diff", "payload": {"whatever": 1}})
            await ws.send_json({"op": "events", "payload": {"events": []}})
            batches = await _wait_for_list(client, "/test/events", "batches")
            assert batches[-1]["body"] == {"events": []}
            assert (await (await client.get("/test/usage")).json())["reports"] == []
    finally:
        await ws.close()


async def test_inbound_malformed_frame_does_not_kill_the_channel(client, auth_headers):
    """A frame that is not the `{op, payload}` envelope is dropped, not fatal —
    the fake must never wedge the socket the whole scenario depends on."""
    ws = await client.ws_connect("/api/router/ws", headers=auth_headers)
    try:
        async with _live_peer(ws) as frames:
            await _wait_for_frames(frames, 1)
            await ws.send_str("not json at all")
            await ws.send_json({"op": "usage"})  # envelope with no payload
            await ws.send_json({"op": "events", "payload": {"events": []}})
            batches = await _wait_for_list(client, "/test/events", "batches")
            assert batches[-1]["body"] == {"events": []}
    finally:
        await ws.close()


async def _wait_for_list(client, path: str, key: str, *, timeout_s: float = 5.0) -> list:
    """Poll a /test/* list endpoint until it is non-empty.

    The inbound frame is handled by the ws read loop, which runs concurrently
    with this request — so the record is not guaranteed to be there on the first
    GET.
    """
    items: list = []

    async def _poll() -> bool:
        nonlocal items
        items = (await (await client.get(path)).json()).get(key) or []
        return bool(items)

    deadline = asyncio.get_running_loop().time() + timeout_s
    while not await _poll():
        if asyncio.get_running_loop().time() >= deadline:
            raise AssertionError(f"no {key} recorded at {path} within {timeout_s}s")
        await asyncio.sleep(0.05)
    return items
