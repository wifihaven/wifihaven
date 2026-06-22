"""Gate 3 smoke — single scenario covering the contract surface between
the router agent and the API:

  1. Router boots, enrolls against staging (session fixture).
  2. Profile + device provisioned with extraBlocked + extraAllowed.
  3. Agent fetches the new policy (etag advances).
  4. Client traffic — blocked host is intercepted (block page body),
     allowed host returns upstream content.
  5. Usage report — staging /api/logs has a row attributed to this MAC,
     and /api/time/status surfaces the device under its profile.

Either side of the contract drifting (snapshot field rename consumed by
render.lua, or events POST shape rename) turns this red. Behavior depth
beyond this lives in Gate 2 (#654); the deliberately narrow scope is the
point.
"""
from __future__ import annotations

import logging
import time

from lib.traffic import http_get
from lib.wait import wait_for_etag_change, wait_until

log = logging.getLogger(__name__)


# Window for /api/logs lookback. Generous enough to absorb the agent's
# usage-report flush cadence (default 60s) without flaking.
LOGS_LOOKBACK_HOURS = 1


def test_gate3_smoke(admin, enrolled_router, client_vm, scratch_profile_and_device):
    mac = scratch_profile_and_device["mac"]
    profile_id = scratch_profile_and_device["profile_id"]
    blocked_host = scratch_profile_and_device["blocked_host"]
    allowed_host = scratch_profile_and_device["allowed_host"]

    log.info("gate3: probing blocked=%s allowed=%s from mac=%s",
             blocked_host, allowed_host, mac)

    # ── blocked path ─────────────────────────────────────────────────────
    # Per #351 / #429 / render.lua: extraBlocked HTTP/80 is DNAT'd to the
    # local block page (uhttpd on 127.0.0.1:8081). dnsmasq must first
    # populate the per-host nft set from a real upstream answer, so the
    # very first request after policy apply may still race to upstream —
    # poll until the block page lands.
    probe = wait_until(
        lambda: _block_page_probe(client_vm, blocked_host),
        timeout_s=120, interval_s=3,
        description=f"block page response for {blocked_host}",
    )
    assert probe.http_code == 200
    log.info("gate3: block page hit for %s (%d bytes)", blocked_host, len(probe.body))

    # ── allowed path ─────────────────────────────────────────────────────
    # example.org is in extraAllowed but no category blocks it either, so
    # this asserts the negative — agent did NOT over-block. Genuine
    # upstream content is expected (no block-page markers).
    allowed = wait_until(
        lambda: _upstream_probe(client_vm, allowed_host),
        timeout_s=60, interval_s=3,
        description=f"upstream response for {allowed_host}",
    )
    log.info("gate3: upstream OK for %s (code=%s, %d bytes)",
             allowed_host, allowed.http_code, len(allowed.body))

    # ── generate a few more probes to give the agent's usage flush
    # something to ship. The agent batches connection events; one probe
    # may not survive the flush cadence before the test's wait expires.
    for _ in range(3):
        http_get(client_vm, f"http://{allowed_host}/", timeout_s=8)
        time.sleep(1)

    # ── usage cross-check: /api/logs sees rows attributed to our MAC ────
    # Per scenarios_fake/test_05_usage_in_api.py the canonical assertion is
    # "MAC attribution lands" — host-string matching is brittle because
    # what the agent emits depends on dnsmasq log timing and packet
    # batching. The wire-end-to-wire-end signal we care about is
    # "events POST round-tripped → API attributed → /api/logs surfaces".
    rows = wait_until(
        lambda: _logs_for_mac(admin, mac) or None,
        timeout_s=240, interval_s=5,
        description=f"/api/logs row for mac={mac}",
    )
    assert any((_row_mac(r) or "").lower() == mac.lower() for r in rows), (
        f"no /api/logs row attributed to mac={mac}; "
        f"saw {len(rows)} row(s), macs={sorted({_row_mac(r) for r in rows if _row_mac(r)})}"
    )
    log.info("gate3: %d /api/logs rows attributed to %s", len(rows), mac)

    # ── /api/time/status cross-check ─────────────────────────────────────
    # Second public-surface endpoint the API/UI ships. A rename of the
    # device-summary shape (deviceMac, deviceName) breaks the SPA today;
    # a Gate 3b run against the last-published router would still expect
    # this field to be present.
    statuses = admin.time_status()
    summaries = [d for prof in statuses for d in prof.get("devices", [])]
    matched = [
        d for d in summaries
        if (d.get("deviceMac") or "").lower() == mac.lower()
    ]
    assert matched, (
        f"no /api/time/status summary for {mac} under profile {profile_id}; "
        f"saw {len(summaries)} summaries across {len(statuses)} profile(s)"
    )
    log.info("gate3: time_status summary present for %s: %s", mac, matched[0])

    # ── whole-MAC block → block page is reachable (#1865) ─────────────────
    # Regression for #1865 ("the block page must NEVER be blocked"). A
    # *whole-MAC* block (pause / schedule / time-limit / manual — exercised
    # here via pause) DNATs ALL of the device's HTTP to the local block page.
    # The bug: the per-MAC blocked drop killed the DNAT'd packet (notably v6,
    # which DNAT'd to ::1 and forwarded out the WAN), so the user saw a hung
    # connection instead of the block page. After the fix the block page must
    # load for the whole-MAC-blocked device. We probe a NEUTRAL host (not
    # allowed_host: a Paused MAC keeps its extraAllowed carve-out per #1413,
    # so the allowed host stays reachable even while paused). The block-page
    # DNAT for a whole-MAC block is not gated on a resolved ipset, so any
    # HTTP/80 from the paused MAC lands on the page.
    neutral_host = "example.com"  # client_vm fixture already gated its DNS
    admin.set_profile_paused(profile_id, True)
    try:
        wait_for_etag_change(admin, enrolled_router["router_id"], timeout_s=180)
        blocked_probe = wait_until(
            lambda: _block_page_probe(client_vm, neutral_host),
            timeout_s=120, interval_s=3,
            description=f"block page for whole-MAC-blocked {neutral_host}",
        )
        assert blocked_probe.http_code == 200
        log.info("gate3: whole-MAC-block block page hit for %s (%d bytes)",
                 neutral_host, len(blocked_probe.body))
    finally:
        # Restore so teardown (and any retry of this run) starts unpaused.
        admin.set_profile_paused(profile_id, False)


# ── probes ───────────────────────────────────────────────────────────────


_BLOCK_MARKERS = ("wifihaven", "/blocked", "blocked by", "blocked.html")


def _block_page_probe(client, host: str):
    """Return the HttpProbe iff the response looks like the wifihaven block
    page (200 + marker substring in body). None otherwise — caller polls."""
    p = http_get(client, f"http://{host}/", timeout_s=8)
    if p.http_code != 200 or not p.body:
        return None
    body_lc = p.body.lower()
    if any(m in body_lc for m in _BLOCK_MARKERS):
        return p
    return None


def _upstream_probe(client, host: str):
    """Return the HttpProbe iff the response is a genuine 2xx/3xx from
    upstream with NO block-page markers. None otherwise — caller polls."""
    p = http_get(client, f"http://{host}/", timeout_s=8)
    if not p.ok or p.http_code is None:
        return None
    if not (200 <= p.http_code < 400):
        return None
    body_lc = (p.body or "").lower()
    if any(m in body_lc for m in _BLOCK_MARKERS):
        return None
    return p


def _logs_for_mac(admin, mac: str) -> list[dict]:
    # /api/logs shape changed at some point; older scenarios assert
    # isinstance(logs, list) (test_05_usage_in_api.py) but staging today
    # returns {"rows": [...]}. Handle both.
    res = admin.logs(mac=mac, hours=LOGS_LOOKBACK_HOURS)
    if isinstance(res, dict):
        return res.get("rows") or []
    return res or []


def _row_mac(row: dict) -> str | None:
    return row.get("mac") or row.get("deviceMac")
