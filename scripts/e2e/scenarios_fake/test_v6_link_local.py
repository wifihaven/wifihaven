"""Gate 2 v6 link-local bring-up (#1680, prep for #1677).

Confirms the qemu LAN bridge carries IPv6 between the client and router VMs
all the way through the router's FORWARD chain. The `client` fixture's
`_assert_v6_link_local_ready` (scripts/e2e/lib/vm.py) already gates that
SLAAC + a v6 default route landed on the client; the test below covers
what the fixture can't — that a v6 SYN from the client actually transits
the router's FORWARD chain and fires conntrack NEW, which is the
prerequisite for v6 hostname attribution in #1677.

The router can't actually forward the packet anywhere — qemu SLIRP is
v4-only — but the FORWARD-chain transit alone is what #1677 needs.
"""
from __future__ import annotations

import pytest

from lib.vm import ROUTER_ULA_PREFIX, client_exec, router_ssh

pytestmark = pytest.mark.v6_link_local

assert ROUTER_ULA_PREFIX  # silence unused-import lints; documents the contract


def test_v6_syn_traverses_router_forward_chain(client):
    """A v6 SYN to an RFC 3849 doc-space address must fire conntrack NEW
    on the router. We don't care that SLIRP drops it on the WAN side; the
    FORWARD-chain transit is what #1677 attribution depends on."""
    # Start `conntrack -E -f ipv6 -e NEW` in the background and capture its
    # PID so we can kill it by PID later — avoids relying on busybox
    # `pkill -f` matching full argv (which varies across busybox builds).
    #
    # The conntrack process MUST be daemonized in its own router_ssh call:
    # if a future cleanup pass folds the start/probe into one SSH session,
    # the backgrounded conntrack dies with the session and the capture
    # never sees the SYN.
    start = router_ssh(
        "rm -f /tmp/ct6.log /tmp/ct6.pid; "
        "( conntrack -E -f ipv6 -e NEW -o timestamp >/tmp/ct6.log 2>&1 & "
        "  echo $! >/tmp/ct6.pid ); "
        "cat /tmp/ct6.pid",
        timeout=10,
    )
    ct_pid = (start.stdout or "").strip().splitlines()[-1]
    assert ct_pid.isdigit(), f"failed to capture conntrack pid: {start.stdout!r}"

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
        f"sleep 2; kill {ct_pid} 2>/dev/null; "
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
