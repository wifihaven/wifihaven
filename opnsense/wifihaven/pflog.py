"""pflog.py — tail /dev/pflog and emit connection_attempt events.

Tails pflog0 via `tcpdump -l -n -e -i pflog0`.  Each new outbound flow is
turned into a connection_attempt event and handed to a Batcher for delivery
to POST /api/router/events.

The -e flag on pflog0 shows the pflog metadata header (rule, action,
direction, interface) rather than Ethernet headers, giving us the pf
decision alongside the IP 5-tuple.
"""

import re
import subprocess
import sys
from datetime import datetime, timezone
from typing import Callable, Optional

# ---------------------------------------------------------------------------
# Regex: matches the pflog-specific header that tcpdump emits on pflog0
#
# Example line:
#   14:31:05.823456 rule 5/0(match): pass out on em0: 192.168.1.42.51234 > 93.184.216.34.443: Flags [S]...
#   14:31:09.345678 rule 5/0(match): pass out on em0: 192.168.1.42 > 8.8.8.8: ICMP echo request...
# ---------------------------------------------------------------------------
_PFLOG_RE = re.compile(
    r"rule \d+/\d+\(\w+\): (pass|block) (in|out) on \S+: "
    r"(\S+?) > (\S+?):"
)


def _strip_port(addr: str) -> str:
    """Remove trailing .PORT from an IP address in tcpdump dot-notation.

    IPv4: '192.168.1.42.51234' → '192.168.1.42'  (>3 dots means port present)
          '192.168.1.42'       → '192.168.1.42'  (exactly 3 dots, no port)
    IPv6: '2001:db8::1.80'    → '2001:db8::1'   (colon present; dot = port)
          '2001:db8::1'       → '2001:db8::1'   (no dot, no port)
    """
    if ":" in addr:
        last = addr.rfind(".")
        if last != -1 and addr[last + 1 :].isdigit():
            return addr[:last]
        return addr
    if addr.count(".") > 3:
        return addr[: addr.rfind(".")]
    return addr


def parse_pflog_line(line: str) -> Optional[dict]:
    """Parse one line of `tcpdump -l -n -e -i pflog0` output.

    Returns a dict with keys action, direction, src_ip, dst_ip, or None if
    the line doesn't match the pflog header format.
    """
    m = _PFLOG_RE.search(line)
    if not m:
        return None
    action, direction, src_raw, dst_raw = m.groups()
    return {
        "action": action,
        "direction": direction,
        "src_ip": _strip_port(src_raw),
        "dst_ip": _strip_port(dst_raw),
    }


def is_outbound(flow: dict, lan_prefix: str) -> bool:
    """Return True for egress flows originating from the LAN."""
    return flow["direction"] == "out" and flow["src_ip"].startswith(lan_prefix)


def build_event(opts: dict) -> dict:
    """Build a connection_attempt event dict ready for JSON serialisation.

    opts keys: mac, hostname (optional), dest_ip, allowed (bool),
               reason (optional), ts (ISO-8601 string).
    """
    hostname = opts.get("hostname") or opts["dest_ip"]
    reason = opts.get("reason")
    if not reason:
        reason = "allow" if opts["allowed"] else "blocked"
    return {
        "type": "connection_attempt",
        "mac": opts["mac"],
        "hostname": hostname,
        "dest_ip": opts["dest_ip"],
        "allowed": opts["allowed"],
        "reason": reason,
        "ts": opts["ts"],
    }


def watch(cfg: dict) -> None:
    """Blocking event loop: tail pflog0 and emit connection_attempt events.

    cfg keys:
        router_id      str   UUID of this router
        api_url        str   base URL, e.g. "http://192.168.1.1:8080"
        router_token   str   bearer token
        hostname_cache HostnameCache   shared ref (populated by unbound watcher)
        arp_fn         callable() -> {ip: mac}   injectable for tests
        batcher        Batcher         pre-built batcher wired to POST /events
        lan_prefix     str   default "192.168.1."
        tcpdump_cmd    list  injectable for tests; default runs real tcpdump
    """
    from wifihaven.unbound import lookup_arp

    lan_prefix = cfg.get("lan_prefix", "192.168.1.")
    hostname_cache = cfg["hostname_cache"]
    batcher = cfg["batcher"]
    arp_fn = cfg.get("arp_fn", lookup_arp)

    tcpdump_cmd = cfg.get(
        "tcpdump_cmd",
        ["tcpdump", "-l", "-n", "-e", "-i", "pflog0"],
    )

    try:
        proc = subprocess.Popen(
            tcpdump_cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
        )
    except OSError as exc:
        print(f"[wifihaven] pflog: cannot start tcpdump: {exc}", file=sys.stderr)
        return

    try:
        for line in proc.stdout:
            line = line.rstrip("\n")
            flow = parse_pflog_line(line)
            if not flow or not is_outbound(flow, lan_prefix):
                continue

            arp_table = arp_fn()
            mac = arp_table.get(flow["src_ip"])
            hostname = None
            if mac:
                hostname = hostname_cache.lookup(mac, flow["dst_ip"])

            allowed = flow["action"] == "pass"
            ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

            ev = build_event(
                {
                    "mac": mac,
                    "hostname": hostname,
                    "dest_ip": flow["dst_ip"],
                    "allowed": allowed,
                    "reason": None,
                    "ts": ts,
                }
            )
            batcher.add(ev)
            batcher.tick()
    finally:
        proc.terminate()
        batcher.flush()
