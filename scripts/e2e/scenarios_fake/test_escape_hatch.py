"""Suite: #2381 local enforcement escape hatch (fake-mode Gate 2).

The router-level off switch. A LOCAL UCI flag (wifihaven.settings.
enforcement_disabled, toggled by the `wifihaven-disable` / `wifihaven-enable`
CLI helpers) tears down ALL enforcement — every nftables forward-drop and the
block-page DNAT — so all forwarded traffic passes, INDEPENDENT of the policy
snapshot. It is the offline fallback (works when the API is unreachable); here
we prove the stronger property that it overrides an ACTIVE block snapshot the
fake keeps serving the whole time.

Flow:
  1. Serve a snapshot that blocks a host → the block page is served (baseline
     enforcement is working).
  2. `wifihaven-disable` on the router → within a few seconds the same host is
     reachable, the block page is NOT served, and the nft ruleset carries no
     drop/DNAT — even though the fake is STILL serving the block snapshot.
  3. `wifihaven-enable` → enforcement returns and the block page comes back.
"""
from __future__ import annotations

import pytest

from lib.vm import router_ssh
from lib.wait import wait_until

from ._observers import is_block_page_body, wait_block_page, wait_http_succeeds
from .snapshot_builder import SnapshotBuilder

pytestmark = pytest.mark.escape_hatch

BLOCKED_HOST = "example.org"
PROFILE_ID = 2381


def _enforcement_plane_absent() -> bool:
    """True iff the runtime nft table has no drop rule and no block-page DNAT.

    The permissive (#2381) ruleset keeps the wifihaven_account_tx/rx accounting
    chains but emits neither a `drop` verdict nor the wifihaven_block_nat DNAT
    chain, so their absence is the connection-layer proof that enforcement is
    fully torn down.
    """
    res = router_ssh(
        "nft list table inet wifihaven 2>/dev/null || true",
        check=False, timeout=10,
    )
    out = (res.stdout or "").lower()
    return ("drop" not in out) and ("wifihaven_block_nat" not in out)


def test_escape_hatch_disables_and_restores_enforcement(router, client, fake_api):
    # 1. Baseline: block the host and confirm the block page is served.
    snap = (
        SnapshotBuilder()
        .add_profile(id=PROFILE_ID, name="e2e-escape-hatch", extra_blocked=[BLOCKED_HOST])
        .add_device(mac=client.mac, name="e2e-escape-hatch-dev", profile_id=PROFILE_ID)
        .build(etag='"sha256:escape-hatch-v1"')
    )
    etag = fake_api.serve_snapshot(snap)
    fake_api.wait_for_etag_served(etag=etag, timeout_s=240)

    assert wait_block_page(client, host=BLOCKED_HOST, timeout_s=120) is not None, (
        "baseline: expected the block page for a blocked host before the hatch is on"
    )

    try:
        # 2. Flip the escape hatch ON via the shipped CLI helper. The fake keeps
        #    serving the SAME block snapshot — the hatch must override it locally.
        r = router_ssh("wifihaven-disable", check=True, timeout=30)
        assert "enforcement DISABLED" in (r.stdout or ""), (
            f"wifihaven-disable did not confirm; stdout={r.stdout!r} stderr={r.stderr!r}"
        )

        # The blocked host is now reachable (real upstream, NOT the block page).
        # The agent re-reads the flag every apply cycle, so this lands quickly.
        probe = wait_http_succeeds(client, host=BLOCKED_HOST, timeout_s=90)
        assert probe is not None and probe.http_code is not None and 200 <= probe.http_code < 400
        assert not is_block_page_body(probe.body), "hatch on: must NOT serve the block page"

        # ...and the connection-layer teardown is observable in the ruleset.
        wait_until(
            lambda: True if _enforcement_plane_absent() else None,
            timeout_s=60, interval_s=3,
            description="nft ruleset has no drop/DNAT while the escape hatch is on (#2381)",
        )

        # 3. Flip it back OFF — enforcement returns from the still-served snapshot.
        r = router_ssh("wifihaven-enable", check=True, timeout=30)
        assert "enforcement RE-ENABLED" in (r.stdout or ""), (
            f"wifihaven-enable did not confirm; stdout={r.stdout!r} stderr={r.stderr!r}"
        )

        assert wait_block_page(client, host=BLOCKED_HOST, timeout_s=120) is not None, (
            "after wifihaven-enable: the block page must come back"
        )
    finally:
        # Never leak bypass into a later Gate-2 scenario if an assertion above
        # fails between disable and enable — always restore enforcement.
        router_ssh("wifihaven-enable", check=False, timeout=30)
