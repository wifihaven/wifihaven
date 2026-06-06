#!/usr/bin/env python3
"""Presence replay / validation harness — the go/no-go gate on the tuned presence
defaults (design: ``docs/design/presence-tuning.md`` §5 item 5; issues #1467,
subsumes #790).

This reproduces, over a week of real prod data, the three analyses that justify the
shipped presence model so a future change to the defaults can't shift the visible
numbers silently:

  §2b  current ``max(activeSeconds)`` model vs session-stitch at each candidate N
       (continuation window) — confirms the undercount the session model fixes is
       real and that N=120 s sits at the knee (the dominant loss is *within* the
       reporting window, not across windows).

  §2d  re-bucketing invariance — collapse the same day's per-request activity into
       buckets of width R = 10/60/300 s, re-run the identical stitch, and confirm
       the session-stitch (per-device union) minutes are stable when N ≥ R, and
       collapse to ~0 when N < R (the rate-independence invariant).

  HB   heartbeat-filter replay (the folded-in #790) — re-run the kept-minutes
       computation with the current heartbeat defaults (bytes floor + FQDN
       allowlist) plus a low/high bytes-floor sensitivity sweep, to confirm the
       floor is neither over-suppressing real screen time (5 KB recovers little)
       nor sitting on the edge of real interactive traffic (20 KB drops little).

The computation here mirrors ``api/src/presence/Presence.scala`` (``spanOf`` /
``stitch`` / ``unionSeconds`` / ``effectiveGap`` / ``isHeartbeat``) so the gate
reflects the *shipped* behaviour, not a parallel model. It is a validation tool,
NOT the production path — the fixture-based test (``test_presence_replay.py``) pins
the computation so it doesn't bit-rot.

Data is read OUT OF BAND, read-only, by ``fetch_prod_data.sh`` into a local data
directory (gitignored); this script consumes that directory and never touches the
network or prod. See ``README.md`` for the end-to-end recipe.
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass, field
from pathlib import Path

# ── shared domain constants (mirror the design + shipped defaults) ────────────

# Candidate continuation windows N (seconds) swept in §2b, mirroring the design's
# br0/br30/br60/br120/br180/br300/br600 columns.
CANDIDATE_NS = [0, 30, 60, 120, 180, 300, 600]
# The shipped default (household_settings.presence_continuation_seconds, V52).
DEFAULT_N = 120
# Report intervals R (seconds) re-bucketed in the §2d invariance check.
REBUCKET_RS = [10, 60, 300]


# ── data model ────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class HeartbeatFilter:
    """Mirror of ``shared`` ``HeartbeatFilter`` — the keepalive-stripping config."""

    enabled: bool
    bytes_threshold: int
    host_patterns: tuple[str, ...] = ()

    @staticmethod
    def from_settings(s: dict) -> "HeartbeatFilter":
        return HeartbeatFilter(
            enabled=bool(s["heartbeat_filter_enabled"]),
            bytes_threshold=int(s["heartbeat_bytes_threshold"]),
            host_patterns=tuple(s.get("heartbeat_host_patterns") or ()),
        )


@dataclass(frozen=True)
class TrafficRow:
    """One ``traffic_reports`` row, epoch-second timed (mirror of ``PresenceRow``)."""

    mac: str
    date: str
    period_start: int  # epoch seconds
    period_end: int  # epoch seconds
    host: str
    host_type: str  # 'fqdn' | 'ipv4' | 'ipv6'
    bytes: int  # bytes_in + bytes_out
    active_seconds: int

    @property
    def period_seconds(self) -> int:
        return max(0, self.period_end - self.period_start)


@dataclass(frozen=True)
class ConnEvent:
    """One ``connection_events`` row — per-request timestamp, no bytes (§2d)."""

    mac: str
    host: str
    host_type: str
    ts: int  # epoch seconds


# ── host pattern matching (mirror of shared HostMatch.matchesPattern) ─────────


def matches_pattern(host: str, pattern: str) -> bool:
    """``*.foo.com`` / ``foo.com`` apex-suffix match: host == apex or host ends
    with ``"." + apex``. Inputs are assumed already lowercased."""
    apex = pattern[2:] if pattern.startswith("*.") else pattern
    return host == apex or host.endswith("." + apex)


# ── heartbeat classification (mirror of Presence.isHeartbeat) ─────────────────


def is_heartbeat(row: TrafficRow, f: HeartbeatFilter) -> bool:
    if not f.enabled:
        return False
    if row.bytes < f.bytes_threshold:
        return True
    if row.host_type == "fqdn":
        return any(matches_pattern(row.host, p) for p in f.host_patterns)
    return False


def is_heartbeat_fqdn_only(host: str, host_type: str, patterns: tuple[str, ...]) -> bool:
    """FQDN half of the heartbeat filter — the only half modelable on
    ``connection_events`` (no bytes). Used by the §2d invariance proof."""
    if host_type != "fqdn":
        return False
    return any(matches_pattern(host, p) for p in patterns)


# ── session-stitch primitive (mirror of Presence.scala §4.1) ──────────────────

Span = tuple[int, int]  # (start_epoch, end_epoch), end >= start


def span_seconds(s: Span) -> int:
    return max(0, s[1] - s[0])


def effective_gap(rows_period_seconds: list[int], continuation_seconds: int) -> int:
    """The idle gap actually used: configured N raised to the ``2 × R`` collapse
    guard (design §4.4 invariant 1). R is read from the data, never assumed 60 s.
    Mirrors ``Presence.effectiveGap``."""
    r = max(rows_period_seconds) if rows_period_seconds else 0
    return max(continuation_seconds, 2 * r)


def stitch(spans: list[Span], gap_seconds: int) -> list[Span]:
    """Fold time-ordered spans into sessions, merging while the wall-clock idle gap
    to the next span is ≤ ``gap_seconds``. Mirrors ``Presence.stitch``."""
    out: list[Span] = []
    for s in sorted(spans):
        if out and s[0] - out[-1][1] <= gap_seconds:
            out[-1] = (out[-1][0], max(out[-1][1], s[1]))
        else:
            out.append(s)
    return out


def merge_spans(spans: list[Span]) -> list[Span]:
    """Overlap-merge (gap 0) into a minimal sorted non-overlapping set. Mirrors
    ``Presence.mergeSpans``."""
    out: list[Span] = []
    for s in sorted(spans):
        if out and s[0] <= out[-1][1]:
            out[-1] = (out[-1][0], max(out[-1][1], s[1]))
        else:
            out.append(s)
    return out


def union_seconds(spans: list[Span]) -> int:
    return sum(span_seconds(s) for s in merge_spans(spans))


# ── §2b: legacy max(activeSeconds) and session models over traffic_reports ────


def counted_rows(rows: list[TrafficRow], f: HeartbeatFilter) -> list[TrafficRow]:
    """Non-heartbeat rows (the daily-cap subject). Exempt-site filtering is out of
    scope for the kid-device replay — no per-site exemptions on these profiles."""
    return [r for r in rows if not is_heartbeat(r, f)]


def legacy_max_seconds(rows: list[TrafficRow]) -> int:
    """`cur` — the pre-#1464 model: per ``period_start`` bucket take
    ``max(active_seconds)`` across hosts, sum over buckets. The undercount driver."""
    by_bucket: dict[int, int] = {}
    for r in rows:
        by_bucket[r.period_start] = max(by_bucket.get(r.period_start, 0), r.active_seconds)
    return sum(by_bucket.values())


def full_bucket_seconds(rows: list[TrafficRow]) -> int:
    """`full` — credit each non-heartbeat reporting window its full span, deduped
    per ``period_start`` (one count per window). The interval-union of the bucket
    windows; equals the session model evaluated at the current report interval."""
    by_bucket: dict[int, Span] = {}
    for r in rows:
        cur = by_bucket.get(r.period_start)
        end = max(r.period_end, cur[1]) if cur else r.period_end
        by_bucket[r.period_start] = (r.period_start, end)
    return union_seconds(list(by_bucket.values()))


def session_total_seconds(rows: list[TrafficRow], gap: int) -> int:
    """Session model daily total for one device: per ``(mac, host)`` stitch the
    ``[period_start, period_end]`` spans on ``gap``, then union within the device
    (two apps in the same minute count once). Mirrors ``totalSecondsByMac`` for a
    single mac. ``gap`` is the *literal* idle window — callers pass either a raw
    candidate N (the §2b sweep, to surface the knee) or ``effective_gap(...)`` (the
    shipped production value)."""
    by_host: dict[str, list[Span]] = {}
    for r in rows:
        by_host.setdefault(r.host, []).append((r.period_start, r.period_end))
    sessions: list[Span] = []
    for host_spans in by_host.values():
        sessions.extend(stitch(host_spans, gap))
    return union_seconds(sessions)


# ── §2d: re-bucketing invariance over connection_events ───────────────────────


def rebucket_union_seconds(
    events: list[ConnEvent],
    report_interval_r: int,
    gap_n: int,
    patterns: tuple[str, ...],
) -> int:
    """Collapse per-request timestamps into width-R buckets, session-stitch at gap
    N per ``(mac, host)``, then union within the device. FQDN-allowlist heartbeats
    are dropped first (the only heartbeat half modelable without bytes — see the
    design Appendix §2d note). Returns the per-device union seconds.

    Buckets are **point-anchored** (design §2d (ii) "stitching point-anchored
    buckets"): each occupied bucket contributes a zero-width point at its start,
    and a session's presence is the span from its first to its last point. This is
    what makes the union rate-independent — R only quantizes point *positions* (by
    ≤ R), it is never a term in the span width. (Crediting each bucket a full
    ``[b, b+R]`` span instead would inflate linearly with R and is exactly the
    bucket-size dependence the session model exists to avoid; see design §1's
    "full-bucket credit" rejection.)

    Invariant under test: with ``N ≥ R`` the union is ~flat across R; with
    ``N < R`` consecutive points are farther apart than the gap threshold, nothing
    merges, every session is a zero-span point and presence collapses to ~0."""
    active = [
        e for e in events if not is_heartbeat_fqdn_only(e.host, e.host_type, patterns)
    ]
    by_host: dict[str, set[int]] = {}
    for e in active:
        bucket_start = (e.ts // report_interval_r) * report_interval_r
        by_host.setdefault(e.host, set()).add(bucket_start)
    sessions: list[Span] = []
    for buckets in by_host.values():
        points = [(b, b) for b in sorted(buckets)]  # point-anchored
        sessions.extend(stitch(points, gap_n))
    return union_seconds(sessions)


# ── data loading ──────────────────────────────────────────────────────────────


@dataclass
class Dataset:
    settings: dict
    devices: dict[str, str]  # mac -> name
    traffic: list[TrafficRow]
    conn: list[ConnEvent]
    filter: HeartbeatFilter = field(init=False)

    def __post_init__(self) -> None:
        self.filter = HeartbeatFilter.from_settings(self.settings)

    @staticmethod
    def load(data_dir: Path) -> "Dataset":
        settings = json.loads((data_dir / "settings.json").read_text())
        devices = json.loads((data_dir / "devices.json").read_text())
        traffic = [TrafficRow(**r) for r in json.loads((data_dir / "traffic_reports.json").read_text())]
        conn = [ConnEvent(**r) for r in json.loads((data_dir / "connection_events.json").read_text())]
        return Dataset(settings=settings, devices=devices, traffic=traffic, conn=conn)

    def name(self, mac: str) -> str:
        return self.devices.get(mac, mac)


# ── report builders ────────────────────────────────────────────────────────────


def device_days(traffic: list[TrafficRow]) -> list[tuple[str, str]]:
    return sorted({(r.mac, r.date) for r in traffic})


@dataclass
class TuningResult:
    section_2b: dict
    section_2d: dict
    heartbeat: dict
    gate: dict


def run_2b(ds: Dataset) -> dict:
    """Per device-day and total: cur, full, session(N) for each candidate N (raw
    literal gap, to surface the knee), in minutes."""
    rows_by_dd: dict[tuple[str, str], list[TrafficRow]] = {}
    for r in ds.traffic:
        rows_by_dd.setdefault((r.mac, r.date), []).append(r)

    per_day = []
    totals = {"cur": 0, "full": 0, **{f"n{n}": 0 for n in CANDIDATE_NS}}
    for (mac, date) in device_days(ds.traffic):
        rows = counted_rows(rows_by_dd[(mac, date)], ds.filter)
        cur = legacy_max_seconds(rows)
        full = full_bucket_seconds(rows)
        sess = {n: session_total_seconds(rows, n) for n in CANDIDATE_NS}
        per_day.append(
            {
                "mac": mac,
                "name": ds.name(mac),
                "date": date,
                "cur_min": cur // 60,
                "full_min": full // 60,
                "sess_min": {n: sess[n] // 60 for n in CANDIDATE_NS},
                # production semantics: shipped effective gap at the default N.
                "prod_min": session_total_seconds(
                    rows, effective_gap([r.period_seconds for r in rows], DEFAULT_N)
                )
                // 60,
            }
        )
        totals["cur"] += cur
        totals["full"] += full
        for n in CANDIDATE_NS:
            totals[f"n{n}"] += sess[n]

    cur_min = totals["cur"] // 60
    full_min = totals["full"] // 60
    sess_min = {n: totals[f"n{n}"] // 60 for n in CANDIDATE_NS}
    ratio_default = (sess_min[DEFAULT_N] / cur_min) if cur_min else 0.0
    # "dominant loss is within the minute": N=120 should add little over `full`.
    within_minute_share = (full_min / sess_min[DEFAULT_N]) if sess_min[DEFAULT_N] else 0.0
    bridge_gain = (sess_min[DEFAULT_N] / full_min) if full_min else 0.0
    return {
        "per_day": per_day,
        "total": {
            "cur_min": cur_min,
            "full_min": full_min,
            "sess_min": sess_min,
            "ratio_default_vs_cur": round(ratio_default, 3),
            "within_minute_share": round(within_minute_share, 3),
            "bridge_gain_n120_over_full": round(bridge_gain, 3),
        },
    }


def run_2d(ds: Dataset, n_inv: int = 300, collapse_n: int = 60, collapse_r: int = 300) -> dict:
    """Re-bucketing invariance: per (mac, day) union minutes at N=n_inv across R,
    aggregated; plus the N<R collapse demonstration."""
    conn_by_dd: dict[tuple[str, str], list[ConnEvent]] = {}
    for e in ds.conn:
        # day key from epoch — conn events carry no `date`, so derive the UTC day.
        # The invariance claim is about per-day-shaped data, not an exact calendar
        # boundary, so UTC-day partitioning is sufficient (it only affects which
        # events group together, not the within-day stitch).
        import datetime as _dt

        day = _dt.datetime.fromtimestamp(e.ts, _dt.timezone.utc).date().isoformat()
        conn_by_dd.setdefault((e.mac, day), []).append(e)

    per_day = []
    agg_by_r = {r: 0 for r in REBUCKET_RS}
    collapse_total = 0
    for (mac, day), events in sorted(conn_by_dd.items()):
        unions = {
            r: rebucket_union_seconds(events, r, n_inv, ds.filter.host_patterns)
            for r in REBUCKET_RS
        }
        collapse = rebucket_union_seconds(events, collapse_r, collapse_n, ds.filter.host_patterns)
        per_day.append(
            {
                "mac": mac,
                "name": ds.name(mac),
                "date": day,
                "events": len(events),
                "union_min_by_r": {r: unions[r] // 60 for r in REBUCKET_RS},
                "collapse_min": collapse // 60,
            }
        )
        for r in REBUCKET_RS:
            agg_by_r[r] += unions[r]
        collapse_total += collapse

    agg_min = {r: agg_by_r[r] // 60 for r in REBUCKET_RS}
    vals = [agg_by_r[r] for r in REBUCKET_RS]
    spread = ((max(vals) - min(vals)) / max(vals)) if max(vals) else 0.0
    # collapse: union at N<R should be a tiny fraction of the N>=R union at that R.
    collapse_ref = agg_by_r[collapse_r] or 1
    collapse_frac = collapse_total / collapse_ref
    return {
        "per_day": per_day,
        "n_inv": n_inv,
        "agg_union_min_by_r": agg_min,
        "spread": round(spread, 3),
        "collapse": {
            "n": collapse_n,
            "r": collapse_r,
            "union_min": collapse_total // 60,
            "ref_min": agg_min[collapse_r],
            "fraction_of_ref": round(collapse_frac, 4),
        },
    }


def run_heartbeat(ds: Dataset, low: int = 5000, high: int = 20000) -> dict:
    """Heartbeat-filter replay (#790): kept session-minutes under the current bytes
    floor and a low/high sensitivity sweep (FQDN allowlist held constant). Plus the
    'kept but heartbeat-shaped' and 'dropped-by-allowlist' host lists for retuning."""
    base = ds.filter.bytes_threshold
    thresholds = sorted({low, base, high})
    rows_by_dd: dict[tuple[str, str], list[TrafficRow]] = {}
    for r in ds.traffic:
        rows_by_dd.setdefault((r.mac, r.date), []).append(r)

    def kept_minutes_at(threshold: int) -> int:
        f = HeartbeatFilter(enabled=True, bytes_threshold=threshold, host_patterns=ds.filter.host_patterns)
        gap = DEFAULT_N
        total = 0
        for (mac, date), rows in rows_by_dd.items():
            kept = [r for r in rows if not is_heartbeat(r, f)]
            total += session_total_seconds(kept, gap)
        return total // 60

    kept = {t: kept_minutes_at(t) for t in thresholds}

    # Hosts dropped solely by the FQDN allowlist (would otherwise be kept at the
    # current bytes floor) — confirms the allowlist is doing real work.
    f_base = ds.filter
    f_no_patterns = HeartbeatFilter(enabled=True, bytes_threshold=base, host_patterns=())
    dropped_by_allowlist: dict[str, int] = {}
    kept_low_bytes: dict[str, int] = {}
    for r in ds.traffic:
        if r.host_type != "fqdn":
            continue
        hb_base = is_heartbeat(r, f_base)
        hb_bytes_only = is_heartbeat(r, f_no_patterns)
        if hb_base and not hb_bytes_only:
            dropped_by_allowlist[r.host] = dropped_by_allowlist.get(r.host, 0) + 1
        # kept (not heartbeat) but with low bytes — "looks heartbeat-shaped",
        # a candidate for the allowlist if it is in fact keepalive noise.
        if not hb_base and r.bytes < high:
            kept_low_bytes[r.host] = kept_low_bytes.get(r.host, 0) + 1

    delta_low = abs(kept[base] - kept.get(low, kept[base])) / kept[base] if kept[base] else 0.0
    delta_high = abs(kept[base] - kept.get(high, kept[base])) / kept[base] if kept[base] else 0.0
    return {
        "base_threshold": base,
        "kept_min_by_threshold": kept,
        "delta_low": round(delta_low, 3),
        "delta_high": round(delta_high, 3),
        "dropped_by_allowlist_top": sorted(dropped_by_allowlist.items(), key=lambda kv: -kv[1])[:20],
        "kept_low_bytes_top": sorted(kept_low_bytes.items(), key=lambda kv: -kv[1])[:20],
    }


def evaluate_gate(
    s2b: dict,
    s2d: dict,
    hb: dict,
    *,
    ratio_min: float,
    ratio_max: float,
    bridge_max: float,
    invariance_tol: float,
    collapse_max: float,
    hb_delta_tol: float,
) -> dict:
    checks = []

    # §2b: undercount real + N=120 in the right regime.
    ratio = s2b["total"]["ratio_default_vs_cur"]
    bridge = s2b["total"]["bridge_gain_n120_over_full"]
    checks.append(
        {
            "id": "2b-undercount",
            "desc": f"session(N={DEFAULT_N}) recovers the undercount vs legacy max(activeSeconds)",
            "pass": ratio_min <= ratio <= ratio_max,
            "detail": f"ratio={ratio} (band [{ratio_min},{ratio_max}])",
        }
    )
    checks.append(
        {
            "id": "2b-knee",
            "desc": f"N={DEFAULT_N} adds little cross-window bridging over full-bucket credit (loss is within the window)",
            "pass": bridge <= bridge_max,
            "detail": f"session(N={DEFAULT_N})/full={bridge} (max {bridge_max})",
        }
    )

    # §2d: invariance + collapse.
    spread = s2d["spread"]
    checks.append(
        {
            "id": "2d-invariance",
            "desc": f"session-stitch union is stable across R={REBUCKET_RS} at N={s2d['n_inv']} (N≥R)",
            "pass": spread <= invariance_tol,
            "detail": f"spread={spread} (max {invariance_tol}), union_min_by_r={s2d['agg_union_min_by_r']}",
        }
    )
    cf = s2d["collapse"]["fraction_of_ref"]
    checks.append(
        {
            "id": "2d-collapse",
            "desc": f"N<R collapses presence to ~0 (N={s2d['collapse']['n']}, R={s2d['collapse']['r']})",
            "pass": cf <= collapse_max,
            "detail": f"collapse fraction={cf} (max {collapse_max}), {s2d['collapse']['union_min']}m vs ref {s2d['collapse']['ref_min']}m",
        }
    )

    # Heartbeat: floor neither over-suppresses (low) nor sits on the edge (high).
    checks.append(
        {
            "id": "hb-low",
            "desc": "lowering the bytes floor recovers little — current floor isn't over-suppressing real screen time",
            "pass": hb["delta_low"] <= hb_delta_tol,
            "detail": f"delta_low={hb['delta_low']} (max {hb_delta_tol})",
        }
    )
    checks.append(
        {
            "id": "hb-high",
            "desc": "raising the bytes floor drops little — current floor isn't sitting on the edge of real interactive traffic",
            "pass": hb["delta_high"] <= hb_delta_tol,
            "detail": f"delta_high={hb['delta_high']} (max {hb_delta_tol})",
        }
    )

    overall = all(c["pass"] for c in checks)
    return {"go": overall, "checks": checks}


# ── text rendering ─────────────────────────────────────────────────────────────


def _fmt_row(cols: list[str], widths: list[int]) -> str:
    return "  ".join(c.rjust(w) for c, w in zip(cols, widths))


def render(ds: Dataset, res: TuningResult) -> str:
    out: list[str] = []
    f = ds.filter
    out.append("=" * 78)
    out.append("PRESENCE REPLAY / VALIDATION HARNESS — go/no-go gate (#1467, subsumes #790)")
    out.append("=" * 78)
    out.append("")
    out.append("Live prod defaults under test:")
    out.append(f"  presence_continuation_seconds : {DEFAULT_N}")
    out.append(f"  heartbeat_filter_enabled      : {f.enabled}")
    out.append(f"  heartbeat_bytes_threshold     : {f.bytes_threshold}")
    out.append(f"  heartbeat_host_patterns       : {len(f.host_patterns)} patterns")
    macs = sorted({r.mac for r in ds.traffic})
    out.append(f"  devices                       : {', '.join(ds.name(m) for m in macs)}")
    dates = sorted({r.date for r in ds.traffic})
    if dates:
        out.append(f"  date range                    : {dates[0]} .. {dates[-1]} ({len(dates)} days)")
    out.append("")

    # §2b
    out.append("-" * 78)
    out.append("§2b  current max(activeSeconds) vs session-stitch at each candidate N (minutes)")
    out.append("-" * 78)
    hdr = ["device", "date", "cur", "full"] + [f"N{n}" for n in CANDIDATE_NS]
    widths = [16, 10, 5, 5] + [5] * len(CANDIDATE_NS)
    out.append(_fmt_row(hdr, widths))
    for d in res.section_2b["per_day"]:
        cols = [d["name"][:16], d["date"], str(d["cur_min"]), str(d["full_min"])] + [
            str(d["sess_min"][n]) for n in CANDIDATE_NS
        ]
        out.append(_fmt_row(cols, widths))
    t = res.section_2b["total"]
    out.append(_fmt_row(
        ["TOTAL", "", str(t["cur_min"]), str(t["full_min"])] + [str(t["sess_min"][n]) for n in CANDIDATE_NS],
        widths,
    ))
    out.append("")
    out.append(f"  session(N={DEFAULT_N}) / cur          = {t['ratio_default_vs_cur']}x  (the undercount the model fixes)")
    out.append(f"  session(N={DEFAULT_N}) / full         = {t['bridge_gain_n120_over_full']}x  (cross-window bridging — small ⇒ loss is within the window)")
    out.append(f"  N={DEFAULT_N} clamped (≥2·R) prod min = {sum(d['prod_min'] for d in res.section_2b['per_day'])}  (shipped effective-gap semantics)")
    out.append("")

    # §2d
    out.append("-" * 78)
    out.append("§2d  re-bucketing invariance — per-device union minutes at N=%d across R" % res.section_2d["n_inv"])
    out.append("-" * 78)
    hdr = ["device", "date", "evts"] + [f"R={r}" for r in REBUCKET_RS] + [f"N{res.section_2d['collapse']['n']}/R{res.section_2d['collapse']['r']}"]
    widths = [16, 10, 6] + [6] * len(REBUCKET_RS) + [10]
    out.append(_fmt_row(hdr, widths))
    for d in res.section_2d["per_day"]:
        cols = [d["name"][:16], d["date"], str(d["events"])] + [
            str(d["union_min_by_r"][r]) for r in REBUCKET_RS
        ] + [str(d["collapse_min"])]
        out.append(_fmt_row(cols, widths))
    s2d = res.section_2d
    out.append(_fmt_row(
        ["TOTAL", "", ""] + [str(s2d["agg_union_min_by_r"][r]) for r in REBUCKET_RS] + [str(s2d["collapse"]["union_min"])],
        widths,
    ))
    out.append("")
    out.append(f"  spread across R (N≥R)         = {s2d['spread']}  (small ⇒ rate-independent)")
    out.append(f"  N<R collapse fraction         = {s2d['collapse']['fraction_of_ref']}  (≈0 ⇒ collapse guard needed; matches §2d (ii))")
    out.append("")

    # heartbeat
    out.append("-" * 78)
    out.append("HB   heartbeat-filter replay (#790) — kept session-minutes by bytes floor")
    out.append("-" * 78)
    hb = res.heartbeat
    for thr in sorted(hb["kept_min_by_threshold"]):
        marker = "  <- current" if thr == hb["base_threshold"] else ""
        out.append(f"  bytes floor {thr:>7}: {hb['kept_min_by_threshold'][thr]:>6} min kept{marker}")
    out.append("")
    out.append(f"  delta vs lowering floor       = {hb['delta_low']}  (small ⇒ floor not over-suppressing)")
    out.append(f"  delta vs raising floor        = {hb['delta_high']}  (small ⇒ floor not on the edge)")
    if hb["dropped_by_allowlist_top"]:
        out.append("")
        out.append("  Top FQDNs dropped by the allowlist (allowlist doing real work):")
        for host, n in hb["dropped_by_allowlist_top"][:10]:
            out.append(f"    {n:>5}  {host}")
    if hb["kept_low_bytes_top"]:
        out.append("")
        out.append("  Top kept low-byte FQDNs (candidates to add to the allowlist if keepalive noise):")
        for host, n in hb["kept_low_bytes_top"][:10]:
            out.append(f"    {n:>5}  {host}")
    out.append("")

    # gate
    out.append("=" * 78)
    out.append("GATE")
    out.append("=" * 78)
    for c in res.gate["checks"]:
        flag = "PASS" if c["pass"] else "FAIL"
        out.append(f"  [{flag}] {c['id']:<14} {c['desc']}")
        out.append(f"         {c['detail']}")
    out.append("")
    verdict = "GO — tuned defaults validated" if res.gate["go"] else "NO-GO — defaults need review"
    out.append(f"  >>> {verdict} <<<")
    out.append("")
    return "\n".join(out)


# ── CLI ─────────────────────────────────────────────────────────────────────────


def build_result(ds: Dataset, args: argparse.Namespace) -> TuningResult:
    s2b = run_2b(ds)
    s2d = run_2d(ds)
    hb = run_heartbeat(ds)
    gate = evaluate_gate(
        s2b,
        s2d,
        hb,
        ratio_min=args.ratio_min,
        ratio_max=args.ratio_max,
        bridge_max=args.bridge_max,
        invariance_tol=args.invariance_tol,
        collapse_max=args.collapse_max,
        hb_delta_tol=args.hb_delta_tol,
    )
    return TuningResult(section_2b=s2b, section_2d=s2d, heartbeat=hb, gate=gate)


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("data_dir", type=Path, help="directory of exported prod JSON (see fetch_prod_data.sh)")
    p.add_argument("--json", action="store_true", help="emit machine-readable JSON instead of the text report")
    # gate tolerances — defaults encode the design's conclusions; override to probe.
    p.add_argument("--ratio-min", type=float, default=2.0)
    p.add_argument("--ratio-max", type=float, default=6.0)
    p.add_argument("--bridge-max", type=float, default=1.25)
    p.add_argument("--invariance-tol", type=float, default=0.25)
    p.add_argument("--collapse-max", type=float, default=0.10)
    # The heartbeat floor is a secondary filter; the gate flags only a real cliff
    # (the floor sitting on the dominant traffic mode), not the modest, monotone
    # sensitivity a healthy floor shows. #790's question is "no over/under-suppress
    # cliff over a real week", not "insensitive".
    p.add_argument("--hb-delta-tol", type=float, default=0.35)
    args = p.parse_args(argv)

    ds = Dataset.load(args.data_dir)
    res = build_result(ds, args)

    if args.json:
        print(json.dumps(
            {"section_2b": res.section_2b, "section_2d": res.section_2d, "heartbeat": res.heartbeat, "gate": res.gate},
            indent=2,
        ))
    else:
        print(render(ds, res))
    # non-zero exit on NO-GO so the harness can gate CI / a script.
    return 0 if res.gate["go"] else 1


if __name__ == "__main__":
    sys.exit(main())
