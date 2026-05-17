"""Suite C — schedule enforcement (#430).

Verifies #305 / #317 and the pause-over-schedule precedence locked in by
#406. TZ-sensitive assertions assert *current* behavior; the API gained
timezone-aware schedules via #334 / #442, but the agent's wall-clock
sampling vs. CLOCK_MONOTONIC poll cadence (#336) still means we have to
`wait_for_next_poll` after every `set_router_clock`.

Schedule wire shape (see shared/src/Models.scala `ScheduleRequest`):
    {"name": str,
     "days": ["mon", "tue", ...],   # lowercase 3-letter
     "startLocal": "HH:MM:SS",
     "endLocal":   "HH:MM:SS",
     "tz": "America/Los_Angeles"}

Connection-attempt events use lowercase reason strings: "schedule",
"paused" (see api/src/policy/PolicyService.scala).

Asserts CURRENT behavior. Bugs surfaced here get filed separately —
this file never bundles fixes.
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

import pytest

from lib.clock import get_router_clock, set_router_clock
from lib.traffic import http_get
from lib.vm import router_nft_set
from lib.wait import wait_for_etag_change, wait_for_next_poll, wait_until

pytestmark = pytest.mark.schedule

BLOCKED_MACS_SET = "blocked_macs"
LA = ZoneInfo("America/Los_Angeles")
ALL_DAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]


# ── shared helpers (mirroring test_pause.py style) ─────────────────────────


def _norm_mac(mac: str) -> str:
    return mac.lower().strip()


def _mac_in_set(mac: str) -> bool:
    members = {_norm_mac(m) for m in router_nft_set(BLOCKED_MACS_SET)}
    return _norm_mac(mac) in members


def _wait_mac_in_blocked_set(mac: str, *, present: bool, timeout_s: float = 90) -> None:
    target = present
    wait_until(
        lambda: True if _mac_in_set(mac) is target else None,
        timeout_s=timeout_s,
        interval_s=2,
        description=(
            f"{mac} {'present in' if present else 'absent from'} {BLOCKED_MACS_SET}"
        ),
    )


def _wait_http_succeeds(client, *, host: str = "example.com", timeout_s: float = 60):
    """Probe HTTP to `host`, disambiguating block page from real allowed response.

    The block page returns 200 too, so a substring check on the body rules
    it out (same trick as test_pause.py)."""
    def probe():
        p = http_get(client, f"http://{host}/", timeout_s=8)
        if p.http_code is not None and 200 <= p.http_code < 400:
            if "wifihaven" in p.body.lower() or "blocked" in p.body.lower():
                return None
            return p
        return None
    return wait_until(probe, timeout_s=timeout_s, interval_s=3,
                      description=f"allowed HTTP response for {host}")


def _wait_event(debug_api, mac: str, *, reason: str, timeout_s: float = 30):
    return wait_until(
        lambda: (debug_api.events_for_mac_filtered(
            mac, allowed=False, reason=reason,
        ) or None),
        timeout_s=timeout_s, interval_s=2,
        description=f"allowed=False reason={reason} event for {mac}",
    )


def _set_clock_and_sync(admin, router, when: datetime) -> None:
    """Jump the router VM clock, then wait one poll so the agent observes it.

    Per #336 the agent's CLOCK_MONOTONIC cadence is unaffected by a
    wall-clock jump; `wait_for_next_poll` here pins the next sync to the
    next cycle boundary and asserts the agent actually polled."""
    set_router_clock(when)
    wait_for_next_poll(admin, router.router_id, timeout_s=240)


def _schedule_id_from(profile: dict, name: str) -> int | None:
    for s in (profile.get("schedules") or []):
        if s.get("name") == name:
            return s.get("id")
    return None


# ── C1 — in-window blocks ──────────────────────────────────────────────────


def test_in_window_blocks(
    router, client, scratch_device, scratch_profile, admin, debug_api,
):
    """C1: schedule covering current router time → MAC blocked within one poll, event reason=schedule."""
    profile_id = scratch_profile["id"]
    mac = client.mac

    assert not _mac_in_set(mac), "precondition: MAC should not be blocked yet"

    original_clock = get_router_clock()
    # Anchor at a fixed UTC instant inside the window we'll configure. Using
    # UTC tz here keeps C1 free of TZ math — that's what C3/C5 are for.
    anchor = datetime(2026, 5, 15, 12, 0, 0, tzinfo=timezone.utc)
    sched_name = f"e2e-c1-{anchor.strftime('%H%M%S')}"
    schedule = {
        "name": sched_name,
        "days": ALL_DAYS,
        "startLocal": "11:00:00",
        "endLocal":   "13:00:00",
        "tz": "UTC",
    }
    result = None
    try:
        _set_clock_and_sync(admin, router, anchor)

        result = admin.add_schedule(profile_id, schedule)
        wait_for_etag_change(admin, router.router_id, timeout_s=240)

        _wait_mac_in_blocked_set(mac, present=True, timeout_s=180)
        _wait_event(debug_api, mac, reason="schedule", timeout_s=60)
    finally:
        sid = _schedule_id_from(result or {}, sched_name)
        if sid is not None:
            try:
                admin.remove_schedule(profile_id, sid)
            except Exception:
                pass
        try:
            set_router_clock(original_clock)
        except Exception:
            pass


# ── C2 — out-of-window allows ──────────────────────────────────────────────


def test_out_of_window_allows(
    router, client, scratch_device, scratch_profile, admin, debug_api,
):
    """C2: clock outside the configured window → MAC stays unblocked, HTTP works."""
    profile_id = scratch_profile["id"]
    mac = client.mac

    original_clock = get_router_clock()
    anchor = datetime(2026, 5, 15, 6, 0, 0, tzinfo=timezone.utc)  # well outside 11:00–13:00
    sched_name = f"e2e-c2-{anchor.strftime('%H%M%S')}"
    schedule = {
        "name": sched_name,
        "days": ALL_DAYS,
        "startLocal": "11:00:00",
        "endLocal":   "13:00:00",
        "tz": "UTC",
    }
    result = None
    try:
        _set_clock_and_sync(admin, router, anchor)

        result = admin.add_schedule(profile_id, schedule)
        wait_for_etag_change(admin, router.router_id, timeout_s=240)
        wait_for_next_poll(admin, router.router_id, timeout_s=240)

        assert not _mac_in_set(mac), \
            f"{mac} should not be blocked while clock is outside the window"
        _wait_http_succeeds(client, timeout_s=60)
    finally:
        sid = _schedule_id_from(result or {}, sched_name)
        if sid is not None:
            try:
                admin.remove_schedule(profile_id, sid)
            except Exception:
                pass
        try:
            set_router_clock(original_clock)
        except Exception:
            pass


# ── C3 — cross-midnight 23:58→00:03 LA-tz ──────────────────────────────────


def test_cross_midnight_LA_tz(
    router, client, scratch_device, scratch_profile, admin,
):
    """C3: window 23:58–00:03 LA. Verify enforcement on both sides of midnight.

    `# pending #334` — TZ math went live in #442; if these assertions flip
    the failure is a real regression, file a separate issue.
    """
    profile_id = scratch_profile["id"]
    mac = client.mac

    original_clock = get_router_clock()
    sched_name = "e2e-c3-cross-midnight"
    schedule = {
        "name": sched_name,
        "days": ALL_DAYS,
        "startLocal": "23:58:00",
        "endLocal":   "00:03:00",
        "tz": "America/Los_Angeles",
    }

    # Pick a date and convert each local instant to UTC for the VM clock.
    date_la = datetime(2026, 5, 15, tzinfo=LA)
    def la(h, m) -> datetime:
        return date_la.replace(hour=h, minute=m).astimezone(timezone.utc)

    result = None
    try:
        # Start at 23:57 LA — just before the window opens.
        _set_clock_and_sync(admin, router, la(23, 57))

        result = admin.add_schedule(profile_id, schedule)
        wait_for_etag_change(admin, router.router_id, timeout_s=240)
        wait_for_next_poll(admin, router.router_id, timeout_s=240)

        # # pending #334
        assert not _mac_in_set(mac), "23:57 LA: outside window, should be allowed"

        # Step to 23:59 LA — inside window.
        _set_clock_and_sync(admin, router, la(23, 59))
        # # pending #334
        _wait_mac_in_blocked_set(mac, present=True, timeout_s=180)

        # Step across midnight to 00:01 LA (next day) — still inside window.
        next_la = date_la + timedelta(days=1)
        _set_clock_and_sync(
            admin, router,
            next_la.replace(hour=0, minute=1).astimezone(timezone.utc),
        )
        # # pending #334
        _wait_mac_in_blocked_set(mac, present=True, timeout_s=180)

        # Step to 00:04 LA — past window close.
        _set_clock_and_sync(
            admin, router,
            next_la.replace(hour=0, minute=4).astimezone(timezone.utc),
        )
        # # pending #334
        _wait_mac_in_blocked_set(mac, present=False, timeout_s=180)
    finally:
        sid = _schedule_id_from(result or {}, sched_name)
        if sid is not None:
            try:
                admin.remove_schedule(profile_id, sid)
            except Exception:
                pass
        try:
            set_router_clock(original_clock)
        except Exception:
            pass


# ── C4 — pause overrides schedule ──────────────────────────────────────────


def test_pause_overrides_schedule(
    router, client, scratch_device, scratch_profile, admin, debug_api,
):
    """C4: during a scheduled-block window, pause → reason flips to paused;
    unpause → reason returns to schedule. Precedence per #406."""
    profile_id = scratch_profile["id"]
    mac = client.mac

    original_clock = get_router_clock()
    anchor = datetime(2026, 5, 15, 12, 0, 0, tzinfo=timezone.utc)
    sched_name = f"e2e-c4-{anchor.strftime('%H%M%S')}"
    schedule = {
        "name": sched_name,
        "days": ALL_DAYS,
        "startLocal": "11:00:00",
        "endLocal":   "13:00:00",
        "tz": "UTC",
    }
    result = None
    paused = False
    try:
        _set_clock_and_sync(admin, router, anchor)

        result = admin.add_schedule(profile_id, schedule)
        wait_for_etag_change(admin, router.router_id, timeout_s=240)

        _wait_mac_in_blocked_set(mac, present=True, timeout_s=180)
        _wait_event(debug_api, mac, reason="schedule", timeout_s=60)

        # Pause — reason should flip to paused within one poll.
        admin.set_profile_paused(profile_id, True)
        paused = True
        wait_for_etag_change(admin, router.router_id, timeout_s=240)
        _wait_mac_in_blocked_set(mac, present=True, timeout_s=180)
        _wait_event(debug_api, mac, reason="paused", timeout_s=60)

        # Unpause — schedule still in window, reason should revert.
        admin.set_profile_paused(profile_id, False)
        paused = False
        wait_for_etag_change(admin, router.router_id, timeout_s=240)
        _wait_mac_in_blocked_set(mac, present=True, timeout_s=180)
        _wait_event(debug_api, mac, reason="schedule", timeout_s=60)
    finally:
        if paused:
            try:
                admin.set_profile_paused(profile_id, False)
            except Exception:
                pass
        sid = _schedule_id_from(result or {}, sched_name)
        if sid is not None:
            try:
                admin.remove_schedule(profile_id, sid)
            except Exception:
                pass
        try:
            set_router_clock(original_clock)
        except Exception:
            pass


# ── C5 — all-day weekday ───────────────────────────────────────────────────


def test_all_day_monday(
    router, client, scratch_device, scratch_profile, admin,
):
    """C5: all-day-Monday schedule; Sunday 23:59 → allowed, Monday 00:01 → blocked.

    `# pending #334` — depends on TZ-aware day-of-week evaluation."""
    profile_id = scratch_profile["id"]
    mac = client.mac

    original_clock = get_router_clock()
    sched_name = "e2e-c5-all-day-monday"
    schedule = {
        "name": sched_name,
        "days": ["mon"],
        "startLocal": "00:00:00",
        "endLocal":   "23:59:00",
        "tz": "America/Los_Angeles",
    }

    # 2026-05-17 is a Sunday in LA; 2026-05-18 is a Monday.
    sunday_2359 = datetime(2026, 5, 17, 23, 59, tzinfo=LA).astimezone(timezone.utc)
    monday_0001 = datetime(2026, 5, 18, 0, 1, tzinfo=LA).astimezone(timezone.utc)

    result = None
    try:
        _set_clock_and_sync(admin, router, sunday_2359)

        result = admin.add_schedule(profile_id, schedule)
        wait_for_etag_change(admin, router.router_id, timeout_s=240)
        wait_for_next_poll(admin, router.router_id, timeout_s=240)

        # # pending #334
        assert not _mac_in_set(mac), "Sun 23:59 LA: outside Monday window, should be allowed"

        _set_clock_and_sync(admin, router, monday_0001)
        # # pending #334
        _wait_mac_in_blocked_set(mac, present=True, timeout_s=180)
    finally:
        sid = _schedule_id_from(result or {}, sched_name)
        if sid is not None:
            try:
                admin.remove_schedule(profile_id, sid)
            except Exception:
                pass
        try:
            set_router_clock(original_clock)
        except Exception:
            pass


# ── C6 — smoke (C1 + C2 back-to-back, no clock acrobatics) ─────────────────


@pytest.mark.smoke
def test_in_then_out_smoke(
    router, client, scratch_device, scratch_profile, admin, debug_api,
):
    """C6 smoke: in-window blocks, then sliding the clock outside the window
    unblocks. Single schedule lifecycle, two clock jumps — fastest signal."""
    profile_id = scratch_profile["id"]
    mac = client.mac

    original_clock = get_router_clock()
    in_window  = datetime(2026, 5, 15, 12, 0, 0, tzinfo=timezone.utc)
    out_window = datetime(2026, 5, 15, 6,  0, 0, tzinfo=timezone.utc)
    sched_name = "e2e-c6-smoke"
    schedule = {
        "name": sched_name,
        "days": ALL_DAYS,
        "startLocal": "11:00:00",
        "endLocal":   "13:00:00",
        "tz": "UTC",
    }
    result = None
    try:
        _set_clock_and_sync(admin, router, in_window)

        result = admin.add_schedule(profile_id, schedule)
        wait_for_etag_change(admin, router.router_id, timeout_s=240)

        _wait_mac_in_blocked_set(mac, present=True, timeout_s=180)
        _wait_event(debug_api, mac, reason="schedule", timeout_s=60)

        _set_clock_and_sync(admin, router, out_window)
        _wait_mac_in_blocked_set(mac, present=False, timeout_s=180)
        _wait_http_succeeds(client, timeout_s=60)
    finally:
        sid = _schedule_id_from(result or {}, sched_name)
        if sid is not None:
            try:
                admin.remove_schedule(profile_id, sid)
            except Exception:
                pass
        try:
            set_router_clock(original_clock)
        except Exception:
            pass
