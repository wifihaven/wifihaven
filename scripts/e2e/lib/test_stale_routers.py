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

import re
from datetime import datetime, timedelta, timezone
from pathlib import Path

from stale_routers import TEST_ROUTER_PREFIXES, select_stale_test_routers

# scripts/e2e/lib/ → repo root is three parents up.
_REPO_ROOT = Path(__file__).resolve().parents[3]


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


def test_prefixes_track_the_suite_naming_conventions():
    """Drift guard (#2179): the pruner's prefixes must still match how the E2E
    suites actually NAME the routers they create. Derived from the source, not
    hard-coded here — so renaming a router in either suite without updating
    TEST_ROUTER_PREFIXES turns THIS test red instead of silently disarming the
    pruner (the #1334 silent-no-op class: the leak would quietly return and prod
    would 403 on router_cap again).
    """
    # Gate 1 — scripts/e2e-router.sh: ROUTER_NAME="e2e-test-router-${RUN_ID}".
    shell = (_REPO_ROOT / "scripts" / "e2e-router.sh").read_text()
    m = re.search(r'ROUTER_NAME="([^"$]+)\$\{', shell)
    assert m, "could not find ROUTER_NAME=... in scripts/e2e-router.sh"
    gate1_prefix = m.group(1)
    assert gate1_prefix in TEST_ROUTER_PREFIXES, (
        f"e2e-router.sh names routers {gate1_prefix!r}, not matched by the "
        f"pruner prefixes {TEST_ROUTER_PREFIXES!r} — update stale_routers.py"
    )

    # Gate 3a/3b — scripts/e2e/gate3/conftest.py session router fixture:
    # name = f"gate3-{_suffix()}" (the profile fixture uses {suffix}, so keying
    # on the literal `{_suffix()}` call pins the *router* name specifically).
    conftest = (_REPO_ROOT / "scripts" / "e2e" / "gate3" / "conftest.py").read_text()
    m = re.search(r'f"([^"{]+)\{_suffix\(\)\}"', conftest)
    assert m, "could not find the router-name f-string in gate3/conftest.py"
    gate3_prefix = m.group(1)
    assert gate3_prefix in TEST_ROUTER_PREFIXES, (
        f"gate3 conftest names routers {gate3_prefix!r}, not matched by the "
        f"pruner prefixes {TEST_ROUTER_PREFIXES!r} — update stale_routers.py"
    )


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
