"""Tests for opnsense/wifihaven/unbound.py

Run with: pytest opnsense/test/test_unbound.py
"""

import sys
import os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from wifihaven.unbound import (
    parse_unbound_query_line,
    parse_arp_line,
    HostnameCache,
)

# ---------------------------------------------------------------------------
# 1. Unbound query log parser
#    parse_unbound_query_line(line) -> dict | None
#
#    Unbound log format (log-queries: yes, verbosity: 1):
#      [UNIX_TS] unbound[PID:THREAD] info: CLIENT_IP QNAME. QTYPE IN
# ---------------------------------------------------------------------------

class TestParseUnboundQueryLine:
    A_QUERY    = "[1746621674] unbound[1234:0] info: 192.168.1.42 youtube.com. A IN"
    AAAA_QUERY = "[1746621675] unbound[1234:1] info: 192.168.1.10 google.com. AAAA IN"
    NON_QUERY  = "[1746621676] unbound[1234:0] info: start of service"
    MX_QUERY   = "[1746621677] unbound[1234:0] info: 192.168.1.42 example.com. MX IN"

    def test_a_query_client_ip(self):
        r = parse_unbound_query_line(self.A_QUERY)
        assert r is not None
        assert r["client_ip"] == "192.168.1.42"

    def test_a_query_qname_trailing_dot_stripped(self):
        r = parse_unbound_query_line(self.A_QUERY)
        assert r is not None
        assert r["qname"] == "youtube.com"

    def test_a_query_qtype(self):
        r = parse_unbound_query_line(self.A_QUERY)
        assert r is not None
        assert r["qtype"] == "A"

    def test_aaaa_query_qtype(self):
        r = parse_unbound_query_line(self.AAAA_QUERY)
        assert r is not None
        assert r["qtype"] == "AAAA"

    def test_aaaa_query_qname(self):
        r = parse_unbound_query_line(self.AAAA_QUERY)
        assert r is not None
        assert r["qname"] == "google.com"

    def test_non_query_line_returns_none(self):
        assert parse_unbound_query_line(self.NON_QUERY) is None

    def test_mx_query_returns_none(self):
        """Only A and AAAA records are flow-relevant; MX etc. are ignored."""
        assert parse_unbound_query_line(self.MX_QUERY) is None

    def test_blank_line_returns_none(self):
        assert parse_unbound_query_line("") is None


# ---------------------------------------------------------------------------
# 2. FreeBSD ARP table parser
#    parse_arp_line(line) -> dict | None
#
#    From `arp -an` output:
#      ? (192.168.1.42) at aa:bb:cc:11:22:33 on em0 [ethernet]
#      ? (192.168.1.50) at (incomplete) on em0
# ---------------------------------------------------------------------------

class TestParseArpLine:
    VALID      = "? (192.168.1.42) at aa:bb:cc:11:22:33 on em0 [ethernet]"
    INCOMPLETE = "? (192.168.1.50) at (incomplete) on em0"
    GATEWAY    = "? (192.168.1.1) at 00:11:22:33:44:55 on em0 expires in 1188 seconds [ethernet]"

    def test_valid_ip(self):
        r = parse_arp_line(self.VALID)
        assert r is not None
        assert r["ip"] == "192.168.1.42"

    def test_valid_mac(self):
        r = parse_arp_line(self.VALID)
        assert r is not None
        assert r["mac"] == "aa:bb:cc:11:22:33"

    def test_incomplete_entry_returns_none(self):
        assert parse_arp_line(self.INCOMPLETE) is None

    def test_gateway_parses_correctly(self):
        r = parse_arp_line(self.GATEWAY)
        assert r is not None
        assert r["ip"] == "192.168.1.1"
        assert r["mac"] == "00:11:22:33:44:55"

    def test_blank_line_returns_none(self):
        assert parse_arp_line("") is None


# ---------------------------------------------------------------------------
# 3. Hostname attribution LRU cache
#    HostnameCache(maxsize)
#
#    Cache key: (mac, dest_ip)  — handles concurrent connections to different hosts.
#    Cache value: hostname (the qname the client resolved to reach dest_ip).
#
#    Populated by watching the Unbound query log:
#      - Parse query line → (client_ip, qname)
#      - Resolve qname → [ip1, ip2, ...]  (injectable resolver for testability)
#      - For each resolved ip: cache[(mac_of_client_ip, ip)] = qname
#    This lets a pflog event (src_ip, dst_ip) → mac, then lookup (mac, dst_ip).
# ---------------------------------------------------------------------------

class TestHostnameCache:
    def test_lookup_returns_none_for_unknown_key(self):
        cache = HostnameCache(maxsize=128)
        assert cache.lookup("aa:bb:cc:11:22:33", "93.184.216.34") is None

    def test_lookup_returns_hostname_after_update(self):
        cache = HostnameCache(maxsize=128)
        cache.update("aa:bb:cc:11:22:33", "93.184.216.34", "youtube.com", ts=1000)
        assert cache.lookup("aa:bb:cc:11:22:33", "93.184.216.34") == "youtube.com"

    def test_same_mac_different_dest_ips_are_independent(self):
        """Concurrent connections from one MAC to different hosts."""
        cache = HostnameCache(maxsize=128)
        cache.update("aa:bb:cc:11:22:33", "1.2.3.4", "youtube.com",  ts=1000)
        cache.update("aa:bb:cc:11:22:33", "8.8.8.8", "google.com",   ts=1001)
        assert cache.lookup("aa:bb:cc:11:22:33", "1.2.3.4") == "youtube.com"
        assert cache.lookup("aa:bb:cc:11:22:33", "8.8.8.8") == "google.com"

    def test_different_macs_same_dest_ip_are_independent(self):
        """Two devices connecting to the same IP get separate entries."""
        cache = HostnameCache(maxsize=128)
        cache.update("aa:bb:cc:11:22:33", "1.2.3.4", "youtube.com", ts=1000)
        cache.update("de:ad:be:ef:00:01", "1.2.3.4", "netflix.com", ts=1001)
        assert cache.lookup("aa:bb:cc:11:22:33", "1.2.3.4") == "youtube.com"
        assert cache.lookup("de:ad:be:ef:00:01", "1.2.3.4") == "netflix.com"

    def test_newer_ts_overwrites_older_for_same_key(self):
        cache = HostnameCache(maxsize=128)
        cache.update("aa:bb:cc:11:22:33", "1.2.3.4", "old-host.com", ts=1000)
        cache.update("aa:bb:cc:11:22:33", "1.2.3.4", "new-host.com", ts=1001)
        assert cache.lookup("aa:bb:cc:11:22:33", "1.2.3.4") == "new-host.com"

    def test_older_ts_does_not_overwrite_newer(self):
        cache = HostnameCache(maxsize=128)
        cache.update("aa:bb:cc:11:22:33", "1.2.3.4", "new-host.com", ts=1001)
        cache.update("aa:bb:cc:11:22:33", "1.2.3.4", "old-host.com", ts=1000)  # stale
        assert cache.lookup("aa:bb:cc:11:22:33", "1.2.3.4") == "new-host.com"

    def test_lru_evicts_least_recently_used_entry(self):
        """When maxsize is reached the LRU entry is evicted on next insert."""
        cache = HostnameCache(maxsize=2)
        cache.update("aa:00:00:00:00:01", "1.1.1.1", "site-a.com", ts=1)
        cache.update("aa:00:00:00:00:02", "2.2.2.2", "site-b.com", ts=2)
        # Access entry 1 to make it recently used; entry 2 becomes LRU.
        cache.lookup("aa:00:00:00:00:01", "1.1.1.1")
        # Adding a third entry should evict the LRU (entry 2).
        cache.update("aa:00:00:00:00:03", "3.3.3.3", "site-c.com", ts=3)
        assert cache.lookup("aa:00:00:00:00:01", "1.1.1.1") == "site-a.com"  # still here
        assert cache.lookup("aa:00:00:00:00:02", "2.2.2.2") is None          # evicted
        assert cache.lookup("aa:00:00:00:00:03", "3.3.3.3") == "site-c.com"  # new
