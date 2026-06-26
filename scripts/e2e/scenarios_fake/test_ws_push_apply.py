"""Suite K — ws policy-snapshot push transport, real agent (#1939).

The gap this closes: every other ws check exercises only PART of the push path —
the API emits to a test client (RouterWsSpec), the agent ws machinery is unit-
tested in isolation (busted ws_*_spec.lua), and ingest parity uses the stdlib
ws_send.py fake client (Gate 3). NONE drives a *server push* through the *real*
OpenWRT agent's wifihaven-ws sidecar.

This scenario does. With the sidecar enabled (UCI `wifihaven.ws.enabled=1`,
default-off everywhere else) and pointed at the Gate-2 fake API, it asserts the
two legs of the push path end-to-end through the real agent:

  Leg A — policy-on-connect (#1849): on upgrade the fake pushes the current
    snapshot; the sidecar receives it (`ws_frames_recv_total{op=policy}` ticks)
    and saves it to /etc/wifihaven/policy.json.
  Leg B — push-on-change (#1849): a server-side snapshot change pushes ONE fresh
    `policy` frame; the sidecar receives it and the on-disk snapshot's etag flips
    to the new value — and because the HTTP poll interval is widened to 3600s for
    this scenario, that flip can ONLY have arrived over the websocket, not a poll.

SCOPE (per #1939 + the operator's "test-only, defer wiring" decision): this
verifies the push *transport* reaches the real agent and the snapshot is
*saved*. It deliberately does NOT assert a live enforcement flip from the push:
the running agent applies policy only from its HTTP poll (the sidecar's on_policy
saves the snapshot file but the agent never re-reads it mid-run), so a pushed
snapshot does not change nft/dnsmasq state until the next poll or a restart.
Wiring agent-side apply-on-push (and extending this scenario with an
enforcement-flip assertion) is tracked in #1945.
Asserting only local file/metric observables also keeps the scenario clear of the
known intermittent runner→public-resolver egress flake (#1935) — no external
reachability is probed.
"""
from __future__ import annotations

import json

import pytest

from lib.vm import router_ssh
from lib.wait import wait_until

from .snapshot_builder import SnapshotBuilder

pytestmark = pytest.mark.ws_push

SNAPSHOT_PATH = "/etc/wifihaven/policy.json"
WS_METRICS_PATH = "/tmp/wifihaven-ws-metrics.txt"
WS_HEALTH_PATH = "/tmp/wifihaven-ws-health"

# A phantom device MAC — no client VM is booted because these scenarios assert
# router-side file/metric/nft observables only (snapshot saved + frame received +
# the @blocked_macs set membership), not client traffic enforcement.
DEV_MAC = "02:e2:9c:00:19:39"
PID = 1939
ETAG_ON_CONNECT = '"sha256:ws-push-onconnect-v1"'
ETAG_ON_CHANGE = '"sha256:ws-push-onchange-v2"'
# #1945 — distinct etags for the live-apply scenario so its on-disk + nft proofs
# can't be confused with the save-only scenario above.
ETAG_ENFORCE_BASE = '"sha256:ws-push-enforce-base-v1"'
ETAG_ENFORCE_PAUSED = '"sha256:ws-push-enforce-paused-v2"'


def _snapshot(*, etag: str, extra_blocked: list[str], paused: bool = False) -> dict:
    return (
        SnapshotBuilder()
        .add_profile(
            id=PID,
            name="e2e-ws-push",
            # #1945: a paused profile collapses to BlockRules.blocked=true, which
            # renders the device's MAC into the nft @blocked_macs set — a
            # client-free enforcement observable the live-apply test asserts on.
            blocked=paused,
            block_reason="Paused" if paused else None,
            extra_blocked=extra_blocked,
        )
        .add_device(mac=DEV_MAC, name="e2e-ws-push-dev", profile_id=PID)
        .build(etag=etag)
    )


def _enable_ws_and_freeze_poll() -> None:
    """Turn the ws sidecar on and widen the HTTP poll so only the push delivers.

    Setting `policy_poll_interval=3600` neutralises the poll for the scenario's
    lifetime (the agent still does ONE startup poll, then sleeps an hour), so any
    policy.json change observed after the socket is up is attributable to the ws
    push and nothing else. The settings live in the VM disk and are reverted by
    the next test's `router` fixture (router_restore to the ws-off base snapshot).
    """
    router_ssh(
        "uci set wifihaven.ws.enabled=1; "
        "uci set wifihaven.wifihaven.policy_poll_interval=3600; "
        "uci commit wifihaven; "
        "/etc/init.d/wifihaven restart",
        timeout=60,
    )


def _router_snapshot_etag() -> str | None:
    res = router_ssh(f"cat {SNAPSHOT_PATH} 2>/dev/null || true", check=False, timeout=10)
    out = (res.stdout or "").strip()
    if not out:
        return None
    try:
        return json.loads(out).get("etag")
    except (ValueError, AttributeError):
        return None


def _ws_recv_policy_count() -> int:
    """Parse ws_frames_recv_total{op=policy} from the sidecar's tmpfs tally.

    Format (ws_metrics.lua): `<name>\\t<label>\\t<count>` per counter line.
    Returns 0 when the file/line is absent (sidecar not up yet / no frame yet).
    """
    res = router_ssh(f"cat {WS_METRICS_PATH} 2>/dev/null || true", check=False, timeout=10)
    for line in (res.stdout or "").splitlines():
        parts = line.split("\t")
        if len(parts) == 3 and parts[0] == "ws_frames_recv_total" and parts[1] == "policy":
            try:
                return int(parts[2])
            except ValueError:
                return 0
    return 0


def _ws_health_present() -> bool:
    res = router_ssh(
        f"[ -s {WS_HEALTH_PATH} ] && echo yes || echo no",
        check=False, timeout=10,
    )
    return (res.stdout or "").strip() == "yes"


def _blocked_macs_set() -> str:
    """Dump the inet wifihaven @blocked_macs nft set (empty string if absent)."""
    res = router_ssh(
        "nft list set inet wifihaven blocked_macs 2>/dev/null || true",
        check=False, timeout=10,
    )
    return (res.stdout or "")


def test_ws_policy_push_received_and_saved(router, fake_api):
    # The fake serves the on-connect snapshot before the sidecar upgrades.
    fake_api.serve_snapshot(
        _snapshot(etag=ETAG_ON_CONNECT, extra_blocked=["example.com"])
    )

    # Enable the sidecar + freeze the poll, then wait for the agent to upgrade
    # the socket (the fake sees the connection).
    _enable_ws_and_freeze_poll()
    fake_api.wait_for_ws_connected(timeout_s=180)

    # ── Leg A — policy-on-connect ───────────────────────────────────────────
    # The on-connect push lands: the sidecar counts a received `policy` frame and
    # the ws-health sentinel is live.
    wait_until(
        lambda: True if _ws_recv_policy_count() >= 1 else None,
        timeout_s=90, interval_s=3,
        description="sidecar receives the on-connect policy frame "
                    "(ws_frames_recv_total{op=policy} >= 1)",
    )
    assert _ws_health_present(), "ws-health sentinel should be live once connected"
    # The pushed snapshot is saved to flash with the served etag. (The startup
    # poll also writes this etag; the push-specific proof is Leg B below, where
    # the frozen poll can't be the source.)
    wait_until(
        lambda: True if _router_snapshot_etag() == ETAG_ON_CONNECT else None,
        timeout_s=90, interval_s=3,
        description=f"policy.json carries the on-connect etag {ETAG_ON_CONNECT}",
    )
    baseline_recv = _ws_recv_policy_count()

    # ── Leg B — push-on-change, independent of the poll ─────────────────────
    # Swap the served snapshot. The fake fans ONE fresh `policy` frame to the
    # connected agent; the HTTP poll is frozen at 3600s, so the only path that
    # can move policy.json to the new etag is the ws push.
    push = fake_api.serve_snapshot(
        _snapshot(etag=ETAG_ON_CHANGE, extra_blocked=["example.com", "example.net"])
    )
    assert push == ETAG_ON_CHANGE

    wait_until(
        lambda: True if _router_snapshot_etag() == ETAG_ON_CHANGE else None,
        timeout_s=90, interval_s=3,
        description=f"policy.json etag flips to {ETAG_ON_CHANGE} via the ws push "
                    "(poll frozen at 3600s — push is the only possible source)",
    )
    wait_until(
        lambda: True if _ws_recv_policy_count() > baseline_recv else None,
        timeout_s=60, interval_s=3,
        description="sidecar receives a second policy frame (push-on-change)",
    )


def test_ws_policy_push_applies_enforcement_live(router, fake_api):
    """#1945 — a ws-pushed snapshot is APPLIED live, not just saved.

    The save-only test above proves the push reaches the agent and lands on
    flash; this proves the running agent then re-applies it to the enforcement
    plane (nft) WITHOUT an HTTP poll. With the poll frozen at 3600s, the only
    thing that can move nft state mid-run is the agent's apply-on-push tick
    reading the snapshot the sidecar wrote.

    Observable: the device's MAC appears in the inet wifihaven @blocked_macs set.
    That set is rendered directly from BlockRules.blocked (a paused profile),
    needs no client traffic / DNS resolve / external egress, and so dodges the
    intermittent runner→resolver flake (#1935). It flips ONLY if the agent
    actually re-rendered + reloaded nft from the pushed snapshot.
    """
    # ── On-connect baseline — device assigned, NOT paused ───────────────────
    fake_api.serve_snapshot(
        _snapshot(etag=ETAG_ENFORCE_BASE, extra_blocked=["example.com"])
    )
    _enable_ws_and_freeze_poll()
    fake_api.wait_for_ws_connected(timeout_s=180)

    # The one startup poll applies the base snapshot; the device must NOT be in
    # @blocked_macs yet (nothing is blocking it).
    wait_until(
        lambda: True if _router_snapshot_etag() == ETAG_ENFORCE_BASE else None,
        timeout_s=90, interval_s=3,
        description=f"base snapshot applied (etag {ETAG_ENFORCE_BASE})",
    )
    assert DEV_MAC not in _blocked_macs_set(), (
        "device should not be in @blocked_macs at the un-paused baseline"
    )

    # ── Push a PAUSED snapshot — poll frozen, so push is the only delivery ───
    push = fake_api.serve_snapshot(
        _snapshot(etag=ETAG_ENFORCE_PAUSED, extra_blocked=["example.com"], paused=True)
    )
    assert push == ETAG_ENFORCE_PAUSED

    # Enforcement flips from the push ALONE: the agent's apply-on-push tick reads
    # the sidecar-written snapshot and re-renders nft, landing the MAC in
    # @blocked_macs. Pre-#1945 (save-only) this never happens until the next poll
    # — frozen here at 3600s — so the assertion is the live-apply proof.
    wait_until(
        lambda: True if DEV_MAC in _blocked_macs_set() else None,
        timeout_s=120, interval_s=3,
        description="nft @blocked_macs gains the MAC via the ws push with the "
                    "poll frozen — live apply-on-push (#1945)",
    )
    # Sanity: the same push also advanced the on-disk snapshot etag.
    assert _router_snapshot_etag() == ETAG_ENFORCE_PAUSED
