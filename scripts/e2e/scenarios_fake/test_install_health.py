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
# The pattern every pgrep/pkill in this file uses. The bracket is load-bearing —
# see _ws_sidecar_pids. CRITICAL: the literal `wifihaven-ws` must not appear
# ANYWHERE in a remote command that also runs pgrep/pkill, because dropbear runs
# the whole command through `sh -c` and the parent shell's own cmdline is then a
# match. That is not hypothetical: a first draft did `mv /usr/sbin/wifihaven-ws
# … ; pkill -f '[w]ifihaven-ws'` and the pkill SIGKILLed its own parent, so the
# loop ran once and the `rm` after it never executed.
WS_PATTERN = "[w]ifihaven-ws"
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
    """PIDs of the running wifihaven-ws sidecar, [] when it is not running.

    The bracket in the pattern is load-bearing. dropbear runs the remote command
    as `sh -c "pgrep -f '<pattern>' || true"`, so the pattern is in the parent
    shell's own /proc/<pid>/cmdline; busybox pgrep skips only its OWN pid, not
    its parent, and would match that shell every time — making "the sidecar is
    running" trivially true and "the sidecar is dead" unreachable. `[w]ifihaven`
    matches the real process's cmdline but not the literal pattern string.
    """
    res = router_ssh(f"pgrep -f '{WS_PATTERN}' || true", check=False, timeout=20)
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

    The kill loop is sized off the init script's own budget: `procd_set_param
    respawn 3600 5 5` (openwrt/files/etc/init.d/wifihaven, the ws instance) means
    5s between attempts and 5 retries inside a 3600s window, so 7 kills spaced 5s
    apart exhaust it. The `wait_until` below is the real assertion — if procd
    were still respawning, it would fail rather than pass on the assumption.

    Cleanup note: nothing is undone here. The moved binary and the stopped
    instance live on in the VM until the NEXT `router`-fixture test restores the
    base snapshot at ITS setup — which is how every scenario in this harness
    behaves, and why the fixture restores on entry rather than on exit.
    """
    wait_until(
        _ws_sidecar_pids,
        timeout_s=120,
        description="the ws sidecar to come up before we kill it",
    )

    # Both paths are assembled in the REMOTE shell from split string literals, so
    # the pattern's plain form appears nowhere in the command text — including in
    # WS_HEALTH_PATH, which contains it too.
    kill_cmd = (
        'b="/usr/sbin/wifihaven""-ws"; h="/tmp/wifihaven""-ws-health"; '
        'mv "$b" "$b.disabled"; '
        # Kill it repeatedly so procd burns through its respawn retry budget
        # against a binary that is no longer there, then stops trying. The budget
        # is `procd_set_param respawn 3600 5 5` on the ws instance in
        # openwrt/files/etc/init.d/wifihaven: 5s between attempts, 5 retries in a
        # 3600s window, so 7 kills spaced 5s apart exhaust it.
        f"for i in 1 2 3 4 5 6 7; do pkill -9 -f '{WS_PATTERN}' 2>/dev/null; sleep 5; done; "
        # Clearing the sentinel is what makes the fallback immediate: an ABSENT
        # sentinel is never fresh (ws_outbound.lua), while a stale-but-present one
        # would make the agent wait out ws_fallback_after (300s, longer than this
        # test's budget). This must run — it is why the kill above must not take
        # its own shell down with it.
        'rm -f "$h"; '
        # Fail loudly if the sentinel somehow survived, rather than leaving the
        # assertion below to time out with a misleading message.
        'test ! -e "$h"'
    )
    # The guard that keeps this from regressing: if the plain pattern ever leaks
    # back into the command text, pkill matches its own parent shell and silently
    # eats the rest of the script.
    assert WS_PATTERN.replace("[", "").replace("]", "") not in kill_cmd, (
        f"kill command must not contain the plain sidecar name: {kill_cmd!r}"
    )
    router_ssh(kill_cmd, check=True, timeout=180)

    wait_until(
        lambda: not _ws_sidecar_pids(),
        timeout_s=120,
        description="the ws sidecar to stay dead (procd respawn budget exhausted)",
    )

    # Require an HTTP-transport fetch specifically. The fake records ws pushes in
    # the same list (#2608, so wait_for_etag_served stays transport-agnostic), so
    # asserting only "a new record appeared" could be satisfied by a last push
    # racing the kill — which would prove nothing about the poll resuming.
    after = fake_api.latest_fetch_id()
    wait_until(
        lambda: [
            f
            for f in (fake_api.policy_fetches(since_id=after).get("fetches") or [])
            if f.get("transport") == "http"
        ],
        timeout_s=180,
        interval_s=2.0,
        description="the HTTP policy poll to resume after the sidecar died",
    )
