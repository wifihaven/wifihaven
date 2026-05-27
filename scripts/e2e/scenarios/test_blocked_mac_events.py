"""#1122 — blocked-MAC traffic surfaces in /api/logs via the agent's nflog tail.

Rewrites the six steps from the closed PR #1119's `test_mac_drops_visible.py`
against the existing `connection_events` / `/api/logs?blocked=true` read path
instead of the rejected parallel `mac_drops` table.

Root cause recap (see #1122 body): conntrack -E NEW only fires for flows that
get *confirmed* at the postrouting hook. The forward-chain drop on
`@blocked_macs` / `eb_` / `bl_` / `blockIpOnly` happens between prerouting and
postrouting, so dropped flows never emit IPCT_NEW and the agent's existing
connection_attempt path never sees them. #1122 adds `log group 1` to every
drop rule and tails nflog from the agent, synthesizing `connection_attempt`
events with `allowed=false reason=<MacBlockReason>`.

Each step here drives the live VM stack through one block reason, generates
client traffic, and asserts a blocked-MAC `connection_attempt` row appears
in /api/logs within the agent's flush window.

Step 5 is an *absence* assertion: after unblocking, no new blocked-MAC rows
appear for the MAC within one poll interval. Absence is what regressed in
#1119's deleted-table approach — a polling counter-diff path could keep
synthesizing the same diff long after the drop rule was gone. The nflog
approach is event-driven, so this assertion is meaningful.
"""
from __future__ import annotations

import time

import pytest

from lib.traffic import http_get
from lib.wait import wait_for_etag_change, wait_until

pytestmark = pytest.mark.blocked_mac_events


def _norm(mac: str) -> str:
    return mac.lower().strip()


def _wait_blocked_event(
    debug_api,
    mac: str,
    *,
    reason: str,
    timeout_s: float = 60,
) -> list[dict]:
    """Wait for at least one allowed=False blocked-MAC event with the given reason."""
    return wait_until(
        lambda: (
            debug_api.events_for_mac_filtered(mac, allowed=False, reason=reason) or None
        ),
        timeout_s=timeout_s,
        interval_s=2,
        description=f"allowed=False reason={reason} event for {mac}",
    )


def _drive_traffic(client, *, hosts: tuple[str, ...] = ("example.com", "wikipedia.org")) -> None:
    """Generate forward-bound flows so the kernel's forward chain has packets
    to drop. The block hits the nft drop rule, the `log group 1` action sends
    the packet to nflog, and the agent's nflog tail synthesizes the event."""
    for h in hosts:
        try:
            http_get(client, f"http://{h}/", timeout_s=5)
        except Exception:
            # Connection refused / dropped is the normal outcome — we just
            # need the SYNs to traverse the forward chain.
            pass


# ── Step 1: Paused profile ─────────────────────────────────────────────────


@pytest.mark.smoke
def test_paused_profile_emits_blocked_mac_event(
    router, client, scratch_device, scratch_profile, admin, debug_api,
):
    """Pause → blocked-MAC rows appear in /api/logs with reason=Paused."""
    profile_id = scratch_profile["id"]
    mac = client.mac

    admin.set_profile_paused(profile_id, True)
    wait_for_etag_change(admin, router.router_id, timeout_s=240)

    _drive_traffic(client)
    _wait_blocked_event(debug_api, mac, reason="Paused", timeout_s=90)


# ── Step 2: Time-limit exhausted ───────────────────────────────────────────


def test_time_limit_exhausted_emits_blocked_mac_event(
    router, client, scratch_device, scratch_profile, admin, debug_api,
):
    """Daily time-limit exhausted → reason=TimeLimit."""
    profile_id = scratch_profile["id"]
    mac = client.mac

    # 1-minute limit means we hit it after a single 5-minute traffic bucket.
    admin.apply_profile_update(profile_id, timeLimit=1)
    # Burn the budget by reporting fake traffic via the admin path. The
    # specifics live with the existing test_time_limit.py harness; here we
    # rely on the same primitive used there.
    admin.set_time_used_today(profile_id=profile_id, mac=mac, minutes=60)
    wait_for_etag_change(admin, router.router_id, timeout_s=240)

    _drive_traffic(client)
    _wait_blocked_event(debug_api, mac, reason="TimeLimit", timeout_s=90)


# ── Step 3: Schedule window ────────────────────────────────────────────────


def test_active_schedule_emits_blocked_mac_event(
    router, client, scratch_device, scratch_profile, admin, debug_api,
):
    """Active schedule window → reason=Schedule."""
    profile_id = scratch_profile["id"]
    mac = client.mac

    admin.add_schedule(profile_id, {
        "daysOfWeek": ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"],
        "startTime": "00:00",
        "endTime":   "23:59",
        "timezone":  "UTC",
    })
    wait_for_etag_change(admin, router.router_id, timeout_s=240)

    _drive_traffic(client)
    _wait_blocked_event(debug_api, mac, reason="Schedule", timeout_s=90)


# ── Step 4: Unmanaged MAC under default-block ──────────────────────────────


def test_unmanaged_mac_emits_blocked_mac_event(
    router, client, admin, debug_api,
):
    """Unenrolled MAC + unmanagedMacPolicy.policy=block → reason=Unmanaged.

    Uses a fresh MAC the API has never seen so it ends up profile-less,
    which is what unmanagedMacPolicy gates on (#961).
    """
    # New MAC for the client — bypass the scratch_device fixture so the MAC
    # is genuinely unenrolled.
    import uuid
    new_mac = "02:e2:e2:c4:00:%02x" % (uuid.uuid4().int & 0xFF)

    admin.set_household_settings({
        "unmanagedMacPolicy": {"policy": "block", "blockPage": True},
    })
    wait_for_etag_change(admin, router.router_id, timeout_s=240)

    # First-seen event auto-creates the device row with profileId=None,
    # which the snapshot then resolves to Unmanaged-blocked rules.
    client.set_mac(new_mac)
    _drive_traffic(client)
    _wait_blocked_event(debug_api, new_mac, reason="Unmanaged", timeout_s=120)


# ── Step 5: After unblock, no new blocked-MAC rows ─────────────────────────


def test_unblock_stops_new_blocked_mac_events(
    router, client, scratch_device, scratch_profile, admin, debug_api,
):
    """After unpausing, no new allowed=False rows for the MAC within one poll."""
    profile_id = scratch_profile["id"]
    mac = client.mac

    admin.set_profile_paused(profile_id, True)
    wait_for_etag_change(admin, router.router_id, timeout_s=240)
    _drive_traffic(client)
    initial = _wait_blocked_event(debug_api, mac, reason="Paused", timeout_s=90)
    last_seen_ts = max(e.get("ts", "") for e in initial)

    admin.set_profile_paused(profile_id, False)
    wait_for_etag_change(admin, router.router_id, timeout_s=240)

    # Drive new traffic now that the drop rule is gone. Within one full
    # agent flush window (10s default + slack) no new blocked rows should
    # appear for this MAC. We poll for 30s and assert nothing newer than
    # `last_seen_ts` shows up.
    deadline = time.time() + 30
    while time.time() < deadline:
        _drive_traffic(client)
        evs = debug_api.events_for_mac_filtered(mac, allowed=False)
        new_evs = [e for e in evs if e.get("ts", "") > last_seen_ts]
        assert not new_evs, (
            f"unexpected new blocked-MAC events after unblock: {new_evs}"
        )
        time.sleep(2)


# ── Step 6: Cross-check — host attribution from dnsmasq cache ──────────────


def test_blocked_event_carries_host_attribution_when_available(
    router, client, scratch_device, scratch_profile, admin, debug_api,
):
    """Blocked-MAC rows carry FQDN attribution when dnsmasq resolved the host."""
    profile_id = scratch_profile["id"]
    mac = client.mac

    admin.set_profile_paused(profile_id, True)
    wait_for_etag_change(admin, router.router_id, timeout_s=240)

    # Drive HTTP to example.com — dnsmasq resolves it and the agent's
    # `lookup_hostname` callback attributes the dropped flow back to that
    # name via the dns-tail cache. The nflog event should carry
    # host.type="fqdn".
    _drive_traffic(client, hosts=("example.com",))
    evs = _wait_blocked_event(debug_api, mac, reason="Paused", timeout_s=120)

    fqdn_attributed = [e for e in evs if (e.get("host") or {}).get("type") == "fqdn"]
    assert fqdn_attributed, (
        f"no FQDN-attributed blocked rows for {mac}; events were: {evs}"
    )
