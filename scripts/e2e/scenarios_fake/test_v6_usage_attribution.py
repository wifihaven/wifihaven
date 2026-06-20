"""Gate 2 IPv6 per-device *usage* attribution (#1804, e2e gate for #1796/#1802).

#1796 shipped a SILENT prod gap: the usage byte-accounting nftables plane was
IPv4-only — `mac_ip_tracking` / `ip_pair_tracking_rx` were typed `ipv4_addr`
and their rules matched `ip daddr`, so every IPv6 destination flow was never
counted and never surfaced in per-device traffic / recent-apexes. A device that
Happy-Eyeballs'd onto IPv6 for a dual-stack host showed *nothing*; the same host
over IPv4 showed up. It went undetected for months because no e2e test drove
IPv6 traffic and asserted it surfaces as per-device usage.

PR #1802 fixed it (render.lua `mac_ip6_tracking` / `ip_pair6_tracking_rx` sets
+ `ip6 daddr` accounting rules; the agent reads both families and resolves v6
rx via the NDP neighbor cache through `conntrack.parse_arp_table`'s `ip -6
neigh` read), and PR #1807 fixed v6 LAN-source classification in
`conntrack.is_wan_bound` (NDP table, not `lan_prefix_v6`). This file is the
e2e layer the busted unit tests (`render_spec` v6 sets/rules, `usage_spec` v6
round-trip) cannot provide: real `nft` counters, real `nft -j` v6 formatting,
real `ip -6 neigh` resolution, and a real round-trip through the router's
FORWARD chain.

What this exercises end-to-end (all four #1804 acceptance points in one VM
restore, to stay inside the Gate-2 wall-time budget):

  1. v6 usage round-trip — v6 download+upload to a dual-stack host surfaces by
     FQDN with non-zero bytesIn/bytesOut.
  3. v6 rx (download) resolves to the right MAC via the NDP neighbor cache
     (`ip_pair6_tracking_rx` → `load_ip_to_mac` → `ip -6 neigh`).
  4. Regression guard — a host reached ONLY over IPv6 is NOT invisible (the
     exact #1796 symptom): the assertions in 1/3 run BEFORE any v4 traffic, so
     the row is proven to exist on v6 bytes alone.
  2. v4+v6 aggregation — after adding v4 traffic to the SAME host, there is
     still exactly ONE host row for (mac, host) (no family-split duplication,
     no v6 silently dropped).

── Why a router-side route + LAN origin, not the real Internet ──────────────

The qemu SLIRP WAN is v4-only (see scripts/vm/uci-defaults/99-wifihaven), so
there is no upstream IPv6 path: a v6 SYN to a public address crosses the
FORWARD chain (enough for the connection_events attribution gate,
test_v6_attribution.py) but never gets a response, so it can never produce
download (rx) bytes. To get a *real* bidirectional v6 flow that the router
forwards (and therefore counts), we stand up a throwaway HTTP origin as a
SECOND L2 host on the LAN segment — a macvlan interface in its own network
namespace on the client VM — owning the e2e-edge doc-space addresses
(2001:db8::10 / 192.0.2.10). The router is given an on-link route for those
addresses out br-lan; the origin routes its replies back VIA the router. So
both directions traverse the router's FORWARD chain:

    client → router → origin     (tx: iifname br-lan, ip6 daddr)  → bytesOut
    origin → router → client     (rx: oifname br-lan, ip6 saddr)  → bytesIn

Attribution stays production-shape: e2e-edge.wifihaven.net is resolved through
the router's dnsmasq against the REAL Cloudflare records (A 192.0.2.10 /
AAAA 2001:db8::10, infra/cloudflare/main.tf, #1351/#1677), so the agent learns
the (ip → FQDN) mapping from a genuine forwarded `reply` log line exactly as it
would in prod — only the packets' final hop is a local origin instead of a dead
SLIRP route.

TERRAFORM PREREQUISITE: infra/cloudflare/main.tf must be applied so the A+AAAA
on e2e-edge.wifihaven.net resolve. CI applies on merge to main
(master-cloudflare.yml). Until propagation completes, the resolution guards
below fail with a pointer to the terraform-apply expectation, matching the
v4 #1351 / v6 #1677 pattern.
"""
from __future__ import annotations

import re

import pytest

from ._observers import dig_ipv4_answers, dig_ipv6_answers
from .snapshot_builder import SnapshotBuilder
from lib.traffic import http_get
from lib.vm import client_exec, router_ssh
from lib.wait import wait_until

pytestmark = pytest.mark.v6_usage_attribution

# ── Fixture (Cloudflare wifihaven.net zone) ──────────────────────────────────
# Reuse the dual-stack leaf the v6 attribution gate (#1677) already manages.
LEAF_HOST = "e2e-edge.wifihaven.net"
LEAF_IP6 = "2001:db8::10"   # RFC 3849 documentation address (AAAA)
LEAF_IP4 = "192.0.2.10"     # RFC 5737 TEST-NET-1 address (A)

PROFILE_ID = 1804

# A throwaway origin large enough that the download dominates the upload, so
# the bytesIn assertion can't be satisfied by stray ACK/retransmit noise.
_ORIGIN_BODY_BYTES = 4096

# All origin-side state lives under this netns so teardown is a single delete
# (the function-scoped router/client fixtures also revert, but we clean up
# explicitly — the origin is stood up post-restore, in-test).
_NS = "whsrv"
_MACVLAN = "whsrv0"

# A self-contained dual-family HTTP responder. socat is in the client base
# image (scripts/vm/build-client-base.sh); BusyBox `nc` on Alpine is
# client-only so it cannot serve. We stage the reply as a STANDALONE script
# (`socat ... EXEC:<path>`) rather than an inline `SYSTEM:'...'` so there is
# zero nested-quoting across the SSH + `sh -c` + `ip netns exec` layers — the
# script emits HTTP headers + a fixed `Content-Length` body to the socket
# (its stdout) and ignores the request bytes on stdin (a GET has no body).
_REPLY_SCRIPT = "/tmp/whsrv-reply.sh"
_REPLY_BODY = "\n".join([
    f"cat > {_REPLY_SCRIPT} <<'EOF'",
    "#!/bin/sh",
    "printf 'HTTP/1.0 200 OK\\r\\n'",
    "printf 'Content-Type: text/plain\\r\\n'",
    f"printf 'Content-Length: {_ORIGIN_BODY_BYTES}\\r\\n'",
    "printf 'Connection: close\\r\\n\\r\\n'",
    f"yes x | head -c {_ORIGIN_BODY_BYTES}",
    "EOF",
    f"chmod +x {_REPLY_SCRIPT}",
])


# ── origin setup / teardown (client VM) ──────────────────────────────────────


def _router_lan_gateways(client) -> tuple[str, str]:
    """Discover the router's LAN gateways (v6 link-local + v4) as the client
    sees them, so the origin can route its replies back THROUGH the router
    rather than directly to the client on the shared L2 segment.

    Returns (router_v6_ll, router_v4). Raises with the raw route output if
    either default route is missing — that would mean the v6 LAN bring-up
    (#1680) or DHCP never completed, and the origin could not be wired.
    """
    v6 = client_exec(
        client, ["sh", "-c", "ip -6 route show default 2>/dev/null"],
        timeout=10, check=False,
    ).stdout or ""
    v4 = client_exec(
        client, ["sh", "-c", "ip route show default 2>/dev/null"],
        timeout=10, check=False,
    ).stdout or ""
    m6 = re.search(r"default\s+via\s+(fe80:[0-9a-fA-F:]+)\s+dev", v6)
    m4 = re.search(r"default\s+via\s+(\d+\.\d+\.\d+\.\d+)\s+dev", v4)
    assert m6, f"client has no IPv6 default route via the router (#1680?):\n{v6!r}"
    assert m4, f"client has no IPv4 default route via the router:\n{v4!r}"
    return m6.group(1), m4.group(1)


def _setup_origin(client) -> None:
    """Stand up a throwaway dual-stack HTTP origin owning the e2e-edge
    doc-space addresses on a macvlan in a netns on the client, and route the
    router to it.

    macvlan in `bridge` mode gives the netns its own MAC on eth0's L2 segment,
    so the router resolves the origin addresses via ND/ARP and forwards LAN
    traffic to it — making the client↔origin flow a genuine routed,
    FORWARD-chain-traversing exchange (the only shape the usage counters see).
    The origin routes replies back VIA the router (not directly to the client)
    so the download leg is rx-counted on `oifname br-lan`.
    """
    router_v6_ll, router_v4 = _router_lan_gateways(client)

    # The macvlan module is built `=m` in Alpine's linux-virt kernel; load it
    # explicitly so `ip link add type macvlan` cannot fail for a cold module.
    setup = "\n".join([
        "set -e",
        _REPLY_BODY,
        "modprobe macvlan 2>/dev/null || true",
        f"ip netns add {_NS}",
        f"ip link add {_MACVLAN} link eth0 type macvlan mode bridge",
        f"ip link set {_MACVLAN} netns {_NS}",
        f"ip netns exec {_NS} ip link set lo up",
        f"ip netns exec {_NS} ip link set {_MACVLAN} up",
        f"ip netns exec {_NS} ip -6 addr add {LEAF_IP6}/64 dev {_MACVLAN}",
        f"ip netns exec {_NS} ip addr add {LEAF_IP4}/24 dev {_MACVLAN}",
        # Replies route back via the router (on-link route to the gateway
        # first, since the gateways are outside the origin's own subnets).
        f"ip netns exec {_NS} ip -6 route add default via {router_v6_ll} dev {_MACVLAN}",
        f"ip netns exec {_NS} ip route add {router_v4} dev {_MACVLAN}",
        f"ip netns exec {_NS} ip route add default via {router_v4} dev {_MACVLAN}",
        # Dual-family responder (v6-only + v4 listeners on :80), each EXECing
        # the staged reply script per accepted connection (`fork`).
        f"ip netns exec {_NS} socat TCP6-LISTEN:80,fork,reuseaddr,ipv6-v6only=1 "
        f"EXEC:{_REPLY_SCRIPT} >/tmp/whsrv6.log 2>&1 &",
        f"ip netns exec {_NS} socat TCP4-LISTEN:80,fork,reuseaddr "
        f"EXEC:{_REPLY_SCRIPT} >/tmp/whsrv4.log 2>&1 &",
    ])
    res = client_exec(client, ["sh", "-c", setup], timeout=40, check=False)
    assert res.returncode == 0, (
        f"failed to stand up the v6/v4 origin on the client (iproute2/macvlan/"
        f"socat from scripts/vm/build-client-base.sh?):\n"
        f"stdout={res.stdout!r}\nstderr={res.stderr!r}"
    )

    # Point the router at the origin: on-link routes out br-lan for both
    # doc-space addresses override the bogus v6 default (dev eth1, #1680) and
    # the v4 default (SLIRP), so the router ND/ARPs for them on the LAN and
    # forwards there.
    rt = router_ssh(
        f"ip -6 route replace {LEAF_IP6} dev br-lan && "
        f"ip route replace {LEAF_IP4} dev br-lan",
        check=False, timeout=15,
    )
    assert rt.returncode == 0, (
        f"failed to route the router to the LAN origin:\n"
        f"stdout={rt.stdout!r}\nstderr={rt.stderr!r}"
    )


def _teardown_origin(client) -> None:
    client_exec(
        client,
        ["sh", "-c", f"ip netns pids {_NS} 2>/dev/null | xargs -r kill 2>/dev/null; "
                     f"ip netns del {_NS} 2>/dev/null; true"],
        timeout=20, check=False,
    )


def _wait_origin_reachable(client, *, url: str, family_flag: str,
                           timeout_s: float = 60) -> None:
    """Poll until the client can actually fetch the origin over `family_flag`
    (curl -4/-6). Surfaces an origin/routing misconfig HERE (clear message)
    instead of as a downstream usage-flush timeout."""
    def probe():
        r = client_exec(
            client,
            ["sh", "-c",
             f"curl {family_flag} -s -o /dev/null -w '%{{http_code}}' "
             f"--max-time 5 {url} || true"],
            timeout=12, check=False,
        )
        return True if (r.stdout or "").strip() == "200" else None
    wait_until(
        probe, timeout_s=timeout_s, interval_s=3,
        description=f"client can fetch {url} over {family_flag} via the LAN origin",
    )


# ── usage helpers ────────────────────────────────────────────────────────────


def _set_fast_usage_cadence() -> None:
    """Drop the agent's usage report cadence to 10 s (default 60 s) and restart
    it, so a flush lands within the scenario budget. 10 s is an exact multiple
    of activity_sample_int (10 s) so the even-divisor invariant holds (no
    activeSeconds-accuracy warning; wifihaven-agent:301)."""
    res = router_ssh(
        "uci set wifihaven.@wifihaven[0].usage_report_interval='10' && "
        "uci commit wifihaven && /etc/init.d/wifihaven restart",
        check=False, timeout=30,
    )
    assert res.returncode == 0, (
        f"failed to lower usage_report_interval / restart agent:\n"
        f"stdout={res.stdout!r}\nstderr={res.stderr!r}"
    )


def _usage_row(fake_api, mac: str, host: str) -> dict | None:
    """Return the latest captured usage record for (mac, host), or None.

    /test/usage returns a row per (mac, host) the agent reported; filter to the
    workload host so incidental client traffic (NTP, DNS) can't shadow it
    (#1049)."""
    latest = None
    for r in (fake_api.usage().get("reports") or []):
        for rec in (r.get("body", {}).get("records") or []):
            if (rec.get("mac") or "").lower() != mac.lower():
                continue
            if (rec.get("host") or {}).get("value") == host:
                latest = rec
    return latest


def _wait_usage_row(fake_api, mac: str, host: str, *,
                    predicate, description: str, timeout_s: float = 180) -> dict:
    def probe():
        rec = _usage_row(fake_api, mac, host)
        return rec if (rec is not None and predicate(rec)) else None
    return wait_until(
        probe, timeout_s=timeout_s, interval_s=5, description=description,
    )


def _host_row_count(fake_api, mac: str, host: str) -> int:
    """How many DISTINCT host rows the agent has reported whose value resolves
    to `host` — across every captured report. >1 means a family-split
    duplication (e.g. one row keyed to the bare v6 literal, one to the FQDN),
    which is exactly the #1796-class bug the aggregation check guards against.

    A bare v4/v6 literal for LEAF_IP4/LEAF_IP6 counts as a duplicate of the
    FQDN row, since both denote the same host reached over different families.
    """
    seen: set[str] = set()
    aliases = {host.lower(), LEAF_IP4, LEAF_IP6}
    for r in (fake_api.usage().get("reports") or []):
        for rec in (r.get("body", {}).get("records") or []):
            if (rec.get("mac") or "").lower() != mac.lower():
                continue
            val = (rec.get("host") or {}).get("value") or ""
            if val.lower() in aliases:
                seen.add(val.lower())
    return len(seen)


# ── the test ─────────────────────────────────────────────────────────────────


def test_v6_usage_attributes_to_fqdn_and_aggregates_with_v4(router, client, fake_api):
    snap = (
        SnapshotBuilder()
        .add_profile(id=PROFILE_ID, name="e2e-v6-usage")
        .add_device(mac=client.mac, name="e2e-v6-usage-dev", profile_id=PROFILE_ID)
        .build(etag='"sha256:v6-usage-v1"')
    )

    _set_fast_usage_cadence()
    etag = fake_api.serve_snapshot(snap)
    fake_api.wait_for_etag_served(etag=etag, timeout_s=240)

    _setup_origin(client)
    try:
        # ── resolve the dual-stack leaf so the agent's dns_log cache learns
        # both (ip → FQDN) mappings from real forwarded `reply` lines. ──────
        aaaa = wait_until(
            lambda: dig_ipv6_answers(client, LEAF_HOST) or None,
            timeout_s=120, interval_s=5,
            description=(
                f"dig AAAA {LEAF_HOST} (requires terraform apply for "
                f"infra/cloudflare, #1677)"
            ),
        )
        assert LEAF_IP6 in aaaa, (
            f"expected {LEAF_IP6} in AAAA answers for {LEAF_HOST}, got {aaaa!r} "
            f"— terraform apply for infra/cloudflare/ not propagated (#1677)?"
        )
        a = wait_until(
            lambda: dig_ipv4_answers(client, LEAF_HOST) or None,
            timeout_s=60, interval_s=5,
            description=f"dig A {LEAF_HOST} (requires terraform apply, #1351)",
        )
        assert LEAF_IP4 in a, (
            f"expected {LEAF_IP4} in A answers for {LEAF_HOST}, got {a!r}"
        )

        # ── real-nft sanity: the v6 accounting sets #1802 added must exist in
        # the loaded ruleset. Pre-#1802 they do not (the regression's root
        # cause), so this is a cheap, deterministic #1796 guard independent of
        # the traffic round-trip. ──────────────────────────────────────────
        sets = router_ssh(
            "nft list sets inet wifihaven 2>/dev/null || true",
            check=False, timeout=10,
        ).stdout or ""
        for s in ("mac_ip6_tracking", "ip_pair6_tracking_rx"):
            assert s in sets, (
                f"nft ruleset is missing the v6 accounting set {s!r} — "
                f"pre-#1802 IPv4-only accounting plane regressed?\n{sets!r}"
            )

        url = f"http://{LEAF_HOST}/"
        _wait_origin_reachable(client, url=url, family_flag="-6")

        # ── 1/3/4: drive IPv6 ONLY, then assert the host surfaces by FQDN with
        # non-zero bytesIn (rx via NDP) AND bytesOut. No v4 has flowed yet, so
        # a visible row here is proof a v6-only host is not invisible. ───────
        for _ in range(4):
            client_exec(
                client, ["sh", "-c", f"curl -6 -s -o /dev/null --max-time 6 {url} || true"],
                timeout=12, check=False,
            )

        rec = _wait_usage_row(
            fake_api, client.mac, LEAF_HOST,
            predicate=lambda r: (r.get("bytesIn") or 0) > 0 and (r.get("bytesOut") or 0) > 0,
            description=(
                f"usage row for {client.mac} host={LEAF_HOST} with bytesIn>0 AND "
                f"bytesOut>0 from IPv6-only traffic (pre-#1802 v6 was uncounted)"
            ),
        )
        # host.value must be the FQDN, never the bare v6 literal.
        hval = (rec.get("host") or {}).get("value") or ""
        assert hval == LEAF_HOST, (
            f"v6 usage attributed to {hval!r}, not the FQDN {LEAF_HOST!r} — "
            f"pre-#1802/#1673 bare-literal attribution leak. rec={rec!r}"
        )
        assert (rec.get("bytesIn") or 0) > 0, f"v6 download (rx) not counted: {rec!r}"
        assert (rec.get("bytesOut") or 0) > 0, f"v6 upload (tx) not counted: {rec!r}"
        v6_in, v6_out = rec["bytesIn"], rec["bytesOut"]

        # Exactly one host row from v6-only traffic (no bare-literal sibling).
        assert _host_row_count(fake_api, client.mac, LEAF_HOST) == 1, (
            f"expected a single host row for {LEAF_HOST} after IPv6-only "
            f"traffic, found a family-split/literal duplicate"
        )

        # ── 2: add IPv4 to the SAME host; it must aggregate into the SAME row
        # (no family-split duplication, no v6 silently dropped). ─────────────
        _wait_origin_reachable(client, url=url, family_flag="-4")
        for _ in range(4):
            client_exec(
                client, ["sh", "-c", f"curl -4 -s -o /dev/null --max-time 6 {url} || true"],
                timeout=12, check=False,
            )

        merged = _wait_usage_row(
            fake_api, client.mac, LEAF_HOST,
            predicate=lambda r: (r.get("bytesIn") or 0) > v6_in
                                and (r.get("bytesOut") or 0) > v6_out,
            description=(
                f"usage row for {client.mac} host={LEAF_HOST} growing past the "
                f"v6-only totals after adding IPv4 (v4+v6 aggregate to one row)"
            ),
        )
        assert (merged.get("host") or {}).get("value") == LEAF_HOST
        assert _host_row_count(fake_api, client.mac, LEAF_HOST) == 1, (
            f"v4 and v6 to {LEAF_HOST} must aggregate to ONE host row — found a "
            f"family-split duplicate (the #1796 silent-drop / double-count class)"
        )
    finally:
        _teardown_origin(client)
