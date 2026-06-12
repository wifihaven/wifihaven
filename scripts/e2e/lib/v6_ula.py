"""Client-side ULA detection for the Gate 2/3b v6 link-local check (#1680/#1687).

Lives in its own module so it stays a pure function the unit tests can
exercise without spinning up VMs. vm.py's `_assert_v6_link_local_ready`
calls into here once it has the client's `ip -6 addr show eth0` output.

The check accepts ANY ULA in fc00::/7 (RFC 4193). Earlier revisions of
the harness hardcoded the deterministic prefix
`scripts/vm/uci-defaults/99-wifihaven` writes on freshly built routers,
which broke Gate 3b — that gate boots the last-published router image,
which predates the uci-default and falls back to OpenWRT's auto-generated
random ULA. The functional intent of #1680 is "client got a v6 ULA from
the router via RA/SLAAC," and any ULA satisfies that. See #1687.
"""
from __future__ import annotations

import ipaddress

_ULA_NET = ipaddress.IPv6Network("fc00::/7")


def client_has_ula_address(ip6_addr_output: str) -> bool:
    """Return True iff `ip -6 addr show <iface>` output contains at least
    one IPv6 address inside fc00::/7.

    Link-local (fe80::/10) and global-unicast (2000::/3) addresses are
    explicitly rejected — only an actual ULA proves the router's RA/SLAAC
    is reaching the client.
    """
    for raw in ip6_addr_output.splitlines():
        line = raw.strip()
        if not line.startswith("inet6 "):
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        try:
            addr = ipaddress.IPv6Interface(parts[1]).ip
        except ValueError:
            continue
        if addr in _ULA_NET:
            return True
    return False
