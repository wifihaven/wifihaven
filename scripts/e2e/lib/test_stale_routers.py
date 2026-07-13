"""Unit cover for the staging test-router pruner selection logic (#2179).

Pure — no staging API, no KVM VM. Run standalone:

    python3 -m pytest scripts/e2e/lib/test_stale_routers.py

Pins the contract the `prune-staging-test-routers` CD job relies on: given the
`GET /api/admin/routers` payload, select exactly the *stale test* routers to
delete so the staging admin household stays under `households.router_cap`
(#2146). "Stale test" = name matches a known E2E prefix AND the router is older
than the age threshold. Age (not run-id) is the guard so a *concurrent* CD run's
freshly-created routers (minutes old) are never deleted out from under it.
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

from stale_routers import select_stale_test_routers


def _router(name: str, created_at: str | None) -> dict:
    """A router dict shaped as `GET /api/admin/routers` returns it."""
    r = {"id": f"id-{name}", "name": name}
    if created_at is not None:
        r["createdAt"] = created_at
    return r


NOW = datetime(2026, 7, 13, 12, 0, 0, tzinfo=timezone.utc)
MAX_AGE = timedelta(hours=2)


def _iso(dt: datetime) -> str:
    # Match the API's shape: microseconds + trailing Z.
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f") + "Z"


def test_selects_stale_e2e_and_gate3_routers():
    old = _iso(NOW - timedelta(days=18))
    routers = [
        _router("e2e-test-router-gh-28177601682-1", old),
        _router("gate3-28177601682-1-apk-apk-3b", old),
        _router("gate3-28297900682-1-ipk-ipk-3a", old),
    ]
    got = select_stale_test_routers(routers, now=NOW, max_age=MAX_AGE)
    assert got == [
        "id-e2e-test-router-gh-28177601682-1",
        "id-gate3-28177601682-1-apk-apk-3b",
        "id-gate3-28297900682-1-ipk-ipk-3a",
    ]


def test_preserves_non_test_routers_even_if_old():
    old = _iso(NOW - timedelta(days=90))
    routers = [
        _router("my-home-router", old),
        _router("office-gateway", old),
        _router("prod-router-01", old),
    ]
    assert select_stale_test_routers(routers, now=NOW, max_age=MAX_AGE) == []


def test_preserves_fresh_test_routers_for_concurrent_runs():
    # A concurrent CD run's router, created 3 minutes ago — must survive so the
    # pruner never races a live run's own creation.
    fresh = _iso(NOW - timedelta(minutes=3))
    routers = [
        _router("gate3-99999999999-1-apk-apk-3b", fresh),
        _router("e2e-test-router-gh-99999999999-1", fresh),
    ]
    assert select_stale_test_routers(routers, now=NOW, max_age=MAX_AGE) == []


def test_boundary_exactly_at_threshold_is_not_stale():
    # Strictly-older-than: exactly max_age old is kept (conservative).
    at = _iso(NOW - MAX_AGE)
    assert select_stale_test_routers([_router("gate3-1-apk-apk-3b", at)], now=NOW, max_age=MAX_AGE) == []
    just_over = _iso(NOW - MAX_AGE - timedelta(seconds=1))
    assert select_stale_test_routers([_router("gate3-1-apk-apk-3b", just_over)], now=NOW, max_age=MAX_AGE) == [
        "id-gate3-1-apk-apk-3b"
    ]


def test_missing_or_malformed_createdAt_is_not_selected():
    # We can't age a router without a parseable timestamp — never delete it.
    routers = [
        _router("gate3-1-apk-apk-3b", None),
        _router("e2e-test-router-gh-1-1", "not-a-timestamp"),
        _router("gate3-2-apk-apk-3b", ""),
    ]
    assert select_stale_test_routers(routers, now=NOW, max_age=MAX_AGE) == []


def test_mixed_payload_selects_only_stale_test_routers():
    old = _iso(NOW - timedelta(days=18))
    fresh = _iso(NOW - timedelta(minutes=5))
    routers = [
        _router("e2e-test-router-gh-1-1", old),        # stale test → delete
        _router("gate3-2-apk-apk-3b", old),            # stale test → delete
        _router("gate3-3-apk-apk-3b", fresh),          # fresh test → keep
        _router("my-home-router", old),                # non-test → keep
        _router("e2e-test-router-gh-4-1", None),       # untimestamped → keep
    ]
    assert select_stale_test_routers(routers, now=NOW, max_age=MAX_AGE) == [
        "id-e2e-test-router-gh-1-1",
        "id-gate3-2-apk-apk-3b",
    ]
