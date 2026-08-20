"""Pure matching over the router's shared dns→host attribution cache (#2734).

The wifihaven-dns-tail sidecar writes ``/tmp/wifihaven-dns-cache.txt`` as
``<ip>\t<hostname>\t<ts>`` rows. Both DNS-derived (``reply <h> is <ip>``) and
SNI-derived (``cache.insert_sni``) attributions land in that one file, and
conntrack.lua's ``attribute_hostname`` reads the SAME file to label
connection_events when a NEW flow's SYN crosses the router (#259/#583).

This module is the single matcher used by the observers' cache-settle gates
(``wait_sni_attributed``, ``wait_dns_cache_attributed``) so the question
"does the cache map ip→host yet?" is answered in exactly one place and is
unit-testable without a VM.
"""
from __future__ import annotations

from typing import Iterable, Optional, Sequence, Tuple


def find_ip_host_row(
    rows: Iterable[Sequence[str]], ip: str, host: str
) -> Optional[Tuple[str, str]]:
    """Return the first ``(ip, hostname)`` row mapping ``ip`` to ``host``, else None.

    Host match is EXACT and case-insensitive — the cache stores the resolved
    leaf verbatim, and a settle-gate for a specific leaf must not be satisfied
    by a suffix/substring row (that would defeat the point of gating on the
    leaf actually being learned). IP match is exact. Surrounding whitespace on
    either column is ignored so raw ``<ip>\t<host>\t<ts>`` file lines compare
    cleanly. Rows with fewer than two columns are skipped.
    """
    want_ip = (ip or "").strip()
    want_host = (host or "").strip().lower()
    if not want_ip or not want_host:
        return None
    for row in rows:
        if row is None or len(row) < 2:
            continue
        cip = (row[0] or "").strip()
        chost = (row[1] or "").strip()
        if cip == want_ip and chost.lower() == want_host:
            return (cip, chost)
    return None
