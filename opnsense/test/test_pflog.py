"""Tests for opnsense/familydns/pflog.py

Run with: pytest opnsense/test/test_pflog.py
"""

import sys
import os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from familydns.pflog import parse_pflog_line, build_event, is_outbound

# ---------------------------------------------------------------------------
# 1. pflog line parsing — parse_pflog_line(line) -> dict | None
# ---------------------------------------------------------------------------

class TestParsePflogLine:
    """parse_pflog_line parses tcpdump -l -n -e -i pflog0 output."""

    # Happy-path TCP pass outbound
    TCP_PASS_OUT = (
        "14:31:05.823456 rule 5/0(match): pass out on em0: "
        "192.168.1.42.51234 > 93.184.216.34.443: Flags [S], "
        "cksum 0x1234 (correct), seq 123456789, win 65535, length 0"
    )

    # TCP block outbound
    TCP_BLOCK_OUT = (
        "14:31:07.123456 rule 3/0(match): block out on em0: "
        "192.168.1.10.54321 > 1.2.3.4.80: Flags [S], "
        "cksum 0x5678, seq 987654321, win 65535, length 0"
    )

    # UDP pass outbound
    UDP_PASS_OUT = (
        "14:31:06.001234 rule 5/0(match): pass out on em0: "
        "192.168.1.42.12345 > 8.8.8.8.53: UDP, length 32"
    )

    # ICMP — no port numbers in the address field
    ICMP_PASS_OUT = (
        "14:31:09.345678 rule 5/0(match): pass out on em0: "
        "192.168.1.42 > 8.8.8.8: ICMP echo request, id 1234, seq 1"
    )

    # TCP pass inbound (return traffic — not an outbound flow)
    TCP_PASS_IN = (
        "14:31:08.234567 rule 5/0(match): pass in on em0: "
        "93.184.216.34.443 > 192.168.1.42.51234: Flags [.], length 0"
    )

    # tcpdump startup header
    HEADER_LINE = (
        "listening on pflog0, link-type PFLOG (OpenBSD pflog file), "
        "capture size 262144 bytes"
    )

    # Blank / empty line
    BLANK_LINE = ""

    def test_tcp_pass_out_src_ip(self):
        r = parse_pflog_line(self.TCP_PASS_OUT)
        assert r is not None
        assert r["src_ip"] == "192.168.1.42"

    def test_tcp_pass_out_dst_ip(self):
        r = parse_pflog_line(self.TCP_PASS_OUT)
        assert r is not None
        assert r["dst_ip"] == "93.184.216.34"

    def test_tcp_pass_out_action(self):
        r = parse_pflog_line(self.TCP_PASS_OUT)
        assert r is not None
        assert r["action"] == "pass"

    def test_tcp_pass_out_direction(self):
        r = parse_pflog_line(self.TCP_PASS_OUT)
        assert r is not None
        assert r["direction"] == "out"

    def test_tcp_block_out_action(self):
        r = parse_pflog_line(self.TCP_BLOCK_OUT)
        assert r is not None
        assert r["action"] == "block"

    def test_tcp_block_out_src_ip(self):
        r = parse_pflog_line(self.TCP_BLOCK_OUT)
        assert r is not None
        assert r["src_ip"] == "192.168.1.10"

    def test_tcp_block_out_dst_ip(self):
        r = parse_pflog_line(self.TCP_BLOCK_OUT)
        assert r is not None
        assert r["dst_ip"] == "1.2.3.4"

    def test_udp_pass_out_src_ip(self):
        r = parse_pflog_line(self.UDP_PASS_OUT)
        assert r is not None
        assert r["src_ip"] == "192.168.1.42"

    def test_udp_pass_out_dst_ip(self):
        r = parse_pflog_line(self.UDP_PASS_OUT)
        assert r is not None
        assert r["dst_ip"] == "8.8.8.8"

    def test_icmp_no_port_src_ip(self):
        """ICMP lines have no port suffix — src_ip should still parse correctly."""
        r = parse_pflog_line(self.ICMP_PASS_OUT)
        assert r is not None
        assert r["src_ip"] == "192.168.1.42"

    def test_icmp_no_port_dst_ip(self):
        r = parse_pflog_line(self.ICMP_PASS_OUT)
        assert r is not None
        assert r["dst_ip"] == "8.8.8.8"

    def test_inbound_line_parses_direction(self):
        """Inbound lines parse but callers should filter by direction."""
        r = parse_pflog_line(self.TCP_PASS_IN)
        assert r is not None
        assert r["direction"] == "in"

    def test_header_line_returns_none(self):
        assert parse_pflog_line(self.HEADER_LINE) is None

    def test_blank_line_returns_none(self):
        assert parse_pflog_line(self.BLANK_LINE) is None


# ---------------------------------------------------------------------------
# 2. Outbound filter — is_outbound(flow, lan_prefix)
# ---------------------------------------------------------------------------

class TestIsOutbound:
    def test_src_on_lan_is_outbound(self):
        flow = {"src_ip": "192.168.1.42", "dst_ip": "8.8.8.8", "direction": "out"}
        assert is_outbound(flow, "192.168.1.") is True

    def test_src_not_on_lan_is_not_outbound(self):
        flow = {"src_ip": "8.8.8.8", "dst_ip": "192.168.1.42", "direction": "in"}
        assert is_outbound(flow, "192.168.1.") is False

    def test_direction_out_required(self):
        """Direction 'in' is not outbound even if src is LAN (shouldn't happen in practice)."""
        flow = {"src_ip": "192.168.1.42", "dst_ip": "8.8.8.8", "direction": "in"}
        assert is_outbound(flow, "192.168.1.") is False


# ---------------------------------------------------------------------------
# 3. Event construction — build_event(opts) -> dict
# ---------------------------------------------------------------------------

class TestBuildEvent:
    FULL_OPTS = {
        "mac": "aa:bb:cc:11:22:33",
        "hostname": "youtube.com",
        "dest_ip": "93.184.216.34",
        "allowed": False,
        "reason": "category:adult",
        "ts": "2026-05-08T14:01:14Z",
    }

    def test_type_field(self):
        ev = build_event(self.FULL_OPTS)
        assert ev["type"] == "connection_attempt"

    def test_mac_field(self):
        ev = build_event(self.FULL_OPTS)
        assert ev["mac"] == "aa:bb:cc:11:22:33"

    def test_hostname_field(self):
        ev = build_event(self.FULL_OPTS)
        assert ev["hostname"] == "youtube.com"

    def test_dest_ip_field(self):
        ev = build_event(self.FULL_OPTS)
        assert ev["dest_ip"] == "93.184.216.34"

    def test_allowed_field(self):
        ev = build_event(self.FULL_OPTS)
        assert ev["allowed"] is False

    def test_reason_field(self):
        ev = build_event(self.FULL_OPTS)
        assert ev["reason"] == "category:adult"

    def test_ts_field(self):
        ev = build_event(self.FULL_OPTS)
        assert ev["ts"] == "2026-05-08T14:01:14Z"

    def test_hostname_falls_back_to_dest_ip(self):
        opts = {**self.FULL_OPTS, "hostname": None}
        ev = build_event(opts)
        assert ev["hostname"] == "93.184.216.34"

    def test_reason_defaults_to_allow_when_allowed_true(self):
        opts = {**self.FULL_OPTS, "allowed": True, "reason": None}
        ev = build_event(opts)
        assert ev["reason"] == "allow"

    def test_reason_defaults_to_blocked_when_allowed_false(self):
        opts = {**self.FULL_OPTS, "allowed": False, "reason": None}
        ev = build_event(opts)
        assert ev["reason"] == "blocked"

    def test_explicit_reason_overrides_default(self):
        opts = {**self.FULL_OPTS, "allowed": False, "reason": "schedule"}
        ev = build_event(opts)
        assert ev["reason"] == "schedule"

    def test_event_is_json_serialisable(self):
        import json
        ev = build_event(self.FULL_OPTS)
        encoded = json.dumps(ev)
        decoded = json.loads(encoded)
        assert decoded["type"] == "connection_attempt"
        assert decoded["dest_ip"] == "93.184.216.34"
        assert isinstance(decoded["allowed"], bool)
