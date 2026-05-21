"""Suite D — daily time-limit enforcement (fake-mode #684).

Live-mode `scenarios/test_time_limit.py` covers:
  - D1 (in `test_04_daily_limit.py`): exhaustion blocks.
  - D2: minute-granularity of usage accounting (#295, #516).
  - D3: cross-day rollover (#334).

In fake mode the API-side evaluator that turns "5 minutes of activeSeconds
in a 60-minute budget" into a `blocked=True` snapshot is out of scope — the
fake just serves whatever snapshot we POST. So Suite D collapses to:

  - One scenario: snapshot has `blocked=True, blockReason=TimeLimit` →
    MAC ends up in blocked_macs and the router emits an event with
    `reason=timelimit`.

D2 (minute-granularity) verifies the API's processing of agent-side usage
reports — orthogonal to router behavior. Tracked in live-mode test_time_limit.py.
D3 (cross-day rollover) is pending #334 even in live mode.

Folds `test_04_daily_limit.py::D1` per #684: same wire shape (blocked=True
with a time-bucket-derived reason), no separate case needed in fake mode.
"""
from __future__ import annotations

import pytest

from ._observers import (
    wait_block_page,
    wait_event_for_mac,
    wait_mac_in_blocked_set,
)
from .snapshot_builder import SnapshotBuilder

pytestmark = pytest.mark.time_limit

PROFILE_ID = 700


def test_time_limit_exhausted_blocks(router, client, fake_api):
    """D1 (folded with D4): snapshot reports time-limit exhausted → MAC blocked,
    event reason=timelimit."""
    snap = (
        SnapshotBuilder()
        .add_profile(
            id=PROFILE_ID, name="e2e-time",
            blocked=True, block_reason="TimeLimit",
        )
        .add_device(mac=client.mac, name="e2e-time-dev", profile_id=PROFILE_ID)
        .build(etag='"sha256:timelimit-exhausted-v1"')
    )
    etag = fake_api.serve_snapshot(snap)
    fake_api.wait_for_etag_served(etag=etag, timeout_s=240)

    wait_mac_in_blocked_set(client.mac, present=True, timeout_s=180)
    # Drive client traffic so the agent emits a blocked connection_attempt
    # event with reason=timelimit. Without packets, blocked_macs is hot but
    # no event is generated.
    wait_block_page(client, timeout_s=60)
    wait_event_for_mac(
        fake_api, client.mac, allowed=False, reason="timelimit", timeout_s=60,
    )
