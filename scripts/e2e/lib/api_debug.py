"""Debug API client — wraps the loopback-only /api/debug/* endpoints (#228).

Requires WIFIHAVEN_DEBUG=1 on the API service. The orchestrator's compose
override sets this; nothing in production exposes these routes.

The endpoints reject non-loopback callers based on the connection's remote
address. When the API runs in docker compose and the orchestrator hits it via
the host-side port-mapping, docker-proxy NATs the connection and the API sees
the docker bridge gateway IP (e.g. 172.19.0.1) — which the loopback gate
rejects with 403. To stay loopback-from-the-API's-point-of-view we shell into
the api container and curl 127.0.0.1 directly.
"""
from __future__ import annotations

import json
import os
import subprocess
from typing import Any


class DebugAPI:
    def __init__(self, base_url: str, *, container: str | None = None):
        self.base_url = base_url.rstrip("/")
        # Container name to docker-exec into. Defaults to the compose-managed
        # api container created by the e2e stack lifecycle (see stack.py).
        self.container = container or os.environ.get(
            "E2E_VM_API_CONTAINER", "familydns-e2e-vm-api-1"
        )

    def devices(self) -> list[dict[str, Any]]:
        return self._get("/api/debug/devices")

    def events(self, *, limit: int = 200) -> list[dict[str, Any]]:
        return self._get(f"/api/debug/events?limit={limit}")

    def time_usage(self) -> list[dict[str, Any]]:
        return self._get("/api/debug/time_usage")

    def events_for_mac(self, mac: str, *, limit: int = 200) -> list[dict[str, Any]]:
        return [e for e in self.events(limit=limit) if (e.get("mac") or "").lower() == mac.lower()]

    def events_for_mac_filtered(
        self,
        mac: str,
        *,
        hostname: str | None = None,
        allowed: bool | None = None,
        reason: str | None = None,
        limit: int = 200,
    ) -> list[dict[str, Any]]:
        """Filter `events_for_mac` by common predicates.

        `hostname` is substring-matched (case-insensitive); `allowed` and
        `reason` must be exact. All None-valued filters are ignored.
        """
        out = []
        host_needle = hostname.lower() if hostname else None
        for e in self.events_for_mac(mac, limit=limit):
            if host_needle is not None:
                if host_needle not in event_hostname(e).lower():
                    continue
            if allowed is not None and e.get("allowed") is not allowed:
                continue
            if reason is not None and e.get("reason") != reason:
                continue
            out.append(e)
        return out

    def _get(self, path: str) -> Any:
        url = f"http://127.0.0.1:8080{path}"
        proc = subprocess.run(
            [
                "docker", "exec", self.container,
                "curl", "-fsS", "-H", "accept: application/json", url,
            ],
            capture_output=True, text=True, timeout=15,
        )
        if proc.returncode != 0:
            raise RuntimeError(
                f"debug GET {path} via docker exec failed (rc={proc.returncode}): "
                f"{proc.stderr.strip()}"
            )
        return json.loads(proc.stdout)


def event_hostname(e: dict[str, Any]) -> str:
    """Return the FQDN attribution on a connection_attempt event, or "".

    The wire schema (#391) carries the resolved hostname inside a tagged
    `host` union: `{"type": "fqdn", "value": "<name>"}` when DNS attribution
    landed, or `{"type": "ipv4"|"ipv6", "value": "<ip>"}` when the agent only
    had a raw destination IP. Tests want to match on the FQDN, not on a raw
    IP that happens to substring-match — so filter on `type == "fqdn"`.
    """
    host = e.get("host") or {}
    if isinstance(host, dict) and host.get("type") == "fqdn":
        return host.get("value") or ""
    return ""
