"""Debug API client — wraps the loopback-only /api/debug/* endpoints (#228).

Requires FAMILYDNS_DEBUG=1 on the API service. The orchestrator's compose
override sets this; nothing in production exposes these routes.
"""
from __future__ import annotations

import json
import urllib.request
from typing import Any


class DebugAPI:
    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/")

    def devices(self) -> list[dict[str, Any]]:
        return self._get("/api/debug/devices")

    def events(self, *, limit: int = 200) -> list[dict[str, Any]]:
        return self._get(f"/api/debug/events?limit={limit}")

    def time_usage(self) -> list[dict[str, Any]]:
        return self._get("/api/debug/time_usage")

    def events_for_mac(self, mac: str, *, limit: int = 200) -> list[dict[str, Any]]:
        return [e for e in self.events(limit=limit) if (e.get("mac") or "").lower() == mac.lower()]

    def _get(self, path: str) -> Any:
        req = urllib.request.Request(self.base_url + path, headers={"accept": "application/json"})
        with urllib.request.urlopen(req, timeout=10) as resp:  # noqa: S310
            return json.loads(resp.read().decode("utf-8"))
