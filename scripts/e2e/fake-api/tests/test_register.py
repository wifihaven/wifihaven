from __future__ import annotations

REGISTER_BODY = {
    "enrollmentToken": "et_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "platformVersion": "OpenWrt 23.05.5",
    "agentVersion": "wifihaven-agent 1.2.3",
}


async def test_register_returns_canned_credentials(client, state):
    resp = await client.post("/api/router/register", json=REGISTER_BODY)
    assert resp.status == 200
    payload = await resp.json()
    assert payload == {
        "routerId": state.router_id,
        "routerToken": state.router_token,
    }


async def test_register_is_deterministic_per_instance(client):
    # Two calls against the same fake return the same credentials.
    r1 = await (await client.post("/api/router/register", json=REGISTER_BODY)).json()
    r2 = await (await client.post("/api/router/register", json=REGISTER_BODY)).json()
    assert r1 == r2


async def test_register_request_is_captured(client):
    await client.post("/api/router/register", json=REGISTER_BODY)
    resp = await client.get("/test/register")
    payload = await resp.json()
    assert len(payload["requests"]) == 1
    assert payload["requests"][0]["body"] == REGISTER_BODY


async def test_register_does_not_require_bearer(client):
    # Enrollment is the one router-facing endpoint that authenticates via the
    # request body's enrollmentToken, not a pre-existing routerToken.
    resp = await client.post("/api/router/register", json=REGISTER_BODY)
    assert resp.status == 200
