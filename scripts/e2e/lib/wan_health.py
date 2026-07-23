"""Detect the shared-host qemu-SLIRP guest-WAN-DHCP boot flake (#2390).

Gate 2 (`e2e-vm-fake.yml`) boots the OpenWRT router VM with its WAN NIC on
qemu **user-mode (SLIRP)** networking, which serves the guest an internal DHCP
lease. On the shared self-hosted KVM runner a cold boot / snapshot cycle
occasionally loses that handshake under coincident host memory/IO pressure: the
SLIRP main loop stalls long enough that the guest's busybox ``udhcpc`` (default
~3 tries x 3s) gives up before the reply lands. With no WAN lease the guest has
no upstream, so the agent's ``policy.apply`` smoke check resolves its probe host
to ``nil``, dnsmasq serves nothing, the ``resolved_`` nft sets stay empty, and
whichever scenario is running times out on a block-page / fallback assertion.
It presents as *1 random scenario failing out of ~51* -- an environmental boot
flake, NOT an enforcement regression. Same contention family as #2034 / #2158.

This module is the precise signature matcher the harness uses to classify such
a failure as the flake (skip with a clear reason) rather than red-gating the
whole router deploy. Kept a PURE text matcher (no I/O, no VM, no intra-lib
imports) so it is unit-testable in the ``E2E lib unit tests`` CI job and
imports bare (``import wan_health``) from ``scripts/e2e/lib``.
"""
from __future__ import annotations

import re

# busybox udhcpc's terminal give-up line for the WAN interface, e.g.
#   ... daemon.err wifihaven-agent: udhcpc: no lease, failing
# Match the stable core regardless of syslog facility/prefix. Deliberately does
# NOT match the healthy "udhcpc: lease of 10.0.2.15 obtained" line.
_UDHCPC_NO_LEASE = re.compile(r"udhcpc:\s*no lease", re.IGNORECASE)

# policy.lua's post-apply smoke check (openwrt/.../policy.lua ~L510) when
# dnsmasq has no working upstream: it resolves its probe host to a non-IP and
# logs, e.g.
#   policy.apply: smoke check failed; dnsmasq may be serving a stale config
#   -- got "nil" for tiktok.com (expected a real upstream IP)
# Keyed on the `got "nil"` result specifically: a smoke failure that returned a
# real-but-wrong IP is a different (possibly genuine) condition and must NOT be
# swallowed as this flake.
_SMOKE_CHECK_NIL = re.compile(
    r'policy\.apply: smoke check failed.*?got "nil"',
    re.IGNORECASE | re.DOTALL,
)


def wan_lease_flake_signature(*logs: str) -> bool:
    """STUB (RED): real implementation lands in the next commit."""
    return False
