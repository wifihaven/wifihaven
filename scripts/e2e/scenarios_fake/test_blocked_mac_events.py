"""#1122 — router deploy gate: forward-drop visibility via nflog.

Runs in the same Gate 2 (fake API + real router VM) lane as test_pause.py.
Validates the router-side half of #1122:

  1. The deployed agent ships `nflog.lua` and `render.lua` installs per-MAC
     drop rules that carry `log group 1 counter drop comment "wh_drop:<mac>:<reason>"`.
     This guards the data-plane contract end-to-end across the package build,
     so if a future refactor drops `log group` from one of the drop families,
     a router-side regression fires here in CI instead of in production.

  2. With the snapshot pausing a MAC, the agent posts a synthesized
     `connection_attempt(allowed=false, reason=Paused)` event for client
     traffic to the fake API. This validates the full nflog → batcher →
     /api/router/events path on the real OpenWRT VM.

Step 2 depends on the production nflog reader landing (#1126). Until that
ships, only the static contract assertions in step 1 run; the dynamic
emission test is skipped with an explicit reference to the gating issue.
That keeps the file in CI rotation (so build/install regressions are caught
on every router publish) without producing a hard red on a known-pending
follow-up.
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


def test_nflog_module_is_installed_on_router(router):
    """The agent package ships openwrt/files/usr/lib/lua/wifihaven/nflog.lua.

    A package-build regression that drops the module (typo in the makefile
    glob, file rename without update) fails the agent boot via the explicit
    `require("wifihaven.nflog")` in /usr/sbin/wifihaven-agent — but only when
    the agent restarts. This assertion catches the missing file at deploy
    time, before any agent restart, so a bad release never ships."""
    res = router_ssh(
        "test -f /usr/lib/lua/wifihaven/nflog.lua && echo ok || echo missing",
        check=False, timeout=10,
    )
    assert (res.stdout or "").strip() == "ok", (
        f"nflog.lua not installed on router: stdout={res.stdout!r} "
        f"stderr={res.stderr!r}"
    )


def test_paused_snapshot_emits_per_mac_drop_with_log_group_and_wh_drop_comment(
    router, client, fake_api,
):
    """With a paused profile, the live nft ruleset on the router carries a
    per-MAC drop with `log group 1 counter drop comment "wh_drop:<mac>:Paused"`.

    This is the wire contract every nflog consumer (production reader in
    #1126, ulogd sidecars, ad-hoc `nft monitor trace` debugging) depends on.
    Pinning it here means a future render.lua refactor cannot silently drop
    the `log group` or rename the comment prefix without a CI red.
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
        # Order in render.lua: `ether saddr <mac> log group 1 counter drop
        # comment "wh_drop:<mac>:Paused"`. We don't depend on whitespace
        # beyond what nft normalises, so substring-match the parts that
        # downstream consumers rely on.
        needles = [
            f"ether saddr {mac}",
            "log group 1",
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
        description=f"per-MAC drop with log group + wh_drop comment for {mac}",
    )


# ── dynamic event emission (gated on #1126) ────────────────────────────────


@pytest.mark.skip(
    reason="#1126 — production nflog reader (non-blocking glue alongside "
    "conntrack.watch) not yet wired in wifihaven-agent. The static contract "
    "above guarantees the data-plane is correct; this test un-skips when the "
    "reader lands.",
)
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
