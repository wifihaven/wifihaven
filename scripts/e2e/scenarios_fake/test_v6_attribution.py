"""Gate 2 v6 hostname attribution (#1677, CD-tier follow-up to PR #1673).

PR #1673 closed #1668 — `dns_log.parse_reply_line` was dropping every AAAA
answer, so v6 destinations always landed in `connection_events` as a bare v6
literal (`host.type=ipv6`) instead of the FQDN. #1673 pinned the parser fix
with busted integration tests in `openwrt/test/conntrack_spec.lua` that wire
a real `dns_log` cache through `handle_flow`. This file is the VM-level
proof: actual dnsmasq emitting actual AAAA log lines, actual `dns-tail`
parsing them, actual `conntrack -E NEW` firing on a real v6 SYN, actual
event POST to the fake API.

Pipeline under test:

    dnsmasq AAAA log → dns_log.ingest_line → cache.lookup → handle_flow
                                                              ↓
                                                  host.type=fqdn (NOT ipv6)

Fixture chain (Cloudflare wifihaven.net zone, real records managed by
infra/cloudflare/main.tf):

    e2e-edge.wifihaven.net   A     → 192.0.2.10   (RFC 5737 TEST-NET-1)
    e2e-edge.wifihaven.net   AAAA  → 2001:db8::10 (RFC 3849 doc-space, #1677)

The dual-stack record models the realistic shape of any production host —
DNS returns both families on a single name, and the dns_log path must
attribute v6 destinations the same way it attributes v4. The AAAA target
is RFC 3849 doc-space (`2001:db8::/32`, reserved for documentation) so it
is GUARANTEED never globally routed; the conntrack NEW event fires on the
LAN-side SYN traversal, which is what attribution needs — the WAN-side
drop downstream is irrelevant.

TERRAFORM PREREQUISITE: infra/cloudflare/main.tf must be applied so the
AAAA on e2e-edge.wifihaven.net resolves. CI applies on merge to main
(master-cloudflare.yml; #1357/#1358). Until propagation completes after
this PR merges, `dig AAAA` returns no answers and the scenario fails at
the resolution step with a pointer to the terraform-apply expectation —
matching the v4 #1351 pattern.
"""
from __future__ import annotations

import re

import pytest

from ._observers import (
    dig_ipv6_answers,
    wait_event_with_host_attribution,
)
from .snapshot_builder import SnapshotBuilder
from lib.vm import client_exec
from lib.wait import wait_until

pytestmark = pytest.mark.v6_attribution

# ── Fixture (Cloudflare wifihaven.net zone) ──────────────────────────────────
#
# Same leaf as the v4 G3 attribution test (test_cname_direct_requery.py) —
# we reuse `e2e-edge.wifihaven.net` and add an AAAA record alongside its
# existing A. One dual-stack name covers both families realistically.

LEAF_HOST = "e2e-edge.wifihaven.net"
LEAF_IP6 = "2001:db8::10"  # RFC 3849 documentation address

KIDS_PID = 700

# ── Helpers ─────────────────────────────────────────────────────────────────


def _wait_for_aaaa_resolution(client, *, timeout_s: float = 120) -> list[str]:
    """Poll until LEAF_HOST resolves to at least one IPv6 answer.

    If the Terraform AAAA record hasn't propagated yet (TTL or just-applied)
    this guard surfaces the root cause (empty AAAA answer) rather than
    letting the test hang at the SYN step.
    """
    return wait_until(
        lambda: dig_ipv6_answers(client, LEAF_HOST) or None,
        timeout_s=timeout_s,
        interval_s=5,
        description=(
            f"dig AAAA {LEAF_HOST} to return at least one IPv6 answer "
            f"(requires terraform apply for infra/cloudflare, #1677)"
        ),
    )


def _fire_v6_syn(client) -> None:
    """Emit a TCP SYN from the client to LEAF_HOST over IPv6.

    The destination is RFC 3849 doc-space so the SYN will RST or timeout on
    the WAN side — that does not matter. The conntrack NEW event on the
    router fires as the packet crosses the FORWARD chain, which is what
    `handle_flow` consumes to emit the connection_event. `nc -6 -w 2` does
    a SYN-then-bail with a 2-second timeout, returning immediately. Tail
    `|| true` so a non-zero exit (the expected timeout) doesn't fail the
    client_exec call.
    """
    client_exec(
        client,
        ["sh", "-c", f"nc -6 -w 2 {LEAF_HOST} 80 >/dev/null 2>&1; true"],
        check=False,
        timeout=10,
    )


# ── Attribution test ─────────────────────────────────────────────────────────


def test_v6_destination_attributes_to_fqdn_not_bare_literal(router, client, fake_api):
    """A v6 SYN to a dual-stack wifihaven.net host must produce a
    connection_event with host.type=fqdn and host.value=<the hostname>, NOT
    host.type=ipv6 with the raw literal.

    Pre-#1673 regression: dns_log.parse_reply_line returned nil on every
    AAAA answer, so the cache never learned the AAAA → hostname mapping.
    `handle_flow` then fell back to the raw v6 destination as a literal and
    posted `host: { type: 'ipv6', value: '2001:db8::10' }` instead of the
    FQDN.

    Post-#1673 dns_log accepts both v4 and v6 answers and populates the same
    cache for both families. The attribution path is otherwise unchanged.

    The probe sequence mirrors the v4 G3 attribution test minimally:

      1. dig AAAA LEAF_HOST  → dnsmasq logs `reply LEAF_HOST is 2001:db8::10`,
                                dns-tail ingests, cache learns the mapping.
      2. nc -6 LEAF_HOST 80  → kernel sends a v6 SYN via the router's v6
                                default route (set up by #1680). Router
                                FORWARD chain fires conntrack NEW, handle_flow
                                resolves 2001:db8::10 → LEAF_HOST via the
                                cache, posts the event with host.type=fqdn.

    Snapshot is intentionally minimal — a single device assigned to an
    unblocked profile. We are exercising the attribution surface, not
    enforcement; allowed flows produce connection_events just as blocked
    ones do (see the v4 G3 test, which uses the same shape).
    """
    snap = (
        SnapshotBuilder()
        .add_profile(id=KIDS_PID, name="e2e-v6-attr")
        .add_device(mac=client.mac, name="e2e-v6-attr-dev", profile_id=KIDS_PID)
        .build(etag='"sha256:v6-attr-v1"')
    )
    etag = fake_api.serve_snapshot(snap)
    fake_api.wait_for_etag_served(etag=etag, timeout_s=240)

    # Step 1: resolve AAAA so dnsmasq logs the reply line that populates the
    # dns_log cache. The _assert_v6_link_local_ready gate (lib/vm.py, #1680)
    # already proved the client has a v6 default route, so the dig itself
    # cannot fail for transport reasons.
    aaaa = _wait_for_aaaa_resolution(client)
    assert LEAF_IP6 in aaaa, (
        f"expected {LEAF_IP6} in AAAA answers for {LEAF_HOST}, got {aaaa!r} "
        f"— check that terraform apply has been run for infra/cloudflare/ "
        f"(#1677)"
    )

    # Step 2: emit the v6 SYN. The router's conntrack -E NEW fires; the
    # agent's handle_flow resolves the v6 destination through the cache
    # populated in step 1 and posts the connection_event.
    _fire_v6_syn(client)

    # Primary assertion: connection_event for client.mac with host.type=fqdn
    # and host.value matching LEAF_HOST. The wait_event_with_host_attribution
    # helper greps for the hostname as a substring of host.value, which is
    # sufficient — what we are guarding against is the pre-#1673 bare-literal
    # attribution, where host.value would be the v6 string and not contain
    # the hostname at all.
    ev = wait_event_with_host_attribution(
        fake_api,
        client.mac,
        branded_host=LEAF_HOST,
        timeout_s=120,
    )
    assert ev is not None, (
        f"no connection_event for {client.mac} with v6 host attribution "
        f"containing {LEAF_HOST!r} — pre-#1673 dns_log.parse_reply_line "
        f"AAAA-drop regression?"
    )

    # Sanity: the event must NOT attribute to the bare v6 literal. The
    # pre-#1673 failure mode posted host.type=ipv6 with host.value being a
    # raw v6 string; verify host.type is fqdn so a future regression that
    # leaves host.value containing the hostname AND the literal (e.g. by
    # concatenation) is caught.
    host_field = ev.get("host") or {}
    htype = (host_field.get("type") or "").lower()
    hval = host_field.get("value") or ""
    assert htype == "fqdn", (
        f"connection_event for v6 SYN attributed with host.type={htype!r} "
        f"(host.value={hval!r}) — pre-#1673 regression: dns_log dropped "
        f"AAAA, so the cache had no mapping and handle_flow fell back to "
        f"the bare v6 literal."
    )
    # Belt-and-braces: host.value is not a raw v6 literal even if type was
    # somehow fqdn. A v6-looking literal contains ≥2 colons and no dots.
    looks_like_v6 = re.fullmatch(r"[0-9a-fA-F:]+", hval) and hval.count(":") >= 2
    assert not looks_like_v6, (
        f"connection_event host.value {hval!r} looks like a bare v6 literal "
        f"despite host.type=fqdn — attribution leak"
    )
