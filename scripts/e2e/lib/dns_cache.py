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

import ipaddress
from typing import Iterable, Optional, Sequence, Tuple


def canon_ip(ip: str) -> str:
    """Canonicalize an IP string to the cache's key form, mirroring the agent's
    ``host_norm.canon_ip`` (#1793).

    wifihaven-dns-tail keys the cache by the canonical **fully-expanded,
    lowercase, zero-padded 8-group** IPv6 form (e.g. dnsmasq's compressed
    ``2001:db8::10`` and the kernel LOG's expanded spelling both collapse to
    ``2001:0db8:0000:0000:0000:0000:0000:0010``) so a lookup in either spelling
    hits the same insert. IPv4 and non-IP strings are returned unchanged.

    A settle-gate over these rows MUST compare on this same canonical key: the
    file stores the expanded form, but a test literal is usually the compressed
    one, so a raw string compare never matches (the #2734 first-cut bug — the
    mapping was present the whole time and the gate still timed out). This
    mirrors ``canon_ip``'s observable output, including its carve-outs: an
    embedded-IPv4 tail (``::ffff:1.2.3.4``) and any malformed shape are left
    lowercased-but-unexpanded rather than risk corrupting the key.
    """
    s = (ip or "").strip()
    if ":" not in s:
        return s  # IPv4 or non-IP: nothing to canonicalize (matches canon_ip)
    addr = s.split("%", 1)[0].lower()  # strip a zone id, lowercase
    if "." in addr:
        return addr  # embedded-IPv4 tail: leave intact, as canon_ip does
    try:
        return ipaddress.IPv6Address(addr).exploded  # already lowercase
    except ValueError:
        return addr  # malformed: lowercased fallback, as canon_ip does


def find_ip_host_row(
    rows: Iterable[Sequence[str]], ip: str, host: str
) -> Optional[Tuple[str, str]]:
    """Return the first ``(ip, hostname)`` row mapping ``ip`` to ``host``, else None.

    IP match is on the canonical key (:func:`canon_ip`), so a compressed IPv6
    literal matches the cache's expanded spelling and vice-versa. Host match is
    EXACT and case-insensitive — the cache stores the resolved leaf verbatim,
    and a settle-gate for a specific leaf must not be satisfied by a
    suffix/substring row (that would defeat the point of gating on the leaf
    actually being learned). Surrounding whitespace on either column is ignored
    so raw ``<ip>\t<host>\t<ts>`` file lines compare cleanly. Rows with fewer
    than two columns are skipped. The RETURNED tuple is the row as stored (not
    canonicalized), so callers see exactly what is in the cache.
    """
    if not (ip or "").strip() or not (host or "").strip():
        return None
    want_ip = canon_ip(ip)
    want_host = host.strip().lower()
    for row in rows:
        if row is None or len(row) < 2:
            continue
        cip = (row[0] or "").strip()
        chost = (row[1] or "").strip()
        if canon_ip(cip) == want_ip and chost.lower() == want_host:
            return (cip, chost)
    return None
