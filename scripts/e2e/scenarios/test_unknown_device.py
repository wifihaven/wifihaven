"""Suite F — unknown-device autocreation (#432, verifies #62, #249).

Locks in the contract that traffic from a never-registered MAC results in
an auto-created device row on the API side:

  - On `first_seen_mac` (or `dhcp_lease`) with no hostname, the API
    upserts a device named `device-XXYYZZ` (lowercased last-three octets
    of the MAC, no separators) with NULL profile_id (#249).
  - A subsequent `dhcp_lease` that carries a real hostname upgrades the
    auto-generated name in place (#249's rename-if-auto path).
  - The "connection before DHCP" race-ordering preserves the rename — the
    fallback name is replaced by the real hostname once the lease arrives.

Asserts current behavior — bugs surfaced here get filed separately.
"""
from __future__ import annotations

import re

import pytest

from lib.traffic import http_get
from lib.vm import client_exec
from lib.wait import wait_until

pytestmark = pytest.mark.unknown_device

AUTO_NAME_RE = re.compile(r"^device-[0-9a-f]{6}$")


def _norm_mac(mac: str) -> str:
    return mac.lower().strip()


def _expected_auto_name(mac: str) -> str:
    hex_only = _norm_mac(mac).replace(":", "").replace("-", "")
    return f"device-{hex_only[-6:]}"


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


# ── F1 ─────────────────────────────────────────────────────────────────────


@pytest.mark.smoke
def test_unknown_mac_autocreates_device_row(router, client, admin, debug_api):
    """F1 (smoke): first traffic from an unregistered MAC autocreates the row.

    Uses `client` (not `scratch_device`), so the client's MAC has never
    been registered with the API. We drive any HTTP probe — the agent
    emits `first_seen_mac` (and/or `dhcp_lease`) and the API upserts a
    row with NULL profile_id named `device-XXYYZZ` (per #62 + #249).
    """
    mac = client.mac

    # Sanity: no device row yet.
    assert _device_row_for_mac(admin, mac) is None, (
        "precondition: unregistered MAC must not have a device row"
    )

    # Drive a request; failure to connect (default-deny pre-policy) is OK —
    # we only care that the agent observes the MAC and ingests the event.
    http_get(client, "http://example.com/", timeout_s=8)

    row = _wait_device_row(admin, mac, timeout_s=90)

    # Auto-generated name pattern, no profile assigned.
    assert AUTO_NAME_RE.match(row.get("name") or ""), (
        f"expected auto-generated name matching {AUTO_NAME_RE.pattern}, "
        f"got {row.get('name')!r}"
    )
    assert row.get("name") == _expected_auto_name(mac), (
        f"expected {_expected_auto_name(mac)!r} (lowercased MAC suffix), "
        f"got {row.get('name')!r}"
    )
    assert row.get("profileId") is None, (
        f"expected profileId=None for autocreated row, got {row.get('profileId')!r}"
    )


# ── F2 ─────────────────────────────────────────────────────────────────────


def test_dhcp_lease_upgrades_auto_name(router, client, admin, debug_api):
    """F2: a later `dhcp_lease` carrying a real hostname renames the row.

    Per #249's rename-if-auto path: a device row whose name still matches
    `device-XXYYZZ` is renamed when a subsequent DHCP lease arrives with a
    real hostname (option 12). We set the client hostname to a known value
    and force a DHCP release+renew, then assert the row's `name` flips.
    """
    mac = client.mac
    new_hostname = "kid-laptop"

    # Drive traffic so a row exists (likely with auto-generated name).
    http_get(client, "http://example.com/", timeout_s=8)
    row = _wait_device_row(admin, mac, timeout_s=90)
    # If the row already has a non-auto name, we still want to exercise the
    # rename path; #249's predicate only triggers when the current name is
    # auto-generated, so guard the assertion to current behavior.
    if not AUTO_NAME_RE.match(row.get("name") or ""):
        pytest.skip(
            f"row already has non-auto name {row.get('name')!r}; "
            "F2 exercises the auto→hostname rename path",
        )

    # Set hostname and force a fresh DHCP lease (Alpine: udhcpc -R then renew).
    client_exec(client, ["hostname", new_hostname])
    client_exec(client, ["sh", "-c", "udhcpc -R -i eth0 >/dev/null 2>&1 || true"])
    client_exec(client, ["sh", "-c", "udhcpc -i eth0 >/dev/null 2>&1 || true"])

    _wait_device_name(admin, mac, new_hostname, timeout_s=120)


# ── F3 ─────────────────────────────────────────────────────────────────────


def test_connection_before_dhcp_then_lease_upgrades_name(
    router, client, admin, debug_api,
):
    """F3: race — traffic before DHCP gives fallback name; lease upgrades it.

    Written as a single ordered scenario:
      1. Drive HTTP traffic from the unknown MAC → agent emits
         `first_seen_mac` (no hostname yet) → API stores `device-XXYYZZ`.
      2. Set hostname and force DHCP renew → agent emits `dhcp_lease` with
         hostname → API upgrades the name.

    Makes the ordering explicit; pairs with F2 which tests the same
    rename path but doesn't pin the sequence.
    """
    mac = client.mac
    new_hostname = "racey-laptop"

    # Step 1: traffic first — should land an auto-generated row.
    http_get(client, "http://example.com/", timeout_s=8)
    row = _wait_device_row(admin, mac, timeout_s=90)
    if not AUTO_NAME_RE.match(row.get("name") or ""):
        pytest.skip(
            f"row already has non-auto name {row.get('name')!r}; "
            "F3 exercises the connection-before-dhcp ordering",
        )
    assert row.get("name") == _expected_auto_name(mac)
    assert row.get("profileId") is None

    # Step 2: DHCP renew with a real hostname — name must upgrade.
    client_exec(client, ["hostname", new_hostname])
    client_exec(client, ["sh", "-c", "udhcpc -R -i eth0 >/dev/null 2>&1 || true"])
    client_exec(client, ["sh", "-c", "udhcpc -i eth0 >/dev/null 2>&1 || true"])

    _wait_device_name(admin, mac, new_hostname, timeout_s=120)
