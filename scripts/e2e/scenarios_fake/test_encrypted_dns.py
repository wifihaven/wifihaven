"""Suite J — network-wide "block encrypted DNS & relays" toggle (#1911).

VM-level end-to-end proof that a deployed agent enforces the additive top-level
`snapshot.blockEncryptedDns` flag (feature #1909, epic #1903). The busted unit
coverage (`openwrt/test/encrypted_dns_spec.lua`) proves the *render shape*; this
proves the *enforced behaviour against live traffic on a real OpenWRT agent*.

When the toggle is on the agent enforces two separable halves:

  1. NODATA half (dnsmasq): a NEGATIVE DNS answer (`local=/<host>/`) for the
     curated relay/DoH hostnames — Apple's documented clean way to disable
     iCloud Private Relay (a 0.0.0.0 sinkhole does NOT disable it). This is the
     one sanctioned, narrow exception to Architectural Truth #1.
  2. Connection-layer half (nft): drop DoT (TCP/853 any IP) + DNS :53 (udp+tcp)
     to the curated public-resolver IPs ONLY — catches "system DNS hardcoded to
     8.8.8.8" and forces fallback to the LAN resolver.

The assertions that matter most (per the #1909 blast-radius + port-scope
refinements):
  - **DNS :53 to the curated resolver IP is blocked when the toggle is on** —
    the core enforcement check. We assert only the toggle-ON (blocked) state,
    not a toggle-OFF "reachable" baseline (#1935): the e2e client is nested
    behind the e2e router VM behind the household router, and once #1911 is
    enabled on that outer gateway it drops every LAN device's forwarded :53 to
    the curated resolver IPs regardless of the inner toggle — so 8.8.8.8 is not
    a reliably-reachable baseline. That the toggle took effect is confirmed
    instead by the chain loading + the NODATA half (both toggle-on-only).
  - **Opportunistic fallback survives** — after the toggle is on, a normal name
    still resolves via the LAN resolver and the network is NOT broken. This is
    the core "did we break the network" assertion.

Port-scope (the resolver-IP drop is `:53`-only, so `:443`/ICMP "am I online?"
probes to `1.1.1.1`/`8.8.8.8` survive) is proven at the render layer by the
busted unit test `openwrt/test/encrypted_dns_spec.lua`; this e2e does not dial a
public IP on :443, since that path is unreliable from the nested harness.

Reachability here is a connection-layer assertion, never "DNS resolved ⇒
allowed". See docs/architecture.md §0.1 and the AGENTS.md anti-pattern callout.
"""
from __future__ import annotations

import logging
import time

import pytest

from lib.vm import client_exec, router_ssh
from lib.wait import wait_until

from ._observers import dig_ipv4_answers
from .snapshot_builder import SnapshotBuilder

log = logging.getLogger(__name__)

pytestmark = pytest.mark.encrypted_dns

PID = 800

# A curated relay + DoH hostname that must return a NEGATIVE answer when on.
RELAY_HOST = "mask.icloud.com"
DOH_HOST = "dns.google"
# A normal host that must keep resolving via the LAN resolver (fallback proof).
NORMAL_HOST = "example.com"
# A curated public-resolver IP whose :53 must be dropped when the toggle is on.
RESOLVER_IP = "8.8.8.8"


def _wait_encrypted_dns_chain_loaded(*, timeout_s: float = 180) -> None:
    """Block until the agent has rendered + loaded the wifihaven_encrypted_dns
    nft chain — the observable that the toggle has actually taken effect."""
    def probe():
        res = router_ssh(
            "nft list table inet wifihaven 2>/dev/null || true",
            check=False, timeout=10,
        )
        return True if "wifihaven_encrypted_dns" in (res.stdout or "") else None
    wait_until(
        probe, timeout_s=timeout_s, interval_s=3,
        description="wifihaven_encrypted_dns nft chain loaded",
    )


def _dig_via(client, host: str, server: str, *, timeout_s: int = 6):
    """`dig +short @<server> <host>` from the client — used to probe whether
    DNS to a SPECIFIC upstream (e.g. 8.8.8.8) reaches it. Returns the Result."""
    return client_exec(
        client,
        ["dig", "+time=2", "+tries=1", "+short", f"@{server}", host],
        timeout=timeout_s + 5,
        check=False,
    )


def _ipv4_answers(stdout: str | None) -> list[str]:
    import re
    pat = re.compile(r"^\d+\.\d+\.\d+\.\d+$")
    return [l.strip() for l in (stdout or "").splitlines()
            if l.strip() and pat.match(l.strip())]


def _dig_until_answered(client, host: str, *, timeout_s: float) -> list[str]:
    """`dig +short <host>` until it answers; [] if it never does.

    Returns [] on timeout rather than raising, so the caller's own assertion
    fires with its diagnostic instead of a bare TimeoutError — the message is
    what tells the next reader whether the network was broken or the probe was
    early.
    """
    started = time.monotonic()
    deadline = started + timeout_s
    attempts = 0
    while True:
        attempts += 1
        answers = dig_ipv4_answers(client, host)
        if answers:
            # Log elapsed/attempts: a run that answered on the first try and one
            # that consumed most of the budget are otherwise indistinguishable in
            # the log, and the budget being eaten is the signal worth seeing.
            log.info("%s resolved after %.1fs (%d attempt(s), answer=%s)",
                     host, time.monotonic() - started, attempts, answers[0])
            return answers
        if time.monotonic() >= deadline:
            log.warning("%s never resolved in %.0fs (%d attempt(s))",
                        host, timeout_s, attempts)
            return []
        time.sleep(3)


@pytest.mark.smoke
def test_block_encrypted_dns_enforced(router, client, fake_api):
    """J1: with `blockEncryptedDns=true`, the agent (a) returns a NEGATIVE DNS
    answer for the curated relay/DoH hostnames, and (b) drops DNS :53 to the
    curated resolver IPs while a normal name still resolves via the LAN resolver.

    We assert only the toggle-ON state — that DNS to 8.8.8.8 is BLOCKED — and do
    NOT assert a toggle-OFF baseline that 8.8.8.8 is reachable (#1935). The e2e
    client sits behind the e2e router VM behind the household router; once #1911
    is enabled on that outer gateway it drops every LAN device's forwarded :53 to
    the curated resolver IPs regardless of the inner toggle, so "8.8.8.8 reachable
    with the toggle off" is not a property the harness can rely on. That the
    toggle actually took effect is confirmed by the rendered chain loading and by
    the NODATA half below (both only happen when the toggle is on). Port-scope
    (no :443/ICMP over-block) is covered by the render unit test,
    openwrt/test/encrypted_dns_spec.lua.
    """
    # ── Turn the toggle ON network-wide. ─────────────────────────────────────
    snap = (
        SnapshotBuilder()
        .add_profile(id=PID, name="e2e-j1")  # permissive profile; toggle is global
        .add_device(mac=client.mac, name="e2e-j1-dev", profile_id=PID)
        .set_block_encrypted_dns(True)
        .build(etag='"sha256:j1-block-encrypted-dns-v1"')
    )
    etag = fake_api.serve_snapshot(snap)
    fake_api.wait_for_etag_served(etag=etag, timeout_s=240)
    _wait_encrypted_dns_chain_loaded(timeout_s=180)

    # ── (a) NODATA half: the curated relay/DoH hostnames return NO IP. ────────
    # dnsmasq answers `local=/<host>/` with NODATA, so `dig +short` is empty —
    # an IP would mean Private Relay stays enabled (the #1891 hang). Poll: the
    # dnsmasq restart that picks up the new conf may lag the nft chain load.
    #
    # The poll requires BOTH halves: the relay host silent AND a normal name
    # still answering. That second clause is a liveness anchor, and without it
    # this test can pass while proving nothing at all — every assertion from here
    # to the LAN-fallback probe asserts the ABSENCE of a DNS answer, so a dnsmasq
    # that is down or mid-restart satisfies all of them vacuously. That is not
    # hypothetical: on the Gate-2 ipk arm of run 31301029999 the whole enforcement
    # phase completed in 2.9s (snapshot served 08:44:48.75, failed 08:44:51.70)
    # because every probe came back empty — the LAN-fallback dig was the only
    # thing that noticed. Anchoring liveness HERE is what keeps the absence
    # assertions meaningful, rather than leaving one late probe to catch a run
    # that was empty from the start.
    def _relay_negative():
        if dig_ipv4_answers(client, RELAY_HOST):
            return None  # relay still resolves — toggle hasn't taken effect yet
        if not dig_ipv4_answers(client, NORMAL_HOST):
            return None  # resolver isn't answering anything — nothing is proven
        return True
    wait_until(_relay_negative, timeout_s=60, interval_s=3,
               description=(f"{RELAY_HOST} to return a NEGATIVE DNS answer (no IP) "
                            f"while {NORMAL_HOST} still resolves"))
    assert not dig_ipv4_answers(client, RELAY_HOST), (
        f"{RELAY_HOST} must return a negative answer (NODATA), not an IP"
    )
    assert not dig_ipv4_answers(client, DOH_HOST), (
        f"{DOH_HOST} must return a negative answer (NODATA), not an IP"
    )

    # ── (b) :53-to-resolver-IP drop is enforced when the toggle is on. ────────
    # DNS to 8.8.8.8 is dropped (no answer). This asserts the BLOCKED state only
    # — see the docstring on why we don't pair it with a toggle-OFF "reachable"
    # baseline. Asserting absence of an answer is robust: a timeout from the
    # #1911 drop is exactly what we want, and nothing in the environment can make
    # this spuriously pass.
    blocked = _dig_via(client, NORMAL_HOST, RESOLVER_IP)
    assert not _ipv4_answers(blocked.stdout), (
        "DNS (:53) to 8.8.8.8 must be dropped when the toggle is on "
        f"(unexpectedly got {_ipv4_answers(blocked.stdout)!r})"
    )
    # …but the SAME name still resolves via the LAN resolver. THE core "did we
    # break the network" assertion — opportunistic DoH/hardcoded-DNS clients
    # fall back to the router's resolver and stay online.
    # Polled, not single-shot — the sibling poll above says why: "the dnsmasq
    # restart that picks up the new conf may lag the nft chain load". A restart
    # gap, or a cold cache meeting a momentary upstream blip on the shared CD
    # host (#1935/#2034), makes ONE dig come back empty on a network that is fine,
    # and that emptiness reddened Master Router CD.
    #
    # Polling is safe here for two reasons, and the second is the load-bearing
    # one. First, "the toggle broke the network" is a STANDING condition: a
    # genuinely broken LAN resolver stays broken for the whole window, every retry
    # comes back empty, and the assertion below still fires with its diagnostic.
    # Second — and this is what makes the poll an improvement rather than a
    # loosening — the liveness clause added to the (a) poll above means the run
    # cannot have reached this line without the resolver having answered at least
    # once WHILE the toggle was on. Before that clause, this dig was the only
    # thing standing between an all-empty run and a green pass; it no longer
    # carries that alone, which is why absorbing a transient here costs nothing.
    lan = _dig_until_answered(client, NORMAL_HOST, timeout_s=60)
    assert lan, (
        f"{NORMAL_HOST} must still resolve via the LAN resolver after the toggle "
        "is on (opportunistic fallback — network must NOT be broken)"
    )
