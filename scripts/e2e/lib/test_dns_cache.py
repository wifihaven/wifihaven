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

from dns_cache import canon_ip, find_ip_host_row

IP6 = "2001:db8::10"  # compressed — the form the scenario passes as LEAF_IP6
IP6_EXPANDED = "2001:0db8:0000:0000:0000:0000:0000:0010"  # the form the cache stores
HOST = "e2e-edge.wifihaven.net"


def test_hit_exact():
    assert find_ip_host_row([(IP6, HOST)], IP6, HOST) == (IP6, HOST)


def test_hit_compressed_query_matches_expanded_cache_row():
    # THE #2734 first-cut bug: wifihaven-dns-tail stores v6 keys in the canonical
    # fully-expanded form, but the scenario queries with the compressed literal.
    # A raw string compare misses even though the mapping is present, so the gate
    # times out against a cache that already had the row. The match must be on
    # the canonical key.
    assert find_ip_host_row([(IP6_EXPANDED, HOST)], IP6, HOST) == (IP6_EXPANDED, HOST)


def test_hit_expanded_query_matches_compressed_cache_row():
    # The reverse spelling must match too (dnsmasq compressed insert, expanded query).
    assert find_ip_host_row([(IP6, HOST)], IP6_EXPANDED, HOST) == (IP6, HOST)


def test_canon_ip_expands_v6_and_leaves_v4_alone():
    assert canon_ip(IP6) == IP6_EXPANDED
    assert canon_ip(IP6_EXPANDED) == IP6_EXPANDED
    assert canon_ip("2001:DB8::10") == IP6_EXPANDED  # case-insensitive
    assert canon_ip("192.0.2.10") == "192.0.2.10"  # v4 untouched
    assert canon_ip("not-an-ip") == "not-an-ip"


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
