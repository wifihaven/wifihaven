"""Suite I — SNI-only hostname attribution on a real router VM (#573).

PR #1643 adds a `wifihaven-sni-tail` procd sidecar that captures the first
packet of every outbound TCP/443 flow on br-lan via tcpdump-mini, parses the
TLS ClientHello SNI in `sni.lua`, and feeds `(dst_ip, server_name)` tuples
through `cache.insert_sni` into the SHARED dns→host attribution cache
(paths.dns_cache = /tmp/wifihaven-dns-cache.txt) — the same single-writer cache
the dnsmasq query log populates. The Lua busted suite (openwrt/test/sni_spec.lua,
dns_log_spec.lua) covers the parser + cache helper in isolation, and the
in-process integration test wires pcap → cache; THIS suite proves the FULL
pipeline on a real router VM driving real br-lan traffic:

    client → TLS ClientHello (curl --resolve, no DNS lookup)
           → br-lan
           → wifihaven-sni-tail (tcpdump-mini capture + sni.lua parse)
           → /tmp/wifihaven-sni.log spool
           → wifihaven-dns-tail (cache.insert_sni)
           → /tmp/wifihaven-dns-cache.txt  (dst_ip → server_name)

Failure mode this guards (the IP-vs-hostname collateral class, #1636 / #1640,
sibling to the CNAME-direct-requery attribution bugs #1348/#1344): when a client
bypasses the on-router DNS resolver (DoH) or hard-codes a destination IP, the
DNS attribution path never sees the queried hostname, so connection_event
labeling and per-host classification fall back to the raw IP — mis-attributing
the flow (and, for a shared CDN IP, mislabeling collateral hosts). SNI v1 closes
that gap for TLS/443 by recovering the hostname off the ClientHello.

ISOLATION — why SNI is provably the SOLE attribution source here:
  SNI_HOST is `e2e-sni.wifihaven.net`, a name the client NEVER `dig`s. dnsmasq
  therefore logs zero `reply e2e-sni.wifihaven.net is <ip>` lines for it, so the
  DNS attribution path CANNOT know it and no DNS-derived dns-cache row for it can
  ever exist. The only way that name reaches the router's attribution cache is
  the captured ClientHello. So a dns-cache row `<router-lan-ip> → e2e-sni…` is
  an unambiguous proof of the SNI pipeline — there is no DNS confound.

CAPTURABLE-CLIENTHELLO REQUIREMENT — why we dial the router's OWN LAN IP:
  The TLS ClientHello is sent immediately AFTER the TCP handshake completes. An
  unroutable / unreachable dst never completes the handshake, so no ClientHello
  is ever emitted and nothing is captured (this is exactly why the Suite G
  CNAME allow-path xfails against the RFC 5737 TEST-NET leaf — see
  test_cname_direct_requery._xfail_if_leaf_unreachable / #1360). To guarantee a
  real handshake on br-lan WITHOUT depending on any external origin, we stand up
  a throwaway TCP listener on 443 on the ROUTER ITSELF (its br-lan IP) and pin
  the client's connection there with `curl --resolve`. The TLS handshake need
  not succeed — the listener speaks no TLS — but the ClientHello has already
  left the client and crossed br-lan, which is all the sidecar needs.

ASSERTIONS:
  Primary (hard, deterministic, origin-independent): the router's shared
  dns-cache maps <ROUTER_LAN_IP> → SNI_HOST. This needs only that the
  ClientHello crossed br-lan — no reachable forwarded ORIGIN, no successful
  TLS — so it stays HARD-gating even though the listener answers no TLS.

  Secondary (best-effort, xfail-guarded per the #1360 harness gap): the
  forwarded connection_event attribution (wait_event_with_host_attribution)
  would prove conntrack.lua labels the flow with the SNI-derived host. But the
  fake harness has NO forwarded reachable 443 origin to drive a labeled
  connection_event for this synthetic name (the same missing-reachable-origin
  gap #1360 documents for the CNAME allow-path), and the flow here targets the
  router's own LAN IP (not a forwarded destination), so this path is NOT
  exercised. It is intentionally skipped, not red-gated — see the inline note.
"""
from __future__ import annotations

import pytest

from ._observers import (
    sni_cache_rows,
    sni_spool_lines,
    wait_sni_attributed,
)
from .snapshot_builder import SnapshotBuilder
from lib.traffic import tls_clienthello
from lib.vm import router_ssh
from lib.wait import wait_until

pytestmark = pytest.mark.sni_attribution

# A name the client NEVER resolves — guarantees DNS cannot be the attribution
# source, so a dns-cache row for it isolates the SNI pipeline (see module docstring).
SNI_HOST = "e2e-sni.wifihaven.net"

# Default router br-lan IP (scripts/vm/config.sh WH_LAN_ROUTER_IP /
# uci-defaults/99-wifihaven network.lan.ipaddr). Resolved live from the router
# at test time via uci, falling back to this constant.
_DEFAULT_ROUTER_LAN_IP = "192.168.100.1"

PID = 730


def _router_lan_ip() -> str:
    """The router's br-lan IP — the client's gateway, reachable on br-lan so the
    TLS handshake completes and the ClientHello is emitted + captured."""
    res = router_ssh("uci -q get network.lan.ipaddr || true", check=False, timeout=10)
    ip = (res.stdout or "").strip()
    return ip or _DEFAULT_ROUTER_LAN_IP


# Exact arg string used to launch the throwaway responder, so teardown can
# pgrep it precisely without matching the block-page uhttpd instance.
_LISTENER_CMD = "uhttpd -h /tmp -p 0.0.0.0:443"


def _port_443_listening() -> bool:
    """True iff something now accepts TCP connections on the router's :443."""
    res = router_ssh(
        "netstat -tln 2>/dev/null | grep -q ':443 ' && echo yes || true",
        check=False, timeout=10,
    )
    return "yes" in (res.stdout or "")


def _start_router_tcp443_listener():
    """Stand up a throwaway TCP/443 responder on the router so the client's TLS
    handshake to <ROUTER_LAN_IP>:443 completes and the ClientHello is emitted.

    The responder need not speak TLS — it only has to ACCEPT the TCP connection
    so the client proceeds to send the ClientHello (the first application-data
    packet after the TCP handshake), which is what sni-tail captures on br-lan.

    Why uhttpd and not nc: OpenWRT's busybox `nc` is the CLIENT-ONLY applet
    (`Usage: nc [IPADDR PORT]` — no `-l`/server mode compiled in), so an
    `nc -l -p 443` listener never binds. With no other 443 responder in the
    minimal image (uhttpd hosts the block page on loopback:8081 only), the
    client's SYN is refused, no ClientHello is ever emitted, and the sidecar
    correctly captures nothing — which is exactly how this scenario first went
    red. `uhttpd` IS in the image (it is the block-page dependency) and does
    listen, so we run a throwaway standalone instance on 0.0.0.0:443. It speaks
    plain HTTP, not TLS, but that is irrelevant here: accepting the TCP
    connection is all that is needed for the ClientHello to cross br-lan.
    """
    router_ssh(f"{_LISTENER_CMD} >/dev/null 2>&1 &", check=False, timeout=10)
    # Surface a listener-start failure HERE (clear message) rather than letting
    # it masquerade as a downstream attribution timeout.
    wait_until(
        lambda: True if _port_443_listening() else None,
        timeout_s=20,
        interval_s=2,
        description=(
            "a TCP/443 responder (throwaway uhttpd) to bind on the router so "
            "the client's TLS ClientHello is actually emitted onto br-lan"
        ),
    )


def _stop_router_tcp443_listener():
    """Tear down the throwaway responder so it doesn't leak into the next
    scenario (the function-scoped `router` fixture restores the VM snapshot, but
    the responder is started post-restore in-test, so clean it up explicitly).
    Targets only our instance's arg string, never the block-page uhttpd."""
    router_ssh(
        f"kill $(pgrep -f '{_LISTENER_CMD}') 2>/dev/null; true",
        check=False, timeout=10,
    )


def test_sni_only_hostname_attribution(router, client, fake_api):
    """I1: a TLS ClientHello for a name the client never DNS-resolves is captured
    by the sni-tail sidecar and attributed in the shared dns-cache.

    Primary assertion (hard, origin-independent): the router's dns→host cache
    maps <ROUTER_LAN_IP> → e2e-sni.wifihaven.net. Because e2e-sni.wifihaven.net
    is never `dig`'d, no DNS-derived row for it can exist — so this row proves
    the SNI pipeline end-to-end (client ClientHello → br-lan → tcpdump-mini →
    sni.lua → cache.insert_sni → dns-cache). A failure here means either the
    sidecar didn't capture/parse the ClientHello or dns-tail didn't ingest the
    SNI spool line; the observer's TimeoutError prints both the cache rows and
    the SNI spool tail to localize which.
    """
    # A plain profile + this device. SNI attribution needs no DNS/blocklist/block
    # state — it is attribution-only (PR #1643 review SHOULD-FIX #2: SNI improves
    # cache.lookup / connection_event labeling; it does NOT add IPs to eb_/bl_
    # drop sets), so we deliberately exercise it with nothing else in the way.
    snap = (
        SnapshotBuilder()
        .add_profile(id=PID, name="e2e-sni-attr")
        .add_device(mac=client.mac, name="e2e-sni-attr-dev", profile_id=PID)
        .build(etag='"sha256:sni-attr-v1"')
    )
    etag = fake_api.serve_snapshot(snap)
    fake_api.wait_for_etag_served(etag=etag, timeout_s=240)

    router_lan_ip = _router_lan_ip()

    # Sanity precondition: e2e-sni.wifihaven.net must NOT already be in the
    # dns-cache before we emit the ClientHello. If it were, the isolation
    # premise is broken and the primary assertion would be meaningless.
    pre_rows = sni_cache_rows()
    assert not any(
        h.lower() == SNI_HOST.lower() for _ip, h in pre_rows
    ), (
        f"{SNI_HOST} unexpectedly already present in the dns-cache before any "
        f"ClientHello — isolation premise broken (it must never be DNS-resolved). "
        f"rows={pre_rows!r}"
    )

    _start_router_tcp443_listener()
    try:
        # Emit the ClientHello WITHOUT a DNS lookup for SNI_HOST, pinned to the
        # router's own LAN IP so the TCP handshake completes on br-lan and the
        # ClientHello is captured. Fire it a few times: the sidecar's BPF only
        # captures the first packet of a NEW flow, and a single curl could race
        # the sidecar's tcpdump warm-up on a freshly-restored VM.
        for _ in range(3):
            tls_clienthello(client, SNI_HOST, router_lan_ip, timeout_s=5)

        # Keep emitting in the background while we poll, so the attribution has
        # multiple chances to be captured even if tcpdump warmed up late.
        def _attributed():
            try:
                return wait_sni_attributed(router_lan_ip, SNI_HOST, timeout_s=15)
            except TimeoutError:
                tls_clienthello(client, SNI_HOST, router_lan_ip, timeout_s=5)
                return None

        row = wait_until(
            _attributed,
            timeout_s=150,
            interval_s=1,
            description=(
                f"dns-cache row {router_lan_ip} -> {SNI_HOST} attributed via SNI "
                f"(host never DNS-resolved => SNI is the only possible source)"
            ),
        )
        assert row == (router_lan_ip, SNI_HOST) or row[1].lower() == SNI_HOST.lower(), (
            f"expected dns-cache attribution {router_lan_ip} -> {SNI_HOST}, "
            f"got {row!r}. SNI spool tail: {sni_spool_lines()[-10:]!r}"
        )
    finally:
        _stop_router_tcp443_listener()

    # Secondary / best-effort (NOT exercised here; intentionally skipped, not
    # red-gated). Proving conntrack.lua emits a connection_event whose host is
    # the SNI-derived name would require a FORWARDED reachable TCP/443 origin so
    # a labeled forwarded flow exists — the fake harness has none (the same
    # missing-reachable-origin gap #1360 documents for the Suite G CNAME
    # allow-path), and this scenario deliberately targets the router's OWN LAN
    # IP (not a forwarded destination) to guarantee a capturable ClientHello.
    # The primary dns-cache assertion above already proves the SNI pipeline
    # populates the attribution cache; the connection_event consumer reading
    # that cache is covered by the conntrack/dns_log unit + the existing
    # CNAME-attribution Gate 2 scenario (G3). A future harness-served forwarded
    # 443 origin (the #1360 follow-up) would let this run for real.
    pytest.skip(
        "forwarded connection_event SNI labeling needs a reachable forwarded "
        "443 origin the fake harness lacks (#1360); primary dns-cache "
        "attribution above is the hard proof of the SNI pipeline."
    )
