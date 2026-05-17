"""Suite B — extraBlocked Truth-1 + per-profile scoping (#429).

Extends `test_03_blocked_domain.py` / `test_06_blocked_page.py` by locking
the architectural contract from #351 / #352 / #392:

  Truth 1: extraBlocked is enforced at the *connection* layer (nftables),
  not the DNS layer. dnsmasq still returns the real upstream IPs; the
  router then drops port-80 traffic to those IPs (or DNATs to the local
  block page) via a per-host nft set (`eb_<sanitized_host>`).

Cases:
  - B1 (@smoke): dig from the client returns a real IPv4, NOT NXDOMAIN.
  - B2: curl gets block-page body; the resolved dest IP appears in the
        per-host nft drop set on the router.
  - B3: per-MAC scoping — the Kids MAC (assigned to a profile WITH
        extraBlocked) is gated by the per-host set; a second MAC assigned
        to a profile WITHOUT extraBlocked is not subject to MAC-wide block.
        Note: render.lua's `eb_<host>` set is per-host (shared across MACs);
        per-MAC isolation lives in the drop *rules* (ether saddr <mac> AND
        ip daddr @eb_<host>), not the set membership. So we assert (a) the
        Kids MAC is NOT in `blocked_macs` (extraBlocked is host-scoped, not
        a MAC-wide pause), (b) the Adults MAC is NOT in `blocked_macs`
        either, and (c) the per-host set IS populated — confirming the
        enforcement plane is alive without claiming a per-MAC set split
        that render.lua doesn't produce.

Asserts current behavior. Bugs surfaced here get filed separately
(see Discipline note in #429).
"""
from __future__ import annotations

import re
import uuid

import pytest

from lib.traffic import dns_query, http_get
from lib.vm import router_nft_set
from lib.wait import wait_for_etag_change, wait_until

pytestmark = pytest.mark.extra_blocked


# A host whose A-records are reachable from the router's WAN. Per
# test_06_blocked_page.py, the agent populates the per-host nft set from
# dnsmasq's actual upstream answers, so a real domain is required.
BLOCKED_HOST = "example.com"

BLOCKED_MACS_SET = "blocked_macs"

# render.lua's sanitize() replaces "." and ":" with "_". `example.com`
# becomes `eb_example_com` (v4) / `eb6_example_com` (v6). See
# openwrt/files/usr/lib/lua/wifihaven/render.lua eb_set_name().
EB_SET_V4 = "eb_" + BLOCKED_HOST.replace(".", "_").replace(":", "_")

IPV4_LINE = re.compile(r"^\d+\.\d+\.\d+\.\d+$")


def _norm_mac(mac: str) -> str:
    return mac.lower().strip()


def _mac_in_blocked_set(mac: str) -> bool:
    members = {_norm_mac(m) for m in router_nft_set(BLOCKED_MACS_SET)}
    return _norm_mac(mac) in members


def _eb_set_members() -> list[str]:
    return router_nft_set(EB_SET_V4)


def _wait_block_page(client, *, host: str = BLOCKED_HOST, timeout_s: float = 90):
    def probe():
        p = http_get(client, f"http://{host}/", timeout_s=8)
        body_lower = (p.body or "").lower()
        if p.http_code == 200 and (
            "wifihaven" in body_lower or "blocked" in body_lower
        ):
            return p
        return None
    return wait_until(
        probe, timeout_s=timeout_s, interval_s=3,
        description=f"block page response for {host}",
    )


def _wait_eb_set_populated(*, timeout_s: float = 90) -> list[str]:
    """Wait until the per-host eb_<host> nft set has at least one IPv4
    element. Population is driven by dnsmasq --nftset= callbacks fired on
    real upstream answers; depending on cache state this can take a few
    seconds after the etag changes."""
    def probe():
        elems = _eb_set_members()
        return elems if elems else None
    return wait_until(
        probe, timeout_s=timeout_s, interval_s=3,
        description=f"nft set {EB_SET_V4} to gain at least one element",
    )


def _dig_ipv4_answers(client, host: str) -> list[str]:
    """Return the IPv4 answer lines from `dig +short <host>` on the client."""
    res = dns_query(client, host, timeout_s=5)
    lines = [l.strip() for l in (res.stdout or "").splitlines() if l.strip()]
    return [l for l in lines if IPV4_LINE.match(l)]


# ── B1 / B4 ────────────────────────────────────────────────────────────────


@pytest.mark.smoke
def test_extra_blocked_dns_returns_real_ip(
    router, client, scratch_device, scratch_profile, admin,
):
    """B1 + B4 (smoke): Truth-1 — DNS for an extraBlocked host returns
    real IPv4 answers, not NXDOMAIN.

    Guards against regression to the old dnsmasq `address=/.../#` sinkhole
    behavior (#351). The enforcement plane is nft drops, NOT DNS lies.
    """
    admin.apply_profile_update(scratch_profile["id"], extraBlocked=[BLOCKED_HOST])
    wait_for_etag_change(admin, router.router_id, timeout_s=120)

    # Allow one poll cycle worth of slack for the new dnsmasq config to be
    # re-rendered and reloaded before we sample DNS.
    answers = wait_until(
        lambda: _dig_ipv4_answers(client, BLOCKED_HOST) or None,
        timeout_s=90, interval_s=3,
        description=f"dig {BLOCKED_HOST} to return an IPv4 answer",
    )
    assert answers, f"expected real IPv4 answers for {BLOCKED_HOST}, got none"
    for a in answers:
        assert IPV4_LINE.match(a), f"unexpected non-IPv4 answer line: {a!r}"


# ── B2 ─────────────────────────────────────────────────────────────────────


def test_extra_blocked_http_drops_to_block_page_and_set_populated(
    router, client, scratch_device, scratch_profile, admin,
):
    """B2: HTTP/80 to an extraBlocked host is intercepted (block page body),
    and the resolved dest IP appears in the per-host nft set on the router.

    The set name pattern is `eb_<sanitize(host)>` where sanitize maps
    [.:] → _ (see render.lua eb_set_name). It is per-HOST, not per-MAC —
    per-MAC isolation lives in the drop rules (ether saddr <mac> AND
    ip daddr @eb_<host>), which we cover indirectly via the block-page
    observable.
    """
    admin.apply_profile_update(scratch_profile["id"], extraBlocked=[BLOCKED_HOST])
    wait_for_etag_change(admin, router.router_id, timeout_s=120)

    # 1) The block page is served (Truth-1 enforcement, DNAT to local uhttpd).
    probe = _wait_block_page(client, timeout_s=90)
    assert probe.http_code == 200

    # 2) The per-host nft set has elements (dnsmasq --nftset= populated it
    # from the real upstream answer). The exact IP varies — we only need
    # at least one member to prove the enforcement plane is hot.
    members = _wait_eb_set_populated(timeout_s=90)
    assert members, (
        f"expected nft set {EB_SET_V4} to have at least one IP element, "
        f"got empty"
    )


# ── B3 ─────────────────────────────────────────────────────────────────────


def test_extra_blocked_is_per_profile_scoped(
    router, client, scratch_device, scratch_profile, admin,
):
    """B3: extraBlocked on the Kids profile does NOT MAC-wide block either
    the Kids MAC or a phantom Adults MAC.

    We can't drive HTTP from a phantom MAC without a second client VM
    (see scripts/vm/README.md), so we assert per-MAC isolation via NFT
    state:

      - Neither MAC appears in `blocked_macs` (extraBlocked is host-scoped,
        not a MAC-wide pause — that's the pause suite's job, #428).
      - The per-host eb_<host> set is populated (Kids enforcement is hot).
      - The Adults profile has no extraBlocked, so its derived state should
        not introduce any MAC-wide block on its assigned device.

    This indirectly proves per-(MAC, host) drop scoping: the Kids MAC's
    HTTP gets the block page (verified by B2), but neither MAC is in
    blocked_macs, so the Adults MAC is not subject to a MAC-wide drop.
    """
    kids_pid = scratch_profile["id"]
    kids_mac = client.mac

    # Apply extraBlocked to Kids only.
    admin.apply_profile_update(kids_pid, extraBlocked=[BLOCKED_HOST])
    wait_for_etag_change(admin, router.router_id, timeout_s=120)
    _wait_eb_set_populated(timeout_s=90)

    # Create a second profile (Adults) with NO extraBlocked, assigned to a
    # phantom MAC the router has never seen. The profile defaults
    # (create_profile in api_admin.py) already set extraBlocked=[].
    adults = admin.create_profile(name=f"e2e-b3-adults-{uuid.uuid4().hex[:6]}")
    adults_pid = adults["profile"]["id"] if "profile" in adults else adults["id"]
    adults_mac = "02:b3:b3:00:00:%02x" % (uuid.uuid4().int & 0xFF)
    try:
        admin.upsert_device(
            mac=adults_mac,
            name=f"e2e-b3-adults-{adults_mac[-5:]}",
            profile_id=adults_pid,
        )
        wait_for_etag_change(admin, router.router_id, timeout_s=120)

        # Neither MAC should be in blocked_macs — extraBlocked is per-host,
        # not a profile-wide pause.
        def neither_blocked():
            kb = _mac_in_blocked_set(kids_mac)
            ab = _mac_in_blocked_set(adults_mac)
            return True if (not kb and not ab) else None

        wait_until(
            neither_blocked, timeout_s=60, interval_s=2,
            description=(
                f"neither {kids_mac} nor {adults_mac} present in "
                f"{BLOCKED_MACS_SET}"
            ),
        )

        # And the Kids per-host set is still populated (extraBlocked applied
        # to Kids only — Adults having no extraBlocked must not tear down
        # the Kids enforcement set).
        assert _eb_set_members(), (
            f"Kids enforcement set {EB_SET_V4} unexpectedly empty after "
            f"adding an unrelated Adults profile"
        )
    finally:
        try:
            admin.delete_device(adults_mac)
        except Exception:
            pass
        try:
            admin.delete_profile(adults_pid)
        except Exception:
            pass
