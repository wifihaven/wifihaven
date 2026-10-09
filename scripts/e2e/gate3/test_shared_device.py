"""Gate 3 — a real check-in unblocks a shared device on a real router (#2879).

Epic #2841, design `docs/design/shared-devices.md` §11. One round trip against
the real API with the router the gate boots:

  1. Mark the client's device shared (PATCH /api/devices/{mac} {shared:true}).
     Nobody holds it, so `PolicyService.effectiveDeviceRules` ships it as a
     whole-MAC block (`CheckedOut`) — the router serves the block page.
  2. Check it in to the scratch profile (POST /api/shared-devices/{mac}/check-in).
     The device takes the profile's rules, so it is unblocked. The time from the
     request to the first upstream response is logged against the design's 5 s
     p95 target (`ws_push_apply_latency_seconds` is the server-side series).
  3. Check it out (POST /api/shared-devices/{mac}/check-out) — blocked again.

No new wire field is involved: a checked-out shared device rides the existing
per-device `rules` override (blocked=true), and a checked-in one carries its
holder's `profileId`. What this pins is that the two transitions reach a router
customers are running, through the real push path, quickly.

Version skew. Gate 3a runs this against whatever API staging serves, which can
predate #2848 (staging deploys only when Master API/UI CD is green). The test
probes `GET /api/shared-devices` first: on Gate 3a a 404 skips, because the
router under test cannot be judged against an API that lacks the feature; on
Gate 3b the API is the commit under test, so a 404 fails.

Liveness anchor (absence assertions need one). Each "blocked" sample is
paired with a probe of `captive.apple.com`, an infra host the API ships in
`global.extraAllowed` (`InfraHosts.canonical`), which the router carves out
ahead of every whole-MAC drop. A router that has stopped forwarding fails the
anchor instead of passing as "blocked". The pair is retried together, never the
anchor alone.
"""
from __future__ import annotations

import logging
import os
import time

import pytest

from lib.traffic import http_get
from lib.wait import wait_until

from .conftest import WH_GATE3_SIDE
from .test_smoke import _BLOCK_MARKERS, _block_page_probe, _upstream_probe

log = logging.getLogger(__name__)

# The neutral host the whole-MAC block is observed on: neither of the scratch
# profile's apps, so once checked in it is plain upstream content. The
# client_vm fixture already gated its DNS.
NEUTRAL_HOST = "example.com"

# In `global.extraAllowed` on every snapshot (InfraHosts.canonical). Apple's
# captive-portal probe answers plain HTTP/80 with a "Success" page.
ANCHOR_URL = "http://captive.apple.com/hotspot-detect.html"

# Design §11 target, logged only: one sample is not a p95.
TARGET_UNBLOCK_S = 5.0
# The assertion bound. Generous for a shared CI runner and staging; a breach
# means the check-in did not reach the router at all, not that it was slow.
UNBLOCK_BOUND_S = 60.0
# Probe cadence while timing the unblock. Each probe is an SSH round trip into
# the client VM, so the measured latency is an upper bound with this resolution
# plus one probe's duration.
UNBLOCK_INTERVAL_S = 0.25
# Waits for the block page, same timeout and cadence as test_smoke.py's
# whole-MAC block-page wait, which covers the same push-and-apply path.
BLOCKED_TIMEOUT_S = 180
BLOCKED_INTERVAL_S = 3


@pytest.fixture()
def require_shared_device_routes(admin):
    try:
        admin.list_shared_devices()
    except RuntimeError as e:
        if "HTTP 404" not in str(e):
            raise
        msg = (f"{admin.base_url} has no GET /api/shared-devices "
               f"(predates #2848)")
        if WH_GATE3_SIDE == "3a":
            pytest.skip(f"{msg}; Gate 3a runs against deployed staging")
        pytest.fail(f"{msg}; Gate 3b deploys the API under test, so it must have it")


def test_shared_device_check_in_unblocks(require_shared_device_routes, admin,
                                         enrolled_router, client_vm,
                                         scratch_profile_and_device):
    mac = scratch_profile_and_device["mac"]
    profile_id = scratch_profile_and_device["profile_id"]

    admin.patch_device(mac, shared=True)
    try:
        # ── shared, nobody holds it → whole-MAC block ────────────────────
        _wait_blocked_with_anchor(client_vm, phase="after marking shared")

        # ── check in → unblocked ─────────────────────────────────────────
        clicked = time.monotonic()
        admin.check_in_shared_device(mac, profile_id=profile_id)
        request_s = time.monotonic() - clicked
        probes = 0

        def _unblocked():
            nonlocal probes
            probes += 1
            return _upstream_probe(client_vm, NEUTRAL_HOST)

        wait_until(
            _unblocked, timeout_s=UNBLOCK_BOUND_S, interval_s=UNBLOCK_INTERVAL_S,
            description=f"{NEUTRAL_HOST} upstream after check-in of {mac}",
        )
        elapsed = time.monotonic() - clicked
        log_fn = log.info if elapsed <= TARGET_UNBLOCK_S else log.warning
        log_fn(
            "gate3: shared device %s unblocked %.2fs after check-in "
            "(target %.1fs p95; check-in request %.2fs; %d probe(s), upper bound)",
            mac, elapsed, TARGET_UNBLOCK_S, request_s, probes,
        )
        _step_summary(
            f"Shared-device check-in unblock ({WH_GATE3_SIDE}): {elapsed:.2f}s "
            f"(target {TARGET_UNBLOCK_S:.0f}s p95, one sample, upper bound)"
        )

        # ── check out → blocked again ────────────────────────────────────
        admin.check_out_shared_device(mac)
        _wait_blocked_with_anchor(client_vm, phase="after check-out")
    finally:
        # Unshare and hand the device back to the scratch profile in one
        # request (`shared` applies first, so the profileId is legal). This
        # also ends a check-in left open by a failure above. The fixture then
        # deletes the device and profile.
        try:
            admin.patch_device(mac, shared=False, profileId=profile_id)
        except Exception as e:  # noqa: BLE001
            log.warning("restore of device %s failed: %s", mac, e)


def _wait_blocked_with_anchor(client, *, phase: str) -> None:
    """Wait until the neutral host returns the block page AND the global-allow
    anchor returns upstream content, both in the same sample."""
    last: dict[str, bool] = {}

    def _pair():
        blocked = _block_page_probe(client, NEUTRAL_HOST)
        anchor = _anchor_probe(client)
        last.update(blocked=blocked is not None, anchor=anchor is not None)
        return (blocked, anchor) if blocked and anchor else None

    try:
        blocked, _ = wait_until(
            _pair, timeout_s=BLOCKED_TIMEOUT_S, interval_s=BLOCKED_INTERVAL_S,
            description=f"block page for {NEUTRAL_HOST} with live anchor ({phase})",
        )
    except TimeoutError as e:
        if not last:
            raise AssertionError(
                f"{phase}: no probe pair completed in {BLOCKED_TIMEOUT_S}s "
                f"(every sample raised): {e}",
            ) from e
        raise AssertionError(
            f"{phase}: last sample block_page={last.get('blocked')} "
            f"anchor={last.get('anchor')} — block_page=False means the router "
            f"did not block the shared device; anchor=False means the router "
            f"or its global allow is not forwarding, so no block can be judged",
        ) from e
    log.info("gate3: %s — block page for %s (%d bytes), anchor live",
             phase, NEUTRAL_HOST, len(blocked.body))


def _anchor_probe(client):
    """The HttpProbe iff the captive-portal probe returned Apple's upstream
    "Success" page, not the block page. None otherwise."""
    p = http_get(client, ANCHOR_URL, timeout_s=8)
    if p.http_code != 200:
        return None
    body_lc = (p.body or "").lower()
    if "success" not in body_lc or any(m in body_lc for m in _BLOCK_MARKERS):
        return None
    return p


def _step_summary(line: str) -> None:
    """Append to the GitHub Actions job summary when running in CI, so the
    latency sample is visible without opening the log."""
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not path:
        return
    try:
        with open(path, "a", encoding="utf-8") as f:
            f.write(line + "\n")
    except OSError as e:
        log.warning("could not write step summary: %s", e)
