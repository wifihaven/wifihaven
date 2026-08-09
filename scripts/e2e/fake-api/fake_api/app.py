"""aiohttp application factory for the fake WifiHaven router API.

Production-shaped endpoints (`/api/router/*`) mirror the surface that the
OpenWRT lua agent (`openwrt/files/usr/lib/lua/wifihaven/policy.lua`, etc.)
hits against the real Scala API (`api/src/routes/RouterRoutes.scala`).
Test-control endpoints (`/test/*`) let pytest scenarios drive the fake.
"""

from __future__ import annotations

import asyncio
import contextlib
import copy
import json
from typing import Any

from aiohttp import WSMsgType, web

from .state import State

STATE_KEY = web.AppKey("state", State)

# #2642: per-channel ceiling on each of the probe's two control-frame awaits —
# the liveness ping and the best-effort close of a channel that failed it. Both
# reach aiohttp's writer, which awaits a drain when the transport is paused, and
# a wedged peer is exactly how a transport ends up paused. Deliberately NOT a
# State field (unlike ws_probe_timeout_s, which the suite shortens): 1s is
# already short enough to pay in a unit test, so there is nothing to override.
# Kept small so the whole of /test/reset stays well inside the 10s client
# timeout the harness POSTs it with (lib/fake_client.py) — see
# _prune_dead_ws_channels for the arithmetic.
_WS_CTL_TIMEOUT_S = 1.0


def _bearer_token(request: web.Request) -> str | None:
    auth = request.headers.get("Authorization", "")
    if not auth.startswith("Bearer "):
        return None
    tok = auth[len("Bearer ") :].strip()
    return tok or None


def _require_bearer(request: web.Request) -> web.Response | None:
    if _bearer_token(request) is None:
        return web.json_response(
            {"error": "Missing router token"}, status=401
        )
    return None


async def _read_json(request: web.Request) -> dict[str, Any]:
    try:
        return await request.json()
    except Exception as e:  # noqa: BLE001
        raise web.HTTPBadRequest(reason=f"invalid JSON: {e}")


# --- Production endpoints ----------------------------------------------------


async def post_register(request: web.Request) -> web.Response:
    body = await _read_json(request)
    state: State = request.app[STATE_KEY]
    state.record_register(body=body, headers=dict(request.headers))
    return web.json_response(
        {"routerId": state.router_id, "routerToken": state.router_token}
    )


async def get_policy(request: web.Request) -> web.Response:
    unauth = _require_bearer(request)
    if unauth is not None:
        return unauth
    state: State = request.app[STATE_KEY]
    etag = state.etag
    if_none_header = request.headers.get("If-None-Match")
    since_query = request.query.get("since")
    # Header takes precedence over ?since= (matches the Scala route's
    # `.orElse(req.url.queryParam("since"))`).
    if_none = if_none_header or since_query
    if if_none is not None and if_none == etag:
        state.record_policy_fetch(
            if_none_match=if_none_header,
            since_query=since_query,
            served_etag=etag,
            status=304,
        )
        resp = web.Response(status=304)
        resp.headers["ETag"] = etag
        return resp
    state.record_policy_fetch(
        if_none_match=if_none_header,
        since_query=since_query,
        served_etag=etag,
        status=200,
    )
    resp = web.json_response(state.snapshot)
    resp.headers["ETag"] = etag
    return resp


async def post_events(request: web.Request) -> web.Response:
    unauth = _require_bearer(request)
    if unauth is not None:
        return unauth
    body = await _read_json(request)
    state: State = request.app[STATE_KEY]
    state.record_event_batch(body)
    return web.json_response({"ok": True})


async def post_usage(request: web.Request) -> web.Response:
    unauth = _require_bearer(request)
    if unauth is not None:
        return unauth
    body = await _read_json(request)
    state: State = request.app[STATE_KEY]
    state.record_usage(body)
    return web.json_response({"ok": True})


async def get_blocklist(request: web.Request) -> web.Response:
    """Serve a blocklist by id — mirrors the real GET /api/blocklists/<id>.

    The agent's blocklists.lua fetches this URL, parses it as a
    newline-delimited host list (lines starting with # are comments), and
    caches the result on disk keyed by (id, version). The fake serves
    whatever content was registered via POST /test/blocklist; if no content
    has been registered for the id it returns 404 so the agent logs a fetch
    error (matching the behaviour of an unknown blocklist id in production).
    """
    unauth = _require_bearer(request)
    if unauth is not None:
        return unauth
    state: State = request.app[STATE_KEY]
    bl_id = request.match_info["id"]
    content = state.get_blocklist(bl_id)
    if content is None:
        return web.Response(status=404, text=f"blocklist {bl_id!r} not registered")
    return web.Response(
        status=200,
        text=content,
        content_type="text/plain",
    )


# --- Websocket transport (#1939: server-side push for the Gate-2 e2e) --------
#
# Mirrors the real API's GET /api/router/ws (api/src/routes/RouterWsRoutes.scala)
# closely enough to exercise the agent's wifihaven-ws sidecar end-to-end:
#   - auth at upgrade: a missing/empty bearer is rejected 401 BEFORE the 101,
#     exactly like the REST routes (and the real ws route),
#   - on connect: push exactly one `policy` frame with the current snapshot
#     (the #1849 first-policy-on-connect push),
#   - on change: POST /test/snapshot fans a fresh `policy` frame to every
#     connected channel (the #1849 push-on-change),
#   - inbound: `usage` and `events` frames are demuxed and recorded into the
#     SAME state the HTTP handlers write (#2642 — see _handle_inbound_frame),
#     so a scenario's /test/usage + /test/events observables are satisfied by
#     either transport. Those are the only two ops the sidecar can send: the
#     metrics push stays on HTTP always (ws_outbound.lua's tee matches only
#     /api/router/{usage,events}). Payloads are recorded unvalidated — the
#     fake is a delivery observable, and ingest-SCHEMA parity remains Gate 3's
#     job (lib/ws_send.py against the real API). The real server acks data
#     frames; the agent's drain does NOT gate on an ack
#     (ws_loop.drain_and_send), so the fake omits acks entirely.
#   - control frames: the upgrade sets autoping=False so the read loop can see
#     the pong the #2642 liveness probe depends on, which makes answering the
#     sidecar's heartbeat ping the handler's own job (get_ws below).


def _policy_frame_text(snapshot: dict[str, Any]) -> str:
    """The `{op:"policy", payload:<snapshot>}` envelope a pushed snapshot rides.

    Byte-for-byte the shape RouterWsRegistry.policyFrameText produces, so the
    agent's ws_loop sees an identical frame whether the push came from the real
    Scala API or this fake.
    """
    return json.dumps({"op": "policy", "payload": snapshot})


async def _push_policy(state: State, ws: web.WebSocketResponse) -> bool:
    """Send one `policy` frame to a single channel; True on success.

    A send failure (a racing disconnect) returns False so the caller can drop
    the dead channel — same posture as the real registry's channel_closed path.
    """
    try:
        await ws.send_str(_policy_frame_text(state.snapshot))
        state.note_policy_frame_sent()
        # #2608: a successful push DELIVERED this etag, so record it alongside
        # the HTTP polls. With ws the shipped default the agent's poll is dormant
        # on a healthy link (#2037), and every scenario that synchronises on
        # `wait_for_etag_served` would otherwise wait forever for a fetch that no
        # longer happens.
        state.record_policy_push(served_etag=state.etag)
        return True
    except Exception:  # noqa: BLE001
        return False


async def _push_policy_to_all(state: State) -> int:
    """Fan the current snapshot to every connected channel; return push count."""
    pushed = 0
    for ws in list(state.ws_connections):
        if await _push_policy(state, ws):
            pushed += 1
        else:
            state.deregister_ws(ws)
    return pushed


async def _prune_dead_ws_channels(
    state: State, *, timeout_s: float | None = None, interval_s: float = 0.1
) -> int:
    """Ping every registered channel and drop the ones that don't pong (#2642).

    Run from `POST /test/reset`, i.e. immediately after the `router` fixture's
    qemu `loadvm`. A restore is invisible from the server side and leaves the
    channel in one of two states the fake CANNOT tell apart by inspection:

      - usable — the hypothesis (not a verified mechanism: nothing here observes
        what `loadvm` does to a TCP stream) is that reverting guest memory in
        place leaves the host end of the socket untouched, so the restored
        sidecar carries on using the connection the fake holds. Dropping this
        one strands the scenario: nothing severed the socket, so the sidecar
        never reconnects, and a later `POST /test/snapshot` pushes to an empty
        set while the agent's poll sits dormant on a healthy link (#2037). That
        was the #2642 Gate-2 etag timeout.
      - wedged — the restored guest's TCP sequence numbers rewind to their
        snapshot values while the host's have moved on, so the stream can no
        longer be parsed on one or both ends. `send_str` still SUCCEEDS into the
        local buffer, so keeping this one is worse than dropping it: the push
        gets recorded as a delivery (`_push_policy` → `record_policy_push`) that
        the router never saw, turning a loud timeout into a silent false pass.

    So neither guess is safe, and the fake measures instead. A pong is the one
    thing a wedged channel cannot produce: it requires the guest's ws client to
    have parsed our ping off a correctly sequenced stream and written a reply
    back. The sidecar answers a server ping inline in its recv path
    (`ws_client.lua`, "Transparently answers server ping→pong"), and its recv
    loop ticks on `ws.poll_interval` — 1s by default
    (`ws_loop.DEFAULT_POLL_INTERVAL`) — so a live channel answers well inside
    the budget here. A channel that doesn't answer is CLOSED, not just dropped:
    closing pushes a FIN the wedged guest's TCP stack still acts on, so its
    sidecar notices the dead socket and reconnects promptly instead of sitting
    on a link only the fake knows is gone.

    Returns the number of channels dropped. The common path costs one poll tick
    (the loop sleeps before its first re-check), and the ceiling is
    `N × _WS_CTL_TIMEOUT_S` for the pings + `timeout_s + interval_s` for the wait
    + one `_WS_CTL_TIMEOUT_S` for the concurrent closes — comfortably inside the
    caller's 10s at the N=1 the harness runs. The full wait is only paid when a
    channel really is dead, which is exactly when waiting is worth it.
    """
    if timeout_s is None:
        timeout_s = state.ws_probe_timeout_s
    channels = list(state.ws_connections)
    if not channels:
        return 0
    before = {ws: state.pong_count(ws) for ws in channels}
    dead = [ws for ws in channels if not await _ping_or_dead(ws)]
    pending = [ws for ws in channels if ws not in dead]

    loop = asyncio.get_running_loop()
    deadline = loop.time() + timeout_s
    while pending and loop.time() < deadline:
        await asyncio.sleep(interval_s)
        pending = [ws for ws in pending if state.pong_count(ws) <= before[ws]]

    dead += pending
    for ws in dead:
        state.deregister_ws(ws)
    # Concurrently, so N dead channels cost one bounded close rather than N.
    await asyncio.gather(*(_close_quietly(ws) for ws in dead))
    return len(dead)


async def _ping_or_dead(ws: web.WebSocketResponse) -> bool:
    """Send the liveness ping; False if the channel is already unusable.

    Bounded for the same reason the close is: `ping()` reaches aiohttp's writer,
    which awaits a drain once the transport is paused, and a peer that stopped
    ACKing is exactly how it gets paused. A ping we cannot even send is the
    answer the probe wanted anyway, so a timeout here is just an early "dead".
    """
    try:
        await asyncio.wait_for(ws.ping(), timeout=_WS_CTL_TIMEOUT_S)
    except Exception:  # noqa: BLE001
        return False
    return True


async def _close_quietly(ws: web.WebSocketResponse) -> None:
    """Best-effort bounded close of a channel that failed the probe.

    The close is the load-bearing half of dropping a channel: the FIN is what
    makes a real sidecar notice the socket is gone and reconnect. It is still
    best-effort — the channel is already deregistered, so a close that never
    lands costs the peer only a slower reconnect, and aiohttp closes the
    transport even when the close is cancelled mid-wait.
    """
    with contextlib.suppress(Exception):
        await asyncio.wait_for(ws.close(), timeout=_WS_CTL_TIMEOUT_S)


def _handle_inbound_frame(state: State, text: str) -> None:
    """Demux one inbound `{op, payload}` text frame into the fake's state.

    #2642. With ws the shipped router default (#2608) and the link healthy, the
    agent's outbound tee (`ws_outbound.make`) hands usage/events bodies to the
    sidecar as frames INSTEAD of POSTing them — so `POST /api/router/usage` and
    `POST /api/router/events` stop being exercised, and every Gate-2 scenario
    that waits on a `/test/usage` or `/test/events` record hangs. Recording the
    frame payload into the same lists the HTTP handlers write is what keeps
    those observables transport-agnostic, the same way `record_policy_push`
    keeps the policy-delivery observable transport-agnostic.

    The payload of a `usage`/`events` frame is byte-for-byte the body its REST
    counterpart takes (`ws_outbound.make` wraps the very body it would have
    POSTed), so recording it needs no translation. That is where the parity with
    the real server ends: `RouterWsRoutes.scala` decodes the payload, runs it
    through the shared ingest services, and acks `ok`/`reject` on a typed error,
    while this records any dict unvalidated. Gate 3's `lib/ws_send.py` owns
    ingest-schema parity against the real API; a green Gate 2 is delivery
    evidence, not schema evidence.

    `usage` and `events` are the whole inbound vocabulary the sidecar can
    produce — the metrics push stays on HTTP always (`ws_outbound.lua`: the tee
    matches only `/api/router/{usage,events}$`), and the fake has no metrics
    surface anyway (its `POST /api/router/metrics` 404s).

    Never raises: a malformed frame or an unrecognized op is dropped, the
    forward-compat rule (design §1.3) and also plain self-defence — an
    exception in the read loop would tear down the channel the whole scenario
    depends on.
    """
    try:
        frame = json.loads(text)
    except Exception:  # noqa: BLE001
        return
    if not isinstance(frame, dict):
        return
    payload = frame.get("payload")
    if not isinstance(payload, dict):
        return
    op = frame.get("op")
    if op == "usage":
        state.record_usage(payload, transport="ws")
    elif op == "events":
        state.record_event_batch(payload, transport="ws")


async def get_ws(request: web.Request) -> web.StreamResponse:
    # Auth at upgrade time. Reject a missing/empty bearer with the SAME 401 the
    # REST routes produce (no 101) before preparing the websocket.
    if _bearer_token(request) is None:
        return web.json_response({"error": "Missing router token"}, status=401)
    state: State = request.app[STATE_KEY]
    # autoping=False so the read loop below SEES control frames. aiohttp's
    # default handles PING/PONG internally and never surfaces them, which would
    # hide the pong `_prune_dead_ws_channels` needs as its liveness signal
    # (aiohttp's web_ws.py `receive()` returns PING/PONG only when autoping is
    # off — its internal-type handling is guarded on `self._autoping` — and that
    # has held across the aiohttp>=3.9 floor this package pins). The cost is that
    # the loop must answer the sidecar's own heartbeat ping itself — see below.
    ws = web.WebSocketResponse(autoping=False)
    await ws.prepare(request)
    state.register_ws(ws)
    # #1849 first-policy-on-connect push.
    await _push_policy(state, ws)
    try:
        async for msg in ws:
            if msg.type == WSMsgType.ERROR:
                break
            # TEXT carries the agent's usage/events frames — those two are its
            # whole outbound vocabulary — and they are recorded into the same
            # state the REST handlers write (#2642). BINARY is never sent
            # (ws_loop sends text frames only), so it falls through and is
            # dropped, as is any unrecognised type.
            if msg.type == WSMsgType.TEXT:
                _handle_inbound_frame(state, msg.data)
            elif msg.type == WSMsgType.PING:
                # The sidecar's RFC-6455 heartbeat (ws_loop's `send_ping("hb")`
                # on ws.heartbeat_interval, design §5.5) — distinct from the
                # application-level `{op:"ping"}` the real server answers.
                # With autoping off, answering is ours. Nothing forces it: the
                # sidecar has no pong deadline (its recv just loops on a pong,
                # ws_client.lua), which is precisely why autoping=False is safe
                # here — a heartbeat we answer late, or during a push, cannot
                # drop the link. We answer anyway so the socket behaves like the
                # real endpoint rather than like a peer that ignores control
                # frames.
                await ws.pong(msg.data)
            elif msg.type == WSMsgType.PONG:
                # #2642: the reply to `_prune_dead_ws_channels`' liveness ping —
                # the one thing a wedged channel cannot produce.
                state.note_pong(ws)
    finally:
        state.deregister_ws(ws)
    return ws


# --- Test-control endpoints --------------------------------------------------


async def test_post_snapshot(request: web.Request) -> web.Response:
    body = await _read_json(request)
    state: State = request.app[STATE_KEY]
    state.replace_snapshot(body)
    # #1939/#1849: a snapshot change pushes ONE fresh `policy` frame to every
    # connected ws channel. The HTTP poll path (GET /api/router/policy) is
    # unchanged; a scenario that wants to prove the push specifically widens the
    # agent's poll interval so only this push can deliver the new etag.
    pushed = await _push_policy_to_all(state)
    return web.json_response({"ok": True, "etag": state.etag, "wsPushed": pushed})


async def test_get_events(request: web.Request) -> web.Response:
    state: State = request.app[STATE_KEY]
    since_id = request.query.get("since_id")
    mac = request.query.get("mac")

    batches = state.events
    if since_id is not None:
        try:
            cutoff = int(since_id)
        except ValueError:
            raise web.HTTPBadRequest(reason="since_id must be an integer")
        batches = [b for b in batches if b.id > cutoff]

    flat: list[dict[str, Any]] = []
    for b in batches:
        for ev in b.body.get("events", []) or []:
            ev_copy = copy.deepcopy(ev)
            ev_copy["_batchId"] = b.id
            # #2642: which transport carried the batch — "http" for a POST,
            # "ws" for an inbound frame. Underscore-prefixed like _batchId
            # because it is harness bookkeeping, not part of the event body.
            ev_copy["_transport"] = b.transport
            flat.append(ev_copy)
    if mac is not None:
        flat = [e for e in flat if e.get("mac") == mac]

    return web.json_response(
        {
            "batches": [
                {"id": b.id, "body": b.body, "transport": b.transport}
                for b in batches
            ],
            "events": flat,
        }
    )


async def test_get_usage(request: web.Request) -> web.Response:
    state: State = request.app[STATE_KEY]
    since_id = request.query.get("since_id")
    reports = state.usage
    if since_id is not None:
        try:
            cutoff = int(since_id)
        except ValueError:
            raise web.HTTPBadRequest(reason="since_id must be an integer")
        reports = [r for r in reports if r.id > cutoff]
    return web.json_response(
        {
            "reports": [
                {"id": r.id, "body": r.body, "transport": r.transport}
                for r in reports
            ]
        }
    )


async def test_get_register(request: web.Request) -> web.Response:
    state: State = request.app[STATE_KEY]
    return web.json_response(
        {
            "routerId": state.router_id,
            "routerToken": state.router_token,
            "requests": [
                {"body": r.body, "headers": r.headers} for r in state.registers
            ],
        }
    )


async def test_get_policy_fetches(request: web.Request) -> web.Response:
    """Return captured policy-fetch metadata.

    Added by #683 so qemu scenarios can wait for "agent has fetched the
    current snapshot's etag" without polling an admin API.
    """
    state: State = request.app[STATE_KEY]
    since_id = request.query.get("since_id")
    fetches = state.policy_fetches
    if since_id is not None:
        try:
            cutoff = int(since_id)
        except ValueError:
            raise web.HTTPBadRequest(reason="since_id must be an integer")
        fetches = [f for f in fetches if f.id > cutoff]
    return web.json_response(
        {
            "count": len(state.policy_fetches),
            "fetches": [
                {
                    "id": f.id,
                    "ifNoneMatch": f.if_none_match,
                    "sinceQuery": f.since_query,
                    "servedEtag": f.served_etag,
                    "status": f.status,
                    "transport": f.transport,
                }
                for f in fetches
            ],
        }
    )


async def test_post_blocklist(request: web.Request) -> web.Response:
    """Register blocklist content for GET /api/blocklists/<id>.

    Body: { "id": "<blocklist-id>", "hosts": ["host1", "host2", ...] }

    The hosts are stored as a newline-delimited plain-text body, exactly the
    format blocklists.lua expects. An empty `hosts` list is valid — it
    registers an empty blocklist (useful for clearing a previous registration
    without removing the id from the snapshot's `blocklists` dict).
    """
    body = await _read_json(request)
    bl_id = body.get("id")
    if not bl_id or not isinstance(bl_id, str):
        raise web.HTTPBadRequest(reason="missing or invalid 'id' field")
    hosts = body.get("hosts", [])
    if not isinstance(hosts, list):
        raise web.HTTPBadRequest(reason="'hosts' must be a list")
    content = "\n".join(str(h) for h in hosts)
    if content:
        content += "\n"
    state: State = request.app[STATE_KEY]
    state.set_blocklist(bl_id, content)
    return web.json_response({"ok": True, "id": bl_id, "host_count": len(hosts)})


async def test_get_ws_status(request: web.Request) -> web.Response:
    """Report ws push state so scenarios can sync on "the agent connected".

    `connections` is the live channel count; `policyFramesSent` is the
    cumulative count of `policy` frames the fake has pushed (on-connect +
    on-change). Added by #1939 so the Gate-2 ws-push scenario can wait for the
    sidecar to upgrade before triggering a change, and assert the server side
    of the push independently of the router-side receive observables.
    """
    state: State = request.app[STATE_KEY]
    return web.json_response(
        {
            "connections": len(state.ws_connections),
            "policyFramesSent": state.ws_policy_frames_sent,
        }
    )


async def test_post_reset(request: web.Request) -> web.Response:
    state: State = request.app[STATE_KEY]
    # #2642: probe the ws channels BEFORE clearing the record lists. The probe
    # awaits, so the read loop runs during it and can drain a usage/events frame
    # the previous scenario left in flight; doing the clear afterwards is what
    # keeps that out of this scenario's lists. It does not close the window
    # entirely — a frame still sitting in the host's receive buffer when reset()
    # returns lands after the clear, which is what the `transport` marker on the
    # records is for. The probe is also what decides which channels survive a VM
    # restore; `state.reset()` deliberately does not touch the set.
    dropped = await _prune_dead_ws_channels(state)
    state.reset()
    return web.json_response(
        {"ok": True, "etag": state.etag, "wsDropped": dropped}
    )


async def test_post_clock(request: web.Request) -> web.Response:
    # Cosmetic per the #654 roll-up: stamps `generatedAt` on snapshots
    # loaded after this call. Not load-bearing for v1 since /test/snapshot
    # is the primary control surface and callers can stamp generatedAt
    # themselves in the body they POST.
    body = await _read_json(request)
    state: State = request.app[STATE_KEY]
    state.clock_base = body.get("now")
    return web.json_response({"ok": True, "now": state.clock_base})


# --- App factory -------------------------------------------------------------


def make_app(state: State | None = None) -> web.Application:
    app = web.Application()
    app[STATE_KEY] = state if state is not None else State.fresh()
    app.router.add_post("/api/router/register", post_register)
    app.router.add_get("/api/router/policy", get_policy)
    app.router.add_post("/api/router/events", post_events)
    app.router.add_post("/api/router/usage", post_usage)
    app.router.add_get("/api/blocklists/{id}", get_blocklist)
    app.router.add_get("/api/router/ws", get_ws)
    app.router.add_post("/test/snapshot", test_post_snapshot)
    app.router.add_get("/test/ws_status", test_get_ws_status)
    app.router.add_post("/test/blocklist", test_post_blocklist)
    app.router.add_get("/test/events", test_get_events)
    app.router.add_get("/test/usage", test_get_usage)
    app.router.add_get("/test/register", test_get_register)
    app.router.add_get("/test/policy_fetches", test_get_policy_fetches)
    app.router.add_post("/test/reset", test_post_reset)
    app.router.add_post("/test/clock", test_post_clock)
    return app
