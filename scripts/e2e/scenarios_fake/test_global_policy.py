"""Suite H — global policy layer enforcement on a real OpenWRT agent (#1460).

VM-level end-to-end proof that a deployed agent actually enforces the
snapshot's fleet-wide `global` BlockRules (#1318) via the `@global_allow` /
`@global_block` nftables sets (#1319). The feature/unit coverage (#1322,
`api/test/**`, `openwrt/test/**`) proves the *shape* and the *render*; this
proves the *enforced behaviour against live traffic*.

This is the **gate for #1321 / PR #1459**. That PR removes the #1307
per-profile infra-allow copy and relies entirely on `global.extraAllowed`
(`@global_allow`) to keep connectivity-check / OCSP / PKI / captive-portal
hosts reachable under a whole-MAC block. If the live `@global_allow` path has
any gap, retiring the per-profile safety net would silently break infra
reachability fleet-wide. A router/agent version that passes this suite must be
deployed before #1459 un-drafts.

Reachability here is a **connection-layer** assertion (nftables forward-drop /
block-page DNAT), never "DNS resolved ⇒ allowed". A successful DNS lookup
proves nothing — DNS always resolves; the resolved IP is exactly what the
forward-drop acts on. See `docs/architecture.md` and the AGENTS.md anti-pattern
callout.

Precedence ladder under test (`docs/design/global-policy-layer.md` §5.2):
    1. global.extraAllowed  — carves out EVERY drop, all MACs (top)
    2. global block         — beaten only by (1); a per-profile allow can't
    3. per-MAC extraAllowed — carves out per-MAC drops only (#421)
    4. per-MAC blocks
"""
from __future__ import annotations

import socket

import pytest

from ._observers import (
    ea_set_exists_for_mac,
    mac_drop_rule_present,
    wait_block_page,
    wait_global_allow_populated,
    wait_global_block_populated,
    wait_http_succeeds,
    wait_mac_drop_rule_present,
)
from .snapshot_builder import SnapshotBuilder

pytestmark = pytest.mark.global_policy

# A host that is ONLY ever reachable via @global_allow in these scenarios —
# never in any profile's own extraAllowed. example.com has stable real DNS +
# routable IPs and is the allowed-browsing host the rest of the suite uses.
GLOBAL_ALLOW_HOST = "example.com"
# A host the fleet-wide block acts on. example.org (used by test_03) — distinct
# from the allow host so a single snapshot can allow one and block the other.
GLOBAL_BLOCK_HOST = "example.org"

KIDS_PID = 700
ADULTS_PID = 701


def _alloc_free_port() -> int:
    """Kernel-assigned free TCP port for a second client VM's SSH hostfwd.

    Mirrors conftest.alloc_free_port — the default `client` fixture pins
    CLIENT_SSH_PORT_DEFAULT, so a concurrently-booted second client needs its
    own port.
    """
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


# ── H1 — global.extraAllowed beats a whole-MAC block (direct #1321 guard) ────


@pytest.mark.smoke
def test_global_allow_beats_whole_mac_block(router, client, fake_api):
    """H1: a paused (blocked=true) MAC still reaches a host that is ONLY in
    `global.extraAllowed` — not in its profile's own extraAllowed.

    This is the exact connectivity #1459 leans on: under a whole-MAC block,
    `@global_allow` must carve the host's resolved IPs out of the
    `@blocked_macs` drop. If it doesn't, retiring the per-profile infra copy
    breaks infra reachability fleet-wide.
    """
    snap = (
        SnapshotBuilder()
        .add_profile(id=KIDS_PID, name="e2e-h1-paused", blocked=True, block_reason="Paused")
        .add_device(mac=client.mac, name="e2e-h1-dev", profile_id=KIDS_PID)
        .set_global(extra_allowed=[GLOBAL_ALLOW_HOST])
        .build(etag='"sha256:h1-global-allow-beats-block-v1"')
    )
    etag = fake_api.serve_snapshot(snap)
    fake_api.wait_for_etag_served(etag=etag, timeout_s=240)

    # The MAC is genuinely blocked: render.lua pulls a blocked-with-carve-out
    # MAC out of @blocked_macs into per-MAC forward-chain drop rules tagged
    # `wh_drop:<mac>` (#421/#1319). Asserting the drop rule exists proves the
    # reachability below is the @global_allow carve-out at work, not an absent
    # block.
    wait_mac_drop_rule_present(client.mac, timeout_s=180)

    # Connection-layer reachability: HTTP/80 to the global-allowed host returns
    # a real upstream response, NOT the block page. Each probe re-queries DNS,
    # which fires the dnsmasq nftset= callback that populates @global_allow.
    probe = wait_http_succeeds(client, host=GLOBAL_ALLOW_HOST, timeout_s=120)
    assert probe.http_code is not None and 200 <= probe.http_code < 400

    # Router-state corroboration: the fleet-wide @global_allow set holds the
    # resolved IP, and the host did NOT get a per-MAC ea_ set (it lives only in
    # the global layer for this MAC).
    members = wait_global_allow_populated(timeout_s=30)
    assert members, "expected @global_allow to carry >=1 resolved IP"
    assert not ea_set_exists_for_mac(client.mac), (
        "global-only allow host must not produce a per-MAC ea_ set; the carve-out "
        "is the fleet-wide @global_allow, not a per-(MAC, host) allow"
    )


# ── H2 — global.extraAllowed is flat across the fleet (every MAC) ────────────


def test_global_allow_applies_to_every_mac(router, client, client_factory, fake_api):
    """H2: the same global-allowed host is reachable from a SECOND,
    differently-profiled, also-blocked MAC — proving `@global_allow` is one
    fleet-wide set, not silently keyed per-(MAC).

    Both devices are on distinct, paused profiles with empty per-profile
    extraAllowed; only `global.extraAllowed` keeps the host reachable. If the
    carve-out were per-MAC, MAC-B (never individually allowed) would hit the
    block page.
    """
    kids = client  # MAC-A, Kids profile (paused)
    adults = client_factory(
        mac="02:e2:fa:70:00:02", name="client2", ssh_port=_alloc_free_port(),
    )

    snap = (
        SnapshotBuilder()
        .add_profile(id=KIDS_PID, name="e2e-h2-kids", blocked=True, block_reason="Paused")
        .add_profile(id=ADULTS_PID, name="e2e-h2-adults", blocked=True, block_reason="Paused")
        .add_device(mac=kids.mac, name="e2e-h2-kids-dev", profile_id=KIDS_PID)
        .add_device(mac=adults.mac, name="e2e-h2-adults-dev", profile_id=ADULTS_PID)
        .set_global(extra_allowed=[GLOBAL_ALLOW_HOST])
        .build(etag='"sha256:h2-global-allow-every-mac-v1"')
    )
    etag = fake_api.serve_snapshot(snap)
    fake_api.wait_for_etag_served(etag=etag, timeout_s=240)

    # Both MACs are genuinely blocked.
    wait_mac_drop_rule_present(kids.mac, timeout_s=180)
    wait_mac_drop_rule_present(adults.mac, timeout_s=180)

    # The global-allowed host is reachable from BOTH differently-profiled MACs.
    p_kids = wait_http_succeeds(kids, host=GLOBAL_ALLOW_HOST, timeout_s=120)
    assert p_kids.http_code is not None and 200 <= p_kids.http_code < 400
    p_adults = wait_http_succeeds(adults, host=GLOBAL_ALLOW_HOST, timeout_s=120)
    assert p_adults.http_code is not None and 200 <= p_adults.http_code < 400

    # Neither MAC got a per-MAC ea_ set — the carve-out is the single shared
    # @global_allow, applied flat across the fleet.
    assert not ea_set_exists_for_mac(kids.mac)
    assert not ea_set_exists_for_mac(adults.mac)


# ── H3 — global.extraBlocked blocks fleet-wide, per-profile allow can't save ─


def test_global_block_not_unblockable_by_profile_allow(router, client, fake_api):
    """H3: a host in `global.extraBlocked` is blocked even when the MAC's
    profile lists it in `extraAllowed`. Only `global.extraAllowed` carves out a
    global block — a per-profile allow does NOT (precedence ladder §5.2, step
    2 beats step 3).

    Guards the asymmetry that makes a global block *mandatory*: an operator's
    fleet-wide block must not be loosenable per-profile.
    """
    snap = (
        SnapshotBuilder()
        # The profile TRIES to allow the host — this must not win.
        .add_profile(
            id=KIDS_PID,
            name="e2e-h3-kids",
            extra_allowed=[GLOBAL_BLOCK_HOST],
        )
        .add_device(mac=client.mac, name="e2e-h3-dev", profile_id=KIDS_PID)
        .set_global(extra_blocked=[GLOBAL_BLOCK_HOST])
        .build(etag='"sha256:h3-global-block-beats-profile-allow-v1"')
    )
    etag = fake_api.serve_snapshot(snap)
    fake_api.wait_for_etag_served(etag=etag, timeout_s=240)

    # Despite the per-profile extraAllowed, HTTP/80 to the globally-blocked host
    # hits the block page (global block wins). Each probe re-queries DNS, firing
    # the nftset= callback that populates @global_block.
    probe = wait_block_page(client, host=GLOBAL_BLOCK_HOST, timeout_s=120)
    assert probe is not None and probe.http_code == 200

    members = wait_global_block_populated(timeout_s=30)
    assert members, "expected @global_block to carry >=1 resolved IP"

    # Control: the MAC is NOT wholesale blocked — a host that is neither
    # globally blocked nor per-profile blocked stays reachable. Confirms H3's
    # block is the targeted @global_block, not a stray whole-MAC drop.
    assert not mac_drop_rule_present(client.mac)
    p_ok = wait_http_succeeds(client, host=GLOBAL_ALLOW_HOST, timeout_s=120)
    assert p_ok.http_code is not None and 200 <= p_ok.http_code < 400


# ── H4 (stretch) — global.blocked lockdown, global.extraAllowed survives ─────


def test_global_blocked_lockdown_with_allow_carveout(router, client, fake_api):
    """H4: `global.blocked = true` is a whole-network kill switch — every MAC
    drops all forwarded traffic — EXCEPT hosts in `global.extraAllowed`.

    Asserts both directions of the lockdown: a non-allowed host hits the block
    page (DNAT), while the global-allowed host still reaches its real upstream.
    The profile is otherwise permissive, so the only thing blocking traffic is
    the global lockdown.
    """
    snap = (
        SnapshotBuilder()
        .add_profile(id=KIDS_PID, name="e2e-h4-open")  # not blocked, no extras
        .add_device(mac=client.mac, name="e2e-h4-dev", profile_id=KIDS_PID)
        .set_global(
            blocked=True,
            block_reason="Manual",  # cosmetic — drives block-page copy only
            extra_allowed=[GLOBAL_ALLOW_HOST],
        )
        .build(etag='"sha256:h4-global-lockdown-v1"')
    )
    etag = fake_api.serve_snapshot(snap)
    fake_api.wait_for_etag_served(etag=etag, timeout_s=240)

    # Lockdown drops every MAC; render.lua tags the per-MAC drop wh_drop:<mac>
    # (the global-allow carve-out forces the per-family rule path).
    wait_mac_drop_rule_present(client.mac, timeout_s=180)

    # A non-allowed host is dropped → DNATed to the local block page.
    probe = wait_block_page(client, host=GLOBAL_BLOCK_HOST, timeout_s=120)
    assert probe is not None and probe.http_code == 200

    # The global-allowed host still reaches its real upstream through the
    # lockdown.
    p_ok = wait_http_succeeds(client, host=GLOBAL_ALLOW_HOST, timeout_s=120)
    assert p_ok.http_code is not None and 200 <= p_ok.http_code < 400

    members = wait_global_allow_populated(timeout_s=30)
    assert members, "expected @global_allow to carry >=1 resolved IP under lockdown"
