"""Suite K — ws policy-snapshot push transport, real agent (#1939).

The gap this closes: every other ws check exercises only PART of the push path —
the API emits to a test client (RouterWsSpec), the agent ws machinery is unit-
tested in isolation (busted ws_*_spec.lua), and ingest parity uses the stdlib
ws_send.py fake client (Gate 3). NONE drives a *server push* through the *real*
OpenWRT agent's wifihaven-ws sidecar.

This scenario does. Pointed at the Gate-2 fake API, it asserts the
two legs of the push path end-to-end through the real agent:

  Leg A — policy-on-connect (#1849): on upgrade the fake pushes the current
    snapshot; the sidecar receives it (`ws_frames_recv_total{op=policy}` ticks)
    and saves it to /etc/wifihaven/policy.json.
  Leg B — push-on-change (#1849): a server-side snapshot change pushes ONE fresh
    `policy` frame; the sidecar receives it and the on-disk snapshot's etag flips
    to the new value — and because the HTTP poll interval is widened to 3600s for
    this scenario, that flip can ONLY have arrived over the websocket, not a poll.

The save-only legs (#1939) prove the push *transport* reaches the real agent and
the snapshot is *saved* to flash.

#1945 adds a third test — `test_ws_policy_push_applies_enforcement_live` — that
asserts a live *enforcement* flip from the push alone: with the poll frozen, a
pushed paused snapshot installs a per-MAC `wh_drop:<mac>:Paused` forward-drop in
nft, which only happens if the running agent re-reads + applies the snapshot the
sidecar wrote (the apply-on-push tick). Before #1945 the sidecar saved the file
but the agent applied policy only from its HTTP poll, so the snapshot never
changed nft/dnsmasq until the next poll or a restart.

All three tests assert only router-side file/metric/nft observables (no external
reachability is probed), keeping the scenario clear of the known intermittent
runner→public-resolver egress flake (#1935).
"""
from __future__ import annotations

import time

import pytest

from lib.vm import router_ssh
from lib.wait import router_snapshot_etag, wait_until

from .snapshot_builder import SnapshotBuilder

pytestmark = pytest.mark.ws_push

WS_METRICS_PATH = "/tmp/wifihaven-ws-metrics.txt"
WS_HEALTH_PATH = "/tmp/wifihaven-ws-health"
# #2229 event-driven apply trigger: the sidecar writes "<etag>\t<uptime>" here
# the moment it persists a pushed snapshot; the agent reads it every on_tick and
# applies immediately (instead of waiting for the poll-of-disk gate).
WS_PENDING_PATH = "/tmp/wifihaven-ws-pending"

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
# #2229 — distinct etags for the IDLE event-driven-apply scenario (no conntrack
# nudge), kept separate from the nudged live-apply scenario above.
ETAG_IDLE_BASE = '"sha256:ws-push-idle-base-v1"'
ETAG_IDLE_PAUSED = '"sha256:ws-push-idle-paused-v2"'
# #2207 — distinct etags for the carve-out live-apply scenario (a paused profile
# that KEEPS extraAllowed hosts, so the whole-MAC drop renders with per-(MAC,host)
# `!= @ea_...` carve clauses). Kept separate from the plain scenario above.
ETAG_CARVE_BASE = '"sha256:ws-push-carve-base-v1"'
ETAG_CARVE_PAUSED = '"sha256:ws-push-carve-paused-v2"'
# The hosts a paused profile retains in extraAllowed (a soft pause / allowed-app
# carve-out). The whole-MAC `:Paused` drop must render WITH these as `!= @ea_...`
# exceptions — it must NOT be suppressed by their presence (the #2207 concern).
CARVE_ALLOWED = ["gimkit.com", "gimkitconnect.com", "eaglercraft.dev"]


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


def _restart_agent() -> None:
    """Restart the agent so this scenario starts from a known transport state.

    #2736 emptied this of its old contents and that is the point. It used to set
    `ws.enabled=1` and widen `policy_poll_interval` to an hour, so that only the
    ws push could deliver a snapshot. Neither is needed or possible now: the
    toggle is gone, and there is no HTTP poll left for a push to be confused
    with, so the attribution this function existed to establish is now
    structural. A plain restart is all that remains.
    """
    router_ssh("/etc/init.d/wifihaven restart", timeout=60)


_router_snapshot_etag = router_snapshot_etag


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


def _ws_app_frames_and_stamp() -> tuple[int, int | None]:
    """One ssh round trip → (usage+events frames sent, health-sentinel stamp).

    Both values come from a SINGLE `cat` so they describe the same instant. Read
    separately, a usage frame landing between the two reads would produce a
    window that looks quiet but was not, which is exactly the sample this
    scenario must not mis-classify.

    The sentinel is read as CONTENT rather than mtime because stock busybox has
    no `stat -c %Y` — the agent reads it the same way (ws_health_read).
    """
    res = router_ssh(
        f"cat {WS_METRICS_PATH} 2>/dev/null; echo '---'; cat {WS_HEALTH_PATH} 2>/dev/null",
        check=False, timeout=10,
    )
    metrics_text, _, stamp_text = (res.stdout or "").partition("---")
    frames = 0
    for line in metrics_text.splitlines():
        parts = line.split("\t")
        if len(parts) == 3 and parts[0] == "ws_frames_sent_total" and parts[1] in ("usage", "events"):
            try:
                frames += int(parts[2])
            except ValueError:
                pass
    try:
        stamp: int | None = int(stamp_text.strip())
    except ValueError:
        stamp = None
    return frames, stamp


def _ws_health_present() -> bool:
    res = router_ssh(
        f"[ -s {WS_HEALTH_PATH} ] && echo yes || echo no",
        check=False, timeout=10,
    )
    return (res.stdout or "").strip() == "yes"


def _nudge_conntrack() -> None:
    """Emit a small burst of LAN-side flows so the agent's `on_tick` fires.

    The agent's cooperative scheduler — the policy poll, usage/metrics timers,
    and the #1945 apply-on-push tick — all run inside `conntrack.watch`'s loop,
    which calls `on_tick` only after a blocking read returns a `conntrack -E NEW`
    line (conntrack.lua). `conntrack -E -e NEW` is unfiltered, so ANY new flow —
    including a router-originated one — drives a tick. This phantom-device
    scenario boots no client VM, so nothing else reliably generates flows and
    `on_tick` would be starved; a short HTTP GET to the API the agent already
    reaches opens a fresh tracked connection → a NEW event → an `on_tick` pass,
    exactly as a live network's traffic does continuously. The GET carries NO
    policy (the HTTP poll stays frozen at 3600s, and a bare /healthz hit is not a
    snapshot fetch), so the enforcement flip remains attributable to the ws push.

    #2001: we fire a *burst* (not a single GET) because `conntrack -E`'s stdout
    is block-buffered when piped (no `stdbuf` on OpenWRT), so under VM load a lone
    sparse event can sit in conntrack's ~4 KiB buffer for tens of seconds before
    it flushes to the agent's reader — starving `on_tick` and stretching the
    apply latency. A handful of flows per wait-iteration fills that buffer sooner,
    so `on_tick` (and the apply-on-push tick it rides) runs promptly. Still bounded
    and policy-free, so the push remains the sole enforcement source.
    """
    router_ssh(
        'u="$(uci get wifihaven.wifihaven.api_url)"; '
        "for _ in 1 2 3 4 5; do "
        'curl -s -m 2 -o /dev/null "$u/healthz" 2>/dev/null || true; '
        "done",
        check=False, timeout=20,
    )


def _ws_pending_etag() -> str | None:
    """The etag in the #2229 event-driven apply trigger, or None if absent.

    Format (ws_pending.encode): "<etag>\\t<uptime>". We only need the etag field.
    """
    res = router_ssh(f"cat {WS_PENDING_PATH} 2>/dev/null || true", check=False, timeout=10)
    out = (res.stdout or "").strip()
    if not out:
        return None
    return out.split("\t", 1)[0] or None


def _paused_drop_rule_present(mac: str) -> bool:
    """True iff the live nft ruleset carries a whole-MAC *paused* forward-drop.

    A paused profile collapses to BlockRules.blocked=true, which render.lua emits
    as a per-MAC forward-drop labelled `wh_drop:<mac>:Paused`. We key on the
    `:Paused` reason rather than the @blocked_macs set because a global allowlist
    (the infra allow set is always present in real deployments, #1307/#1308)
    routes blocked MACs to per-MAC rules carrying a `!= @global_allow` carve-out
    instead of the family-agnostic @blocked_macs set — so the set can be empty
    while the device is fully blocked. The `:Paused` drop is the unambiguous,
    config-independent enforcement signal, and it is absent for an un-paused
    device regardless of its extraBlocked/blocklist rules (those carry different
    drop reasons).
    """
    res = router_ssh(
        f'nft list table inet wifihaven 2>/dev/null | grep -F "wh_drop:{mac}:Paused" || true',
        check=False, timeout=10,
    )
    return bool((res.stdout or "").strip())


def test_ws_policy_push_received_and_saved(router, fake_api):
    # The fake serves the on-connect snapshot before the sidecar upgrades.
    fake_api.serve_snapshot(
        _snapshot(etag=ETAG_ON_CONNECT, extra_blocked=["example.com"])
    )

    # Enable the sidecar + freeze the poll, then wait for the agent to upgrade
    # the socket (the fake sees the connection).
    _restart_agent()
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


def test_ws_health_sentinel_is_refreshed_by_the_heartbeat_alone(router, fake_api):
    """#2731 — a live-but-quiet socket keeps proving itself, with no app traffic.

    The bug: the sidecar wrote the health sentinel only on connect and on
    APPLICATION frames in either direction, and the agent's outbound tee only
    produces application frames while that same sentinel is fresh. The signal fed
    itself, so one quiet gap past ws.fallback_after (300s) latched a perfectly
    healthy connection into permanent HTTP polling — measured on prod as a
    sentinel 80 minutes stale under a socket that was still heartbeating. (That
    was under the old `ws.fallback_after` window; #2736 removed both the poll it
    fell back to and the key, and the sentinel is now what the failover edge and
    alert W15 read instead — so this property matters more, not less.)

    The fix makes the control ping/pong exchange refresh the sentinel, so this
    asserts exactly that: a window in which the sentinel ADVANCED while the
    sidecar sent NO application frame. Pre-#2731 no such window exists on a quiet
    link — the sentinel only ever moves alongside a usage/events frame.

    Sampling rather than a single before/after pair because the agent's own usage
    timer can fire inside any given window; we need one clean window, not a lucky
    one. The heartbeat is dropped to 5s so several fit inside the sampling run.

    "Quiet" counts only OUTBOUND application frames, even though an inbound one
    would also refresh the sentinel (ws_loop.handle_inbound). That is sound here
    because the Gate-2 fake does not ack (fake-api/fake_api/app.py) and no
    snapshot changes mid-run, so there is no inbound application traffic to
    confound the window. A fake that starts acking would need this to count
    received frames too.
    """
    fake_api.serve_snapshot(_snapshot(etag=ETAG_ON_CONNECT, extra_blocked=[]))

    # The faster heartbeat is written FIRST, uncommitted, so the helper's commit
    # and restart pick it up: the freeze decision stays in one place and the
    # sidecar comes up once, rather than connecting on the 30s heartbeat and
    # being restarted out from under itself a moment later. This leans on uci
    # staging an uncommitted `set` in the savedir, where it outlives the one-shot
    # ssh session, so the helper's `uci commit wifihaven` sweeps it up along with
    # its own — which is exactly the coupling a future edit to the helper could
    # break from a distance, hence the assertion below.
    router_ssh("uci set wifihaven.ws.heartbeat_interval=5", timeout=30)
    _restart_agent()
    # Pin the precondition. A 30s heartbeat still lands inside the 300s window,
    # so a heartbeat that silently failed to commit would leave this scenario
    # GREEN while measuring the slow path — the assertion is what turns that into
    # a failure. Same spirit as the every-sample-parsed anchor further down.
    committed = router_ssh(
        "uci get wifihaven.ws.heartbeat_interval", check=False, timeout=30,
    ).stdout.strip()
    assert committed == "5", (
        f"the 5s heartbeat did not commit (uci reports {committed!r}) — the "
        "scenario would still pass on the 30s default while measuring something "
        "slower than it claims to"
    )
    fake_api.wait_for_ws_connected(timeout_s=180)
    wait_until(
        lambda: True if _ws_health_present() else None,
        timeout_s=90, interval_s=3,
        description="ws-health sentinel exists once the socket is up",
    )

    # Sample (application frames sent, sentinel stamp) until one consecutive pair
    # shows the frame counters holding still while the sentinel moved. Up to ~30
    # samples over ~60s against a 5s heartbeat and a 60s usage timer, so at most a
    # couple of pairs can be contaminated by a real frame — but the loop stops at
    # the first clean one, which on a healthy link is within a few samples.
    samples: list[tuple[int, int | None]] = []
    quiet_refreshes: list[tuple[tuple[int, int | None], tuple[int, int | None]]] = []
    for _ in range(30):
        samples.append(_ws_app_frames_and_stamp())
        if len(samples) >= 2:
            a, b = samples[-2], samples[-1]
            if a[1] is not None and b[1] is not None and a[0] == b[0] and b[1] > a[1]:
                quiet_refreshes.append((a, b))
                break
        time.sleep(2)

    # Liveness anchor: the sentinel must have been readable on EVERY sample. A
    # harness that could not read the router at all would otherwise satisfy "no
    # frame was sent" for free, which is the absence-assertion trap.
    stamps = [st for _, st in samples if st is not None]
    assert len(stamps) == len(samples), (
        f"the sentinel became unreadable mid-run ({len(stamps)}/{len(samples)} "
        "samples parsed) — the router or sidecar died, so this run proves nothing"
    )

    assert quiet_refreshes, (
        "the ws-health sentinel never advanced during a window in which the "
        "sidecar sent no usage/events frame — the heartbeat is not refreshing it, "
        "so a quiet link will age out into the HTTP poll fallback (#2731). "
        f"samples (app_frames, stamp): {samples}"
    )


def test_ws_policy_push_applies_enforcement_live(router, fake_api):
    """#1945 — a ws-pushed snapshot is APPLIED live, not just saved.

    The save-only test above proves the push reaches the agent and lands on
    flash; this proves the running agent then re-applies it to the enforcement
    plane (nft) WITHOUT an HTTP poll. With the poll frozen at 3600s, the only
    thing that can move nft state mid-run is the agent's apply-on-push tick
    reading the snapshot the sidecar wrote.

    Observable: a per-MAC `wh_drop:<mac>:Paused` forward-drop rule in the live
    nft ruleset. It is rendered directly from BlockRules.blocked (a paused
    profile), needs no client traffic / DNS resolve / external egress (so it
    dodges the intermittent runner→resolver flake #1935), and flips ONLY if the
    agent actually re-rendered + reloaded nft from the pushed snapshot.
    """
    # ── On-connect baseline — device assigned, NOT paused ───────────────────
    fake_api.serve_snapshot(
        _snapshot(etag=ETAG_ENFORCE_BASE, extra_blocked=["example.com"])
    )
    _restart_agent()
    fake_api.wait_for_ws_connected(timeout_s=180)

    # The one startup poll applies the base snapshot; the device must NOT carry a
    # paused-drop yet (nothing is whole-MAC blocking it).
    wait_until(
        lambda: True if _router_snapshot_etag() == ETAG_ENFORCE_BASE else None,
        timeout_s=90, interval_s=3,
        description=f"base snapshot applied (etag {ETAG_ENFORCE_BASE})",
    )
    assert not _paused_drop_rule_present(DEV_MAC), (
        "device should carry no paused-drop rule at the un-paused baseline"
    )

    # ── Push a PAUSED snapshot — poll frozen, so push is the only delivery ───
    push = fake_api.serve_snapshot(
        _snapshot(etag=ETAG_ENFORCE_PAUSED, extra_blocked=["example.com"], paused=True)
    )
    assert push == ETAG_ENFORCE_PAUSED

    # Enforcement flips from the push ALONE: the agent's apply-on-push tick reads
    # the sidecar-written snapshot and re-renders nft, installing the per-MAC
    # `wh_drop:<mac>:Paused` forward-drop. Pre-#1945 (save-only) this never
    # happens until the next poll — frozen here at 3600s — so the assertion is
    # the live-apply proof. Each poll iteration first nudges a flow so the
    # agent's conntrack-driven `on_tick` (which the apply-on-push tick rides) runs.
    def _nudged_drop_present() -> bool | None:
        _nudge_conntrack()
        return True if _paused_drop_rule_present(DEV_MAC) else None

    wait_until(
        _nudged_drop_present,
        timeout_s=180, interval_s=5,
        description="nft gains a wh_drop:<mac>:Paused forward-drop via the ws "
                    "push with the poll frozen — live apply-on-push (#1945)",
    )
    # Sanity: the same push also advanced the on-disk snapshot etag.
    assert _router_snapshot_etag() == ETAG_ENFORCE_PAUSED


def test_ws_pushed_pause_applies_on_idle_client_without_nudge(router, fake_api):
    """#2229 — a ws-pushed pause applies on an IDLE device with NO conntrack nudge.

    The live-apply test above must NUDGE conntrack every wait-iteration because
    it was written to the pre-#2229 reality: the apply-on-push tick rode the
    conntrack-driven `on_tick`, and on a phantom-device scenario (no client VM)
    nothing generates flows, so `on_tick` — and the apply — would starve without
    a synthetic GET. #2229 makes the apply EVENT-DRIVEN: the sidecar drops a
    `<etag>\\t<uptime>` trigger (WS_PENDING_PATH) the instant it persists a push,
    and the agent reads it every heartbeat tick (#2024) and applies immediately —
    so a pushed pause installs the drop with the device fully idle and NO nudge.

    This scenario proves exactly that: it NEVER calls `_nudge_conntrack`. If the
    apply still needed traffic to run, the drop would never appear and the wait
    would time out. It also asserts the new trigger file itself carries the
    pushed etag — pinning the #2229 mechanism, not just the outcome.
    """
    # ── On-connect baseline — device assigned, NOT paused ───────────────────
    fake_api.serve_snapshot(
        _snapshot(etag=ETAG_IDLE_BASE, extra_blocked=["example.com"])
    )
    _restart_agent()
    fake_api.wait_for_ws_connected(timeout_s=180)

    wait_until(
        lambda: True if _router_snapshot_etag() == ETAG_IDLE_BASE else None,
        timeout_s=90, interval_s=3,
        description=f"idle base snapshot applied (etag {ETAG_IDLE_BASE})",
    )
    assert not _paused_drop_rule_present(DEV_MAC), (
        "device should carry no paused-drop rule at the un-paused baseline"
    )

    # ── Push a PAUSED snapshot; the client stays IDLE (no nudge anywhere) ────
    push = fake_api.serve_snapshot(
        _snapshot(etag=ETAG_IDLE_PAUSED, extra_blocked=["example.com"], paused=True)
    )
    assert push == ETAG_IDLE_PAUSED

    # The sidecar's trigger reflects the pushed etag — the #2229 event-driven
    # signal is present (not merely the snapshot file).
    wait_until(
        lambda: True if _ws_pending_etag() == ETAG_IDLE_PAUSED else None,
        timeout_s=90, interval_s=3,
        description=f"ws-pending trigger carries the pushed etag {ETAG_IDLE_PAUSED} "
                    "(#2229 event-driven signal written on persist)",
    )

    # The drop appears with the device IDLE and NO `_nudge_conntrack` — the
    # apply is driven by the heartbeat tick reading the trigger, not by traffic.
    wait_until(
        lambda: True if _paused_drop_rule_present(DEV_MAC) else None,
        timeout_s=90, interval_s=3,
        description="nft gains wh_drop:<mac>:Paused from the ws push on an IDLE "
                    "client with NO conntrack nudge — event-driven apply (#2229)",
    )
    assert _router_snapshot_etag() == ETAG_IDLE_PAUSED


def _carve_snapshot(*, etag: str, paused: bool) -> dict:
    """A paused profile that KEEPS extraAllowed carve-outs, over a global allow.

    A soft pause (or an allowed-app carve-out that survives the pause) leaves
    non-empty `extraAllowed` on a `blocked=True` profile. Combined with the
    fleet-wide `global.extraAllowed` that every real deployment ships (the infra
    allowlist, #1307/#1308), the blocked MAC is rendered NOT via the family-
    agnostic `@blocked_macs` set but as per-family drops carrying one
    `ip daddr != @ea_<m>_<host>` clause per carve host plus `!= @global_allow`.
    That whole-MAC-drop + ea_-carve combination is the exact shape #2207 flagged
    on the failing prod device — this snapshot reproduces it on the bench.
    """
    return (
        SnapshotBuilder()
        .add_profile(
            id=PID,
            name="e2e-ws-push-carve",
            blocked=paused,
            block_reason="Paused" if paused else None,
            extra_allowed=CARVE_ALLOWED if paused else [],
        )
        .add_device(mac=DEV_MAC, name="e2e-ws-push-carve-dev", profile_id=PID)
        .set_global(
            extra_allowed=["captive.apple.com", "connectivitycheck.gstatic.com"]
        )
        .build(etag=etag)
    )


def test_ws_policy_push_applies_paused_with_extra_allowed(router, fake_api):
    """#2207 — a ws-pushed pause on a profile WITH extraAllowed carve-outs still
    renders the whole-MAC `:Paused` drop over the ws apply-on-push path.

    The plain live-apply test above pauses a profile with NO per-MAC
    `extraAllowed`, so the MAC lands in the simple drop path. #2207's failing
    prod device was a paused profile that ALSO kept `extraAllowed` carve-outs,
    which routes the whole-MAC drop through render.lua's per-family
    `blocked_ea_macs` branch (one `!= @ea_<m>_<host>` clause per carve host, plus
    `!= @global_allow`). This asserts that branch renders live from a ws push
    with the poll frozen — the previously-untested carve-out combination.

    Observable: the same `wh_drop:<mac>:Paused` comment (the drop still carries
    it even with the ea_ exceptions), so `_paused_drop_rule_present` is the
    config-independent enforcement signal here too.
    """
    # ── On-connect baseline — device assigned, carve hosts allowed, NOT paused ─
    fake_api.serve_snapshot(_carve_snapshot(etag=ETAG_CARVE_BASE, paused=False))
    _restart_agent()
    fake_api.wait_for_ws_connected(timeout_s=180)

    wait_until(
        lambda: True if _router_snapshot_etag() == ETAG_CARVE_BASE else None,
        timeout_s=90, interval_s=3,
        description=f"carve base snapshot applied (etag {ETAG_CARVE_BASE})",
    )
    assert not _paused_drop_rule_present(DEV_MAC), (
        "device should carry no paused-drop rule at the un-paused baseline"
    )

    # ── Push a PAUSED snapshot that RETAINS the extraAllowed carve-outs ───────
    push = fake_api.serve_snapshot(_carve_snapshot(etag=ETAG_CARVE_PAUSED, paused=True))
    assert push == ETAG_CARVE_PAUSED

    # The whole-MAC drop (with its ea_ carve clauses) must render live from the
    # push alone. Pre-fix, an `nft -f` that failed to load this ruleset would
    # have been swallowed (policy.apply returned true regardless) and the etag
    # advanced, so the drop would never appear and never retry — the #2207 gap.
    def _nudged_drop_present() -> bool | None:
        _nudge_conntrack()
        return True if _paused_drop_rule_present(DEV_MAC) else None

    wait_until(
        _nudged_drop_present,
        timeout_s=180, interval_s=5,
        description="nft gains a wh_drop:<mac>:Paused drop (with ea_ carve-outs) "
                    "via the ws push with the poll frozen — #2207",
    )
    assert _router_snapshot_etag() == ETAG_CARVE_PAUSED
