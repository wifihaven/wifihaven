"""Suite J — network-wide blockEncryptedDns enforcement on a real OpenWRT agent
(#1909 / #1911; epic #1903).

VM-level end-to-end proof that a deployed agent enforces the snapshot's
top-level `blockEncryptedDns` toggle. The busted unit coverage
(`openwrt/test/render_spec.lua`, `encrypted_dns_spec.lua`, `nft_drops_spec.lua`)
proves the *render*; this proves the *enforced behaviour against live traffic +
real router state*.

Two halves under test:

  1. **DNS half (NEGATIVE answer, observable via `dig`).** With the flag on, the
     curated relay/DoH hostnames (`mask.icloud.com`, `dns.google`, …) resolve to
     a NEGATIVE answer (NXDOMAIN via dnsmasq `local=/host/`) — Apple's clean
     iCloud-Private-Relay disable signal — while a normal host (`example.com`)
     keeps resolving through the LAN resolver. This is the *one* sanctioned
     Truth-#1 exception: a negative answer here is bypass-disable signalling,
     not a content sinkhole (no `0.0.0.0`).

  2. **Connection-layer half (nftables forward-drop, router-state proof).** With
     the flag on, the forward chain drops the curated public-resolver IPs (the
     static, agent-baked `encrypted_dns_resolvers` set) and DoT (TCP/853). We
     assert the set is populated and the drop rules are live — the deterministic
     router-state observable the rest of this suite uses for enforcement (a raw
     "curl 1.1.1.1 times out" probe can't distinguish a drop from a no-route).

Reachability/blocking here is a CONNECTION-LAYER property; a successful DNS
lookup proves nothing (DNS always resolves; the resolved IP is what nftables
drops). See `docs/architecture.md` §0.1 and the AGENTS.md anti-pattern callout.
"""
from __future__ import annotations

import pytest

from ._observers import (
    dig_ipv4_answers,
    resolver_ip_drop_rule_present,
    wait_dot_drop_rule_present,
    wait_encrypted_dns_resolvers_populated,
)
from .snapshot_builder import SnapshotBuilder
from lib.vm import router_nft_set
from lib.wait import wait_until

pytestmark = pytest.mark.encrypted_dns

PID = 800

# A curated relay/DoH host that must get a NEGATIVE answer when the flag is on.
RELAY_HOST = "mask.icloud.com"
DOH_HOST = "dns.google"
# A normal host that must keep resolving through the LAN resolver regardless.
CONTROL_HOST = "example.com"
# A curated public-resolver IP that must be in the drop set when the flag is on.
RESOLVER_IP = "1.1.1.1"


def _wait_dig_negative(client, host: str, *, timeout_s: float = 120) -> None:
    """Block until `dig <host>` returns NO IPv4 answer (NXDOMAIN/NODATA).

    `dig +short` prints nothing for a negative answer, so an empty A-answer list
    is the observable. Polled because the agent applies the dnsmasq `local=`
    directive asynchronously after the snapshot is served.
    """
    wait_until(
        lambda: True if not dig_ipv4_answers(client, host) else None,
        timeout_s=timeout_s, interval_s=3,
        description=f"dig {host} to return a NEGATIVE answer (no A record)",
    )


@pytest.mark.smoke
def test_block_encrypted_dns_negative_answer_and_drops(router, client, fake_api):
    """J1: flag on → curated relay/DoH hosts get a NEGATIVE DNS answer, a normal
    host still resolves, and the curated resolver-IP + DoT/853 forward-drops are
    live on the router."""
    snap = (
        SnapshotBuilder()
        .add_profile(id=PID, name="e2e-edns")
        .add_device(mac=client.mac, name="e2e-edns-dev", profile_id=PID)
        .set_block_encrypted_dns(True)
        .build(etag='"sha256:block-encrypted-dns-on-v1"')
    )
    etag = fake_api.serve_snapshot(snap)
    fake_api.wait_for_etag_served(etag=etag, timeout_s=240)

    # Connection-layer half applied: the static curated v4 resolver-IP set is
    # populated (proves the flag-on ruleset is live) and the drop rules exist.
    members = wait_encrypted_dns_resolvers_populated(timeout_s=180)
    assert any(RESOLVER_IP in m for m in members), (
        f"expected curated resolver IP {RESOLVER_IP} in @encrypted_dns_resolvers; "
        f"got {members!r}"
    )
    wait_dot_drop_rule_present(timeout_s=60)
    assert resolver_ip_drop_rule_present(), (
        "expected a forward-chain drop rule on @encrypted_dns_resolvers"
    )

    # DNS half: the curated relay/DoH hosts get a NEGATIVE answer (NXDOMAIN),
    # NOT a 0.0.0.0 sinkhole — Apple's clean Private-Relay disable signal.
    _wait_dig_negative(client, RELAY_HOST, timeout_s=120)
    _wait_dig_negative(client, DOH_HOST, timeout_s=60)
    assert RELAY_HOST not in dig_ipv4_answers(client, RELAY_HOST)
    # No sinkhole: a negative answer means zero A records, so 0.0.0.0 must never
    # appear (a positive 0.0.0.0 would NOT disable Private Relay).
    assert "0.0.0.0" not in dig_ipv4_answers(client, RELAY_HOST)

    # The LAN resolver still works for everything else — the toggle is scoped to
    # the curated list, it does not break normal DNS.
    control = wait_until(
        lambda: dig_ipv4_answers(client, CONTROL_HOST) or None,
        timeout_s=60, interval_s=3,
        description=f"normal DNS for {CONTROL_HOST} still resolves via the LAN resolver",
    )
    assert control, f"expected {CONTROL_HOST} to resolve through the LAN resolver"


def test_block_encrypted_dns_off_is_a_noop(router, client, fake_api):
    """J2 (control): flag absent → NO encrypted_dns set/rules, and the curated
    relay/DoH hosts resolve normally through the LAN resolver. Proves J1's
    behaviour is driven by the flag, not by the host list alone."""
    snap = (
        SnapshotBuilder()
        .add_profile(id=PID, name="e2e-edns-off")
        .add_device(mac=client.mac, name="e2e-edns-off-dev", profile_id=PID)
        # NOTE: set_block_encrypted_dns NOT called → top-level key omitted.
        .build(etag='"sha256:block-encrypted-dns-off-v1"')
    )
    etag = fake_api.serve_snapshot(snap)
    fake_api.wait_for_etag_served(etag=etag, timeout_s=240)

    # The static resolver-IP set must be ABSENT (router_nft_set → [] for an
    # absent set; the set is never empty-but-present, so [] ⇒ no flag).
    def _set_gone():
        return True if not router_nft_set("encrypted_dns_resolvers") else None

    wait_until(
        _set_gone, timeout_s=180, interval_s=3,
        description="encrypted_dns_resolvers set absent when the flag is off",
    )
    assert not resolver_ip_drop_rule_present(), (
        "no resolver-IP drop rule should exist when blockEncryptedDns is off"
    )

    # And the curated relay host resolves normally (a real answer, NOT NXDOMAIN).
    answers = wait_until(
        lambda: dig_ipv4_answers(client, CONTROL_HOST) or None,
        timeout_s=60, interval_s=3,
        description=f"{CONTROL_HOST} resolves normally with the flag off",
    )
    assert answers
