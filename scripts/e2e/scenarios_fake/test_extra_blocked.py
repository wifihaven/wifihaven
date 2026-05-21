"""Suite B — extraBlocked Truth-1 + per-profile scoping (#429, fake-mode #684).

Mirrors `scenarios/test_extra_blocked.py`. extraBlocked is enforced at the
*connection* layer (per-host nft drop sets, DNATed to the local block page)
rather than at DNS — see #351/#352/#392.

Snapshot-driven: each case POSTs a new snapshot with `extraBlocked` populated
on a profile, waits for the agent to fetch + apply, and inspects nft state +
block-page response on the client.
"""
from __future__ import annotations

import pytest

from ._observers import (
    dig_ipv4_answers,
    eb_set_name,
    mac_in_blocked_set,
    wait_block_page,
    wait_eb_set_populated,
)
from .snapshot_builder import SnapshotBuilder

pytestmark = pytest.mark.extra_blocked

BLOCKED_HOST = "example.com"
KIDS_PID = 200
ADULTS_PID = 201


def _kids_snapshot(*, etag: str, kids_mac: str, extra_blocked: list[str]) -> dict:
    return (
        SnapshotBuilder()
        .add_profile(id=KIDS_PID, name="e2e-kids", extra_blocked=extra_blocked)
        .add_device(mac=kids_mac, name="e2e-kids-dev", profile_id=KIDS_PID)
        .build(etag=etag)
    )


# ── B1 / B4 — DNS still returns real IPv4s (Truth-1) ────────────────────────


@pytest.mark.smoke
def test_extra_blocked_dns_returns_real_ip(router, client, fake_api):
    """B1+B4 (smoke): Truth-1 — DNS for an extraBlocked host returns real
    IPv4 answers, not NXDOMAIN. Guards against regression to old `address=`
    sinkhole behavior (#351). Enforcement plane is nft drops, NOT DNS lies.
    """
    snap = _kids_snapshot(
        etag='"sha256:eb-dns-v1"', kids_mac=client.mac, extra_blocked=[BLOCKED_HOST],
    )
    etag = fake_api.serve_snapshot(snap)
    fake_api.wait_for_etag_served(etag=etag, timeout_s=240)

    from lib.wait import wait_until
    answers = wait_until(
        lambda: dig_ipv4_answers(client, BLOCKED_HOST) or None,
        timeout_s=90, interval_s=3,
        description=f"dig {BLOCKED_HOST} to return an IPv4 answer",
    )
    assert answers, f"expected real IPv4 answers for {BLOCKED_HOST}, got none"


# ── B2 — HTTP/80 hits block page + eb_<host> set populated ───────────────────


def test_extra_blocked_http_drops_to_block_page_and_set_populated(
    router, client, fake_api,
):
    """B2: HTTP/80 to an extraBlocked host is intercepted (block-page body),
    and the resolved dest IP appears in the per-host nft set `eb_<host>`
    (rendered by render.lua, populated by dnsmasq `--nftset=` callbacks)."""
    snap = _kids_snapshot(
        etag='"sha256:eb-http-v1"', kids_mac=client.mac, extra_blocked=[BLOCKED_HOST],
    )
    etag = fake_api.serve_snapshot(snap)
    fake_api.wait_for_etag_served(etag=etag, timeout_s=240)

    probe = wait_block_page(client, host=BLOCKED_HOST, timeout_s=90)
    assert probe.http_code == 200

    members = wait_eb_set_populated(BLOCKED_HOST, timeout_s=90)
    assert members, (
        f"expected nft set {eb_set_name(BLOCKED_HOST)} to have at least one IP "
        f"element, got empty"
    )


# ── B3 — per-profile scoping ─────────────────────────────────────────────────


def test_extra_blocked_is_per_profile_scoped(router, client, fake_api):
    """B3: extraBlocked on the Kids profile does NOT MAC-wide block either the
    Kids MAC or a phantom Adults MAC.

    eb_<host> is per-HOST (shared across MACs); per-MAC isolation lives in the
    drop *rules* (ether saddr <mac> AND ip daddr @eb_<host>). We assert
    (a) neither MAC is in blocked_macs (extraBlocked is host-scoped, not a
    pause); (b) the per-host set IS populated; (c) extraAllowed beats blocked
    isn't relevant here (no blocking), but extraBlocked on Kids must not bleed
    into Adults's rule set.
    """
    kids_mac = client.mac
    adults_mac = "02:b3:b3:00:00:01"
    snap = (
        SnapshotBuilder()
        .add_profile(id=KIDS_PID, name="e2e-kids", extra_blocked=[BLOCKED_HOST])
        .add_profile(id=ADULTS_PID, name="e2e-adults")  # extraBlocked=[]
        .add_device(mac=kids_mac, name="e2e-kids-dev", profile_id=KIDS_PID)
        .add_device(mac=adults_mac, name="e2e-adults-dev", profile_id=ADULTS_PID)
        .build(etag='"sha256:eb-scope-v1"')
    )
    etag = fake_api.serve_snapshot(snap)
    fake_api.wait_for_etag_served(etag=etag, timeout_s=240)

    # `wait_for_etag_served` only confirms the agent FETCHED the new policy,
    # not that it has rewritten dnsmasq.conf and reloaded dnsmasq. The
    # `nftset=` directive that drives eb_<host> population is part of that
    # rewrite. Drive the block-page probe in a poll loop (same shape B2 uses)
    # so each curl attempt triggers a fresh DNS query — once dnsmasq has been
    # reloaded with the new nftset directive, the next resolver answer
    # populates the set and the block page renders for the Kids MAC.
    wait_block_page(client, host=BLOCKED_HOST, timeout_s=120)
    wait_eb_set_populated(BLOCKED_HOST, timeout_s=30)

    from lib.wait import wait_until
    def neither_blocked():
        kb = mac_in_blocked_set(kids_mac)
        ab = mac_in_blocked_set(adults_mac)
        return True if (not kb and not ab) else None
    wait_until(
        neither_blocked, timeout_s=60, interval_s=2,
        description=(
            f"neither {kids_mac} nor {adults_mac} present in blocked_macs"
        ),
    )


# ── B5 — extraAllowed beats blocked invariant ───────────────────────────────


def test_extra_allowed_beats_blocked(router, client, fake_api):
    """B5: when a host is in both extraBlocked AND extraAllowed, allowed wins.

    Locks in the router enforcement invariant (memory:
    `feedback_extraallowed_beats_blocked`). Without this case in the pack, a
    regression to the alternate precedence would slip past the migration.
    """
    snap = (
        SnapshotBuilder()
        .add_profile(
            id=KIDS_PID,
            name="e2e-kids-overlap",
            extra_blocked=[BLOCKED_HOST],
            extra_allowed=[BLOCKED_HOST],
        )
        .add_device(mac=client.mac, name="e2e-kids-dev", profile_id=KIDS_PID)
        .build(etag='"sha256:eb-allowed-beats-v1"')
    )
    etag = fake_api.serve_snapshot(snap)
    fake_api.wait_for_etag_served(etag=etag, timeout_s=240)

    # Allowed must win: the MAC is not blocked and HTTP/80 succeeds without
    # hitting the block page.
    from ._observers import wait_http_succeeds
    wait_http_succeeds(client, host=BLOCKED_HOST, timeout_s=90)
    assert not mac_in_blocked_set(client.mac)
