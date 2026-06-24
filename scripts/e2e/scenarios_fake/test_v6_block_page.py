"""IPv6 block-page reachability for a whole-MAC-blocked device (#1865).

Regression for #1865 ("the block page must NEVER be blocked, for any reason").

The bug: a whole-MAC-blocked device's IPv6 HTTP was DNAT'd to ``::1``. IPv6 has
no ``route_localnet`` equivalent and ``::1`` must never appear on the wire
(RFC 4291), so the routing decision treated ``::1`` as non-local and forwarded
the packet out the WAN (observed live: ``wh_drop:…:Schedule … DST=::1:8443
OUT=eth1``) instead of delivering it to the local block-page listener — the
user saw a hung connection instead of *why* they were blocked.

The fix (render.lua + setup-uhttpd-block-page.sh):
  * the v6 block-page path DNATs via nft ``redirect`` (→ the router's br-lan v6
    address, which IS router-local and deliverable) instead of ``dnat … ::1``;
  * uhttpd binds ``[::]:8081`` / ``[::]:8443`` (wildcard) so the redirected
    packet lands on a live listener;
  * a ``fib daddr type local`` accept guard at the top of ``wifihaven_block``
    ensures the redirected (now router-local) packet is never dropped.

Why this is exercisable in the qemu harness even though SLIRP is v4-only
(cf. test_v6_link_local): the block-page redirect terminates **locally** on the
router — it never needs WAN forwarding. The client's v6 SYN to a blocked
destination is intercepted in prerouting, redirected to the router's own uhttpd,
and the block page comes back over IPv6 entirely on the LAN bridge.

The ``client`` fixture already gates v6 readiness (``_assert_v6_link_local_ready``
in lib/vm.py: a v6 default route via eth0 + a global-scope ULA), so a v6 request
from the client routes to the router.
"""
from __future__ import annotations

import pytest

from lib.traffic import http_get
from lib.vm import router_nft_set, router_ssh
from lib.wait import wait_until

from .snapshot_builder import snapshot_with_assigned_device

pytestmark = pytest.mark.block_page_v6

BLOCKED_MACS_SET = "blocked_macs"

# RFC 3849 documentation address — same target test_v6_link_local proves the
# client routes toward the router. The address itself is unreachable on the WAN
# (and SLIRP is v4-only anyway); the point is the SYN reaches the router, where
# the block-page redirect intercepts it before it can be forwarded.
V6_BLOCKED_DEST = "2001:db8::10"


def _norm(mac: str) -> str:
    return mac.lower().strip()


def _wait_mac_blocked(mac: str, *, timeout_s: float = 180) -> None:
    wait_until(
        lambda: True
        if _norm(mac) in {_norm(m) for m in router_nft_set(BLOCKED_MACS_SET)}
        else None,
        timeout_s=timeout_s,
        interval_s=2,
        description=f"{mac} present in {BLOCKED_MACS_SET}",
    )


def _v6_uhttpd_listener_ready() -> bool | None:
    """True iff uhttpd's v6 block-page listener is bound (`[::]:8081` AND
    `[::]:8443`), else None — for `wait_until`.

    #1943: the round-trip below was racing uhttpd readiness. PolicyApply (#341)
    re-runs setup-uhttpd-block-page.sh and bounces uhttpd, so there is a window
    after the snapshot lands — and even after the MAC appears in `blocked_macs`,
    which is all `_wait_mac_blocked` gates on — where the v6 wildcard listener
    isn't bound yet. The #1868 redirect rewrites the dest to a br-lan v6
    address:8081; if uhttpd hasn't (re)bound `[::]:8081` at that instant the SYN
    lands on no listener and the probe just times out — which reads as a "v6
    delivery regression" on a degraded-network window (the #1935 egress flake)
    even though render, the ruleset and the listener are all correct. Gate on
    the listener explicitly so a not-yet-bound socket is a labelled precondition
    wait, not an opaque round-trip timeout. busybox `netstat`/`ss` render a v6
    wildcard bind as `:::8081`; bindv6only=0 means that one socket also serves
    v4, so we only assert the v6 form. Verified sound on a real OpenWRT box
    (openwrt.lan, nft 1.1.6): the rendered paused ruleset's v6 `redirect` +
    never-drop guard load via `nft -c -f -`, and a `[::]` wildcard listener
    serves v6 to the router's real br-lan GUA (200) — so the delivery mechanism
    is not the bug; the readiness race is."""
    out = router_ssh(
        "ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null || true",
        check=False, timeout=15,
    ).stdout or ""
    bound = lambda port: f":::{port}" in out or f"[::]:{port}" in out
    return True if bound(8081) and bound(8443) else None


def _v6_block_page_probe(client):
    """Return the HttpProbe iff a v6 request to a blocked dest yields the block
    page (200 + marker). None otherwise — caller polls. The bracketed v6 literal
    forces curl onto IPv6."""
    p = http_get(client, f"http://[{V6_BLOCKED_DEST}]/", timeout_s=8)
    if p.http_code != 200 or not p.body:
        return None
    body_lc = p.body.lower()
    if "wifihaven" in body_lc or "blocked" in body_lc:
        return p
    return None


@pytest.mark.smoke
def test_whole_mac_block_serves_block_page_over_v6(router, client, fake_api):
    mac = client.mac

    # Whole-MAC block (paused → rules.blocked=true) for this device.
    paused = snapshot_with_assigned_device(
        etag='"sha256:fake-v6-blockpage-v1"', mac=mac, paused=True,
    )
    etag = fake_api.serve_snapshot(paused)
    fake_api.wait_for_etag_served(etag=etag, timeout_s=240)
    _wait_mac_blocked(mac, timeout_s=180)

    # The fix's shape on the live router: the v6 block-page DNAT must be a
    # `redirect` (not the broken `dnat … ::1`), and the never-drop guard must be
    # present. Pins the regression at the ruleset level before the round-trip.
    nat_chain = router_ssh(
        "nft list chain inet wifihaven wifihaven_block_nat 2>&1",
        check=False, timeout=15,
    ).stdout or ""
    # The v6 block-page path must use `redirect` (deliverable to the local
    # listener), and the old broken DNAT-to-loopback targets must be gone.
    # (Substring `redirect`/`::1:8081` rather than the full input syntax — nft's
    # list form can normalize differently, and `::1` still legitimately appears
    # in the retained `ip6 daddr != ::1` predicate, so we match on the old DNAT
    # *target* ::1:<port>, which only the removed rules carried.)
    assert "redirect" in nat_chain, (
        "v6 block-page redirect missing from wifihaven_block_nat — "
        f"chain was:\n{nat_chain}"
    )
    assert "::1:8081" not in nat_chain and "::1:8443" not in nat_chain, (
        f"broken v6 DNAT-to-::1 target still present (the #1865 bug):\n{nat_chain}"
    )
    block_chain = router_ssh(
        "nft list chain inet wifihaven wifihaven_block 2>&1",
        check=False, timeout=15,
    ).stdout or ""
    assert "fib daddr type local" in block_chain, (
        "never-drop block-page guard missing from wifihaven_block — "
        f"chain was:\n{block_chain}"
    )

    # #1943: gate on uhttpd's v6 listener readiness BEFORE the round-trip.
    # PolicyApply (#341) bounces uhttpd, so `blocked_macs` membership (above) can
    # be present while `[::]:8081`/`[::]:8443` is mid-rebind — the round-trip
    # would then race the listener and time out opaquely. Make it a labelled
    # precondition wait instead. (Delivery itself is sound — see the helper.)
    try:
        wait_until(
            _v6_uhttpd_listener_ready,
            timeout_s=120, interval_s=3,
            description="uhttpd v6 block-page listener ([::]:8081 + [::]:8443) bound",
        )
    except AssertionError:
        listeners = router_ssh(
            "ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null || true",
            check=False, timeout=15,
        ).stdout or ""
        pytest.fail(
            "uhttpd v6 block-page listener never bound for "
            f"{mac} — the redirect has no v6 listener to deliver to.\n{listeners}"
        )

    # The round-trip the bug broke: a v6 request from the whole-MAC-blocked
    # client must render the block page (delivered locally via redirect), not
    # hang. dnsmasq restarts on PolicyApply (#341) and the agent's apply lags
    # the etag flip, so poll. Timeout has headroom (#1943) so a degraded-network
    # window (cf. the #1935 egress flake) doesn't redden a sound delivery path.
    try:
        probe = wait_until(
            lambda: _v6_block_page_probe(client),
            timeout_s=180, interval_s=3,
            description=f"v6 block page for whole-MAC-blocked {mac}",
        )
    except AssertionError:
        # Full diagnostics on failure: was the SYN redirected, or did it leak
        # to FORWARD / get dropped?
        dump = router_ssh(
            "echo '=== block_nat ==='; nft list chain inet wifihaven wifihaven_block_nat 2>&1; "
            "echo '=== block ==='; nft list chain inet wifihaven wifihaven_block 2>&1 | head -30; "
            "echo '=== uhttpd listeners ==='; netstat -ltn 2>/dev/null | grep -E ':8081|:8443' || true; "
            "echo '=== ip -6 ==='; ip -6 addr show br-lan 2>&1",
            check=False, timeout=20,
        ).stdout or ""
        pytest.fail(f"v6 block page never rendered for {mac}.\n{dump}")

    assert probe.http_code == 200
    body_lc = probe.body.lower()
    assert "blocked" in body_lc or "wifihaven" in body_lc
