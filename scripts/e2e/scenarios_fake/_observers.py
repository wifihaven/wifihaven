"""Shared observers for fake-mode scenarios (#684).

Same nft / HTTP / DNS probes used by the live-mode scenarios. Kept in a
private module rather than `lib/` because they exist purely to mirror the
live scenarios' assertion shape — moving them into `lib/` would pull a
fake-mode-specific dialect into a module the live conftest also imports.

Helpers are observers only — they read router state, never mutate the fake.
Snapshot-side mutation goes through `scenarios_fake.snapshot_builder` + the
fake's `POST /test/snapshot`.
"""
from __future__ import annotations

import re

from lib.traffic import dns_query, http_get
from lib.vm import router_nft_set
from lib.wait import wait_until


BLOCKED_MACS_SET = "blocked_macs"


def norm_mac(mac: str) -> str:
    return mac.lower().strip()


def mac_in_blocked_set(mac: str) -> bool:
    members = {norm_mac(m) for m in router_nft_set(BLOCKED_MACS_SET)}
    return norm_mac(mac) in members


def wait_mac_in_blocked_set(mac: str, *, present: bool, timeout_s: float = 180) -> None:
    wait_until(
        lambda: True if mac_in_blocked_set(mac) is present else None,
        timeout_s=timeout_s,
        interval_s=2,
        description=(
            f"{mac} {'present in' if present else 'absent from'} {BLOCKED_MACS_SET}"
        ),
    )


def is_block_page_body(body: str | None) -> bool:
    b = (body or "").lower()
    return ("wifihaven" in b) or ("blocked" in b)


def wait_block_page(client, *, host: str = "example.com", timeout_s: float = 90):
    def probe():
        p = http_get(client, f"http://{host}/", timeout_s=8)
        if p.http_code == 200 and is_block_page_body(p.body):
            return p
        return None
    return wait_until(
        probe, timeout_s=timeout_s, interval_s=3,
        description=f"block page response for {host}",
    )


def wait_http_succeeds(client, *, host: str = "example.com", timeout_s: float = 60):
    """Probe HTTP success, disambiguating block-page (also 200) by body."""
    def probe():
        p = http_get(client, f"http://{host}/", timeout_s=8)
        if p.http_code is not None and 200 <= p.http_code < 400:
            if is_block_page_body(p.body):
                return None
            return p
        return None
    return wait_until(
        probe, timeout_s=timeout_s, interval_s=3,
        description=f"allowed HTTP response for {host}",
    )


_IPV4 = re.compile(r"^\d+\.\d+\.\d+\.\d+$")


def dig_ipv4_answers(client, host: str) -> list[str]:
    res = dns_query(client, host, timeout_s=5)
    lines = [l.strip() for l in (res.stdout or "").splitlines() if l.strip()]
    return [l for l in lines if _IPV4.match(l)]


def eb_set_name(host: str) -> str:
    """render.lua::sanitize(): `[.:]` → `_`. See openwrt/files/usr/lib/lua/wifihaven/render.lua."""
    return "eb_" + host.replace(".", "_").replace(":", "_")


def bl_set_name(bl_id: str) -> str:
    """render.lua::bl_sanitize(): `[.:-\\s]` → `_`.

    Category-blocklist drop-sets are named `bl_<sanitized_id>` where
    bl_sanitize replaces dots, colons, hyphens, and whitespace with
    underscores. See render.lua `bl_sanitize()`.

    Example: id="e2e-cname-bl" → "bl_e2e_cname_bl"
    """
    sanitized = re.sub(r"[.\:\-\s]", "_", bl_id)
    return "bl_" + sanitized


def wait_eb_set_populated(host: str, *, timeout_s: float = 90) -> list[str]:
    name = eb_set_name(host)
    def probe():
        elems = router_nft_set(name)
        return elems if elems else None
    return wait_until(
        probe, timeout_s=timeout_s, interval_s=3,
        description=f"nft set {name} to gain at least one element",
    )


def wait_bl_set_populated(bl_id: str, *, timeout_s: float = 90) -> list[str]:
    """Wait until the nft bl_ category-drop-set for a blocklist id has at
    least one element.

    This is the router-state side of the bl_ category-blocklist assertion — it
    confirms dns-tail's bl_ populator (added by #1348/#1350) added a
    directly-queried CNAME-target's IP to the category drop-set. The primary
    assertion in G4 is still traffic-level (wait_block_page), but this check
    surfaces the specific sub-component that failed when the traffic probe
    catches a regression. Mirrors wait_eb_set_populated.

    bl_set_name(id) → render.lua::bl_sanitize convention.
    """
    name = bl_set_name(bl_id)

    def probe():
        elems = router_nft_set(name)
        return elems if elems else None

    return wait_until(
        probe, timeout_s=timeout_s, interval_s=3,
        description=f"nft set {name} to gain at least one element",
    )


def wait_event_for_mac(fake_api, mac: str, *, allowed: bool, reason: str,
                       timeout_s: float = 60):
    """Block until fake captured a connection_attempt event matching predicates."""
    def probe():
        evs = fake_api.events_for_mac(mac, allowed=allowed, reason=reason)
        return evs or None
    return wait_until(
        probe, timeout_s=timeout_s, interval_s=2,
        description=f"fake event allowed={allowed} reason={reason!r} for {mac}",
    )


def _san(s: str) -> str:
    """render.lua::sanitize(): `[.:]` → `_`."""
    return s.replace(".", "_").replace(":", "_")


def ea_set_name(mac: str, host: str) -> str:
    """Return the nft set name for an ea_ allow-set.

    render.lua names these `ea_<sanmac>_<sanhost>` where sanmac and sanhost
    are the sanitized (dots/colons→underscores) forms. The mac is the full
    colon-separated form as seen in the snapshot.

    Example: mac=aa:bb:cc:11:22:33, host=khanacademy.org
             → ea_aa_bb_cc_11_22_33_khanacademy_org
    """
    return "ea_" + _san(mac) + "_" + _san(host)


def wait_ea_set_populated(mac: str, host: str, *, timeout_s: float = 90) -> list[str]:
    """Wait until the nft ea_ allow-set for (mac, host) has at least one element.

    This is the router-state side of the ea_ assertion — it confirms the
    dns-tail sidecar populated the kernel carve-out for a directly-queried
    CNAME target. The primary assertion is still traffic-level (wait_http_succeeds
    / wait_block_page), but the set membership check surfaces the specific
    sub-component that failed when the traffic probe catches a regression.
    """
    name = ea_set_name(mac, host)

    def probe():
        elems = router_nft_set(name)
        return elems if elems else None

    return wait_until(
        probe, timeout_s=timeout_s, interval_s=3,
        description=f"nft set {name} to gain at least one element",
    )


def wait_event_with_host_attribution(
    fake_api,
    mac: str,
    *,
    branded_host: str,
    timeout_s: float = 120,
) -> dict:
    """Wait until the fake has a connection_event whose host.value contains
    the branded hostname (not the raw CDN/leaf target).

    Used to assert #1344/#1345 attribution: after a direct re-query of a
    CNAME target, the event posted to /api/router/events must record the
    branded ancestor, not the CDN hostname.
    """
    def probe():
        for ev in fake_api.events_for_mac(mac):
            host_field = ev.get("host") or {}
            hval = host_field.get("value") or ""
            if branded_host.lower() in hval.lower():
                return ev
        return None

    return wait_until(
        probe, timeout_s=timeout_s, interval_s=3,
        description=(
            f"connection_event for {mac} with host attribution containing "
            f"{branded_host!r}"
        ),
    )
