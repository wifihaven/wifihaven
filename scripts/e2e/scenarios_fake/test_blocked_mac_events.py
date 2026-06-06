"""#1122/#1126 — router deploy gate: forward-drop visibility via nflog.

Runs in the same Gate 2 (fake API + real router VM) lane as test_pause.py.
Validates the router-side half of #1122/#1126:

  1. The deployed agent ships `nflog.lua`, the `wifihaven-nflog-tail` sidecar,
     and `render.lua` installs per-MAC drop rules carrying
     `log prefix "wh_drop:<mac>:<reason> " counter drop comment "wh_drop:…"`.
     This guards the data-plane contract end-to-end across the package build,
     so if a future refactor drops the `log prefix` from one of the drop
     families, a router-side regression fires here in CI instead of in
     production. (#1126 switched the read channel from NFLOG `log group` —
     which had no stock consumer — to `log prefix`, readable off logread.)

  2. With the snapshot pausing a MAC, the agent's nflog reader (sidecar →
     spool → nflog.drain_file → batcher) posts a synthesized
     `connection_attempt(allowed=false, reason=Paused)` event for client
     traffic to the fake API. This validates the full
     logread → spool → agent → /api/router/events path on the real OpenWRT VM
     and is the router-side proof for the #1524 "Connection Events near-blind"
     gap (blocked traffic produced zero connection events). Companion ingest
     coverage: RouterIngestSpec "nflog-synthesized blocked flow persists"
     (#1116) and the agent-unit nflog_spec drain_file/run tests (#1117).
"""
from __future__ import annotations

import logging

import pytest

from lib.traffic import http_get
from lib.vm import router_ssh
from lib.wait import wait_until

from .snapshot_builder import snapshot_with_assigned_device

log = logging.getLogger(__name__)

pytestmark = pytest.mark.nflog


# ── static deploy contract ──────────────────────────────────────────────────


def test_nflog_module_and_sidecar_are_installed_on_router(router):
    """The agent package ships nflog.lua AND the wifihaven-nflog-tail sidecar.

    A package-build regression that drops either (typo in the makefile glob,
    file rename without update) breaks the blocked-flow visibility path:
    nflog.lua fails the agent boot via the explicit `require("wifihaven.nflog")`,
    and a missing sidecar leaves the spool empty so the agent's drain reads
    nothing. These assertions catch the missing files at deploy time, before
    any agent restart, so a bad release never ships."""
    res = router_ssh(
        "test -f /usr/lib/lua/wifihaven/nflog.lua "
        "&& test -x /usr/sbin/wifihaven-nflog-tail && echo ok || echo missing",
        check=False, timeout=10,
    )
    assert (res.stdout or "").strip() == "ok", (
        f"nflog.lua / wifihaven-nflog-tail not installed on router: "
        f"stdout={res.stdout!r} stderr={res.stderr!r}"
    )


def test_paused_snapshot_emits_per_mac_drop_with_log_prefix_and_wh_drop_comment(
    router, client, fake_api,
):
    """With a paused profile, the live nft ruleset on the router carries a
    per-MAC drop with
    `log prefix "wh_drop:<mac>:Paused " counter drop comment "wh_drop:<mac>:Paused"`.

    This is the wire contract the production nflog reader (#1126, via
    logread → spool) depends on. Pinning it here means a future render.lua
    refactor cannot silently drop the `log prefix` or rename the token without
    a CI red.
    """
    mac = client.mac

    paused = snapshot_with_assigned_device(
        etag='"sha256:nflog-paused-v1"',
        mac=mac,
        paused=True,
    )
    paused_etag = fake_api.serve_snapshot(paused)
    fake_api.wait_for_etag_served(etag=paused_etag, timeout_s=240)

    # The agent applies the snapshot asynchronously after the fetch — the
    # serve doesn't guarantee the new ruleset is live yet. Poll the live nft
    # ruleset for up to one apply cycle.
    def _drop_rule_landed():
        res = router_ssh(
            "nft list chain inet wifihaven wifihaven_block 2>/dev/null || true",
            check=False, timeout=10,
        )
        body = res.stdout or ""
        # Order in render.lua: `ether saddr <mac> log prefix "wh_drop:<mac>:Paused "
        # counter drop comment "wh_drop:<mac>:Paused"`. We don't depend on
        # whitespace beyond what nft normalises, so substring-match the parts
        # the production reader relies on.
        needles = [
            f"ether saddr {mac}",
            f'log prefix "wh_drop:{mac}:Paused "',
            "counter",
            "drop",
            f'comment "wh_drop:{mac}:Paused"',
        ]
        if all(n in body for n in needles):
            return body
        return None

    wait_until(
        _drop_rule_landed,
        timeout_s=60,
        interval_s=2,
        description=f"per-MAC drop with log prefix + wh_drop comment for {mac}",
    )


# ── dynamic event emission (production reader, #1126) ───────────────────────


def test_paused_mac_https_traffic_surfaces_as_nflog_event(router, client, fake_api):
    """Drive HTTPS (port 443) from a paused MAC and assert a synthesized
    `connection_attempt(allowed=false, reason=Paused)` arrives at the fake.

    HTTPS specifically — port 80 has a DNAT redirect to the local block page,
    so a port-80 hit gets *forwarded* to uhttpd and is observed by the
    existing conntrack-confirmed path (i.e. pre-#1122 already saw it). The
    bug #1122 fixes is invisible for everything that isn't port 80: the
    forward-chain drop happens between prerouting and postrouting, conntrack
    never confirms, IPCT_NEW never fires. Driving port 443 here proves the
    nflog path is what surfaces the event, not the legacy DNAT trick.
    """
    mac = client.mac

    paused = snapshot_with_assigned_device(
        etag='"sha256:nflog-emit-v1"',
        mac=mac,
        paused=True,
    )
    fake_api.wait_for_etag_served(
        etag=fake_api.serve_snapshot(paused), timeout_s=240,
    )

    # Drop happens at the forward chain — the connect attempt is enough; we
    # don't care whether the TLS handshake completes (it won't).
    http_get(client, "https://example.com/", timeout_s=5)

    def _event_landed():
        evs = fake_api.events_for_mac(mac, allowed=False, reason="Paused")
        return evs or None

    wait_until(
        _event_landed,
        timeout_s=120,
        interval_s=2,
        description=f"nflog-synthesized Paused event for {mac}",
    )
