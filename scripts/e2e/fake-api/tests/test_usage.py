from __future__ import annotations

USAGE_BODY = {
    "routerId": "11111111-2222-3333-4444-555555555555",
    "periodStart": "2026-05-17T14:00:00Z",
    "periodEnd": "2026-05-17T14:05:00Z",
    "records": [
        {
            "mac": "aa:bb:cc:11:22:33",
            "host": {"type": "fqdn", "value": "youtube.com"},
            "bytesIn": 12345,
            "bytesOut": 678,
            "activeSeconds": 30,
        }
    ],
}


async def test_post_usage_captures_body_verbatim(client, auth_headers):
    resp = await client.post(
        "/api/router/usage", json=USAGE_BODY, headers=auth_headers
    )
    assert resp.status == 200

    resp = await client.get("/test/usage")
    payload = await resp.json()
    assert len(payload["reports"]) == 1
    assert payload["reports"][0]["body"] == USAGE_BODY


async def test_usage_since_id_filter(client, auth_headers):
    await client.post("/api/router/usage", json=USAGE_BODY, headers=auth_headers)
    await client.post("/api/router/usage", json=USAGE_BODY, headers=auth_headers)

    resp = await client.get("/test/usage", params={"since_id": "1"})
    payload = await resp.json()
    assert [r["id"] for r in payload["reports"]] == [2]


async def test_usage_requires_bearer(client):
    resp = await client.post("/api/router/usage", json=USAGE_BODY)
    assert resp.status == 401
