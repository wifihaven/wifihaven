"""Suite F — unknown-device autocreation (#432, verifies #62, #249, #468).

Locks in the contract that traffic from a never-registered MAC results in
an auto-created device row on the API side with NULL profile_id.

What's covered here (F1, smoke): a DHCP client at boot results in a row
named after the DHCP-advertised hostname. This is the common real-world
case for a freshly-plugged-in device.

What is NOT covered here — and why (#517)
-----------------------------------------

The previous F1b and F2 scenarios attempted to drive the auto-name /
late-DHCP-rename paths from a single VM by hot-swapping eth0 to a fresh
MAC + static IP and asserting that no DHCP lease ever attached the
cloud-init-baked `wh-client` hostname to the new MAC. Four attempts
across #468/PR#470, #475/PR#477, #483/PR#484 and #517 all hit the same
leak: the fresh MAC appeared on the API with the boot-image hostname.

The 4th-attempt evidence (run 25972689545) was conclusive — the leaked
row carried `lastSeenIp=192.168.100.205` (a DHCP-pool address, NOT the
static `192.168.100.50` the test configured). Because the agent populates
`first_seen_mac.ip` from the dnsmasq lease file (`conntrack.parse_dhcp_leases`
keyed by MAC), the only way that field can carry a pool IP is if udhcpc
did in fact DHCP with the new MAC after the test's reconfigure step.

Three layers of defense — `sed iface dhcp → manual`, `pkill -9 udhcpc`,
and renaming the `/sbin/udhcpc` binary to `.disabled` — were still
bypassed. The remaining suspects (busybox-applet invocation as `busybox
udhcpc ...`, mdev/hotplug scripts that read the symlink target before
the rename, post-`ip link up` hotplug events) form a long tail we cannot
practically catalogue from the VM image alone. Trying to harden further
would make the test fixture less and less like a real Alpine client,
defeating the realism that motivates the VM tier.

The equivalent contract is already enforced at the API layer in
`api/test/src/feature/RouterIngestSpec.scala`:

  - `events: first_seen_mac creates an unknown-device row when missing`
    covers F1b (no hostname → `device-XXYYZZ`).
  - `events: dhcp_lease renames a device whose name matches device-XXYYZZ`
    covers F2 (late DHCP lease upgrades an auto-name row in place).

Those tests exercise `RouterIngestRoutes.upsertDeviceFromEvent` directly,
which is the only code path the VM tests were ever really validating —
the rest was DHCP-suppression scaffolding that turned out to be
unenforceable on a stock cloud-init Alpine image.

If you reopen this question, the right next experiment is NOT another
disable layer on the client; it's either (i) baking a no-DHCP, no-hostname
client variant from a different base image, or (ii) keeping the test
deletion and accepting that this contract lives at the unit tier.
"""
from __future__ import annotations

import re

import pytest

from lib.wait import wait_until

pytestmark = pytest.mark.unknown_device

AUTO_NAME_RE = re.compile(r"^device-[0-9a-f]{6}$")


def _norm_mac(mac: str) -> str:
    return mac.lower().strip()


def _device_row_for_mac(admin, mac: str) -> dict | None:
    norm = _norm_mac(mac)
    for d in admin.list_devices():
        if _norm_mac(d.get("mac") or "") == norm:
            return d
    return None


def _wait_device_row(admin, mac: str, *, timeout_s: float = 90) -> dict:
    return wait_until(
        lambda: _device_row_for_mac(admin, mac),
        timeout_s=timeout_s, interval_s=2,
        description=f"device row for {mac} appears via list_devices",
    )


# ── F1 ─────────────────────────────────────────────────────────────────────


@pytest.mark.smoke
def test_unknown_dhcp_mac_autocreates_with_hostname(router, client, admin, debug_api):
    """F1 (smoke): DHCP client, hostname advertised → row named by hostname.

    Uses `client` (not `scratch_device`), so the MAC has never been
    registered with the API. The Alpine client DHCPs at boot via udhcpc
    with hostname option 12. The agent posts `dhcp_lease` to the API,
    which stores the row with the supplied hostname directly (no
    auto-name, per `RouterIngestRoutes`).
    """
    mac = client.mac

    row = _wait_device_row(admin, mac, timeout_s=90)

    name = row.get("name") or ""
    assert name and not AUTO_NAME_RE.match(name), (
        f"expected a DHCP-advertised hostname for a DHCP client, "
        f"got {name!r} (matches auto-name pattern — DHCP hostname option "
        f"may have been dropped)"
    )
    assert row.get("profileId") is None, (
        f"expected profileId=None for autocreated row, got {row.get('profileId')!r}"
    )
