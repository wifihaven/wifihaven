"""Suite F — unknown-device autocreation (#432, verifies #62, #249, #468).

Locks in the contract that traffic from a never-registered MAC results in
an auto-created device row on the API side with NULL profile_id.

Two real-world scenarios produce different initial names:

  - **DHCP clients** (the common case): the host DHCPs at boot, advertising
    its hostname via option 12. The API's `dhcp_lease` ingest path stores
    the row with that hostname directly — no auto-name involved. F1
    covers this.
  - **Static-IP clients**: the host has a hand-configured IP and never
    DHCPs. The first the router agent sees is a `connection_attempt` /
    `first_seen_mac` with no hostname, so the API stores the row with
    auto-generated `device-XXYYZZ` (#62 + #249). F1b covers this.
  - **Late-arriving DHCP** (e.g. static-IP host that later switches to
    DHCP, or DHCP arrives after a connection attempt): the row was
    created with auto-name and is renamed in place when the lease lands
    carrying a hostname (#249's rename-if-auto path). F2 covers this.

The static-IP and rename paths are driven from a single VM by swapping
eth0's MAC at runtime to a fresh, API-unknown MAC and configuring it
either statically or with DHCP+hostname. The boot-time DHCP exchange the
client did with its original MAC is unrelated and ignored — the test
only asserts on the fresh MAC's row.
"""
from __future__ import annotations

import random
import re

import pytest

from lib.traffic import http_get
from lib.vm import client_exec
from lib.wait import wait_until

pytestmark = pytest.mark.unknown_device

AUTO_NAME_RE = re.compile(r"^device-[0-9a-f]{6}$")

# Static IP outside the router's dnsmasq DHCP pool (default starts at .100).
STATIC_CLIENT_IP = "192.168.100.50"
ROUTER_LAN_IP = "192.168.100.1"


def _norm_mac(mac: str) -> str:
    return mac.lower().strip()


def _expected_auto_name(mac: str) -> str:
    hex_only = _norm_mac(mac).replace(":", "").replace("-", "")
    return f"device-{hex_only[-6:]}"


def _fresh_unknown_mac() -> str:
    """Locally-administered MAC the API has never seen.

    Uses a distinct OUI prefix (02:e2:f0:*) from the boot-time client MAC
    (`_gen_mac` in conftest.py uses 02:e2:e2:*) so a stray match between
    the two is obvious in logs.
    """
    return "02:e2:f0:%02x:%02x:%02x" % (
        random.randint(0, 255),
        random.randint(0, 255),
        random.randint(0, 255),
    )


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


def _wait_device_name(admin, mac: str, expected: str, *, timeout_s: float = 90) -> dict:
    def pred():
        row = _device_row_for_mac(admin, mac)
        if row is None:
            return None
        return row if row.get("name") == expected else None
    return wait_until(
        pred, timeout_s=timeout_s, interval_s=2,
        description=f"device row for {mac} renamed to {expected!r}",
    )


def _reconfigure_eth0_static(client, *, mac: str, ip: str) -> None:
    """Swap eth0 to a fresh MAC + static IP with no DHCP client running.

    Kills any running udhcpc, flushes eth0, sets a new link-layer address,
    assigns the static IP, and installs a default route to the router. From
    the router-agent's perspective this is a brand-new host that never
    DHCP'd — the very scenario F1b/F2 are about.
    """
    client_exec(client, ["sh", "-c", (
        "pkill udhcpc 2>/dev/null; "
        "sleep 1; "
        "ip addr flush dev eth0; "
        "ip link set eth0 down; "
        f"ip link set eth0 address {mac}; "
        "ip link set eth0 up; "
        f"ip addr add {ip}/24 dev eth0; "
        f"ip route replace default via {ROUTER_LAN_IP}"
    )])


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


# ── F1b ────────────────────────────────────────────────────────────────────


def test_unknown_static_ip_mac_autocreates_with_auto_name(
    router, client, admin, debug_api,
):
    """F1b: static-IP client, no DHCP → row named `device-XXYYZZ`.

    Reconfigures eth0 to a fresh MAC with a static IP and no DHCP client.
    The router agent's first event for this MAC is a `connection_attempt`
    (or `first_seen_mac`) with no hostname, so the API auto-names per
    #62 + #249.
    """
    static_mac = _fresh_unknown_mac()
    assert _device_row_for_mac(admin, static_mac) is None, (
        "precondition: freshly-generated MAC must not have a device row"
    )

    _reconfigure_eth0_static(client, mac=static_mac, ip=STATIC_CLIENT_IP)

    # Drive a request; failure to connect (default-deny pre-policy) is OK —
    # the agent only needs to observe the MAC to emit first_seen_mac.
    http_get(client, "http://example.com/", timeout_s=8)

    row = _wait_device_row(admin, static_mac, timeout_s=90)

    name = row.get("name") or ""
    assert AUTO_NAME_RE.match(name), (
        f"expected auto-generated name matching {AUTO_NAME_RE.pattern}, "
        f"got {name!r} (static-IP client should never carry a DHCP hostname)"
    )
    assert name == _expected_auto_name(static_mac), (
        f"expected {_expected_auto_name(static_mac)!r} (lowercased MAC suffix), "
        f"got {name!r}"
    )
    assert row.get("profileId") is None, (
        f"expected profileId=None for autocreated row, got {row.get('profileId')!r}"
    )


# ── F2 ─────────────────────────────────────────────────────────────────────


def test_auto_named_row_renamed_when_dhcp_lease_arrives(
    router, client, admin, debug_api,
):
    """F2: static-IP host with auto-name → DHCP arrives → row renamed.

    Per #249's rename-if-auto path: a device row whose name still matches
    `device-XXYYZZ` is renamed when a later DHCP lease carries a real
    hostname. Drives the ordering deterministically by starting the host
    static (no DHCP) so the row lands with the auto-name, then bringing
    DHCP up with a chosen hostname so the lease event upgrades it.
    """
    static_mac = _fresh_unknown_mac()
    new_hostname = "kid-laptop"
    assert _device_row_for_mac(admin, static_mac) is None

    # Phase 1: static IP, no DHCP. Drive traffic so the agent observes
    # the MAC and the API stores an auto-named row.
    _reconfigure_eth0_static(client, mac=static_mac, ip=STATIC_CLIENT_IP)
    http_get(client, "http://example.com/", timeout_s=8)
    row = _wait_device_row(admin, static_mac, timeout_s=90)
    assert AUTO_NAME_RE.match(row.get("name") or ""), (
        f"F2 precondition: expected auto-name, got {row.get('name')!r}"
    )
    assert row.get("name") == _expected_auto_name(static_mac)

    # Phase 2: drop the static IP, set hostname, and DHCP. The lease
    # carries hostname option 12 (busybox udhcpc reads /etc/hostname)
    # → API renames the auto-named row.
    client_exec(client, ["sh", "-c", (
        f"hostname {new_hostname}; "
        f"echo {new_hostname} > /etc/hostname; "
        "ip addr flush dev eth0; "
        "ip route del default 2>/dev/null; "
        "rm -f /var/run/udhcpc.eth0.pid; "
        "udhcpc -q -n -i eth0 >/dev/null 2>&1 || true; "
        "udhcpc -i eth0 >/dev/null 2>&1 || true"
    )])

    _wait_device_name(admin, static_mac, new_hostname, timeout_s=120)
