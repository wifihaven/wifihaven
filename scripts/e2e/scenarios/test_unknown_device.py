"""Suite F — unknown-device autocreation (#432, verifies #62, #249).

Locks in the contract that traffic from a never-registered MAC results in
an auto-created device row on the API side with NULL profile_id.

Naming behavior depends on which agent event lands first:

  - If `first_seen_mac` / `connection_attempt` arrives before any DHCP
    lease, the API stores the row with auto-generated `device-XXYYZZ`
    (#249).
  - If `dhcp_lease` arrives first with a real hostname (e.g. the client
    VM DHCPs on boot before user-space traffic), the API stores the row
    with that hostname directly.
  - A later `dhcp_lease` with a hostname renames an auto-generated row
    in place (#249's rename-if-auto path).

The VM harness boots Alpine with cloud-init's `fdns-client` hostname set
*before* any user-space traffic, so DHCP fires first and the row lands
with the real hostname directly. The rename path (auto → hostname) is
not reachable in this tier — it's covered by `RouterIngestSpec.scala`
unit tests which can drive event ordering deterministically (see #468).
"""
from __future__ import annotations

import re

import pytest

from lib.traffic import http_get
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


# ── F1 ─────────────────────────────────────────────────────────────────────


@pytest.mark.smoke
def test_unknown_mac_autocreates_device_row(router, client, admin, debug_api):
    """F1 (smoke): first traffic from an unregistered MAC autocreates the row.

    Uses `client` (not `scratch_device`), so the client's MAC has never
    been registered with the API. We drive any HTTP probe — the agent
    emits `first_seen_mac` / `dhcp_lease` and the API upserts a row with
    NULL profile_id.

    The row's `name` is either an auto-generated `device-XXYYZZ` (if the
    first event carried no hostname) or the client's DHCP-advertised
    hostname (if `dhcp_lease` arrived first — the realistic case for a
    VM that DHCPs before any user-space traffic). Both are valid per
    #62 + #249; the rename-from-auto path is covered by unit tests.
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

    name = row.get("name") or ""
    is_expected_auto = name == _expected_auto_name(mac)
    looks_like_other_auto = (
        AUTO_NAME_RE.match(name) is not None and not is_expected_auto
    )
    assert name and not looks_like_other_auto, (
        f"expected either auto-name {_expected_auto_name(mac)!r} or a "
        f"DHCP-advertised hostname, got {name!r}"
    )
    assert row.get("profileId") is None, (
        f"expected profileId=None for autocreated row, got {row.get('profileId')!r}"
    )
