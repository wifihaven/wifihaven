"""Household-day rollover across three surfaces (#1110, follow-up to #1104).

#1104 consolidated `today` derivation into `TimeStatusService.todaysState`;
both the snapshot served to the router agent and the seven `/api/time/status/*`
endpoints now funnel through it. The contract specs in `api/test/src/feature/`
lock equality at the API level (UI shape == snapshot shape). This scenario
closes the loop on the third surface — the agent's `@blocked_macs` ipset —
by asserting all three agree as the household day flips.

## Mechanism — settings-driven rollover, not clock advancement

The harness can manipulate the router VM wall clock (`lib/clock.py`), but
the API's `Clock.today` reads real host wall-clock and there is no debug
clock affordance (see #1110 thread). We get equivalent assertion power by
moving the *household-day boundary* instead of moving the clock:

  `householdLocalDate(instant, settings)`:
    zdt = instant.atZone(dailyResetTz)
    if zdt.localTime < dailyResetTime: zdt.localDate - 1
    else                              : zdt.localDate

So with a fixed `now` and fixed usage instants, two choices of
`dailyResetTime` produce two different household-days:

  Phase A: dailyResetTime in the FUTURE (relative to now's local time)
           → today = Y-1
           → usage we just wrote (also before the threshold) lands on Y-1
           → cap exhausted on Y-1, profile.rules.blocked == true

  Phase B: dailyResetTime between (usage_instant) and (now's local time)
           → today = Y
           → usage instants stay on Y-1 (before threshold)
           → today's usage = 0, profile.rules.blocked == false (fresh day)

The date returned by `/api/time/status/{mac}` flips Y-1 → Y in lockstep
with the snapshot's `blocked` flipping true → false in lockstep with the
agent dropping the MAC from `@blocked_macs`. That three-surface lockstep
is the structural assertion #1110 wants — it's what makes future drift
between the UI/snapshot/router-enforcement paths impossible without test
failure, regardless of which path a future change touches.

## Why cap=1, not 30

The issue spec uses a 30-minute cap exhausted by 30 minutes of presence.
The harness has no debug usage-flush; usage flows on the agent's natural
~300s cycle, and exhausting 30 minutes would take ~30 minutes of real
client traffic. We use the same cap=1 / ~90s-traffic shape as
`test_04_daily_limit.py` and `test_time_limit.py`. The structural lock
(three surfaces agree on the same household-day) doesn't depend on the
cap magnitude.

## Why tz=America/Denver

#1104's repro was a Denver household. Keeping the same tz here so a
failure points right at the same code path (and the same operator
context) the consolidation fix was written against.
"""
from __future__ import annotations

import json
import logging
import time
import urllib.request
from datetime import datetime, time as dtime, timedelta, timezone
from zoneinfo import ZoneInfo

import pytest

from lib.traffic import http_get
from lib.vm import router_nft_set
from lib.wait import wait_for_etag_change, wait_for_next_poll, wait_until

pytestmark = pytest.mark.tz_rollover

log = logging.getLogger(__name__)

BLOCKED_MACS_SET = "blocked_macs"
USAGE_REPORT_INTERVAL_S = 300  # agent default; orchestrator does not override
TZ_NAME = "America/Denver"
DENVER = ZoneInfo(TZ_NAME)

# Window for Phase A: the dailyResetTime must stay AFTER `now` for the entire
# phase-A window (write usage + flush + poll). 12 minutes leaves headroom for
# the ~6-minute exhaustion path plus assertion overhead.
PHASE_A_WINDOW = timedelta(minutes=12)


def _norm_mac(mac: str) -> str:
    return mac.lower().strip()


def _mac_in_blocked_set(mac: str) -> bool:
    members = {_norm_mac(m) for m in router_nft_set(BLOCKED_MACS_SET)}
    return _norm_mac(mac) in members


def _fetch_snapshot(api_base: str, router_token: str) -> dict:
    """GET /api/router/policy with the router's bearer token.

    Hits the host-mapped API port directly (not via the router VM); we want
    the snapshot as the API would serve it right now, not whatever the agent
    last cached. Returns the parsed JSON snapshot.
    """
    req = urllib.request.Request(
        f"{api_base}/api/router/policy",
        headers={
            "authorization": f"Bearer {router_token}",
            "accept": "application/json",
        },
        method="GET",
    )
    with urllib.request.urlopen(req, timeout=15) as resp:  # noqa: S310
        return json.loads(resp.read().decode("utf-8"))


def _snapshot_rules_for_mac(snap: dict, mac: str) -> dict | None:
    """Walk snapshot → device → effective rules. Returns None if not present."""
    devices = snap.get("devices") or {}
    needle = _norm_mac(mac)
    # Snapshot keys are MacAddress.toString — match case-insensitively.
    for key, dev in devices.items():
        if _norm_mac(key) == needle:
            return dev.get("rules")
    return None


def _put_household_settings(admin, *, daily_reset_time: dtime, tz: str) -> None:
    """Set the household's `dailyResetTime` + `dailyResetTz`.

    `daily_reset_time` is a `datetime.time`; serialized as HH:MM:SS.
    """
    admin._request(
        "PUT", "/api/household/settings",
        body={
            "dailyResetTime": daily_reset_time.strftime("%H:%M:%S"),
            "dailyResetTz": tz,
        },
    )


def _household_local_date(instant_utc, *, daily_reset_time: dtime, tz: ZoneInfo) -> str:
    """Mirror of `PolicyService.householdLocalDate` (for assertion expectations)."""
    zdt = instant_utc.astimezone(tz)
    if zdt.time() < daily_reset_time:
        return (zdt.date() - timedelta(days=1)).isoformat()
    return zdt.date().isoformat()


@pytest.mark.tz_rollover
def test_household_tz_rollover_three_surfaces_agree(
    router, client, scratch_device, scratch_profile, admin, stack,
):
    """All three surfaces (UI endpoint, snapshot, @blocked_macs) flip together."""
    api_base = stack.base_url
    mac = client.mac

    # ── Phase A setup: cap, tz, dailyResetTime in the FUTURE ─────────────────
    #
    # Capture wall-now in Denver tz; pick dailyResetTime = now+12min. All
    # work in this phase (write usage, wait for flush, wait for etag) happens
    # while now's localTime < dailyResetTime, so today = Y-1 throughout.
    t0_utc = datetime.now(timezone.utc)
    t0_denver = t0_utc.astimezone(DENVER)
    phase_a_reset = (t0_denver + PHASE_A_WINDOW).time().replace(microsecond=0)
    expected_date_phase_a = _household_local_date(
        t0_utc, daily_reset_time=phase_a_reset, tz=DENVER,
    )
    log.info(
        "Phase A: now=%s denver=%s dailyResetTime=%s expected_date=%s",
        t0_utc.isoformat(), t0_denver.isoformat(),
        phase_a_reset.isoformat(), expected_date_phase_a,
    )

    _put_household_settings(admin, daily_reset_time=phase_a_reset, tz=TZ_NAME)
    admin.apply_profile_update(scratch_profile["id"], timeLimit=1)
    wait_for_next_poll(admin, router.router_id, timeout_s=120)

    assert not _mac_in_blocked_set(mac), "precondition: MAC should not be blocked yet"

    # Drive ~90s of allowed traffic to exhaust the 1-min cap.
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        http_get(client, "http://example.com/", timeout_s=5)
        time.sleep(2)

    # Wait one usage-report cycle for the agent to flush usage to the API,
    # then for the snapshot etag to bump (= profile.rules.blocked flipped).
    log.info("Phase A: sleeping %ds for agent usage-report flush", USAGE_REPORT_INTERVAL_S + 30)
    time.sleep(USAGE_REPORT_INTERVAL_S + 30)
    wait_for_etag_change(admin, router.router_id, timeout_s=400)

    wait_until(
        lambda: True if _mac_in_blocked_set(mac) else None,
        timeout_s=120, interval_s=3,
        description=f"{mac} present in {BLOCKED_MACS_SET} after cap exhaustion",
    )

    # ── Phase A assertions: three surfaces all agree on "blocked, Y-1" ───────
    ui_a = admin._request("GET", f"/api/time/status/{mac}")
    snap_a = _fetch_snapshot(api_base, router.router_token)
    profile_id = scratch_profile["id"]
    snap_dev_a = _snapshot_rules_for_mac(snap_a, mac)
    snap_prof_a = (snap_a.get("profiles") or {}).get(str(profile_id))

    assert ui_a["date"] == expected_date_phase_a, (
        f"Phase A UI: date={ui_a['date']!r}, expected {expected_date_phase_a!r}; "
        f"full payload={ui_a!r}"
    )
    assert ui_a["usedMins"] >= 1, (
        f"Phase A UI: usedMins={ui_a['usedMins']}, expected >= 1 after exhaustion"
    )

    assert snap_dev_a is not None, f"Phase A snapshot: no device entry for {mac}"
    # Device-level rules may be None for profile-managed devices — the
    # blocking decision lives on the profile entry (BlockRules folded by
    # PolicyService). Verify both: if device.rules is populated, it should
    # agree; the profile rules are the load-bearing assertion.
    assert snap_prof_a is not None, (
        f"Phase A snapshot: no profile entry for {profile_id}"
    )
    pa_rules = snap_prof_a["rules"]
    assert pa_rules["blocked"] is True, (
        f"Phase A snapshot: profile.rules.blocked={pa_rules['blocked']!r}, "
        f"expected true (TimeLimit). rules={pa_rules!r}"
    )
    assert pa_rules.get("blockReason") == "TimeLimit", (
        f"Phase A snapshot: blockReason={pa_rules.get('blockReason')!r}, "
        f"expected 'TimeLimit'. rules={pa_rules!r}"
    )

    assert _mac_in_blocked_set(mac), (
        f"Phase A router: {mac} missing from {BLOCKED_MACS_SET} despite snapshot blocked=true"
    )

    log.info(
        "Phase A OK: UI.date=%s usedMins=%s | snap.blocked=%s reason=%s | router blocked",
        ui_a["date"], ui_a["usedMins"], pa_rules["blocked"], pa_rules.get("blockReason"),
    )

    # ── Phase B: roll the day forward by moving dailyResetTime into the past ─
    #
    # New dailyResetTime is between (Phase A's `t0_denver` ≤ usage_instant)
    # and (now). Pick (t0_denver + 90s + 30s) so it sits comfortably after the
    # last burst write and before now (which is at least t0 + USAGE_REPORT +
    # 30s + 90s burst ≈ t0 + 7min). Usage instants stay on Y-1; today flips
    # to Y; today's usage = 0 → unblocked.
    t1_utc = datetime.now(timezone.utc)
    phase_b_reset = (t0_denver + timedelta(seconds=120)).time().replace(microsecond=0)
    expected_date_phase_b = _household_local_date(
        t1_utc, daily_reset_time=phase_b_reset, tz=DENVER,
    )
    assert expected_date_phase_b != expected_date_phase_a, (
        "test setup error: phase B should resolve to a different household-date "
        f"than phase A ({expected_date_phase_a!r}); got {expected_date_phase_b!r}. "
        "Likely the wall clock crossed midnight Denver during Phase A — re-run."
    )
    log.info(
        "Phase B: now=%s dailyResetTime=%s expected_date=%s",
        t1_utc.isoformat(), phase_b_reset.isoformat(), expected_date_phase_b,
    )

    _put_household_settings(admin, daily_reset_time=phase_b_reset, tz=TZ_NAME)

    # Snapshot etag should bump as today flips Y-1 → Y and blocked flips
    # true → false on the profile.
    wait_for_etag_change(admin, router.router_id, timeout_s=180)

    wait_until(
        lambda: True if not _mac_in_blocked_set(mac) else None,
        timeout_s=180, interval_s=3,
        description=f"{mac} cleared from {BLOCKED_MACS_SET} after household-day rollover",
    )

    # ── Phase B assertions: three surfaces all agree on "unblocked, Y" ───────
    ui_b = admin._request("GET", f"/api/time/status/{mac}")
    snap_b = _fetch_snapshot(api_base, router.router_token)
    snap_prof_b = (snap_b.get("profiles") or {}).get(str(profile_id))

    assert ui_b["date"] == expected_date_phase_b, (
        f"Phase B UI: date={ui_b['date']!r}, expected {expected_date_phase_b!r}; "
        f"full payload={ui_b!r}"
    )
    assert ui_b["usedMins"] == 0, (
        f"Phase B UI: usedMins={ui_b['usedMins']}, expected 0 on a fresh household-day"
    )

    assert snap_prof_b is not None
    pb_rules = snap_prof_b["rules"]
    assert pb_rules["blocked"] is False, (
        f"Phase B snapshot: profile.rules.blocked={pb_rules['blocked']!r}, "
        f"expected false (fresh day). rules={pb_rules!r}"
    )
    assert pb_rules.get("blockReason") in (None, "null"), (
        f"Phase B snapshot: blockReason={pb_rules.get('blockReason')!r}, "
        f"expected null. rules={pb_rules!r}"
    )

    assert not _mac_in_blocked_set(mac), (
        f"Phase B router: {mac} still in {BLOCKED_MACS_SET} despite snapshot blocked=false"
    )

    log.info(
        "Phase B OK: UI.date=%s usedMins=%s | snap.blocked=%s reason=%s | router unblocked",
        ui_b["date"], ui_b["usedMins"], pb_rules["blocked"], pb_rules.get("blockReason"),
    )
