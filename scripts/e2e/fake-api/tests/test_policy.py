from __future__ import annotations

from fake_api.fixtures import load_initial_snapshot


async def test_first_fetch_returns_initial_snapshot(client, auth_headers):
    resp = await client.get("/api/router/policy", headers=auth_headers)
    assert resp.status == 200
    body = await resp.json()
    assert body == load_initial_snapshot()
    assert resp.headers["ETag"] == body["etag"]


async def test_if_none_match_returns_304(client, auth_headers, state):
    etag = state.etag
    resp = await client.get(
        "/api/router/policy",
        headers={**auth_headers, "If-None-Match": etag},
    )
    assert resp.status == 304
    # 304 carries no body.
    body = await resp.read()
    assert body == b""
    assert resp.headers["ETag"] == etag


async def test_since_query_returns_304(client, auth_headers, state):
    # Agent uses ?since= as well as the header (urlencoded). aiohttp's
    # client encodes for us.
    resp = await client.get(
        "/api/router/policy",
        params={"since": state.etag},
        headers=auth_headers,
    )
    assert resp.status == 304


async def test_stale_etag_returns_200(client, auth_headers):
    resp = await client.get(
        "/api/router/policy",
        headers={**auth_headers, "If-None-Match": '"sha256:stale"'},
    )
    assert resp.status == 200
    body = await resp.json()
    assert body == load_initial_snapshot()


async def test_missing_bearer_returns_401(client):
    resp = await client.get("/api/router/policy")
    assert resp.status == 401


async def test_empty_bearer_returns_401(client):
    resp = await client.get(
        "/api/router/policy", headers={"Authorization": "Bearer "}
    )
    assert resp.status == 401


async def test_non_bearer_auth_returns_401(client):
    resp = await client.get(
        "/api/router/policy", headers={"Authorization": "Basic abc"}
    )
    assert resp.status == 401


async def test_snapshot_swap_changes_etag_and_body(client, auth_headers, state):
    old_etag = state.etag

    new_snap = {
        "etag": '"sha256:newetag0001"',
        "generatedAt": "2026-05-19T12:00:00Z",
        "devices": {},
        "profiles": {},
    }
    resp = await client.post("/test/snapshot", json=new_snap)
    assert resp.status == 200

    # If-None-Match with old etag should now miss (new body returned).
    resp = await client.get(
        "/api/router/policy",
        headers={**auth_headers, "If-None-Match": old_etag},
    )
    assert resp.status == 200
    body = await resp.json()
    assert body == new_snap
    assert resp.headers["ETag"] == new_snap["etag"]

    # And If-None-Match with the new etag should 304.
    resp = await client.get(
        "/api/router/policy",
        headers={**auth_headers, "If-None-Match": new_snap["etag"]},
    )
    assert resp.status == 304
