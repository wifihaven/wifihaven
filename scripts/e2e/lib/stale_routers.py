"""Selection logic for the staging test-router pruner (#2179).

Pure — no I/O. `prune_stale_staging_routers.py` supplies the live
`GET /api/admin/routers` payload and deletes whatever this returns.

Why this exists: #2146 (`feat(#2134)`) made `POST /api/admin/routers` return
403 once the calling admin's household reaches `households.router_cap`. The
staging admin household accumulates E2E test routers whose teardown leaks on a
hard-aborted run (the gate3 conftest already flags the need for "a periodic
cleanup job"). Once the leak crosses the cap, every CD run 403s on router
creation and prod deploys are blocked. This pruner scrubs the leak before the
router-creating staging gates run.

Age — not run-id — is the guard. A concurrent CD run creates its routers
minutes before its gates read them; deleting by run-id would risk racing that
run's own creation. Deleting only routers *older than* a threshold leaves any
in-flight run's fresh routers untouched while still clearing week-old leaks.
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

# Name prefixes the E2E suites use for the routers they create against staging:
#   Gate 1 (scripts/e2e-router.sh):  e2e-test-router-<RUN_ID>
#   Gate 3a/3b (scripts/e2e/gate3):  gate3-<WH_RUN_ID>-<pkg>-<side>
# Only routers whose name starts with one of these are ever eligible for
# deletion — a real staging router with any other name is never touched.
TEST_ROUTER_PREFIXES: tuple[str, ...] = ("e2e-test-router-", "gate3-")


def _parse_created_at(value: object) -> datetime | None:
    """Parse the API's `createdAt` (ISO-8601, microseconds, trailing `Z`).

    Returns None for anything unparseable — a router we cannot age is never
    considered stale, so we never delete on a missing/garbled timestamp.
    """
    if not isinstance(value, str) or not value:
        return None
    try:
        # `fromisoformat` handles `+00:00` but historically not a bare `Z`.
        dt = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt


def is_test_router(name: object) -> bool:
    return isinstance(name, str) and name.startswith(TEST_ROUTER_PREFIXES)


def select_stale_test_routers(
    routers: list[dict],
    *,
    now: datetime,
    max_age: timedelta,
) -> list[str]:
    """Return the ids of test routers older than `max_age`, in input order.

    A router is selected iff its name matches a known E2E prefix AND its
    `createdAt` parses AND `now - createdAt > max_age` (strictly older, so a
    router exactly at the threshold is kept).
    """
    stale: list[str] = []
    for r in routers:
        rid = r.get("id")
        if not rid or not is_test_router(r.get("name")):
            continue
        created = _parse_created_at(r.get("createdAt"))
        if created is None:
            continue
        if now - created > max_age:
            stale.append(rid)
    return stale
