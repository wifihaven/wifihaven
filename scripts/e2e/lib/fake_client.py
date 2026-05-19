"""Sync HTTP wrapper around the fake API's /test/* control surface (#683).

The fake itself runs in-process on the host via aiohttp; pytest scenarios
talk to it over plain HTTP loopback. This module mirrors the same flat
shape as `lib/api_admin.py` and `lib/api_debug.py` so a scenario only ever
sees one fake-API handle.
"""
from __future__ import annotations

import json
import logging
import time
import urllib.error
import urllib.request
from typing import Any

log = logging.getLogger(__name__)


class FakeAPIClient:
    """Test-side handle for poking /test/* endpoints on the in-process fake."""

    def __init__(self, base_url: str) -> None:
        # base_url is the host-loopback URL (the agent reaches the same
        # service via the SLIRP gateway — different URL, same fake state).
        self.base_url = base_url.rstrip("/")

    # ── /test/snapshot ─────────────────────────────────────────────────────

    def serve_snapshot(self, snapshot: dict[str, Any]) -> str:
        """Replace the currently-served policy snapshot. Returns the new etag."""
        body = self._post_json("/test/snapshot", snapshot)
        etag = body.get("etag", "")
        log.info("fake: served snapshot etag=%s", etag)
        return etag

    # ── /test/reset ────────────────────────────────────────────────────────

    def reset(self) -> None:
        """Clear captured state and restore the initial snapshot."""
        self._post_json("/test/reset", {})

    # ── /test/events ───────────────────────────────────────────────────────

    def events(
        self, *, since_id: int | None = None, mac: str | None = None,
    ) -> dict[str, Any]:
        """Drain captured events. Returns the raw {batches, events} payload."""
        q: list[str] = []
        if since_id is not None:
            q.append(f"since_id={since_id}")
        if mac is not None:
            q.append(f"mac={urllib.request.quote(mac)}")
        path = "/test/events"
        if q:
            path += "?" + "&".join(q)
        return self._get_json(path)

    def events_for_mac(
        self, mac: str, *, allowed: bool | None = None, reason: str | None = None,
    ) -> list[dict[str, Any]]:
        """Filtered flat event list for a MAC."""
        payload = self.events(mac=mac)
        out = []
        for e in payload.get("events", []) or []:
            if allowed is not None and e.get("allowed") != allowed:
                continue
            if reason is not None and (e.get("reason") or "").lower() != reason.lower():
                continue
            out.append(e)
        return out

    # ── /test/policy_fetches ──────────────────────────────────────────────

    def policy_fetches(self, *, since_id: int | None = None) -> dict[str, Any]:
        path = "/test/policy_fetches"
        if since_id is not None:
            path += f"?since_id={since_id}"
        return self._get_json(path)

    def last_policy_fetch(self) -> dict[str, Any] | None:
        payload = self.policy_fetches()
        f = payload.get("fetches") or []
        return f[-1] if f else None

    # ── /test/register ─────────────────────────────────────────────────────

    def register_info(self) -> dict[str, Any]:
        return self._get_json("/test/register")

    # ── /test/usage ────────────────────────────────────────────────────────

    def usage(self, *, since_id: int | None = None) -> dict[str, Any]:
        path = "/test/usage"
        if since_id is not None:
            path += f"?since_id={since_id}"
        return self._get_json(path)

    # ── internal ───────────────────────────────────────────────────────────

    def _post_json(self, path: str, body: dict[str, Any]) -> dict[str, Any]:
        req = urllib.request.Request(
            self.base_url + path,
            data=json.dumps(body).encode("utf-8"),
            headers={"content-type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:  # noqa: S310
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            raise RuntimeError(f"POST {path}: HTTP {e.code}: {e.read()!r}") from e

    # ── wait helpers ──────────────────────────────────────────────────────

    def wait_for_policy_fetch(
        self,
        *,
        after_id: int = 0,
        timeout_s: float = 180,
        interval_s: float = 2.0,
    ) -> dict[str, Any]:
        """Block until a /api/router/policy fetch with id > after_id arrives."""
        deadline = time.monotonic() + timeout_s
        while time.monotonic() < deadline:
            fetches = (self.policy_fetches(since_id=after_id).get("fetches") or [])
            if fetches:
                return fetches[-1]
            time.sleep(interval_s)
        raise TimeoutError(
            f"no policy fetch after id={after_id} in {timeout_s}s"
        )

    def wait_for_etag_served(
        self,
        *,
        etag: str,
        timeout_s: float = 240,
        interval_s: float = 2.0,
    ) -> dict[str, Any]:
        """Block until a 200 with the given etag has been served."""
        deadline = time.monotonic() + timeout_s
        while time.monotonic() < deadline:
            for f in (self.policy_fetches().get("fetches") or []):
                if f.get("status") == 200 and f.get("servedEtag") == etag:
                    return f
            time.sleep(interval_s)
        raise TimeoutError(
            f"agent never fetched etag={etag!r} in {timeout_s}s"
        )

    def latest_fetch_id(self) -> int:
        last = self.last_policy_fetch()
        return int(last["id"]) if last else 0

    def _get_json(self, path: str) -> dict[str, Any]:
        try:
            with urllib.request.urlopen(self.base_url + path, timeout=10) as resp:  # noqa: S310
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            raise RuntimeError(f"GET {path}: HTTP {e.code}: {e.read()!r}") from e
