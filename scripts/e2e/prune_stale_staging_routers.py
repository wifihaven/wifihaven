#!/usr/bin/env python3
"""Prune leaked E2E test routers from the staging admin household (#2179).

Runs once per Master API/UI CD run, after `deploy-staging` and before the
router-creating staging gates (Gate 1 `e2e-router.sh`, Gate 3b). Deletes test
routers older than a threshold so the household stays under `households.router_cap`
(#2146) — the E2E suites create a router per run and leak it on a hard-aborted
teardown, and once the leak crosses the cap every subsequent `POST /api/admin/routers`
returns 403 and blocks prod deploys.

Best-effort and idempotent: a green run may prune zero. Fails the job only if
admin login fails (that is a real staging-auth problem worth surfacing). See
`lib/stale_routers.py` for the (unit-tested) selection rule.

Env:
  E2E_BASE_URL              staging API base (e.g. https://api-staging.wifihaven.net)
  WH_STAGING_ADMIN_PASS     admin password
  STAGING_ADMIN_USER        admin username (default: admin)
  PRUNE_MAX_AGE_SECONDS     age threshold in seconds (default: 7200 = 2h)
"""
from __future__ import annotations

import logging
import os
import sys
from datetime import datetime, timedelta, timezone

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))

from api_admin import AdminAPI  # noqa: E402
from stale_routers import select_stale_test_routers  # noqa: E402

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
log = logging.getLogger("prune-staging-routers")

DEFAULT_MAX_AGE_SECONDS = 2 * 60 * 60  # 2h — well above a single run's lifetime.


def main() -> int:
    base = os.environ.get("E2E_BASE_URL", "").rstrip("/")
    password = os.environ.get("WH_STAGING_ADMIN_PASS", "")
    user = os.environ.get("STAGING_ADMIN_USER", "admin")
    max_age = timedelta(
        seconds=int(os.environ.get("PRUNE_MAX_AGE_SECONDS", DEFAULT_MAX_AGE_SECONDS))
    )
    if not base or not password:
        log.error("E2E_BASE_URL and WH_STAGING_ADMIN_PASS are required")
        return 2

    admin = AdminAPI(base, username=user, password=password)
    # Let a login failure raise — the job SHOULD go red on a real auth problem.
    admin.login()

    routers = admin.list_routers()
    now = datetime.now(timezone.utc)
    stale = select_stale_test_routers(routers, now=now, max_age=max_age)
    log.info(
        "staging has %d routers; %d are stale test routers (> %s old)",
        len(routers),
        len(stale),
        max_age,
    )

    deleted, failed = 0, 0
    for rid in stale:
        try:
            admin.delete_router(rid)
            deleted += 1
            log.info("deleted stale test router %s", rid)
        except Exception as e:  # best-effort — one failure must not block the rest
            failed += 1
            log.warning("delete_router(%s) failed: %s", rid, e)

    log.info("prune complete: %d deleted, %d failed", deleted, failed)
    # Never fail the job on individual delete errors: the next run retries, and
    # a transient 5xx on one delete should not block the deploy pipeline.
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
