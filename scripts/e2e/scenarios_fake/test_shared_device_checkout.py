"""Suite L — shared device checked out / checked in, real agent (#2877, epic #2841).

A shared device that nobody has checked in ships on the wire as an inline
per-device override, `DevicePolicy(profileId = None, rules = {blocked = true,
blockReason = "CheckedOut"})` — the same shape the unmanaged-MAC path already
uses (`docs/design/shared-devices.md` §8.1). A check-in swaps that for
`profileId = <holder>`. No new wire field; the only new thing the router sees
is the `CheckedOut` reason string, which it treats as opaque (§8.2, F3).

This scenario drives both states through the real OpenWRT agent against the
Gate-2 fake API and asserts, at the connection layer:

  1. Checked out — the client's forwarded traffic is dropped by a per-MAC
     `wh_drop:<mac>:CheckedOut` rule (its counter rises and the kernel logs the
     dropped flow under that prefix), and the blocked events the agent posts
     carry `reason = CheckedOut`.
  2. A host in `global.extraAllowed` (standing in for app.wifihaven.net) is
     still reachable from the blocked client, so a child can reach WifiHaven to
     check in (§3 F2).
  3. HTTP to any other host is answered by the block page.
  4. Check-in — the fake pushes a snapshot with `profileId = <profile>` over
     the ws link; the drop goes away and traffic flows. The push→apply time is
     measured and logged against the §11 target (5 s p95). The CI assertion is
     deliberately loose: one sample on a shared KVM runner is not a p95, and a
     tight bound would only make Gate 2 flaky.
  5. The check-in leaves the dnsmasq conf byte-identical (#2900), so the apply
     skips the dnsmasq restart and smoke probe: who holds the device is nft
     state, and the conf names hosts only.
  6. Checked in and using the app, THEN checked out (#2923): the real-world
     order. The check-out apply empties @global_allow and keeps dnsmasq's
     cache, so the apply must re-seed the set from the dns-tail cache before
     the client asks again.

Reachability is a connection-layer property, never "DNS resolved ⇒ allowed"
(AGENTS.md). Every absence assertion here sits next to a positive one from the
same client in the same state, so a dead client or a dead observer cannot pass
it for free.
"""
from __future__ import annotations

import logging
import re
import time

import pytest

from lib.traffic import http_get
from lib.vm import router_nft_set, router_ssh
from lib.wait import router_snapshot_etag, wait_until

from ._observers import (
    dig_ipv4_answers,
    norm_mac,
    wait_block_page,
    wait_dns_cache_attributed,
    wait_global_allow_populated,
    wait_http_succeeds,
)
from .snapshot_builder import SnapshotBuilder, profile_rules

log = logging.getLogger(__name__)

pytestmark = pytest.mark.checked_out

HOLDER_PID = 2877
CHECKED_OUT = "CheckedOut"
# Stands in for app.wifihaven.net: reachable ONLY through global.extraAllowed.
# example.com is the suite's stable allow host (test_global_policy H1).
GLOBAL_ALLOW_HOST = "example.com"
# Any other host. Must be dropped while checked out, reachable once checked in.
OTHER_HOST = "example.org"
# A port the block-page DNAT does not touch (it rewrites only 80 and 443), so a
# flow to it reaches the forward chain and is dropped there.
FORWARD_PORT = 8080

ETAG_CHECKED_OUT = '"sha256:shared-checked-out-v1"'
ETAG_CHECKED_IN = '"sha256:shared-checked-in-v2"'
# #2923: the real-world order, checked in first and then checked out.
ETAG_WARM_CHECKED_IN = '"sha256:shared-warm-checked-in-v3"'
ETAG_WARM_CHECKED_OUT = '"sha256:shared-warm-checked-out-v4"'

# §11 design target for check-in → router apply (p95). Logged, not asserted.
DESIGN_TARGET_P95_S = 5.0
# CI bound. Generous on purpose: Gate 2 shares a KVM host across arms and CD
# pipelines, and this is one sample. A breach here means the push did not
# apply in any reasonable time, not that we missed the p95.
CI_APPLY_BOUND_S = 60.0


def _snapshot(*, etag: str, mac: str, checked_in: bool) -> dict:
    """The holder profile is always declared and permissive, so the only thing
    that changes between the two states is the device's own entry."""
    b = (
        SnapshotBuilder()
        .add_profile(id=HOLDER_PID, name="e2e-shared-holder")
        .set_global(extra_allowed=[GLOBAL_ALLOW_HOST])
    )
    if checked_in:
        b.add_device(mac=mac, name="e2e-shared-dev", profile_id=HOLDER_PID)
    else:
        # checkedOutRules (§8.1): blocked, CheckedOut, empty extraAllowed.
        b.add_device(
            mac=mac,
            name="e2e-shared-dev",
            rules=profile_rules(blocked=True, block_reason=CHECKED_OUT),
        )
    return b.build(etag=etag)


def _checked_out_drop_state(mac: str) -> bool | None:
    """Whether the live ruleset carries the whole-MAC `CheckedOut` drop.

    Returns None when the router could not be read (ssh failure, or no
    `inet wifihaven` table mid-reload), so a failed read is never mistaken for
    "the rule is gone". Callers wait on True or False explicitly.

    Keyed on the reason suffix, not on @blocked_macs: with a non-empty
    global.extraAllowed, render.lua routes a blocked MAC to per-family rules
    carrying `!= @global_allow` instead of the set, so the set can be empty
    while the device is fully blocked (see test_ws_push_apply).
    """
    res = router_ssh("nft list table inet wifihaven", check=False, timeout=10)
    out = (res.stdout or "").lower()
    if res.returncode != 0 or "table inet wifihaven" not in out:
        return None
    return f"wh_drop:{norm_mac(mac)}:{CHECKED_OUT}".lower() in out


def _checked_out_drop_packets(mac: str) -> int:
    """Sum of `counter packets` across the CheckedOut drop rules for `mac`.

    The log rules that precede each drop carry no counter, so only the drop
    rules contribute (render.lua `drop_suffix`).
    """
    needle = f"wh_drop:{norm_mac(mac)}:{CHECKED_OUT}"
    res = router_ssh("nft list chain inet wifihaven wifihaven_block", check=False, timeout=10)
    if res.returncode != 0:
        raise RuntimeError(f"nft list chain failed (rc={res.returncode}): {res.stderr!r}")
    lines = [ln for ln in (res.stdout or "").splitlines() if needle.lower() in ln.lower()]
    return sum(int(n) for ln in lines for n in re.findall(r"counter packets (\d+)", ln))


def _forward_drop_logged(mac: str, port: int) -> bool:
    """True iff the kernel log has a `wh_drop:<mac>:CheckedOut` line for a flow
    to `port`. That prefix is written only by the forward-chain log rule ahead of
    the drop (render.lua `log_suffix`), and it is what the agent's nflog reader
    turns into a blocked event, so this pins the forward-drop leg on its own.
    """
    needle = f"wh_drop:{norm_mac(mac)}:{CHECKED_OUT} "
    res = router_ssh("logread", check=False, timeout=10)
    return any(
        needle.lower() in ln.lower() and f"DPT={port} " in ln
        for ln in (res.stdout or "").splitlines()
    )


DNSMASQ_CONF = "/tmp/dnsmasq.d/wifihaven.conf"


def _dnsmasq_conf() -> str:
    res = router_ssh(f"cat {DNSMASQ_CONF}", check=False, timeout=10)
    if res.returncode != 0:
        raise RuntimeError(f"cat {DNSMASQ_CONF} failed (rc={res.returncode}): {res.stderr!r}")
    return res.stdout or ""


def test_checked_out_device_is_blocked_and_check_in_unblocks(router, client, fake_api):
    mac = client.mac

    # ── Checked out ──────────────────────────────────────────────────────────
    etag = fake_api.serve_snapshot(
        _snapshot(etag=ETAG_CHECKED_OUT, mac=mac, checked_in=False)
    )
    fake_api.wait_for_etag_served(etag=etag, timeout_s=240)
    wait_until(
        lambda: True if _checked_out_drop_state(mac) is True else None,
        timeout_s=180, interval_s=2,
        description=f"nft carries wh_drop:{mac}:{CHECKED_OUT}",
    )

    # F2: the global-allowed host is reachable from the blocked client. This is
    # also the liveness anchor for the drop checks below: it proves this client
    # has DNS, a forward path and upstream egress right now.
    probe = wait_http_succeeds(client, host=GLOBAL_ALLOW_HOST, timeout_s=120)
    assert probe.http_code is not None and 200 <= probe.http_code < 400
    assert wait_global_allow_populated(timeout_s=30), (
        "expected @global_allow to carry the allow host's resolved IPs"
    )

    # Any other host on HTTP gets the block page (port-80 DNAT).
    page = wait_block_page(client, host=OTHER_HOST, timeout_s=120)
    assert page is not None and page.http_code == 200

    # Forwarded traffic is dropped. Ports 80/443 from a blocked MAC are
    # redirected to the router's block page rather than dropped, so drive a
    # port that is neither. The rule is whole-MAC, so its counter rising shows
    # it is dropping this client's traffic; the kernel-log line below ties the
    # drop to this 8080 flow. The allow-host probe above is the liveness anchor.
    other_v4 = dig_ipv4_answers(client, OTHER_HOST)
    assert other_v4, f"no A records for {OTHER_HOST}; cannot drive a forwarded flow"
    before = _checked_out_drop_packets(mac)
    fwd = http_get(client, f"http://{OTHER_HOST}:{FORWARD_PORT}/", timeout_s=3, ipv4_only=True)
    assert fwd.http_code in (None, 0), (
        f"{OTHER_HOST}:{FORWARD_PORT} answered (http_code={fwd.http_code}) from a "
        "checked-out device"
    )
    wait_until(
        lambda: True if _checked_out_drop_packets(mac) > before else None,
        timeout_s=30, interval_s=2,
        description=f"wh_drop:{mac}:{CHECKED_OUT} counter rises past {before}",
    )
    wait_until(
        lambda: True if _forward_drop_logged(mac, FORWARD_PORT) else None,
        timeout_s=30, interval_s=2,
        description=f"kernel log has wh_drop:{mac}:{CHECKED_OUT} for DPT={FORWARD_PORT}",
    )

    # The blocked-flow events the agent posts carry the snapshot's reason. Two
    # producers label a per-MAC block with blocked_reason[mac], nflog for the
    # forward drop and conntrack for the block-page redirect, and events carry
    # no port, so this accepts either; the kernel-log check above is what pins
    # the forward drop. Filtering on the other host's IPs keeps the carved-out
    # allow-host flows out of the match.
    def _drop_event():
        http_get(client, f"http://{OTHER_HOST}:{FORWARD_PORT}/", timeout_s=3, ipv4_only=True)
        evs = fake_api.events_for_mac(mac, allowed=False, reason=CHECKED_OUT)
        return [e for e in evs if e.get("destIp") in other_v4] or None

    events = wait_until(
        _drop_event, timeout_s=120, interval_s=3,
        description=f"blocked event to {OTHER_HOST} with reason={CHECKED_OUT} for {mac}",
    )
    log.info("checked-out drop events for %s: %d (first: %r)", mac, len(events), events[0])

    # ── Check in ─────────────────────────────────────────────────────────────
    # Time the push only once the ws link is up, so the number is push→apply
    # and not reconnect time.
    fake_api.wait_for_ws_connected(timeout_s=180)
    conf_before = _dnsmasq_conf()
    # Liveness anchor for the equality check below: this is the conf the
    # checked-out apply rendered, not an empty or missing file.
    assert f"nftset=/{GLOBAL_ALLOW_HOST}/" in conf_before, conf_before[:500]
    pushed_at = time.monotonic()
    etag = fake_api.serve_snapshot(
        _snapshot(etag=ETAG_CHECKED_IN, mac=mac, checked_in=True)
    )
    assert etag == ETAG_CHECKED_IN

    # Tight poll so the measurement is not dominated by the poll interval. Each
    # sample is one ssh round trip, so the figure is an upper bound on apply.
    # Unreadable samples (None) are skipped, never counted as "rule gone".
    applied_at: float | None = None
    unreadable = 0
    deadline = pushed_at + CI_APPLY_BOUND_S
    while time.monotonic() < deadline:
        state = _checked_out_drop_state(mac)
        if state is False:
            applied_at = time.monotonic()
            break
        if state is None:
            unreadable += 1
        time.sleep(0.25)

    delivered = fake_api.wait_for_etag_served(etag=ETAG_CHECKED_IN, timeout_s=10)
    assert applied_at is not None, (
        f"the CheckedOut drop was still in nft {CI_APPLY_BOUND_S:.0f}s after the "
        f"check-in push ({unreadable} unreadable samples; delivery record: {delivered!r})"
    )
    latency = applied_at - pushed_at
    log.info(
        "check-in push→apply: %.2fs (design target %.1fs p95, CI bound %.0fs, "
        "transport=%s, %d unreadable samples; one sample, upper bound incl. ssh poll)",
        latency, DESIGN_TARGET_P95_S, CI_APPLY_BOUND_S, delivered.get("transport"), unreadable,
    )
    if latency > DESIGN_TARGET_P95_S:
        log.warning(
            "check-in push→apply %.2fs exceeded the %.1fs p95 design target "
            "(single sample on a shared runner; not failing CI on it)",
            latency, DESIGN_TARGET_P95_S,
        )

    # #2900: policy.apply writes the conf before `nft -f`, so once the drop is
    # gone the check-in's conf is on disk. Identical bytes mean the apply took
    # the no-restart branch (dnsmasq restarts only when the conf changed).
    conf_after = _dnsmasq_conf()
    assert conf_after == conf_before, (
        "check-in changed the dnsmasq conf, so the apply restarted dnsmasq:\n"
        f"before:\n{conf_before}\nafter:\n{conf_after}"
    )

    # The drop rule going away is an absence; this is the positive proof that
    # the same host blocked a moment ago now reaches its real upstream.
    ok = wait_http_succeeds(client, host=OTHER_HOST, timeout_s=120)
    assert ok.http_code is not None and 200 <= ok.http_code < 400


def _set_ips(name: str) -> set[str]:
    """Addresses in an nft set. Elements of a timeout set print as
    `<ip> expires <t>`, so keep only the address token."""
    return {e.split()[0] for e in router_nft_set(name)}


def test_check_out_after_use_keeps_global_allow_reachable(router, client, fake_api):
    """#2923: the order a real device goes through, which the test above skips.

    The device is checked in and uses WifiHaven, so dnsmasq's cache and the
    dns-tail cache both hold the allow host's answers. Then it is checked out.
    The check-out apply's `nft -f` recreates the table, emptying @global_allow,
    and leaves the dnsmasq conf unchanged (#2900), so dnsmasq isn't restarted and
    keeps answering from cache, which never runs its nftset= callback. Before the
    fix nothing refilled @global_allow and the device could not reach WifiHaven to
    check in. The apply now seeds it from the dns-tail cache, so the allow host's
    address must be in the set before the client sends a single new query.
    """
    mac = client.mac

    # ── Checked in, using the allow host ─────────────────────────────────────
    etag = fake_api.serve_snapshot(
        _snapshot(etag=ETAG_WARM_CHECKED_IN, mac=mac, checked_in=True)
    )
    fake_api.wait_for_etag_served(etag=etag, timeout_s=240)
    # Apply barrier: "no CheckedOut drop" and the conf both already hold from the
    # previous test, so neither shows this snapshot landed; the router's on-disk
    # etag does. The apply may restart dnsmasq, so the lookup below retries.
    wait_until(
        lambda: True if router_snapshot_etag() == ETAG_WARM_CHECKED_IN else None,
        timeout_s=180, interval_s=2,
        description=f"checked-in snapshot applied (etag {ETAG_WARM_CHECKED_IN})",
    )
    assert _checked_out_drop_state(mac) is False, f"{mac} is dropped while checked in"
    allow_v4 = wait_until(
        lambda: dig_ipv4_answers(client, GLOBAL_ALLOW_HOST) or None,
        timeout_s=60, interval_s=2,
        description=f"A records for {GLOBAL_ALLOW_HOST} after the checked-in apply",
    )
    wait_http_succeeds(client, host=GLOBAL_ALLOW_HOST, timeout_s=120, ipv4_only=True)
    # The backfill reads the dns-tail cache, so it must hold the answer first.
    wait_dns_cache_attributed(allow_v4[0], GLOBAL_ALLOW_HOST, timeout_s=120)
    conf_before = _dnsmasq_conf()
    assert f"nftset=/{GLOBAL_ALLOW_HOST}/" in conf_before, conf_before[:500]

    # ── Check out ────────────────────────────────────────────────────────────
    etag = fake_api.serve_snapshot(
        _snapshot(etag=ETAG_WARM_CHECKED_OUT, mac=mac, checked_in=False)
    )
    fake_api.wait_for_etag_served(etag=etag, timeout_s=240)
    wait_until(
        lambda: True if _checked_out_drop_state(mac) is True else None,
        timeout_s=180, interval_s=2,
        description=f"nft carries wh_drop:{mac}:{CHECKED_OUT}",
    )
    # The real-world condition: the conf didn't change, so dnsmasq kept its cache.
    assert _dnsmasq_conf() == conf_before, "check-out changed the dnsmasq conf"

    # No client query since the apply: only the apply-time backfill can have put
    # the address back. Waits briefly because the backfill runs just after the
    # `nft -f` that installed the drop.
    seeded = wait_until(
        lambda: (_set_ips("global_allow") & set(allow_v4)) or None,
        timeout_s=15, interval_s=1,
        description=f"@global_allow re-seeded with {GLOBAL_ALLOW_HOST} {allow_v4} after check-out",
    )
    log.info("global_allow re-seeded after check-out: %s", sorted(seeded))

    # Both halves from the same client in the same state: the allow host still
    # connects (dnsmasq answers it from cache), and any other host is dropped.
    probe = wait_http_succeeds(client, host=GLOBAL_ALLOW_HOST, timeout_s=60, ipv4_only=True)
    assert probe.http_code is not None and 200 <= probe.http_code < 400
    before = _checked_out_drop_packets(mac)
    fwd = http_get(client, f"http://{OTHER_HOST}:{FORWARD_PORT}/", timeout_s=3, ipv4_only=True)
    assert fwd.http_code in (None, 0), (
        f"{OTHER_HOST}:{FORWARD_PORT} answered (http_code={fwd.http_code}) from a "
        "checked-out device"
    )
    wait_until(
        lambda: True if _checked_out_drop_packets(mac) > before else None,
        timeout_s=30, interval_s=2,
        description=f"wh_drop:{mac}:{CHECKED_OUT} counter rises past {before}",
    )
    assert not (_set_ips("global_allow") & set(dig_ipv4_answers(client, OTHER_HOST))), (
        f"{OTHER_HOST}'s address landed in @global_allow"
    )
