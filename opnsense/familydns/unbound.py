"""unbound.py — Unbound query log watcher and hostname attribution cache.

Provides:
  - parse_unbound_query_line: parse one Unbound log line into (client_ip, qname, qtype)
  - parse_arp_line: parse one `arp -an` line into (ip, mac) on FreeBSD
  - lookup_arp: run `arp -an` and return {ip: mac}
  - HostnameCache: LRU cache keyed by (mac, dest_ip) → hostname

Attribution flow
----------------
1. Unbound logs a query: client_ip resolved qname (A/AAAA)
2. We resolve qname → [ip, ...] with an injectable resolver
   (defaults to socket.getaddrinfo so tests can supply a fake)
3. For each resolved IP: cache[(mac_of_client_ip, ip)] = qname
4. On a pflog event with (src_ip, dst_ip):
   - ARP lookup: src_ip → mac
   - Cache lookup: (mac, dst_ip) → hostname  (falls back to dst_ip at call site)
"""

import re
import socket
import subprocess
import sys
import time
from collections import OrderedDict
from typing import Callable, Dict, List, Optional

# ---------------------------------------------------------------------------
# Unbound query log parser
# ---------------------------------------------------------------------------

# Unbound log-queries format (verbosity: 1):
#   [UNIX_TS] unbound[PID:THREAD] info: CLIENT_IP QNAME. QTYPE IN
_UNBOUND_RE = re.compile(
    r"info: (\S+) (\S+) (A|AAAA) IN"
)


def parse_unbound_query_line(line: str) -> Optional[dict]:
    """Parse one Unbound query log line.

    Returns {client_ip, qname, qtype} or None if line is not an A/AAAA query.
    The trailing dot on qname is stripped.
    """
    m = _UNBOUND_RE.search(line)
    if not m:
        return None
    client_ip, qname_dot, qtype = m.groups()
    return {
        "client_ip": client_ip,
        "qname": qname_dot.rstrip("."),
        "qtype": qtype,
    }


# ---------------------------------------------------------------------------
# FreeBSD ARP table parser
# ---------------------------------------------------------------------------

# `arp -an` output on FreeBSD:
#   ? (192.168.1.42) at aa:bb:cc:11:22:33 on em0 [ethernet]
#   ? (192.168.1.50) at (incomplete) on em0
_ARP_RE = re.compile(
    r"\? \((\d+\.\d+\.\d+\.\d+)\) at ([0-9a-f:]{17}) on"
)


def parse_arp_line(line: str) -> Optional[dict]:
    """Parse one line of `arp -an` output.

    Returns {ip, mac} or None for incomplete/unparseable entries.
    """
    m = _ARP_RE.search(line)
    if not m:
        return None
    return {"ip": m.group(1), "mac": m.group(2)}


def lookup_arp() -> Dict[str, str]:
    """Return {ip: mac} from the current ARP table via `arp -an`."""
    result: Dict[str, str] = {}
    try:
        out = subprocess.check_output(["arp", "-an"], text=True, stderr=subprocess.DEVNULL)
    except (OSError, subprocess.CalledProcessError):
        return result
    for line in out.splitlines():
        entry = parse_arp_line(line)
        if entry:
            result[entry["ip"]] = entry["mac"]
    return result


# ---------------------------------------------------------------------------
# Hostname attribution LRU cache
# ---------------------------------------------------------------------------

class HostnameCache:
    """LRU cache mapping (mac, dest_ip) → hostname.

    maxsize: maximum number of (mac, dest_ip) entries before eviction.
    """

    def __init__(self, maxsize: int = 4096) -> None:
        self._maxsize = maxsize
        # OrderedDict used as LRU: most-recently-used at the end.
        self._cache: OrderedDict = OrderedDict()

    def update(self, mac: str, dest_ip: str, hostname: str, ts: float) -> None:
        """Record that mac reached dest_ip via hostname at time ts.

        A newer ts always wins; a stale update (older ts) is silently dropped.
        """
        key = (mac, dest_ip)
        existing = self._cache.get(key)
        if existing is not None:
            stored_ts, _ = existing
            if ts <= stored_ts:
                return  # stale — keep the newer entry
            # Update in place (move to end to mark recently used).
            del self._cache[key]

        self._cache[key] = (ts, hostname)
        self._cache.move_to_end(key)

        if len(self._cache) > self._maxsize:
            self._cache.popitem(last=False)  # evict LRU (front)

    def lookup(self, mac: str, dest_ip: str) -> Optional[str]:
        """Return the hostname for (mac, dest_ip), or None if not cached.

        Moves the entry to most-recently-used position.
        """
        key = (mac, dest_ip)
        entry = self._cache.get(key)
        if entry is None:
            return None
        self._cache.move_to_end(key)
        _, hostname = entry
        return hostname


# ---------------------------------------------------------------------------
# Unbound log watcher
# ---------------------------------------------------------------------------

def _default_resolve(qname: str) -> List[str]:
    """Resolve qname → list of IPv4/IPv6 addresses via the system resolver."""
    try:
        infos = socket.getaddrinfo(qname, None)
        return list({info[4][0] for info in infos})
    except OSError:
        return []


def watch(cfg: dict) -> None:
    """Tail the Unbound query log and populate the hostname cache.

    cfg keys:
        log_path        str   path to Unbound query log file (tailed via `tail -F`)
        hostname_cache  HostnameCache   shared ref written by this watcher
        arp_fn          callable() -> {ip: mac}   injectable; default = lookup_arp
        resolve_fn      callable(qname) -> [ip]   injectable; default = _default_resolve
        tail_cmd        list  injectable for tests
    """
    arp_fn = cfg.get("arp_fn", lookup_arp)
    resolve_fn = cfg.get("resolve_fn", _default_resolve)
    cache = cfg["hostname_cache"]
    log_path = cfg.get("log_path", "/var/log/unbound/unbound.log")

    tail_cmd = cfg.get("tail_cmd", ["tail", "-F", log_path])

    try:
        proc = subprocess.Popen(
            tail_cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
        )
    except OSError as exc:
        print(f"[familydns] unbound: cannot tail log: {exc}", file=sys.stderr)
        return

    try:
        for line in proc.stdout:
            entry = parse_unbound_query_line(line.rstrip("\n"))
            if not entry:
                continue

            arp_table = arp_fn()
            mac = arp_table.get(entry["client_ip"])
            if not mac:
                continue

            resolved_ips = resolve_fn(entry["qname"])
            ts = time.time()
            for ip in resolved_ips:
                cache.update(mac, ip, entry["qname"], ts)
    finally:
        proc.terminate()
