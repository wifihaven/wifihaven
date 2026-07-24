"""Unit cover for wan_health signature matchers (#2390).

Runs bare from scripts/e2e/lib (the established convention for the
`E2E lib unit tests` job); no VM, no network. Pins the shared-host guest-WAN
DHCP boot-flake signature matchers used to LABEL a WAN heal in the CI logs
(conftest._wait_for_router_wan_healthy) -- confirming the smoke-check-nil half
is recognized, the boot-time udhcpc line is treated as the weaker signal, and a
real-but-wrong smoke IP is not mistaken for the flake.
"""
from wan_health import smoke_check_nil_signature, wan_lease_flake_signature

# Representative router console / agent-log lines (syslog-prefixed, as the
# harness reads them via router_serial_log / `logread -e wifihaven`).
UDHCPC_LINE = (
    "Tue Jul 23 01:24:11 2026 daemon.err wifihaven-agent: udhcpc: no lease, failing"
)
SMOKE_NIL_LINE = (
    "Tue Jul 23 01:24:14 2026 user.warn wifihaven: policy.apply: smoke check "
    'failed; dnsmasq may be serving a stale config -- got "nil" for tiktok.com '
    "(expected a real upstream IP)"
)


def test_detects_udhcpc_no_lease():
    assert wan_lease_flake_signature(UDHCPC_LINE) is True


def test_detects_smoke_check_nil():
    assert wan_lease_flake_signature(SMOKE_NIL_LINE) is True


def test_detects_either_signature_across_separate_blobs():
    # Callers pass (serial, agent_log) as separate args; a hit in either wins.
    assert wan_lease_flake_signature("boot ok\n" + UDHCPC_LINE, "unrelated") is True
    assert wan_lease_flake_signature("unrelated", SMOKE_NIL_LINE) is True


def test_healthy_logs_are_not_flagged():
    healthy = (
        "daemon.notice wifihaven-agent: udhcpc: lease of 10.0.2.15 obtained\n"
        "user.info wifihaven: policy.apply: smoke check ok -- tiktok.com -> 1.2.3.4"
    )
    assert wan_lease_flake_signature(healthy) is False


def test_empty_or_blank_input_is_not_flagged():
    assert wan_lease_flake_signature() is False
    assert wan_lease_flake_signature("", "") is False


def test_smoke_check_with_real_but_wrong_ip_is_not_flagged():
    # A smoke check that returned a real-but-wrong IP is NOT this DHCP flake and
    # must stay loud (could be a genuine upstream/config regression).
    line = (
        "policy.apply: smoke check failed; dnsmasq may be serving a stale config "
        '-- got "9.9.9.9" for tiktok.com (expected a real upstream IP)'
    )
    assert wan_lease_flake_signature(line) is False
    assert smoke_check_nil_signature(line) is False


# ── smoke_check_nil_signature: the per-scenario-reliable root-cause signal ────


def test_smoke_nil_signal_matches_smoke_line():
    assert smoke_check_nil_signature(SMOKE_NIL_LINE) is True


def test_smoke_nil_signal_ignores_bare_udhcpc_line():
    # smoke_check_nil is the stronger, per-scenario-reliable signal: the
    # boot-time udhcpc line alone is a cold-boot transient the base snapshot
    # captures and loadvm replays every scenario, so it must NOT be mistaken for
    # an in-scenario smoke-nil.
    assert smoke_check_nil_signature(UDHCPC_LINE) is False
    # ...while the broad matcher still surfaces it as the weaker fallback tag.
    assert wan_lease_flake_signature(UDHCPC_LINE) is True


def test_smoke_nil_signal_empty_is_false():
    assert smoke_check_nil_signature() is False
    assert smoke_check_nil_signature("", "") is False
