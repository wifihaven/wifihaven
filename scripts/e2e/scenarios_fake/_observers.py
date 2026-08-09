"""Shared observers for fake-mode scenarios (#684).

Same nft / HTTP / DNS probes used by the live-mode scenarios. Kept in a
private module rather than `lib/` because they exist purely to mirror the
live scenarios' assertion shape — moving them into `lib/` would pull a
fake-mode-specific dialect into a module the live conftest also imports.

Helpers are observers only — they read router state, never mutate the fake.
Snapshot-side mutation goes through `scenarios_fake.snapshot_builder` + the
fake's `POST /test/snapshot`.
"""
from __future__ import annotations

import re

from lib.traffic import dns_query, http_get
from lib.vm import client_exec, router_nft_set, router_nft_set_exists, router_ssh
from lib.wait import wait_until
from lib.wan_health import CONTROL_APEX_HOSTS


BLOCKED_MACS_SET = "blocked_macs"


def norm_mac(mac: str) -> str:
    return mac.lower().strip()


def mac_in_blocked_set(mac: str) -> bool:
    members = {norm_mac(m) for m in router_nft_set(BLOCKED_MACS_SET)}
    return norm_mac(mac) in members


def wait_mac_in_blocked_set(mac: str, *, present: bool, timeout_s: float = 180) -> None:
    wait_until(
        lambda: True if mac_in_blocked_set(mac) is present else None,
        timeout_s=timeout_s,
        interval_s=2,
        description=(
            f"{mac} {'present in' if present else 'absent from'} {BLOCKED_MACS_SET}"
        ),
    )


def mac_drop_rule_present(mac: str) -> bool:
    """True iff the agent has rendered a **whole-MAC** drop rule for `mac`.

    The correct "this MAC is blocked right now" observable for a MAC whose
    effective rules have ``blocked = true`` — and the only correct one when the
    MAC also has a non-empty ``extraAllowed`` list. Per #421, render.lua pulls a
    blocked-with-extraAllowed MAC OUT of the ``@blocked_macs`` set into per-MAC
    forward-chain rules that carry the ``ip daddr != @ea_<mac>_<host>`` carve-out
    (the set cannot hold an ``ip daddr`` predicate). So such a MAC is
    deliberately ABSENT from ``blocked_macs`` while still fully blocked — asserting
    set membership there would never succeed (the #1360 G2 false-failure).

    We look for a forward-chain drop rule scoped to this MAC whose reason is a
    ``MacBlockReason`` (Paused / Schedule / TimeLimit / Manual / generic
    ``blocked``). render.lua tags every drop with ``comment "wh_drop:<mac>:<reason>"``
    (#1122); destination-scoped reasons like ``host``, ``category:<id>``,
    ``global_block`` (#1319/#1460), and ``ip_only`` ALSO use the
    ``wh_drop:<mac>:`` prefix but they drop only a specific daddr subset, not
    the whole MAC. Filter on the reason suffix so the check stays a true
    whole-MAC observable.
    """
    res = router_ssh(
        "nft list table inet wifihaven 2>/dev/null || true",
        check=False, timeout=10,
    )
    out = (res.stdout or "").lower()
    prefix = f"wh_drop:{norm_mac(mac)}:"
    # MacBlockReason cases — see shared/.../BlockReason.scala. Lowercased to
    # match the lowercased nft dump above.
    whole_mac_reasons = ("paused", "schedule", "timelimit", "manual", "blocked")
    return any((prefix + r) in out for r in whole_mac_reasons)


def wait_mac_drop_rule_present(mac: str, *, timeout_s: float = 180) -> None:
    wait_until(
        lambda: True if mac_drop_rule_present(mac) else None,
        timeout_s=timeout_s,
        interval_s=2,
        description=f"per-MAC forward-chain drop rule (wh_drop:{norm_mac(mac)}) present",
    )


def is_block_page_body(body: str | None) -> bool:
    b = (body or "").lower()
    return ("wifihaven" in b) or ("blocked" in b)


def wait_block_page(client, *, host: str = "example.com", timeout_s: float = 90):
    # No `ipv4_only` knob (cf. wait_http_succeeds): the block-path DNAT/redirect
    # serves the block page in BOTH families (v4 DNAT → 127.0.0.1, v6 redirect →
    # br-lan), so whichever address curl's happy-eyeballs picks for a dual-stack
    # host still lands on the block page. Only the ALLOW path is family-sensitive
    # — it must hit the same family whose ea_/ea6_ set the test populated (#1929).
    def probe():
        p = http_get(client, f"http://{host}/", timeout_s=8)
        if p.http_code == 200 and is_block_page_body(p.body):
            return p
        return None
    return wait_until(
        probe, timeout_s=timeout_s, interval_s=3,
        description=f"block page response for {host}",
    )


def wait_http_succeeds(
    client, *, host: str = "example.com", timeout_s: float = 60, ipv4_only: bool = False
):
    """Probe HTTP success, disambiguating block-page (also 200) by body.

    `ipv4_only=True` pins the probe to IPv4 (curl `-4`) so it exercises the same
    address family the caller populated/asserted in the kernel sets — see
    http_get for the dual-stack happy-eyeballs trap (#1929).
    """
    def probe():
        p = http_get(client, f"http://{host}/", timeout_s=8, ipv4_only=ipv4_only)
        if p.http_code is not None and 200 <= p.http_code < 400:
            if is_block_page_body(p.body):
                return None
            return p
        return None
    return wait_until(
        probe, timeout_s=timeout_s, interval_s=3,
        description=f"allowed HTTP response for {host}",
    )


_IPV4 = re.compile(r"^\d+\.\d+\.\d+\.\d+$")
# Loose v6 literal recognizer: ≥2 colons, only hex/colons. Sufficient to
# filter `dig +short AAAA` output, where the answer lines are always
# canonical addresses (no zone-ids, no brackets).
_IPV6 = re.compile(r"^[0-9a-fA-F:]+$")


def dig_ipv4_answers(client, host: str) -> list[str]:
    res = dns_query(client, host, timeout_s=5)
    lines = [l.strip() for l in (res.stdout or "").splitlines() if l.strip()]
    return [l for l in lines if _IPV4.match(l)]


# ── External-DNS-egress health (#2034, Mode 2 of #2033) ──────────────────────
#
# Scenarios that resolve the REAL wifihaven.net zone (e.g. the #1351 CNAME-chain
# suite) go client → router dnsmasq → upstream (WH_ROUTER_LAN_RESOLVER) → ISP →
# Cloudflare. On the shared CD host that egress is intermittently degraded
# (#1935/#2034): a whole resolution window returns nothing and the suite reddens
# even though the enforcement path is sound. A degraded-egress window must NOT be
# confused with a genuine zone/terraform regression (our e2e records missing),
# so callers distinguish the two with a control probe through the SAME
# client→router→upstream path:
#   * control hosts resolve, our records don't → real zone problem → fail
#   * control hosts also fail to resolve       → egress degraded   → skip
# These controls are stable third-party apexes that do NOT depend on
# infra/cloudflare; they answer whenever upstream egress is healthy. Single-
# sourced in lib.wan_health so the router-side WAN warm-up probe uses the same
# set (#2390).
EGRESS_CONTROL_HOSTS = CONTROL_APEX_HOSTS


def dns_egress_degraded(client) -> bool:
    """True iff NONE of the third-party control apexes resolve through the
    router's upstream — i.e. external DNS egress is down right now, independent
    of our e2e zone. False as soon as any control resolves (egress is healthy;
    a failure to resolve OUR records is then a real zone/terraform problem)."""
    return not any(dig_ipv4_answers(client, h) for h in EGRESS_CONTROL_HOSTS)


def dig_ipv6_answers(client, host: str) -> list[str]:
    """Return the IPv6 (AAAA) answer lines from `dig +short AAAA <host>`.

    Counterpart to `dig_ipv4_answers`. Used by the v6 attribution Gate 2
    scenario (#1677) to populate the dnsmasq → dns-tail → dns_log cache
    with the (AAAA, hostname) mapping the router needs to attribute a v6
    flow to its FQDN.
    """
    res = client_exec(
        client,
        ["dig", "+time=2", "+tries=1", "+short", "AAAA", host],
        timeout=10,
        check=False,
    )
    lines = [l.strip() for l in (res.stdout or "").splitlines() if l.strip()]
    return [l for l in lines if _IPV6.match(l) and l.count(":") >= 2]


def eb_set_name(host: str) -> str:
    """render.lua::sanitize(): `[.:]` → `_`. See openwrt/files/usr/lib/lua/wifihaven/render.lua."""
    return "eb_" + host.replace(".", "_").replace(":", "_")


def bl_set_name(bl_id: str) -> str:
    """render.lua::bl_sanitize(): `[.:-\\s]` → `_`.

    Category-blocklist drop-sets are named `bl_<sanitized_id>` where
    bl_sanitize replaces dots, colons, hyphens, and whitespace with
    underscores. See render.lua `bl_sanitize()`.

    Example: id="e2e-cname-bl" → "bl_e2e_cname_bl"
    """
    sanitized = re.sub(r"[.\:\-\s]", "_", bl_id)
    return "bl_" + sanitized


def wait_eb_set_exists(host: str, *, timeout_s: float = 120) -> None:
    """Wait until the router has APPLIED a snapshot that extraBlocks `host`.

    #2642. This is an apply barrier, and it exists because
    `fake_api.wait_for_etag_served` is only a DELIVERY one: since ws became the
    shipped transport (#2608) the fake records a push the instant it sends the
    frame, so the helper returns while the router is still seconds away from
    rendering the snapshot into nftables. Any scenario that must generate
    traffic which the NEW policy has to be live for — not merely assert on
    router state afterwards — has to wait for the apply in between.

    eb_ is the sharp case, because its only populator is dns-tail at resolve
    time: a client DNS lookup issued before the apply is ingested while the
    populator still knows nothing about `host` (`eb_adds=0`), and dnsmasq then
    serves the same answer from cache, so no second log line ever gives dns-tail
    another chance. The set stays empty, no DNAT fires, and the block page never
    arrives. (bl_ survives the same race only because blocklists.lua
    re-populates it on its own periodic cadence.)

    Set EXISTENCE is the observable rather than membership: render.lua declares
    `set eb_<host> {}` for each effective extraBlocked host, so the set appears
    at apply and is absent before it — whereas membership is what the scenario
    is trying to prove and cannot be waited on up front.
    """
    name = eb_set_name(host)
    wait_until(
        lambda: router_nft_set_exists(name) or None,
        timeout_s=timeout_s, interval_s=2,
        description=f"nft set {name} to exist (router applied the extraBlocked snapshot)",
    )


def wait_eb_set_populated(host: str, *, timeout_s: float = 90) -> list[str]:
    name = eb_set_name(host)
    def probe():
        elems = router_nft_set(name)
        return elems if elems else None
    return wait_until(
        probe, timeout_s=timeout_s, interval_s=3,
        description=f"nft set {name} to gain at least one element",
    )


def wait_bl_set_populated(bl_id: str, *, timeout_s: float = 90) -> list[str]:
    """Wait until the nft bl_ category-drop-set for a blocklist id has at
    least one element.

    This is the router-state side of the bl_ category-blocklist assertion — it
    confirms dns-tail's bl_ populator (added by #1348/#1350) added a
    directly-queried CNAME-target's IP to the category drop-set. The primary
    assertion in G4 is still traffic-level (wait_block_page), but this check
    surfaces the specific sub-component that failed when the traffic probe
    catches a regression. Mirrors wait_eb_set_populated.

    bl_set_name(id) → render.lua::bl_sanitize convention.
    """
    name = bl_set_name(bl_id)

    def probe():
        elems = router_nft_set(name)
        return elems if elems else None

    return wait_until(
        probe, timeout_s=timeout_s, interval_s=3,
        description=f"nft set {name} to gain at least one element",
    )


# ── #1319 global policy sets (@global_allow / @global_block) ─────────────────
#
# Fleet-wide ipsets render.lua declares when the snapshot carries a populated
# `global` section. Unlike the per-(MAC, host) `ea_<mac>_<host>` sets, these
# are NOT keyed by MAC — one `global_allow` / `global_block` set applies to
# every MAC. Populated at DNS resolve time by dnsmasq `nftset=` callbacks, same
# as eb_/bl_. See render.lua GLOBAL_ALLOW4 / GLOBAL_BLOCK4.

GLOBAL_ALLOW_SET = "global_allow"
GLOBAL_BLOCK_SET = "global_block"


def _wait_set_populated(name: str, *, timeout_s: float = 90) -> list[str]:
    def probe():
        elems = router_nft_set(name)
        return elems if elems else None
    return wait_until(
        probe, timeout_s=timeout_s, interval_s=3,
        description=f"nft set {name} to gain at least one element",
    )


def wait_global_allow_populated(*, timeout_s: float = 90) -> list[str]:
    """Wait until the fleet-wide @global_allow v4 set has >=1 resolved IP."""
    return _wait_set_populated(GLOBAL_ALLOW_SET, timeout_s=timeout_s)


def wait_global_block_populated(*, timeout_s: float = 90) -> list[str]:
    """Wait until the fleet-wide @global_block v4 set has >=1 resolved IP."""
    return _wait_set_populated(GLOBAL_BLOCK_SET, timeout_s=timeout_s)


def ea_set_exists_for_mac(mac: str) -> bool:
    """True iff any per-(MAC, host) ea_<mac>_<host> allow-set has been rendered.

    Used to prove the global allow carve-out is genuinely *global* (a single
    @global_allow set), not silently degraded into per-MAC ea_ sets. render.lua
    names ea_ sets `ea_<sanmac>_<sanhost>`; a `nft list table` scan for the
    `ea_<sanmac>_` prefix tells us whether the MAC got a per-MAC allow path.
    """
    prefix = "ea_" + _san(mac) + "_"
    res = router_ssh(
        "nft list table inet wifihaven 2>/dev/null || true",
        check=False, timeout=10,
    )
    return prefix.lower() in (res.stdout or "").lower()


def wait_event_for_mac(fake_api, mac: str, *, allowed: bool, reason: str,
                       timeout_s: float = 60):
    """Block until fake captured a connection_attempt event matching predicates."""
    def probe():
        evs = fake_api.events_for_mac(mac, allowed=allowed, reason=reason)
        return evs or None
    return wait_until(
        probe, timeout_s=timeout_s, interval_s=2,
        description=f"fake event allowed={allowed} reason={reason!r} for {mac}",
    )


def _san(s: str) -> str:
    """render.lua::sanitize(): `[.:]` → `_`."""
    return s.replace(".", "_").replace(":", "_")


def ea_set_name(mac: str, host: str) -> str:
    """Return the nft set name for an ea_ allow-set.

    render.lua names these `ea_<sanmac>_<sanhost>` where sanmac and sanhost
    are the sanitized (dots/colons→underscores) forms. The mac is the full
    colon-separated form as seen in the snapshot.

    Example: mac=aa:bb:cc:11:22:33, host=khanacademy.org
             → ea_aa_bb_cc_11_22_33_khanacademy_org
    """
    return "ea_" + _san(mac) + "_" + _san(host)


def wait_ea_set_populated(mac: str, host: str, *, timeout_s: float = 90) -> list[str]:
    """Wait until the nft ea_ allow-set for (mac, host) has at least one element.

    This is the router-state side of the ea_ assertion — it confirms the
    dns-tail sidecar populated the kernel carve-out for a directly-queried
    CNAME target. The primary assertion is still traffic-level (wait_http_succeeds
    / wait_block_page), but the set membership check surfaces the specific
    sub-component that failed when the traffic probe catches a regression.
    """
    name = ea_set_name(mac, host)

    def probe():
        elems = router_nft_set(name)
        return elems if elems else None

    return wait_until(
        probe, timeout_s=timeout_s, interval_s=3,
        description=f"nft set {name} to gain at least one element",
    )


def sni_cache_rows() -> list[tuple[str, str]]:
    """Return (ip, hostname) rows from the router's shared dns→host cache.

    The cache file (paths.dns_cache = /tmp/wifihaven-dns-cache.txt) is written
    by wifihaven-dns-tail in the format `<ip>\\t<hostname>\\t<ts>` per entry. For
    SNI-derived attribution (#573) the sidecar feeds `(dst_ip, sni)` tuples
    through cache.insert_sni → the SAME single-writer cache, so an SNI-only
    hostname (one never DNS-resolved) appears here exactly as a DNS-derived one
    would. Ignores the trailing timestamp column.
    """
    res = router_ssh(
        "cat /tmp/wifihaven-dns-cache.txt 2>/dev/null || true",
        check=False, timeout=10,
    )
    rows: list[tuple[str, str]] = []
    for line in (res.stdout or "").splitlines():
        parts = line.split("\t")
        if len(parts) >= 2 and parts[0].strip() and parts[1].strip():
            rows.append((parts[0].strip(), parts[1].strip()))
    return rows


def sni_spool_lines() -> list[str]:
    """Return raw lines from the router's SNI capture spool.

    paths.sni_capture = /tmp/wifihaven-sni.log — the wifihaven-sni-tail sidecar
    appends one `SNI\\t<dst_ip>\\t<src_mac>\\t<server_name>` line per parsed
    ClientHello. Used as a secondary diagnostic: if a row is in the spool but
    not yet in the dns-cache, the failure is in dns-tail's SNI ingest, not in
    the sidecar's capture/parse.
    """
    res = router_ssh(
        "cat /tmp/wifihaven-sni.log 2>/dev/null || true",
        check=False, timeout=10,
    )
    return [l for l in (res.stdout or "").splitlines() if l.strip()]


def wait_sni_attributed(ip: str, host: str, *, timeout_s: float = 120) -> tuple[str, str]:
    """Block until the router's shared dns→host cache maps `ip → host` (#573).

    This is THE proof that SNI is the sole attribution source: `host` is a name
    the client never DNS-resolves, so the only way it can reach the router's
    attribution cache is via the TLS ClientHello captured by wifihaven-sni-tail,
    parsed in sni.lua, and routed through cache.insert_sni by wifihaven-dns-tail.
    A DNS-derived entry for `host` is impossible because no `reply <host> is <ip>`
    line ever existed.

    Returns the matched (ip, hostname) cache row. Raises TimeoutError (surfacing
    the current cache rows + SNI spool tail) if the mapping never appears.
    """
    want_ip = ip.strip()
    want_host = host.strip().lower()

    def probe():
        for cip, chost in sni_cache_rows():
            if cip == want_ip and chost.lower() == want_host:
                return (cip, chost)
        return None

    try:
        return wait_until(
            probe, timeout_s=timeout_s, interval_s=3,
            description=(
                f"dns-cache row {want_ip} -> {host} attributed via SNI "
                f"(host never DNS-resolved, so SNI is the only possible source)"
            ),
        )
    except TimeoutError:
        # Secondary diagnostic: did the sidecar capture the ClientHello at all?
        spool = sni_spool_lines()
        rows = sni_cache_rows()
        raise TimeoutError(
            f"SNI attribution {want_ip} -> {host} never landed in the dns-cache "
            f"within {timeout_s}s.\n"
            f"  dns-cache rows ({len(rows)}): {rows!r}\n"
            f"  sni spool lines ({len(spool)}): {spool[-10:]!r}\n"
            f"  (spool non-empty + cache miss => dns-tail SNI ingest broke; "
            f"spool empty => sidecar capture/parse broke or no ClientHello crossed br-lan)"
        )


def wait_event_with_host_attribution(
    fake_api,
    mac: str,
    *,
    branded_host: str,
    timeout_s: float = 120,
) -> dict:
    """Wait until the fake has a connection_event whose host.value contains
    the branded hostname (not the raw CDN/leaf target).

    Used to assert #1344/#1345 attribution: after a direct re-query of a
    CNAME target, the event posted to /api/router/events must record the
    branded ancestor, not the CDN hostname.
    """
    def probe():
        for ev in fake_api.events_for_mac(mac):
            host_field = ev.get("host") or {}
            hval = host_field.get("value") or ""
            if branded_host.lower() in hval.lower():
                return ev
        return None

    return wait_until(
        probe, timeout_s=timeout_s, interval_s=3,
        description=(
            f"connection_event for {mac} with host attribution containing "
            f"{branded_host!r}"
        ),
    )
