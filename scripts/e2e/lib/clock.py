"""Router VM clock control.

Per #346 (Option 2 / 'sledgehammer'): set the router VM's wall clock with
`date -s`. Affects the agent's next-cycle decisions (schedules, time limits)
because the agent samples wall clock for those checks. Cheap, brittle if any
service on the router cares about clock jumps — fine for v1 e2e.

Note: the agent uses CLOCK_MONOTONIC for poll cadence (#336), so a wall-clock
jump does NOT change when the next poll fires. Use wait_for_next_poll() to
synchronize.
"""
from __future__ import annotations

import logging
from datetime import datetime, timezone

from .vm import router_ssh

log = logging.getLogger(__name__)


def set_router_clock(when: datetime) -> None:
    """Set the router VM's wall clock to `when` (interpreted as UTC if naive)."""
    if when.tzinfo is None:
        when = when.replace(tzinfo=timezone.utc)
    iso = when.astimezone(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")
    log.info("setting router VM clock to %s UTC", iso)
    router_ssh(f"date -u -s '{iso}'", timeout=10).check()


def get_router_clock() -> datetime:
    res = router_ssh("date -u +%s", timeout=5).check()
    return datetime.fromtimestamp(int(res.stdout.strip()), tz=timezone.utc)
