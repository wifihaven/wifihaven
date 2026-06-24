"""Suite J — network-wide "block encrypted DNS & relays" toggle (#1911).

VM-level end-to-end proof that a deployed agent enforces the additive top-level
`snapshot.blockEncryptedDns` flag (feature #1909, epic #1903). The busted unit
coverage (`openwrt/test/encrypted_dns_spec.lua`) proves the *render shape*; this
proves the *enforced behaviour against live traffic on a real OpenWRT agent*.

When the toggle is on the agent enforces two separable halves:

  1. NODATA half (dnsmasq): a NEGATIVE DNS answer (`local=/<host>/`) for the
     curated relay/DoH hostnames — Apple's documented clean way to disable
     iCloud Private Relay (a 0.0.0.0 sinkhole does NOT disable it). This is the
     one sanctioned, narrow exception to Architectural Truth #1.
  2. Connection-layer half (nft): drop DoT (TCP/853 any IP) + DNS :53 (udp+tcp)
     to the curated public-resolver IPs ONLY — catches "system DNS hardcoded to
     8.8.8.8" and forces fallback to the LAN resolver.

The two assertions that matter most (per the #1909 blast-radius + port-scope
refinements):
  - **Opportunistic fallback survives** — after the toggle is on, a normal name
    still resolves via the LAN resolver and the network is NOT broken. This is
    the core "did we break the network" assertion.
  - **Connectivity probes survive** — `:443`/ICMP to `1.1.1.1` still works,
    because we port-scope the resolver-IP drop to `:53`. `1.1.1.1`/`8.8.8.8`
    double as the world's most common "am I online?" targets; a blanket drop
    would make such devices believe they are permanently offline.

Reachability here is a connection-layer assertion, never "DNS resolved ⇒
allowed". See docs/architecture.md §0.1 and the AGENTS.md anti-pattern callout.
"""
from __future__ import annotations

import pytest

from lib.vm import client_exec, router_ssh
from lib.wait import wait_until

from ._observers import dig_ipv4_answers
from .snapshot_builder import SnapshotBuilder

pytestmark = pytest.mark.encrypted_dns

PID = 800

# A curated relay + DoH hostname that must return a NEGATIVE answer when on.
RELAY_HOST = "mask.icloud.com"
DOH_HOST = "dns.google"
# A normal host that must keep resolving via the LAN resolver (fallback proof).
NORMAL_HOST = "example.com"
# A curated public-resolver IP. :53 to it is dropped; :443/ICMP must survive.
RESOLVER_IP = "8.8.8.8"
PROBE_IP = "1.1.1.1"


def _wait_encrypted_dns_chain_loaded(*, timeout_s: float = 180) -> None:
    """Block until the agent has rendered + loaded the wifihaven_encrypted_dns
    nft chain — the observable that the toggle has actually taken effect."""
    def probe():
        res = router_ssh(
            "nft list table inet wifihaven 2>/dev/null || true",
            check=False, timeout=10,
        )
        return True if "wifihaven_encrypted_dns" in (res.stdout or "") else None
    wait_until(
        probe, timeout_s=timeout_s, interval_s=3,
        description="wifihaven_encrypted_dns nft chain loaded",
    )


def _dig_via(client, host: str, server: str, *, timeout_s: int = 6):
    """`dig +short @<server> <host>` from the client — used to probe whether
    DNS to a SPECIFIC upstream (e.g. 8.8.8.8) reaches it. Returns the Result."""
    return client_exec(
        client,
        ["dig", "+time=2", "+tries=1", "+short", f"@{server}", host],
        timeout=timeout_s + 5,
        check=False,
    )


def _ipv4_answers(stdout: str | None) -> list[str]:
    import re
    pat = re.compile(r"^\d+\.\d+\.\d+\.\d+$")
    return [l.strip() for l in (stdout or "").splitlines()
            if l.strip() and pat.match(l.strip())]


@pytest.mark.smoke
def test_block_encrypted_dns_enforced(router, client, fake_api):
    """J1: with `blockEncryptedDns=true`, the agent (a) returns a NEGATIVE DNS
    answer for the curated relay/DoH hostnames, (b) drops DNS :53 to the curated
    resolver IPs while a normal name still resolves via the LAN resolver, and
    (c) leaves :443 connectivity probes to those IPs intact.
    """
    # ── Baseline (toggle OFF): DNS to 8.8.8.8 reaches it. This proves the drop
    #    asserted below is caused by the toggle, not by the VM environment. ────
    base = _dig_via(client, NORMAL_HOST, RESOLVER_IP)
    assert _ipv4_answers(base.stdout), (
        "precondition: with the toggle OFF, DNS to 8.8.8.8 must reach it "
        f"(got rc={base.returncode} stdout={base.stdout!r} stderr={base.stderr!r})"
    )

    # ── Turn the toggle ON network-wide. ─────────────────────────────────────
    snap = (
        SnapshotBuilder()
        .add_profile(id=PID, name="e2e-j1")  # permissive profile; toggle is global
        .add_device(mac=client.mac, name="e2e-j1-dev", profile_id=PID)
        .set_block_encrypted_dns(True)
        .build(etag='"sha256:j1-block-encrypted-dns-v1"')
    )
    etag = fake_api.serve_snapshot(snap)
    fake_api.wait_for_etag_served(etag=etag, timeout_s=240)
    _wait_encrypted_dns_chain_loaded(timeout_s=180)

    # ── (a) NODATA half: the curated relay/DoH hostnames return NO IP. ────────
    # dnsmasq answers `local=/<host>/` with NODATA, so `dig +short` is empty —
    # an IP would mean Private Relay stays enabled (the #1891 hang). Poll: the
    # dnsmasq restart that picks up the new conf may lag the nft chain load.
    def _relay_negative():
        return True if not dig_ipv4_answers(client, RELAY_HOST) else None
    wait_until(_relay_negative, timeout_s=60, interval_s=3,
               description=f"{RELAY_HOST} returns a NEGATIVE DNS answer (no IP)")
    assert not dig_ipv4_answers(client, RELAY_HOST), (
        f"{RELAY_HOST} must return a negative answer (NODATA), not an IP"
    )
    assert not dig_ipv4_answers(client, DOH_HOST), (
        f"{DOH_HOST} must return a negative answer (NODATA), not an IP"
    )

    # ── (b) :53-to-resolver-IP drop + opportunistic LAN-resolver fallback. ────
    # DNS to 8.8.8.8 is now dropped (no answer)…
    blocked = _dig_via(client, NORMAL_HOST, RESOLVER_IP)
    assert not _ipv4_answers(blocked.stdout), (
        "DNS (:53) to 8.8.8.8 must be dropped when the toggle is on "
        f"(unexpectedly got {_ipv4_answers(blocked.stdout)!r})"
    )
    # …but the SAME name still resolves via the LAN resolver. THE core "did we
    # break the network" assertion — opportunistic DoH/hardcoded-DNS clients
    # fall back to the router's resolver and stay online.
    lan = dig_ipv4_answers(client, NORMAL_HOST)
    assert lan, (
        f"{NORMAL_HOST} must still resolve via the LAN resolver after the toggle "
        "is on (opportunistic fallback — network must NOT be broken)"
    )

    # ── (c) :443 connectivity probe to a resolver IP STILL succeeds. ──────────
    # 1.1.1.1/8.8.8.8 double as universal "am I online?" targets; we port-scope
    # the drop to :53, so a TLS connection to 1.1.1.1:443 must still establish
    # (no DNS involved — the literal IP is dialed directly).
    probe = client_exec(
        client,
        ["sh", "-c",
         f"curl -k -sS -o /dev/null -w '%{{http_code}}' --max-time 8 https://{PROBE_IP}/ "
         f"|| echo 000"],
        timeout=20, check=False,
    )
    code = (probe.stdout or "").strip().splitlines()[-1] if probe.stdout else "000"
    assert code not in ("", "000"), (
        f"connectivity probe https://{PROBE_IP}:443 must still connect when the "
        f"toggle is on (:53-scoped drop must not catch :443); got code={code!r} "
        f"stderr={probe.stderr!r}"
    )
