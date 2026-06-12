"""Gate 2 v6 link-local bring-up (#1680, prep for #1677).

Confirms the qemu LAN bridge carries IPv6 between the client and router VMs:

- Router LAN advertises a ULA prefix via odhcpd RAs.
- Client picks up SLAAC + a v6 default route via the router's link-local.
- A v6 SYN from client to a documentation address traverses the router's
  FORWARD chain and fires a conntrack NEW event (the prerequisite for v6
  hostname attribution in #1677).

The router can't actually forward the packet anywhere — qemu SLIRP is
v4-only — but the FORWARD-chain transit alone is what #1677 needs.
"""
from __future__ import annotations

import pytest

from lib.vm import client_exec, router_ssh

pytestmark = pytest.mark.v6_link_local

# Must match the ULA in scripts/vm/uci-defaults/99-wifihaven.
ROUTER_ULA_PREFIX = "fdaa:bbbb:cccc"


def test_client_has_v6_default_route(client):
    """Client kernel must accept the router's RA and install a default route."""
    res = client_exec(
        client,
        ["sh", "-c", "ip -6 route show default"],
        check=False,
        timeout=10,
    )
    assert res.returncode == 0, (
        f"`ip -6 route show default` failed: rc={res.returncode} "
        f"stdout={res.stdout!r} stderr={res.stderr!r}"
    )
    out = (res.stdout or "").strip()
    assert out, (
        "no IPv6 default route on client eth0 — RA from router did not "
        f"install one. `ip -6 route show default` returned empty.\n"
        f"full `ip -6 route`:\n"
        f"{client_exec(client, ['sh', '-c', 'ip -6 route'], check=False, timeout=10).stdout}"
    )
    # Default route must be via a link-local on eth0 (the LAN-side NIC).
    assert "dev eth0" in out, f"v6 default route not on eth0: {out!r}"


def test_client_has_ula_global_address(client):
    """Client must SLAAC-derive a global-scope address from the router's ULA."""
    res = client_exec(
        client,
        ["sh", "-c", "ip -6 addr show eth0 scope global"],
        check=False,
        timeout=10,
    )
    out = res.stdout or ""
    assert ROUTER_ULA_PREFIX in out, (
        f"client eth0 has no address in router ULA prefix "
        f"{ROUTER_ULA_PREFIX}::/48 — SLAAC failed.\n"
        f"`ip -6 addr show eth0 scope global` stdout:\n{out!r}"
    )


def test_v6_syn_traverses_router_forward_chain(client):
    """A v6 SYN to an RFC 3849 doc-space address must fire conntrack NEW
    on the router. We don't care that SLIRP drops it on the WAN side; the
    FORWARD-chain transit is what #1677 attribution depends on."""
    # Start a `conntrack -E -f ipv6 -e NEW` capture on the router in the
    # background, fire the v6 SYN, then check the capture.
    router_ssh(
        "rm -f /tmp/ct6.log; "
        "(conntrack -E -f ipv6 -e NEW -o timestamp >/tmp/ct6.log 2>&1 &) ; "
        "echo $!",
        timeout=10,
    )
    # nc returns immediately on SYN_SENT timeout. Doc-space 2001:db8::10
    # is unreachable, which is the point — the SYN crossed FORWARD.
    client_exec(
        client,
        ["sh", "-c", "nc -6 -w 2 2001:db8::10 80 >/dev/null 2>&1; true"],
        check=False,
        timeout=10,
    )
    # Give conntrack a moment, then sample.
    res = router_ssh(
        "sleep 2; pkill -f 'conntrack -E -f ipv6' 2>/dev/null; "
        "grep -c '\\[NEW\\].*2001:db8::10' /tmp/ct6.log || echo 0",
        check=False,
        timeout=15,
    )
    count_line = (res.stdout or "0").strip().splitlines()[-1]
    try:
        n = int(count_line)
    except ValueError:
        n = 0
    if n == 0:
        # If conntrack didn't fire we want full diagnostics in the failure.
        ct_dump = router_ssh(
            "cat /tmp/ct6.log 2>/dev/null; echo '---'; "
            "ip -6 route; echo '---'; nft list chain inet wifihaven forward 2>&1 | head -20",
            check=False, timeout=10,
        )
        pytest.fail(
            f"no conntrack NEW for v6 SYN to 2001:db8::10 "
            f"(grep returned {count_line!r}).\n{ct_dump.stdout}"
        )
