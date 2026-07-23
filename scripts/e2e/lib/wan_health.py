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

The harness heals this at the source: the ``router`` fixture warms/re-kicks the
WAN after every ``loadvm`` restore (``conftest._wait_for_router_wan_healthy``)
so scenarios run on a healthy upstream instead of flaking — no scenario is
skipped. This module is the precise signature matcher that fixture uses to
LABEL a heal in the CI logs (udhcpc no-lease vs policy.apply smoke-check nil),
so when the heal fires you can see WHY the WAN was cold. Kept a PURE text
matcher (no I/O, no VM, no intra-lib imports) so it is unit-testable in the
``E2E lib unit tests`` CI job and imports bare (``import wan_health``) from
``scripts/e2e/lib``.
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


def _blob(logs: tuple[str, ...]) -> str:
    return "\n".join(l for l in logs if l)


def smoke_check_nil_signature(*logs: str) -> bool:
    """True iff a blob shows the ``policy.apply`` smoke-check-``nil`` line.

    This is the *per-scenario-reliable* half of the flake: the agent logs it
    only when a ``policy.apply`` runs against a dead upstream. It is NOT present
    in a healthy base snapshot's log ring (the snapshot is taken after a healthy
    policy fetch + smoke check), so unlike the boot-time ``udhcpc: no lease``
    line it does not get baked in and replayed on every ``loadvm`` restore. The
    heal-log labeler prefers it as the stronger, more-specific root-cause tag.
    """
    blob = _blob(logs)
    return bool(blob and _SMOKE_CHECK_NIL.search(blob))


def wan_lease_flake_signature(*logs: str) -> bool:
    """True iff any log blob shows the SLIRP guest-WAN-DHCP boot-flake signature.

    Two views of the same root (no WAN lease -> no upstream DNS): the guest's
    ``udhcpc: no lease`` give-up, and/or the agent's ``policy.apply`` smoke check
    resolving its probe host to ``"nil"`` for lack of an upstream. Either alone
    is sufficient.

    Broadest matcher, used for the human-facing root-cause tag in the WAN-heal
    log -- it includes the boot-time ``udhcpc: no lease`` line. Note that udhcpc
    line is a cold-boot transient the base snapshot captures and ``loadvm``
    replays every scenario, so it is a WEAK signal on its own; the labeler
    checks :func:`smoke_check_nil_signature` first and only falls back to this.

    Pure text matcher (no I/O) so it is unit-testable without a VM.
    """
    blob = _blob(logs)
    if not blob:
        return False
    return bool(_UDHCPC_NO_LEASE.search(blob) or _SMOKE_CHECK_NIL.search(blob))
