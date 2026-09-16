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
import uuid

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

    Pass multiple `settings` to fold them into one restart cycle instead of one
    per setting. `check=False` for cleanup-only calls (see `_restart_ws`) so a
    router already in a broken state can't raise here and obscure the test's
    real assertion failure in the traceback.

    Settings may be empty, which makes this a bare restart cycle.
    """
    prefix = ("; ".join(settings) + "; uci commit wifihaven; ") if settings else ""
    router_ssh(prefix + "/etc/init.d/wifihaven restart", timeout=60, check=check)


def _restart_ws() -> None:
    # #2736: there is no `wifihaven.ws.enabled` any more — the sidecar is the
    # router's only transport and the init script always starts it. A restart is
    # all these tests ever needed: what they actually vary is `api_url`.
    _uci_ws()


def _teardown_ws(*, extra_settings: tuple[str, ...] = ()) -> None:
    # check=False: this only ever runs from a `finally` (see both tests below),
    # so it must never itself raise and hide the original failure.
    _uci_ws(*extra_settings, check=False)
    # This does NOT clear the ws-health sentinel: a sentinel written by a
    # successful connection survives the restart. The next test resets it
    # itself. See _reset_ws_health.


def _reset_ws_health() -> None:
    """Remove the ws-health sentinel on the router so a *stale* one can't be
    read as a live connection.

    The sidecar touches WS_HEALTH_PATH only after a successful connect and
    removes it on a failed/dropped connect — but nothing clears it on a procd
    stop (ws disabled) or at sidecar startup. So the sentinel written by the
    positive test's successful connection survives `_teardown_ws` and is still
    on disk when the negative test re-enables ws against the wrong-host target.
    That test polls immediately and would catch the STALE file — present for
    ~1s until the sidecar's first failed connect clears it — a false
    "wrongly connected". Proven on-device (openwrt.lan, identical to prod:
    OpenWrt 25.12.3 / luaossl-20220711 / OpenSSL 3.5.6): wrong.host.badssl.com
    is rejected at starttls and the sentinel is gone by t≈2s, but observable at
    t≈1s. Clearing it up front makes any later appearance genuine.

    #2788: "up front" means after the pre-restart sidecar is GONE, not merely
    before the restart. Until procd kills it, the old sidecar is still
    connected and re-touches the sentinel on every frame or pong (#2731), so a
    clear done before the restart can be undone before it takes effect. The
    negative test calls this after `_wait_for_sidecar_replaced`.
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


def _ws_router_diag() -> str:
    """Router-side state for a ws assertion message (#2788): when the sentinel
    was written vs now, which sidecar processes are running, the tally, and the
    sidecar's recent log lines. Without it a red run can't tell a stale
    sentinel from a genuinely completed handshake."""
    res = router_ssh(
        "echo now=$(date +%s); "
        f"echo sentinel=$(cat {WS_HEALTH_PATH} 2>/dev/null || echo absent); "
        "echo '--- ps'; ps w | grep '[/]usr/sbin/wifihaven-ws'; "
        f"echo '--- tally'; cat {WS_METRICS_PATH} 2>/dev/null; "
        "echo '--- logread'; logread 2>/dev/null | grep -E 'ws:|wifihaven-ws|procd' | tail -n 40",
        check=False, timeout=15,
    )
    return (res.stdout or "") + (res.stderr or "")


# How a failed connect is logged (ws_loop.run: "ws: connect failed (attempt N):
# <err>", with <err> from ws_client.connect). A TLS-stage rejection is prefixed
# "starttls:" (any starttls failure, a stalled handshake included) or
# "hostname verify:" (the #2182 Lua re-check). A starttls timeout proves nothing
# about verification, so it doesn't count as a rejection (TLS_TIMEOUT_MARKERS).
# An error from AFTER the TLS stage means the handshake to the wrong host was
# accepted: wrong.host.badssl.com is not a ws endpoint, so an
# accepted cert shows up as a rejected upgrade, never as a healthy connection.
TLS_REJECT_PREFIXES = ("starttls:", "hostname verify:")
POST_TLS_ERRORS = ("upgrade rejected", "bad Sec-WebSocket-Accept")
TLS_TIMEOUT_MARKERS = ("timed out", "timeout")


def _mark_router_log() -> str:
    marker = f"gate3-ws-wronghost-{uuid.uuid4().hex}"
    router_ssh(f"logger -t wifihaven-gate3 {marker}", timeout=10)
    return marker


def _ws_connect_failures_since(marker: str) -> list[str] | None:
    """The sidecar's `ws: connect failed` log lines written after `marker`, or
    None if the marker is not in logread (so the caller can't mistake a lost
    log for "no failures")."""
    res = router_ssh("logread 2>/dev/null", check=False, timeout=15)
    lines = (res.stdout or "").splitlines()
    idx = next((i for i, l in enumerate(lines) if marker in l), None)
    if idx is None:
        return None
    return [l for l in lines[idx + 1:] if "ws: connect failed" in l]


def _ws_sidecar_pids() -> set[str]:
    res = router_ssh(
        "ps w | grep '[/]usr/sbin/wifihaven-ws' | awk '{print $1}'",
        check=False, timeout=10,
    )
    return {p for p in (res.stdout or "").split() if p.isdigit()}


def _wait_for_sidecar_replaced(old_pids: set[str]) -> None:
    """Block until no pre-restart sidecar pid is alive and a new one is running
    (#2788). Raises if that never happens, so a restart that left the old
    sidecar up, or never started a new one, fails loudly instead of being
    observed as a clean or dirty ws state."""
    def replaced() -> bool:
        now = _ws_sidecar_pids()
        return bool(now) and not (now & old_pids)

    # 30s: a generous ceiling for procd's stop (SIGTERM, then SIGKILL after its
    # term timeout) plus the respawned start. Not a tuned value.
    if not _poll_until_or_timeout(replaced, timeout_s=30, interval_s=1):
        raise AssertionError(
            f"ws sidecar was not replaced by the restart (old pids {sorted(old_pids)})\n"
            f"router state:\n{_ws_router_diag()}"
        )


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
    _restart_ws()
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
        _teardown_ws()


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

    # Snapshot the sidecar that is running NOW (connected to staging by the
    # positive test) so the restart below can be proven to have replaced it.
    old_pids = _ws_sidecar_pids()
    # An empty snapshot (ssh hiccup, or no sidecar running) would make the
    # replacement wait below accept the old sidecar and bring the race back.
    assert old_pids, (
        "no running ws sidecar found before the restart; can't prove it gets "
        f"replaced\nrouter state:\n{_ws_router_diag()}"
    )
    _uci_ws(f"uci set wifihaven.wifihaven.api_url=https://{WRONG_HOST_TARGET}")
    try:
        # #2788: clearing the sentinel BEFORE the restart is not enough. The old
        # sidecar is still connected to staging until procd kills it, and it
        # re-touches the sentinel on every frame it sends or receives, pongs
        # included (#2731); SIGTERM does not clear it. So the restart can leave a fresh
        # sentinel that no connection to the wrong host ever wrote, and it stays
        # until the new sidecar's first failed connect clears it. Wait until
        # every pre-restart sidecar pid is gone and a new one is running, THEN
        # clear the sentinel and tally. From that point the only writer is the
        # new sidecar, which touches the sentinel only after ws_client.connect
        # returned a client (starttls + hostname re-check + verified 101), so
        # any sentinel seen afterwards is a genuine wrong-host connection.
        _wait_for_sidecar_replaced(old_pids)
        _reset_ws_health()
        _reset_ws_metrics()
        marker = _mark_router_log()

        # The handshake must NOT complete against the mismatched-name target.
        # Watch the whole window; any of these at any poll fails immediately:
        #  - a sentinel or a `ws_connect_total{ok}` (a full ws connection), or
        #  - a connect failure from AFTER the TLS stage. The tally can't tell
        #    those apart from a TLS rejection (both count as `upgrade_fail`,
        #    ws_loop.classify_connect_error), and a regressed verify would land
        #    exactly there, so the reason in the log is what's asserted on.
        tls_rejections: list[str] = []
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            assert not _ws_health_present(), (
                f"ws sidecar reported a healthy connection to {WRONG_HOST_TARGET} "
                "— hostname verification regressed to chain-only (#2153)\n"
                f"router state:\n{_ws_router_diag()}"
            )
            assert _ws_metric("ws_connect_total", "ok") == 0, (
                f"ws_connect_total{{result=ok}} recorded a completed handshake to "
                f"{WRONG_HOST_TARGET} — hostname verification regressed to "
                "chain-only (#2153)\n"
                f"router state:\n{_ws_router_diag()}"
            )
            failures = _ws_connect_failures_since(marker) or []
            post_tls = [l for l in failures if any(e in l for e in POST_TLS_ERRORS)]
            assert not post_tls, (
                f"the TLS handshake to {WRONG_HOST_TARGET} was ACCEPTED (the connect "
                "failed only after TLS) — hostname verification regressed to "
                f"chain-only (#2153):\n{post_tls[0]}\n"
                f"router state:\n{_ws_router_diag()}"
            )
            tls_rejections = [
                l for l in failures
                if any(f"): {p}" in l for p in TLS_REJECT_PREFIXES)
                and not any(m in l.lower() for m in TLS_TIMEOUT_MARKERS)
            ]
            time.sleep(3)

        # Liveness anchor: the clean window above only means something if the
        # new sidecar actually tried the wrong host during it and was turned
        # away at the TLS stage. A sidecar that never started, never reached
        # the host, or whose log was lost writes no such line and fails here.
        assert _ws_connect_failures_since(marker) is not None, (
            f"log marker {marker} is no longer in logread, so the connect "
            f"failures can't be attributed\nrouter state:\n{_ws_router_diag()}"
        )
        assert tls_rejections, (
            "expected at least one connect attempt rejected at the TLS stage "
            f"({' / '.join(TLS_REJECT_PREFIXES)}) against the wrong-hostname "
            f"target {WRONG_HOST_TARGET} after the restart (starttls timeouts don't "
            "count; if every attempt timed out, the router couldn't reach the host)\n"
            f"router state:\n{_ws_router_diag()}"
        )
        assert _ws_metric("ws_connect_total", "upgrade_fail") >= 1, (
            "expected ws_connect_total{result=upgrade_fail} to count the failed "
            f"connect attempts against {WRONG_HOST_TARGET}\n"
            f"router state:\n{_ws_router_diag()}"
        )
    finally:
        # Restore the real staging URL. enrolled_router is session-scoped and
        # its teardown (router_down / delete_router) doesn't depend on this, but
        # leave the agent pointed at something real rather than a
        # deliberately-broken target.
        _teardown_ws(
            extra_settings=(f"uci set wifihaven.wifihaven.api_url={real_api_url}",),
        )
