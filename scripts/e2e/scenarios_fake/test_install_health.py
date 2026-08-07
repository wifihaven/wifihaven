"""Install-time health asserts against the booted router VM (Gate 2, #922).

The `router_session` fixture has already booted the router, run apk postinst,
enrolled, and confirmed at least one policy poll. Once we reach this point
the install must look healthy from the router's perspective.

Why cron specifically (#922): apk postinst (#869/#873) registers the
`wifihaven-update` cron entry; the agent self-heals it on startup
(#896/#899) precisely because installs have historically completed without
it, leaving a router that silently never auto-updates. If both paths fail
the only signal is a router that drifts further out of date every day —
exactly the kind of regression Gate 2 should fail loudly on.

#2608 adds the transport asserts. Its two acceptance criteria are both
install-time health properties, so they live here rather than in a new suite:
a brand-new install must come up on the websocket with NO manual UCI step,
and a router whose sidecar has died must still have a transport — the HTTP
poll — rather than nothing at all. The VM is the real target for both: real
procd, real libuci, real Lua 5.1, and the uci-defaults migration running at
first boot exactly as it does on hardware.
"""
from __future__ import annotations

import pytest

from lib.vm import router_ssh
from lib.wait import wait_until

pytestmark = pytest.mark.install_health

CRONTAB_PATH = "/etc/crontabs/root"
EXPECTED_SCRIPT = "/usr/sbin/wifihaven-update"
WS_BIN = "/usr/sbin/wifihaven-ws"
WS_HEALTH_PATH = "/tmp/wifihaven-ws-health"


def test_wifihaven_update_cron_entry_present(router):
    """After boot + first poll, /etc/crontabs/root must schedule wifihaven-update."""
    res = router_ssh(f"grep wifihaven-update {CRONTAB_PATH}", check=False)
    assert res.returncode == 0, (
        f"grep wifihaven-update {CRONTAB_PATH} exited {res.returncode}; "
        f"stdout={res.stdout!r} stderr={res.stderr!r}"
    )
    matched = res.stdout.strip()
    assert matched, f"empty grep match in {CRONTAB_PATH}"
    assert EXPECTED_SCRIPT in matched, (
        f"cron entry does not point at {EXPECTED_SCRIPT}: {matched!r}"
    )


def _ws_sidecar_pids() -> list[str]:
    res = router_ssh(f"pgrep -f '{WS_BIN}' || true", check=False, timeout=20)
    return [p for p in (res.stdout or "").split() if p.strip()]


def test_ws_is_the_default_transport_after_install(router):
    """#2608 acceptance: a fresh install comes up on ws with no manual UCI step.

    Three observables, all on the real target:

    * `wifihaven.ws.enabled` is UNSET. The shipped conffile deliberately writes
      no value; "unset" is the state every reader turns into 1, and keeping it
      absent is what lets the migration tell "nobody chose this" apart from a
      hand-authored opt-out.
    * `wifihaven.ws.default_on_migrated` is 1, i.e. the one-shot uci-defaults
      migration actually ran on this box (at first boot here; via postinst on an
      in-place upgrade). Its presence is what makes any FUTURE `enabled=0`
      durable — the script never rewrites the key again.
    * The `wifihaven-ws` procd instance is actually running. The init script's
      `config_get ws_enabled ws enabled '1'` is the third reader of the default,
      and this is the only place it gets exercised against real procd.
    """
    enabled = router_ssh(
        "uci -q get wifihaven.ws.enabled || true", check=False, timeout=20
    ).stdout.strip()
    assert enabled == "", (
        "a fresh install must leave wifihaven.ws.enabled unset so the default "
        f"applies; got {enabled!r}"
    )

    marker = router_ssh(
        "uci -q get wifihaven.ws.default_on_migrated || true", check=False, timeout=20
    ).stdout.strip()
    assert marker == "1", (
        "the #2608 uci-defaults migration did not run (or did not record its "
        f"marker); wifihaven.ws.default_on_migrated={marker!r}"
    )

    pids = wait_until(
        _ws_sidecar_pids,
        timeout_s=120,
        description=f"the {WS_BIN} sidecar to be running with no UCI step",
    )
    assert pids, f"no {WS_BIN} process"


def test_dead_ws_sidecar_falls_back_to_the_http_poll(router, fake_api):
    """#2608 acceptance: killing the sidecar leaves the router still reporting.

    This is the failure mode that makes the default flip safe. We simulate a
    sidecar that cannot stay up — move the binary aside, then kill it, so every
    procd respawn fails its exec and procd gives up after its retry budget — and
    then clear the health sentinel the way a clean sidecar exit would. The agent
    reads an absent sentinel as "the link is down", which un-dormants the policy
    poll (`ws_outbound.is_healthy`, #2037) and puts the outbound tee back on
    `http_post`. A fresh `GET /api/router/policy` arriving at the fake AFTER the
    sidecar is gone is the proof: the router still has a transport.

    The VM snapshot is restored by the `router` fixture, so the moved binary and
    the killed instance do not leak into the next scenario.
    """
    wait_until(
        _ws_sidecar_pids,
        timeout_s=120,
        description="the ws sidecar to come up before we kill it",
    )

    router_ssh(
        f"mv {WS_BIN} {WS_BIN}.disabled; "
        # Kill it repeatedly so procd burns through its respawn retry budget
        # against a binary that is no longer there, then stops trying.
        f"for i in 1 2 3 4 5 6 7; do pkill -9 -f '{WS_BIN}' 2>/dev/null; sleep 3; done; "
        f"rm -f {WS_HEALTH_PATH}",
        check=False,
        timeout=120,
    )

    wait_until(
        lambda: not _ws_sidecar_pids(),
        timeout_s=120,
        description="the ws sidecar to stay dead (procd respawn budget exhausted)",
    )

    after = fake_api.latest_fetch_id()
    fake_api.wait_for_policy_fetch(after_id=after, timeout_s=180)
