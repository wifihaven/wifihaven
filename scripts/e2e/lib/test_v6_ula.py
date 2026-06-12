"""Unit cover for the #1687 fix: the Gate 3b client-v6 check must accept
ANY ULA (fc00::/7 per RFC 4193), not a hardcoded prefix.

Before #1687, the harness asserted the client landed in `fdaa:bbbb:cccc::/48`
(the prefix `/etc/uci-defaults/99-wifihaven` writes on a freshly built
router). That broke Gate 3b, which boots the last-published router image —
older builds use OpenWRT's auto-generated random ULA, so the check failed
even though v6 was fully plumbed end-to-end.

The check's real intent (from #1680) is "client got a v6 ULA from the
router via RA/SLAAC" — any ULA satisfies that. Run standalone:

    python3 -m pytest scripts/e2e/lib/test_v6_ula.py
"""
from __future__ import annotations

from v6_ula import client_has_ula_address


# Real `ip -6 addr show eth0` output shape, abridged.
def _addr_output(*global_addrs: str) -> str:
    lines = [
        "2: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 state UP qlen 1000",
        "    inet6 fe80::5054:ff:fe12:3456/64 scope link",
        "       valid_lft forever preferred_lft forever",
    ]
    for a in global_addrs:
        lines.append(f"    inet6 {a} scope global dynamic")
        lines.append("       valid_lft 1800sec preferred_lft 1800sec")
    return "\n".join(lines)


def test_accepts_deterministic_ula_from_99_wifihaven():
    """The Gate 2 case: new router image, deterministic prefix from
    scripts/vm/uci-defaults/99-wifihaven."""
    out = _addr_output("fdaa:bbbb:cccc::1/64")
    assert client_has_ula_address(out)


def test_accepts_random_ula_from_last_published_router():
    """The Gate 3b case (#1687): older router image, OpenWRT-generated
    random ULA. Must still pass — any ULA is fine."""
    out = _addr_output("fdf8:cd30:bf17::1/64")
    assert client_has_ula_address(out)


def test_accepts_fc00_half_of_the_range():
    """fc00::/7 is fc00::/8 plus fd00::/8. The fd00 half is the only one
    in practical use today, but the check must not reject fc00 either."""
    out = _addr_output("fc12:3456:7890::1/64")
    assert client_has_ula_address(out)


def test_rejects_no_v6_at_all():
    """Preserve #1680's intent: a client with no v6 ULA must still fail
    the check (catches a genuine RA/SLAAC regression)."""
    out = "2: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP>\n    inet6 fe80::1/64 scope link"
    assert not client_has_ula_address(out)


def test_rejects_only_link_local():
    """Link-local fe80::/10 is not a ULA — must not satisfy the check."""
    out = _addr_output()  # only the link-local line
    assert not client_has_ula_address(out)


def test_rejects_only_gua():
    """A global unicast (2000::/3) is not a ULA — must not satisfy.
    (In practice the qemu LAN bridge never sees a GUA, but the check
    should reject it cleanly rather than spuriously pass.)"""
    out = _addr_output("2001:db8::1/64")
    assert not client_has_ula_address(out)
