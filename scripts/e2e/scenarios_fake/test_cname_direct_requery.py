"""Suite G — directly-queried CNAME targets: ea_/eb_ kernel set population (#1351).

Guards the dns-tail ea_/eb_ populator introduced in #1346/#1349 against
whole-system regression. The Lua busted unit tests (openwrt/test/dns_tail_sets_spec.lua)
cover the module in isolation; THIS suite proves the full pipeline on a real router VM:

    client → dnsmasq (CNAME chain resolution + log-queries=extra)
           → dns-tail sidecar (CNAME alias memory + nft add element)
           → nftables ea_/eb_ sets
           → nftables forward rules (drop or carve-out)

The failure mode captured by #1346/#1349:
  (1) Client first resolves the branded host (e.g. `e2e-brand.wifihaven.net`).
      dnsmasq logs the full CNAME chain; dns-tail learns the alias map
      `e2e-edge.wifihaven.net → e2e-brand.wifihaven.net`.
  (2) Client then re-queries the leaf CNAME target DIRECTLY
      (`dig e2e-edge.wifihaven.net`). dnsmasq's `nftset=` callback only fires
      for names that suffix-match the configured domain — it does NOT fire for
      the raw CDN leaf. Pre-#1349 dns-tail also missed this: `maybe_populate_eb`
      keyed only on `r.name` (= CDN leaf), which never label-matched `eb_<brand>`.
  (3) The directly-queried IP was therefore ABSENT from the kernel ea_/eb_ set,
      so a whole-MAC block did not carve out the flow (ea_ miss → false block)
      and an extraBlocked host was silently reachable (eb_ miss → filter bypass).

DNS chain (wifihaven.net zone — real Cloudflare records, managed by infra/cloudflare/main.tf):

    e2e-brand.wifihaven.net  CNAME → e2e-mid.wifihaven.net
    e2e-mid.wifihaven.net    CNAME → e2e-edge.wifihaven.net
    e2e-edge.wifihaven.net   A     → 93.184.216.34   (IANA example.com)

Records are DNS-only (no Cloudflare proxy) so the chain resolves as authored.
The leaf A points to 93.184.216.34 (IANA's example.com IP) — a real HTTP server
that returns a response the harness can distinguish from the block page. The
DNAT for port 80 intercepts before the real origin is reached in the blocked
case, so the origin's actual content is irrelevant for the eb_ test.

TERRAFORM PREREQUISITE: `terraform apply` in infra/cloudflare/ must have been
run (operator-applied, CLOUDFLARE_API_TOKEN required) before this suite can
pass — CI validates fmt+validate only; it does not apply. Without the real DNS
records the `dig e2e-brand.wifihaven.net` step will NXDOMAIN and the scenario
will fail at the resolution step, not the enforcement step. See PR #1351.

Cases:
  G1 — eb_ (extraBlocked): direct re-query of the leaf puts its IPs in eb_
       and HTTP/80 to the leaf hits the block page.
  G2 — ea_ (extraAllowed under blocked=True): direct re-query of the leaf puts
       its IPs in ea_ and HTTP to the leaf succeeds (carve-out fires).
  G3 — attribution: after direct re-query, connection_event reports the BRANDED
       host (e2e-brand.wifihaven.net), not the CDN/leaf target.
  G4 — bl_ category blocklist slot (TODO(#1348): xfail until bl_ dns-tail
       populator is implemented).

Assertions are TRAFFIC-LEVEL (connection-layer, as required by docs/architecture.md
Truth-1 and CLAUDE.md): DNS always resolves; blocking is an nftables property.
nft set membership is checked as a DIAGNOSTIC secondary assertion, not the primary.
"""
from __future__ import annotations

import pytest

from ._observers import (
    dig_ipv4_answers,
    ea_set_name,
    eb_set_name,
    mac_in_blocked_set,
    wait_block_page,
    wait_ea_set_populated,
    wait_eb_set_populated,
    wait_event_with_host_attribution,
    wait_http_succeeds,
)
from .snapshot_builder import SnapshotBuilder
from lib.traffic import dns_query
from lib.vm import router_nft_set
from lib.wait import wait_until

pytestmark = pytest.mark.cname_direct_requery

# ── Fixture hostnames (Cloudflare wifihaven.net zone) ──────────────────────
#
# These are the e2e controllable records added to infra/cloudflare/main.tf.
# They must exist in the real wifihaven.net zone (terraform apply) before this
# suite can run. The three-hop chain is:
#   BRAND_HOST → CNAME → MID_HOST → CNAME → LEAF_HOST → A → LEAF_IP

BRAND_HOST = "e2e-brand.wifihaven.net"  # branded/queried host in extraBlocked/extraAllowed
MID_HOST = "e2e-mid.wifihaven.net"  # intermediate CNAME hop
LEAF_HOST = "e2e-edge.wifihaven.net"  # final CNAME target; Apple devices re-query this directly
LEAF_IP = "93.184.216.34"  # leaf A record — IANA example.com (stable, responds to HTTP)

KIDS_PID = 700
ADULTS_PID = 701


# ── Helpers ─────────────────────────────────────────────────────────────────


def _resolve_chain(client) -> list[str]:
    """Resolve the branded host from the client; return IPv4 answers.

    This step exercises step (1) of the attack surface: dnsmasq observes the
    full CNAME chain for BRAND_HOST and dns-tail builds the alias map
    leaf→brand. Without this first resolution the alias map is empty and the
    direct-requery step proves nothing new.
    """
    res = dns_query(client, BRAND_HOST, timeout_s=10)
    lines = [l.strip() for l in (res.stdout or "").splitlines() if l.strip()]
    import re
    ipv4 = re.compile(r"^\d+\.\d+\.\d+\.\d+$")
    return [l for l in lines if ipv4.match(l)]


def _requery_leaf_directly(client) -> list[str]:
    """Re-query the leaf CNAME target directly, simulating Apple device behavior.

    This is step (2): the client issues `dig e2e-edge.wifihaven.net` as a fresh,
    standalone query. The qname on the wire is the CDN leaf, NOT the branded host.
    dnsmasq's nftset= callback does not fire for the leaf name. dns-tail's
    maybe_populate_ea/eb must recover the branded ancestor from the alias map and
    add the IP to the correct ea_/eb_ set inline (#1346/#1349).
    """
    return dig_ipv4_answers(client, LEAF_HOST)


def _wait_for_chain_resolution(client, *, timeout_s: float = 120) -> list[str]:
    """Poll until BRAND_HOST resolves to at least one IPv4 answer.

    If the Terraform records haven't propagated yet (TTL or just-applied)
    this guard surfaces the root cause (NXDOMAIN / empty answer) rather than
    letting the test hang at the next step.
    """
    return wait_until(
        lambda: _resolve_chain(client) or None,
        timeout_s=timeout_s,
        interval_s=5,
        description=(
            f"dig {BRAND_HOST} to return at least one IPv4 answer "
            f"(requires terraform apply for infra/cloudflare)"
        ),
    )


# ── G1 — eb_: directly-queried leaf IP lands in eb_ → HTTP hits block page ──


def test_eb_direct_requery_blocked(router, client, fake_api):
    """G1: direct re-query of the CNAME leaf populates eb_ and triggers the
    block page for HTTP/80 connections to that IP.

    Pre-#1349 regression: the directly-queried leaf IP was absent from
    `eb_<brand>` so the extraBlocked rule was silently bypassed — an
    extraBlocked host was reachable simply by re-querying its CNAME target
    directly. This is the higher-severity half (#1346 severity note).

    Primary assertion (traffic-level): HTTP/80 to LEAF_HOST returns the block
    page body (DNAT fires → real origin is never reached).
    Secondary (diagnostic): the leaf IP appears in `eb_<brand>` set on the router.
    DNS still returns real IPv4 (Truth-1 guard — never NXDOMAIN).
    """
    snap = (
        SnapshotBuilder()
        .add_profile(
            id=KIDS_PID,
            name="e2e-cname-eb",
            extra_blocked=[BRAND_HOST],
        )
        .add_device(mac=client.mac, name="e2e-cname-eb-dev", profile_id=KIDS_PID)
        .build(etag='"sha256:cname-eb-v1"')
    )
    etag = fake_api.serve_snapshot(snap)
    fake_api.wait_for_etag_served(etag=etag, timeout_s=240)

    # Step 1: resolve the branded host so dns-tail builds the alias map.
    chain_ips = _wait_for_chain_resolution(client)
    assert chain_ips, (
        f"dig {BRAND_HOST} returned no IPv4 answers — check that "
        f"terraform apply has been run for infra/cloudflare/ (#1351)"
    )

    # Truth-1: DNS for the branded host returns real IPs, not NXDOMAIN.
    assert LEAF_IP in chain_ips or any(ip for ip in chain_ips), (
        f"expected real IPv4 from chain resolution, got {chain_ips!r}"
    )

    # Step 2: re-query the leaf CNAME target directly (simulates Apple device).
    leaf_ips = _requery_leaf_directly(client)
    assert leaf_ips, (
        f"direct dig {LEAF_HOST} returned no IPv4 — leaf A record missing or "
        f"not yet propagated (terraform apply required)"
    )
    assert LEAF_IP in leaf_ips, (
        f"expected {LEAF_IP} in direct-requery answers for {LEAF_HOST}, got {leaf_ips!r}"
    )

    # Primary assertion: HTTP/80 to the leaf's branded hostname hits the block page.
    # The DNAT rule fires because the leaf IP should now be in eb_<brand>; the
    # connection never reaches 93.184.216.34.
    probe = wait_block_page(client, host=LEAF_HOST, timeout_s=120)
    assert probe.http_code == 200, (
        f"expected block page (HTTP 200) for {LEAF_HOST}, got {probe.http_code!r}"
    )

    # Secondary (diagnostic): assert eb_<brand> set membership.
    # This narrows the failure to the dns-tail populator when the traffic
    # probe catches a regression even though the set is empty.
    eb_members = wait_eb_set_populated(BRAND_HOST, timeout_s=60)
    assert eb_members, (
        f"nft set {eb_set_name(BRAND_HOST)} is empty — dns-tail eb_ populator "
        f"did not add the directly-queried leaf IP to the brand's drop set"
    )
    assert any(LEAF_IP in m for m in eb_members), (
        f"leaf IP {LEAF_IP} not found in {eb_set_name(BRAND_HOST)} members: "
        f"{eb_members!r} — direct-requery IP was not attributed to brand"
    )


# ── G2 — ea_: directly-queried leaf IP lands in ea_ → flow is allowed ────────


def test_ea_direct_requery_allowed_under_blocked_mac(router, client, fake_api):
    """G2: under a whole-MAC block (blocked=True), an extraAllowed CNAME-fronted
    host's directly-queried leaf IP lands in ea_ and the connection is carved out.

    Pre-#1349 regression: the directly-queried leaf IP was absent from
    `ea_<mac>_<brand>` so the carve-out did not fire and the app was falsely
    blocked during a downtime schedule — the original prod incident with Khan
    Academy / Math Academy (#1344).

    Primary assertion (traffic-level): HTTP to LEAF_HOST succeeds (real response
    body, NOT the block page) even though blocked=True for the MAC. If ea_ is
    not populated for the directly-queried IP, the forward-drop rule fires and
    curl sees HTTPCODE:000 / connection reset — distinguishable from the block
    page by `wait_http_succeeds` which rejects block-page-looking bodies.
    Secondary (diagnostic): the leaf IP appears in `ea_<mac>_<brand>` on the router.
    """
    snap = (
        SnapshotBuilder()
        .add_profile(
            id=KIDS_PID,
            name="e2e-cname-ea",
            blocked=True,
            block_reason="Schedule",
            extra_allowed=[BRAND_HOST],
        )
        .add_device(mac=client.mac, name="e2e-cname-ea-dev", profile_id=KIDS_PID)
        .build(etag='"sha256:cname-ea-v1"')
    )
    etag = fake_api.serve_snapshot(snap)
    fake_api.wait_for_etag_served(etag=etag, timeout_s=240)

    # Confirm the MAC is in blocked_macs (whole-MAC block active).
    from ._observers import wait_mac_in_blocked_set
    wait_mac_in_blocked_set(client.mac, present=True, timeout_s=90)

    # Step 1: resolve the branded host so dns-tail builds the alias map.
    chain_ips = _wait_for_chain_resolution(client)
    assert chain_ips, (
        f"dig {BRAND_HOST} returned no IPv4 — check terraform apply (#1351)"
    )

    # Step 2: re-query the leaf directly.
    leaf_ips = _requery_leaf_directly(client)
    assert leaf_ips, (
        f"direct dig {LEAF_HOST} returned no IPv4 — leaf A record missing"
    )
    assert LEAF_IP in leaf_ips, (
        f"expected {LEAF_IP} in direct-requery answers for {LEAF_HOST}, "
        f"got {leaf_ips!r}"
    )

    # Primary assertion: HTTP to LEAF_HOST succeeds despite blocked=True.
    # The ea_ carve-out for the directly-queried IP must be present in the
    # kernel set for the forward-drop to spare this flow.
    probe = wait_http_succeeds(client, host=LEAF_HOST, timeout_s=120)
    assert probe.http_code is not None and 200 <= probe.http_code < 400, (
        f"expected allowed HTTP response (200-399) for {LEAF_HOST} under "
        f"blocked=True, got {probe.http_code!r} — ea_ carve-out may be missing"
    )

    # Secondary (diagnostic): assert ea_<mac>_<brand> set membership.
    ea_members = wait_ea_set_populated(client.mac, BRAND_HOST, timeout_s=60)
    assert ea_members, (
        f"nft set {ea_set_name(client.mac, BRAND_HOST)} is empty — dns-tail "
        f"ea_ populator did not add the directly-queried leaf IP to the "
        f"brand's allow-set for this MAC"
    )
    assert any(LEAF_IP in m for m in ea_members), (
        f"leaf IP {LEAF_IP} not found in "
        f"{ea_set_name(client.mac, BRAND_HOST)} members: {ea_members!r}"
    )


# ── G3 — attribution: connection_event reports BRAND_HOST, not LEAF_HOST ─────


def test_attribution_reports_branded_host_after_direct_requery(router, client, fake_api):
    """G3: after the client re-queries the leaf CNAME target directly, the
    connection_event posted to /api/router/events attributes the flow to the
    BRANDED host (e2e-brand.wifihaven.net), not the raw CDN/leaf target
    (e2e-edge.wifihaven.net).

    This proves #1344/#1345 end-to-end: dns_log's CNAME alias map correctly
    recovers the branded chain-head at attribution time so that logs and
    per-host usage are readable and conntrack.lua's ea_/eb_ classification
    suffix-matches the app's allowlist entry.

    Primary assertion: a connection_event for client.mac whose host.value
    contains BRAND_HOST appears in the fake after the direct re-query and an
    HTTP request to LEAF_HOST.
    """
    snap = (
        SnapshotBuilder()
        .add_profile(id=KIDS_PID, name="e2e-cname-attr")
        .add_device(mac=client.mac, name="e2e-cname-attr-dev", profile_id=KIDS_PID)
        .build(etag='"sha256:cname-attr-v1"')
    )
    etag = fake_api.serve_snapshot(snap)
    fake_api.wait_for_etag_served(etag=etag, timeout_s=240)

    # Step 1: resolve the branded host (builds alias map in dns-tail/dns_log).
    chain_ips = _wait_for_chain_resolution(client)
    assert chain_ips, (
        f"dig {BRAND_HOST} returned no IPv4 — check terraform apply (#1351)"
    )

    # Step 2: direct re-query of the leaf target.
    leaf_ips = _requery_leaf_directly(client)
    assert leaf_ips, (
        f"direct dig {LEAF_HOST} returned no IPv4 — leaf A record missing"
    )

    # Step 3: make an HTTP request to LEAF_HOST so conntrack.lua emits a
    # connection_event with the attributed hostname. In the allowed profile
    # the connection goes through to the real origin.
    wait_http_succeeds(client, host=LEAF_HOST, timeout_s=60)

    # Primary assertion: the event posted to /api/router/events carries the
    # branded host, not the CDN leaf target.
    ev = wait_event_with_host_attribution(
        fake_api,
        client.mac,
        branded_host=BRAND_HOST,
        timeout_s=120,
    )
    assert ev is not None, (
        f"no connection_event for {client.mac} with host attribution "
        f"containing {BRAND_HOST!r} — dns_log alias map not recovering "
        f"branded host after direct re-query of {LEAF_HOST}"
    )

    # Sanity: the event must NOT attribute to the raw CDN leaf name.
    host_val = (ev.get("host") or {}).get("value") or ""
    assert LEAF_HOST not in host_val, (
        f"connection_event attributed to CDN leaf {LEAF_HOST!r} instead of "
        f"branded host {BRAND_HOST!r} — #1344 attribution regression"
    )


# ── G4 — bl_ (category blocklist) — xfail pending #1348 ─────────────────────


@pytest.mark.xfail(
    reason=(
        "TODO(#1348): bl_ category-blocklist bypass via directly-queried CNAME "
        "targets is not yet fixed. The bl_ populator (blocklists.lua) is driven "
        "by agent-side resolution, not by client DNS answers, so the #1346/#1349 "
        "dns-tail alias-map fix is not a drop-in here. This slot will flip from "
        "xfail to a real assertion once #1348 ships a dns-tail bl_ populator."
    ),
    strict=False,
)
def test_bl_direct_requery_blocked_xfail(router, client, fake_api):
    """G4 (xfail — #1348): category-blocklist (bl_) enforcement for
    directly-queried CNAME targets.

    The bl_/bl6_ sets are populated by the agent's periodic blocklists.lua
    resolution, not by client DNS answers. When a device re-queries a blocklist
    member's CNAME target directly, the resolved IP may not be in the bl_ set
    (CDN-anycast IP variance). Fixing this requires a dns-tail bl_ populator
    that maps client-observed CNAME-target answers back to blocklist membership
    via the #1344 alias map — tracked in #1348.

    This slot exists so the scenario file is complete and will flip to a real
    assertion when #1348 ships, without requiring a new PR at that time.
    """
    # When #1348 ships: set up a snapshot with a blocklist that includes
    # BRAND_HOST (or a host whose CNAME chain is BRAND_HOST→...→LEAF_HOST),
    # resolve the branded host (step 1), re-query the leaf directly (step 2),
    # and assert HTTP to LEAF_HOST hits the block page. For now: xfail-by-pass.
    pytest.xfail("bl_ direct-requery populator not yet implemented (#1348)")
