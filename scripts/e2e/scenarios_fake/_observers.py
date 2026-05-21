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


def wait_eb_set_populated(host: str, *, timeout_s: float = 90) -> list[str]:
    name = eb_set_name(host)
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
