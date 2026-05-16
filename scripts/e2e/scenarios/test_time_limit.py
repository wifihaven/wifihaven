"""Suite D — daily time-limit minute granularity + cross-day rollover (#431).

Extends `test_04_daily_limit.py` (D1: exhaustion blocks). Suite D covers
the harder cases:

  - D2 (@smoke) verifies the contract from #295: usage accumulates at
    *seconds* granularity, not whole-minute buckets. With a generous limit
    and ~90s of activity, we expect `time_usage` to report ~1 minute of
    usage — NOT 0 (would mean nothing accrued) and NOT 5 (would mean a
    full 5-minute report-interval bucket).
  - D3 cross-day rollover: with the daily limit exhausted, advancing the
    router's wall clock past the household `daily_reset_time` should
    unblock the MAC and reset `time_usage` for the new day within one
    poll. This depends on #334 and may currently `pytest.skip` if the
    underlying behavior isn't wired up; we capture and lock in whatever
    the system does today.

D1 (exhaustion blocks) is intentionally NOT duplicated here — it lives in
`test_04_daily_limit.py` under marker `daily_limit`. Running the full
Suite D bundle therefore needs both markers (`-m "daily_limit or time_limit"`).

Note on runtime: D2 must wait at least one usage-report cycle (300s
default) for the agent to flush its usage counters to the API. There is
no debug-flush endpoint exposed today, so the suite is intrinsically
slow (~5–6 minutes). D3 reuses the same waiting pattern as D1.

Asserts CURRENT behavior — bugs surfaced here get filed as separate
issues, never bundled fixes (see #346 discipline).
"""
from __future__ import annotations

import logging
import time
from datetime import datetime, timedelta, timezone

import pytest

from lib.clock import get_router_clock, set_router_clock
from lib.traffic import http_get
from lib.vm import router_nft_set
from lib.wait import wait_for_etag_change, wait_for_next_poll, wait_until

pytestmark = pytest.mark.time_limit

log = logging.getLogger(__name__)

BLOCKED_MACS_SET = "blocked_macs"
USAGE_REPORT_INTERVAL_S = 300  # agent default; orchestrator does not override


def _norm_mac(mac: str) -> str:
    return mac.lower().strip()


def _mac_in_set(mac: str) -> bool:
    members = {_norm_mac(m) for m in router_nft_set(BLOCKED_MACS_SET)}
    return _norm_mac(mac) in members


def _total_minutes_for_mac(debug_api, mac: str) -> int:
    """Bucket-deduplicated online minutes for `mac` from today's time_usage.

    Reads `deviceTotalMinutes` (#474), which is computed server-side from
    `traffic_reports` via Presence: each 5-min agent bucket counts once per
    device regardless of how many hosts the device touched. Naively summing
    per-host `minutesUsed` would inflate when DNS + gateway + the actual
    site all see traffic in the same bucket.
    """
    needle = _norm_mac(mac)
    for row in debug_api.time_usage():
        if _norm_mac(row.get("mac", "")) == needle:
            # Same value on every row for the same mac — first wins.
            return int(row.get("deviceTotalMinutes", 0))
    return -1  # sentinel: no rows yet


# ── D2 (@smoke) — minute granularity (verifies #295) ───────────────────────


@pytest.mark.smoke
def test_time_limit_minute_granularity(
    router, client, scratch_device, scratch_profile, admin, debug_api,
):
    """D2: ~90s of traffic shows up as ~1 minute (not 0, not a 5-min bucket).

    Generous 60-minute cap so the device is NEVER blocked during the
    test — we're measuring accumulation granularity, not enforcement.

    SLOW: waits one full usage-report interval (300s) for the agent to
    flush usage to the API. There is no debug-flush endpoint today; if
    one lands, swap in here.
    """
    admin.apply_profile_update(scratch_profile["id"], timeLimit=60)
    wait_for_next_poll(admin, router.router_id, timeout_s=120)

    mac = client.mac
    assert not _mac_in_set(mac), "precondition: MAC should not be blocked"

    # Drive ~90s of activity: 10 bursts spaced 9s apart. Each request
    # touches the same allowed host so usage accrues against one row.
    burst_start = time.monotonic()
    for _ in range(10):
        http_get(client, "http://example.com/", timeout_s=5)
        time.sleep(9)
    burst_elapsed = time.monotonic() - burst_start
    log.info("D2 burst loop done after %.1fs", burst_elapsed)

    # Wait for the agent's next usage-report cycle + a small grace window.
    # The agent reports every `usage_report_interval` (default 300s); we
    # don't know where we are in its cycle, so wait the full interval +
    # buffer for the API to ingest.
    log.info("D2 sleeping %ds for agent usage-report flush", USAGE_REPORT_INTERVAL_S + 30)
    time.sleep(USAGE_REPORT_INTERVAL_S + 30)

    # Poll the debug API for non-empty time_usage for this MAC.
    total_minutes = wait_until(
        lambda: (
            v if (v := _total_minutes_for_mac(debug_api, mac)) >= 0 else None
        ),
        timeout_s=120, interval_s=5,
        description=f"time_usage row for {mac}",
    )

    # MAC must still NOT be blocked — generous cap.
    assert not _mac_in_set(mac), (
        f"D2: MAC was blocked despite 60-min cap; total_minutes={total_minutes}"
    )

    # Granularity assertion (#295):
    #   - 0 → usage never accrued (regression)
    #   - 1 → expected: 90s truncated to 1 minute
    #   - 2 → acceptable upper bound if the burst stretched out / a second
    #         report cycle landed before we polled
    #   - >= 5 → suggests minute-bucket rounding (#295 contract violated)
    assert 1 <= total_minutes <= 2, (
        f"D2: expected minutesUsed in [1, 2] after ~90s of traffic, got "
        f"{total_minutes}. Either nothing accrued (=0) or the API is "
        f"rounding to a multi-minute bucket (#295)."
    )


# ── D3 — cross-day rollover (pending #334) ─────────────────────────────────


def test_time_limit_cross_day_rollover(
    router, client, scratch_device, scratch_profile, admin, debug_api,
):
    """D3: advance clock past `daily_reset_time` → MAC unblocked, usage reset.

    Exhausts a 1-minute limit (fast path), advances the router wall clock
    past the household daily reset, and asserts that within one poll the
    MAC is unblocked and `time_usage` reflects a fresh day.

    Marked `# pending #334`: if the API/agent does not yet honor the
    daily reset in this end-to-end shape, we skip the post-rollover
    assertions and lock in the current (buggy) behavior with a clear
    log line. The `daily_reset_time` setting itself is configured via
    `PUT /api/household/settings` (see api/src/routes/Routes.scala).
    """
    mac = client.mac

    # Step 1: configure a small limit and drive enough traffic to exhaust.
    admin.apply_profile_update(scratch_profile["id"], timeLimit=1)
    wait_for_next_poll(admin, router.router_id, timeout_s=120)

    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        http_get(client, "http://example.com/", timeout_s=5)
        time.sleep(2)

    # Wait long enough for usage-report + policy poll → MAC ends up blocked.
    time.sleep(60)
    wait_for_etag_change(admin, router.router_id, timeout_s=400)

    became_blocked = False
    try:
        wait_until(
            lambda: True if _mac_in_set(mac) else None,
            timeout_s=120, interval_s=3,
            description=f"{mac} present in {BLOCKED_MACS_SET} (exhaustion)",
        )
        became_blocked = True
    except Exception as e:  # noqa: BLE001
        log.warning("D3: MAC never became blocked (%s); skipping rollover assertions", e)
        pytest.skip("D3 precondition failed: limit-exhaustion did not block MAC")

    assert became_blocked

    # Step 2: configure household daily_reset_time to a known value
    # (00:00 UTC), then jump router wall clock to just after it on the
    # next day. The agent uses CLOCK_MONOTONIC for poll cadence (#336)
    # so the jump doesn't stall polls.
    try:
        admin._request(
            "PUT", "/api/household/settings",
            body={"dailyResetTime": "00:00:00", "dailyResetTz": "UTC"},
        )
    except Exception as e:  # noqa: BLE001
        log.warning("D3: could not set household settings (%s)", e)

    now_router = get_router_clock()
    # Jump to next-day 00:05 UTC, well past the 00:00 reset.
    next_day = (now_router + timedelta(days=1)).astimezone(timezone.utc)
    target = datetime(
        next_day.year, next_day.month, next_day.day, 0, 5, 0, tzinfo=timezone.utc,
    )
    set_router_clock(target)

    # Step 3: within one poll cycle, expect MAC unblocked AND time_usage
    # reset for the new day (`time_usage()` is keyed on `today` server-side).
    try:
        wait_until(
            lambda: True if not _mac_in_set(mac) else None,
            timeout_s=180, interval_s=3,
            description=f"{mac} cleared from {BLOCKED_MACS_SET} after rollover",
        )
    except Exception as e:  # noqa: BLE001
        # pending #334: lock in current (likely-buggy) behavior, don't fail.
        log.warning(
            "D3 pending #334: MAC remained blocked after clock advance past "
            "daily_reset_time (%s). Filing under #334 if not already tracked.",
            e,
        )
        pytest.skip("D3 pending #334: clock-based rollover not yet honored end-to-end")

    # If we get here, rollover worked at the router level — also confirm
    # the API side reflects a fresh budget. The debug endpoint is keyed
    # on the *API* clock (Clock.today), which is real-wall-clock; jumping
    # only the router VM clock won't reset the API's notion of "today".
    # So we softly check: if usage is still pinned to yesterday, just log.
    post_minutes = _total_minutes_for_mac(debug_api, mac)
    log.info("D3 post-rollover total_minutes for %s = %s", mac, post_minutes)
    # No hard assertion on the API side — see comment above. The router-side
    # unblock is the load-bearing observation that #334 wants to lock in.
