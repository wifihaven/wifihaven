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
            "failureMode": "block-all",
        }
        defaults.update(fields)
        return self._request("POST", "/api/profiles", body=defaults)

    def update_profile(self, profile_id: int, **fields: Any) -> dict[str, Any]:
        # PUT requires the full nested shape; callers should fetch + merge.
        return self._request("PUT", f"/api/profiles/{profile_id}", body=fields)

    def apply_profile_update(
        self, profile_id: int, **changes: Any,
    ) -> dict[str, Any]:
        """GET the profile, fold in `changes`, and PUT the merged result.

        Handles the fact that GET returns the profile in a nested shape
        (`{profile: {...}, schedules: [...], timeLimit: {...} | null,
        siteTimeLimits: [...]}`) while PUT requires a flat body and expects
        `timeLimit` to be an integer minute-count, not the object the GET
        returns. Callers should pass overrides as keyword args (e.g.
        `extraBlocked=[...]`, `timeLimit=5`).
        """
        full = self.get_profile(profile_id)
        prof = full.get("profile", full) if isinstance(full, dict) else {}
        tl = full.get("timeLimit") if isinstance(full, dict) else None
        time_limit_minutes: int | None
        if isinstance(tl, dict):
            time_limit_minutes = tl.get("dailyMinutes")
        else:
            time_limit_minutes = tl  # None or already an int
        body: dict[str, Any] = {
            "name": prof.get("name"),
            "blockedCategories": prof.get("blockedCategories", []),
            "extraBlocked": prof.get("extraBlocked", []),
            "extraAllowed": prof.get("extraAllowed", []),
            "paused": prof.get("paused", False),
            "schedules": full.get("schedules", []) if isinstance(full, dict) else [],
            "timeLimit": time_limit_minutes,
            "siteTimeLimits":
                full.get("siteTimeLimits", []) if isinstance(full, dict) else [],
            "failureMode": prof.get("failureMode", "block-all"),
        }
        body.update(changes)
        return self._request("PUT", f"/api/profiles/{profile_id}", body=body)

    def get_profile(self, profile_id: int) -> dict[str, Any]:
        return self._request("GET", f"/api/profiles/{profile_id}")

    def list_profiles(self) -> list[dict[str, Any]]:
        return self._request("GET", "/api/profiles")

    def delete_profile(self, profile_id: int) -> None:
        self._request("DELETE", f"/api/profiles/{profile_id}")

    def set_profile_paused(self, profile_id: int, paused: bool) -> dict[str, Any]:
        return self.apply_profile_update(profile_id, paused=paused)

    def add_schedule(self, profile_id: int, schedule: dict[str, Any]) -> dict[str, Any]:
        """Append a schedule to the profile via fold-and-PUT.

        `schedule` is a dict in the shape the API expects (e.g. `{"daysOfWeek":
        ["MON"], "startTime": "09:00", "endTime": "17:00", "timezone":
        "America/Los_Angeles"}`). The server may assign an `id` on round-trip;
        the returned profile reflects what was stored.
        """
        full = self.get_profile(profile_id)
        existing = full.get("schedules", []) if isinstance(full, dict) else []
        return self.apply_profile_update(profile_id, schedules=existing + [schedule])

    def remove_schedule(self, profile_id: int, schedule_id: Any) -> dict[str, Any]:
        """Drop the schedule with the given id; no-op if absent."""
        full = self.get_profile(profile_id)
        existing = full.get("schedules", []) if isinstance(full, dict) else []
        kept = [s for s in existing if s.get("id") != schedule_id]
        return self.apply_profile_update(profile_id, schedules=kept)

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

    # ── apps (#761/#764) ──────────────────────────────────────────────────
    # Post-#764, extraAllowed/extraBlocked are sourced exclusively from
    # app_policy_assignments — the legacy `extraBlocked`/`extraAllowed`
    # fields on profile POST/PUT are silently dropped. To exercise the
    # block/allow planes against a real API, callers must use the Apps
    # surface: create an app with hosts, then assign it to a profile with
    # a mode.

    def create_app(self, *, name: str, slug: str | None = None,
                   hosts: list[str] | None = None,
                   template_id: str | None = None,
                   icon: str | None = None,
                   icon_type: str | None = None) -> dict[str, Any]:
        """POST /api/apps. Returns the app-detail body
        ({app: {id, name, ...}, hosts: [...], assignments: [...]})."""
        body: dict[str, Any] = {"name": name}
        if slug is not None:
            body["slug"] = slug
        if hosts is not None:
            body["hosts"] = hosts
        if template_id is not None:
            body["templateId"] = template_id
        if icon is not None:
            body["icon"] = icon
        if icon_type is not None:
            body["iconType"] = icon_type
        return self._request("POST", "/api/apps", body=body)

    def assign_app_policy(self, *, app_id: int, profile_id: int, mode: str,
                          daily_minutes: int | None = None,
                          exempt_from_daily: bool | None = None) -> None:
        """PUT /api/apps/{app_id}/policy/{profile_id}. mode is one of
        'blocked' | 'allowed' | 'time_limited' (per shared/Models.scala
        AppMode.asString)."""
        body: dict[str, Any] = {"mode": mode}
        if daily_minutes is not None:
            body["dailyMinutes"] = daily_minutes
        if exempt_from_daily is not None:
            body["exemptFromDaily"] = exempt_from_daily
        self._request("PUT", f"/api/apps/{app_id}/policy/{profile_id}", body=body)

    def delete_app(self, app_id: int) -> None:
        """DELETE /api/apps/{id}. Cascades app_policy_assignments."""
        self._request("DELETE", f"/api/apps/{app_id}")

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
