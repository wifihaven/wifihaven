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
    # Clear the ws-health sentinel on teardown so it never leaks to the next
    # test. The sidecar removes it only on a *failed/dropped* connect; a procd
    # stop (ws disabled here) kills the sidecar without clearing, so a sentinel
    # from a successful connection would otherwise survive. See _reset_ws_health.
    _reset_ws_health()


def _reset_ws_health() -> None:
    """Remove the ws-health sentinel on the router so a *stale* one can't be
    read as a live connection.

    The sidecar touches WS_HEALTH_PATH only after a successful connect and
    removes it on a failed/dropped connect — but nothing clears it on a procd
    stop (ws disabled) or at sidecar startup. So the sentinel written by the
    positive test's successful connection survives `_disable_ws` and is still
    on disk when the negative test re-enables ws against the wrong-host target.
    That test polls immediately and would catch the STALE file — present for
    ~1s until the sidecar's first failed connect clears it — a false
    "wrongly connected". Proven on-device (openwrt.lan, identical to prod:
    OpenWrt 25.12.3 / luaossl-20220711 / OpenSSL 3.5.6): wrong.host.badssl.com
    is rejected at starttls and the sentinel is gone by t≈2s, but observable at
    t≈1s. Clearing it up front makes any later appearance genuine.
    """
    router_ssh(f"rm -f {WS_HEALTH_PATH}", check=False, timeout=10)


def _reset_ws_metrics() -> None:
    """Remove the sidecar's tmpfs metric tally so a count read afterwards can
    only have come from a connect attempt made after this call.

    #2642. Every assertion below is "a fresh attempt happened", and the tally is
    NOT a monotonic series across restarts: `ws_metrics.new()` starts empty and
    `ws_metrics.flush` is a truncating write, so the restart these tests use to
    apply their uci change re-bases every counter to 0 (documented at the top of
    openwrt/files/usr/lib/lua/wifihaven/ws_metrics.lua — the agent folds it as a
    counter reset). Reading a pre-restart baseline and asserting the post-restart
    value EXCEEDS it is therefore wrong, and it broke the moment #2608 made ws
    default-on: the sidecar was then already connected and already counting
    before the test ran, so the baseline was >= 1 while the post-restart tally
    started again from 1 — a deterministic failure of a test that had passed for
    months only because a default-OFF sidecar left the baseline at 0.

    Clearing the file up front removes the need for a baseline at all: any count
    observed afterwards is new.

    What makes that sound is the sidecar's own startup, not this `rm`: a fresh
    instance builds an empty tally and flushes it before connecting
    (`openwrt/files/usr/sbin/wifihaven-ws`, the `ws_metrics.new()` →
    `set(ws_state, 0)` → `flush` sequence), so the file the poll below reads
    cannot carry a pre-restart count. The `rm` narrows the window rather than
    closing it — between the `rm` and the restart the still-running default-on
    sidecar flushes on every `inc`, so it can briefly re-create the file with its
    old counts — which is why the assertion is written against the post-restart
    tally and the restart is what it depends on.
    """
    router_ssh(f"rm -f {WS_METRICS_PATH}", check=False, timeout=10)


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
    # Start from a clean sentinel AND a clean metric tally so both assertions
    # below reflect a genuine new connection, not a leftover from a prior
    # run/boot — and, since #2608 made ws default-on, not the connection the
    # sidecar had already made before this test ran (#2642, _reset_ws_metrics).
    _reset_ws_health()
    _reset_ws_metrics()
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
            lambda: _ws_metric("ws_connect_total", "ok") >= 1,
            timeout_s=30, interval_s=3,
        )
        assert got_ok, "ws_connect_total{result=ok} never recorded a successful connect"
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

    # Critical: clear any sentinel the positive test left behind BEFORE enabling
    # ws against the wrong-host target. Without this the poll below catches that
    # stale file and reports a false "wrongly connected" even though the sidecar
    # correctly rejects wrong.host.badssl.com at starttls (see _reset_ws_health).
    # Clear the tally for the same reason the positive test does: the restart
    # below re-bases it, so a pre-restart baseline is not a baseline (#2642).
    _reset_ws_health()
    _reset_ws_metrics()
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
        assert _ws_metric("ws_connect_total", "upgrade_fail") >= 1, (
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
