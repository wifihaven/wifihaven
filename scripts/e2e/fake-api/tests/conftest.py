from __future__ import annotations

import pytest

from fake_api import State, make_app


@pytest.fixture
def state() -> State:
    return State.fresh()


@pytest.fixture
async def client(aiohttp_client, state):
    app = make_app(state=state)
    return await aiohttp_client(app)


@pytest.fixture
def auth_headers() -> dict[str, str]:
    # Real API requires `Bearer <token>` — the fake accepts any non-empty
    # token (shape match, not validation).
    return {"Authorization": "Bearer test-token"}
