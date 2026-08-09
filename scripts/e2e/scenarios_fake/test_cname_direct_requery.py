"""Suite G — directly-queried CNAME targets: ea_/eb_/bl_ kernel set population (#1351).

Guards the dns-tail ea_/eb_ populator introduced in #1346/#1349 and the bl_
populator introduced in #1348/#1350 against whole-system regression. The Lua
busted unit tests (openwrt/test/dns_tail_sets_spec.lua) cover the modules in
isolation; THIS suite proves the full pipeline on a real router VM:

    client → dnsmasq (CNAME chain resolution + log-queries=extra)
           → dns-tail sidecar (CNAME alias memory + nft add element)
           → nftables ea_/eb_/bl_ sets
           → nftables forward rules (drop or carve-out)

The failure mode captured by #1346/#1349 (ea_/eb_) and #1348/#1350 (bl_):
  (1) Client first resolves the branded host (e.g. `e2e-brand.wifihaven.net`).
      dnsmasq logs the full CNAME chain; dns-tail learns the alias map
      `e2e-edge.wifihaven.net → e2e-brand.wifihaven.net`.
  (2) Client then re-queries the leaf CNAME target DIRECTLY
      (`dig e2e-edge.wifihaven.net`). dnsmasq's `nftset=` callback only fires
      for names that suffix-match the configured domain — it does NOT fire for
      the raw CDN leaf.
  (3) Pre-#1349 dns-tail also missed ea_/eb_ population for the direct re-query.
      Pre-#1350 dns-tail also missed bl_ population for the direct re-query.
  (4) The directly-queried IP was therefore ABSENT from the kernel ea_/eb_/bl_
      sets, so a whole-MAC block did not carve out the flow (ea_ miss → false
      block), extraBlocked hosts were silently reachable (eb_ miss → filter
      bypass), and category-blocked content was reachable (bl_ miss → filter
      bypass — the highest severity case, since it bypasses a broad blocklist).

DNS chain (wifihaven.net zone — real Cloudflare records, managed by infra/cloudflare/main.tf):

    e2e-brand.wifihaven.net  CNAME → e2e-mid.wifihaven.net
    e2e-mid.wifihaven.net    CNAME → e2e-edge.wifihaven.net
    e2e-edge.wifihaven.net   A     → 192.0.2.10   (RFC 5737 TEST-NET-1)

Records are DNS-only (no Cloudflare proxy) so the chain resolves as authored.
The leaf A points to 192.0.2.10 — an RFC 5737 reserved-for-documentation
address that is GUARANTEED never globally routed or reassigned. The prior value
93.184.216.34 (legacy IANA example.com) was decommissioned ~2025 and its
HTTP/80 went dead, silently breaking the allow-path assertions and red-gating
all router releases (#1360). A reserved IP can never repeat that.

The leaf is intentionally unroutable, and the two test families treat it
differently:
  * BLOCK-PATH (G1 eb_, G4 bl_): the port-80 DNAT fires at prerouting BEFORE the
    dest is routed, so the block page is served regardless of whether the origin
    is reachable. These stay HARD-gating (origin-independent).
  * ALLOW-PATH (G2 HTTP-primary, G3 attribution): proving traffic flows THROUGH
    the ea_ carve-out / attribution path needs a real HTTP response, which an
    unroutable origin can't give — so those steps xfail when the leaf is
    unreachable (see _xfail_if_leaf_unreachable) instead of red-gating. The ea_
    POPULATOR is still hard-asserted via nft set membership, which needs only
    DNS resolution. A future harness-served origin (a fake-mode DNAT of the
    TEST-NET leaf to a local HTTP responder) would let the allow-path run for
    real again — tracked as the #1360 follow-up.

TERRAFORM PREREQUISITE: the e2e CNAME chain in infra/cloudflare/main.tf must be
applied to the live wifihaven.net zone before this suite can resolve it. Applies
are CI-driven on merge to main (master-cloudflare.yml; #1357/#1358) — no manual
`terraform apply` is needed going forward. CI's ci.yml cloudflare-terraform job
validates fmt+validate on PRs but does not apply. Without the DNS records the
`dig e2e-brand.wifihaven.net` step will NXDOMAIN and the scenario fails at the
resolution step, not the enforcement step. See PR #1351.

Cases:
  G1 — eb_ (extraBlocked): direct re-query of the leaf puts its IPs in eb_
       and HTTP/80 to the leaf hits the block page.
  G2 — ea_ (extraAllowed under blocked=True): direct re-query of the leaf puts
       its IPs in ea_ (hard-asserted); HTTP to the leaf succeeds via the
       carve-out (xfail when the leaf origin is unreachable).
  G3 — attribution: after direct re-query, connection_event reports the BRANDED
       host (e2e-brand.wifihaven.net), not the CDN/leaf target (xfail when the
       leaf origin is unreachable).
  G4 — bl_ category blocklist: direct re-query of the leaf puts its IPs in the
       bl_ category drop-set and HTTP/80 to the leaf hits the block page.
       Guards the dns-tail bl_ populator from #1348/#1350 AND the #1360
       blocklist-fetch auth fix (the fetch is router-authenticated; without the
       bearer token the bl_ set stays empty and the block silently no-ops).

Assertions are TRAFFIC-LEVEL (connection-layer, as required by docs/architecture.md
Truth-1 and CLAUDE.md): DNS always resolves; blocking is an nftables property.
nft set membership is checked as a DIAGNOSTIC secondary assertion, not the primary.
"""
from __future__ import annotations

import time

import pytest

from ._observers import (
    EGRESS_CONTROL_HOSTS,
    bl_set_name,
    dig_ipv4_answers,
    dns_egress_degraded,
    ea_set_name,
    eb_set_name,
    mac_in_blocked_set,
    wait_bl_set_exists,
    wait_bl_set_populated,
    wait_block_page,
    wait_ea_set_populated,
    wait_eb_set_exists,
    wait_eb_set_populated,
    wait_event_with_host_attribution,
    wait_http_succeeds,
)
from .snapshot_builder import SnapshotBuilder
from lib.traffic import dns_query, http_get
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
# NOTE (#1929): LEAF_HOST is DUAL-STACK — it carries both an A (LEAF_IP below)
# and an AAAA (2001:db8::10, RFC 3849, added in #1677 for the v6-attribution
# scenario). The allow-path probes here are pinned to IPv4 (`ipv4_only=True`)
# so they exercise the v4 ea_/eb_ sets these tests populate and assert; an
# unpinned probe drifts to the v6 chain (whose ea6_ set is unpopulated at
# connect time) and, post-#1868, lands on the now-delivering v6 block-page
# redirect. See _xfail_if_leaf_unreachable for the full mechanism.
# Leaf A record — RFC 5737 TEST-NET-1, reserved-for-documentation and GUARANTEED
# never globally routed or reassigned (#1360). The previous value 93.184.216.34
# (legacy IANA example.com) was decommissioned ~2025 and its HTTP/80 went dead,
# silently breaking the allow-path assertions. A reserved IP can never repeat
# that: the block-path tests (G1/G4) DNAT port 80 at prerouting BEFORE the dest
# is routed, so an unroutable leaf is irrelevant to them; the allow-path tests
# (G2-primary/G3) cannot reach an unroutable origin and therefore xfail when the
# leaf is unreachable (see _xfail_if_leaf_unreachable) rather than depending on a
# live third-party origin that returns 2xx for an arbitrary Host header — a thing
# that no longer reliably exists. The ea_ POPULATOR is still hard-asserted via
# nft set membership, which needs only DNS resolution, not a reachable origin.
LEAF_IP = "192.0.2.10"

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


def _requery_leaf_directly(client, *, attempts: int = 6, interval_s: float = 3) -> list[str]:
    """Re-query the leaf CNAME target directly, simulating Apple device behavior.

    This is step (2): the client issues `dig e2e-edge.wifihaven.net` as a fresh,
    standalone query. The qname on the wire is the CDN leaf, NOT the branded host.
    dnsmasq's nftset= callback does not fire for the leaf name. dns-tail's
    maybe_populate_ea/eb must recover the branded ancestor from the alias map and
    add the IP to the correct ea_/eb_ set inline (#1346/#1349).

    #2034: `dns_query` is single-shot (`dig +tries=1 +time=2`), so a lone egress
    blip here — immediately after the 120s chain poll already succeeded — would
    empty the answer and hard-fail the `assert leaf_ips` at the call site. Retry
    a handful of times to absorb a transient blip; the answer is normally
    immediate (dnsmasq already has the chain cached from step 1). This does not
    change what the step proves — it is still a fresh, standalone direct query of
    the leaf — only its resilience to a flaky shared-host egress.
    """
    last: list[str] = []
    for i in range(attempts):
        last = dig_ipv4_answers(client, LEAF_HOST)
        if last:
            return last
        if i < attempts - 1:
            time.sleep(interval_s)
    return last


def _skip_if_egress_degraded(client, what: str) -> None:
    """Skip (not fail) the calling test when external DNS egress is degraded.

    #2034 (Mode 2 of #2033): this suite resolves the REAL wifihaven.net CNAME
    chain through the router VM's dnsmasq upstream, which depends on the shared CD
    host's intermittently-flaky external egress (#1935). When a resolution step
    comes back empty we must distinguish two cases via a control probe through the
    SAME client→router→upstream path (`dns_egress_degraded`):

      * control apexes ALSO fail to resolve → external egress is down right now →
        environmental blip, NOT an enforcement regression → ``pytest.skip``.
      * control apexes resolve but our records don't → a genuine missing-zone /
        terraform regression (#1351) → return, letting the caller's hard
        assertion fire with its existing diagnostic.

    Enforcement assertions (block page, eb_/ea_/bl_ membership) are NEVER guarded
    by this — they run only after a successful resolution and remain hard, so a
    real enforcement regression still red-gates.
    """
    if dns_egress_degraded(client):
        pytest.skip(
            f"external DNS egress degraded (#2034): {what} did not resolve, and "
            f"neither did control hosts {EGRESS_CONTROL_HOSTS} "
            f"through the router upstream — an environmental egress blip on the "
            f"shared CD host, not an enforcement regression. Enforcement "
            f"assertions left intact; re-run to pick up a healthy egress window."
        )


def _wait_for_chain_resolution(client, *, timeout_s: float = 120) -> list[str]:
    """Poll until BRAND_HOST resolves to at least one IPv4 answer.

    If the Terraform records haven't propagated yet (TTL or just-applied)
    this guard surfaces the root cause (NXDOMAIN / empty answer) rather than
    letting the test hang at the next step.

    #2034: on timeout, classify the failure before propagating. A degraded
    external-egress window (the dominant Gate-2 flake) is skipped, not failed; a
    genuine missing-zone regression (control hosts resolve, ours don't) re-raises
    the `TimeoutError` (carrying `wait_until`'s `dig {BRAND_HOST} …` description,
    which already names the terraform/#1351 prerequisite) so the suite red-gates.
    """
    try:
        return wait_until(
            lambda: _resolve_chain(client) or None,
            timeout_s=timeout_s,
            interval_s=5,
            description=(
                f"dig {BRAND_HOST} to return at least one IPv4 answer "
                f"(requires terraform apply for infra/cloudflare)"
            ),
        )
    except TimeoutError:
        _skip_if_egress_degraded(client, f"chain head {BRAND_HOST}")
        raise


def _xfail_if_leaf_unreachable(client) -> None:
    """xfail (not fail) the calling allow-path test when the leaf origin can't
    answer HTTP/80.

    The leaf A record is RFC 5737 TEST-NET-1 (see LEAF_IP) — deliberately
    unroutable so the suite never again depends on a live third-party origin
    that happens to return 2xx for an arbitrary Host header (#1360). The
    block-path tests (G1/G4) don't need the origin at all because the port-80
    DNAT intercepts at prerouting before the dest is routed. The allow-path
    tests (G2-primary/G3), by contrast, need a real HTTP response to prove that
    traffic actually flows THROUGH the ea_ carve-out / attribution path — which
    an unroutable origin cannot provide. Rather than red-gating the whole router
    release on that environmental gap, we mark the end-to-end allow-flow xfail
    here; the ea_ POPULATOR is still hard-asserted via nft set membership above,
    which needs only DNS resolution. A future harness-served origin (a fake-mode
    DNAT of the TEST-NET leaf to a local HTTP responder) would let these run for
    real again — tracked as the #1360 follow-up.

    #1929: the probe is PINNED to IPv4 (`-4`). The leaf is dual-stack — an A
    (192.0.2.10) AND an AAAA (2001:db8::10), the latter added in #1677 for the
    v6-attribution scenario, AFTER this suite was authored against a v4-only
    leaf (#1360). Without `-4`, curl's happy-eyeballs prefers the v6 address and
    probes the v6 chain instead of the v4 ea_ set this test populated and
    asserted. That is a real trap, not cosmetic: #1868 switched the v6
    block-page path from a DNAT-to-::1 that never delivered to an nft `redirect`
    that DOES deliver to the local uhttpd listener, so a v6 probe to the leaf —
    whose ea6_ carve set is empty here (no AAAA is resolved through the router
    before curl's own lookup, so dns-tail has not populated ea6_ at connect
    time) — now lands on the block page and hard-fails the allow assertion
    (Master Router CD red 2026-06-22 → 06-24). Pinning to v4 exercises the v4
    ea_ carve the test actually proved, restoring the documented "unroutable v4
    leaf ⇒ xfail" semantics. The carve itself is correct in both families
    (render.lua emits `ip daddr != @ea_…` / `ip6 daddr != @ea6_…`; dns-tail
    populates both) — this was test-harness drift, not a carve regression.
    """
    probe = http_get(client, f"http://{LEAF_HOST}/", timeout_s=8, ipv4_only=True)
    if probe.http_code is None:
        pytest.xfail(
            f"leaf origin {LEAF_HOST} ({LEAF_IP}, RFC 5737 TEST-NET-1) is "
            f"unreachable for HTTP/80 — end-to-end allow-flow not exercised; "
            f"the ea_ populator is still asserted via nft set membership. "
            f"A harness-served origin would restore this path (#1360 follow-up)."
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
    # #2642: served is DELIVERED, not APPLIED — over ws the fake records the push
    # as it sends the frame, seconds before the router renders it. The client
    # resolution below is what populates eb_, and a lookup ingested before the
    # apply is attributed to no eb_ host (eb_adds=0), after which dnsmasq answers
    # from cache and never emits a second reply line for dns-tail to act on. Wait
    # for the apply first. (Narrows the window rather than closing it — #2662.)
    wait_eb_set_exists(BRAND_HOST)

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
    if not leaf_ips:  # #2034: empty here is a degraded-egress blip → skip, not fail
        _skip_if_egress_degraded(client, f"leaf {LEAF_HOST}")
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

    # Confirm the whole-MAC block is live. NOTE (#1360 / #421): this profile
    # has a non-empty extraAllowed, so render.lua deliberately pulls the MAC OUT
    # of the @blocked_macs set into a per-MAC forward-chain drop carrying the
    # `ip daddr != @ea_<mac>_<brand>` carve-out — the set cannot hold an
    # `ip daddr` predicate. Asserting @blocked_macs membership here therefore
    # never succeeds (it was the original G2 false-failure). Assert the per-MAC
    # drop rule instead, which is the correct "blocked right now" observable for
    # a blocked-with-extraAllowed MAC.
    from ._observers import wait_mac_drop_rule_present
    wait_mac_drop_rule_present(client.mac, timeout_s=120)

    # Step 1: resolve the branded host so dns-tail builds the alias map.
    chain_ips = _wait_for_chain_resolution(client)
    assert chain_ips, (
        f"dig {BRAND_HOST} returned no IPv4 — check terraform apply (#1351)"
    )

    # Step 2: re-query the leaf directly.
    leaf_ips = _requery_leaf_directly(client)
    if not leaf_ips:  # #2034: empty here is a degraded-egress blip → skip, not fail
        _skip_if_egress_degraded(client, f"leaf {LEAF_HOST}")
    assert leaf_ips, (
        f"direct dig {LEAF_HOST} returned no IPv4 — leaf A record missing"
    )
    assert LEAF_IP in leaf_ips, (
        f"expected {LEAF_IP} in direct-requery answers for {LEAF_HOST}, "
        f"got {leaf_ips!r}"
    )

    # POPULATOR PROOF (hard, origin-independent): the directly-queried leaf IP
    # must land in ea_<mac>_<brand>. This is the actual #1346 regression guard
    # and needs only DNS resolution — no reachable origin — so it stays hard
    # even with the unroutable TEST-NET leaf (#1360). Asserted BEFORE the
    # end-to-end HTTP check so it is never skipped by the xfail below.
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

    # END-TO-END ALLOW-FLOW (xfail when the leaf origin is unreachable). With
    # the carve-out present, the connection to the allowed leaf is neither
    # dropped nor DNAT'd — so an HTTP/80 request reaches the origin (when one
    # answers) and must NOT come back as the block page. The unroutable
    # TEST-NET leaf can't answer, so this xfails rather than red-gating (#1360);
    # the carve-out itself is already proven by the ea_ membership above.
    # #1929: pin the probe to IPv4 — the leaf is dual-stack (A + #1677 AAAA) and
    # the v4 ea_ set is the one populated/asserted above; see
    # _xfail_if_leaf_unreachable for why an unpinned (v6) probe hits the block
    # page post-#1868.
    _xfail_if_leaf_unreachable(client)
    probe = wait_http_succeeds(client, host=LEAF_HOST, timeout_s=120, ipv4_only=True)
    assert probe.http_code is not None and 200 <= probe.http_code < 400, (
        f"expected allowed HTTP response (200-399) for {LEAF_HOST} under "
        f"blocked=True, got {probe.http_code!r} — ea_ carve-out may be missing"
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
    if not leaf_ips:  # #2034: empty here is a degraded-egress blip → skip, not fail
        _skip_if_egress_degraded(client, f"leaf {LEAF_HOST}")
    assert leaf_ips, (
        f"direct dig {LEAF_HOST} returned no IPv4 — leaf A record missing"
    )

    # Step 3: make an HTTP request to LEAF_HOST so conntrack.lua emits a
    # connection_event with the attributed hostname. This attribution proof
    # needs a real flow to the origin; the unroutable TEST-NET leaf can't
    # provide one, so xfail rather than red-gate when it's unreachable (#1360).
    # The branded-host alias recovery itself is unit-covered in dns_log_spec.
    # #1929: pin to IPv4 for the same dual-stack reason as G2 — keep the probe
    # on the A-record path the attribution fixture exercises (the v4 conntrack
    # NEW event) rather than letting happy-eyeballs drift to the #1677 AAAA.
    _xfail_if_leaf_unreachable(client)
    wait_http_succeeds(client, host=LEAF_HOST, timeout_s=60, ipv4_only=True)

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


# ── G4 — bl_ (category blocklist) — dns-tail populator from #1348/#1350 ──────

# Blocklist id used for G4. Any ASCII-safe string is fine; we use a dedicated
# id so the bl_ set name is predictable and the test doesn't cross-pollinate
# other scenarios. The name intentionally contains hyphens to exercise
# bl_sanitize (render.lua replaces `-` → `_`).
_BL_ID = "e2e-cname-bl"


def test_bl_direct_requery_blocked(router, client, fake_api):
    """G4: direct re-query of the CNAME leaf populates the bl_ category
    drop-set and HTTP/80 to that IP hits the block page.

    Regression guard for #1348/#1350 (dns-tail bl_ populator). The
    pre-#1350 failure mode:
      - blocklists.lua adds BRAND_HOST's agent-resolved IPs to bl_<id>
        on the periodic 30 s cadence.
      - A device re-queries LEAF_HOST directly. Dnsmasq's `nftset=/<member>/…`
        callback does NOT fire (the wire qname is the CDN leaf, not the member).
        Pre-#1350 dns-tail also skipped bl_ population for the direct re-query.
      - The directly-queried IP is absent from bl_<id> → category block is
        silently bypassed.

    Post-#1350 dns-tail runs maybe_populate_bl on each `reply <name> is <ip>`:
    it resolves LEAF_HOST through the CNAME alias map → BRAND_HOST, finds that
    BRAND_HOST is a member of bl_<id>, and calls `nft add element bl_<id> {ip}`
    inline — the same lockstep approach as eb_ (#515/#1346).

    Harness design:
      - A fake-api-served blocklist is registered at /api/blocklists/<id>
        containing BRAND_HOST. The snapshot's `blocklists` dict points to
        that URL. blocklists.lua fetches and caches it; render.lua emits both
        the `nftset=/<member>/…` dnsmasq directive and the bl-member-index file
        that dns-tail loads every 30 s.
      - NO extraBlocked entry for LEAF_HOST. The point is that bl_ alone must
        catch the directly-queried leaf IP via the dns-tail populator.
      - Steps follow G1 (eb_ case): (1) resolve brand → alias map built,
        (2) direct re-query of leaf → maybe_populate_bl fires, (3) HTTP/80
        to the leaf must hit the block page (DNAT intercepted before real origin).

    Primary assertion (traffic-level, per docs/architecture.md Truth-1):
      HTTP/80 to LEAF_HOST returns the block-page body.
    Secondary (diagnostic):
      wait_bl_set_populated confirms bl_<id> has the leaf IP on the router.
    """
    # Register blocklist content in the fake before serving the snapshot.
    # blocklists.lua fetches /api/blocklists/<id>; the fake serves it from its
    # in-memory store. The version in the snapshot controls cache-busting on the
    # agent (a new version string forces a fresh fetch even if the file is already
    # on disk). We use a stable version string here — the agent will fetch once
    # and cache; subsequent policy cycles see a 304 for the snapshot but the
    # blocklist cache file is already present.
    fake_api.register_blocklist(_BL_ID, [BRAND_HOST])

    snap = (
        SnapshotBuilder()
        .add_profile(
            id=KIDS_PID,
            name="e2e-cname-bl",
            blocklist_ids=[_BL_ID],
        )
        .add_device(mac=client.mac, name="e2e-cname-bl-dev", profile_id=KIDS_PID)
        .add_blocklist(id=_BL_ID, version="2026.06.01")
        .build(etag='"sha256:cname-bl-v1"')
    )
    etag = fake_api.serve_snapshot(snap)
    fake_api.wait_for_etag_served(etag=etag, timeout_s=240)
    # #2642: the same apply barrier G1 needs, for the same reason — this suite's
    # bl_ path is not structurally safer than its eb_ path. Both rely on the
    # resolve-time populator within a scenario, because their shared periodic
    # re-populator (eb_refresh, over eb_hosts AND bl_pairs) runs on
    # eb_refresh_interval — 1800s, far outside this test's budget. G4 happened to
    # win this race while G1 lost it; the barrier is what makes winning it
    # deterministic rather than luck.
    wait_bl_set_exists(_BL_ID)

    # Step 1: resolve the branded host so dns-tail builds the alias map
    # (edge→brand). Without this first resolution the alias map is empty and
    # maybe_populate_bl falls back to the raw replied name (LEAF_HOST), which
    # is not a member of the blocklist.
    chain_ips = _wait_for_chain_resolution(client)
    assert chain_ips, (
        f"dig {BRAND_HOST} returned no IPv4 answers — check that "
        f"terraform apply has been run for infra/cloudflare/ (#1351)"
    )

    # Truth-1: DNS for the branded host returns real IPs, not NXDOMAIN.
    assert any(chain_ips), (
        f"expected real IPv4 from chain resolution, got {chain_ips!r}"
    )

    # Step 2: re-query the leaf CNAME target directly (simulates Apple device).
    # dns-tail observes the `reply e2e-edge.wifihaven.net is 93.184.216.34`
    # log line, resolves the name → BRAND_HOST via the alias map, finds it in
    # the bl-member-index, and calls `nft add element bl_<id> {ip}`.
    leaf_ips = _requery_leaf_directly(client)
    if not leaf_ips:  # #2034: empty here is a degraded-egress blip → skip, not fail
        _skip_if_egress_degraded(client, f"leaf {LEAF_HOST}")
    assert leaf_ips, (
        f"direct dig {LEAF_HOST} returned no IPv4 — leaf A record missing or "
        f"not yet propagated (terraform apply required)"
    )
    assert LEAF_IP in leaf_ips, (
        f"expected {LEAF_IP} in direct-requery answers for {LEAF_HOST}, "
        f"got {leaf_ips!r}"
    )

    # Primary assertion: HTTP/80 to the leaf hits the block page. The bl_
    # category drop + DNAT fires because the leaf IP is now in bl_<id>;
    # the connection never reaches 93.184.216.34.
    probe = wait_block_page(client, host=LEAF_HOST, timeout_s=120)
    assert probe.http_code == 200, (
        f"expected block page (HTTP 200) for {LEAF_HOST}, got {probe.http_code!r} — "
        f"bl_ category drop may be missing the directly-queried leaf IP"
    )

    # Secondary (diagnostic): assert bl_<id> set membership. This narrows the
    # failure to the dns-tail bl_ populator when the traffic probe catches a
    # regression even though the set is empty.
    bl_members = wait_bl_set_populated(_BL_ID, timeout_s=60)
    assert bl_members, (
        f"nft set {bl_set_name(_BL_ID)} is empty — dns-tail bl_ populator "
        f"(#1348/#1350) did not add the directly-queried leaf IP to the "
        f"category drop-set"
    )
    assert any(LEAF_IP in m for m in bl_members), (
        f"leaf IP {LEAF_IP} not found in {bl_set_name(_BL_ID)} members: "
        f"{bl_members!r} — direct-requery IP was not attributed to blocklist member"
    )
