"""Gate 3 — wss/TLS coverage for the wifihaven-ws sidecar (#2154).

The gap this closes: Gate 2's suite K (#1939,
scripts/e2e/scenarios_fake/test_ws_push_apply.py) drives the real agent's ws
sidecar end-to-end, but only over plain `ws://` against the fake API — TLS is
never negotiated. Gate 3 (this directory) talks to the real HTTPS staging API,
but never enables ws. Neither exercises "ws sidecar establishes a verified
wss:// connection to a real TLS endpoint" — the intersection that let #2153
(VERIFY_PEER with an empty CA trust store, so every wss handshake failed)
reach a prod-cutover attempt undetected.

Reuses the session-scoped `enrolled_router` fixture from conftest.py (already
booted + registered against staging for test_smoke.py), so this only adds a
uci flip + a couple of polls — no second VM boot.

Server-side proof, without a new secret: `GET /metrics` on staging is gated by
`WIFIHAVEN_METRICS_SCRAPE_TOKEN_STAGING` (render.yaml, MetricsRoutes.scala) —
a token this harness does not have and Gate 3 deliberately avoids anything
past the public admin surface. We don't need it: per RouterWsRoutes.scala's
own doc comment, the upgrade handler runs `RouterAuth.authenticate` and only
returns the `101 Switching Protocols` (with the correct Sec-WebSocket-Accept)
if that succeeds and the handler proceeds to register the channel — a client
that observes a completed RFC 6455 handshake could only do so because the
server-side handler already ran. So the sidecar-side proof below (health
sentinel + `ws_connect_total{result=ok}`) is also the server-side proof.
"""
from __future__ import annotations

import logging
import os
import time

from lib.vm import router_ssh

log = logging.getLogger(__name__)

WS_HEALTH_PATH = "/tmp/wifihaven-ws-health"
WS_METRICS_PATH = "/tmp/wifihaven-ws-metrics.txt"

# badssl.com's dedicated hostname-verification test fixture: a valid CA chain
# issued for a DIFFERENT name. Accepting it would mean hostname-verify silently
# regressed to chain-only — the exact MITM hole ws_tls.lua's header comment
# says was manually caught on-device during the #2153 fix ("wrong.host.badssl.com
# ... was ACCEPTED with just the store loaded"). Reused here as the e2e negative
# check for the same regression class.
WRONG_HOST_TARGET = "wrong.host.badssl.com"


def _uci_ws(*settings: str, check: bool = True) -> None:
    """Apply uci settings under the `ws`/`wifihaven` sections, commit, restart.

    Mirrors scenarios_fake/test_ws_push_apply.py's `_enable_ws_and_freeze_poll`.
    Pass multiple `settings` to fold them into one restart cycle instead of one
    per setting. `check=False` for cleanup-only calls (see `_disable_ws`) so a
    router already in a broken state can't raise here and obscure the test's
    real assertion failure in the traceback.
    """
    cmd = "; ".join(settings) + "; uci commit wifihaven; /etc/init.d/wifihaven restart"
    router_ssh(cmd, timeout=60, check=check)


def _enable_ws() -> None:
    _uci_ws("uci set wifihaven.ws.enabled=1")


def _disable_ws(*, extra_settings: tuple[str, ...] = ()) -> None:
    # check=False: this only ever runs from a `finally` (see both tests below),
    # so it must never itself raise and hide the original failure.
    _uci_ws("uci set wifihaven.ws.enabled=0", *extra_settings, check=False)


def _ws_health_present() -> bool:
    res = router_ssh(
        f"[ -s {WS_HEALTH_PATH} ] && echo yes || echo no",
        check=False, timeout=10,
    )
    return (res.stdout or "").strip() == "yes"


def _ws_metric(name: str, label: str) -> int:
    """Parse a `<name>\\t<label>\\t<count>` line from the sidecar's tmpfs tally
    (ws_metrics.lua format — same parsing as suite K's _ws_recv_policy_count)."""
    res = router_ssh(f"cat {WS_METRICS_PATH} 2>/dev/null || true", check=False, timeout=10)
    for line in (res.stdout or "").splitlines():
        parts = line.split("\t")
        if len(parts) == 3 and parts[0] == name and parts[1] == label:
            try:
                return int(parts[2])
            except ValueError:
                return 0
    return 0


def _poll_until_or_timeout(pred, *, timeout_s: float, interval_s: float) -> bool:
    """Like lib.wait.wait_until but returns False on timeout instead of raising —
    for asserting something does NOT happen within the window (the negative
    test below), where a clean timeout is the expected/passing outcome."""
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if pred():
            return True
        time.sleep(interval_s)
    return False


def test_ws_sidecar_wss_handshake(enrolled_router):
    """Enable the ws sidecar against the real HTTPS staging API and assert a
    verified wss:// connection — the path #2153 silently broke.
    """
    baseline_ok = _ws_metric("ws_connect_total", "ok")

    _enable_ws()
    try:
        # The health sentinel is only touched after ws_loop.lua's connect step
        # returns successfully, which for a wss:// URL requires starttls to
        # succeed — the system CA store loaded a valid chain AND the bound
        # hostname matched the connect host (ws_tls.build_context). Pre-#2153
        # this failed on every attempt (empty trust store), so this sentinel
        # never appeared against a real TLS endpoint.
        connected = _poll_until_or_timeout(
            _ws_health_present, timeout_s=90, interval_s=3,
        )
        assert connected, (
            "ws-health sentinel never appeared — sidecar failed the wss:// "
            "handshake against staging (possible #2153 regression: CA store "
            "or hostname verification broken again)"
        )
        got_ok = _poll_until_or_timeout(
            lambda: _ws_metric("ws_connect_total", "ok") > baseline_ok,
            timeout_s=30, interval_s=3,
        )
        assert got_ok, "ws_connect_total{result=ok} never incremented"
        log.info("gate3: wss handshake confirmed via sidecar health + connect metric")
    finally:
        _disable_ws()


def test_ws_sidecar_rejects_wrong_hostname_cert(enrolled_router):
    """Stretch (#2154): a valid-chain cert for the WRONG hostname must still be
    rejected — proves hostname verification (ws_tls.build_context's setHost,
    #2153) hasn't silently regressed to chain-only.

    Requires the router's SLIRP WAN egress to reach badssl.com — the same
    external reachability the gate3 smoke test already depends on for its
    example.com/.org probes.

    NOTE — depends on `test_ws_sidecar_wss_handshake` (above, in this same
    file) having already proven ws CAN connect successfully against a real
    target. A clean rejection here is only meaningful evidence that hostname
    verification specifically caused it if the sidecar is otherwise capable of
    connecting at all — on its own this test can't distinguish "hostname
    mismatch correctly rejected" from "nothing works right now." pytest runs
    a module's tests in source order (no order-randomizing plugin in
    requirements.txt) and both land in the same Gate 3a/3b job, so the pairing
    holds today; don't split or reorder these two without preserving it.
    """
    real_api_url = os.environ.get("WH_API_URL")
    assert real_api_url, "WH_API_URL not set"

    baseline_fail = _ws_metric("ws_connect_total", "upgrade_fail")
    _uci_ws(
        f"uci set wifihaven.wifihaven.api_url=https://{WRONG_HOST_TARGET}",
        "uci set wifihaven.ws.enabled=1",
    )
    try:
        # The handshake must NOT complete against the mismatched-name target.
        # A clean timeout (health sentinel never appears) is the PASSING
        # outcome here — the inverse of the positive test above.
        wrongly_connected = _poll_until_or_timeout(
            _ws_health_present, timeout_s=45, interval_s=3,
        )
        assert not wrongly_connected, (
            f"ws sidecar reported a healthy connection to {WRONG_HOST_TARGET} "
            "— hostname verification regressed to chain-only (#2153)"
        )
        assert _ws_metric("ws_connect_total", "upgrade_fail") > baseline_fail, (
            "expected at least one failed connect attempt "
            f"(ws_connect_total{{result=upgrade_fail}}) against the "
            f"wrong-hostname target {WRONG_HOST_TARGET}"
        )
    finally:
        # Restore the real staging URL in the SAME restart cycle as disabling
        # ws. enrolled_router is session-scoped and its teardown (router_down /
        # delete_router) doesn't depend on this, but leave the agent pointed
        # at something real rather than a deliberately-broken target.
        _disable_ws(
            extra_settings=(f"uci set wifihaven.wifihaven.api_url={real_api_url}",),
        )
