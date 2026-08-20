"""Unit cover for dns_cache.find_ip_host_row — the ip→host matcher behind the
Gate-2 dns cache-settle gates (#2734).

Run bare (cwd = scripts/e2e/lib) per the established e2e-lib convention:

    cd scripts/e2e/lib && python -m pytest test_dns_cache.py -v

Why this exists: the v6 attribution scenario
(``scenarios_fake/test_v6_attribution.py``) fires a single SYN and then waits
for a FQDN connection_event. Under Gate-2 VM load the SYN can beat
wifihaven-dns-tail's ingest of the AAAA reply, so conntrack.lua attributes the
bare v6 literal and the FQDN event never comes — the ~5/6 flake in #2734. The
fix gates the SYN on the shared dns→host cache actually mapping the leaf, using
this matcher. The gate is only meaningful if the matcher reports a MISS on an
unpopulated cache (test_miss_when_cache_empty) — a gate that can't miss asserts
nothing.
"""
from __future__ import annotations

from dns_cache import find_ip_host_row

IP6 = "2001:db8::10"
HOST = "e2e-edge.wifihaven.net"


def test_hit_exact():
    assert find_ip_host_row([(IP6, HOST)], IP6, HOST) == (IP6, HOST)


def test_hit_case_insensitive_host():
    assert find_ip_host_row([(IP6, HOST.upper())], IP6, HOST) == (IP6, HOST.upper())


def test_hit_ignores_surrounding_whitespace():
    # Raw cache lines split to columns can carry stray whitespace.
    assert find_ip_host_row([(f"  {IP6}  ", f"\t{HOST}\t")], IP6, HOST) == (IP6, HOST)


def test_hit_returns_first_match_among_many():
    rows = [("2001:db8::11", "other.wifihaven.net"), (IP6, HOST), (IP6, HOST)]
    assert find_ip_host_row(rows, IP6, HOST) == (IP6, HOST)


def test_miss_when_cache_empty():
    # THE race the gate guards: before dns-tail ingests the AAAA reply the
    # cache holds no row, so the matcher MUST report a miss (→ the gate blocks
    # and, if it never settles, raises TimeoutError) rather than let the SYN
    # fire against an unpopulated cache.
    assert find_ip_host_row([], IP6, HOST) is None


def test_miss_wrong_ip():
    assert find_ip_host_row([("2001:db8::11", HOST)], IP6, HOST) is None


def test_miss_partial_host_is_not_a_match():
    # A subdomain or parent apex sharing the IP must NOT satisfy a leaf gate.
    assert find_ip_host_row([(IP6, f"cdn.{HOST}")], IP6, HOST) is None
    assert find_ip_host_row([(IP6, "wifihaven.net")], IP6, HOST) is None


def test_skips_malformed_short_rows():
    assert find_ip_host_row([(IP6,), (IP6, HOST)], IP6, HOST) == (IP6, HOST)


def test_miss_on_blank_inputs():
    assert find_ip_host_row([(IP6, HOST)], "", HOST) is None
    assert find_ip_host_row([(IP6, HOST)], IP6, "  ") is None
