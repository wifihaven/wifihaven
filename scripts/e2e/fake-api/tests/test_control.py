from __future__ import annotations

from fake_api.fixtures import load_initial_snapshot


async def test_reset_clears_state_and_restores_initial_snapshot(client, auth_headers):
    new_snap = {
        "etag": '"sha256:tempetag"',
        "generatedAt": "2026-05-19T00:00:00Z",
        "devices": {},
        "profiles": {},
    }
    await client.post("/test/snapshot", json=new_snap)
    await client.post(
        "/api/router/events",
        json={"routerId": "r", "events": [{"mac": "aa:bb:cc:00:00:01"}]},
        headers=auth_headers,
    )
    await client.post(
        "/api/router/usage",
        json={"routerId": "r", "records": []},
        headers=auth_headers,
    )
    await client.post("/api/router/register", json={"enrollmentToken": "et_x"})

    resp = await client.post("/test/reset")
    assert resp.status == 200

    # Snapshot restored.
    resp = await client.get("/api/router/policy", headers=auth_headers)
    body = await resp.json()
    assert body == load_initial_snapshot()

    # Captured state cleared.
    for path, key in [
        ("/test/events", "batches"),
        ("/test/usage", "reports"),
    ]:
        resp = await client.get(path)
        payload = await resp.json()
        assert payload[key] == []

    resp = await client.get("/test/register")
    payload = await resp.json()
    assert payload["requests"] == []

    # And the event id counter restarts.
    await client.post(
        "/api/router/events",
        json={"routerId": "r", "events": []},
        headers=auth_headers,
    )
    resp = await client.get("/test/events")
    payload = await resp.json()
    assert payload["batches"][0]["id"] == 1


async def test_clock_sets_base_instant(client, state):
    resp = await client.post("/test/clock", json={"now": "2026-05-19T00:00:00Z"})
    assert resp.status == 200
    assert state.clock_base == "2026-05-19T00:00:00Z"
