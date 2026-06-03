"""aiohttp application factory for the fake WifiHaven router API.

Production-shaped endpoints (`/api/router/*`) mirror the surface that the
OpenWRT lua agent (`openwrt/files/usr/lib/lua/wifihaven/policy.lua`, etc.)
hits against the real Scala API (`api/src/routes/RouterRoutes.scala`).
Test-control endpoints (`/test/*`) let pytest scenarios drive the fake.
"""

from __future__ import annotations

import copy
from typing import Any

from aiohttp import web

from .state import State

STATE_KEY = web.AppKey("state", State)


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


# --- Test-control endpoints --------------------------------------------------


async def test_post_snapshot(request: web.Request) -> web.Response:
    body = await _read_json(request)
    state: State = request.app[STATE_KEY]
    state.replace_snapshot(body)
    return web.json_response({"ok": True, "etag": state.etag})


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
            flat.append(ev_copy)
    if mac is not None:
        flat = [e for e in flat if e.get("mac") == mac]

    return web.json_response(
        {
            "batches": [{"id": b.id, "body": b.body} for b in batches],
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
        {"reports": [{"id": r.id, "body": r.body} for r in reports]}
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


async def test_post_reset(request: web.Request) -> web.Response:
    state: State = request.app[STATE_KEY]
    state.reset()
    return web.json_response({"ok": True, "etag": state.etag})


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
    app.router.add_post("/test/snapshot", test_post_snapshot)
    app.router.add_post("/test/blocklist", test_post_blocklist)
    app.router.add_get("/test/events", test_get_events)
    app.router.add_get("/test/usage", test_get_usage)
    app.router.add_get("/test/register", test_get_register)
    app.router.add_get("/test/policy_fetches", test_get_policy_fetches)
    app.router.add_post("/test/reset", test_post_reset)
    app.router.add_post("/test/clock", test_post_clock)
    return app
