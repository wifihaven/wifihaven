from __future__ import annotations

EVENTS_BODY = {
    "routerId": "11111111-2222-3333-4444-555555555555",
    "events": [
        {
            "type": "connection_attempt",
            "mac": "aa:bb:cc:11:22:33",
            "host": {"type": "fqdn", "value": "youtube.com"},
            "destIp": "142.250.80.46",
            "allowed": False,
            "reason": "blocked",
            "ts": "2026-05-17T14:00:01Z",
            "eventId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        },
        {
            "type": "connection_attempt",
            "mac": "de:ad:be:ef:00:01",
            "host": {"type": "fqdn", "value": "example.com"},
            "destIp": "93.184.216.34",
            "allowed": True,
            "ts": "2026-05-17T14:00:02Z",
            "eventId": "bbbbbbbb-cccc-dddd-eeee-ffffffffffff",
        },
    ],
}


async def test_post_events_captures_body_verbatim(client, auth_headers):
    resp = await client.post(
        "/api/router/events", json=EVENTS_BODY, headers=auth_headers
    )
    assert resp.status == 200

    resp = await client.get("/test/events")
    assert resp.status == 200
    payload = await resp.json()
    assert len(payload["batches"]) == 1
    assert payload["batches"][0]["body"] == EVENTS_BODY


async def test_events_mac_filter(client, auth_headers):
    await client.post("/api/router/events", json=EVENTS_BODY, headers=auth_headers)

    resp = await client.get("/test/events", params={"mac": "aa:bb:cc:11:22:33"})
    payload = await resp.json()
    assert len(payload["events"]) == 1
    assert payload["events"][0]["mac"] == "aa:bb:cc:11:22:33"


async def test_events_since_id_filter(client, auth_headers):
    await client.post("/api/router/events", json=EVENTS_BODY, headers=auth_headers)
    second = {"routerId": EVENTS_BODY["routerId"], "events": []}
    await client.post("/api/router/events", json=second, headers=auth_headers)

    resp = await client.get("/test/events", params={"since_id": "1"})
    payload = await resp.json()
    assert [b["id"] for b in payload["batches"]] == [2]


async def test_events_requires_bearer(client):
    resp = await client.post("/api/router/events", json=EVENTS_BODY)
    assert resp.status == 401
