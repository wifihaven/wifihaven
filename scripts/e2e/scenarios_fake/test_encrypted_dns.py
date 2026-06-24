"""Suite J — network-wide "block encrypted DNS & relays" toggle (#1911).

VM-level end-to-end proof that a deployed agent enforces the additive top-level
`snapshot.blockEncryptedDns` flag (feature #1909, epic #1903). The busted unit
coverage (`openwrt/test/encrypted_dns_spec.lua`) proves the *render shape*; this
proves the *enforced behaviour against a real OpenWRT agent* — the chain is
actually rendered AND loaded into the live kernel ruleset, and dnsmasq actually
answers the curated hostnames locally.

When the toggle is on the agent enforces two separable halves:

  1. NODATA half (dnsmasq): a NEGATIVE DNS answer (`local=/<host>/`) for the
     curated relay/DoH hostnames — Apple's documented clean way to disable
     iCloud Private Relay (a 0.0.0.0 sinkhole does NOT disable it). This is the
     one sanctioned, narrow exception to Architectural Truth #1.
  2. Connection-layer half (nft): drop DoT (TCP/853 any IP) + DNS :53 (udp+tcp)
     to the curated public-resolver IPs ONLY — catches "system DNS hardcoded to
     8.8.8.8" and forces fallback to the LAN resolver.

The behaviours this suite locks down (per the #1909 blast-radius + port-scope
refinements):
  - **NODATA for the curated relay/DoH hostnames** — live check via the LAN
    resolver (dnsmasq answers locally; no egress).
  - **:53→curated-resolver-IP drop** — asserted on the LIVE kernel ruleset.
  - **DoT/853 drop** — asserted on the LIVE kernel ruleset.
  - **Opportunistic fallback survives** — after the toggle is on, a normal name
    still resolves via the LAN resolver and the network is NOT broken. THE core
    "did we break the network" assertion (live check; no egress).
  - **Connectivity probes survive** — the resolver-IP drop is `:53`-scoped, so
    `:443`/ICMP to `1.1.1.1`/`8.8.8.8` is never dropped. Asserted as a property
    of the rendered ruleset (no :443/icmp drop), not by dialing a public IP.

── Why this suite is egress-independent (#1935) ─────────────────────────────
The Gate-2 fake-mode VM is network-isolated: the router's dnsmasq is the only
reachable resolver (its upstream is pinned through QEMU SLIRP — see
scenarios_fake/conftest.py), and a *client*-originated packet to a public IP
(`dig @8.8.8.8`, `curl https://1.1.1.1`) cannot leave the harness REGARDLESS of
the toggle. The original precondition `dig @8.8.8.8 example.com` therefore timed
out in CI and red-gated every router release even though the #1911 enforcement
was correct. The drop / port-scope assertions are now made against the RENDERED
+ LOADED kernel ruleset — origin-independent, exactly as the ea_/eb_/bl_ suites
prove their populators via nft set/rule membership (see
test_cname_direct_requery.py). The toggle-causation is proven on that same
observable: the `wifihaven_encrypted_dns` chain is ABSENT with the toggle off
and PRESENT (with the drop rules) when it is on. The two halves that need no
egress — dnsmasq NODATA and the LAN-resolver fallback — stay live checks.

Reachability here is a connection-layer assertion, never "DNS resolved ⇒
allowed". See docs/architecture.md §0.1 and the AGENTS.md anti-pattern callout.
"""
from __future__ import annotations

import pytest

from lib.vm import router_ssh
from lib.wait import wait_until

from ._observers import dig_ipv4_answers
from .snapshot_builder import SnapshotBuilder

pytestmark = pytest.mark.encrypted_dns

PID = 800

# Curated relay + DoH hostnames that must return a NEGATIVE answer when on.
RELAY_HOST = "mask.icloud.com"
DOH_HOST = "dns.google"
# A normal host that must keep resolving via the LAN resolver (fallback proof).
NORMAL_HOST = "example.com"
# Curated public-resolver IPs whose :53 drop we assert on the live ruleset.
# These must match encrypted_dns.lua RESOLVER_IPS_{V4,V6}.
RESOLVER_IP_V4 = "8.8.8.8"
RESOLVER_IP_V4_ALT = "1.1.1.1"
RESOLVER_IP_V6 = "2606:4700:4700::1111"
ENCRYPTED_DNS_CHAIN = "wifihaven_encrypted_dns"


def _nft_wifihaven_dump() -> str:
    """Dump the agent's `inet wifihaven` table from the live kernel ruleset."""
    res = router_ssh(
        "nft list table inet wifihaven 2>/dev/null || true",
        check=False, timeout=10,
    )
    return res.stdout or ""


def _encrypted_dns_chain_present(dump: str) -> bool:
    return ENCRYPTED_DNS_CHAIN in dump


def _encrypted_dns_chain_lines(dump: str) -> list[str]:
    """Return the body lines of the `wifihaven_encrypted_dns` chain.

    The kernel-dumped chain looks like::

        chain wifihaven_encrypted_dns {
            type filter hook forward priority filter; policy accept;
            tcp dport 853 counter packets 0 bytes 0 drop comment "wh_encrypted_dns:dot853"
            ip daddr { 1.0.0.1, 1.1.1.1, 8.8.4.4, 8.8.8.8, ... } udp dport 53 ... drop comment "wh_encrypted_dns:resolver53"
            ...
        }

    We capture every non-empty line between the `chain ... {` header and the
    closing `}` (which sits on its own line; the anonymous `{ ... }` resolver
    sets are balanced on a single rule line, so the bare `}` unambiguously ends
    the chain). Returns [] if the chain is absent.
    """
    lines: list[str] = []
    capturing = False
    for raw in dump.splitlines():
        s = raw.strip()
        if not capturing:
            if s.startswith("chain " + ENCRYPTED_DNS_CHAIN):
                capturing = True
            continue
        if s == "}":
            break
        if s:
            lines.append(s)
    return lines


def _wait_encrypted_dns_chain_loaded(*, timeout_s: float = 180) -> str:
    """Block until the agent has rendered + loaded the wifihaven_encrypted_dns
    nft chain — the observable that the toggle has actually taken effect.
    Returns the table dump at the moment the chain appeared."""
    def probe():
        dump = _nft_wifihaven_dump()
        return dump if _encrypted_dns_chain_present(dump) else None
    return wait_until(
        probe, timeout_s=timeout_s, interval_s=3,
        description=f"{ENCRYPTED_DNS_CHAIN} nft chain loaded",
    )


@pytest.mark.smoke
def test_block_encrypted_dns_enforced(router, client, fake_api):
    """J1: with `blockEncryptedDns=true`, the agent (a) returns a NEGATIVE DNS
    answer for the curated relay/DoH hostnames, (b) loads a forward-hook chain
    that drops DNS :53 to the curated resolver IPs and DoT/853 while a normal
    name still resolves via the LAN resolver, and (c) leaves :443 connectivity
    probes intact (the resolver-IP drop is :53-scoped).

    Egress-independent (#1935): the drop/port-scope assertions are made on the
    LIVE kernel ruleset (origin-independent), and the toggle-causation is proven
    on that same observable — the chain is ABSENT before the toggle and PRESENT
    after. The NODATA + LAN-resolver-fallback halves are live checks that need no
    external egress (the router's dnsmasq is the reachable resolver).
    """
    # ── Baseline (toggle OFF): no enforcement applied, LAN resolver works. ────
    # This replaces the old `dig @8.8.8.8` precondition (which required client→
    # public egress the isolated VM never has). Proving the chain is ABSENT here
    # and PRESENT below is what shows the drop is caused by the TOGGLE, not the
    # environment — on the same observable, with zero egress.
    base_dump = _nft_wifihaven_dump()
    assert not _encrypted_dns_chain_present(base_dump), (
        "precondition: with the toggle OFF the agent must apply NO encrypted-DNS "
        "enforcement (the wifihaven_encrypted_dns chain must be absent)"
    )
    assert dig_ipv4_answers(client, NORMAL_HOST), (
        f"precondition: {NORMAL_HOST} must resolve via the LAN resolver with the "
        "toggle OFF (proves the no-egress LAN-resolver path works before the "
        "toggle, so the post-toggle fallback check is meaningful)"
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
    dump = _wait_encrypted_dns_chain_loaded(timeout_s=180)

    # ── (a) NODATA half: the curated relay/DoH hostnames return NO IP. ────────
    # dnsmasq answers `local=/<host>/` with a negative answer, so `dig +short`
    # is empty — an IP would mean Private Relay stays enabled (the #1891 hang).
    # Poll: the dnsmasq restart that picks up the new conf may lag the nft chain.
    # This is a LIVE check against the LAN resolver and needs no egress.
    def _relay_negative():
        return True if not dig_ipv4_answers(client, RELAY_HOST) else None
    wait_until(_relay_negative, timeout_s=60, interval_s=3,
               description=f"{RELAY_HOST} returns a NEGATIVE DNS answer (no IP)")
    assert not dig_ipv4_answers(client, RELAY_HOST), (
        f"{RELAY_HOST} must return a negative answer (no IP), not an address"
    )
    assert not dig_ipv4_answers(client, DOH_HOST), (
        f"{DOH_HOST} must return a negative answer (no IP), not an address"
    )

    # ── (b) :53-to-resolver-IP drop + DoT/853 drop, asserted on the LIVE kernel
    #    ruleset. Origin-independent: the chain is loaded, so the drop predicate
    #    is enforced for any forwarded LAN flow matching it — proving this by
    #    dialing 8.8.8.8:53 from the client would require egress the harness
    #    lacks. Mirrors how ea_/eb_/bl_ prove their populators via membership. ─
    chain_lines = _encrypted_dns_chain_lines(dump)
    assert chain_lines, (
        f"{ENCRYPTED_DNS_CHAIN} chain is loaded but its body is empty — "
        f"render emitted no drop rules. Dump:\n{dump}"
    )
    joined = "\n".join(chain_lines)

    # DoT: TCP/853 dropped (any IP).
    assert any("tcp dport 853" in l and "drop" in l for l in chain_lines), (
        f"no DoT (tcp dport 853) drop rule in {ENCRYPTED_DNS_CHAIN}:\n{joined}"
    )
    assert "wh_encrypted_dns:dot853" in joined, (
        f"DoT drop rule missing its wh_encrypted_dns:dot853 comment:\n{joined}"
    )

    # DNS :53 (udp AND tcp) to the curated resolver IPs.
    assert any(RESOLVER_IP_V4 in l and "udp dport 53" in l and "drop" in l
               for l in chain_lines), (
        f"no udp/53 drop to {RESOLVER_IP_V4} in {ENCRYPTED_DNS_CHAIN}:\n{joined}"
    )
    assert any(RESOLVER_IP_V4 in l and "tcp dport 53" in l and "drop" in l
               for l in chain_lines), (
        f"no tcp/53 drop to {RESOLVER_IP_V4} in {ENCRYPTED_DNS_CHAIN}:\n{joined}"
    )
    assert RESOLVER_IP_V4_ALT in joined, (
        f"curated resolver IP {RESOLVER_IP_V4_ALT} missing from "
        f"{ENCRYPTED_DNS_CHAIN}:\n{joined}"
    )
    assert "wh_encrypted_dns:resolver53" in joined, (
        f"resolver-IP drop rules missing their wh_encrypted_dns:resolver53 "
        f"comment:\n{joined}"
    )
    # v6 resolver IPs are :53-scoped too.
    assert any(RESOLVER_IP_V6 in l and "dport 53" in l for l in chain_lines), (
        f"no :53 drop to v6 resolver {RESOLVER_IP_V6} in {ENCRYPTED_DNS_CHAIN}:"
        f"\n{joined}"
    )

    # ── (c) Port-scope: every resolver-IP drop is :53-only; nothing drops :443
    #    or ICMP. 1.1.1.1/8.8.8.8 double as universal "am I online?" targets, so
    #    a blanket drop would make such devices believe they are offline. This
    #    is the rendered-ruleset form of the old "curl https://1.1.1.1 still
    #    works" probe (which the isolated VM could never reach). ──────────────
    for l in chain_lines:
        if RESOLVER_IP_V4 in l or RESOLVER_IP_V4_ALT in l:
            assert "dport 53" in l, (
                f"resolver-IP rule not scoped to :53 (would break connectivity "
                f"probes): {l}"
            )
    # Match the port token specifically — the live dump carries dynamic
    # `counter packets N bytes N` values, so a bare "443" substring could
    # spuriously hit a byte count. A :443 drop always renders as `dport 443`.
    assert "dport 443" not in joined, (
        f"{ENCRYPTED_DNS_CHAIN} must not touch :443 — connectivity probes to the "
        f"resolver IPs must survive:\n{joined}"
    )
    assert "icmp" not in joined.lower(), (
        f"{ENCRYPTED_DNS_CHAIN} must not drop ICMP to the resolver IPs:\n{joined}"
    )

    # ── (d) Opportunistic LAN-resolver fallback. THE core "did we break the
    #    network" assertion: the SAME normal name still resolves via the LAN
    #    resolver after the toggle is on. Opportunistic-DoH / hardcoded-DNS
    #    clients fall back to the router's resolver and stay online. Live check,
    #    no egress (the router's dnsmasq upstream is pinned via SLIRP). ────────
    lan = dig_ipv4_answers(client, NORMAL_HOST)
    assert lan, (
        f"{NORMAL_HOST} must still resolve via the LAN resolver after the toggle "
        "is on (opportunistic fallback — network must NOT be broken)"
    )
