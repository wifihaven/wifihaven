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

#2608 added the transport asserts and #2736 reshaped them, because the fallback
they described is gone. Both criteria are install-time health properties, so
they live here rather than in a new suite: a brand-new install must come up on
the websocket with NO manual UCI step, and a router whose sidecar has died must
keep ENFORCING its last on-disk snapshot while making no HTTP policy fetch at
all — there is no longer any code that could. The VM is the real target for
both: real procd, real libuci, real Lua 5.1, real nftables.
"""
from __future__ import annotations

import time

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

# How long to watch for an HTTP policy fetch that must never come. The removed
# poll ran at `policy_poll_interval` (5s shipped), so 30s is six windows it
# would have used — long enough that a surviving poll shows up, short enough not
# to stretch the scenario.
POLL_ABSENCE_WINDOW_S = 30.0


def _wifihaven_nft_ruleset() -> str:
    """The agent's installed nftables table, or "" if it is not there.

    Enforcement lives in `table inet wifihaven`; its presence is the observable
    for "this router is still blocking", independent of any transport.
    """
    res = router_ssh(
        "nft list table inet wifihaven 2>/dev/null || true", check=False, timeout=30
    )
    return (res.stdout or "").strip()


def _fake_api_reachable_from_router() -> bool:
    """Can the router reach the very endpoint the removed poll used to call?

    The liveness anchor for the no-poll assertion. Curls the agent's own
    configured `api_url` + /api/router/policy WITHOUT a bearer token and checks
    for a real HTTP status rather than curl's 000 (no connection). Unauthenticated
    is deliberate on both counts: it proves the network path without needing the
    token, and the fake rejects it at `_require_bearer` BEFORE recording a fetch,
    so this probe cannot itself pollute the list we are about to assert is empty.
    """
    res = router_ssh(
        "u=$(uci -q get wifihaven.wifihaven.api_url); "
        'curl -s -o /dev/null -m 10 -w "%{http_code}" "$u/api/router/policy" '
        "2>/dev/null || echo 000",
        check=False,
        timeout=40,
    )
    code = (res.stdout or "").strip().splitlines()[-1:] or ["000"]
    return code[0] not in ("", "000")


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


def test_ws_is_the_only_transport_after_install(router):
    """#2608/#2736 acceptance: a fresh install comes up on ws with no UCI step.

    Two observables, both on the real target:

    * There is NO `wifihaven.ws.enabled` key to consult. #2736 removed the
      toggle along with the HTTP poll it selected — with the poll gone, an
      opt-out would leave the router unable to receive policy or report usage at
      all. This asserts the shipped conffile carries no such option, so nothing
      can be "off" by configuration.
    * The `wifihaven-ws` procd instance is actually running. The init script now
      starts it unconditionally, and this is the only place that gets exercised
      against real procd.
    """
    enabled = router_ssh(
        "uci -q get wifihaven.ws.enabled || true", check=False, timeout=20
    ).stdout.strip()
    assert enabled == "", (
        "#2736 removed the ws.enabled toggle; a fresh install must ship no such "
        f"key, got {enabled!r}"
    )

    pids = wait_until(
        _ws_sidecar_pids,
        timeout_s=120,
        description=f"the {WS_BIN} sidecar to be running with no UCI step",
    )
    assert pids, f"no {WS_BIN} process"


def test_dead_ws_sidecar_keeps_enforcing_and_never_polls(router, fake_api):
    """#2736 acceptance: losing ws degrades the router, it does not stop it.

    This is the trade #2736 makes, asserted on the real target. Before #2736 a
    dead sidecar un-dormanted the HTTP snapshot poll and the router carried on
    over REST. That fallback is gone, so what must hold now is:

      1. enforcement KEEPS RUNNING off the last on-disk snapshot — the nftables
         ruleset the agent last applied is still installed, and
      2. NO HTTP policy fetch ever reaches the API, because there is no code
         left that could make one.

    Point 2 is an absence, so it gets a LIVENESS ANCHOR: point 1 is checked
    through the same SSH channel in the same window, and the fake is proven
    reachable from the router before we look for the fetch that must not be
    there. A dead harness fails those first rather than passing the absence for
    free.

    We simulate a sidecar that cannot stay up — move the binary aside, then kill
    it, so every procd respawn fails its exec and procd gives up after its retry
    budget — and then clear the health sentinel the way a clean sidecar exit
    would, so the agent sees the link as down immediately rather than waiting out
    the staleness window.

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

    # ANCHOR, part 1: the agent has actually applied a snapshot, so "still
    # enforcing" below is a statement about real installed rules rather than
    # about an agent that never enforced anything.
    ruleset_before = _wifihaven_nft_ruleset()
    assert ruleset_before, (
        "the agent had installed no wifihaven nft table before the sidecar was "
        "killed — nothing to prove survives the outage"
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
        #
        # `pgrep` + `kill`, NOT `pkill`: pkill is absent from at least some
        # OpenWrt busybox builds — verified on a real router (OpenWrt 25.12.5,
        # `ash: pkill: not found`). Its stderr is swallowed by the 2>/dev/null
        # this loop needs anyway, so a pkill that does not exist reads exactly
        # like a kill that found nothing, and the only symptom is this test
        # timing out later on a sidecar that never died. pgrep and kill are both
        # present (pgrep is used by _ws_sidecar_pids above; kill is a shell
        # builtin).
        f"for i in 1 2 3 4 5 6 7; do "
        f"for p in $(pgrep -f '{WS_PATTERN}'); do kill -9 \"$p\" 2>/dev/null; done; "
        f"sleep 5; done; "
        # Clearing the sentinel makes the link read as down at once: an ABSENT
        # sentinel is never fresh (ws_outbound.lua), while a stale-but-present
        # one would make the agent wait out the staleness window first. This must
        # run — it is why the kill above must not take its own shell down with
        # it. The sentinel is /tmp/wifihaven-ws-health, assembled into "$h" above
        # rather than named here for the same reason.
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
    after = fake_api.latest_fetch_id()
    router_ssh(kill_cmd, check=True, timeout=180)

    wait_until(
        lambda: not _ws_sidecar_pids(),
        timeout_s=120,
        description="the ws sidecar to stay dead (procd respawn budget exhausted)",
    )

    # (1) Enforcement survives the outage: the ruleset the agent applied before
    # the sidecar died is still installed. This is the whole reason removing the
    # poll is acceptable — the router degrades to "last known policy", it does
    # not stop blocking.
    ruleset_after = _wifihaven_nft_ruleset()
    assert ruleset_after, (
        "the wifihaven nft table disappeared after the ws sidecar died — losing "
        "the socket must degrade enforcement to the last snapshot, not end it"
    )

    # ANCHOR, part 2: the API is reachable from the router right now, so a fetch
    # COULD have landed. Without this, "no fetch arrived" would also be satisfied
    # by a wedged VM with no network.
    assert _fake_api_reachable_from_router(), (
        "the fake API is not reachable from the router, so the no-poll assertion "
        "below would pass for free"
    )

    # (2) No HTTP policy fetch, ever. The fake records ws pushes in the same list
    # (#2608, so wait_for_etag_served stays transport-agnostic), so this filters
    # on transport rather than on "a new record appeared".
    time.sleep(POLL_ABSENCE_WINDOW_S)
    http_fetches = [
        f
        for f in (fake_api.policy_fetches(since_id=after).get("fetches") or [])
        if f.get("transport") == "http"
    ]
    assert not http_fetches, (
        "the agent made an HTTP policy fetch after the sidecar died — #2736 "
        f"removed that code path entirely: {http_fetches!r}"
    )
