"""Admin API client. Bearer-token auth via /api/auth/login."""
from __future__ import annotations

import json
import logging
from typing import Any

import urllib.error
import urllib.request

log = logging.getLogger(__name__)


class _LoginUnauthorized(Exception):
    """Internal — raised by _raw_login on a 401, caught by self-heal."""


class AdminAPI:
    def __init__(self, base_url: str, *, username: str = "admin", password: str = "changeme"):
        self.base_url = base_url.rstrip("/")
        self.username = username
        self.password = password
        self._token: str | None = None

    # ── auth ──────────────────────────────────────────────────────────────

    # #1790: staging's Postgres resets every 30 days; on reset the admin user
    # reverts to 'changeme' with must_change_password set. CI's stored secret
    # holds the operator-chosen value, so a normal login() 401s. Self-heal:
    # try 'changeme', rotate the password back to self.password, re-login.
    # Bash mirror lives at scripts/e2e/lib/admin-auth.sh — keep both in sync.
    _DEFAULT_PASSWORD = "changeme"

    def login(self) -> str:
        try:
            token = self._raw_login(self.password)
        except _LoginUnauthorized:
            token = self._self_heal_after_reset()
        self._token = token
        return token

    def _raw_login(self, password: str) -> str:
        try:
            body = self._request(
                "POST", "/api/auth/login",
                body={"username": self.username, "password": password},
                authed=False,
            )
        except RuntimeError as e:
            if "HTTP 401" in str(e):
                raise _LoginUnauthorized() from e
            raise
        token = body.get("token") if isinstance(body, dict) else None
        if not token:
            raise RuntimeError(f"login response missing token: {body!r}")
        return token

    def _self_heal_after_reset(self) -> str:
        log.info("admin login 401 — attempting post-reset bootstrap with 'changeme'")
        try:
            bootstrap_token = self._raw_login(self._DEFAULT_PASSWORD)
        except _LoginUnauthorized as e:
            # Concurrent-gate race: a sibling gate may have rotated 'changeme'
            # back to the stored password between our first stored-login and
            # this one. Retry stored once before declaring hard fail.
            try:
                return self._raw_login(self.password)
            except _LoginUnauthorized:
                raise RuntimeError(
                    "neither stored password nor 'changeme' worked on "
                    f"{self.base_url} — staging admin credentials need manual rotation",
                ) from e
        # Rotate back to the stored password BEFORE returning a token — that
        # way every caller gets a token good for the post-rotation state, and
        # the must_change_password guard can't bite the next admin call.
        self._token = bootstrap_token
        # #623: change-password returns JSON ({"mustChangePassword": false}).
        # AdminAPI._request already json-decodes a JSON-content-type response;
        # a regression to an empty body would surface as None here.
        try:
            resp = self._request(
                "POST", "/api/auth/change-password",
                body={
                    "currentPassword": self._DEFAULT_PASSWORD,
                    "newPassword": self.password,
                },
            )
        except RuntimeError as e:
            # Same race: a sibling rotated between our changeme-login and
            # this change-password call, so currentPassword=changeme is no
            # longer valid. Fall back to a stored-password login.
            if "HTTP 401" in str(e):
                self._token = None
                return self._raw_login(self.password)
            raise
        if not isinstance(resp, dict) or "mustChangePassword" not in resp:
            raise RuntimeError(
                f"change-password did not return the expected JSON body (#623 regression?): {resp!r}",
            )
        log.info("admin password rotated back to stored value; re-logging in")
        self._token = None
        return self._raw_login(self.password)

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
        (`{profile: {...}, timeLimit: {...} | null, siteTimeLimits: [...]}`)
        while PUT requires a flat body and expects `timeLimit` to be an integer
        minute-count, not the object the GET returns. Callers should pass
        overrides as keyword args (e.g. `extraBlocked=[...]`, `timeLimit=5`).

        Schedules are NOT folded here: since #1490/#1494 the profile upsert no
        longer carries schedules (an inline `schedules` array is an ignored
        unknown field). Schedules attach via the named-schedule path — see
        `add_schedule` / `remove_schedule`.
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

    def attached_schedule_ids(self, profile_id: int) -> list[Any]:
        """The named-schedule ids attached to the profile as block schedules."""
        full = self.get_profile(profile_id)
        return full.get("scheduleIds", []) if isinstance(full, dict) else []

    def set_profile_schedules(
        self, profile_id: int, schedule_ids: list[Any],
    ) -> None:
        """Replace the profile's attached block schedules (#1494 write path).

        PUT /api/profiles/{id}/schedules -> profile_schedule_rules. This is the
        ONLY enforced schedule write path since #1490 — the legacy inline
        `schedules` array on the profile upsert is dead.
        """
        self._request(
            "PUT", f"/api/profiles/{profile_id}/schedules",
            body={"scheduleIds": schedule_ids},
        )

    def add_schedule(self, profile_id: int, window: dict[str, Any], *,
                     name: str | None = None) -> Any:
        """Create a household named schedule and attach it to the profile.

        `window` is a ScheduleWindow dict — `{"days": ["mon", ...], "startLocal":
        "21:00", "endLocal": "07:00", "tz": "UTC"}`. Returns the new schedule id.

        Replaces the pre-#1494 fold-and-PUT of an inline `schedules` array, which
        the API now ignores (enforcement reads named_schedules /
        profile_schedule_rules since #1490).
        """
        sched_name = name or f"e2e-sched-p{profile_id}-{window.get('startLocal', '')}"
        created = self._request(
            "POST", "/api/schedules",
            body={"name": sched_name, "windows": [window]},
        )
        sid = created.get("id") if isinstance(created, dict) else None
        self.set_profile_schedules(
            profile_id, self.attached_schedule_ids(profile_id) + [sid],
        )
        return sid

    def remove_schedule(self, profile_id: int, schedule_id: Any) -> None:
        """Detach the named schedule from the profile; no-op if not attached."""
        kept = [s for s in self.attached_schedule_ids(profile_id) if s != schedule_id]
        self.set_profile_schedules(profile_id, kept)

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

    # ── apps (#761/#764/#1798) ────────────────────────────────────────────
    # Post-#764, extraAllowed/extraBlocked are sourced exclusively from
    # app_policy_assignments — the legacy `extraBlocked`/`extraAllowed`
    # fields on profile POST/PUT are silently dropped.
    #
    # Post-#1798, app *definitions* are template-authored ONLY — the
    # arbitrary-host `POST /api/apps` create (and the update / replace-hosts
    # / PATCH mutators) were retired. To exercise the block/allow planes
    # against a real API, callers seed the shipped templates
    # (`seed_apps_from_templates`), read them back (`list_apps`), and assign
    # a seeded app to a profile with a mode (`assign_app_policy`). See
    # lib/app_seed.pick_block_allow_apps for the gate3 selection (#1810).

    def seed_apps_from_templates(self) -> dict[str, Any]:
        """POST /api/apps/seed-from-templates (admin, idempotent). Find-or-
        creates one `apps` row per built-in template; returns the seed
        summary ({created, repopulated, preserved, augmented})."""
        return self._request("POST", "/api/apps/seed-from-templates")

    def list_apps(self) -> list[dict[str, Any]]:
        """GET /api/apps. Returns a list of app-detail bodies
        ({app: {id, slug, templateId, ...}, hosts: [...], assignments: [...]})."""
        return self._request("GET", "/api/apps")

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

    def delete_app_policy(self, *, app_id: int, profile_id: int) -> None:
        """DELETE /api/apps/{app_id}/policy/{profile_id}. Detaches this
        profile's assignment WITHOUT touching the shared app definition —
        the teardown path for seeded (template-authored) apps, which must
        survive for other gates / the live catalog."""
        self._request("DELETE", f"/api/apps/{app_id}/policy/{profile_id}")

    def delete_app(self, app_id: int) -> None:
        """DELETE /api/apps/{id} (admin, #1798 stray-row cleanup). Cascades
        app_policy_assignments. NOT used to tear down seeded apps — detach
        the assignment with `delete_app_policy` instead."""
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
