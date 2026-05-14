"""Admin API client. Bearer-token auth via /api/auth/login."""
from __future__ import annotations

import json
import logging
from typing import Any

import urllib.error
import urllib.request

log = logging.getLogger(__name__)


class AdminAPI:
    def __init__(self, base_url: str, *, username: str = "admin", password: str = "changeme"):
        self.base_url = base_url.rstrip("/")
        self.username = username
        self.password = password
        self._token: str | None = None

    # ── auth ──────────────────────────────────────────────────────────────

    def login(self) -> str:
        body = self._request(
            "POST", "/api/auth/login",
            body={"username": self.username, "password": self.password},
            authed=False,
        )
        token = body.get("token")
        if not token:
            raise RuntimeError(f"login response missing token: {body!r}")
        self._token = token
        return token

    @property
    def token(self) -> str:
        if not self._token:
            self.login()
        return self._token  # type: ignore[return-value]

    # ── routers ───────────────────────────────────────────────────────────

    def create_router(self, name: str) -> dict[str, Any]:
        """Returns {routerId, name, enrollmentToken}."""
        return self._request("POST", "/api/admin/routers", body={"name": name})

    def list_routers(self) -> list[dict[str, Any]]:
        return self._request("GET", "/api/admin/routers")

    def delete_router(self, router_id: str) -> None:
        self._request("DELETE", f"/api/admin/routers/{router_id}")

    def get_router(self, router_id: str) -> dict[str, Any] | None:
        for r in self.list_routers():
            if r.get("id") == router_id:
                return r
        return None

    # ── profiles ──────────────────────────────────────────────────────────

    def create_profile(self, **fields: Any) -> dict[str, Any]:
        defaults = {
            "name": fields.pop("name", "test-profile"),
            "blockedCategories": [],
            "extraBlocked": [],
            "extraAllowed": [],
            "paused": False,
            "schedules": [],
            "timeLimit": None,
            "siteTimeLimits": [],
            "failureMode": "closed",
        }
        defaults.update(fields)
        return self._request("POST", "/api/profiles", body=defaults)

    def update_profile(self, profile_id: int, **fields: Any) -> dict[str, Any]:
        # PUT requires the full nested shape; callers should fetch + merge.
        return self._request("PUT", f"/api/profiles/{profile_id}", body=fields)

    def get_profile(self, profile_id: int) -> dict[str, Any]:
        return self._request("GET", f"/api/profiles/{profile_id}")

    def list_profiles(self) -> list[dict[str, Any]]:
        return self._request("GET", "/api/profiles")

    def delete_profile(self, profile_id: int) -> None:
        self._request("DELETE", f"/api/profiles/{profile_id}")

    def set_profile_paused(self, profile_id: int, paused: bool) -> dict[str, Any]:
        full = self.get_profile(profile_id)
        prof = full.get("profile", full)
        return self.update_profile(profile_id, **{**prof, "paused": paused})

    # ── devices ───────────────────────────────────────────────────────────

    def upsert_device(self, *, mac: str, name: str, profile_id: int) -> dict[str, Any]:
        return self._request(
            "PUT", "/api/devices",
            body={"mac": mac, "name": name, "profileId": profile_id},
        )

    def list_devices(self) -> list[dict[str, Any]]:
        return self._request("GET", "/api/devices")

    def delete_device(self, mac: str) -> None:
        self._request("DELETE", f"/api/devices/{mac}")

    # ── logs / sessions / stats ───────────────────────────────────────────

    def logs(self, **params: Any) -> list[dict[str, Any]]:
        qs = self._qs(params)
        return self._request("GET", f"/api/logs{qs}")

    def sessions(self, **params: Any) -> dict[str, Any]:
        qs = self._qs(params)
        return self._request("GET", f"/api/sessions{qs}")

    def time_status(self) -> list[dict[str, Any]]:
        return self._request("GET", "/api/time/status")

    # ── HTTP plumbing ─────────────────────────────────────────────────────

    @staticmethod
    def _qs(params: dict[str, Any]) -> str:
        from urllib.parse import urlencode
        if not params:
            return ""
        return "?" + urlencode({k: v for k, v in params.items() if v is not None})

    def _request(
        self,
        method: str,
        path: str,
        *,
        body: Any = None,
        authed: bool = True,
        timeout: float = 15.0,
    ) -> Any:
        url = self.base_url + path
        data = None
        headers = {"accept": "application/json"}
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["content-type"] = "application/json"
        if authed:
            headers["authorization"] = f"Bearer {self.token}"

        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:  # noqa: S310
                raw = resp.read().decode("utf-8")
                if not raw:
                    return None
                ctype = resp.headers.get("content-type", "")
                if "application/json" in ctype:
                    return json.loads(raw)
                return raw
        except urllib.error.HTTPError as e:
            err_body = e.read().decode("utf-8", errors="replace")
            raise RuntimeError(
                f"{method} {url} → HTTP {e.code}\n{err_body}"
            ) from e
