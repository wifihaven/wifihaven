"""`python -m fake_api` — serve the fake on a local port for manual poking.

Honors `WH_FAKE_API_PORT` (default 8765) and `WH_FAKE_API_HOST` (default
127.0.0.1). New env vars use the `WH_` prefix per repo convention.
"""

from __future__ import annotations

import os

from aiohttp import web

from .app import make_app


def main() -> None:
    host = os.environ.get("WH_FAKE_API_HOST", "127.0.0.1")
    port = int(os.environ.get("WH_FAKE_API_PORT", "8765"))
    web.run_app(make_app(), host=host, port=port)


if __name__ == "__main__":
    main()
